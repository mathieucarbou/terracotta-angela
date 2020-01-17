package com.terracottatech.qa.angela.client;

import com.terracottatech.qa.angela.agent.Agent;
import com.terracottatech.qa.angela.client.config.ClientArrayConfigurationContext;
import com.terracottatech.qa.angela.client.config.ConfigurationContext;
import com.terracottatech.qa.angela.client.config.MonitoringConfigurationContext;
import com.terracottatech.qa.angela.client.config.TmsConfigurationContext;
import com.terracottatech.qa.angela.client.config.TsaConfigurationContext;
import com.terracottatech.qa.angela.client.remote.agent.RemoteAgentLauncher;
import com.terracottatech.qa.angela.common.cluster.Cluster;
import com.terracottatech.qa.angela.common.metrics.HardwareMetric;
import com.terracottatech.qa.angela.common.metrics.MonitoringCommand;
import com.terracottatech.qa.angela.common.topology.InstanceId;
import com.terracottatech.qa.angela.common.util.DirectoryUtils;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteException;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.logger.NullLogger;
import org.apache.ignite.logger.slf4j.Slf4jLogger;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.terracottatech.qa.angela.agent.Agent.IGNITE_DIR;
import static com.terracottatech.qa.angela.common.AngelaProperties.IGNITE_LOGGING;
import static com.terracottatech.qa.angela.common.util.IpUtils.encloseInBracketsIfIpv6;

