package onl.nl0.chengshu;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class Library {
  private final File root;
  private final File indexFile;

  Library(Context context) {
    root = new File(context.getFilesDir(), "library");
    indexFile = new File(root, "index.json");
    if (!root.exists()) root.mkdirs();
  }

  synchronized List<Item> list() {
    return readIndex();
  }

  synchronized Item findByUrl(String url) {
    String id = idFor(normalizeUrl(url));
    for (Item item : readIndex()) {
      if (item.id.equals(id)) return item;
    }
    return null;
  }

  synchronized File file(Item item, Format format) {
    File dir = itemDir(item.id);
    File named = new File(dir, fileStem(item.title) + format.ext);
    if (named.isFile()) return named;
    File[] matches = dir.listFiles((d, n) -> n != null && n.endsWith(format.ext));
    if (matches != null) {
      for (File f : matches) {
        if (f.getName().equals("body" + format.ext)) {
          if (!named.getName().equals(f.getName()) && f.renameTo(named) && named.isFile()) {
            return named;
          }
          return f;
        }
      }
      if (matches.length == 1) return matches[0];
    }
    return named;
  }

  synchronized boolean has(Item item, Format format) {
    return file(item, format).isFile() && file(item, format).length() > 0;
  }

  synchronized Item save(String url, String title, Format format, byte[] body) throws Exception {
    String normalized = normalizeUrl(url);
    String id = idFor(normalized);
    File dir = itemDir(id);
    if (!dir.exists()) dir.mkdirs();
    List<Item> items = readIndex();
    Item found = null;
    for (Iterator<Item> it = items.iterator(); it.hasNext(); ) {
      Item item = it.next();
      if (item.id.equals(id)) {
        found = item;
        it.remove();
        break;
      }
    }
    if (found == null) {
      found = new Item();
      found.id = id;
      found.url = normalized;
      found.host = hostOf(normalized);
      found.formats = new ArrayList<>();
    }
    if (title != null && !title.trim().isEmpty()) found.title = title.trim();
    if (found.title == null || found.title.isEmpty()) found.title = found.host;
    File out = new File(dir, fileStem(found.title) + format.ext);
    File[] old = dir.listFiles();
    if (old != null) {
      for (File f : old) {
        if (f.getName().endsWith(format.ext) && !f.getName().equals(out.getName())) {
          f.delete();
        }
      }
    }
    try (FileOutputStream fos = new FileOutputStream(out)) {
      fos.write(body);
    }
    if (!found.formats.contains(format.id)) found.formats.add(format.id);
    found.lastFormat = format.id;
    found.updated = System.currentTimeMillis();
    items.add(0, found);
    while (items.size() > 200) {
      Item drop = items.remove(items.size() - 1);
      deleteDir(itemDir(drop.id));
    }
    writeIndex(items);
    return found;
  }

  synchronized void markOpened(Item item, Format format) {
    List<Item> items = readIndex();
    Item found = null;
    for (Iterator<Item> it = items.iterator(); it.hasNext(); ) {
      Item cur = it.next();
      if (cur.id.equals(item.id)) {
        found = cur;
        it.remove();
        break;
      }
    }
    if (found == null) return;
    found.lastFormat = format.id;
    found.updated = System.currentTimeMillis();
    items.add(0, found);
    writeIndex(items);
  }

  synchronized void delete(Item item) {
    deleteDir(itemDir(item.id));
    List<Item> items = readIndex();
    Iterator<Item> it = items.iterator();
    while (it.hasNext()) {
      if (it.next().id.equals(item.id)) it.remove();
    }
    writeIndex(items);
  }

  private File itemDir(String id) {
    return new File(root, id);
  }

  private List<Item> readIndex() {
    List<Item> items = new ArrayList<>();
    if (!indexFile.isFile()) return items;
    try (FileInputStream in = new FileInputStream(indexFile)) {
      byte[] buf = new byte[(int) indexFile.length()];
      int off = 0;
      while (off < buf.length) {
        int n = in.read(buf, off, buf.length - off);
        if (n < 0) break;
        off += n;
      }
      JSONArray arr = new JSONArray(new String(buf, 0, off, StandardCharsets.UTF_8));
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.getJSONObject(i);
        Item item = new Item();
        item.id = o.optString("id");
        item.url = o.optString("url");
        item.title = o.optString("title");
        item.host = o.optString("host");
        item.lastFormat = o.optString("lastFormat", Format.EPUB.id);
        item.updated = o.optLong("updated", 0);
        item.formats = new ArrayList<>();
        JSONArray f = o.optJSONArray("formats");
        if (f != null) {
          for (int j = 0; j < f.length(); j++) item.formats.add(f.getString(j));
        }
        if (!item.id.isEmpty()) items.add(item);
      }
    } catch (Exception ignored) {
    }
    return items;
  }

  private void writeIndex(List<Item> items) {
    JSONArray arr = new JSONArray();
    try {
      for (Item item : items) {
        JSONObject o = new JSONObject();
        o.put("id", item.id);
        o.put("url", item.url);
        o.put("title", item.title);
        o.put("host", item.host);
        o.put("lastFormat", item.lastFormat);
        o.put("updated", item.updated);
        JSONArray f = new JSONArray();
        for (String id : item.formats) f.put(id);
        o.put("formats", f);
        arr.put(o);
      }
      byte[] data = arr.toString().getBytes(StandardCharsets.UTF_8);
      try (FileOutputStream fos = new FileOutputStream(indexFile)) {
        fos.write(data);
      }
    } catch (Exception ignored) {
    }
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

  private static void deleteDir(File dir) {
    File[] files = dir.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) deleteDir(file);
        else file.delete();
      }
    }
    dir.delete();
  }

  static final class Item {
    String id;
    String url;
    String title;
    String host;
    String lastFormat;
    long updated;
    List<String> formats = new ArrayList<>();
  }
}
