package onl.nl0.chengshu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ShareActivity extends Activity {
  private static final String API = "https://0nl.onl/export";
  private static final String PREFS = "chengshu";
  private static final String KEY_FORMAT = "format";
  private static final String KEY_READER = "reader_package";
  private static final String ASK = "";
  private static final long SHARE_FRESH_MS = 45_000;

  private View home;
  private View converting;
  private View confirm;
  private LinearLayout history;
  private LinearLayout confirmFormats;
  private TextView empty;
  private TextView formatValue;
  private TextView destValue;
  private TextView hiddenValue;
  private TextView updateValue;
  private TextView status;
  private TextView confirmTitle;
  private TextView confirmHost;
  private ProgressBar progress;
  private SharedPreferences prefs;
  private Library library;
  private Update.Info pendingUpdate;
  private String pendingUrl;
  private String pendingTitle;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_share);
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    library = new Library(this);
    migrateLegacy();
    home = findViewById(R.id.home);
    converting = findViewById(R.id.converting);
    confirm = findViewById(R.id.confirm);
    history = findViewById(R.id.history);
    confirmFormats = findViewById(R.id.confirmFormats);
    empty = findViewById(R.id.empty);
    formatValue = findViewById(R.id.formatValue);
    destValue = findViewById(R.id.destValue);
    hiddenValue = findViewById(R.id.hiddenValue);
    updateValue = findViewById(R.id.updateValue);
    status = findViewById(R.id.status);
    confirmTitle = findViewById(R.id.confirmTitle);
    confirmHost = findViewById(R.id.confirmHost);
    progress = findViewById(R.id.progress);

    findViewById(R.id.rowFormat).setOnClickListener(v -> pickFormat());
    findViewById(R.id.rowDest).setOnClickListener(v -> pickDest(currentFormat()));
    findViewById(R.id.rowHidden).setOnClickListener(v -> pickHidden());
    findViewById(R.id.rowUpdate).setOnClickListener(v -> onUpdateTap());
    findViewById(R.id.confirmCancel).setOnClickListener(v -> cancelShare());

    handleIntent(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIntent(intent);
  }

  private void handleIntent(Intent intent) {
    String action = intent == null ? null : intent.getAction();
    int flags = intent == null ? 0 : intent.getFlags();
    if (!ShareFlow.shouldConvertShare(action, flags)) {
      showHome();
      return;
    }
    String pageUrl = urlOf(intent);
    if (pageUrl != null) {
      if (ShareFlow.autoConvertOnShare()) {
        convertAndOpen(pageUrl, titleOf(intent), currentFormat(), true, false);
      } else {
        showShareConfirm(pageUrl, titleOf(intent));
      }
    } else {
      showHome();
    }
  }

  private void migrateLegacy() {
    if (!prefs.contains("dest.epub") && prefs.contains(KEY_READER)) {
      prefs.edit().putString("dest.epub", prefs.getString(KEY_READER, ASK)).apply();
    }
  }

  private Format currentFormat() {
    return Format.of(prefs.getString(KEY_FORMAT, Format.EPUB.id));
  }

  private String destKey(Format format) {
    return "dest." + format.id;
  }

  private void showHome() {
    converting.setVisibility(View.GONE);
    confirm.setVisibility(View.GONE);
    home.setVisibility(View.VISIBLE);
    refreshPrefs();
    refreshHistory();
    checkUpdate(false);
  }

  private void cancelShare() {
    pendingUrl = null;
    pendingTitle = null;
    clearShareIntent();
    showHome();
  }

  private void showShareConfirm(String pageUrl, String title) {
    pendingUrl = pageUrl;
    pendingTitle = title == null ? "" : title;
    home.setVisibility(View.GONE);
    converting.setVisibility(View.GONE);
    confirm.setVisibility(View.VISIBLE);
    String host = Library.hostOf(pageUrl);
    confirmTitle.setText(pendingTitle.isEmpty() ? host : pendingTitle);
    confirmHost.setText(host);
    refreshConfirm();
  }

  private void refreshConfirm() {
    if (confirmFormats == null) return;
    confirmFormats.removeAllViews();
    for (Format format : Format.ALL) {
      confirmFormats.addView(confirmFormatRow(format));
    }
  }

  private View confirmFormatRow(Format format) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setPadding(dp(20), dp(14), dp(20), dp(14));
    row.setBackgroundResource(android.R.drawable.list_selector_background);
    row.setClickable(true);
    LinearLayout left = new LinearLayout(this);
    left.setOrientation(LinearLayout.VERTICAL);
    left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    TextView title = new TextView(this);
    title.setText(format.title);
    title.setTextColor(getColor(R.color.ink));
    title.setTextSize(16);
    TextView hint = new TextView(this);
    hint.setText(format.hint);
    hint.setTextColor(getColor(R.color.muted));
    hint.setTextSize(13);
    hint.setPadding(0, dp(4), 0, 0);
    left.addView(title);
    left.addView(hint);
    TextView dest = new TextView(this);
    String pkg = prefs.getString(destKey(format), ASK);
    dest.setText(pkg == null || pkg.isEmpty() ? "每次询问" : labelOf(pkg));
    dest.setTextColor(getColor(R.color.muted));
    dest.setTextSize(15);
    dest.setPadding(dp(12), dp(8), 0, dp(8));
    dest.setOnClickListener(v -> pickDest(format));
    row.addView(left);
    row.addView(dest);
    row.setOnClickListener(v -> startShareConvert(format));
    View line = new View(this);
    line.setBackgroundColor(getColor(R.color.line));
    LinearLayout wrap = new LinearLayout(this);
    wrap.setOrientation(LinearLayout.VERTICAL);
    wrap.addView(row);
    wrap.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
    return wrap;
  }

  private void startShareConvert(Format format) {
    if (pendingUrl == null || pendingUrl.isEmpty()) {
      showHome();
      return;
    }
    prefs.edit().putString(KEY_FORMAT, format.id).apply();
    convertAndOpen(pendingUrl, pendingTitle, format, true, false);
  }

  private void refreshPrefs() {
    Format format = currentFormat();
    formatValue.setText(format.title);
    String pkg = prefs.getString(destKey(format), ASK);
    destValue.setText(pkg == null || pkg.isEmpty() ? "每次询问" : labelOf(pkg));
    int hidden = Apps.userHidden(prefs).size();
    hiddenValue.setText(hidden == 0 ? "无" : hidden + " 个");
  }

  private void refreshHistory() {
    history.removeAllViews();
    List<Library.Item> items = library.list();
    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    for (Library.Item item : items) history.addView(historyRow(item));
  }

  private View historyRow(Library.Item item) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(dp(20), dp(14), dp(20), dp(14));
    row.setBackgroundResource(android.R.drawable.list_selector_background);
    row.setClickable(true);
    TextView title = new TextView(this);
    title.setText(item.title);
    title.setTextColor(getColor(R.color.ink));
    title.setTextSize(16);
    title.setMaxLines(2);
    TextView meta = new TextView(this);
    meta.setText(
        item.host
            + " · "
            + Format.of(item.lastFormat).title
            + " · "
            + relative(item.updated));
    meta.setTextColor(getColor(R.color.muted));
    meta.setTextSize(13);
    meta.setPadding(0, dp(4), 0, 0);
    row.addView(title);
    row.addView(meta);
    row.setOnClickListener(v -> openItem(item, Format.of(item.lastFormat)));
    row.setOnLongClickListener(
        v -> {
          itemMenu(item);
          return true;
        });
    View line = new View(this);
    line.setBackgroundColor(getColor(R.color.line));
    LinearLayout wrap = new LinearLayout(this);
    wrap.setOrientation(LinearLayout.VERTICAL);
    wrap.addView(row);
    wrap.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
    return wrap;
  }

  private void itemMenu(Library.Item item) {
    String[] actions = new String[Format.ALL.length + 3];
    actions[0] = "打开";
    for (int i = 0; i < Format.ALL.length; i++) {
      Format format = Format.ALL[i];
      boolean have = library.has(item, format);
      actions[i + 1] = (have ? "打开 " : "转成 ") + format.title;
    }
    actions[actions.length - 2] = "重新抓取";
    actions[actions.length - 1] = "删除";
    new AlertDialog.Builder(this)
        .setTitle(item.title)
        .setItems(
            actions,
            (d, which) -> {
              if (which == 0) {
                openItem(item, Format.of(item.lastFormat));
              } else if (which == actions.length - 1) {
                library.delete(item);
                refreshHistory();
              } else if (which == actions.length - 2) {
                convertAndOpen(item.url, item.title, Format.of(item.lastFormat), false, true);
              } else {
                openItem(item, Format.ALL[which - 1]);
              }
            })
        .show();
  }

  private void pickFormat() {
    Format current = currentFormat();
    String[] labels = new String[Format.ALL.length];
    int selected = 0;
    for (int i = 0; i < Format.ALL.length; i++) {
      labels[i] = Format.ALL[i].title;
      if (Format.ALL[i].id.equals(current.id)) selected = i;
    }
    new AlertDialog.Builder(this)
        .setTitle("格式")
        .setSingleChoiceItems(
            labels,
            selected,
            (d, which) -> {
              prefs.edit().putString(KEY_FORMAT, Format.ALL[which].id).apply();
              d.dismiss();
              refreshPrefs();
            })
        .show();
  }

  private void pickDest(Format format) {
    List<Apps.Entry> apps = Apps.visible(this, format, prefs);
    List<String> labels = new ArrayList<>();
    List<String> pkgs = new ArrayList<>();
    labels.add("每次询问");
    pkgs.add(ASK);
    int selected = 0;
    String saved = prefs.getString(destKey(format), ASK);
    for (int i = 0; i < apps.size(); i++) {
      Apps.Entry app = apps.get(i);
      labels.add(app.pinned ? app.label : app.label);
      pkgs.add(app.packageName);
      if (app.packageName.equals(saved)) selected = i + 1;
    }
    new AlertDialog.Builder(this)
        .setTitle("打开方式")
        .setSingleChoiceItems(
            labels.toArray(new String[0]),
            selected,
            (d, which) -> {
              prefs.edit().putString(destKey(format), pkgs.get(which)).apply();
              d.dismiss();
              refreshPrefs();
              refreshConfirm();
            })
        .setNeutralButton("排除…", (d, w) -> pickExclude(format))
        .show();
  }

  private void pickExclude(Format format) {
    List<Apps.Entry> apps = Apps.visible(this, format, prefs);
    if (apps.isEmpty()) {
      Toast.makeText(this, "没有可排除的应用", Toast.LENGTH_SHORT).show();
      return;
    }
    String[] labels = new String[apps.size()];
    for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).label;
    new AlertDialog.Builder(this)
        .setTitle("点一项即排除")
        .setItems(
            labels,
            (d, which) -> {
              Apps.hide(prefs, apps.get(which).packageName);
              String saved = prefs.getString(destKey(format), ASK);
              if (apps.get(which).packageName.equals(saved)) {
                prefs.edit().putString(destKey(format), ASK).apply();
              }
              refreshPrefs();
              refreshConfirm();
            })
        .show();
  }

  private void pickHidden() {
    List<String> pkgs = new ArrayList<>(Apps.userHidden(prefs));
    if (pkgs.isEmpty()) {
      Toast.makeText(this, "没有排除的应用。打开方式里可以排除。", Toast.LENGTH_SHORT).show();
      return;
    }
    String[] labels = new String[pkgs.size()];
    for (int i = 0; i < pkgs.size(); i++) labels[i] = labelOf(pkgs.get(i));
    new AlertDialog.Builder(this)
        .setTitle("点一项即恢复")
        .setItems(
            labels,
            (d, which) -> {
              Apps.unhide(prefs, pkgs.get(which));
              refreshPrefs();
            })
        .show();
  }

  @Override
  public void onBackPressed() {
    if (confirm != null && confirm.getVisibility() == View.VISIBLE) {
      cancelShare();
      return;
    }
    super.onBackPressed();
  }

  private void convertAndOpen(
      String pageUrl, String title, Format format, boolean fromShare, boolean force) {
    Library.Item existing = library.findByUrl(pageUrl);
    long age = existing == null ? Long.MAX_VALUE : System.currentTimeMillis() - existing.updated;
    if (!force
        && existing != null
        && library.has(existing, format)
        && age < SHARE_FRESH_MS) {
      openFile(library.file(existing, format), format, existing.title, fromShare);
      return;
    }
    home.setVisibility(View.GONE);
    confirm.setVisibility(View.GONE);
    converting.setVisibility(View.VISIBLE);
    progress.setVisibility(View.VISIBLE);
    status.setText(existing == null || force ? "成书中" : "转成 " + format.title);
    new Thread(
            () -> {
              try {
                Downloaded downloaded = download(pageUrl, format);
                String savedTitle =
                    downloaded.title != null && !downloaded.title.isEmpty()
                        ? downloaded.title
                        : (title == null || title.isEmpty() ? Library.hostOf(pageUrl) : title);
                Library.Item item = library.save(pageUrl, savedTitle, format, downloaded.body);
                runOnUiThread(
                    () -> openFile(library.file(item, format), format, item.title, fromShare));
              } catch (Exception e) {
                runOnUiThread(
                    () -> {
                      progress.setVisibility(View.GONE);
                      status.setText(e.getMessage());
                    });
              }
            },
            "chengshu-convert")
        .start();
  }

  private void openItem(Library.Item item, Format format) {
    if (library.has(item, format)) {
      library.markOpened(item, format);
      openFile(library.file(item, format), format, item.title, false);
      return;
    }
    convertAndOpen(item.url, item.title, format, false, false);
  }

  private void openFile(File file, Format format, String title, boolean fromShare) {
    File share = titledCopy(file, format, title);
    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", share);
    grantAll(uri, format.mime);
    Intent view = viewIntent(uri, format.mime);
    String stem = share.getName();
    int dot = stem.lastIndexOf('.');
    if (dot > 0) stem = stem.substring(0, dot);
    view.putExtra(Intent.EXTRA_TITLE, stem);
    view.putExtra(Intent.EXTRA_STREAM, uri);
    view.setClipData(ClipData.newRawUri(stem, uri));
    boolean chooser = true;
    String pkg = prefs.getString(destKey(format), ASK);
    if (pkg != null && !pkg.isEmpty() && isInstalled(pkg)) {
      view.setPackage(pkg);
      grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      try {
        startActivity(view);
        chooser = false;
      } catch (ActivityNotFoundException ignored) {
        prefs.edit().putString(destKey(format), ASK).apply();
        view.setPackage(null);
      }
    }
    if (chooser) {
      try {
        Intent picker = Intent.createChooser(view, "打开");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        picker.setClipData(ClipData.newRawUri(stem, uri));
        startActivity(picker);
      } catch (ActivityNotFoundException e) {
        converting.setVisibility(View.VISIBLE);
        home.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        status.setText("没有能打开 " + format.title + " 的应用");
        return;
      }
    }
    if (fromShare) clearShareIntent();
    if (fromShare && ShareFlow.finishActivityAfterOpen(chooser)) {
      finish();
    } else {
      showHome();
    }
  }

  private File titledCopy(File file, Format format, String title) {
    String stem = Library.fileStem(title != null && !title.isEmpty() ? title : stripExt(file.getName()));
    File dir = new File(getCacheDir(), "out");
    if (!dir.isDirectory() && !dir.mkdirs()) return file;
    File out = new File(dir, stem + format.ext);
    try {
      copyFile(file, out);
      return out;
    } catch (Exception e) {
      return file;
    }
  }

  private static void copyFile(File from, File to) throws Exception {
    try (FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to)) {
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
  }

  private static String stripExt(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  private Downloaded download(String pageUrl, Format format) throws Exception {
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
    conn.setRequestProperty("User-Agent", "Chengshu/" + BuildConfig.VERSION_NAME);
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
    if (body.length < 8) throw new RuntimeException("没返回文件");
    if (format.id.equals("epub") && (body[0] != 'P' || body[1] != 'K')) {
      throw new RuntimeException("没返回 EPUB");
    }
    String header = conn.getHeaderField("X-Title");
    String title = "";
    if (header != null && !header.isEmpty()) {
      title = URLDecoder.decode(header, StandardCharsets.UTF_8.name());
    }
    return new Downloaded(body, title);
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

  private String labelOf(String pkg) {
    try {
      PackageManager pm = getPackageManager();
      return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
    } catch (Exception e) {
      return pkg;
    }
  }

  private static String urlOf(Intent intent) {
    if (intent == null) return null;
    Uri data = intent.getData();
    return ShareFlow.extractUrl(
        intent.getStringExtra(Intent.EXTRA_TEXT), data == null ? null : data.toString());
  }

  private static String titleOf(Intent intent) {
    if (intent == null) return "";
    return ShareFlow.extractTitle(
        intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT));
  }

  private void clearShareIntent() {
    Intent clean = new Intent(this, ShareActivity.class);
    clean.setAction(Intent.ACTION_MAIN);
    clean.addCategory(Intent.CATEGORY_LAUNCHER);
    setIntent(clean);
  }

  private void checkUpdate(boolean toastIfCurrent) {
    updateValue.setText("…");
    new Thread(
            () -> {
              try {
                Update.Info info = Update.fetch();
                runOnUiThread(
                    () -> {
                      pendingUpdate = info;
                      if (info.newerThan(BuildConfig.VERSION_CODE)) {
                        updateValue.setText("有 " + info.versionName);
                      } else {
                        updateValue.setText("已是最新");
                        if (toastIfCurrent) {
                          Toast.makeText(this, "已是最新", Toast.LENGTH_SHORT).show();
                        }
                      }
                    });
              } catch (Exception e) {
                runOnUiThread(
                    () -> {
                      updateValue.setText(toastIfCurrent ? "失败" : BuildConfig.VERSION_NAME);
                    });
              }
            },
            "chengshu-update")
        .start();
  }

  private void onUpdateTap() {
    if (pendingUpdate != null && pendingUpdate.newerThan(BuildConfig.VERSION_CODE)) {
      installUpdate(pendingUpdate);
      return;
    }
    checkUpdate(true);
  }

  private void installUpdate(Update.Info info) {
    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
      startActivity(
          new Intent(
              Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
              Uri.parse("package:" + getPackageName())));
      Toast.makeText(this, "允许安装后，再点一次检查更新", Toast.LENGTH_LONG).show();
      return;
    }
    updateValue.setText("下载中");
    new Thread(
            () -> {
              try {
                HttpURLConnection conn = (HttpURLConnection) new URL(info.apk).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                InputStream in = conn.getInputStream();
                File apk = new File(getCacheDir(), "update.apk");
                try (FileOutputStream fos = new FileOutputStream(apk)) {
                  byte[] buf = new byte[16384];
                  int n;
                  while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", apk);
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(uri, "application/vnd.android.package-archive");
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(
                    () -> {
                      updateValue.setText("安装");
                      startActivity(view);
                    });
              } catch (Exception e) {
                runOnUiThread(() -> updateValue.setText("下载失败"));
              }
            },
            "chengshu-apk")
        .start();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static String relative(long t) {
    long d = System.currentTimeMillis() - t;
    if (d < 60_000) return "刚刚";
    if (d < 3_600_000) return (d / 60_000) + " 分钟前";
    if (d < 86_400_000) return (d / 3_600_000) + " 小时前";
    return (d / 86_400_000) + " 天前";
  }

  private static final class Downloaded {
    final byte[] body;
    final String title;

    Downloaded(byte[] body, String title) {
      this.body = body;
      this.title = title;
    }
  }
}
