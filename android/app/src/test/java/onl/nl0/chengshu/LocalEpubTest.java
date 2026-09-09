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
    assertTrue(text(files, "OEBPS/chapter.xhtml").contains("body title="));
    assertEquals("", result.warning);
    EpubInspect.Report report = EpubInspect.inspect(result.bytes);
    assertTrue(report.toString(), report.ok());
  }
  @Test public void nestedContentsAndFootnotesHaveRealTargets() throws Exception {
    Map<String, byte[]> files = unzip(build("<h2 id='起点'>A</h2><h3>B</h3><p id='back'><a href='#注释'>1</a></p>"
        + "<p id='注释'>Note <a href='#back'>back</a></p><h2 id='起点'>C</h2>", u -> null).bytes);
    Set<String> ids = new HashSet<>();
    Map<String, Set<String>> idsIn = new HashMap<>();
    for (String path : files.keySet()) {
      if (!path.endsWith(".xhtml")) continue;
      String href = path.substring(path.lastIndexOf('/') + 1);
      Document body = Jsoup.parse(text(files, path));
      Set<String> local = new HashSet<>();
      body.select("[id]").forEach(e -> { assertTrue(ids.add(e.id())); local.add(e.id()); });
      idsIn.put(href, local);
    }
    for (String path : files.keySet()) {
      if (!path.endsWith(".xhtml")) continue;
      String href = path.substring(path.lastIndexOf('/') + 1);
      Document body = Jsoup.parse(text(files, path));
      body.select("a[href]").forEach(a -> {
        String target = a.attr("href");
        if (target.startsWith("#")) assertTrue(href + " " + target, idsIn.get(href).contains(target.substring(1)));
        else if (target.contains("#")) {
          String id = target.substring(target.indexOf('#') + 1);
          assertTrue(id, ids.contains(id));
        }
      });
    }
    Document ncx = Jsoup.parse(text(files, "OEBPS/toc.ncx"), "", org.jsoup.parser.Parser.xmlParser());
    assertEquals(4, ncx.select("navPoint").size());
    assertTrue(ncx.select("navPoint > navPoint > navPoint").size() > 0);
    ncx.select("content").forEach(e -> {
      String target = e.attr("src"); if (target.contains("#")) assertTrue(ids.contains(target.split("#")[1]));
    });
    assertNotNull(files.get("OEBPS/chapter-1.xhtml"));
    assertNotNull(files.get("OEBPS/chapter-2.xhtml"));
    assertNull(files.get("OEBPS/chapter.xhtml"));
    assertTrue(text(files, "OEBPS/chapter-1.xhtml").contains("body title="));
    assertTrue(text(files, "OEBPS/chapter-2.xhtml").contains("title=\"C\""));
  }
  @Test public void twoH2HeadingsBecomeSeparateSpineDocuments() throws Exception {
    Map<String, byte[]> files = unzip(build("<p>引子。</p><h2>第一节</h2><p>甲。</p><h2>第二节</h2><p>乙。</p>", u -> null).bytes);
    String opf = text(files, "OEBPS/content.opf");
    assertTrue(opf.contains("href=\"chapter-1.xhtml\""));
    assertTrue(opf.contains("href=\"chapter-2.xhtml\""));
    assertTrue(opf.contains("href=\"chapter-3.xhtml\""));
    assertTrue(opf.contains("<itemref idref=\"ch1\"/>"));
    assertTrue(opf.contains("<itemref idref=\"ch2\"/>"));
    assertTrue(opf.contains("<itemref idref=\"ch3\"/>"));
    String ncx = text(files, "OEBPS/toc.ncx");
    assertTrue(ncx.contains("第一节"));
    assertTrue(ncx.contains("第二节"));
    assertTrue(text(files, "OEBPS/chapter-1.xhtml").contains("引子"));
    assertTrue(text(files, "OEBPS/chapter-2.xhtml").contains("甲"));
    assertTrue(text(files, "OEBPS/chapter-3.xhtml").contains("乙"));
    assertTrue(text(files, "OEBPS/chapter-2.xhtml").contains("body title=\"第一节\""));
  }
  @Test public void twoH3HeadingsSplitWhenThereAreNotTwoH2s() throws Exception {
    Map<String, byte[]> files = unzip(build("<p>引。</p><h3>甲章</h3><p>甲。</p><h3>乙章</h3><p>乙。</p>", u -> null).bytes);
    assertNotNull(files.get("OEBPS/chapter-2.xhtml"));
    assertTrue(text(files, "OEBPS/chapter-2.xhtml").contains("body title=\"甲章\""));
    assertTrue(text(files, "OEBPS/chapter-3.xhtml").contains("body title=\"乙章\""));
    assertEquals("h3", LocalEpub.chapterSplitTag("<h3>a</h3><h3>b</h3>"));
    assertEquals("h2", LocalEpub.chapterSplitTag("<h2>a</h2><h3>x</h3><h2>b</h2><h3>y</h3>"));
    assertNull(LocalEpub.chapterSplitTag("<h3>only</h3><p>x</p>"));
  }
  @Test public void definitionListsAndTableCaptionsSurvive() throws Exception {
    String html = "<dl><dt>EPUB</dt><dd>电子书容器</dd></dl>"
        + "<table><caption>对照</caption><tr><th>a</th><td>b</td></tr></table>"
        + "<blockquote><p>引文</p><cite>出处</cite></blockquote>";
    Document doc = Jsoup.parse(text(unzip(build(html, u -> null).bytes), "OEBPS/chapter.xhtml"));
    assertEquals("EPUB", doc.selectFirst("dt").text());
    assertEquals("电子书容器", doc.selectFirst("dd").text());
    assertEquals("对照", doc.selectFirst("caption").text());
    assertEquals("出处", doc.selectFirst("cite").text());
    String css = text(unzip(build(html, u -> null).bytes), "OEBPS/style.css");
    assertTrue(css.contains("dt{font-weight:bold"));
    assertTrue(css.contains("caption{caption-side:top"));
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

  @Test public void firstNonBlankLazySourceWinsAndBlanksAreNotThePage() {
    Document doc = Jsoup.parseBodyFragment("<img src='  ' data-src='folder/a.png' data-original='x.png'>", URL);
    assertEquals("folder/a.png", LocalEpub.firstImageSource(doc.selectFirst("img")));
    assertEquals("https://example.org/article/folder/a.png",
        LocalEpub.resolvedImageUrl("folder/a.png", URL));
    assertEquals("", LocalEpub.resolvedImageUrl("", URL));
    assertEquals("", LocalEpub.resolvedImageUrl("   ", URL));
    assertEquals("data:image/png;base64,xx", LocalEpub.resolvedImageUrl("data:image/png;base64,xx", URL));
    assertEquals("", LocalEpub.resolvedImageUrl("javascript:alert(1)", URL));
    Document placeholder = Jsoup.parseBodyFragment(
        "<img src='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==' data-src='real.png'>",
        URL);
    assertEquals("real.png", LocalEpub.firstImageSource(placeholder.selectFirst("img")));
    Document lazy = Jsoup.parseBodyFragment("<img data-lazy-src='later.jpg'>", URL);
    assertEquals("later.jpg", LocalEpub.firstImageSource(lazy.selectFirst("img")));
    Document onlyData = Jsoup.parseBodyFragment("<img src='data:image/png;base64,xx'>", URL);
    assertEquals("data:image/png;base64,xx", LocalEpub.firstImageSource(onlyData.selectFirst("img")));
    assertTrue(LocalEpub.isDataUri("data:image/png;base64,xx"));
    assertFalse(LocalEpub.isDataUri("https://example.org/a.png"));
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
        + "<table><thead><tr><th colspan='2'>h</th></tr></thead><tbody><tr><td>v</td><td>w</td></tr></tbody></table>"
        + "<pre><code> a\n  b</code></pre>";
    Document doc = Jsoup.parse(text(unzip(build(html, u -> null).bytes), "OEBPS/chapter.xhtml"));
    assertEquals(1, doc.select("table").size()); assertEquals(2, doc.select("ol li").size());
    assertEquals("3", doc.selectFirst("ol").attr("start"));
    assertEquals("2", doc.selectFirst("th").attr("colspan"));
    assertEquals("h", doc.selectFirst("th").text());
    assertEquals(1, doc.select("br").size()); assertEquals(" a\n  b", doc.selectFirst("pre").wholeText());
    String css = text(unzip(build(html, u -> null).bytes), "OEBPS/style.css");
    assertTrue(css.contains("li{text-indent:0}"));
  }
  @Test public void nestedListsKeepStructure() throws Exception {
    String html = "<ul><li>外层<ol><li>内一</li><li>内二</li></ol></li><li>另一项</li></ul>";
    Document doc = Jsoup.parse(text(unzip(build(html, u -> null).bytes), "OEBPS/chapter.xhtml"));
    assertEquals(1, doc.select("ul > li > ol").size());
    assertEquals(2, doc.select("ul > li > ol > li").size());
    assertEquals(2, doc.select("ul > li").size());
  }

  @Test public void srcsetPicksLargestCandidateWhenSrcIsBlank() {
    Document width = Jsoup.parseBodyFragment(
        "<img src='  ' srcset='small.png 320w, large.png 1280w, medium.png 640w'>", URL);
    assertEquals("large.png", LocalEpub.firstImageSource(width.selectFirst("img")));
    Document density = Jsoup.parseBodyFragment("<img srcset='a.png 1x, b.png 2x'>", URL);
    assertEquals("b.png", LocalEpub.firstImageSource(density.selectFirst("img")));
    Document lazy = Jsoup.parseBodyFragment("<img data-srcset='lazy.png 1x'>", URL);
    assertEquals("lazy.png", LocalEpub.firstImageSource(lazy.selectFirst("img")));
    Document preferSrc = Jsoup.parseBodyFragment(
        "<img src='plain.png' srcset='big.png 2x'>", URL);
    assertEquals("plain.png", LocalEpub.firstImageSource(preferSrc.selectFirst("img")));
    assertEquals("only.png", LocalEpub.pickSrcset("only.png"));
    assertEquals("", LocalEpub.pickSrcset("  "));
  }

  @Test public void pictureSourcePrefersJpegOverWebpWhenImgSrcIsBlank() {
    Document doc = Jsoup.parseBodyFragment(
        "<picture>"
            + "<source type='image/webp' srcset='a.webp 640w, b.webp 1280w'/>"
            + "<source type='image/jpeg' srcset='small.jpg 320w, large.jpg 1600w'/>"
            + "<img alt='x'>"
            + "</picture>",
        URL);
    LocalEpub.promotePictureSources(doc);
    org.jsoup.nodes.Element img = doc.selectFirst("img");
    assertNotNull(img);
    assertEquals("large.jpg", img.attr("src"));
    assertEquals(0, doc.select("picture").size());
    Document keep = Jsoup.parseBodyFragment(
        "<picture><source srcset='other.jpg 2x'/><img src='plain.jpg'></picture>", URL);
    LocalEpub.promotePictureSources(keep);
    assertEquals("plain.jpg", keep.selectFirst("img").attr("src"));
    Document placeholder = Jsoup.parseBodyFragment(
        "<picture><source type='image/jpeg' srcset='photo.jpg 800w'/><img src='data:image/gif;base64,xx'></picture>",
        URL);
    LocalEpub.promotePictureSources(placeholder);
    assertEquals("photo.jpg", placeholder.selectFirst("img").attr("src"));
  }

  @Test public void pictureSourceImageIsEmbeddedAndResolvedAgainstTheArticle() throws Exception {
    List<String> requested = new ArrayList<>();
    LocalEpub.Result result = build(
        "<p>图</p><picture><source type='image/jpeg' srcset='hero.jpg 1200w'/><img alt='hero'></picture>",
        u -> { requested.add(u); return new LocalEpub.Image(PNG, "image/png"); });
    assertEquals(Collections.singletonList("https://example.org/article/hero.jpg"), requested);
    assertEquals(1, result.embeddedImages);
    assertEquals(0, result.missingImages);
  }

  @Test public void srcsetImageIsEmbeddedAndResolvedAgainstTheArticle() throws Exception {
    List<String> requested = new ArrayList<>();
    LocalEpub.Result result = build(
        "<p>图</p><img srcset='wide.png 1280w, tiny.png 80w' alt='宽图'>",
        u -> { requested.add(u); return new LocalEpub.Image(PNG, "image/png"); });
    assertEquals(Collections.singletonList("https://example.org/article/wide.png"), requested);
    assertEquals(1, result.embeddedImages);
    assertEquals(0, result.missingImages);
  }

  @Test public void samePageAbsoluteAnchorsBecomeInBookJumps() throws Exception {
    assertTrue(LocalEpub.sameDocument(URL, "https://example.org/article/index.html#注释"));
    assertTrue(LocalEpub.sameDocument(URL, "https://example.org/article/index.html/#note-1"));
    assertFalse(LocalEpub.sameDocument(URL, "https://evil.example/article/index.html#note-1"));
    assertFalse(LocalEpub.sameDocument(URL, "https://example.org/else#note-1"));
    assertEquals("注释", LocalEpub.fragmentOf("https://example.org/article/index.html#注释"));

    String html = "<h2>第一节</h2><p id='back'><a href='https://example.org/article/index.html#注释'>1</a></p>"
        + "<h2>第二节</h2><p id='注释'>脚注 <a href='/article/index.html#back'>back</a></p>"
        + "<p><a href='https://evil.example/phish#注释'>站外</a></p>"
        + "<p><a href='https://example.org/else#back'>它页</a></p>";
    Map<String, byte[]> files = unzip(build(html, u -> null).bytes);
    String ch1 = text(files, "OEBPS/chapter-1.xhtml");
    String ch2 = text(files, "OEBPS/chapter-2.xhtml");
    assertTrue(ch1.contains("chapter-2.xhtml#"));
    assertTrue(ch2.contains("chapter-1.xhtml#back"));
    assertFalse(ch1.contains("https://example.org/article/index.html#"));
    assertFalse(ch2.contains("https://example.org/article/index.html#"));
    assertTrue(ch2.contains("https://evil.example/phish#"));
    assertTrue(ch2.contains("https://example.org/else#back"));
  }
}
