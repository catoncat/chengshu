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
    String kept = Urls.normalize("https://ex.test/wiki/A%2FB");
    assertTrue(kept.contains("%2F") || kept.contains("%2f") || kept.contains("A/B") || kept.contains("A%2FB"));
    assertEquals(Urls.normalize("https://ex.test/a"), Urls.normalize("https://ex.test/a/"));
    assertEquals(Urls.normalize("https://EX.test/A"), Urls.normalize("https://ex.test/A"));
  }
}
