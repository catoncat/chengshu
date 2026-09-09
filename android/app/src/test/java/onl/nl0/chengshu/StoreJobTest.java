package onl.nl0.chengshu;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
  }
}
