import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { createFileRoute } from "@tanstack/react-router";
import {
  BookOpen,
  Check,
  Download,
  Link2,
  Loader2,
  Settings2,
  Share2,
  Smartphone,
  Trash2,
  X,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  extractSharedUrl,
  formatBytes,
  formatRelative,
  hostOf,
} from "@/lib/utils";
import { READERS, epubViewUrl, isAndroid, openButtonLabel, type ReaderId } from "@/lib/readers";
import { useSettings } from "@/lib/settings";
import { deleteBook, listBooks, saveBook, type StoredBook } from "@/lib/history";
import { downloadBlob, openInReader } from "@/lib/open-epub";

type Search = { url?: string; title?: string; text?: string };

type ConvertOk = {
  title: string;
  byline: string;
  siteName: string;
  excerpt: string;
  sourceUrl: string;
  filename: string;
  imageCount: number;
  charCount: number;
  epubBase64: string;
  size: number;
};

type ResultBook = {
  title: string;
  byline: string;
  siteName: string;
  excerpt: string;
  sourceUrl: string;
  filename: string;
  imageCount: number;
  charCount: number;
  size: number;
  blob: Blob;
};

const STEPS = ["抓取网页", "抽出正文", "收进配图", "装订 EPUB"];
const DEMO_URL = "https://zh.wikipedia.org/wiki/EPUB";

export const Route = createFileRoute("/")({
  validateSearch: (search: Record<string, unknown>): Search => ({
    url: typeof search.url === "string" ? search.url : undefined,
    title: typeof search.title === "string" ? search.title : undefined,
    text: typeof search.text === "string" ? search.text : undefined,
  }),
  component: Home,
});

