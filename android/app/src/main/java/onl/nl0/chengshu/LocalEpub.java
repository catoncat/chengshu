package onl.nl0.chengshu;

import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/** Device-side EPUB 2 compiler. No server, font download, analytics or cloud fallback. */
final class LocalEpub {
  static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
  static final int IMAGE_BUDGET = 24 * 1024 * 1024;
  interface ImageLoader { Image load(String url) throws Exception; }
  static final class Image {
    final byte[] bytes;
    final String mime;
    Image(byte[] bytes, String mime) { this.bytes = bytes; this.mime = mime; }
  }
  static final class Result {
    final byte[] bytes;
    final String title;
    final String warning;
    final int embeddedImages;
    final int missingImages;
    Result(byte[] bytes, String title, String warning, int embedded, int missing) {
      this.bytes = bytes; this.title = title; this.warning = warning;
      embeddedImages = embedded; missingImages = missing;
    }
  }
  private static final class Resource {
    final String path;
    final Image image;
    Resource(String path, Image image) { this.path = path; this.image = image; }
  }
  private static final class Chapter {
    final String title, target;
    final int level;
    final List<Chapter> children = new ArrayList<>();
    Chapter(String title, String target, int level) {
      this.title = title; this.target = target; this.level = level;
    }
  }

  static Result build(String url, String title, String byline, String html, ImageLoader loader)
      throws Exception {
    if (html == null || html.length() > 3 * 1024 * 1024)
      throw new IOException("正文过大，未覆盖已保存的文件");
    title = title == null ? "" : title.trim();
    if (title.isEmpty() || "body".equalsIgnoreCase(title)) {
      String host = URI.create(url).getHost();
      title = host == null ? "未命名文章" : host;
    }
    title = xmlCharacters(title);
    byline = xmlCharacters(byline == null ? "" : byline);
    Document source = Jsoup.parseBodyFragment(xmlCharacters(html), url);
    int unsupported = source.select("video,audio,iframe,svg,math,canvas").size();
    source.select("script,style,form,iframe,object,embed,video,audio,svg,math,canvas,noscript").remove();
    for (Element image : source.select("img")) {
      String src = firstImageSource(image);
      String resolved = resolvedImageUrl(src, url);
      image.attr("src", resolved);
    }
    for (Element link : source.select("a[href]")) {
      if (!link.attr("href").startsWith("#")) link.attr("href", link.absUrl("href"));
    }
    for (Element e : source.select("figure,section,article,main")) e.tagName("div");
    for (Element e : source.select("figcaption")) e.tagName("p").addClass("caption");
    for (Element e : source.select("a[name]")) {
      if (e.id().isEmpty()) e.attr("id", e.attr("name"));
      e.removeAttr("name");
    }
    Safelist allowed = Safelist.relaxed()
        .addTags("h1", "h2", "h3", "h4", "h5", "h6", "div", "span", "hr", "sup", "sub", "del")
        .addAttributes(":all", "id", "class")
        .addProtocols("a", "href", "#")
        .addProtocols("img", "src", "data")
        .preserveRelativeLinks(true);
    Document clean = new Cleaner(allowed).clean(source);
    clean.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
        .escapeMode(Entities.EscapeMode.xhtml).charset(StandardCharsets.UTF_8).prettyPrint(false);
    Element body = clean.body();
    paragraphize(body);
    if (body.text().trim().isEmpty()) throw new IOException("没有提取到可阅读的正文");

    Set<String> ids = new HashSet<>();
    ids.add("chengshu-title");
    Map<String, String> renamed = new HashMap<>();
    int sequence = 0;
    for (Element element : body.select("[id]")) {
      String original = element.id();
      String id = original;
      if (!id.matches("[A-Za-z_][A-Za-z0-9._-]*") || ids.contains(id)) {
        do { id = "anchor-" + (++sequence); } while (ids.contains(id));
      }
      ids.add(id);
      renamed.putIfAbsent(original, id);
      element.attr("id", id);
    }
    for (Element link : body.select("a[href^=#]")) {
      String original = link.attr("href").substring(1);
      try { original = URLDecoder.decode(original.replace("+", "%2B"), "UTF-8"); }
      catch (IllegalArgumentException ignored) { /* preserve an invalid escape literally */ }
      if (renamed.containsKey(original)) link.attr("href", "#" + renamed.get(original));
      else link.removeAttr("href"); // Do not ship a known-broken internal jump.
    }
    Chapter toc = new Chapter(title, "chapter.xhtml", 0);
    Deque<Chapter> parents = new ArrayDeque<>();
    parents.push(toc);
    for (Element heading : body.select("h1,h2,h3,h4,h5,h6")) {
      if (heading.text().trim().isEmpty()) continue;
      if (heading.id().isEmpty()) {
        String id;
        do { id = "section-" + (++sequence); } while (ids.contains(id));
        ids.add(id); heading.attr("id", id);
      }
      int level = heading.tagName().charAt(1) - '0';
      Chapter chapter = new Chapter(heading.text(), "chapter.xhtml#" + heading.id(), level);
      while (parents.peek().level >= level) parents.pop();
      parents.peek().children.add(chapter);
      parents.push(chapter);
    }

    Map<String, Resource> resources = fetchImages(body, loader);
    int missing = 0, embedded = 0;
    for (Element image : new ArrayList<>(body.select("img"))) {
      Resource resource = resources.get(image.attr("src"));
      if (resource == null) {
        missing++;
        image.replaceWith(new Element("span").addClass("missing-image")
            .text("[图片未保存" + (image.attr("alt").isEmpty() ? "" : "：" + image.attr("alt")) + "]"));
      } else {
        embedded++;
        image.attr("src", resource.path);
        if (!image.hasAttr("alt")) image.attr("alt", "");
        image.removeAttr("width").removeAttr("height");
      }
    }
    String warning = missing == 0 ? "" : missing + " 张图片未能保存（网络、格式或资源限制），正文已保留。";
    if (unsupported > 0) warning += "有 " + unsupported + " 处交互或特殊媒体未包含，请查看原文。";
    String lang = (title + body.text()).matches("(?s).*[\\u4e00-\\u9fff].*") ? "zh" : "en";
    String identifier = "urn:sha256:" + LocalArchive.digest((url + "\n" + html).getBytes(StandardCharsets.UTF_8));
    String chapter = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"" + lang + "\"><head><title>"
        + xml(title) + "</title><link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head><body>"
        + "<h1 id=\"chengshu-title\">" + xml(title) + "</h1><p class=\"meta\">"
        + (byline.isEmpty() ? "" : xml(byline) + " · ") + "<a href=\"" + xml(url) + "\">原文</a></p>"
        + (warning.isEmpty() ? "" : "<p class=\"warning\">" + xml(warning) + "</p>")
        + body.html() + "</body></html>";
    StringBuilder manifest = new StringBuilder();
    int imageIndex = 0;
    for (Resource resource : resources.values()) manifest.append("<item id=\"img")
        .append(++imageIndex).append("\" href=\"").append(resource.path)
        .append("\" media-type=\"").append(resource.image.mime).append("\"/>");
    String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<package xmlns=\"http://www.idpf.org/2007/opf\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
        + " unique-identifier=\"bookid\" version=\"2.0\"><metadata><dc:identifier id=\"bookid\">"
        + identifier + "</dc:identifier><dc:title>" + xml(title) + "</dc:title><dc:language>" + lang
        + "</dc:language><dc:source>" + xml(url) + "</dc:source>"
        + (byline.isEmpty() ? "" : "<dc:creator>" + xml(byline) + "</dc:creator>")
        + "</metadata><manifest><item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/>"
        + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
        + "<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>" + manifest
        + "</manifest><spine toc=\"ncx\"><itemref idref=\"chapter\"/></spine></package>";
    StringBuilder navigation = new StringBuilder();
    appendNavigation(toc, navigation, new int[] {0});
    String ncx = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><head>"
        + "<meta name=\"dtb:uid\" content=\"" + identifier + "\"/><meta name=\"dtb:depth\" content=\""
        + depth(toc) + "\"/><meta name=\"dtb:totalPageCount\" content=\"0\"/>"
        + "<meta name=\"dtb:maxPageNumber\" content=\"0\"/></head><docTitle><text>" + xml(title)
        + "</text></docTitle><navMap>" + navigation + "</navMap></ncx>";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      byte[] mime = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
      ZipEntry first = new ZipEntry("mimetype");
      first.setMethod(ZipEntry.STORED); first.setSize(mime.length); first.setCompressedSize(mime.length);
      CRC32 crc = new CRC32(); crc.update(mime); first.setCrc(crc.getValue());
      zip.putNextEntry(first); zip.write(mime); zip.closeEntry();
      put(zip, "META-INF/container.xml", "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
      put(zip, "OEBPS/content.opf", opf); put(zip, "OEBPS/toc.ncx", ncx);
      put(zip, "OEBPS/chapter.xhtml", chapter); put(zip, "OEBPS/style.css", CSS);
      for (Resource resource : resources.values()) {
        zip.putNextEntry(new ZipEntry("OEBPS/" + resource.path));
        zip.write(resource.image.bytes); zip.closeEntry();
      }
    }
    return new Result(bytes.toByteArray(), title, warning, embedded, missing);
  }

