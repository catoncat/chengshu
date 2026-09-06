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
};

export const READERS: Reader[] = [
  {
    id: "share",
    label: "每次选择",
    hint: "弹出系统分享，点你的阅读器",
  },
  {
    id: "koreader",
    label: "KOReader",
    hint: "开源阅读器，格式支持最全",
  },
  {
    id: "librera",
    label: "Librera",
    hint: "F-Droid 上的常用阅读器",
  },
  {
    id: "moonplus",
    label: "Moon+ Reader",
    hint: "排版细、安卓上很常见",
  },
  {
    id: "readera",
    label: "ReadEra",
    hint: "轻量，直接打开本地文件",
  },
  {
    id: "lithium",
    label: "Lithium",
    hint: "专注 EPUB 的开源阅读器",
  },
  {
    id: "playbooks",
    label: "Play 图书",
    hint: "系统自带的 Google 图书",
  },
];

export function readerById(id: ReaderId): Reader {
  return READERS.find((r) => r.id === id) ?? READERS[0]!;
}

export function openButtonLabel(id: ReaderId): string {
  if (id === "share") return "用阅读器打开";
  return `用 ${readerById(id).label} 打开`;
}
