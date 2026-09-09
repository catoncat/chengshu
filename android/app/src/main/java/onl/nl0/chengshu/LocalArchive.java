package onl.nl0.chengshu;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

/** Immutable blobs + one atomically published manifest per article. No global index to truncate. */
final class LocalArchive {
  private static final Object PROCESS_LOCK = new Object();
  private final File root;
  private final Runnable beforePublish;

  LocalArchive(File root) { this(root, () -> {}); }

  // Fault-injection seam: tests stop a real process between blob durability and publication.
  LocalArchive(File root, Runnable beforePublish) {
    this.root = root;
    this.beforePublish = beforePublish;
  }

  interface Operation<T> { T run() throws IOException; }

  private <T> T locked(Operation<T> operation) throws IOException {
    synchronized (PROCESS_LOCK) {
      Files.createDirectories(root.toPath());
      try (RandomAccessFile lock = new RandomAccessFile(new File(root, ".lock"), "rw");
          FileChannel channel = lock.getChannel(); FileLock ignored = channel.lock()) {
        return operation.run();
      }
    }
  }

  Properties get(String id) throws IOException { return locked(() -> read(id)); }

  Map<String, Properties> list() throws IOException {
    return locked(() -> {
      Map<String, Properties> result = new LinkedHashMap<>();
      File[] dirs = root.listFiles(File::isDirectory);
      if (dirs == null) throw new IOException("无法读取本地保存目录");
      for (File dir : dirs) {
        if (!validId(dir.getName())) continue;
        Properties record = read(dir.getName());
        if (!record.isEmpty()) result.put(dir.getName(), record);
      }
      return result;
    });
  }

  Properties put(String id, Map<String, String> metadata, Map<String, byte[]> blobs)
      throws IOException {
    return locked(() -> {
      File dir = directory(id);
      Files.createDirectories(dir.toPath());
      Properties record = read(id);
      record.remove("deleted");
      for (Map.Entry<String, String> entry : metadata.entrySet()) {
        if (entry.getKey().startsWith("blob.") || entry.getKey().equals("version"))
          throw new IOException("保留的元数据字段");
        record.setProperty(entry.getKey(), entry.getValue());
      }
      // Never overwrite a published blob, including when the title changes.
      for (Map.Entry<String, byte[]> entry : blobs.entrySet()) {
        String kind = entry.getKey();
        byte[] body = entry.getValue();
        if (!kind.matches("[a-z0-9_-]{1,32}") || body == null || body.length == 0)
          throw new IOException("文件为空或格式无效，未覆盖已保存的内容");
        String hash = digest(body);
        String name = hash + "." + kind;
        File blob = new File(dir, name);
        if (!blob.isFile() || !digest(blob).equals(hash))
          atomicWrite(blob, body);
        record.setProperty("blob." + kind, name);
      }
      record.setProperty("version", "1");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      record.store(bytes, "Chengshu article manifest");
      beforePublish.run();
      atomicWrite(new File(dir, "record.properties"), bytes.toByteArray());
      return record;
    });
  }

  File file(String id, String kind) throws IOException {
    return locked(() -> {
      return fileFromRecord(id, read(id), kind);
    });
  }

  File fileFromRecord(String id, Properties record, String kind) throws IOException {
      if ("true".equals(record.getProperty("deleted"))) return null;
      String name = record.getProperty("blob." + kind);
      if (name == null) return null;
      if (!name.matches("[0-9a-f]{64}\\.[a-z0-9_-]{1,32}"))
        throw new IOException("本地文件记录无效");
      File file = new File(directory(id), name);
      if (!file.isFile() || file.length() == 0) throw new IOException("本地文件缺失");
      if (!name.substring(0, 64).equals(digest(file)))
        throw new IOException("本地文件校验失败，原始记录仍保留");
      return file;
  }

  void delete(String id) throws IOException {
    locked(() -> {
      File dir = directory(id);
      Files.createDirectories(dir.toPath());
      // Tombstone first: an interrupted delete must not resurrect legacy entries.
      atomicWrite(new File(dir, "record.properties"), "version=1\ndeleted=true\n".getBytes("UTF-8"));
      File[] files = dir.listFiles();
      if (files == null) throw new IOException("无法删除本地文件");
      for (File file : files) {
        if (!file.getName().equals("record.properties")) Files.deleteIfExists(file.toPath());
      }
      return null;
    });
  }

  private Properties read(String id) throws IOException {
    File manifest = new File(directory(id), "record.properties");
    Properties result = new Properties();
    if (!manifest.exists()) return result;
    if (!manifest.isFile() || manifest.length() > 1024 * 1024)
      throw new IOException("本地保存记录损坏");
    try (InputStream in = new FileInputStream(manifest)) { result.load(in); }
    catch (IllegalArgumentException e) { throw new IOException("本地保存记录损坏", e); }
    if (!"1".equals(result.getProperty("version"))) throw new IOException("无法识别本地保存记录");
    return result;
  }

  private File directory(String id) throws IOException {
    if (!validId(id)) throw new IOException("无效文章标识");
    return new File(root, id);
  }

  private static boolean validId(String id) {
    return id != null && id.matches("[a-zA-Z0-9_-]{1,128}");
  }

  static void atomicWrite(File target, byte[] body) throws IOException {
    File parent = target.getAbsoluteFile().getParentFile();
    Files.createDirectories(parent.toPath());
    File temporary = File.createTempFile(".pending-", ".tmp", parent);
    try {
      try (FileOutputStream out = new FileOutputStream(temporary)) {
        out.write(body);
        out.getFD().sync();
      }
      // No copy/truncate fallback: report failure rather than weakening atomicity.
      Files.move(temporary.toPath(), target.toPath(),
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temporary.toPath()); }
  }

  static String digest(File file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new FileInputStream(file)) {
        byte[] buffer = new byte[16384]; int n;
        while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
      }
      return hex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(64);
    for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }
  static String digest(byte[] bytes) {
    try {
      byte[] value = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder(64);
      for (byte b : value) result.append(String.format(Locale.ROOT, "%02x", b & 255));
      return result.toString();
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
}