  private static void paragraphize(Element body) {
    // Work bottom-up; only wrap loose inline runs in block containers, never inside lists or code.
    List<Element> containers = new ArrayList<>(body.select("body,div,blockquote"));
    Collections.reverse(containers);
    for (Element container : containers) {
      Element paragraph = null;
      for (Node node : new ArrayList<>(container.childNodes())) {
        if (node instanceof Element && ((Element) node).isBlock()) { paragraph = null; continue; }
        if (node instanceof TextNode && ((TextNode) node).isBlank() && paragraph == null) continue;
        if (paragraph == null) { paragraph = new Element("p"); node.before(paragraph); }
        paragraph.appendChild(node);
      }
    }
    for (Element paragraph : new ArrayList<>(body.select("p"))) {
      Element current = paragraph;
      boolean sawBreak = false;
      for (Node node : new ArrayList<>(paragraph.childNodes())) {
        boolean br = node instanceof Element && ((Element) node).normalName().equals("br");
        boolean blank = node instanceof TextNode && ((TextNode) node).isBlank();
        if (br && sawBreak) {
          Node last = current.childNodeSize() == 0 ? null : current.childNode(current.childNodeSize() - 1);
          while (last instanceof TextNode && ((TextNode) last).isBlank()) {
            last.remove(); last = current.childNodeSize() == 0 ? null : current.childNode(current.childNodeSize() - 1);
          }
          if (last instanceof Element && ((Element) last).normalName().equals("br")) last.remove();
          Element next = new Element("p"); current.after(next); current = next;
          node.remove(); sawBreak = false; continue;
        }
        if (current != paragraph) current.appendChild(node);
        if (!blank) sawBreak = br;
      }
    }
  }

