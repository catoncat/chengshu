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
          <p className="text-foreground">Chrome 读到一半，想换到 KOReader 里接着翻。</p>
          <p>
            按理说分享菜单就该有这一项。实际上没有。阅读器只接受文件，浏览器只交出链接，中间靠人：复制、找个转换页、等下载、再从下载列表里打开。走完一遍，原来要查的那句话已经不在脑子里了。
          </p>
          <p>
            成书出现在分享列表里。它要的是链接。抽正文，打成 EPUB，送到事先指定的阅读器。阅读器不必是
            KOReader，格式也不必是 EPUB，Markdown、HTML、纯文本都可以。指定能改。同一条地址再进来，它认得，不再抓一遍。
          </p>
          <p>
            它不是阅读器，也不是浏览器。第一次打开，把格式和打开方式选好。以后只在 Chrome 里点分享。
          </p>
          <p>
            <a href="https://0nl.onl/chengshu.apk">安装</a>
            ，Android 8 以上，不经过应用商店。
          </p>
        </article>
      </main>
    </>
  );
}
