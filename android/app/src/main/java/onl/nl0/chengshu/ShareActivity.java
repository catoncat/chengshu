package onl.nl0.chengshu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
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
import android.widget.CheckBox;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.nio.file.Files;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class ShareActivity extends Activity {
  private static final String API = "https://0nl.onl/export";
  private static final String PREFS = "chengshu";
  private static final String KEY_FORMAT = "format";
  private static final String KEY_READER = "reader_package";
  private static final String ASK = "";
  private PendingShares inbox;
  private PendingShares.Job activeJob;
  private boolean extracting;
  private boolean resumed;
  private long inboxRevision = -1;
  private final Handler refreshHandler = new Handler(Looper.getMainLooper());
  private final Runnable refreshPending = new Runnable() {
    @Override public void run() {
      if (resumed && home != null && home.getVisibility() == View.VISIBLE
          && inboxRevision != PendingShares.revision()) {
        inboxRevision = PendingShares.revision(); refreshHistory();
      }
      if (resumed) refreshHandler.postDelayed(this, 1500);
    }
  };

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
  private ArticleRepository articles;
  private ConversionCoordinator coordinator;
  private Update.Info pendingUpdate;
  private String pendingUrl;
  private String pendingTitle;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_share);
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    library = new Library(this);
    articles = new ArticleRepository(new File(getFilesDir(), "chengshu-db"));
    coordinator = new ConversionCoordinator(articles);
    inbox = new PendingShares(new File(getFilesDir(), "pending-shares"));
    migrateLegacy();
    try { articles.importLegacy(library); } catch (Exception ignored) { /* keep the old vault readable */ }
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
    findViewById(R.id.retry).setOnClickListener(v -> {
      if (activeJob != null) startJob(activeJob); else showHome();
    });
    View tryExample = findViewById(R.id.tryExample);
    if (tryExample != null) tryExample.setOnClickListener(v -> runExample());
    View pasteLink = findViewById(R.id.pasteLink);
    if (pasteLink != null) pasteLink.setOnClickListener(v -> pasteLink());
    findViewById(R.id.backToRecent).setOnClickListener(v -> {
      activeJob = null;
      showHome();
    });

    handleIntent(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIntent(intent);
  }

  @Override
  protected void onDestroy() {
    if (extracting && activeJob != null) {
      PageExtractor.cancel(this);
      inbox.release(activeJob);
    }
    refreshHandler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  @Override protected void onResume() {
    super.onResume(); resumed = true; inboxRevision = -1;
    refreshHandler.removeCallbacks(refreshPending);
    refreshHandler.post(refreshPending);
  }

  @Override protected void onPause() {
    resumed = false; refreshHandler.removeCallbacks(refreshPending);
    super.onPause();
  }

  private void handleIntent(Intent intent) {
    String action = intent == null ? null : intent.getAction();
    int flags = intent == null ? 0 : intent.getFlags();
    if (!ShareFlow.shouldConvertShare(action, flags)) { showHome(); return; }
    String pageUrl = urlOf(intent);
    if (pageUrl == null) { showHome(); return; }
    try {
      PendingShares.Job job = inbox.capture(Library.normalizeUrl(pageUrl), pageUrl,
          titleOf(intent), formatIsAsk() ? "" : currentFormat().id, false);
      clearShareIntent(); // Intent consumed only after durable capture.
      if (activeJob != null && (extracting || inbox.running(activeJob)
          || confirm.getVisibility() == View.VISIBLE)) {
        Toast.makeText(this, "链接已接住，会依次处理", Toast.LENGTH_SHORT).show();
        return;
      }
      startJob(job);
    } catch (Exception e) { showFailure(null); }
  }

  private void startJob(PendingShares.Job job) {
    if (activeJob != null && (extracting || inbox.running(activeJob))) {
      Toast.makeText(this, "链接已接住，当前文章完成后可以继续", Toast.LENGTH_SHORT).show();
      return;
    }
    activeJob = job;
    if (inbox.running(job)) {
      Toast.makeText(this, "这篇正在保存，完成后会出现在最近", Toast.LENGTH_LONG).show();
      activeJob = null; showHome(); return;
    }
    if (job.format.isEmpty()) showShareConfirm(job.url, job.title);
    else convertAndOpen(job.url, job.title, Format.of(job.format), true, job.force);
  }

  private boolean startNextJob() {
    try {
      for (PendingShares.Job job : inbox.list()) {
        if ((activeJob == null || !activeJob.id.equals(job.id)) && job.error.isEmpty() && !inbox.running(job)) { startJob(job); return true; }
      }
    } catch (IOException e) { /* Current saved result remains available. */ }
    return false;
  }

  private void migrateLegacy() {
    if (!prefs.contains("dest.epub") && prefs.contains(KEY_READER)) {
      prefs.edit().putString("dest.epub", prefs.getString(KEY_READER, ASK)).apply();
    }
    if (!prefs.getBoolean("migrated_format_ask", false)) {
      prefs.edit().putBoolean("migrated_format_ask", true).putString(KEY_FORMAT, ASK).apply();
    }
  }

  private boolean formatIsAsk() {
    String id = prefs.getString(KEY_FORMAT, ASK);
    return id == null || id.isEmpty();
  }

  private Format currentFormat() {
    if (formatIsAsk()) return Format.EPUB;
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
    activeJob = null; // Keep the captured link in the retryable inbox.
    clearShareIntent();
    showHome();
  }

  private void runExample() {
    new Thread(() -> {
      try {
        String html;
        try (InputStream in = getAssets().open("example-article.html")) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          byte[] buf = new byte[4096];
          int n;
          while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
          html = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        PageExtractor.Article article = new PageExtractor.Article(
            "成书示例：把一篇文章做成书", "", html, "https://0nl.onl/example");
        Downloaded downloaded = download(article.sourceUrl, Format.EPUB, article);
        Library.Item item = library.save(article.sourceUrl, downloaded.title, Format.EPUB, downloaded.body, downloaded.warning);
        runOnUiThread(() -> openSaved(item, Format.EPUB, false));
      } catch (Exception e) {
        runOnUiThread(() -> Toast.makeText(this, "示例没能保存。请检查可用空间。", Toast.LENGTH_LONG).show());
      }
    }, "chengshu-example").start();
  }

  private void pasteLink() {
    android.widget.EditText input = new android.widget.EditText(this);
    input.setHint("https://");
    input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
    new AlertDialog.Builder(this)
        .setTitle("粘贴链接")
        .setMessage("只在你点这一下之后才读取剪贴板。")
        .setView(input)
        .setPositiveButton("读取剪贴板", (d, w) -> {
          android.content.ClipboardManager clipboard =
              (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          CharSequence clip = clipboard != null && clipboard.hasPrimaryClip()
              ? clipboard.getPrimaryClip().getItemAt(0).coerceToText(this) : "";
          input.setText(clip);
        })
        .setNeutralButton("成书", (d, w) -> {
          String url = input.getText() == null ? "" : input.getText().toString().trim();
          if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "请输入 http 或 https 链接", Toast.LENGTH_LONG).show();
            return;
          }
          showShareConfirm(url, "");
        })
        .setNegativeButton("取消", null)
        .show();
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
    try {
      if (((CheckBox) findViewById(R.id.rememberFormat)).isChecked()) {
        prefs.edit().putString(KEY_FORMAT, format.id).apply();
      }
      activeJob = inbox.choose(activeJob, format.id);
      convertAndOpen(pendingUrl, pendingTitle, format, true, false);
    } catch (IOException e) { showFailure(activeJob); }
  }

  private void refreshPrefs() {
    formatValue.setText(formatIsAsk() ? "每次询问" : currentFormat().title);
    Format destFormat = currentFormat();
    String pkg = prefs.getString(destKey(destFormat), ASK);
    destValue.setText(pkg == null || pkg.isEmpty() ? "每次询问" : labelOf(pkg));
    int hidden = Apps.userHidden(prefs).size();
    hiddenValue.setText(hidden == 0 ? "无" : hidden + " 个");
  }

  private void refreshHistory() {
    history.removeAllViews();
    try {
      List<PendingShares.Job> jobs = inbox.list();
      List<Library.Item> items = library.list();
      empty.setVisibility(jobs.isEmpty() && items.isEmpty() ? View.VISIBLE : View.GONE);
      for (PendingShares.Job job : jobs) {
        TextView row = new TextView(this);
        String title = job.title.isEmpty() ? Library.hostOf(job.url) : job.title;
        row.setText(title + "\n" + (inbox.running(job) ? "正在处理" : "未完成 · 点此继续"));
        row.setTextColor(getColor(R.color.ink)); row.setTextSize(16);
        row.setPadding(dp(20), dp(16), dp(20), dp(16)); row.setMinHeight(dp(64));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setOnClickListener(v -> startJob(job));
        row.setOnLongClickListener(v -> {
          if (inbox.running(job)) return true;
          new AlertDialog.Builder(this).setMessage("移除这个待处理链接？已保存的文件不会删除。")
              .setPositiveButton("移除", (d, w) -> {
                try { inbox.complete(job); refreshHistory(); }
                catch (IOException e) { Toast.makeText(this, "未能移除，链接仍保留", Toast.LENGTH_LONG).show(); }
              }).setNegativeButton("保留", null).show();
          return true;
        });
        history.addView(row);
      }
      for (Library.Item item : items) history.addView(historyRow(item));
    } catch (Exception e) {
      empty.setVisibility(View.VISIBLE);
      empty.setText("暂时无法读取保存记录。原文件没有被删除，请重新打开应用重试。");
    }
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
    String[] actions = new String[Format.ALL.length + 4];
    actions[0] = "打开";
    for (int i = 0; i < Format.ALL.length; i++) {
      Format format = Format.ALL[i];
      boolean have = library.has(item, format);
      actions[i + 1] = (have ? "打开 " : "转成 ") + format.title;
    }
    actions[actions.length - 3] = "分享到其他应用";
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
                try { library.delete(item); refreshHistory(); }
                catch (Exception e) { Toast.makeText(this, "删除未完成，原记录仍保留", Toast.LENGTH_LONG).show(); }
              } else if (which == actions.length - 3) {
                shareItem(item);
              } else if (which == actions.length - 2) {
                convertAndOpen(item.url, item.title, Format.of(item.lastFormat), false, true);
              } else {
                openItem(item, Format.ALL[which - 1]);
              }
            })
        .show();
  }

  private void shareItem(Library.Item item) {
    try {
      Format format = Format.of(item.lastFormat);
      File file = titledCopy(library.file(item, format), format, item.title);
      Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
      Intent send = new Intent(Intent.ACTION_SEND).setType(format.mime);
      send.putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TITLE, item.title);
      send.setClipData(ClipData.newRawUri(item.title, uri));
      send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      Intent chooser = Intent.createChooser(send, "分享已保存的文件");
      chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      chooser.setClipData(send.getClipData());
      startActivity(chooser);
    } catch (RuntimeException e) { handoffFailed(); }
  }

  private void pickFormat() {
    String[] labels = new String[Format.ALL.length + 1];
    labels[0] = "每次询问";
    int selected = 0;
    for (int i = 0; i < Format.ALL.length; i++) {
      labels[i + 1] = Format.ALL[i].title;
      if (!formatIsAsk() && Format.ALL[i].id.equals(currentFormat().id)) selected = i + 1;
    }
    new AlertDialog.Builder(this)
        .setTitle("格式")
        .setSingleChoiceItems(
            labels,
            selected,
            (d, which) -> {
              if (which == 0) {
                prefs.edit().putString(KEY_FORMAT, ASK).apply();
              } else {
                prefs.edit().putString(KEY_FORMAT, Format.ALL[which - 1].id).apply();
              }
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
    try {
      if (activeJob != null && (extracting || inbox.running(activeJob))) {
        inbox.capture(Library.normalizeUrl(pageUrl), pageUrl, title, format.id, force);
        Toast.makeText(this, "链接已接住，会依次处理", Toast.LENGTH_SHORT).show();
        return;
      }
      if (activeJob == null || !activeJob.url.equals(pageUrl) || activeJob.force != force) {
        activeJob = inbox.capture(Library.normalizeUrl(pageUrl), pageUrl, title, format.id, force);
      }
      Library.Item existing = library.findByUrl(pageUrl);
      if (!force && existing != null && library.has(existing, format)) {
        if (activeJob != null) inbox.complete(activeJob);
        activeJob = null;
        if (!startNextJob()) openSaved(existing, format, fromShare);
        return;
      }
      if (activeJob == null || !activeJob.url.equals(pageUrl) || activeJob.force != force) {
        activeJob = inbox.capture(Library.normalizeUrl(pageUrl), pageUrl, title, format.id, force);
      }
      activeJob = inbox.choose(activeJob, format.id);
      final PendingShares.Job job = activeJob;
      if (!inbox.claim(job)) { activeJob = null; showHome(); return; }
      home.setVisibility(View.GONE); confirm.setVisibility(View.GONE);
      converting.setVisibility(View.VISIBLE); progress.setVisibility(View.VISIBLE);
      findViewById(R.id.retry).setVisibility(View.GONE);
      findViewById(R.id.backToRecent).setVisibility(View.GONE);
      status.setText("链接已接住，正在读取正文");
      new Thread(() -> {
        try {
          PageExtractor.Article cached = force ? null : library.snapshot(pageUrl);
          runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) { inbox.release(job); return; }
            if (cached != null) { packJob(job, format, cached, fromShare); return; }
            extracting = true;
            PageExtractor.extract(this, pageUrl, article -> {
              extracting = false;
              packJob(job, format, article, fromShare);
            });
          });
        } catch (Exception e) { failJob(job); }
      }, "chengshu-source").start();
    } catch (Exception e) { showFailure(activeJob); }
  }

  private void packJob(PendingShares.Job job, Format format, PageExtractor.Article article, boolean fromShare) {
    if (article == null) { failJob(job); return; }
    status.setText(format == Format.EPUB ? "正文已提取，正在设备上整理图片和目录" : "正文已提取，正在联网生成 " + format.title);
    new Thread(() -> {
      try {
        library.saveSnapshot(job.url, article);
        try { articles.saveSnapshot(job.url, article); } catch (Exception ignored) { /* vault remains source of truth if catalog fails */ }
        Downloaded downloaded = download(job.url, format, article);
        String savedTitle = downloaded.title.isEmpty() ? (job.title.isEmpty() ? Library.hostOf(job.url) : job.title) : downloaded.title;
        Library.Item item = library.save(job.url, savedTitle, format, downloaded.body, downloaded.warning);
        inbox.complete(job); // Acknowledgement happens only after publication.
        inbox.release(job);
        runOnUiThread(() -> {
          if (isDestroyed() || isFinishing() || activeJob == null || !activeJob.id.equals(job.id)) return;
          activeJob = null;
          if (!resumed) { showHome(); return; } // Never steal focus from another application.
          if (startNextJob()) return;
          openSaved(item, format, fromShare);
        });
      } catch (Exception e) { failJob(job); }
    }, "chengshu-convert").start();
  }

  private void failJob(PendingShares.Job job) {
    try { inbox.fail(job); } catch (IOException ignored) { /* Initial capture remains durable. */ }
    inbox.release(job);
    runOnUiThread(() -> {
      if (!isDestroyed() && !isFinishing() && activeJob != null && activeJob.id.equals(job.id)) {
        activeJob = job;
        if (resumed && startNextJob()) return;
        showFailure(job);
      }
    });
  }

  private void showFailure(PendingShares.Job job) {
    home.setVisibility(View.GONE); confirm.setVisibility(View.GONE);
    converting.setVisibility(View.VISIBLE); progress.setVisibility(View.GONE);
    status.setText(job == null ? "未能保存这个链接。请检查可用空间后重新分享。"
        : "这次没能完成，链接和已保存的内容仍在。可以重试，或先回到最近。需要登录的网页请先在浏览器确认能阅读正文。");
    findViewById(R.id.retry).setVisibility(job == null ? View.GONE : View.VISIBLE);
    findViewById(R.id.backToRecent).setVisibility(View.VISIBLE);
  }

  private void openSaved(Library.Item item, Format format, boolean fromShare) {
    String warning = library.warning(item, format);
    if (warning.isEmpty()) { openSavedFile(item, format, fromShare); return; }
    showHome();
    new AlertDialog.Builder(this).setTitle("文件已保存，有内容需要注意").setMessage(warning)
        .setPositiveButton("继续阅读", (d, w) -> openSavedFile(item, format, fromShare))
        .setNegativeButton("保留到最近", null)
        .setNeutralButton("查看原文", (d, w) -> {
          try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.url))); }
          catch (ActivityNotFoundException e) { Toast.makeText(this, "未找到浏览器", Toast.LENGTH_LONG).show(); }
        }).show();
  }

  private void openSavedFile(Library.Item item, Format format, boolean fromShare) {
    try { openFile(library.file(item, format), format, item.title, fromShare); }
    catch (RuntimeException e) { handoffFailed(); }
  }

  private void openItem(Library.Item item, Format format) {
    try {
      if (library.has(item, format)) {
        library.markOpened(item, format);
        openSaved(item, format, false);
        return;
      }
      convertAndOpen(item.url, item.title, format, false, false);
    } catch (RuntimeException e) { handoffFailed(); }
  }

  private void openFile(File file, Format format, String title, boolean fromShare) {
    try { openFileUnchecked(file, format, title, fromShare); }
    catch (RuntimeException e) { handoffFailed(); }
  }

  private void handoffFailed() {
    home.setVisibility(View.GONE); confirm.setVisibility(View.GONE);
    converting.setVisibility(View.VISIBLE); progress.setVisibility(View.GONE);
    status.setText("文件已保存，但这次没能打开阅读器。回到最近可以重新打开，或长按文章分享到其他应用。");
    findViewById(R.id.retry).setVisibility(View.GONE);
    findViewById(R.id.backToRecent).setVisibility(View.VISIBLE);
  }

  private void openFileUnchecked(File file, Format format, String title, boolean fromShare) {
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
      } catch (ActivityNotFoundException | SecurityException ignored) {
        prefs.edit().putString(destKey(format), ASK).apply();
        view.setPackage(null);
      }
    }
    if (chooser) {
      try {
        Intent picker = Intent.createChooser(view, "打开");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        picker.setClipData(ClipData.newRawUri(stem, uri));
        ArrayList<ComponentName> exclude = Apps.excludeComponents(this, format, prefs);
        if (!exclude.isEmpty()) {
          picker.putParcelableArrayListExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, exclude);
        }
        startActivity(picker);
      } catch (ActivityNotFoundException e) {
        converting.setVisibility(View.VISIBLE);
        home.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        handoffFailed();
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
    File dir = new File(new File(getCacheDir(), "out"), Library.idFor(file.getAbsolutePath()));
    if (!dir.isDirectory() && !dir.mkdirs()) return file;
    File out = new File(dir, stem + format.ext);
    try {
      if (!out.isFile() || out.length() != file.length())
        LocalArchive.atomicWrite(out, Files.readAllBytes(file.toPath()));
      return out;
    } catch (Exception e) {
      return file;
    }
  }

  private static String stripExt(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  private Downloaded download(String pageUrl, Format format, PageExtractor.Article article)
      throws Exception {
    if (article == null || article.content.isEmpty()) throw new IOException("没有提取到正文");
    if (format == Format.EPUB) {
      LocalEpub.Result result = LocalEpub.build(article.sourceUrl.isEmpty() ? pageUrl : article.sourceUrl, article.title, article.byline, article.content, EpubImages::load);
      return new Downloaded(result.bytes, result.title, result.warning);
    }
    if (format == Format.TXT || format == Format.MD || format == Format.HTML) {
      LocalPack.Result result = LocalPack.build(format, article.sourceUrl.isEmpty() ? pageUrl : article.sourceUrl, article.title, article.byline, article.content);
      return new Downloaded(result.bytes, result.title, result.warning);
    }
    // PDF still goes to the conversion server with already-extracted HTML.
    return postPack(pageUrl, format, article);
  }

  private Downloaded postPack(String pageUrl, Format format, PageExtractor.Article article)
      throws Exception {
    JSONObject payload = new JSONObject();
    payload.put("url", article.sourceUrl.isEmpty() ? pageUrl : article.sourceUrl);
    payload.put("title", article.title);
    payload.put("byline", article.byline);
    payload.put("html", article.content);
    byte[] sent = payload.toString().getBytes(StandardCharsets.UTF_8);
    String endpoint = API + "?format=" + format.id;
    HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(60000);
    conn.setDoOutput(true);
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    conn.setRequestProperty("User-Agent", "Chengshu/" + BuildConfig.VERSION_NAME);
    conn.setRequestProperty("Accept", format.mime + ",*/*");
    conn.setFixedLengthStreamingMode(sent.length);
    try {
      try (java.io.OutputStream out = conn.getOutputStream()) { out.write(sent); }
      return readDownload(conn, format);
    } catch (Exception e) { conn.disconnect(); throw e; }
  }

  private Downloaded readDownload(HttpURLConnection conn, Format format) throws Exception {
    try {
      int code = conn.getResponseCode();
      if (code < 200 || code >= 300) throw new IOException("联网生成失败，请稍后重试");
      if (conn.getContentLengthLong() > 48L * 1024 * 1024) throw new IOException("返回文件过大");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = conn.getInputStream()) {
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) != -1) {
          if (out.size() + n > 48 * 1024 * 1024) throw new IOException("返回文件过大");
          out.write(buf, 0, n);
        }
      }
      byte[] body = out.toByteArray();
      if (body.length < 8) throw new IOException("没有返回可用文件");
      if (format == Format.PDF && !(body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F'))
        throw new IOException("没有返回 PDF 文件");
      String header = conn.getHeaderField("X-Title");
      String title = header == null ? "" : URLDecoder.decode(header, StandardCharsets.UTF_8.name());
      return new Downloaded(body, title, "");
    } finally { conn.disconnect(); }
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
    if (BuildConfig.APPLICATION_ID.endsWith(".preview")) {
      updateValue.setText("试验版 · 与正式版独立"); return;
    }
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
                File apk = Update.download(info, new File(getCacheDir(), "update.apk"), BuildConfig.VERSION_CODE);
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
    final String warning;

    Downloaded(byte[] body, String title, String warning) {
      this.body = body;
      this.title = title;
      this.warning = warning;
    }
  }
}
