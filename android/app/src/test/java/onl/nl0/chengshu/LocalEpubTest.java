package onl.nl0.chengshu;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

public class LocalEpubTest {
  private static final String URL = "https://example.org/article/index.html";
  private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jmfkAAAAASUVORK5CYII=");
  private LocalEpub.Result build(String html, LocalEpub.ImageLoader loader) throws Exception {
    return LocalEpub.build(URL, "阅读 & <思考>", "作者", html, loader);
  }
  private Map<String, byte[]> unzip(byte[] bytes) throws Exception {
    Map<String, byte[]> files = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry; while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
    }
    return files;
  }
  private String text(Map<String, byte[]> files, String path) { return new String(files.get(path), StandardCharsets.UTF_8); }
  @Test public void containerAndAllDocumentsAreWellFormed() throws Exception {
    LocalEpub.Result result = build("<h2>正文</h2><p>内容 &amp; emoji 🐈</p>", u -> null);
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(result.bytes))) {
      ZipEntry first = zip.getNextEntry(); assertEquals("mimetype", first.getName());
      assertEquals(ZipEntry.STORED, first.getMethod());
      assertTrue(first.getExtra() == null || first.getExtra().length == 0);
      assertEquals("application/epub+zip", new String(zip.readAllBytes(), StandardCharsets.US_ASCII));
    }
    Map<String, byte[]> files = unzip(result.bytes);
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    for (String path : new String[] {"META-INF/container.xml", "OEBPS/content.opf", "OEBPS/toc.ncx", "OEBPS/chapter.xhtml"})
      assertNotNull(factory.newDocumentBuilder().parse(new ByteArrayInputStream(files.get(path))));
    assertTrue(text(files, "OEBPS/content.opf").contains("version=\"2.0\""));
    assertEquals("", result.warning);
  }
  @Test public void nestedContentsAndFootnotesHaveRealTargets() throws Exception {
    Map<String, byte[]> files = unzip(build("<h2 id='起点'>A</h2><h3>B</h3><p id='back'><a href='#注释'>1</a></p>"
        + "<p id='注释'>Note <a href='#back'>back</a></p><h2 id='起点'>C</h2>", u -> null).bytes);
    Document body = Jsoup.parse(text(files, "OEBPS/chapter.xhtml"));
    Set<String> ids = new HashSet<>(); body.select("[id]").forEach(e -> assertTrue(ids.add(e.id())));
    body.select("a[href^=\"#\"]").forEach(a -> assertTrue(ids.contains(a.attr("href").substring(1))));
    Document ncx = Jsoup.parse(text(files, "OEBPS/toc.ncx"), "", org.jsoup.parser.Parser.xmlParser());
    assertEquals(4, ncx.select("navPoint").size());
    assertTrue(ncx.select("navPoint > navPoint > navPoint").size() > 0);
    ncx.select("content").forEach(e -> {
      String target = e.attr("src"); if (target.contains("#")) assertTrue(ids.contains(target.split("#")[1]));
    });
  }
  @Test public void moreThanTwelveImagesAndDuplicateUrlsAreHandled() throws Exception {
    StringBuilder html = new StringBuilder("<p>Image essay</p>");
    for (int i = 0; i < 16; i++) html.append("<img src='../image").append(i).append(".png'>");
    html.append("<img src='../image0.png'>"); AtomicInteger calls = new AtomicInteger();
    LocalEpub.Result result = build(html.toString(), u -> {
      assertTrue(u.startsWith("https://example.org/image")); calls.incrementAndGet();
      return new LocalEpub.Image(PNG, "image/png");
    });
    assertEquals(16, calls.get()); assertEquals(17, result.embeddedImages); assertEquals(0, result.missingImages);
    assertEquals(16, unzip(result.bytes).keySet().stream().filter(n -> n.startsWith("OEBPS/images/")).count());
  }
  @Test public void failuresProduceVisiblePlaceholdersAndPersistentWarning() throws Exception {
    LocalEpub.Result result = build("<p>正文</p><img src='ok.png'><img src='bad.png' alt='步骤二'><img alt='无地址'>",
        u -> { if (u.endsWith("bad.png")) throw new IOException("offline"); return new LocalEpub.Image(PNG, "image/png"); });
    assertEquals(1, result.embeddedImages); assertEquals(2, result.missingImages);
    assertTrue(result.warning.contains("2 张"));
    String chapter = text(unzip(result.bytes), "OEBPS/chapter.xhtml");
    assertTrue(chapter.contains("图片未保存：步骤二")); assertTrue(chapter.contains(result.warning));
  }

  @Test public void blankImageSourcesStayMissingAndNeverFetchTheArticle() throws Exception {
    List<String> requested = new ArrayList<>();
    LocalEpub.Result result = build(
        "<p>正文</p><img src='ok.png'><img src=''><img src='   '><img data-src='  ' data-original='' alt='空'><img alt='无地址'>",
        u -> { requested.add(u); return new LocalEpub.Image(PNG, "image/png"); });
    assertEquals(1, result.embeddedImages);
    assertEquals(4, result.missingImages);
    assertEquals(Collections.singletonList("https://example.org/article/ok.png"), requested);
    assertFalse(requested.contains(URL));
  }

  @Test public void relativeAndDataImageUrlsDoNotCollapseToThePage() throws Exception {
    List<String> requested = new ArrayList<>();
    String data = "data:image/png;base64,aaaa";
    LocalEpub.Result result = build(
        "<p>正文</p><img src='../pic.png'><img src='" + data + "'>",
        u -> { requested.add(u); return new LocalEpub.Image(PNG, "image/png"); });
    assertTrue(requested.contains("https://example.org/pic.png"));
    assertFalse(requested.contains(URL));
    for (String u : requested) assertFalse("article URL must not be fetched as an image: " + u, u.equals(URL));
    assertEquals(data, LocalEpub.resolvedImageUrl(data, URL));
    assertEquals(1, result.embeddedImages);
  }

  @Test public void firstNonBlankLazySourceWinsAndBlanksAreNotThePage() {
    Document doc = Jsoup.parseBodyFragment("<img src='  ' data-src='folder/a.png' data-original='x.png'>", URL);
    assertEquals("folder/a.png", LocalEpub.firstImageSource(doc.selectFirst("img")));
    assertEquals("https://example.org/article/folder/a.png",
        LocalEpub.resolvedImageUrl("folder/a.png", URL));
    assertEquals("", LocalEpub.resolvedImageUrl("", URL));
    assertEquals("", LocalEpub.resolvedImageUrl("   ", URL));
    assertEquals("data:image/png;base64,xx", LocalEpub.resolvedImageUrl("data:image/png;base64,xx", URL));
    assertEquals("", LocalEpub.resolvedImageUrl("javascript:alert(1)", URL));
  }
  @Test public void activeContentAndUnsafeUrlsNeverEnterBook() throws Exception {
    Map<String, byte[]> files = unzip(build("<p onclick='alert(1)'>Safe<script>alert(1)</script>"
        + "<a href='javascript:alert(1)'>bad</a><a href='/good'>good</a></p><iframe src='https://tracker.invalid'></iframe>", u -> null).bytes);
    String chapter = text(files, "OEBPS/chapter.xhtml");
    assertFalse(chapter.contains("<script")); assertFalse(chapter.contains("onclick="));
    assertFalse(chapter.contains("javascript:")); assertFalse(chapter.contains("<iframe"));
    assertTrue(chapter.contains("https://example.org/good")); assertTrue(chapter.contains("特殊媒体"));
  }
  @Test public void oversizedAndUnsupportedImagesDoNotMasqueradeAsSuccess() throws Exception {
    LocalEpub.Result result = build("<p>Images</p><img src='large.png'><img src='vector.svg'>",
        u -> u.endsWith("large.png") ? new LocalEpub.Image(new byte[LocalEpub.MAX_IMAGE_BYTES + 1], "image/png")
            : new LocalEpub.Image(new byte[] {1}, "image/svg+xml"));
    assertEquals(2, result.missingImages); assertEquals(0, result.embeddedImages);
  }
  @Test public void totalImageBudgetIsBoundedAndReported() throws Exception {
    StringBuilder html = new StringBuilder("<p>large images</p>");
    for (int i = 0; i < 10; i++) html.append("<img src='image").append(i).append(".png'>");
    byte[] large = new byte[LocalEpub.MAX_IMAGE_BYTES];
    LocalEpub.Result result = build(html.toString(), u -> new LocalEpub.Image(large, "image/png"));
    assertEquals(6, result.embeddedImages); assertEquals(4, result.missingImages);
  }
  @Test public void invalidXmlCharactersAreRemovedWithoutLosingEmoji() throws Exception {
    String chapter = text(unzip(build("<p>bad\u0001 good 🐈</p>", u -> null).bytes), "OEBPS/chapter.xhtml");
    assertFalse(chapter.contains("\u0001")); assertTrue(chapter.contains("🐈"));
  }
  @Test public void sameSnapshotKeepsStableBookIdentity() throws Exception {
    String a = text(unzip(build("<p>stable</p>", u -> null).bytes), "OEBPS/content.opf");
    String b = text(unzip(build("<p>stable</p>", u -> null).bytes), "OEBPS/content.opf");
    assertEquals(a, b);
  }
  @Test(expected = IOException.class) public void emptyArticleIsNotSuccessfulBook() throws Exception {
    build("<script>alert(1)</script>", u -> null);
  }
  @Test public void doubleBreaksBecomeParagraphsWithoutFlatteningContent() throws Exception {
    Document doc = Jsoup.parse(text(unzip(build("<div>First<br><br>Second <b>bold</b><br><br>Third</div>", u -> null).bytes), "OEBPS/chapter.xhtml"));
    assertEquals(3, doc.select("div > p").size());
    assertEquals("bold", doc.selectFirst("b").text());
  }
  @Test public void tablesListsCodeAndSingleLineBreaksSurvive() throws Exception {
    String html = "<p>a<br>b</p><ol start='3'><li>first</li><li>next</li></ol>"
        + "<table><tr><th>h</th><td>v</td></tr></table><pre><code> a\n  b</code></pre>";
    Document doc = Jsoup.parse(text(unzip(build(html, u -> null).bytes), "OEBPS/chapter.xhtml"));
    assertEquals(1, doc.select("table").size()); assertEquals(2, doc.select("ol li").size());
    assertEquals(1, doc.select("br").size()); assertEquals(" a\n  b", doc.selectFirst("pre").wholeText());
  }
}
