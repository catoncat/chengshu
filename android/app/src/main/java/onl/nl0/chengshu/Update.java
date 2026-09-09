package onl.nl0.chengshu;

import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class Update {
  static final String MANIFEST = "https://0nl.onl/app.json";
  static final String APK = "https://0nl.onl/chengshu.apk";
  static final long MAX_APK_BYTES = 80L * 1024 * 1024;

  static final class Info {
    final int versionCode;
    final String versionName;
    final String apk;
    final String sha256;
    final long size;
    final String channel;
    final String sourceCommit;

    Info(int versionCode, String versionName, String apk, String sha256, long size, String channel, String sourceCommit) {
      this.versionCode = versionCode;
      this.versionName = versionName;
      this.apk = apk == null || apk.isEmpty() ? APK : apk;
      this.sha256 = sha256 == null ? "" : sha256;
      this.size = size;
      this.channel = channel == null || channel.isEmpty() ? "stable" : channel;
      this.sourceCommit = sourceCommit == null ? "" : sourceCommit;
    }

    boolean newerThan(int installed) { return versionCode > installed; }
  }

  static Info parse(String raw) throws Exception {
    JSONObject json = new JSONObject(raw);
    return new Info(
        json.optInt("versionCode", 0),
        json.optString("versionName", ""),
        json.optString("apk", APK),
        clean(json.optString("sha256", "")),
        json.optLong("size", 0),
        json.optString("channel", "stable"),
        json.optString("sourceCommit", ""));
  }

  private static String clean(String value) {
    return value == null || "null".equals(value) ? "" : value;
  }

  static void assertSafe(Info info, int installed, String channel) throws Exception {
    if (!"stable".equals(info.channel) && !channel.equals(info.channel))
      throw new IOException("更新渠道不匹配");
    URI uri = URI.create(info.apk);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
    if (!"https".equalsIgnoreCase(uri.getScheme()) || !host.equals("0nl.onl"))
      throw new IOException("更新来源不受信任");
    if (info.versionCode <= installed) throw new IOException("不是更新的版本");
    if (info.size < 0 || info.size > MAX_APK_BYTES) throw new IOException("更新文件过大");
  }

  static Info fetch() throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(MANIFEST).openConnection();
    conn.setConnectTimeout(8000);
    conn.setReadTimeout(8000);
    conn.setInstanceFollowRedirects(false);
    conn.setRequestProperty("Cache-Control", "no-cache");
    conn.setRequestProperty("User-Agent", "Chengshu/" + BuildConfig.VERSION_NAME);
    int code = conn.getResponseCode();
    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[2048];
    int n;
    while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
    if (code >= 400) throw new RuntimeException("检查失败");
    Info info = parse(out.toString(StandardCharsets.UTF_8.name()));
    assertSafe(info, 0, "stable");
    return info;
  }

  static File download(Info info, File target, int installed) throws Exception {
    assertSafe(info, installed, "stable");
    HttpURLConnection conn = (HttpURLConnection) new URL(info.apk).openConnection();
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(60000);
    conn.setInstanceFollowRedirects(false);
    if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300)
      throw new IOException("无法下载更新");
    long reported = conn.getContentLengthLong();
    if (reported > MAX_APK_BYTES || (info.size > 0 && reported > info.size + 1024))
      throw new IOException("更新文件过大");
    File partial = new File(target.getParentFile(), target.getName() + ".part");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long total = 0;
    try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(partial)) {
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) > 0) {
        total += n;
        if (total > MAX_APK_BYTES) throw new IOException("更新文件过大");
        digest.update(buf, 0, n);
        fos.write(buf, 0, n);
      }
      fos.getFD().sync();
    }
    String hex = hex(digest.digest());
    if (info.size > 0 && total != info.size) throw new IOException("更新大小不匹配");
    if (!info.sha256.isEmpty() && !info.sha256.equalsIgnoreCase(hex)) throw new IOException("更新校验失败");
    if (target.exists() && !target.delete()) throw new IOException("无法替换旧的更新文件");
    if (!partial.renameTo(target)) throw new IOException("无法保存更新");
    return target;
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(64);
    for (byte b : bytes) out.append(String.format("%02x", b & 255));
    return out.toString();
  }
}