function Home() {
  const search = Route.useSearch();
  const settings = useSettings();
  const [draft, setDraft] = useState("");
  const [status, setStatus] = useState<"idle" | "working" | "done" | "error">("idle");
  const [step, setStep] = useState(0);
  const [error, setError] = useState("");
  const [result, setResult] = useState<ResultBook | null>(null);
  const [history, setHistory] = useState<StoredBook[]>([]);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [standalone, setStandalone] = useState(false);
  const [installEvent, setInstallEvent] = useState<{ prompt: () => Promise<unknown> } | null>(
    null,
  );
  const startedKey = useRef<string | null>(null);

  useEffect(() => {
    const media = window.matchMedia("(display-mode: standalone)");
    const nav = window.navigator as Navigator & { standalone?: boolean };
    setStandalone(media.matches || nav.standalone === true);
    const onPrompt = (event: Event) => {
      event.preventDefault();
      setInstallEvent(event as unknown as { prompt: () => Promise<unknown> });
    };
    window.addEventListener("beforeinstallprompt", onPrompt);
    return () => window.removeEventListener("beforeinstallprompt", onPrompt);
  }, []);

  useEffect(() => {
    void listBooks().then(setHistory).catch(() => undefined);
  }, []);

  useEffect(() => {
    const url = extractSharedUrl(search);
    const text = !url && search.text ? search.text : "";
    const key = url || text;
    if (!key || startedKey.current === key) return;
    startedKey.current = key;
    if (url) setDraft(url);
    void runConvert({
      url: url ?? undefined,
      text: text || undefined,
      title: search.title,
    });
  }, [search]);

  useEffect(() => {
    if (status !== "working") return;
    setStep(0);
    const timer = window.setInterval(() => {
      setStep((n) => (n < STEPS.length - 1 ? n + 1 : n));
    }, 1400);
    return () => window.clearInterval(timer);
  }, [status]);

  const incoming = useMemo(() => extractSharedUrl(search), [search]);

  async function runConvert(payload: { url?: string; text?: string; title?: string }) {
    setStatus("working");
    setError("");
    setResult(null);
    try {
      const res = await fetch("/api/convert", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(payload),
      });
      const data = (await res.json()) as ConvertOk & { error?: string };
      if (!res.ok) throw new Error(data.error || "转换失败");
      const bytes = Uint8Array.from(atob(data.epubBase64), (c) => c.charCodeAt(0));
      const blob = new Blob([bytes], { type: "application/epub+zip" });
      const book: ResultBook = {
        title: data.title,
        byline: data.byline,
        siteName: data.siteName,
        excerpt: data.excerpt,
        sourceUrl: data.sourceUrl,
        filename: data.filename,
        imageCount: data.imageCount,
        charCount: data.charCount,
        size: data.size,
        blob,
      };
      setResult(book);
      setStatus("done");
      const stored: StoredBook = {
        id: crypto.randomUUID(),
        title: book.title,
        sourceUrl: book.sourceUrl,
        filename: book.filename,
        createdAt: Date.now(),
        size: book.size,
        byline: book.byline,
        siteName: book.siteName,
        excerpt: book.excerpt,
        blob,
      };
      await saveBook(stored);
      setHistory(await listBooks());
      if (settings.autoOpen && isAndroid() && book.sourceUrl.startsWith("http")) {
        void openInReader({
          blob,
          filename: book.filename,
          title: book.title,
          viewUrl: epubViewUrl(book.sourceUrl),
          readerId: settings.readerId,
        });
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : "转换失败";
      setError(message);
      setStatus("error");
      toast.error(message);
    }
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const url = extractSharedUrl({ url: draft, text: draft });
    if (!url) {
      toast.error("请粘贴一个网页链接");
      return;
    }
    void runConvert({ url });
  }

  async function openResult(book: ResultBook | StoredBook) {
    toast.message("正在保存 EPUB…");
    try {
      const viewUrl = book.sourceUrl.startsWith("http")
        ? epubViewUrl(book.sourceUrl)
        : undefined;
      const outcome = await openInReader({
        blob: book.blob,
        filename: book.filename,
        title: book.title,
        viewUrl,
        readerId: settings.readerId,
      });
      if (outcome === "downloaded") {
        toast.message("已保存 EPUB。点屏幕底部下载栏的「打开」，选 KOReader / Librera", {
          duration: 6000,
        });
      }
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      downloadBlob(book.blob, book.filename);
      toast.message("已下载，在文件里用阅读器打开");
    }
  }

  async function removeHistory(id: string) {
    await deleteBook(id);
    setHistory(await listBooks());
  }

  return (
    <main className="paper-grain min-h-dvh">
      <div className="mx-auto flex w-full max-w-lg flex-col px-5 pb-16 pt-[max(1.25rem,env(safe-area-inset-top))]">
        <header className="flex items-center justify-between gap-3">
          <div>
            <p className="font-display text-3xl font-medium tracking-[-0.03em] text-fg">
              成书
            </p>
            <p className="mt-1 text-sm text-fg-muted">网页进来，电子书出去</p>
          </div>
          <Button
            variant="ghost"
            size="icon"
            aria-label="设置"
            onClick={() => setSettingsOpen(true)}
          >
            <Settings2 />
          </Button>
        </header>

        {!standalone ? (
          <div className="mt-6 rounded-xl border border-border bg-surface p-4">
            <div className="flex items-start gap-3">
              <Smartphone className="mt-0.5 size-5 shrink-0 text-primary" />
              <div className="min-w-0">
                <p className="text-sm font-medium">加到主屏幕后，才会出现在 Chrome 分享列表</p>
                <p className="mt-1 text-sm text-fg-muted">
                  用 Chrome 打开本页 → 菜单 → 添加到主屏幕。以后任意网页点分享，选「成书」即可。
                </p>
                {installEvent ? (
                  <Button
                    className="mt-3"
                    size="sm"
                    onClick={() => void installEvent.prompt()}
                  >
                    安装成书
                  </Button>
                ) : null}
              </div>
            </div>
          </div>
        ) : incoming ? (
          <p className="mt-6 text-sm text-fg-muted">已从分享接收链接，正在成书。</p>
        ) : null}

        {status === "working" ? (
          <section className="mt-8 rounded-xl border border-border bg-surface p-6">
            <p className="font-display text-2xl font-medium tracking-[-0.03em]">正在成书</p>
            <p className="mt-2 truncate text-sm text-fg-muted">{draft || incoming || "提取正文"}</p>
            <ol className="mt-6 space-y-3">
              {STEPS.map((label, i) => {
                const active = i === step;
                const done = i < step;
                return (
                  <li key={label} className="flex items-center gap-3 text-sm">
                    <span className="grid size-6 place-items-center rounded-full bg-surface-2 text-fg">
                      {done ? (
                        <Check className="size-3.5" />
                      ) : active ? (
                        <Loader2 className="size-3.5 animate-spin" />
                      ) : (
                        <span className="size-1.5 rounded-full bg-fg-subtle" />
                      )}
                    </span>
                    <span className={active ? "text-fg" : "text-fg-muted"}>{label}</span>
                  </li>
                );
              })}
            </ol>
          </section>
        ) : null}

        {status === "done" && result ? (
          <section className="mt-8 rounded-xl border border-border bg-surface p-5">
            <p className="text-xs font-medium tracking-wide text-fg-subtle">已装订</p>
            <h2 className="font-display mt-2 text-2xl font-medium leading-snug tracking-[-0.03em]">
              {result.title}
            </h2>
            <p className="mt-2 text-sm text-fg-muted">
              {[result.byline, result.siteName || hostOf(result.sourceUrl), formatBytes(result.size)]
                .filter(Boolean)
                .join(" · ")}
            </p>
            {result.excerpt ? (
              <p className="mt-3 line-clamp-3 text-sm text-fg-muted">{result.excerpt}</p>
            ) : null}
            <div className="mt-5 flex flex-col gap-2">
              <Button className="w-full" size="lg" onClick={() => void openResult(result)}>
                <Share2 />
                {openButtonLabel(settings.readerId)}
              </Button>
              {!isAndroid() ? (
                <p className="text-center text-xs text-fg-muted">
                  电脑上会下载文件。请用安卓 Chrome 打开本站，点下载栏的「打开」。
                </p>
              ) : (
                <p className="text-center text-xs text-fg-muted">
                  会先保存到下载。点底部「打开」，再选你的阅读器。
                </p>
              )}
              <Button
                className="w-full"
                variant="secondary"
                onClick={() => downloadBlob(result.blob, result.filename)}
              >
                <Download />
                下载 EPUB
              </Button>
              <Button
                className="w-full"
                variant="ghost"
                onClick={() => {
                  setStatus("idle");
                  setResult(null);
                  setDraft("");
                }}
              >
                再转一篇
              </Button>
            </div>
          </section>
        ) : null}

        {status === "error" ? (
          <section className="mt-8 rounded-xl border border-border bg-surface p-5">
            <p className="text-sm font-medium text-danger">{error}</p>
            <p className="mt-2 text-sm text-fg-muted">
              有的站点会拦服务器抓取。可以换一篇，或把正文复制后粘贴到输入框。
            </p>
            <Button className="mt-4" variant="secondary" onClick={() => setStatus("idle")}>
              返回
            </Button>
          </section>
        ) : null}

        {status === "idle" ? (
          <>
            <form className="mt-8" onSubmit={onSubmit}>
              <label htmlFor="url" className="text-sm font-medium text-fg">
                网页链接
              </label>
              <div className="mt-2 flex flex-col gap-2">
                <Input
                  id="url"
                  inputMode="url"
                  autoCapitalize="off"
                  autoCorrect="off"
                  placeholder="https://"
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                />
                <Button type="submit" className="w-full" size="lg">
                  <BookOpen />
                  做成 EPUB
                </Button>
              </div>
              <button
                type="button"
                className="mt-3 text-sm text-fg-muted underline-offset-4 hover:text-fg hover:underline"
                onClick={() => {
                  setDraft(DEMO_URL);
                  void runConvert({ url: DEMO_URL });
                }}
              >
                先用维基百科的 EPUB 条目试一次
              </button>
            </form>

            <ol className="mt-10 space-y-4">
              {[
                { icon: Smartphone, title: "装到主屏幕", body: "让成书出现在 Chrome 分享菜单。" },
                { icon: Share2, title: "在 Chrome 里分享", body: "打开网页 → 分享 → 成书。不用再跳去别的浏览器。" },
                { icon: BookOpen, title: "交给阅读器", body: `转完点「${openButtonLabel(settings.readerId)}」。` },
              ].map((item) => (
                <li key={item.title} className="flex gap-3">
                  <span className="grid size-10 shrink-0 place-items-center rounded-md bg-surface text-primary">
                    <item.icon className="size-4" />
                  </span>
                  <div>
                    <p className="text-sm font-medium">{item.title}</p>
                    <p className="mt-0.5 text-sm text-fg-muted">{item.body}</p>
                  </div>
                </li>
              ))}
            </ol>
          </>
        ) : null}

        {history.length > 0 && status !== "working" ? (
          <section className="mt-12">
            <h2 className="text-sm font-medium text-fg-muted">最近成书</h2>
            <ul className="mt-3 space-y-2">
              {history.map((book) => (
                <li
                  key={book.id}
                  className="flex items-center gap-3 rounded-lg border border-border bg-surface px-3 py-3"
                >
                  <button
                    type="button"
                    className="min-w-0 flex-1 text-left"
                    onClick={() => void openResult(book)}
                  >
                    <p className="truncate text-sm font-medium">{book.title}</p>
                    <p className="mt-0.5 truncate text-xs text-fg-subtle">
                      {hostOf(book.sourceUrl) || "摘录"} · {formatBytes(book.size)} ·{" "}
                      {formatRelative(book.createdAt)}
                    </p>
                  </button>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label="删除"
                    onClick={() => void removeHistory(book.id)}
                  >
                    <Trash2 className="size-4 text-fg-muted" />
                  </Button>
                </li>
              ))}
            </ul>
          </section>
        ) : null}
      </div>

      {settingsOpen ? (
        <div className="fixed inset-0 z-50 bg-scrim">
          <div className="absolute inset-x-0 bottom-0 max-h-[88dvh] overflow-y-auto rounded-t-xl bg-bg px-5 pb-[max(1.5rem,env(safe-area-inset-bottom))] pt-4">
            <div className="mx-auto w-full max-w-lg">
              <div className="flex items-center justify-between">
                <h2 className="font-display text-xl font-medium">设置</h2>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label="关闭"
                  onClick={() => setSettingsOpen(false)}
                >
                  <X />
                </Button>
              </div>
              <p className="mt-4 text-sm font-medium">转完交给谁</p>
              <p className="mt-1 text-sm text-fg-muted">
                网页不能直接把文件塞进别的 App。点按钮会保存 EPUB，再在下载栏点「打开」选阅读器。
              </p>
              <div className="mt-3 grid gap-2">
                {READERS.map((reader) => {
                  const selected = settings.readerId === reader.id;
                  return (
                    <button
                      key={reader.id}
                      type="button"
                      onClick={() => settings.setReaderId(reader.id as ReaderId)}
                      className={`flex items-start justify-between rounded-lg border px-4 py-3 text-left ${
                        selected
                          ? "border-primary bg-surface"
                          : "border-border bg-surface"
                      }`}
                    >
                      <span>
                        <span className="block text-sm font-medium">{reader.label}</span>
                        <span className="mt-0.5 block text-xs text-fg-muted">{reader.hint}</span>
                      </span>
                      {selected ? <Check className="size-4 text-primary" /> : null}
                    </button>
                  );
                })}
              </div>
              <label className="mt-6 flex items-center justify-between gap-4 rounded-lg border border-border bg-surface px-4 py-3">
                <span>
                  <span className="block text-sm font-medium">转完自动保存并提示打开</span>
                  <span className="block text-xs text-fg-muted">关掉就停在结果页，自己点按钮</span>
                </span>
                <input
                  type="checkbox"
                  checked={settings.autoOpen}
                  onChange={(e) => settings.setAutoOpen(e.target.checked)}
                  className="size-5 accent-primary"
                />
              </label>
              <p className="mt-6 flex items-start gap-2 text-xs text-fg-subtle">
                <Link2 className="mt-0.5 size-3.5 shrink-0" />
                抓取在服务器完成。只为生成 EPUB，不建账号，记录只存在这台手机。
              </p>
            </div>
          </div>
        </div>
      ) : null}
    </main>
  );
}
