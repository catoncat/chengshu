import { useEffect, useRef, useState } from "react";
import { SAMPLE_HTML, SAMPLE_TITLE } from "@/lib/convert/sample-article";
import { deleteBook, listBooks, saveBook, type StoredBook } from "@/lib/history";

const FORMATS = [
  { id: "epub", zh: "EPUB", en: "EPUB" },
  { id: "pdf", zh: "PDF", en: "PDF" },
  { id: "md", zh: "Markdown", en: "Markdown" },
  { id: "html", zh: "HTML", en: "HTML" },
  { id: "txt", zh: "纯文本", en: "Plain text" },
] as const;

type FormatId = (typeof FORMATS)[number]["id"];
const FORMAT_KEY = "chengshu-format";

export function Converter({ lang }: { lang: "zh" | "en" }) {
  const zh = lang === "zh";
  const [url, setUrl] = useState("");
  const [format, setFormat] = useState<FormatId>("epub");
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [error, setError] = useState("");
  const [books, setBooks] = useState<StoredBook[]>([]);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const saved = localStorage.getItem(FORMAT_KEY);
    if (FORMATS.some((item) => item.id === saved)) setFormat(saved as FormatId);
    void listBooks()
      .then(setBooks)
      .catch(() => setBooks([]));
  }, []);

  function chooseFormat(id: FormatId) {
    setFormat(id);
    localStorage.setItem(FORMAT_KEY, id);
  }

  async function run(payload: { url?: string; html?: string; text?: string; title?: string }) {
    abortRef.current?.abort();
    const ac = new AbortController();
    abortRef.current = ac;
    setBusy(true);
    setError("");
    setStatus(zh ? "正在整理成书…" : "Making the book…");
    try {
      const res = await fetch(`/export?format=${format}`, {
        method: "POST",
        headers: { "content-type": "application/json", accept: "*/*" },
        body: JSON.stringify(payload),
        signal: ac.signal,
      });
      const type = res.headers.get("content-type") || "";
      if (!res.ok || type.includes("application/json")) {
        const body = (await res.json().catch(() => ({}))) as { error?: string };
        throw new Error(body.error || (zh ? "转换失败" : "Conversion failed"));
      }
      const blob = await res.blob();
      if (blob.size < 80) throw new Error(zh ? "生成的文件是空的" : "The file came back empty");
      const name = filenameFrom(res.headers.get("content-disposition"), format);
      const title = headerTitle(res.headers.get("X-Title")) || name.replace(/\.[^.]+$/, "");
      const chars = Number(res.headers.get("X-Chars") || "0");
      const paragraphs = Number(res.headers.get("X-Paragraphs") || "0");
      downloadBlob(blob, name);
      const book: StoredBook = {
        id: `${Date.now()}-${name}`,
        title,
        sourceUrl: payload.url || "",
        filename: name,
        format,
        mime: blob.type || "application/octet-stream",
        createdAt: Date.now(),
        size: blob.size,
        charCount: chars,
        paragraphCount: paragraphs,
        blob,
      };
      await saveBook(book).catch(() => undefined);
      setBooks(await listBooks().catch(() => [book]));
      const bits = [
        zh ? `已下载 ${name}` : `Downloaded ${name}`,
        paragraphs > 1 ? (zh ? `${paragraphs} 段` : `${paragraphs} paragraphs`) : "",
        chars > 0 ? (zh ? `${chars} 字` : `${chars} chars`) : "",
      ].filter(Boolean);
      setStatus(bits.join(" · "));
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        setStatus("");
        setError("");
        return;
      }
      setStatus("");
      setError(err instanceof Error ? err.message : zh ? "转换失败" : "Conversion failed");
    } finally {
      setBusy(false);
    }
  }

  function submit() {
    const trimmed = url.trim();
    if (!trimmed) {
      setError(zh ? "先贴链接或正文，或点「试一篇示例」。" : "Paste a link or the article, or try the sample.");
      return;
    }
    if (/^https?:\/\//i.test(trimmed)) {
      void run({ url: trimmed });
      return;
    }
    const first = trimmed.split("\n").find((line) => line.trim())?.trim() ?? "";
    void run({ text: trimmed, title: first.slice(0, 72) });
  }

  return (
    <div className="my-10 border-y border-border py-8">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <p className="mb-4 text-foreground">
          {zh
            ? "贴一个 https 链接，或直接把正文粘进来。手机上装好应用后，从浏览器分享过来即可。"
            : "Paste an https link, or paste the article itself. On the phone, share from the browser."}
        </p>
        <label className="sr-only" htmlFor="chengshu-url">
          {zh ? "文章链接或正文" : "Article URL or text"}
        </label>
        <textarea
          id="chengshu-url"
          rows={3}
          placeholder={zh ? "https://  或直接粘贴正文" : "https://  or paste the article"}
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          className="min-h-20 w-full resize-y border-0 border-b border-border bg-transparent px-0 py-3 text-base text-foreground placeholder:text-muted-foreground focus-visible:border-foreground focus-visible:outline-none"
        />
        <div className="mt-5 flex flex-wrap gap-x-4 gap-y-2" role="radiogroup" aria-label={zh ? "格式" : "Format"}>
          {FORMATS.map((item) => {
            const selected = format === item.id;
            return (
              <button
                key={item.id}
                type="button"
                role="radio"
                aria-checked={selected}
                onClick={() => chooseFormat(item.id)}
                className={`min-h-11 border-0 bg-transparent p-0 text-sm ${selected ? "text-foreground underline" : "text-muted-foreground hover:text-foreground"}`}
              >
                {zh ? item.zh : item.en}
              </button>
            );
          })}
        </div>
        <div className="mt-6 flex flex-wrap items-center gap-x-5 gap-y-3">
          <button
            type="submit"
            disabled={busy}
            className="min-h-11 border-0 bg-foreground px-5 text-sm text-background disabled:opacity-40"
          >
            {busy ? (zh ? "正在成书" : "Working") : zh ? "成书" : "Make book"}
          </button>
          {busy ? (
            <button
              type="button"
              onClick={() => {
                abortRef.current?.abort();
                setBusy(false);
                setStatus("");
              }}
              className="min-h-11 border-0 bg-transparent p-0 text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
            >
              {zh ? "取消" : "Cancel"}
            </button>
          ) : null}
          <button
            type="button"
            disabled={busy}
            onClick={async () => {
              try {
                const text = await navigator.clipboard.readText();
                if (!text.trim()) {
                  setError(zh ? "剪贴板是空的" : "Clipboard is empty");
                  return;
                }
                setUrl(text.trim());
              } catch {
                setError(zh ? "读不了剪贴板，请直接粘贴。" : "Clipboard blocked — paste instead.");
              }
            }}
            className="min-h-11 border-0 bg-transparent p-0 text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline disabled:opacity-40"
          >
            {zh ? "粘贴剪贴板" : "Paste clipboard"}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => void run({ html: SAMPLE_HTML, title: SAMPLE_TITLE })}
            className="min-h-11 border-0 bg-transparent p-0 text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline disabled:opacity-40"
          >
            {zh ? "试一篇示例（不联网）" : "Try a sample (offline)"}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              const article = document.querySelector("main article");
              const html = article instanceof HTMLElement ? article.innerHTML : "";
              if (!html.trim()) {
                setError(zh ? "这一页没有可抓的正文" : "No article on this page");
                return;
              }
              void run({
                html,
                title: zh ? "成书" : "成书",
                url: window.location.origin,
              });
            }}
            className="min-h-11 border-0 bg-transparent p-0 text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline disabled:opacity-40"
          >
            {zh ? "转本页" : "This page"}
          </button>
        </div>
      </form>
      {status ? <p className="mt-4 text-sm text-foreground">{status}</p> : null}
      {error ? <p className="mt-4 text-sm text-foreground">{error}</p> : null}
      {books.length > 0 ? (
        <section className="mt-10" aria-label={zh ? "最近成书" : "Recent books"}>
          <p className="mb-3 text-foreground">{zh ? "最近成书" : "Recent"}</p>
          <ul className="m-0 list-none p-0">
            {books.map((book) => (
              <li key={book.id} className="border-t border-border py-3">
                <div className="flex flex-wrap items-baseline justify-between gap-3">
                  <button
                    type="button"
                    className="border-0 bg-transparent p-0 text-left text-foreground"
                    onClick={() => downloadBlob(book.blob, book.filename)}
                  >
                    {book.title}
                  </button>
                  <span className="text-sm text-muted-foreground">
                    {book.format.toUpperCase()}
                    {book.paragraphCount > 1 ? ` · ${book.paragraphCount}${zh ? " 段" : "p"}` : ""}
                  </span>
                </div>
                <div className="mt-1 flex flex-wrap gap-4 text-sm">
                  <button
                    type="button"
                    className="border-0 bg-transparent p-0 text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
                    onClick={() => downloadBlob(book.blob, book.filename)}
                  >
                    {zh ? "再下载" : "Download"}
                  </button>
                  <button
                    type="button"
                    className="border-0 bg-transparent p-0 text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
                    onClick={async () => {
                      await deleteBook(book.id);
                      setBooks(await listBooks());
                    }}
                  >
                    {zh ? "删除" : "Delete"}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

function downloadBlob(blob: Blob, name: string) {
  const href = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = href;
  a.download = name;
  a.click();
  URL.revokeObjectURL(href);
}

function headerTitle(raw: string | null) {
  if (!raw) return "";
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

function filenameFrom(header: string | null, format: FormatId) {
  const star = header?.match(/filename\*=UTF-8''([^;]+)/i);
  if (star?.[1]) {
    try {
      return decodeURIComponent(star[1]);
    } catch {
      /* fall through */
    }
  }
  const plain = header?.match(/filename="([^"]+)"/i);
  if (plain?.[1]) return plain[1];
  return `book.${format}`;
}