  private static Map<String, Resource> fetchImages(Element body, ImageLoader loader) throws InterruptedException {
    List<String> urls = new ArrayList<>();
    Set<String> unique = new LinkedHashSet<>();
    for (Element image : body.select("img")) if (!image.attr("src").isEmpty()) unique.add(image.attr("src"));
    urls.addAll(unique);
    Map<String, Resource> resources = new LinkedHashMap<>();
    if (urls.isEmpty()) return resources;
    ExecutorService pool = Executors.newFixedThreadPool(4);
    Deque<Future<Image>> pending = new ArrayDeque<>();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    int submitted = 0, used = 0;
    try {
      while (submitted < Math.min(4, urls.size())) {
        String url = urls.get(submitted++); pending.add(pool.submit(() -> loader.load(url)));
      }
      for (int index = 0; index < urls.size(); index++) {
        if (System.nanoTime() >= deadline || used >= IMAGE_BUDGET) break;
        Future<Image> future = pending.remove();
        try {
          Image image = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
          String ext = extension(image == null ? "" : image.mime);
          if (image != null && ext != null && image.bytes != null && image.bytes.length > 0
              && image.bytes.length <= MAX_IMAGE_BYTES && used + image.bytes.length <= IMAGE_BUDGET) {
            used += image.bytes.length;
            resources.put(urls.get(index), new Resource("images/image-" + index + ext, image));
          }
        } catch (ExecutionException | TimeoutException | CancellationException ignored) {
          future.cancel(true); // Failure becomes a visible placeholder, never a missing silent image.
        }
        if (submitted < urls.size() && used < IMAGE_BUDGET) {
          String url = urls.get(submitted++); pending.add(pool.submit(() -> loader.load(url)));
        }
      }
    } finally {
      for (Future<Image> future : pending) future.cancel(true);
      pool.shutdownNow();
    }
    return resources;
  }

