/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.awaitility.Awaitility;
import org.junit.Rule;
import org.junit.Test;
import org.terracotta.angela.client.ClientArray;
import org.terracotta.angela.client.ClientArrayFuture;
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.ConfigTool;
import org.terracotta.angela.client.Tsa;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.support.junit.AngelaOrchestratorRule;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.ClientArrayTopology;
import org.terracotta.angela.common.topology.LicenseType;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.topology.Topology;

import java.util.Collection;
import java.util.concurrent.Callable;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.terracotta.angela.client.config.custom.CustomConfigurationContext.customConfigurationContext;
import static org.terracotta.angela.common.TerracottaConfigTool.configTool;
import static org.terracotta.angela.common.clientconfig.ClientArrayConfig.newClientArrayConfig;
import static org.terracotta.angela.common.distribution.Distribution.distribution;
import static org.terracotta.angela.common.dynamic_cluster.Stripe.stripe;
import static org.terracotta.angela.common.provider.DynamicConfigManager.dynamicCluster;
import static org.terracotta.angela.common.tcconfig.TcConfig.tcConfig;
import static org.terracotta.angela.common.tcconfig.TerracottaServer.server;
import static org.terracotta.angela.common.topology.LicenseType.TERRACOTTA_OS;
import static org.terracotta.angela.common.topology.PackageType.KIT;
import static org.terracotta.angela.common.topology.Version.version;

/**
 * @author Aurelien Broszniowski
 */
@SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
public class GettingStarted {
  private static final String EHCACHE_OS_VERSION = "3.8.1";
  private static final String EHCACHE_VERSION = "3.9-SNAPSHOT";

  // tag::configureCluster[]
  @Rule
  public AngelaOrchestratorRule angelaOrchestratorRule = new AngelaOrchestratorRule(); // <6>

  @Test
  public void configureCluster() throws Exception {
    ConfigurationContext configContext = customConfigurationContext() // <1>
        .tsa(tsa -> tsa // <2>
            .topology(new Topology( // <3>
                distribution(version(EHCACHE_OS_VERSION), PackageType.KIT, LicenseType.TERRACOTTA_OS), // <4>
                tcConfig(version(EHCACHE_OS_VERSION), getClass().getResource("/tc-config-a.xml")))) // <5>
        );

    ClusterFactory factory = angelaOrchestratorRule.newClusterFactory("GettingStarted::configureCluster", configContext); // <6>
    Tsa tsa = factory.tsa() // <7>
        .spawnAll(); // <8>

    factory.close(); // <9>
  }
  // end::configureCluster[]

  @Test
  public void showTsaApi() throws Exception {
    Topology topology = new Topology(
        distribution(version(EHCACHE_OS_VERSION), PackageType.KIT, LicenseType.TERRACOTTA_OS),
        tcConfig(version(EHCACHE_OS_VERSION), getClass().getResource("/tc-config-ap.xml"))
    );
    ConfigurationContext configContext = customConfigurationContext().tsa(tsa -> tsa.topology(topology));

    try (ClusterFactory factory = angelaOrchestratorRule.newClusterFactory("GettingStarted::showTsaApi", configContext)) {
      // tag::showTsaApi[]
      Tsa tsa = factory.tsa() // <1>
          .spawnAll(); // <2>

      TerracottaServer active = tsa.getActive(); // <3>
      Collection<TerracottaServer> actives = tsa.getActives(); // <4>
      TerracottaServer passive = tsa.getPassive(); // <5>
      Collection<TerracottaServer> passives = tsa.getPassives(); // <6>

      tsa.stopAll(); // <7>

      tsa.spawn(active); // <8>
      tsa.spawn(passive);

      tsa.stop(active); // <9>
      Callable<TerracottaServerState> serverState = () -> tsa.getState(passive); // <10>
      Awaitility.await()
          .pollInterval(1, SECONDS)
          .atMost(15, SECONDS)
          .until(serverState, is(TerracottaServerState.STARTED_AS_ACTIVE));
      // end::showTsaApi[]
    }
  }

  @Test
  public void showDynamicTsaApi() throws Exception {
    // tag::showDynamicTsaApi[]
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(context -> context
            .topology(
                new Topology(
                    distribution(version(EHCACHE_VERSION), KIT, TERRACOTTA_OS),
                    dynamicCluster( // <1>
                        stripe(
                            server("server-1")
                                .tsaPort(9410)
                                .tsaGroupPort(9411)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability"),
                            server("server-2")
                                .tsaPort(9510)
                                .tsaGroupPort(9511)
                                .configRepo("terracotta2/repository")
                                .logs("terracotta2/logs")
                                .metaData("terracotta2/metadata")
                                .failoverPriority("availability"))))))
        .configTool(context -> context.configTool(configTool("configTool")));
    try (ClusterFactory factory = angelaOrchestratorRule.newClusterFactory("DynamicClusterTest::testSingleStripeFormation", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.spawnAll(); // <2>
      ConfigTool configTool = factory.configTool();
      configTool.attachAll(); // <3>

      configTool.attachStripe(server("server-3") // <4>
          .tsaPort(9610)
          .tsaGroupPort(9611)
          .configRepo("terracotta3/repository")
          .logs("terracotta3/logs")
          .metaData("terracotta3/metadata")
          .failoverPriority("availability"));

      TerracottaServer toDetach = tsa.getServer(0, 1);
      configTool.detachNode(0, 1); // <5>
      tsa.stop(toDetach); // <6>

      configTool.activate(); // <7>
    }
    // end::showDynamicTsaApi[]
  }

  @Test
  public void runClient() throws Exception {
    // tag::runClient[]
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray // <1>
            .clientArrayTopology(new ClientArrayTopology( // <2>
                distribution(version(EHCACHE_OS_VERSION), PackageType.KIT, LicenseType.TERRACOTTA_OS), // <3>
                newClientArrayConfig().named("localhost-1").named("localhost-2")) // <4>
            )
        );
    ClusterFactory factory = angelaOrchestratorRule.newClusterFactory("GettingStarted::runClient", configContext);
    ClientArray clientArray = factory.clientArray(0); // <5>
    ClientArrayFuture f = clientArray.executeOnAll((context) -> System.out.println("Hello")); // <6>
    f.get(); // <7>

    factory.close();
    // end::runClient[]
  }
}
