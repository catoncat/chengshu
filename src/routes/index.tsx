import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/")({
  component: Home,
});

function Home() {
  return (
    <>
      <header className="mx-auto flex h-16 max-w-[744px] items-center justify-between gap-5 px-5 min-[641px]:justify-start min-[641px]:gap-10 min-[641px]:px-8">
        <a
          href="/"
          className="text-foreground no-underline hover:text-foreground"
          aria-label="成书"
        >
          成书
        </a>
        <nav className="flex items-center gap-[clamp(10px,3vw,24px)] text-[13px] text-muted-foreground min-[641px]:gap-7 min-[641px]:text-sm">
          <a
            href="#install"
            className="text-inherit no-underline hover:text-foreground"
          >
            安装
          </a>
          <a
            href="https://github.com/catoncat/chengshu"
            className="text-inherit no-underline hover:text-foreground"
          >
            源码
          </a>
          <a
            href="https://0nl.onl/chengshu.apk"
            className="text-inherit no-underline hover:text-foreground"
          >
            APK
          </a>
        </nav>
      </header>

      <main className="mx-auto max-w-[744px] px-5 pt-10 pb-16 text-muted-foreground sm:px-8 sm:pt-14 sm:pb-24">
        <article>
          <p className="text-foreground">网页变成下一个 App 能打开的格式。</p>

          <p id="install" className="mt-[1em] text-[clamp(10px,3.2vw,14px)] text-foreground sm:text-sm">
            <a
              href="https://0nl.onl/chengshu.apk"
              className="font-semibold text-foreground no-underline hover:text-foreground"
            >
              $ curl -fsSL https://0nl.onl/chengshu.apk -o chengshu.apk
            </a>
          </p>
          <p className="mt-3.5 text-[13px]">
            v1.3 · Android 8+ · 约 1.9mb ·{" "}
            <a href="https://github.com/catoncat/chengshu">源码</a>
          </p>

          <div className="mt-12 max-w-[600px] leading-[1.75] [&_p+p]:mt-[1.5em]">
            <p>
              成书是一个分享目标。在 Chrome 打开任意网页，点分享，选成书。它抽出正文，做成
              EPUB、Markdown、HTML 或纯文本，再交给你指定的阅读器。
            </p>
            <p>
              这一页本身就是一篇完整的文章。装好成书之后，把{" "}
              <a href="https://0nl.onl/">https://0nl.onl/</a>{" "}
              分享进去，就能验证整条链路：抓取、抽取、装订、打开。
            </p>
          </div>

          <section className="mt-10 max-w-[560px] leading-[1.75]">
            <h2 className="mb-1 text-sm font-bold text-foreground">怎么用</h2>
            <p>
              先安装 APK。打开成书，选格式和打开方式，例如 EPUB 交给 KOReader。之后在
              Chrome 里分享网页到成书即可。格式和去向会记住，随时能改。
            </p>
          </section>

          <section className="mt-10 max-w-[560px] leading-[1.75]">
            <h2 className="mb-1 text-sm font-bold text-foreground">同一篇不会再抓</h2>
            <p>
              转过的网页记在本地。链接会去掉跟踪参数再判断是否同一篇。已经有的格式直接打开；没有的格式再转一次。长按最近一项可以改成别的格式，或删除。
            </p>
          </section>

          <section className="mt-10 max-w-[560px] leading-[1.75]">
            <h2 className="mb-1 text-sm font-bold text-foreground">打开方式</h2>
            <p>
              KOReader、Librera、开源阅读、EinkBro、Obsidian、Markor
              会排在前面。蓝牙、NFC、文件管理器默认藏掉。列表里不对的，打开方式里点排除；排除的应用可以再恢复。
            </p>
          </section>

          <section className="mt-10 max-w-[560px] leading-[1.75]">
            <h2 className="mb-1 text-sm font-bold text-foreground">为什么不是网页应用</h2>
            <p>
              浏览器不能把 EPUB
              文件直接交给阅读器。Android 要的是文件的内容地址，不是一个下载链接。成书是原生分享接收器，转完用
              FileProvider 打开。这一步在网页里做不到。
            </p>
          </section>

          <section className="mt-10 max-w-[560px] leading-[1.75]">
            <h2 className="mb-1 text-sm font-bold text-foreground">抽取</h2>
            <p>
              正文用 Mozilla Readability，也就是 Firefox
              阅读模式那一套。配图会收进 EPUB。页面太薄或几乎没有文章结构时，再走一次兜底。装订是
              EPUB 3，够阅读器翻页，不是出版工具。
            </p>
          </section>

          <section className="mt-10 max-w-[560px] leading-[1.75]">
            <h2 className="mb-1 text-sm font-bold text-foreground">更新</h2>
            <p>
              打开成书，点检查更新。有新版本会显示版本号，再点一次下载安装。第一次需要允许安装未知应用。
            </p>
          </section>

          <p className="mt-16 text-[13px]">
            <a href="https://github.com/catoncat/chengshu" className="text-inherit no-underline hover:text-foreground">
              源码
            </a>
            {" · "}
            <a href="https://0nl.onl/chengshu.apk" className="text-inherit no-underline hover:text-foreground">
              安装
            </a>
            {" · "}
            0nl.onl
          </p>
        </article>
      </main>
    </>
  );
}
