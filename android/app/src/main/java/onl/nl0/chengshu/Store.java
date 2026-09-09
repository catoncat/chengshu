package onl.nl0.chengshu;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;

/**
 * File-backed transactional catalog matching TECHNICAL_DESIGN tables.
 * SQLiteOpenHelper is not used: JVM crash tests cannot open Android SQLite.
 */
final class Store {
  private static final Object PROCESS_LOCK = new Object();
  private final File root;
  final BlobStore blobs;

  Store(File root) {
    this.root = root;
    this.blobs = new BlobStore(new File(root, "blobs"));
  }

  interface Tx<T> { T run() throws Exception; }

  <T> T locked(Tx<T> tx) throws Exception {
    synchronized (PROCESS_LOCK) {
      Files.createDirectories(root.toPath());
      try (RandomAccessFile raf = new RandomAccessFile(new File(root, ".lock"), "rw");
          FileChannel channel = raf.getChannel(); FileLock ignored = channel.lock()) {
        return tx.run();
      }
    }
  }

  JSONObject get(String table, String id) throws Exception {
    File file = record(table, id);
    if (!file.isFile()) return null;
    return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
  }

  void put(String table, String id, JSONObject row) throws Exception {
    LocalArchive.atomicWrite(record(table, id), row.toString().getBytes(StandardCharsets.UTF_8));
  }

  void remove(String table, String id) throws Exception {
    Files.deleteIfExists(record(table, id).toPath());
  }

  List<JSONObject> list(String table) throws Exception {
    File dir = new File(root, table);
    File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
    List<JSONObject> rows = new ArrayList<>();
    if (files == null) return rows;
    Arrays.sort(files);
    for (File file : files) {
      try {
        rows.add(new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
      } catch (Exception e) {
        JSONObject bad = new JSONObject();
        bad.put("id", file.getName().replace(".json", ""));
        bad.put("corrupt", true);
        rows.add(bad);
      }
    }
    return rows;
  }

  private File record(String table, String id) throws IOException {
    if (table == null || !table.matches("[a-z_]+")) throw new IOException("无效表");
    if (id == null || id.isEmpty() || id.length() > 500) throw new IOException("无效标识");
    File dir = new File(root, table);
    Files.createDirectories(dir.toPath());
    return new File(dir, LocalArchive.digest(id.getBytes(StandardCharsets.UTF_8)) + ".json");
  }
}
