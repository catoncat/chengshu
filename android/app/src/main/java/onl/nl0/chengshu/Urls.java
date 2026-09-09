package onl.nl0.chengshu;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Conservative article identity. identityVersion 1 is the installed 1.12 hash; v2 keeps content-bearing ref/si and hash routes. */
final class Urls {
  static final int VERSION = 2;

  static String normalize(String raw) { return normalize(raw, VERSION); }

  static String normalize(String raw, int identityVersion) {
    if (raw == null) return "";
    try {
      URI u = URI.create(raw.trim());
      String scheme = u.getScheme() == null ? "https" : u.getScheme().toLowerCase(Locale.ROOT);
      String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
      String path = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
      if (path.contains("%2f") || path.contains("%2F")) {
        // Keep encoded slashes; they distinguish wiki and app routes.
      } else if (path.length() > 1 && path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      String query = u.getRawQuery();
      if (query != null) {
        List<String> keep = new ArrayList<>();
        for (String part : query.split("&")) {
          if (part.isEmpty()) continue;
          String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
          if (key.startsWith("utm_") || key.equals("fbclid") || key.equals("gclid")
              || key.equals("msclkid") || key.equals("spm")) continue;
          if (identityVersion < 2 && (key.equals("si") || key.equals("ref"))) continue;
          keep.add(part);
        }
        query = keep.isEmpty() ? null : String.join("&", keep);
      }
      String fragment = identityVersion >= 2 ? u.getRawFragment() : null;
      if (fragment != null && !fragment.startsWith("/")) fragment = null; // ignore in-page anchors
      String out = scheme + "://" + host;
      if (u.getPort() > 0) out += ":" + u.getPort();
      out += path;
      if (query != null) out += "?" + query;
      if (fragment != null && !fragment.isEmpty()) out += "#" + fragment;
      return out;
    } catch (Exception e) {
      return raw.trim();
    }
  }

  static String articleId(String normalized) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 16; i++) sb.append(String.format(Locale.ROOT, "%02x", digest[i]));
      return sb.toString();
    } catch (Exception e) {
      return Integer.toHexString(normalized.hashCode());
    }
  }

  static String aliasKey(String raw, int identityVersion) {
    return identityVersion + ":" + normalize(raw, identityVersion);
  }

  static String host(String url) {
    try {
      String host = URI.create(url).getHost();
      return host == null ? url : host.replaceFirst("^www\\.", "");
    } catch (Exception e) {
      return url;
    }
  }
}
