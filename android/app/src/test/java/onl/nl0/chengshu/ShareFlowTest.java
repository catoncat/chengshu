package onl.nl0.chengshu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShareFlowTest {
  @Test
  public void chromeShareOffersExportNotAutoConvert() {
    assertTrue(ShareFlow.shouldConvertShare(ShareFlow.ACTION_SEND, ShareFlow.FLAG_NEW_TASK));
    assertTrue(ShareFlow.shouldConvertShare(ShareFlow.ACTION_SEND, 0));
    assertFalse(ShareFlow.autoConvertOnShare());
  }

  @Test
  public void recentsRelaunchDoesNotConvert() {
    assertFalse(
        ShareFlow.shouldConvertShare(
            ShareFlow.ACTION_SEND, ShareFlow.FLAG_LAUNCHED_FROM_HISTORY));
    assertFalse(
        ShareFlow.shouldConvertShare(
            ShareFlow.ACTION_SEND,
            ShareFlow.FLAG_NEW_TASK | ShareFlow.FLAG_LAUNCHED_FROM_HISTORY));
  }

  @Test
  public void launcherDoesNotConvert() {
    assertFalse(ShareFlow.shouldConvertShare("android.intent.action.MAIN", 0));
    assertFalse(ShareFlow.shouldConvertShare(null, 0));
  }

  @Test
  public void neverRemoveTaskAfterOpen() {
    assertFalse(ShareFlow.removeTaskAfterOpen());
  }

  @Test
  public void chooserKeepsActivityAlive() {
    assertFalse(ShareFlow.finishActivityAfterOpen(true));
  }

  @Test
  public void rememberedReaderAlsoKeepsActivityAlive() {
    assertFalse(ShareFlow.finishActivityAfterOpen(false));
  }

  @Test
  public void extractsUrlFromChromeShareText() {
    assertEquals("https://0nl.onl/", ShareFlow.extractUrl("https://0nl.onl/", null));
    assertEquals(
        "https://0nl.onl/", ShareFlow.extractUrl("成书\nhttps://0nl.onl/", null));
    assertEquals("https://zh.wikipedia.org/wiki/EPUB", ShareFlow.extractUrl("see https://zh.wikipedia.org/wiki/EPUB。", null));
  }

  @Test
  public void shareFileNameIsTheBookTitleNotBody() {
    assertEquals("成书.epub", ShareFlow.shareFileName("成书", ".epub"));
    assertEquals("book.epub", ShareFlow.shareFileName("body", ".epub"));
    assertEquals("book.epub", ShareFlow.shareFileName("", ".epub"));
    assertEquals("book.epub", ShareFlow.shareFileName(null, ".epub"));
  }
}
