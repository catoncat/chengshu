export type ReaderId =
  | "share"
  | "koreader"
  | "librera"
  | "moonplus"
  | "readera"
  | "lithium"
  | "playbooks";

export type Reader = {
  id: ReaderId;
  label: string;
  hint: string;
  packages: string[];
};

export const READERS: Reader[] = [
  {
    id: "share",
    label: "每次选择",
    hint: "弹出能打开 EPUB 的应用列表",
    packages: [],
  },
  {
    id: "koreader",
    label: "KOReader",
    hint: "开源阅读器，格式支持最全",
    packages: ["org.koreader.launcher", "org.koreader.launcher.fdroid"],
  },
  {
    id: "librera",
    label: "Librera",
    hint: "F-Droid 上的常用阅读器",
    packages: ["com.foobnix.pdf.reader", "com.foobnix.pro.pdf.reader"],
  },
  {
    id: "moonplus",
    label: "Moon+ Reader",
    hint: "排版细、安卓上很常见",
    packages: ["com.flyersoft.moonreader", "com.flyersoft.moonreaderp"],
  },
  {
    id: "readera",
    label: "ReadEra",
    hint: "轻量，直接打开本地文件",
    packages: ["org.readera", "org.readera.premium"],
  },
  {
    id: "lithium",
    label: "Lithium",
    hint: "专注 EPUB 的开源阅读器",
    packages: ["com.faultexception.reader"],
  },
  {
    id: "playbooks",
    label: "Play 图书",
    hint: "系统自带的 Google 图书",
    packages: ["com.google.android.apps.books"],
  },
];

export function readerById(id: ReaderId): Reader {
  return READERS.find((r) => r.id === id) ?? READERS[0]!;
}

export function openButtonLabel(id: ReaderId): string {
  if (id === "share") return "用阅读器打开";
  return `用 ${readerById(id).label} 打开`;
}

export function isAndroid(): boolean {
  if (typeof navigator === "undefined") return false;
  return /Android/i.test(navigator.userAgent);
}

export function epubViewUrl(sourceUrl: string): string {
  const url = new URL("/api/epub", window.location.origin);
  url.searchParams.set("url", sourceUrl);
  return url.href;
}

export function androidViewIntent(viewUrl: string, readerId: ReaderId): string {
  const parsed = new URL(viewUrl);
  const path = `${parsed.host}${parsed.pathname}${parsed.search}`;
  const pkg = readerById(readerId).packages[0];
  const parts = [
    `intent://${path}#Intent`,
    "scheme=https",
    "action=android.intent.action.VIEW",
    "type=application/epub+zip",
  ];
  if (pkg) parts.push(`package=${pkg}`);
  parts.push("end");
  return parts.join(";");
}
