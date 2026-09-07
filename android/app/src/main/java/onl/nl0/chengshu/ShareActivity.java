package onl.nl0.chengshu;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShareActivity extends Activity {
  private static final String API = "https://0nl.onl/book.epub?url=";
  private static final String PREFS = "chengshu";
  private static final String KEY_READER = "reader_package";
  private static final String ASK_EVERY_TIME = "";
  private static final Pattern URL_RE = Pattern.compile("https?://\\S+");

  private ScrollView settings;
  private View converting;
  private TextView status;
  private TextView empty;
  private TextView savedHint;
  private ProgressBar progress;
  private RadioGroup readers;
  private SharedPreferences prefs;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_share);
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    settings = findViewById(R.id.settings);
    converting = findViewById(R.id.converting);
    status = findViewById(R.id.status);
    empty = findViewById(R.id.empty);
    savedHint = findViewById(R.id.savedHint);
    progress = findViewById(R.id.progress);
    readers = findViewById(R.id.readers);

    String pageUrl = extractUrl(getIntent());
    if (pageUrl != null) {
      convertAndOpen(pageUrl);
    } else {
      showSettings();
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    String pageUrl = extractUrl(intent);
    if (pageUrl != null) convertAndOpen(pageUrl);
    else showSettings();
  }

  private void showSettings() {
    converting.setVisibility(View.GONE);
    settings.setVisibility(View.VISIBLE);
    fillReaderList();
  }

  private void fillReaderList() {
    readers.removeAllViews();
    String saved = prefs.getString(KEY_READER, ASK_EVERY_TIME);
    addChoice(ASK_EVERY_TIME, "每次询问", null, saved.equals(ASK_EVERY_TIME));

    List<ReaderApp> apps = installedReaders();
    empty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
    boolean matched = saved.equals(ASK_EVERY_TIME);
    for (ReaderApp app : apps) {
      boolean selected = app.packageName.equals(saved);
      if (selected) matched = true;
      addChoice(app.packageName, app.label, app.icon, selected);
    }
    if (!matched && !saved.isEmpty()) {
      prefs.edit().putString(KEY_READER, ASK_EVERY_TIME).apply();
      ((RadioButton) readers.getChildAt(0)).setChecked(true);
      saved = ASK_EVERY_TIME;
    }
    readers.setOnCheckedChangeListener(
        (group, checkedId) -> {
          View button = group.findViewById(checkedId);
          if (button == null) return;
          String pkg = String.valueOf(button.getTag());
          prefs.edit().putString(KEY_READER, pkg).apply();
          updateSavedHint(pkg);
        });
    updateSavedHint(saved);
  }

  private void updateSavedHint(String pkg) {
    if (pkg == null || pkg.isEmpty()) {
        savedHint.setText("下次分享会问你用哪个。随时打开成书都能改。");
      return;
    }
    savedHint.setText("已记住「" + labelFor(pkg) + "」。分享进来会直接打开它。");
  }

  private void addChoice(String pkg, String label, Drawable icon, boolean checked) {
    RadioButton button = new RadioButton(this);
    button.setTag(pkg);
    button.setText(label);
    button.setTextColor(getColor(R.color.ink));
    button.setTextSize(16);
    button.setPadding(8, 28, 8, 28);
    button.setGravity(Gravity.CENTER_VERTICAL);
    if (icon != null) {
      int size = (int) (32 * getResources().getDisplayMetrics().density);
      icon.setBounds(0, 0, size, size);
      button.setCompoundDrawables(icon, null, null, null);
      button.setCompoundDrawablePadding((int) (12 * getResources().getDisplayMetrics().density));
    }
    readers.addView(button);
    if (checked) button.setChecked(true);
  }

  private List<ReaderApp> installedReaders() {
    PackageManager pm = getPackageManager();
    Intent probe = new Intent(Intent.ACTION_VIEW);
    probe.setType("application/epub+zip");
    probe.addCategory(Intent.CATEGORY_DEFAULT);
    List<ResolveInfo> infos = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL);
    Intent probe2 = new Intent(Intent.ACTION_VIEW);
    probe2.setType("application/epub");
    probe2.addCategory(Intent.CATEGORY_DEFAULT);
    infos.addAll(pm.queryIntentActivities(probe2, PackageManager.MATCH_ALL));

    Map<String, ReaderApp> unique = new LinkedHashMap<>();
    for (ResolveInfo info : infos) {
      if (info.activityInfo == null) continue;
      String pkg = info.activityInfo.packageName;
      if (pkg.equals(getPackageName()) || unique.containsKey(pkg)) continue;
      CharSequence label = info.loadLabel(pm);
      Drawable icon = info.loadIcon(pm);
      unique.put(pkg, new ReaderApp(pkg, label == null ? pkg : label.toString(), icon));
    }
    return new ArrayList<>(unique.values());
  }

  private String labelFor(String pkg) {
    try {
      PackageManager pm = getPackageManager();
      return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
    } catch (Exception e) {
      return pkg;
    }
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
    settings.setVisibility(View.GONE);
    converting.setVisibility(View.VISIBLE);
    progress.setVisibility(View.VISIBLE);
    status.setText("正在成书…\n" + pageUrl);
    new Thread(
            () -> {
              try {
                File epub = downloadEpub(pageUrl);
                runOnUiThread(
                    () -> {
                      status.setText("正在打开阅读器");
                      openReader(epub);
                    });
              } catch (Exception e) {
                runOnUiThread(
                    () -> {
                      progress.setVisibility(View.GONE);
                      status.setText("失败：" + e.getMessage());
                    });
              }
            },
            "chengshu-convert")
        .start();
  }

  private File downloadEpub(String pageUrl) throws Exception {
    String endpoint = API + URLEncoder.encode(pageUrl, StandardCharsets.UTF_8.name());
    HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(60000);
    conn.setInstanceFollowRedirects(true);
    conn.setRequestProperty("User-Agent", "Chengshu/1.1");
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
    grantAll(uri);
    String pkg = prefs.getString(KEY_READER, ASK_EVERY_TIME);
    if (pkg != null && !pkg.isEmpty() && isInstalled(pkg)) {
      Intent view = viewIntent(uri);
      view.setPackage(pkg);
      grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      try {
        startActivity(view);
        finish();
        return;
      } catch (ActivityNotFoundException ignored) {
        prefs.edit().putString(KEY_READER, ASK_EVERY_TIME).apply();
      }
    }
    try {
      startActivity(Intent.createChooser(viewIntent(uri), "用哪个阅读器打开"));
      finish();
    } catch (ActivityNotFoundException e) {
      progress.setVisibility(View.GONE);
      status.setText("没找到阅读器。打开成书 App，在设置里看已安装的阅读器。");
    }
  }

  private void grantAll(Uri uri) {
    Intent view = viewIntent(uri);
    List<ResolveInfo> matches = getPackageManager().queryIntentActivities(view, PackageManager.MATCH_ALL);
    for (ResolveInfo info : matches) {
      if (info.activityInfo == null) continue;
      grantUriPermission(
          info.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
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

  private static final class ReaderApp {
    final String packageName;
    final String label;
    final Drawable icon;

    ReaderApp(String packageName, String label, Drawable icon) {
      this.packageName = packageName;
      this.label = label;
      this.icon = icon;
    }
  }
}
