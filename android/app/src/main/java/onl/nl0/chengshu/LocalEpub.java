package onl.nl0.chengshu;

import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  private static final class ChapterDoc {
    final String id, href, title, xhtml;
    ChapterDoc(String id, String href, String title, String xhtml) {
      this.id = id; this.href = href; this.title = title; this.xhtml = xhtml;
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
    promotePictureSources(source);
    for (Element image : source.select("img")) {
      String src = firstImageSource(image);
      String resolved = resolvedImageUrl(src, url);
      image.attr("src", resolved);
    }
    for (Element link : source.select("a[href]")) {
      String href = link.attr("href");
      if (href.startsWith("#")) continue;
      String abs = link.absUrl("href");
      String target = abs.isEmpty() ? href : abs;
      String fragment = fragmentOf(target);
      if (fragment != null && sameDocument(url, target)) link.attr("href", "#" + fragment);
      else if (!abs.isEmpty()) link.attr("href", abs);
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
        .addAttributes("ol", "start", "type")
        .addAttributes("ul", "type")
        .addAttributes("li", "value")
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan", "scope")
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
    for (Element heading : body.select("h1,h2,h3,h4,h5,h6")) {
      if (heading.text().trim().isEmpty()) continue;
      if (heading.id().isEmpty()) {
        String id;
        do { id = "section-" + (++sequence); } while (ids.contains(id));
        ids.add(id); heading.attr("id", id);
      }
    }
    for (Element marked : body.select("p[id],li[id],aside[id]")) {
      String id = marked.id().toLowerCase(java.util.Locale.ROOT);
      if (id.startsWith("fn") || id.startsWith("note") || id.contains("footnote"))
        marked.addClass("footnote");
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

    List<ChapterDoc> chapters = splitChapters(body.html(), title);
    Map<String, String> idFile = idToFile(chapters);
    if (chapters.size() > 1) chapters = rewriteCrossFileLinks(chapters, idFile);

    Chapter toc = new Chapter(title, chapters.get(0).href, 0);
    Deque<Chapter> parents = new ArrayDeque<>();
    parents.push(toc);
    for (Element heading : body.select("h1,h2,h3,h4,h5,h6")) {
      if (heading.text().trim().isEmpty()) continue;
      String file = idFile.getOrDefault(heading.id(), chapters.get(0).href);
      int level = heading.tagName().charAt(1) - '0';
      Chapter chapter = new Chapter(heading.text(), file + "#" + heading.id(), level);
      while (parents.peek().level >= level) parents.pop();
      parents.peek().children.add(chapter);
      parents.push(chapter);
    }

    StringBuilder manifest = new StringBuilder();
    for (ChapterDoc ch : chapters) {
      manifest.append("<item id=\"").append(ch.id).append("\" href=\"").append(ch.href)
          .append("\" media-type=\"application/xhtml+xml\"/>");
    }
    int imageIndex = 0;
    for (Resource resource : resources.values()) manifest.append("<item id=\"img")
        .append(++imageIndex).append("\" href=\"").append(resource.path)
        .append("\" media-type=\"").append(resource.image.mime).append("\"/>");
    StringBuilder spine = new StringBuilder();
    for (ChapterDoc ch : chapters) spine.append("<itemref idref=\"").append(ch.id).append("\"/>");
    String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<package xmlns=\"http://www.idpf.org/2007/opf\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
        + " unique-identifier=\"bookid\" version=\"2.0\"><metadata><dc:identifier id=\"bookid\">"
        + identifier + "</dc:identifier><dc:title>" + xml(title) + "</dc:title><dc:language>" + lang
        + "</dc:language><dc:source>" + xml(url) + "</dc:source>"
        + (byline.isEmpty() ? "" : "<dc:creator>" + xml(byline) + "</dc:creator>")
        + "</metadata><manifest>" + manifest
        + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
        + "<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>"
        + "</manifest><spine toc=\"ncx\">" + spine + "</spine></package>";
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
      boolean firstChapter = true;
      for (ChapterDoc ch : chapters) {
        put(zip, "OEBPS/" + ch.href, wrapXhtml(lang, ch.title, title, byline, url, firstChapter ? warning : "", ch.xhtml, firstChapter));
        firstChapter = false;
      }
      put(zip, "OEBPS/style.css", CSS);
      for (Resource resource : resources.values()) {
        zip.putNextEntry(new ZipEntry("OEBPS/" + resource.path));
        zip.write(resource.image.bytes); zip.closeEntry();
      }
    }
    return new Result(bytes.toByteArray(), title, warning, embedded, missing);
  }

  private static List<ChapterDoc> splitChapters(String xhtml, String bookTitle) {
    String source = xhtml == null ? "" : xhtml.trim();
    if (source.isEmpty()) source = "<p></p>";
    Pattern delim = Pattern.compile("(?i)(<h2\\b[^>]*>[\\s\\S]*?</h2>)");
    Matcher matcher = delim.matcher(source);
    List<String> pieces = new ArrayList<>();
    int last = 0;
    while (matcher.find()) {
      pieces.add(source.substring(last, matcher.start()));
      pieces.add(matcher.group(1));
      last = matcher.end();
    }
    pieces.add(source.substring(last));
    int headings = 0;
    for (int i = 1; i < pieces.size(); i += 2) headings++;
    if (headings < 2) {
      return Collections.singletonList(new ChapterDoc("chapter", "chapter.xhtml", bookTitle, source));
    }
    List<ChapterDoc> chapters = new ArrayList<>();
    String intro = pieces.get(0).trim();
    if (!intro.isEmpty()) {
      chapters.add(new ChapterDoc("ch" + (chapters.size() + 1),
          "chapter-" + (chapters.size() + 1) + ".xhtml", bookTitle, intro));
    }
    for (int i = 1; i < pieces.size(); i += 2) {
      String heading = pieces.get(i);
      String rest = i + 1 < pieces.size() ? pieces.get(i + 1) : "";
      String headingTitle = Jsoup.parse(heading).text().trim();
      if (headingTitle.isEmpty() || "body".equalsIgnoreCase(headingTitle)) headingTitle = bookTitle;
      chapters.add(new ChapterDoc("ch" + (chapters.size() + 1),
          "chapter-" + (chapters.size() + 1) + ".xhtml", headingTitle, (heading + rest).trim()));
    }
    return chapters;
  }

  private static Map<String, String> idToFile(List<ChapterDoc> chapters) {
    Map<String, String> map = new HashMap<>();
    for (ChapterDoc ch : chapters) {
      for (Element e : Jsoup.parseBodyFragment(ch.xhtml).select("[id]")) {
        map.putIfAbsent(e.id(), ch.href);
      }
    }
    return map;
  }

  private static List<ChapterDoc> rewriteCrossFileLinks(List<ChapterDoc> chapters, Map<String, String> idFile) {
    List<ChapterDoc> out = new ArrayList<>();
    for (ChapterDoc ch : chapters) {
      Document frag = Jsoup.parseBodyFragment(ch.xhtml);
      frag.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
          .escapeMode(Entities.EscapeMode.xhtml).prettyPrint(false);
      for (Element a : frag.select("a[href^=#]")) {
        String id = a.attr("href").substring(1);
        String file = idFile.get(id);
        if (file != null && !file.equals(ch.href)) a.attr("href", file + "#" + id);
      }
      out.add(new ChapterDoc(ch.id, ch.href, ch.title, frag.body().html()));
    }
    return out;
  }

  private static String wrapXhtml(String lang, String chapterTitle, String bookTitle, String byline,
      String url, String warning, String inner, boolean first) {
    StringBuilder out = new StringBuilder();
    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    out.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"").append(lang)
        .append("\"><head><title>").append(xml(chapterTitle))
        .append("</title><link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head>");
    out.append("<body title=\"").append(xml(chapterTitle)).append("\">");
    if (first) {
      out.append("<h1 id=\"chengshu-title\">").append(xml(bookTitle)).append("</h1><p class=\"meta\">");
      if (!byline.isEmpty()) out.append(xml(byline)).append(" · ");
      out.append("<a href=\"").append(xml(url)).append("\">原文</a></p>");
      if (!warning.isEmpty()) out.append("<p class=\"warning\">").append(xml(warning)).append("</p>");
    }
    out.append(inner).append("</body></html>");
    return out.toString();
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
    String srcset = image.attr("srcset").trim();
    if (srcset.isEmpty()) srcset = image.attr("data-srcset").trim();
    return pickSrcset(srcset);
  }

  /** Copy the best <source srcset> onto a bare <img> inside <picture>, then unwrap. */
  static void promotePictureSources(Document source) {
    if (source == null) return;
    for (Element picture : new ArrayList<>(source.select("picture"))) {
      Element img = picture.selectFirst("img");
      if (img == null) { picture.remove(); continue; }
      if (firstImageSource(img).isEmpty()) {
        String picked = pickPictureSrcset(picture);
        if (!picked.isEmpty()) img.attr("src", picked);
      }
      picture.replaceWith(img);
    }
  }

  static String pickPictureSrcset(Element picture) {
    if (picture == null) return "";
    StringBuilder raster = new StringBuilder();
    StringBuilder other = new StringBuilder();
    for (Element src : picture.select("source")) {
      String set = src.attr("srcset").trim();
      if (set.isEmpty()) set = src.attr("data-srcset").trim();
      if (set.isEmpty()) continue;
      String type = src.attr("type").toLowerCase(Locale.ROOT);
      boolean exotic = type.contains("webp") || type.contains("avif") || type.contains("svg");
      StringBuilder into = exotic ? other : raster;
      if (into.length() > 0) into.append(", ");
      into.append(set);
    }
    String picked = pickSrcset(raster.length() > 0 ? raster.toString() : other.toString());
    return picked == null ? "" : picked;
  }

  static String pickSrcset(String srcset) {
    if (srcset == null) return "";
    srcset = srcset.trim();
    if (srcset.isEmpty()) return "";
    String best = "";
    double bestScore = -1;
    for (String part : srcset.split(",")) {
      String item = part.trim();
      if (item.isEmpty()) continue;
      int space = -1;
      for (int i = 0; i < item.length(); i++) {
        char c = item.charAt(i);
        if (c == ' ' || c == '\t') { space = i; break; }
      }
      String candidate = space < 0 ? item : item.substring(0, space).trim();
      String descriptor = space < 0 ? "" : item.substring(space).trim();
      if (candidate.isEmpty()) continue;
      double score = 1;
      if (descriptor.endsWith("w") || descriptor.endsWith("x")) {
        try {
          double n = Double.parseDouble(descriptor.substring(0, descriptor.length() - 1));
          score = descriptor.endsWith("w") ? n : n * 10_000;
        } catch (NumberFormatException ignored) { /* keep default score */ }
      }
      if (score >= bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    return best;
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

  static boolean sameDocument(String pageUrl, String linkUrl) {
    try {
      URI page = URI.create(stripFragment(pageUrl));
      URI link = URI.create(stripFragment(linkUrl));
      if (page.getScheme() == null || link.getScheme() == null) return false;
      if (!page.getScheme().equalsIgnoreCase(link.getScheme())) return false;
      String pageHost = page.getHost() == null ? "" : page.getHost();
      String linkHost = link.getHost() == null ? "" : link.getHost();
      if (!pageHost.equalsIgnoreCase(linkHost)) return false;
      int pagePort = page.getPort() == -1 ? defaultPort(page.getScheme()) : page.getPort();
      int linkPort = link.getPort() == -1 ? defaultPort(link.getScheme()) : link.getPort();
      if (pagePort != linkPort) return false;
      return normalizePath(page.getPath()).equals(normalizePath(link.getPath()));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static int defaultPort(String scheme) {
    if ("https".equalsIgnoreCase(scheme)) return 443;
    if ("http".equalsIgnoreCase(scheme)) return 80;
    return -1;
  }

  static String normalizePath(String path) {
    if (path == null || path.isEmpty()) return "/";
    if (path.length() > 1 && path.endsWith("/")) return path.substring(0, path.length() - 1);
    return path;
  }

  static String fragmentOf(String url) {
    if (url == null) return null;
    int hash = url.indexOf('#');
    if (hash < 0 || hash == url.length() - 1) return null;
    return url.substring(hash + 1);
  }

  static String stripFragment(String url) {
    if (url == null) return "";
    int hash = url.indexOf('#');
    return hash < 0 ? url : url.substring(0, hash);
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
      + "h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.2em 0 .6em}p{margin:.7em 0;text-indent:2em;text-align:justify}"
      + "img{max-width:100%;height:auto}pre{white-space:pre-wrap;overflow-wrap:anywhere;text-indent:0}"
      + "table{max-width:100%;border-collapse:collapse}td,th{border:1px solid;padding:.3em}th{font-weight:bold;text-align:left}"
      + "ol,ul{padding-left:1.6em;margin:.7em 0}li{text-indent:0}li p,td p,th p{text-indent:0}"
      + ".meta,.caption,.warning,.missing-image,.footnote{font-size:.9em;text-indent:0}.caption{text-align:center}"
      + "sup{font-size:.75em;line-height:0;vertical-align:super}sub{font-size:.75em;line-height:0;vertical-align:sub}"
      + "blockquote{margin:1em;padding-left:1em;border-left:2px solid}blockquote p{text-indent:0}a{color:inherit}";
}
