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
import android.widget.LinearLayout;
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
  private static final String API = "https://0nl.onl/export";
  private static final String PREFS = "chengshu";
  private static final String KEY_FORMAT = "format";
  private static final String KEY_READER = "reader_package";
  private static final String ASK_EVERY_TIME = "";
  private static final Pattern URL_RE = Pattern.compile("https?://\\S+");

  private ScrollView settings;
  private View converting;
  private LinearLayout formats;
  private RadioGroup readers;
  private TextView empty;
  private TextView savedHint;
  private TextView pipe;
  private TextView status;
  private ProgressBar progress;
  private SharedPreferences prefs;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_share);
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    migrateLegacy();
    settings = findViewById(R.id.settings);
    converting = findViewById(R.id.converting);
    formats = findViewById(R.id.formats);
    readers = findViewById(R.id.readers);
    empty = findViewById(R.id.empty);
    savedHint = findViewById(R.id.savedHint);
    pipe = findViewById(R.id.pipe);
    status = findViewById(R.id.status);
    progress = findViewById(R.id.progress);

    String pageUrl = extractUrl(getIntent());
    if (pageUrl != null) convertAndOpen(pageUrl);
    else showSettings();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    String pageUrl = extractUrl(intent);
    if (pageUrl != null) convertAndOpen(pageUrl);
    else showSettings();
  }

  private void migrateLegacy() {
    if (!prefs.contains("dest.epub") && prefs.contains(KEY_READER)) {
      prefs.edit().putString("dest.epub", prefs.getString(KEY_READER, ASK_EVERY_TIME)).apply();
    }
  }

  private Format currentFormat() {
    return Format.of(prefs.getString(KEY_FORMAT, Format.EPUB.id));
  }

  private String destKey(Format format) {
    return "dest." + format.id;
  }

  private void showSettings() {
    converting.setVisibility(View.GONE);
    settings.setVisibility(View.VISIBLE);
    fillFormats();
    fillDestinations();
  }

  private void fillFormats() {
    formats.removeAllViews();
    Format selected = currentFormat();
    for (Format format : Format.ALL) {
      formats.addView(formatRow(format, format.id.equals(selected.id)));
    }
  }

  private View formatRow(Format format, boolean selected) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setBackgroundResource(selected ? R.drawable.card_selected : R.drawable.card);
    int pad = dp(14);
    row.setPadding(pad, pad, pad, pad);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.bottomMargin = dp(8);
    row.setLayoutParams(lp);

    TextView title = new TextView(this);
    title.setText(format.title);
    title.setTextSize(16);
    title.setTextColor(getColor(selected ? R.color.on_green : R.color.ink));
    TextView hint = new TextView(this);
    hint.setText(format.hint);
    hint.setTextSize(13);
    hint.setPadding(0, dp(2), 0, 0);
    hint.setTextColor(getColor(selected ? R.color.on_green : R.color.muted));
    row.addView(title);
    row.addView(hint);
    row.setOnClickListener(
        v -> {
          prefs.edit().putString(KEY_FORMAT, format.id).apply();
          fillFormats();
          fillDestinations();
        });
    return row;
  }

  private void fillDestinations() {
    readers.setOnCheckedChangeListener(null);
    readers.removeAllViews();
    Format format = currentFormat();
    String saved = prefs.getString(destKey(format), ASK_EVERY_TIME);
    addChoice(ASK_EVERY_TIME, "每次询问", null, saved.equals(ASK_EVERY_TIME));
    List<ReaderApp> apps = appsFor(format);
    empty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
    boolean matched = saved.equals(ASK_EVERY_TIME);
    for (ReaderApp app : apps) {
      boolean on = app.packageName.equals(saved);
      if (on) matched = true;
      addChoice(app.packageName, app.label, app.icon, on);
    }
    if (!matched) {
      saved = ASK_EVERY_TIME;
      prefs.edit().putString(destKey(format), ASK_EVERY_TIME).apply();
      ((RadioButton) readers.getChildAt(0)).setChecked(true);
    }
    readers.setOnCheckedChangeListener(
        (group, checkedId) -> {
          View button = group.findViewById(checkedId);
          if (button == null) return;
          prefs.edit().putString(destKey(currentFormat()), String.valueOf(button.getTag())).apply();
          refreshPipe();
        });
    refreshPipe();
  }

  private void refreshPipe() {
    Format format = currentFormat();
    String pkg = prefs.getString(destKey(format), ASK_EVERY_TIME);
    String dest = (pkg == null || pkg.isEmpty()) ? "每次询问" : labelFor(pkg);
    pipe.setText("网页 → " + format.title + " → " + dest);
    if (pkg == null || pkg.isEmpty()) {
      savedHint.setText("转完会弹出列表。打开成书随时能改格式和去向。");
    } else {
      savedHint.setText("已记住这条线。下次分享会直接交给「" + dest + "」。");
    }
  }

  private void addChoice(String pkg, String label, Drawable icon, boolean checked) {
    RadioButton button = new RadioButton(this);
    button.setTag(pkg);
    button.setText(label);
    button.setTextColor(getColor(R.color.ink));
    button.setTextSize(16);
    button.setPadding(8, dp(12), 8, dp(12));
    button.setGravity(Gravity.CENTER_VERTICAL);
    if (icon != null) {
      int size = dp(28);
      icon.setBounds(0, 0, size, size);
      button.setCompoundDrawables(icon, null, null, null);
      button.setCompoundDrawablePadding(dp(12));
    }
    readers.addView(button);
    if (checked) button.setChecked(true);
  }

  private List<ReaderApp> appsFor(Format format) {
    PackageManager pm = getPackageManager();
    List<ResolveInfo> infos = new ArrayList<>();
    infos.addAll(queryMime(pm, format.mime));
    for (String extra : format.extraMimes) infos.addAll(queryMime(pm, extra));
    Map<String, ReaderApp> unique = new LinkedHashMap<>();
    for (ResolveInfo info : infos) {
      if (info.activityInfo == null) continue;
      String pkg = info.activityInfo.packageName;
      if (pkg.equals(getPackageName()) || unique.containsKey(pkg)) continue;
      CharSequence label = info.loadLabel(pm);
      unique.put(pkg, new ReaderApp(pkg, label == null ? pkg : label.toString(), info.loadIcon(pm)));
    }
    return new ArrayList<>(unique.values());
  }

  private List<ResolveInfo> queryMime(PackageManager pm, String mime) {
    Intent probe = new Intent(Intent.ACTION_VIEW);
    probe.setType(mime);
    probe.addCategory(Intent.CATEGORY_DEFAULT);
    return pm.queryIntentActivities(probe, PackageManager.MATCH_ALL);
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
    if (data != null && data.toString().startsWith("http")) return data.toString();
    return null;
  }

  private void convertAndOpen(String pageUrl) {
    Format format = currentFormat();
    settings.setVisibility(View.GONE);
    converting.setVisibility(View.VISIBLE);
    progress.setVisibility(View.VISIBLE);
    status.setText("正在变成 " + format.title + "…\n" + pageUrl);
    new Thread(
            () -> {
              try {
                File file = downloadFile(pageUrl, format);
                runOnUiThread(
                    () -> {
                      status.setText("正在交给下一个 App");
                      openWith(file, format);
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

  private File downloadFile(String pageUrl, Format format) throws Exception {
    String endpoint =
        API
            + "?format="
            + format.id
            + "&url="
            + URLEncoder.encode(pageUrl, StandardCharsets.UTF_8.name());
    HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(60000);
    conn.setInstanceFollowRedirects(true);
    conn.setRequestProperty("User-Agent", "Chengshu/1.2");
    conn.setRequestProperty("Accept", format.mime + ",*/*");
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
    if (body.length < 8) throw new RuntimeException("服务器没返回文件");
    if (format.id.equals("epub") && (body[0] != 'P' || body[1] != 'K')) {
      throw new RuntimeException("服务器没返回 EPUB");
    }
    File file = new File(getCacheDir(), "chengshu" + format.ext);
    try (FileOutputStream fos = new FileOutputStream(file)) {
      fos.write(body);
    }
    return file;
  }

  private void openWith(File file, Format format) {
    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
    grantAll(uri, format.mime);
    String pkg = prefs.getString(destKey(format), ASK_EVERY_TIME);
    if (pkg != null && !pkg.isEmpty() && isInstalled(pkg)) {
      Intent view = viewIntent(uri, format.mime);
      view.setPackage(pkg);
      grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      try {
        startActivity(view);
        finish();
        return;
      } catch (ActivityNotFoundException ignored) {
        prefs.edit().putString(destKey(format), ASK_EVERY_TIME).apply();
      }
    }
    try {
      startActivity(Intent.createChooser(viewIntent(uri, format.mime), "交给哪个 App"));
      finish();
    } catch (ActivityNotFoundException e) {
      progress.setVisibility(View.GONE);
      status.setText("没找到能打开 " + format.title + " 的 App。打开成书换一个格式。");
    }
  }

  private void grantAll(Uri uri, String mime) {
    List<ResolveInfo> matches =
        getPackageManager().queryIntentActivities(viewIntent(uri, mime), PackageManager.MATCH_ALL);
    for (ResolveInfo info : matches) {
      if (info.activityInfo == null) continue;
      grantUriPermission(
          info.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
  }

  private Intent viewIntent(Uri uri, String mime) {
    Intent view = new Intent(Intent.ACTION_VIEW);
    view.setDataAndType(uri, mime);
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

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
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
