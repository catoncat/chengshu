package onl.nl0.chengshu;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StoreJobTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

  @Test public void cancelledOwnerCannotPublishOverNewRevision() throws Exception {
    ArticleRepository articles = new ArticleRepository(temporary.newFolder());
    String url = "https://example.org/a";
    PageExtractor.Article article = new PageExtractor.Article("title", "", "<p>hello world content</p>", url);
    ConversionCoordinator coordinator = new ConversionCoordinator(articles);
    JobRepository.Job job = coordinator.enqueueSavedSnapshot(url, "epub", article);
    articles.jobs.cancel(job.id);
    try {
      coordinator.runInline(job, article, u -> new LocalEpub.Image(new byte[] {(byte)0x89,'P','N','G',13,10,26,10,0}, "image/png"));
      fail("stale owner must not publish");
    } catch (IllegalStateException expected) { }
    assertTrue(articles.visibleArticles().isEmpty());
    assertEquals(0, articles.store.list("artifacts").size());
  }

  @Test public void snapshotThenPackDoesNotNeedActivityAndKeepsReceipt() throws Exception {
    File root = temporary.newFolder();
    ArticleRepository articles = new ArticleRepository(root);
    String url = "https://example.org/book";
    PageExtractor.Article article = new PageExtractor.Article("目录", "作者",
        "<h2>一</h2><p>第一段。</p><h2>二</h2><p>第二段 <a href='#n'>注</a></p><p id='n'>脚注</p>", url);
    ConversionCoordinator coordinator = new ConversionCoordinator(articles);
    JobRepository.Job job = coordinator.enqueueSavedSnapshot(url, "epub", article);
    File epub = coordinator.runInline(job, article, u -> { throw new java.io.IOException("offline"); });
    assertTrue(epub.isFile());
    assertTrue(epub.length() > 100);
    JobRepository.Job saved = articles.jobs.get(job.id);
    assertEquals(JobRepository.SAVED, saved.state);
    assertFalse(saved.receiptId.isEmpty());
    File again = coordinator.runInline(saved, article, u -> { fail("must not rebuild"); return null; });
    assertEquals(epub.getName(), again.getName());
  }

  @Test public void markdownAndHtmlComeFromTheSameSnapshotOffline() throws Exception {
    ArticleRepository articles = new ArticleRepository(temporary.newFolder());
    String url = "https://example.org/note";
    PageExtractor.Article article = new PageExtractor.Article("笔记", "",
        "<h2>Code</h2><pre><code> a\n  b</code></pre><p>见 <a href='/x'>链接</a></p>", url);
    articles.saveSnapshot(url, article);
    LocalPack.Result md = LocalPack.build(Format.MD, url, article.title, article.byline, article.content);
    LocalPack.Result html = LocalPack.build(Format.HTML, url, article.title, article.byline, article.content);
    LocalPack.Result txt = LocalPack.build(Format.TXT, url, article.title, article.byline, article.content);
    String mdText = new String(md.bytes, StandardCharsets.UTF_8);
    assertTrue(mdText.contains("```"));
    assertTrue(mdText.contains(" a\n  b"));
    assertTrue(new String(html.bytes, StandardCharsets.UTF_8).contains("<h2>"));
    assertFalse(new String(html.bytes, StandardCharsets.UTF_8).contains("<script"));
    assertTrue(new String(txt.bytes, StandardCharsets.UTF_8).contains("笔记"));
  }

  @Test public void deleteIncrementsRevisionSoOldCommitFails() throws Exception {
    ArticleRepository articles = new ArticleRepository(temporary.newFolder());
    String url = "https://example.org/del";
    PageExtractor.Article article = new PageExtractor.Article("t", "", "<p>keep this article text here</p>", url);
    ConversionCoordinator coordinator = new ConversionCoordinator(articles);
    JobRepository.Job job = coordinator.enqueueSavedSnapshot(url, "txt", article);
    String articleId = articles.ensureArticle(url, "t");
    articles.delete(articleId);
    try {
      coordinator.runInline(job, article, u -> null);
      fail();
    } catch (IllegalStateException expected) { }
    assertTrue(articles.visibleArticles().isEmpty());
    assertEquals(0, articles.store.list("artifacts").size());
  }

  @Test public void legacyLibraryMigratesWithoutCopyingAwayTheOriginalBytes() throws Exception {
    File files = temporary.newFolder();
    Library library = new Library(files);
    String url = "https://example.org/old";
    Library.Item item = library.save(url, "旧篇 🐈", Format.EPUB, bytes("old-epub-bytes"));
    byte[] original = Files.readAllBytes(library.file(item, Format.EPUB).toPath());
    ArticleRepository articles = new ArticleRepository(new File(files, "chengshu-db"));
    articles.importLegacy(library);
    assertEquals(1, articles.visibleArticles().size());
    assertArrayEquals(original, Files.readAllBytes(library.file(item, Format.EPUB).toPath()));
  }

  @Test public void qualityReportDoesNotCallLoginPagesSuccess() {
    QualityReport login = QualityReport.evaluate("<p>请登录 后继续阅读</p>", 0, 0);
    assertTrue(login.blocking());
    QualityReport ok = QualityReport.evaluate("<p>" + "正文".repeat(40) + "</p>", 1, 0);
    assertTrue(ok.empty());
    QualityReport missing = QualityReport.evaluate("<p>" + "正文".repeat(40) + "</p>", 1, 2);
    assertTrue(missing.summary().contains("2 张"));
    assertFalse(missing.blocking());
  }

  @Test public void failuresMapNetworkStorageAndLoginWithoutPretendingSuccess() {
    assertEquals(Failures.NETWORK, Failures.code(new java.net.UnknownHostException("x")));
    assertEquals(Failures.NETWORK, Failures.code(new java.io.InterruptedIOException()));
    assertEquals(Failures.STORAGE, Failures.code(new java.io.IOException("No space left on device")));
    assertEquals(Failures.EMPTY, Failures.code(new java.io.IOException("没有提取到可阅读的正文")));
    assertEquals(Failures.AUTH, QualityReport.evaluate("<p>请登录 后继续阅读</p>", 0, 0).failureCode());
    assertTrue(Failures.message(Failures.NETWORK).contains("等待网络"));
    assertTrue(Failures.attention(Failures.AUTH).contains("浏览器"));
  }

  @Test public void imageRepositoryReusesCachedBytesWithoutRefetch() throws Exception {
    ArticleRepository articles = new ArticleRepository(temporary.newFolder());
    java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
    ImageRepository cache = new ImageRepository(articles.store.blobs, u -> {
      loads.incrementAndGet();
      return new LocalEpub.Image(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, "image/png");
    });
    LocalEpub.Image first = cache.load("https://example.org/a.png");
    LocalEpub.Image second = cache.load("https://example.org/a.png");
    assertEquals(1, loads.get());
    assertEquals(1, cache.fetches());
    assertArrayEquals(first.bytes, second.bytes);
  }

  @Test public void gcDryRunDoesNotDeleteReferencedBlobs() throws Exception {
    File root = temporary.newFolder();
    ArticleRepository articles = new ArticleRepository(root);
    String url = "https://example.org/gc";
    articles.saveSnapshot(url, new PageExtractor.Article("t", "", "<p>snapshot text for gc</p>", url));
    articles.store.blobs.put("orphan".getBytes(StandardCharsets.UTF_8));
    Gc.Report report = Gc.dryRun(articles);
    assertEquals(1, report.files);
    Gc.sweep(articles, true);
    assertEquals(0, Gc.dryRun(articles).files);
    assertNotNull(articles.snapshot(url));
  }

  @Test public void captureWithoutActivityIsNeedsUser() {
    assertEquals(JobRepository.NEEDS_USER, CaptureController.experimentResult().fallback);
    assertFalse(CaptureController.experimentResult().canCaptureInBackground);
  }

  @Test public void fixtureExamplePacksOfflineWithSectionsAndCode() throws Exception {
    java.io.InputStream in = StoreJobTest.class.getResourceAsStream("/example.html");
    assertNotNull(in);
    String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    LocalEpub.Result epub = LocalEpub.build("https://0nl.onl/example", "成书示例：把一篇文章做成书", "", html,
        u -> new LocalEpub.Image(new byte[] {(byte)0x89,'P','N','G',13,10,26,10,0,0,0,0}, "image/png"));
    assertTrue(epub.bytes.length > 200);
    LocalPack.Result md = LocalPack.build(Format.MD, "https://0nl.onl/example", "成书示例：把一篇文章做成书", "", html);
    String text = new String(md.bytes, StandardCharsets.UTF_8);
    assertTrue(text.contains("为什么要本地保存"));
    assertTrue(text.contains("```"));
    EpubInspect.Report report = EpubInspect.inspect(epub.bytes);
    assertTrue(report.toString(), report.ok());
  }

  @Test public void notifierAggregatesThreeSavesAndCoordinatorNotifiesOnce() throws Exception {
    ResultsNotifier.Notice three = ResultsNotifier.summarize(java.util.Arrays.asList(
        new ResultsNotifier.Event("saved", "甲", "epub", "1"),
        new ResultsNotifier.Event("saved", "乙", "epub", "2"),
        new ResultsNotifier.Event("saved", "丙", "epub", "3")));
    assertEquals("3 篇文章已保存", three.title);
    assertEquals("", three.itemId);
    ResultsNotifier.Notice mixed = ResultsNotifier.summarize(java.util.Arrays.asList(
        new ResultsNotifier.Event("saved", "甲", "epub", "1"),
        new ResultsNotifier.Event("failed", "乙", "FAILED", "")));
    assertEquals("mixed", mixed.kind);
    List<ResultsNotifier.Notice> notices = new ArrayList<>();
    ResultsNotifier notifier = new ResultsNotifier(notices::add);
    ArticleRepository articles = new ArticleRepository(temporary.newFolder());
    String url = "https://example.org/note-notify";
    PageExtractor.Article article = new PageExtractor.Article("通知篇", "", "<p>hello world content for notify</p>", url);
    ConversionCoordinator coordinator = new ConversionCoordinator(articles, notifier);
    JobRepository.Job job = coordinator.enqueueSavedSnapshot(url, "txt", article);
    coordinator.runInline(job, article, u -> null);
    assertEquals(1, notices.size());
    assertEquals("saved", notices.get(0).kind);
    coordinator.runInline(articles.jobs.get(job.id), article, u -> { fail("must not rebuild"); return null; });
    assertEquals(1, notices.size());
  }

  @Test public void imageCacheReusesBytesAndCancelStopsNewLoads() throws Exception {
    java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
    byte[] png = new byte[] {(byte)0x89,'P','N','G',13,10,26,10,0,0,0,0};
    ArticleRepository articles = new ArticleRepository(temporary.newFolder());
    ImageRepository images = new ImageRepository(articles.store.blobs, u -> {
      loads.incrementAndGet();
      return new LocalEpub.Image(png, "image/png");
    });
    String url = "https://example.org/pic.png";
    LocalEpub.Image first = images.load(url);
    LocalEpub.Image second = images.load(url);
    assertEquals(1, loads.get());
    assertArrayEquals(first.bytes, second.bytes);
    images.cancel();
    try {
      images.load("https://example.org/other.png");
      fail();
    } catch (java.io.InterruptedIOException expected) { }
    assertEquals(1, loads.get());
  }
}