  static String firstImageSource(Element image) {
    for (String name : new String[] {"src", "data-src", "data-original"}) {
      String value = image.attr(name).trim();
      if (!value.isEmpty()) return value;
    }
    return "";
  }

  static String resolvedImageUrl(String src, String pageUrl) {
    if (src == null) return "";
    src = src.trim();
    if (src.isEmpty()) return "";
    if (src.regionMatches(true, 0, "data:", 0, 5)) return src;
    try {
      URI resolved = URI.create(pageUrl).resolve(src);
      String scheme = resolved.getScheme();
      if (scheme == null) return "";
      if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return "";
      return resolved.toString();
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return "";
    }
  }

  static String extension(String mime) {
    switch (mime) {
      case "image/png": return ".png";
      case "image/jpeg": return ".jpg";
      case "image/gif": return ".gif";
      default: return null;
    }
  }
  private static void appendNavigation(Chapter chapter, StringBuilder out, int[] order) {
    int n = ++order[0];
    out.append("<navPoint id=\"nav").append(n).append("\" playOrder=\"").append(n)
        .append("\"><navLabel><text>").append(xml(chapter.title)).append("</text></navLabel><content src=\"")
        .append(xml(chapter.target)).append("\"/>");
    for (Chapter child : chapter.children) appendNavigation(child, out, order);
    out.append("</navPoint>");
  }
  private static int depth(Chapter chapter) {
    int result = 1;
    for (Chapter child : chapter.children) result = Math.max(result, 1 + depth(child));
    return result;
  }
  private static void put(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
  }
  static String xml(String value) {
    return xmlCharacters(value).replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
  }
  private static String xmlCharacters(String value) {
    StringBuilder clean = new StringBuilder(value.length());
    value.codePoints().filter(c -> c == 9 || c == 10 || c == 13 || (c >= 32 && c <= 0xD7FF)
        || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0x10FFFF)).forEach(clean::appendCodePoint);
    return clean.toString();
  }
  private static final String CSS = "body{line-height:1.65;margin:0;padding:0}"
      + "h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.2em 0 .6em}p{margin:.7em 0}"
      + "img{max-width:100%;height:auto}pre{white-space:pre-wrap;overflow-wrap:anywhere}"
      + "table{max-width:100%;border-collapse:collapse}td,th{border:1px solid;padding:.3em}"
      + ".meta,.caption,.warning,.missing-image{font-size:.9em}.caption{text-align:center}"
      + "blockquote{margin:1em;padding-left:1em;border-left:2px solid}a{color:inherit}";
}
