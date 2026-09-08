import { createFileRoute } from "@tanstack/react-router";
import { SiteHeader } from "@/components/site-header";

export const Route = createFileRoute("/")({
  component: Home,
  head: () => ({
    meta: [
      { title: "成书" },
      {
        name: "description",
        content: "在手机浏览器里看到想细读的长文，点系统分享，选成书。",
      },
    ],
  }),
});

function Home() {
  return (
    <>
      <SiteHeader lang="zh" />
      <main className="mx-auto max-w-[744px] px-5 pt-10 pb-24 text-muted-foreground sm:px-8 sm:pt-14">
        <article className="max-w-[600px] leading-[1.75] [&_p+p]:mt-[1.5em] [&_ul]:mt-[1.5em]">
          <p className="text-foreground">
            在手机浏览器里看到想细读的长文，点系统分享，选「成书」。它会自动抓取网页正文，转成
            EPUB、Markdown 或纯文本，直接唤起微信读书、KOReader
            等应用，或者转成 Markdown 交给 AI 整理。
          </p>
          <p>选好格式与打开方式后，不需要留在后台，平时只在分享菜单里见。</p>
          <p>
            在手机浏览器里看长文很受罪：满屏广告、浮动弹窗，动不动还要跳客户端。
          </p>
          <p>
            这种内容本该待在专门的阅读环境里。微信读书自带排版和翻译，KOReader
            适合折腾墨水屏，字体和翻页都舒服得多。
          </p>
          <p>
            但在 Android
            上，这两者很难直接衔接：阅读器要的是本地文件，而浏览器分享出来的只是一串网址。以往要把文章塞进阅读器，得经历一套极其折腾的操作：复制链接、找网页转码、等生成、下载文件、再进文件管理器翻出来手动拉起阅读器。折腾完，阅读的状态早没了。
          </p>
          <p>
            我也不想用 Pocket
            这类服务，不想把数据放在云端，更不需要多余的社交功能。两端都在本地，缺的只是一个干净的转换管道。
          </p>
          <p>这个小工具做的事很简单：</p>
          <ul className="list-disc space-y-2 pl-5">
            <li>挂在系统分享菜单里；</li>
            <li>接过网址，剥离正文，排成 EPUB、Markdown 或纯文本；</li>
            <li>
              直接把文件传给系统里任意支持的应用打开——既可以是阅读器，也可以交给
              AI 提炼。
            </li>
          </ul>
          <p>
            重复分享过的链接它认得，不会重复抓取。配好常用格式和应用，平时完全不用管它。
          </p>
          <p>
            当前页面就是一个测试用例。如果手机上已经装好了，点一下浏览器的分享，选「成书」，看看它排进阅读器是什么样。
          </p>
        </article>
      </main>
    </>
  );
}
