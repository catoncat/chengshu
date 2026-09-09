package onl.nl0.chengshu;

import android.content.Context;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class Library {
  private final File root;
  private final LocalArchive archive;

  Library(Context context) { this(context.getFilesDir()); }

  Library(File filesDir) {
    root = new File(filesDir, "library");
    archive = new LocalArchive(new File(root, "vault"));
  }

  List<Item> list() {
    try {
      Map<String, Properties> records = archive.list();
      List<Item> items = new ArrayList<>();
      for (Map.Entry<String, Properties> entry : records.entrySet()) {
        if (!"true".equals(entry.getValue().getProperty("deleted"))
            && entry.getValue().stringPropertyNames().stream().anyMatch(k -> k.startsWith("blob.") && !k.equals("blob.source")))
          items.add(item(entry.getKey(), entry.getValue()));
      }
      // Read old installations without eagerly copying their entire library.
      for (Item legacy : legacyIndex()) if (!records.containsKey(legacy.id)) items.add(legacy);
      items.sort((a, b) -> Long.compare(b.updated, a.updated));
      return items;
    } catch (IOException e) { throw new UncheckedIOException(e); }
  }

  Item findByUrl(String url) {
    String id = idFor(normalizeUrl(url));
    for (Item item : list()) if (item.id.equals(id)) return item;
    return null;
  }

  File file(Item item, Format format) {
    try {
      if (item.modern) {
        File file = archive.file(item.id, format.id);
        return file == null ? new File(root, "missing/" + item.id + format.ext) : file;
      }
      File dir = new File(root, item.id);
      File named = new File(dir, fileStem(item.title) + format.ext);
      if (named.isFile()) return named;
      File[] matches = dir.listFiles((d, n) -> n.endsWith(format.ext));
      if (matches != null) {
        for (File match : matches) if (match.getName().equals("body" + format.ext)) return match;
        if (matches.length == 1) return matches[0];
      }
      return named;
    } catch (IOException e) { throw new UncheckedIOException(e); }
  }

  boolean has(Item item, Format format) {
    try { File file = file(item, format); return file.isFile() && file.length() > 0; }
    catch (UncheckedIOException e) { return false; }
  }

  Item save(String url, String title, Format format, byte[] body) throws Exception {
    return save(url, title, format, body, "");
  }

  Item save(String url, String title, Format format, byte[] body, String warning) throws Exception {
    migrate(url);
    String normalized = normalizeUrl(url);
    String id = idFor(normalized);
    Map<String, String> meta = metadata(normalized, title);
    meta.put("lastFormat", format.id);
    meta.put("warning." + format.id, warning);
    return item(id, archive.put(id, meta, Collections.singletonMap(format.id, body)));
  }

  void saveSnapshot(String url, PageExtractor.Article article) throws Exception {
    migrate(url);
    String normalized = normalizeUrl(url);
    Map<String, String> meta = metadata(normalized, article.title);
    meta.remove("title");
    meta.remove("updated");
    meta.put("sourceUpdated", Long.toString(System.currentTimeMillis()));
    meta.put("sourceTitle", article.title);
    meta.put("sourceByline", article.byline);
    meta.put("sourceUrl", article.sourceUrl.isEmpty() ? url : article.sourceUrl);
    archive.put(idFor(normalized), meta,
        Collections.singletonMap("source", article.content.getBytes(StandardCharsets.UTF_8)));
  }

  PageExtractor.Article snapshot(String url) throws Exception {
    String id = idFor(normalizeUrl(url));
    Properties meta = archive.get(id);
    File source = archive.fileFromRecord(id, meta, "source");
    if (source == null) return null;
    return new PageExtractor.Article(meta.getProperty("sourceTitle", ""),
        meta.getProperty("sourceByline", ""), new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8), meta.getProperty("sourceUrl", url));
  }

  String warning(Item item, Format format) {
    try { return archive.get(item.id).getProperty("warning." + format.id, ""); }
    catch (IOException e) { return "无法读取内容检查结果"; }
  }

  void markOpened(Item item, Format format) {
    try {
      migrate(item.url);
      Map<String, String> meta = new HashMap<>();
      meta.put("lastFormat", format.id);
      meta.put("updated", Long.toString(System.currentTimeMillis()));
      archive.put(item.id, meta, Collections.emptyMap());
    } catch (Exception e) { throw new IllegalStateException("无法更新本地记录", e); }
  }

  void delete(Item item) {
    try {
      archive.delete(item.id);
      File legacy = new File(root, item.id);
      File[] files = legacy.listFiles();
      if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
      Files.deleteIfExists(legacy.toPath());
    } catch (IOException e) { throw new UncheckedIOException(e); }
  }

  /** Zip every saved format and source snapshot. Does not delete originals. */
  File exportBackup(File destDir) throws IOException {
    Files.createDirectories(destDir.toPath());
    File zipFile = new File(destDir, "chengshu-backup.zip");
    int files = 0;
    JSONObject manifest = new JSONObject();
    JSONArray itemsJson = new JSONArray();
    try {
      manifest.put("version", 1);
      manifest.put("app", "chengshu");
      manifest.put("createdAt", System.currentTimeMillis());
    } catch (Exception e) {
      throw new IOException("无法写入备份清单", e);
    }
    try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new FileOutputStream(zipFile))) {
      for (Item item : list()) {
        String stem = fileStem(item.title);
        String folder = item.id + "/";
        JSONObject row = new JSONObject();
        JSONArray filesJson = new JSONArray();
        try {
          row.put("id", item.id);
          row.put("url", item.url == null ? "" : item.url);
          row.put("title", item.title == null ? "" : item.title);
          row.put("host", item.host == null ? "" : item.host);
          row.put("lastFormat", item.lastFormat == null ? "" : item.lastFormat);
        } catch (Exception e) {
          throw new IOException("无法写入备份清单", e);
        }
        for (String formatId : item.formats) {
          Format format = Format.of(formatId);
          File file = file(item, format);
          if (file == null || !file.isFile() || file.length() == 0) continue;
          byte[] body = Files.readAllBytes(file.toPath());
          zip.putNextEntry(new java.util.zip.ZipEntry(folder + stem + format.ext));
          zip.write(body);
          zip.closeEntry();
          files++;
          try {
            JSONObject f = new JSONObject();
            f.put("name", stem + format.ext);
            f.put("format", format.id);
            f.put("sha256", LocalArchive.digest(body));
            filesJson.put(f);
          } catch (Exception e) {
            throw new IOException("无法写入备份清单", e);
          }
        }
        PageExtractor.Article snap = null;
        try { snap = snapshot(item.url); } catch (Exception ignored) { /* still export the books */ }
        if (snap != null && snap.content != null && !snap.content.isEmpty()) {
          byte[] html = snap.content.getBytes(StandardCharsets.UTF_8);
          zip.putNextEntry(new java.util.zip.ZipEntry(folder + stem + ".source.html"));
          zip.write(html);
          zip.closeEntry();
          files++;
          try {
            JSONObject f = new JSONObject();
            f.put("name", stem + ".source.html");
            f.put("format", "source");
            f.put("sha256", LocalArchive.digest(html));
            filesJson.put(f);
          } catch (Exception e) {
            throw new IOException("无法写入备份清单", e);
          }
        }
        try {
          row.put("files", filesJson);
          itemsJson.put(row);
        } catch (Exception e) {
          throw new IOException("无法写入备份清单", e);
        }
      }
      try { manifest.put("items", itemsJson); }
      catch (Exception e) { throw new IOException("无法写入备份清单", e); }
      zip.putNextEntry(new java.util.zip.ZipEntry("chengshu-backup.json"));
      zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (org.json.JSONException e) {
      Files.deleteIfExists(zipFile.toPath());
      throw new IOException("无法写入备份清单", e);
    }
    if (files == 0) {
      Files.deleteIfExists(zipFile.toPath());
      throw new IOException("没有可导出的文件");
    }
    return zipFile;
  }

  static final class BackupReport {
    int restored;
    int skippedSame;
    int skippedConflict;
    int rejected;
    String summary() {
      if (restored == 0 && skippedSame == 0 && skippedConflict == 0)
        return "没有可恢复的文件";
      StringBuilder text = new StringBuilder();
      if (restored > 0) text.append("恢复了 ").append(restored).append(" 个文件");
      if (skippedSame > 0) {
        if (text.length() > 0) text.append("，");
        text.append(skippedSame).append(" 个已有相同内容");
      }
      if (skippedConflict > 0) {
        if (text.length() > 0) text.append("，");
        text.append(skippedConflict).append(" 个因已有不同版本而跳过");
      }
      if (rejected > 0) {
        if (text.length() > 0) text.append("，");
        text.append(rejected).append(" 个无法识别");
      }
      return text.toString();
    }
  }

  BackupReport importBackup(File zipFile) throws IOException {
    if (zipFile == null || !zipFile.isFile()) throw new IOException("找不到备份文件");
    validateBackupZip(zipFile);
    JSONObject manifest = readBackupManifest(zipFile);
    BackupReport report = new BackupReport();
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
      java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        java.util.zip.ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) continue;
        String name = entry.getName().replace('\\', '/');
        if ("chengshu-backup.json".equals(name)) continue;
        if (unsafeBackupPath(name)) {
          report.rejected++;
          continue;
        }
        boolean source = name.endsWith(".source.html");
        Format format = source ? null : formatOfBackupName(name);
        if (!source && format == null) {
          report.rejected++;
          continue;
        }
        int slash = name.indexOf('/');
        String folder = slash > 0 ? name.substring(0, slash) : "";
        if (folder.isEmpty()) {
          report.rejected++;
          continue;
        }
        byte[] body = readBackupEntry(zip, entry, 12 * 1024 * 1024);
        JSONObject meta = manifestItem(manifest, folder);
        String url = meta != null ? meta.optString("url") : "";
        String title = meta != null ? meta.optString("title") : backupStem(name);
        if (title == null || title.isEmpty()) title = backupStem(name);
        try {
          if (source) {
            PageExtractor.Article existing = url.isEmpty() ? null : snapshot(url);
            if (existing != null && existing.content != null
                && java.util.Arrays.equals(existing.content.getBytes(StandardCharsets.UTF_8), body)) {
              report.skippedSame++;
              continue;
            }
            if (url.isEmpty()) url = "https://0nl.onl/restored/" + folder;
            saveSnapshot(url, new PageExtractor.Article(title, "",
                new String(body, StandardCharsets.UTF_8), url));
            report.restored++;
            continue;
          }
          if (url.isEmpty()) {
            Item byId = findById(folder);
            if (byId != null && has(byId, format)) {
              byte[] have = Files.readAllBytes(file(byId, format).toPath());
              if (java.util.Arrays.equals(have, body)) report.skippedSame++;
              else report.skippedConflict++;
              continue;
            }
            restoreDirect(folder, byId != null ? byId.url : "", title, format, body);
            report.restored++;
            continue;
          }
          Item existing = findByUrl(url);
          if (existing != null && has(existing, format)) {
            byte[] have = Files.readAllBytes(file(existing, format).toPath());
            if (java.util.Arrays.equals(have, body)) report.skippedSame++;
            else report.skippedConflict++;
            continue;
          }
          save(url, title, format, body);
          report.restored++;
        } catch (Exception e) {
          report.rejected++;
        }
      }
    }
    if (report.restored + report.skippedSame + report.skippedConflict == 0)
      throw new IOException("没有可恢复的文件");
    return report;
  }

  static boolean unsafeBackupPath(String name) {
    return EpubInspect.unsafePath(name);
  }

  private Item findById(String id) {
    if (id == null || id.isEmpty()) return null;
    for (Item item : list()) if (id.equals(item.id)) return item;
    return null;
  }

  private Item restoreDirect(String id, String url, String title, Format format, byte[] body)
      throws Exception {
    Map<String, String> meta = metadata(url == null ? "" : url, title);
    meta.put("lastFormat", format.id);
    return item(id, archive.put(id, meta, Collections.singletonMap(format.id, body)));
  }

  private static void validateBackupZip(File zipFile) throws IOException {
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
      if (zip.size() > 500) throw new IOException("备份文件条目过多");
      long total = 0;
      java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        java.util.zip.ZipEntry entry = entries.nextElement();
        String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
        if (unsafeBackupPath(name)) throw new IOException("备份包含不安全的路径");
        long size = entry.getSize();
        if (size > 12L * 1024 * 1024) throw new IOException("备份里有过大的文件");
        if (size > 0) total += size;
        if (total > 80L * 1024 * 1024) throw new IOException("备份解压后过大");
      }
    } catch (java.util.zip.ZipException e) {
      throw new IOException("无法读取备份文件", e);
    }
  }

  private static byte[] readBackupEntry(java.util.zip.ZipFile zip, java.util.zip.ZipEntry entry, int max)
      throws IOException {
    long size = entry.getSize();
    if (size > max) throw new IOException("备份里有过大的文件");
    try (InputStream in = zip.getInputStream(entry);
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[16384];
      int n;
      int total = 0;
      while ((n = in.read(buf)) >= 0) {
        total += n;
        if (total > max) throw new IOException("备份里有过大的文件");
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  private static JSONObject readBackupManifest(File zipFile) {
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
      java.util.zip.ZipEntry entry = zip.getEntry("chengshu-backup.json");
      if (entry == null) return null;
      byte[] body = readBackupEntry(zip, entry, 1024 * 1024);
      return new JSONObject(new String(body, StandardCharsets.UTF_8));
    } catch (Exception ignored) {
      return null;
    }
  }

  private static JSONObject manifestItem(JSONObject manifest, String id) {
    if (manifest == null || id == null) return null;
    JSONArray items = manifest.optJSONArray("items");
    if (items == null) return null;
    for (int i = 0; i < items.length(); i++) {
      JSONObject row = items.optJSONObject(i);
      if (row != null && id.equals(row.optString("id"))) return row;
    }
    return null;
  }

  private static Format formatOfBackupName(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    for (Format format : Format.ALL) {
      if (lower.endsWith(format.ext)) return format;
    }
    return null;
  }

  private static String backupStem(String name) {
    String file = name;
    int slash = name.lastIndexOf('/');
    if (slash >= 0) file = name.substring(slash + 1);
    if (file.endsWith(".source.html")) return file.substring(0, file.length() - ".source.html".length());
    int dot = file.lastIndexOf('.');
    return dot > 0 ? file.substring(0, dot) : file;
  }

  private Map<String, String> metadata(String url, String title) {
    Map<String, String> meta = new HashMap<>();
    meta.put("url", url);
    meta.put("host", hostOf(url));
    if (title != null && !title.trim().isEmpty()) meta.put("title", title.trim());
    meta.put("updated", Long.toString(System.currentTimeMillis()));
    return meta;
  }

  private void migrate(String url) throws Exception {
    String id = idFor(normalizeUrl(url));
    if (!archive.get(id).isEmpty()) return;
    for (Item old : legacyIndex()) {
      if (!old.id.equals(id)) continue;
      Map<String, byte[]> blobs = new HashMap<>();
      for (String formatId : old.formats) {
        Format format = Format.of(formatId);
        if (has(old, format)) blobs.put(formatId, Files.readAllBytes(file(old, format).toPath()));
      }
      Map<String, String> meta = metadata(old.url, old.title);
      meta.put("updated", Long.toString(old.updated));
      meta.put("lastFormat", old.lastFormat);
      archive.put(id, meta, blobs);
      return;
    }
  }

  private List<Item> legacyIndex() throws IOException {
    List<Item> result = new ArrayList<>();
    File index = new File(root, "index.json");
    if (!index.isFile()) return result;
    try {
      JSONArray array = new JSONArray(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
      for (int i = 0; i < array.length(); i++) {
        JSONObject o = array.getJSONObject(i);
        Item item = new Item();
        item.id = o.optString("id");
        if (!item.id.matches("[0-9a-f]{16}")) continue;
        item.url = o.optString("url"); item.title = o.optString("title");
        item.host = o.optString("host");
        item.lastFormat = o.optString("lastFormat", Format.EPUB.id);
        item.updated = o.optLong("updated", 0);
        JSONArray formats = o.optJSONArray("formats");
        if (formats != null) for (int j = 0; j < formats.length(); j++) item.formats.add(formats.getString(j));
        result.add(item);
      }
      return result;
    } catch (Exception e) { throw new IOException("旧版保存记录无法读取；原文件未改动", e); }
  }

  private Item item(String id, Properties meta) {
    Item item = new Item();
    item.id = id; item.modern = true;
    item.url = meta.getProperty("url", "");
    item.host = meta.getProperty("host", hostOf(item.url));
    item.title = meta.getProperty("title", meta.getProperty("sourceTitle", item.host));
    item.lastFormat = meta.getProperty("lastFormat", Format.EPUB.id);
    try { item.updated = Long.parseLong(meta.getProperty("updated", meta.getProperty("sourceUpdated", "0"))); }
    catch (NumberFormatException ignored) { item.updated = 0; }
    for (Format format : Format.ALL) if (meta.containsKey("blob." + format.id)) item.formats.add(format.id);
    return item;
  }

  static String normalizeUrl(String raw) {
    try {
      URI u = URI.create(raw.trim());
      String scheme = u.getScheme() == null ? "https" : u.getScheme().toLowerCase(Locale.ROOT);
      String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
      String path = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
      if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
      String query = u.getRawQuery();
      if (query != null) {
        List<String> keep = new ArrayList<>();
        for (String part : query.split("&")) {
          String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
          if (key.startsWith("utm_")
              || key.equals("fbclid")
              || key.equals("gclid")
              || key.equals("msclkid")
              || key.equals("si")
              || key.equals("ref")
              || key.equals("spm")) {
            continue;
          }
          if (!part.isEmpty()) keep.add(part);
        }
        query = keep.isEmpty() ? null : String.join("&", keep);
      }
      String out = scheme + "://" + host;
      if (u.getPort() > 0) out += ":" + u.getPort();
      out += path;
      if (query != null) out += "?" + query;
      return out;
    } catch (Exception e) {
      return raw.trim();
    }
  }

  static String idFor(String normalized) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", digest[i]));
      return sb.toString();
    } catch (Exception e) {
      return String.valueOf(normalized.hashCode());
    }
  }

  static String hostOf(String url) {
    try {
      String host = URI.create(url).getHost();
      return host == null ? url : host.replaceFirst("^www\\.", "");
    } catch (Exception e) {
      return url;
    }
  }

  static String fileStem(String title) {
    if (title == null) title = "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < title.length(); i++) {
      char c = title.charAt(i);
      if (c <= 31 || "/\\:*?\"<>|".indexOf(c) >= 0) continue;
      sb.append(c);
    }
    String s = sb.toString().replaceAll(" +", " ").trim();
    if (s.isEmpty() || ".".equals(s) || "..".equals(s) || "body".equalsIgnoreCase(s)) s = "book";
    if (s.length() > 80) s = s.substring(0, 80).trim();
    return s;
  }

  static final class Item {
    String id;
    String url;
    String title;
    String host;
    String lastFormat;
    long updated;
    boolean modern;
    List<String> formats = new ArrayList<>();
  }
}
