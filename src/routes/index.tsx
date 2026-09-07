import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/")({
  component: Home,
});

function Home() {
  return (
    <>
      <header className="mx-auto flex h-16 max-w-[744px] items-center justify-between gap-5 px-5 min-[641px]:justify-start min-[641px]:gap-10 min-[641px]:px-8">
        <a href="/" className="text-foreground no-underline hover:text-foreground">
          成书
        </a>
        <nav className="flex items-center gap-7 text-[13px] text-muted-foreground min-[641px]:text-sm">
          <a
            href="https://0nl.onl/chengshu.apk"
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
        </nav>
      </header>

      <main className="mx-auto max-w-[744px] px-5 pt-10 pb-24 text-muted-foreground sm:px-8 sm:pt-14">
        <article className="max-w-[600px] leading-[1.75] [&_p+p]:mt-[1.5em]">
          <p className="text-foreground">浏览器能看网页，阅读器能看书。中间缺一截。</p>
          <p>
            Chrome 里看到想慢慢读的东西，没法丢进 KOReader。成书接住分享，抽成正文，做成
            EPUB，打开你的阅读器。
          </p>
          <p>也可以是 Markdown、HTML、纯文本。格式和打开方式选一次就记住，同一篇不会再转。</p>
          <p>
            <a href="https://0nl.onl/chengshu.apk">安装</a>
            ，然后在 Chrome 里分享网页到成书。
          </p>
        </article>
      </main>
    </>
  );
}
