import JSZip from "jszip";

export type EpubImage = {
  id: string;
  href: string;
  mediaType: string;
  data: Uint8Array;
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
  const title = escapeXml(usableTitle(input.title, fallback));

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
    `<item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>`,
    `<item id="css" href="style.css" media-type="text/css"/>`,
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
    <dc:title>${title}</dc:title>
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
    <itemref idref="chapter"/>
  </spine>
  <guide>
    <reference type="text" title="${title}" href="chapter.xhtml"/>
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
  <docTitle><text>${title}</text></docTitle>
  <navMap>
    <navPoint id="ch1" playOrder="1">
      <navLabel><text>${title}</text></navLabel>
      <content src="chapter.xhtml"/>
    </navPoint>
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

  zip.file(
    "OEBPS/chapter.xhtml",
    `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="${lang}">
  <head>
    <title>${title}</title>
    <link rel="stylesheet" type="text/css" href="style.css"/>
  </head>
  <body title="${title}">
    <h1>${title}</h1>
    ${metaBits.length ? `<p class="meta">${metaBits.join(" · ")}</p>` : ""}
    ${input.xhtml}
  </body>
</html>`,
  );

  zip.file(
    "OEBPS/style.css",
    `body{font-family:"Songti SC","Noto Serif CJK SC","Source Han Serif SC",Georgia,serif;line-height:1.75;font-size:1em;margin:0;padding:0}
h1{font-size:1.55em;line-height:1.3;margin:0 0 .8em;text-indent:0}
h2,h3{line-height:1.35;margin:1.2em 0 .5em;text-indent:0}
p{margin:.75em 0;text-indent:2em}
p.meta{text-indent:0;font-size:.9em;opacity:.72}
img{max-width:100%;height:auto;display:block;margin:1em auto}
blockquote{margin:1em 0;padding-left:1em;border-left:3px solid #c8c1b4;opacity:.92}
a{color:inherit}
pre,code{font-family:ui-monospace,monospace;font-size:.92em}
ul,ol{padding-left:1.4em}
li{margin:.25em 0}`,
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
