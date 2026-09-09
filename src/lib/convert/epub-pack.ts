import JSZip from "jszip";

export type EpubImage = {
  id: string;
  href: string;
  mediaType: string;
  data: Uint8Array;
};

export type EpubChapter = {
  id: string;
  href: string;
  title: string;
  xhtml: string;
};

function escapeXml(value: string) {
  return value
    .replaceAll("\u0026", "\u0026amp;")
    .replaceAll("\u003c", "\u0026lt;")
    .replaceAll("\u003e", "\u0026gt;")
    .replaceAll("\u0022", "\u0026quot;");
}

function hostName(url: string) {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}

function usableTitle(value: string, fallback: string) {
  const t = (value || "").replace(/\s+/g, " ").trim();
  if (!t || /^body$/i.test(t)) return fallback;
  return t;
}

function stripTags(html: string) {
  return html
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/&/gi, "&")
    .replace(/</gi, "<")
    .replace(/>/gi, ">")
    .replace(/"/gi, '"')
    .replace(/\s+/g, " ")
    .trim();
}

/** Split on h2 so WeChat Reading / KOReader get a real TOC, not one “body” chapter. */
export function splitChapters(xhtml: string, bookTitle: string): EpubChapter[] {
  const source = (xhtml || "").trim() || "<p></p>";
  const pieces = source.split(/(<h2\b[^>]*>[\s\S]*?<\/h2>)/i);
  const headingCount = pieces.filter((_, i) => i % 2 === 1).length;
  if (headingCount < 2) {
    return [{ id: "chapter", href: "chapter.xhtml", title: bookTitle, xhtml: source }];
  }
  const chapters: { title: string; xhtml: string }[] = [];
  const intro = (pieces[0] || "").trim();
  if (intro) chapters.push({ title: bookTitle, xhtml: intro });
  for (let i = 1; i < pieces.length; i += 2) {
    const heading = pieces[i] || "";
    const body = pieces[i + 1] || "";
    const title = usableTitle(stripTags(heading), bookTitle);
    chapters.push({ title, xhtml: `${heading}${body}`.trim() });
  }
  return chapters.map((ch, index) => ({
    id: `ch${index + 1}`,
    href: `chapter-${index + 1}.xhtml`,
    title: ch.title,
    xhtml: ch.xhtml,
  }));
}

