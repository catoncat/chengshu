package onl.nl0.chengshu;

final class Format {
  final String id;
  final String title;
  final String hint;
  final String mime;
  final String ext;
  final String[] extraMimes;

  private Format(String id, String title, String hint, String mime, String ext, String[] extraMimes) {
    this.id = id;
    this.title = title;
    this.hint = hint;
    this.mime = mime;
    this.ext = ext;
    this.extraMimes = extraMimes;
  }

  static final Format EPUB =
      new Format("epub", "EPUB", "阅读器翻页的书", "application/epub+zip", ".epub", new String[] {"application/epub"});
  static final Format PDF =
      new Format("pdf", "PDF", "打印、跨设备都好开", "application/pdf", ".pdf", new String[] {});
  static final Format MD =
      new Format(
          "md",
          "Markdown",
          "笔记、Obsidian、Markor",
          "text/markdown",
          ".md",
          new String[] {"text/x-markdown", "text/plain"});
  static final Format HTML =
      new Format("html", "HTML", "浏览器或可离线打开的页", "text/html", ".html", new String[] {});
  static final Format TXT =
      new Format("txt", "纯文本", "任何能看字的 App", "text/plain", ".txt", new String[] {});

  static final Format[] ALL = {EPUB, PDF, MD, HTML, TXT};

  static Format of(String id) {
    for (Format format : ALL) {
      if (format.id.equals(id)) return format;
    }
    return EPUB;
  }
}
