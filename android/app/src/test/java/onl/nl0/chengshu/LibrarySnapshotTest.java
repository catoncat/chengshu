package onl.nl0.chengshu;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LibrarySnapshotTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
  @Test public void failedRefreshKeepsOldBookAndNewSnapshotRetryable() throws Exception {
    File files = temporary.newFolder(); Library library = new Library(files);
    String url = "https://example.org/story";
    Library.Item old = library.save(url, "old title", Format.EPUB, bytes("old valid book"));
    library.saveSnapshot(url, new PageExtractor.Article("new title", "author", "<p>new extracted content</p>", "https://example.org/redirected"));
    Library restored = new Library(files);
    Library.Item visible = restored.findByUrl(url);
    assertEquals("old title", visible.title);
    assertEquals("old valid book", new String(Files.readAllBytes(restored.file(visible, Format.EPUB).toPath()), StandardCharsets.UTF_8));
    assertEquals("new title", restored.snapshot(url).title);
    assertEquals("https://example.org/redirected", restored.snapshot(url).sourceUrl);
    assertEquals(old.id, visible.id);
  }
  @Test public void snapshotAloneIsNotPresentedAsCompletedBook() throws Exception {
    Library library = new Library(temporary.newFolder());
    library.saveSnapshot("https://example.org", new PageExtractor.Article("title", "", "<p>content</p>"));
    assertTrue(library.list().isEmpty()); assertNotNull(library.snapshot("https://example.org"));
  }
  @Test public void multipleFormatsAndWarningsSurviveRecreation() throws Exception {
    File files = temporary.newFolder(); Library library = new Library(files); String url = "https://example.org/a";
    library.save(url, "first", Format.EPUB, bytes("epub result"), "2 张图片未能保存");
    library.save(url, "changed", Format.PDF, bytes("%PDF-result"));
    Library restored = new Library(files); Library.Item item = restored.findByUrl(url);
    assertTrue(restored.has(item, Format.EPUB)); assertTrue(restored.has(item, Format.PDF));
    assertEquals("2 张图片未能保存", restored.warning(item, Format.EPUB));
    assertEquals("", restored.warning(item, Format.PDF));
  }
  @Test public void failedEmptySaveDoesNotRelabelOrDeleteOldBook() throws Exception {
    Library library = new Library(temporary.newFolder()); String url = "https://example.org/a";
    library.save(url, "old", Format.EPUB, bytes("old body"));
    try { library.save(url, "bad new title", Format.EPUB, new byte[0]); fail(); }
    catch (IOException expected) { }
    Library.Item item = library.findByUrl(url);
    assertEquals("old", item.title); assertEquals("old body", new String(Files.readAllBytes(library.file(item, Format.EPUB).toPath()), StandardCharsets.UTF_8));
  }
  @Test public void deleteRemovesSourceAndFormats() throws Exception {
    Library library = new Library(temporary.newFolder()); String url = "https://example.org/a";
    library.saveSnapshot(url, new PageExtractor.Article("title", "", "<p>content</p>"));
    Library.Item item = library.save(url, "title", Format.EPUB, bytes("body"));
    library.delete(item); assertTrue(library.list().isEmpty()); assertNull(library.snapshot(url));
  }
}
