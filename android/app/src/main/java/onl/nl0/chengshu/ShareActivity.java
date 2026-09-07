package onl.nl0.chengshu;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShareActivity extends Activity {
  private static final String API = "https://0nl.onl/book.epub?url=";
  private static final String[] READERS = {
    "org.koreader.launcher",
    "org.koreader.launcher.fdroid",
    "com.foobnix.pdf.reader",
    "com.foobnix.pro.pdf.reader"
  };
  private static final Pattern URL_RE = Pattern.compile("https?://\\S+");

  private TextView status;
  private ProgressBar progress;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_share);
    status = findViewById(R.id.status);
    progress = findViewById(R.id.progress);

    String pageUrl = extractUrl(getIntent());
    if (pageUrl == null) {
      status.setText("在 Chrome 打开网页 → 分享 → 成书。会转成 EPUB 并直接打开 KOReader。");
      return;
    }
    convertAndOpen(pageUrl);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    String pageUrl = extractUrl(intent);
    if (pageUrl != null) convertAndOpen(pageUrl);
  }

  private static String extractUrl(Intent intent) {
    if (intent == null) return null;
    String text = intent.getStringExtra(Intent.EXTRA_TEXT);
    if (text == null) text = "";
    Matcher m = URL_RE.matcher(text);
    if (m.find()) {
      String found = m.group();
      while (found.endsWith(")") || found.endsWith("。") || found.endsWith(".")) {
        found = found.substring(0, found.length() - 1);
      }
      return found;
    }
    Uri data = intent.getData();
    if (data != null) {
      String s = data.toString();
      if (s.startsWith("http")) return s;
    }
    return null;
  }

  private void convertAndOpen(String pageUrl) {
    progress.setVisibility(View.VISIBLE);
    status.setText("正在成书…\n" + pageUrl);
    new Thread(() -> {
      try {
        File epub = downloadEpub(pageUrl);
        runOnUiThread(() -> {
          status.setText("正在打开 KOReader");
          openReader(epub);
        });
      } catch (Exception e) {
        runOnUiThread(() -> {
          progress.setVisibility(View.GONE);
          status.setText("失败：" + e.getMessage());
        });
      }
    }, "chengshu-convert").start();
  }

  private File downloadEpub(String pageUrl) throws Exception {
    String endpoint = API + URLEncoder.encode(pageUrl, StandardCharsets.UTF_8.name());
    HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(60000);
    conn.setInstanceFollowRedirects(true);
    conn.setRequestProperty("User-Agent", "Chengshu/1.0");
    conn.setRequestProperty("Accept", "application/epub+zip");
    int code = conn.getResponseCode();
    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[16384];
    int n;
    while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
    byte[] body = out.toByteArray();
    if (code >= 400) {
      String err = new String(body, StandardCharsets.UTF_8);
      throw new RuntimeException(err.isEmpty() ? ("HTTP " + code) : err);
    }
    if (body.length < 100 || body[0] != 'P' || body[1] != 'K') {
      throw new RuntimeException("服务器没返回 EPUB");
    }
    File file = new File(getCacheDir(), "book.epub");
    try (FileOutputStream fos = new FileOutputStream(file)) {
      fos.write(body);
    }
    return file;
  }

  private void openReader(File epub) {
    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", epub);
    for (String pkg : READERS) {
      if (!isInstalled(pkg)) continue;
      Intent view = viewIntent(uri);
      view.setPackage(pkg);
      grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      try {
        startActivity(view);
        finish();
        return;
      } catch (ActivityNotFoundException ignored) {
      }
    }
    Intent view = viewIntent(uri);
    List<ResolveInfo> matches = getPackageManager().queryIntentActivities(view, 0);
    for (ResolveInfo info : matches) {
      grantUriPermission(
          info.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    try {
      startActivity(Intent.createChooser(view, "用阅读器打开"));
      finish();
    } catch (ActivityNotFoundException e) {
      progress.setVisibility(View.GONE);
      status.setText("没找到阅读器。请先安装 KOReader。");
    }
  }

  private Intent viewIntent(Uri uri) {
    Intent view = new Intent(Intent.ACTION_VIEW);
    view.setDataAndType(uri, "application/epub+zip");
    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return view;
  }

  private boolean isInstalled(String pkg) {
    try {
      getPackageManager().getPackageInfo(pkg, 0);
      return true;
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }
}
