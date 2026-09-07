package onl.nl0.chengshu;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Update {
  static final String MANIFEST = "https://0nl.onl/app.json";
  static final String APK = "https://0nl.onl/chengshu.apk";

  static final class Info {
    final int versionCode;
    final String versionName;
    final String apk;

    Info(int versionCode, String versionName, String apk) {
      this.versionCode = versionCode;
      this.versionName = versionName;
      this.apk = apk == null || apk.isEmpty() ? APK : apk;
    }

    boolean newerThan(int installed) {
      return versionCode > installed;
    }
  }

  static Info fetch() throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(MANIFEST).openConnection();
    conn.setConnectTimeout(8000);
    conn.setReadTimeout(8000);
    conn.setRequestProperty("Cache-Control", "no-cache");
    conn.setRequestProperty("User-Agent", "Chengshu/" + BuildConfig.VERSION_NAME);
    int code = conn.getResponseCode();
    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[2048];
    int n;
    while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
    if (code >= 400) throw new RuntimeException("检查失败");
    JSONObject json = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
    return new Info(
        json.optInt("versionCode", 0),
        json.optString("versionName", ""),
        json.optString("apk", APK));
  }
}
