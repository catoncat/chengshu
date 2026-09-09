package onl.nl0.chengshu;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Hash-addressed immutable files. Does not decide whether a job succeeded. */
final class BlobStore {
  private final File root;

  BlobStore(File root) { this.root = root; }

  File put(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length == 0) throw new IOException("文件为空，未覆盖已保存的内容");
    String hash = LocalArchive.digest(bytes);
    Files.createDirectories(root.toPath());
    File file = new File(root, hash);
    if (file.isFile() && file.length() == bytes.length && LocalArchive.digest(file).equals(hash))
      return file;
    File staging = new File(root, hash + ".part");
    LocalArchive.atomicWrite(staging, bytes);
    if (!staging.renameTo(file) && !(file.isFile() && LocalArchive.digest(file).equals(hash))) {
      Files.deleteIfExists(staging.toPath());
      throw new IOException("无法写入本地文件");
    }
    Files.deleteIfExists(staging.toPath());
    return file;
  }

  File file(String hash) {
    if (hash == null || !hash.matches("[0-9a-f]{64}")) return null;
    File file = new File(root, hash);
    return file.isFile() ? file : null;
  }

  List<File> orphans(Set<String> referenced) {
    File[] files = root.listFiles(File::isFile);
    List<File> extra = new ArrayList<>();
    if (files == null) return extra;
    for (File file : files) {
      if (file.getName().endsWith(".part")) extra.add(file);
      else if (file.getName().matches("[0-9a-f]{64}") && !referenced.contains(file.getName())) extra.add(file);
    }
    return extra;
  }
}
