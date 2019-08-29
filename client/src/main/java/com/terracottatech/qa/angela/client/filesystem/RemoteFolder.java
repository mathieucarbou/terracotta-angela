package com.terracottatech.qa.angela.client.filesystem;

import com.terracottatech.qa.angela.agent.Agent;
import com.terracottatech.qa.angela.client.util.IgniteClientHelper;
import org.apache.commons.io.IOUtils;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteException;
import org.apache.ignite.lang.IgniteCallable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.stream.Collectors.toList;

public class RemoteFolder extends RemoteFile {
  public RemoteFolder(Ignite ignite, String nodeName, String parentName, String name) {
    super(ignite, nodeName, parentName, name);
  }

  public List<RemoteFile> list() {
    String absoluteName = getAbsoluteName();
    List<String> remoteFiles = IgniteClientHelper.executeRemotely(ignite, nodeName, (IgniteCallable<List<String>>) () -> Agent.CONTROLLER.listFiles(absoluteName));
    List<String> remoteFolders = IgniteClientHelper.executeRemotely(ignite, nodeName, (IgniteCallable<List<String>>) () -> Agent.CONTROLLER.listFolders(absoluteName));

    List<RemoteFile> result = new ArrayList<>();
    result.addAll(remoteFiles.stream().map(s -> new RemoteFile(ignite, nodeName, getAbsoluteName(), s)).collect(toList()));
    result.addAll(remoteFolders.stream().map(s -> new RemoteFolder(ignite, nodeName, getAbsoluteName(), s)).collect(toList()));
    return result;
  }

  public void upload(File localFile) throws IOException {
    if (localFile.isDirectory()) {
      uploadFolder(".", localFile);
    } else {
      try (FileInputStream fis = new FileInputStream(localFile)) {
        upload(localFile.getName(), fis);
      }
    }
  }

  private void uploadFolder(String parentName, File folder) throws IOException {
    File[] files = folder.listFiles();
    for (File f : files) {
      String currentName = parentName + "/" + f.getName();
      if (f.isDirectory()) {
        uploadFolder(currentName, f);
      } else {
        try (FileInputStream fis = new FileInputStream(f)) {
          upload(currentName, fis);
        }
      }
    }
  }

  public void upload(String remoteFilename, URL localResourceUrl) throws IOException {
    try (InputStream in = localResourceUrl.openStream()) {
      upload(remoteFilename, in);
    }
  }

  public void upload(String remoteFilename, InputStream localStream) throws IOException {
    byte[] data = IOUtils.toByteArray(localStream);
    String filename = getAbsoluteName() + "/" + remoteFilename;
    IgniteClientHelper.executeRemotely(ignite, nodeName, () -> Agent.CONTROLLER.uploadFile(filename, data));
  }

  @Override
  public void downloadTo(File localPath) throws IOException {
    String foldername = getAbsoluteName();
    byte[] bytes;
    try {
      bytes = IgniteClientHelper.executeRemotely(ignite, nodeName, () -> Agent.CONTROLLER.downloadFolder(foldername));
    } catch (IgniteException ie) {
      throw new IOException("Error downloading remote folder '" + foldername + "' into local folder '" + localPath + "'", ie);
    }

    localPath.mkdirs();
    if (!localPath.isDirectory()) {
      throw new IllegalArgumentException("Destination path '" + localPath + "' is not a folder or could not be created");
    }

    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      while (true) {
        ZipEntry nextEntry = zis.getNextEntry();
        if (nextEntry == null) {
          break;
        }
        String name = nextEntry.getName();
        File file = new File(localPath, name);
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
          IOUtils.copy(zis, fos);
        }
      }
    }
  }

  @Override
  public String toString() {
    return super.toString() + "/";
  }
}