public class ClusterFactory implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClusterFactory.class);

  private static final String TSA = "tsa";
  private static final String TMS = "tms";
  private static final String CLIENT_ARRAY = "clientArray";
  private static final String MONITOR = "monitor";
  private static final DateTimeFormatter PATH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-hhmmss");

  private final List<AutoCloseable> controllers = new ArrayList<>();
  private final String idPrefix;
  private final AtomicInteger instanceIndex;
  private final Map<String, Collection<InstanceId>> nodeToInstanceId = new HashMap<>();
  private final ConfigurationContext configurationContext;

  private Ignite ignite;
  private Agent.Node localAgent;
  private transient RemoteAgentLauncher remoteAgentLauncher;
  private InstanceId monitorInstanceId;

  public ClusterFactory(String idPrefix, ConfigurationContext configurationContext) {
    // Using UTC to have consistent layout even in case of timezone skew between client and server.
    this.idPrefix = idPrefix + "-" + LocalDateTime.now(ZoneId.of("UTC")).format(PATH_FORMAT);
    this.instanceIndex = new AtomicInteger();
    this.configurationContext = configurationContext;
    this.remoteAgentLauncher = configurationContext.remoting().buildRemoteAgentLauncher();
  }

  private InstanceId init(String type, Collection<String> targetServerNames) {
    if (targetServerNames.isEmpty()) {
      throw new IllegalArgumentException("Cannot initialize with 0 server");
    }

    boolean foundLocal = isAnyLocal(targetServerNames);
    boolean allLocal = areAllLocal(targetServerNames);
    if (foundLocal && !allLocal) {
      throw new IllegalArgumentException("Cannot mix local and non-local servers : " + targetServerNames);
    }

    if (!foundLocal && isAnyLocal(nodeToInstanceId.keySet())) {
      throw new IllegalArgumentException("local agent started, connection to remote agents '" + targetServerNames + "' is not possible");
    }

    if (foundLocal) {
      if (nodeToInstanceId.size() > 1 || (nodeToInstanceId.size() == 1 && !isAnyLocal(nodeToInstanceId.keySet()))) {
        throw new IllegalArgumentException("remote agents '" + nodeToInstanceId.keySet() + "' already started, connecting to local is not possible");
      }
    }

    InstanceId instanceId = new InstanceId(idPrefix + "-" + instanceIndex.getAndIncrement(), type);
    for (String targetServerName : targetServerNames) {
      if (targetServerName == null) {
        throw new IllegalArgumentException("Cannot initialize with a null server name");
      }

      if (!isLocal(targetServerName)) {
        Set<String> nodesToJoin = new HashSet<>();
        nodesToJoin.addAll(nodeToInstanceId.keySet());
        nodesToJoin.addAll(targetServerNames);
        LOGGER.info("Target server name: {} is not local. Using remoting agent for connection to: {}", targetServerName, nodesToJoin);
        remoteAgentLauncher.remoteStartAgentOn(targetServerName, nodesToJoin);
      }

      nodeToInstanceId.compute(targetServerName, (s, instanceIds) -> {
        if (instanceIds == null) {
          return Collections.singleton(instanceId);
        }
        List<InstanceId> list = new ArrayList<>(instanceIds);
        list.add(instanceId);
        return Collections.unmodifiableCollection(list);
      });
    }

    if (ignite == null) {
      if (allLocal) {
        LOGGER.info("spawning local agent");
        localAgent = new Agent.Node(targetServerNames.iterator().next(), Collections.emptyList());
      }

      TcpDiscoverySpi spi = new TcpDiscoverySpi();
      TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);
      ipFinder.setAddresses(targetServerNames.stream()
          .map(targetServerName -> encloseInBracketsIfIpv6(targetServerName) + ":40000")
          .collect(Collectors.toList()));
      spi.setJoinTimeout(10000);
      spi.setIpFinder(ipFinder);

      IgniteConfiguration cfg = new IgniteConfiguration();
      cfg.setDiscoverySpi(spi);
      cfg.setClientMode(true);
      DirectoryUtils.createAndValidateDir(IGNITE_DIR);
      cfg.setIgniteHome(IGNITE_DIR.resolve(System.getProperty("user.name")).toString());
      cfg.setPeerClassLoadingEnabled(true);
      boolean enableLogging = Boolean.getBoolean(IGNITE_LOGGING.getValue());
      cfg.setGridLogger(enableLogging ? new Slf4jLogger() : new NullLogger());
      cfg.setIgniteInstanceName("Instance@" + instanceId);
      cfg.setMetricsLogFrequency(0);

      try {
        this.ignite = Ignition.start(cfg);
      } catch (IgniteException e) {
        throw new RuntimeException("Cannot start angela; error connecting to agents : " + targetServerNames, e);
      }
    }

    return instanceId;
  }

  private static boolean isLocal(String targetServerName) {
    InetAddress address;
    try {
      address = InetAddress.getByName(targetServerName);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
    return address.isLoopbackAddress() || address.isLinkLocalAddress();
  }

  private static boolean areAllLocal(Collection<String> targetServerNames) {
    for (String targetServerName : targetServerNames) {
      if (!isLocal(targetServerName)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAnyLocal(Collection<String> targetServerNames) {
    for (String targetServerName : targetServerNames) {
      if (isLocal(targetServerName)) {
        return true;
      }
    }
    return false;
  }

  public Cluster cluster() {
    if (ignite == null) {
      throw new IllegalStateException("No cluster component started");
    }
    return new Cluster(ignite);
  }

  public Tsa tsa() {
    TsaConfigurationContext tsaConfigurationContext = configurationContext.tsa();
    InstanceId instanceId = init(TSA, tsaConfigurationContext.getTopology().getServersHostnames());

    Tsa tsa = new Tsa(ignite, instanceId, tsaConfigurationContext);
    controllers.add(tsa);
    return tsa;
  }

  public Tms tms() {
    TmsConfigurationContext tmsConfigurationContext = configurationContext.tms();
    InstanceId instanceId = init(TMS, Collections.singletonList(tmsConfigurationContext.getHostname()));

    Tms tms = new Tms(ignite, instanceId, tmsConfigurationContext);
    controllers.add(tms);
    return tms;
  }

  public ClientArray clientArray() {
    ClientArrayConfigurationContext clientArrayConfigurationContext = configurationContext.clientArray();
    init(CLIENT_ARRAY, clientArrayConfigurationContext.getClientArrayTopology().getClientHostnames());

    ClientArray clientArray = new ClientArray(ignite, () -> init(CLIENT_ARRAY, clientArrayConfigurationContext.getClientArrayTopology()
        .getClientHostnames()), clientArrayConfigurationContext);
    controllers.add(clientArray);
    return clientArray;
  }

  public ClusterMonitor monitor() {
    MonitoringConfigurationContext monitoringConfigurationContext = configurationContext.monitoring();
    if (monitoringConfigurationContext == null) {
      throw new IllegalStateException("MonitoringConfigurationContext has not been registered");
    }

    Map<HardwareMetric, MonitoringCommand> commands = monitoringConfigurationContext.commands();
    Set<String> hostnames = configurationContext.allHostnames();

    if (monitorInstanceId == null) {
      monitorInstanceId = init(MONITOR, hostnames);
      ClusterMonitor clusterMonitor = new ClusterMonitor(ignite, monitorInstanceId, hostnames, commands);
      controllers.add(clusterMonitor);
      return clusterMonitor;
    } else {
      return new ClusterMonitor(ignite, monitorInstanceId, hostnames, commands);
    }
  }

  @Override
  public void close() throws IOException {
    List<Exception> exceptions = new ArrayList<>();

    for (AutoCloseable controller : controllers) {
      try {
        controller.close();
      } catch (Exception e) {
        exceptions.add(e);
      }
    }
    controllers.clear();

    if (ignite != null) {
      try {
        ignite.close();
      } catch (Exception e) {
        exceptions.add(e);
      }
      ignite = null;
    }
    nodeToInstanceId.clear();

    monitorInstanceId = null;

    try {
      remoteAgentLauncher.close();
    } catch (Exception e) {
      exceptions.add(e);
    }
    remoteAgentLauncher = null;

    if (localAgent != null) {
      try {
        LOGGER.info("shutting down local agent");
        localAgent.close();
      } catch (Exception e) {
        exceptions.add(e);
      }
      localAgent = null;
    }

    if (!exceptions.isEmpty()) {
      IOException ioException = new IOException("Error while closing down Cluster Factory prefixed with " + idPrefix);
      exceptions.forEach(ioException::addSuppressed);
      throw ioException;
    }
  }
}
