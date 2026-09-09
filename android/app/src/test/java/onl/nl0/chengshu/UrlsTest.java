package onl.nl0.chengshu;

import static org.junit.Assert.*;
import org.junit.Test;

public class UrlsTest {
  @Test public void v1AndV2StayAliasedWhenTrackingQueryDiffers() {
    String page = "https://Example.com/a/b/?utm_source=x&ref=keep#/route";
    String v1 = Urls.normalize(page, 1);
    String v2 = Urls.normalize(page, 2);
    assertEquals("https://example.com/a/b", v1);
    assertEquals("https://example.com/a/b?ref=keep#/route", v2);
    assertNotEquals(Urls.articleId(v1), Urls.articleId(v2));
  }

  @Test public void encodedSlashAndTrailingSlashAreStable() {
    assertTrue(Urls.normalize("https://ex.test/wiki/A%2FB").contains("%2F") || Urls.normalize("https://ex.test/wiki/A%2FB").contains("%2f"));
    assertEquals(Urls.normalize("https://ex.test/a"), Urls.normalize("https://ex.test/a/"));
    assertEquals(Urls.normalize("https://EX.test/A"), Urls.normalize("https://ex.test/A"));
  }
}