/** EPUB 2 + NCX. 微信读书 ignores EPUB 3 nav and names the chapter "body". */
export async function buildEpub(input: {
  title: string;
  byline: string;
  siteName: string;
  excerpt: string;
  sourceUrl: string;
  xhtml: string;
  images: EpubImage[];
}): Promise<Uint8Array> {
  const zip = new JSZip();
  const id = crypto.randomUUID();
  const lang = /[\u4e00-\u9fff]/.test(input.title + input.xhtml) ? "zh" : "en";
  const cover = input.images[0];
  const fallback =
    usableTitle(input.siteName, "") || hostName(input.sourceUrl) || "未命名";
  const title = usableTitle(input.title, fallback);
  const titleXml = escapeXml(title);
  const chapters = splitChapters(input.xhtml, title);

  zip.file("mimetype", "application/epub+zip", { compression: "STORE" });
  zip.file(
    "META-INF/container.xml",
    `<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>`,
  );

  const manifestItems = [
    `<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>`,
    `<item id="css" href="style.css" media-type="text/css"/>`,
    ...chapters.map(
      (ch) => `<item id="${ch.id}" href="${ch.href}" media-type="application/xhtml+xml"/>`,
    ),
    ...input.images.map(
      (img) => `<item id="${img.id}" href="${img.href}" media-type="${img.mediaType}"/>`,
    ),
  ];

  zip.file(
    "OEBPS/content.opf",
    `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="2.0">
  <metadata>
    <dc:identifier id="bookid" opf:scheme="UUID">urn:uuid:${id}</dc:identifier>
    <dc:title>${titleXml}</dc:title>
    <dc:language>${lang}</dc:language>
    ${input.byline ? `<dc:creator opf:role="aut">${escapeXml(input.byline)}</dc:creator>` : ""}
    ${input.siteName ? `<dc:publisher>${escapeXml(input.siteName)}</dc:publisher>` : ""}
    ${input.sourceUrl ? `<dc:source>${escapeXml(input.sourceUrl)}</dc:source>` : ""}
    ${input.excerpt ? `<dc:description>${escapeXml(input.excerpt)}</dc:description>` : ""}
    ${cover ? `<meta name="cover" content="${cover.id}"/>` : ""}
  </metadata>
  <manifest>
    ${manifestItems.join("\n    ")}
  </manifest>
  <spine toc="ncx">
    ${chapters.map((ch) => `<itemref idref="${ch.id}"/>`).join("\n    ")}
  </spine>
  <guide>
    <reference type="text" title="${titleXml}" href="${chapters[0]!.href}"/>
  </guide>
</package>`,
  );

  zip.file(
    "OEBPS/toc.ncx",
    `<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:${id}"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${titleXml}</text></docTitle>
  <navMap>
    ${chapters
      .map(
        (ch, i) => `<navPoint id="${ch.id}" playOrder="${i + 1}">
      <navLabel><text>${escapeXml(ch.title)}</text></navLabel>
      <content src="${ch.href}"/>
    </navPoint>`,
      )
      .join("\n    ")}
  </navMap>
</ncx>`,
  );

  const metaBits = [
    input.byline ? escapeXml(input.byline) : "",
    input.siteName ? escapeXml(input.siteName) : "",
    input.sourceUrl
      ? `<a href="${escapeXml(input.sourceUrl)}">${escapeXml(hostName(input.sourceUrl) || input.sourceUrl)}</a>`
      : "",
  ].filter(Boolean);

  for (const [index, ch] of chapters.entries()) {
    const showMeta = index === 0 && metaBits.length > 0;
    const heading = escapeXml(ch.title);
    zip.file(
      `OEBPS/${ch.href}`,
      `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="${lang}">
  <head>
    <title>${heading}</title>
    <link rel="stylesheet" type="text/css" href="style.css"/>
  </head>
  <body title="${heading}">
    <h1>${heading}</h1>
    ${showMeta ? `<p class="meta">${metaBits.join(" · ")}</p>` : ""}
    ${ch.xhtml}
  </body>
</html>`,
    );
  }

  zip.file(
    "OEBPS/style.css",
    `body{font-family:"Songti SC","Noto Serif CJK SC","Source Han Serif SC",Georgia,serif;line-height:1.8;font-size:1em;margin:0;padding:0;line-break:strict;word-break:normal;hyphens:none}
h1{font-size:1.55em;line-height:1.3;margin:0 0 .8em;text-indent:0}
h2,h3{line-height:1.35;margin:1.2em 0 .5em;text-indent:0}
p{margin:.65em 0;text-indent:2em;text-align:justify}
p.meta{text-indent:0;text-align:left;font-size:.9em;opacity:.72}
img{max-width:100%;height:auto;display:block;margin:1em auto}
figcaption{text-indent:0;text-align:center;font-size:.9em;opacity:.8;margin:.2em 0 1em}
blockquote{margin:1em 0;padding-left:1em;border-left:3px solid #c8c1b4;opacity:.92}
blockquote p{text-indent:0;text-align:left}
a{color:inherit}
pre,code{font-family:ui-monospace,monospace;font-size:.92em}
pre{text-indent:0;white-space:pre-wrap;line-break:anywhere}
ul,ol{padding-left:1.4em}
li{margin:.25em 0;text-indent:0}`,
  );

  for (const img of input.images) {
    zip.file(`OEBPS/${img.href}`, img.data);
  }

  return zip.generateAsync({
    type: "uint8array",
    compression: "DEFLATE",
    compressionOptions: { level: 6 },
  });
}
