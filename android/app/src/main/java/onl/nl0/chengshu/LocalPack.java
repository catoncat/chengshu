package onl.nl0.chengshu;

import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/** Device-side TXT / Markdown / HTML from an already-saved snapshot. Never fetches the page again. */
final class LocalPack {
  static final class Result {
    final byte[] bytes;
    final String title;
    final String warning;
    Result(byte[] bytes, String title, String warning) {
      this.bytes = bytes; this.title = title; this.warning = warning;
    }
  }

  static Result build(Format format, String url, String title, String byline, String html) throws Exception {
    if (html == null || html.trim().isEmpty()) throw new java.io.IOException("没有提取到可阅读的正文");
    title = title == null || title.trim().isEmpty() ? Urls.host(url) : title.trim();
    byline = byline == null ? "" : byline.trim();
    Document source = Jsoup.parseBodyFragment(html, url);
    source.select("script,style,form,iframe,object,embed,noscript").remove();
    if (format == Format.TXT) return new Result(text(title, byline, url, source).getBytes(StandardCharsets.UTF_8), title, "");
    if (format == Format.MD) return new Result(markdown(title, byline, url, source).getBytes(StandardCharsets.UTF_8), title, "");
    if (format == Format.HTML) return new Result(html(title, byline, url, source).getBytes(StandardCharsets.UTF_8), title, "");
    throw new java.io.IOException("这个格式还不能在设备上生成");
  }

  private static String text(String title, String byline, String url, Document source) {
    StringBuilder out = new StringBuilder();
    out.append(title).append("\n");
    if (!byline.isEmpty()) out.append(byline).append("\n");
    out.append(url).append("\n\n");
    for (Element block : source.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td,th")) {
      String t = block.wholeText().replace('\u00a0', ' ').trim();
      if (!t.isEmpty()) out.append(t).append("\n\n");
    }
    String body = out.toString().trim();
    return body.isEmpty() ? source.body().wholeText() : body;
  }

  private static String markdown(String title, String byline, String url, Element source) {
    StringBuilder out = new StringBuilder();
    out.append("# ").append(escapeMd(title)).append("\n\n");
    if (!byline.isEmpty()) out.append("*").append(escapeMd(byline)).append("*\n\n");
    out.append("[原文](").append(url).append(")\n\n");
    walkMd(source.body(), out, 0);
    return out.toString().trim() + "\n";
  }

  private static void walkMd(Element root, StringBuilder out, int depth) {
    for (Node node : root.childNodes()) {
      if (node instanceof TextNode) {
        String t = ((TextNode) node).text();
        if (!t.trim().isEmpty() && root.tagName().equals("body")) out.append(escapeMd(t.trim())).append("\n\n");
        continue;
      }
      if (!(node instanceof Element)) continue;
      Element e = (Element) node;
      String tag = e.tagName();
      if (tag.matches("h[1-6]")) {
        int n = tag.charAt(1) - '0';
        out.append("#".repeat(Math.max(1, n))).append(" ").append(escapeMd(e.text())).append("\n\n");
      } else if (tag.equals("p") || tag.equals("figcaption")) {
        out.append(inlineMd(e)).append("\n\n");
      } else if (tag.equals("pre")) {
        out.append("```\n").append(e.wholeText().replace("```", "'''")).append("\n```\n\n");
      } else if (tag.equals("blockquote")) {
        for (String line : e.text().split("\n")) out.append("> ").append(line).append("\n");
        out.append("\n");
      } else if (tag.equals("li")) {
        out.append("  ".repeat(depth)).append("- ").append(inlineMd(e)).append("\n");
      } else if (tag.equals("ul") || tag.equals("ol")) {
        walkMd(e, out, depth + (tag.equals("body") ? 0 : 1));
        out.append("\n");
      } else if (tag.equals("table")) {
        out.append(e.text()).append("\n\n");
      } else if (tag.equals("img")) {
        String alt = e.attr("alt");
        out.append("![").append(escapeMd(alt.isEmpty() ? "图片" : alt)).append("](").append(e.absUrl("src")).append(")\n\n");
      } else {
        walkMd(e, out, depth);
      }
    }
  }

  private static String inlineMd(Element e) {
    StringBuilder out = new StringBuilder();
    for (Node node : e.childNodes()) {
      if (node instanceof TextNode) out.append(escapeMd(((TextNode) node).text()));
      else if (node instanceof Element) {
        Element child = (Element) node;
        if (child.tagName().equals("a") && child.hasAttr("href"))
          out.append("[").append(escapeMd(child.text())).append("](").append(child.absUrl("href")).append(")");
        else if (child.tagName().equals("code")) out.append("`").append(child.text().replace("`", "'")).append("`");
        else if (child.tagName().equals("strong") || child.tagName().equals("b"))
          out.append("**").append(escapeMd(child.text())).append("**");
        else if (child.tagName().equals("em") || child.tagName().equals("i"))
          out.append("*").append(escapeMd(child.text())).append("*");
        else if (child.tagName().equals("br")) out.append("\n");
        else out.append(inlineMd(child));
      }
    }
    return out.toString().trim();
  }

  private static String escapeMd(String value) {
    return value.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("[", "\\[");
  }

  private static String html(String title, String byline, String url, Document source) {
    Safelist allowed = Safelist.relaxed()
        .addTags("h1", "h2", "h3", "h4", "h5", "h6", "pre", "code", "blockquote", "hr")
        .addAttributes(":all", "id")
        .addProtocols("a", "href", "http", "https", "mailto");
    Document clean = new Cleaner(allowed).clean(source);
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title>"
        + LocalEpub.xml(title) + "</title></head><body><h1>" + LocalEpub.xml(title) + "</h1><p>"
        + LocalEpub.xml(byline) + (byline.isEmpty() ? "" : " · ")
        + "<a href=\"" + LocalEpub.xml(url) + "\">原文</a></p>"
        + clean.body().html() + "</body></html>";
  }
}
