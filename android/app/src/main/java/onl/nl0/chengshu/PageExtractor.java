package onl.nl0.chengshu;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Load the live page in WebView, wait until the DOM settles, then run Defuddle
 * on that document. This is how Firefox / EinkBro reader mode works; Chrome
 * share only gives a URL, so we have to render it ourselves.
 */
final class PageExtractor {
  static final class Article {
    final String title;
    final String byline;
    final String content;
    final String sourceUrl;

    Article(String title, String byline, String content) { this(title, byline, content, ""); }

    Article(String title, String byline, String content, String sourceUrl) {
      this.title = title == null ? "" : title;
      this.byline = byline == null ? "" : byline;
      this.content = content == null ? "" : content;
      this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
    }
  }

  interface Done {
    void onDone(Article article);
  }

  private static final String UA =
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";
  private static final long LIMIT_MS = 16_000;
  private static final long POLL_MS = 400;
  private static final int SETTLE_NEED = 2;
  private static final int MIN_TEXT = 240;

  private static int ticket;
  private static WebView live;
  private static Handler liveHandler;

  private PageExtractor() {}

  static void cancel() {
    ticket++;
    if (liveHandler != null) liveHandler.removeCallbacksAndMessages(null);
    liveHandler = null;
    destroy(live);
    live = null;
  }

  static void cancel(Activity owner) {
    if (live != null && live.getContext() == owner) cancel();
  }

  static void extract(Activity activity, String url, Done done) {
    cancel();
    final int mine = ticket;
    final Handler handler = new Handler(Looper.getMainLooper());
    liveHandler = handler;
    final boolean[] delivered = {false};
    final long start = System.currentTimeMillis();
    WebView web = new WebView(activity);
    live = web;
    ViewGroup root = contentRoot(activity);
    web.setLayoutParams(
        new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    root.addView(web, 0);

    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setUserAgentString(UA);
    s.setMediaPlaybackRequiresUserGesture(true);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

    final int[] lastLen = {0};
    final int[] stable = {0};
    final boolean[] finished = {false};

    Done complete = article -> {
      if (mine != ticket || delivered[0]) return;
      delivered[0] = true;
      handler.removeCallbacksAndMessages(null);
      ticket++;
      destroy(web);
      if (live == web) { live = null; liveHandler = null; }
      done.onDone(article);
    };
    Runnable finishNull = () -> complete.onDone(null);

    Runnable runDefuddle =
        () -> {
          if (mine != ticket) return;
          String lib;
          try {
            lib = readAsset(activity, "defuddle.js");
          } catch (Exception e) {
            finishNull.run();
            return;
          }
          web.evaluateJavascript(
              lib,
              ignored -> {
                if (mine != ticket) return;
                web.evaluateJavascript(
                    PARSE,
                    raw -> {
                      if (mine != ticket) return;
                      Article article = parseArticle(raw);
                      complete.onDone(article);
                    });
              });
        };

    Runnable poll =
        new Runnable() {
          @Override
          public void run() {
            if (mine != ticket) return;
            if (System.currentTimeMillis() - start > LIMIT_MS) {
              runDefuddle.run();
              return;
            }
            web.evaluateJavascript(
                MEASURE,
                raw -> {
                  if (mine != ticket) return;
                  int len = measureLen(raw);
                  if (len >= MIN_TEXT && Math.abs(len - lastLen[0]) < 40) {
                    stable[0]++;
                  } else {
                    stable[0] = 0;
                  }
                  lastLen[0] = len;
                  if (finished[0] && stable[0] >= SETTLE_NEED) {
                    runDefuddle.run();
                    return;
                  }
                  handler.postDelayed(this, POLL_MS);
                });
          }
        };

    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageFinished(WebView view, String loaded) {
            if (mine != ticket) return;
            if (loaded == null || loaded.startsWith("about:")) return;
            if (finished[0]) return;
            finished[0] = true;
            handler.postDelayed(poll, 350);
          }

          @Override
          public void onReceivedError(
              WebView view, WebResourceRequest req, android.webkit.WebResourceError error) {
            if (mine != ticket) return;
            if (req != null && req.isForMainFrame()) handler.post(finishNull);
          }
        });

    handler.postDelayed(
        () -> {
          if (mine != ticket) return;
          if (!finished[0]) runDefuddle.run();
        },
        LIMIT_MS);

    // evaluateJavascript can fail to call back after renderer failure; leave a retryable job.
    handler.postDelayed(finishNull, LIMIT_MS + 4000);
    web.loadUrl(url);
  }

  private static final String MEASURE =
      "(function(){var t=(document.body&&document.body.innerText||'').replace(/\\s+/g,' ').trim();return JSON.stringify({len:t.length});})()";

  private static final String PARSE =
      "(function(){try{var C=globalThis.Defuddle;if(C&&C.default)C=C.default;if(typeof C!=='function')return JSON.stringify({ok:false});var r=new C(document,{url:location.href}).parse();var html=r&&r.content?String(r.content):'';var text=html.replace(/<[^>]+>/g,' ').replace(/\\s+/g,' ').trim();if(text.length<80)return JSON.stringify({ok:false});return JSON.stringify({ok:true,title:(r.title||document.title||'').trim(),byline:(r.author||'').trim(),content:html,url:location.href});}catch(e){return JSON.stringify({ok:false});}})()";

  private static Article parseArticle(String raw) {
    try {
      String json = decodeJs(raw);
      if (json.isEmpty()) return null;
      JSONObject o = new JSONObject(json);
      if (!o.optBoolean("ok")) return null;
      String content = o.optString("content", "");
      if (content.length() < 80) return null;
      return new Article(o.optString("title", ""), o.optString("byline", ""), content, o.optString("url", ""));
    } catch (Exception e) {
      return null;
    }
  }

  private static int measureLen(String raw) {
    try {
      JSONObject o = new JSONObject(decodeJs(raw));
      return o.optInt("len", 0);
    } catch (Exception e) {
      return 0;
    }
  }

  private static String decodeJs(String raw) {
    if (raw == null || raw.equals("null") || raw.equals("undefined")) return "";
    try {
      Object v = new org.json.JSONTokener(raw).nextValue();
      return v == null ? "" : String.valueOf(v);
    } catch (Exception e) {
      return raw;
    }
  }

  private static String readAsset(Activity activity, String name) throws Exception {
    try (InputStream in = activity.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  private static ViewGroup contentRoot(Activity activity) {
    android.view.View content = activity.findViewById(android.R.id.content);
    if (content instanceof ViewGroup) {
      ViewGroup box = (ViewGroup) content;
      if (box.getChildCount() > 0 && box.getChildAt(0) instanceof ViewGroup) {
        return (ViewGroup) box.getChildAt(0);
      }
      return box;
    }
    return (ViewGroup) activity.getWindow().getDecorView();
  }

  private static void destroy(WebView web) {
    if (web == null) return;
    try {
      web.stopLoading();
      web.loadUrl("about:blank");
      ViewGroup parent = (ViewGroup) web.getParent();
      if (parent != null) parent.removeView(web);
      web.destroy();
    } catch (Exception ignored) {
    }
  }
}
