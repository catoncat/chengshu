/** Offline fixture used by the “try one” button and quality tests. */
export const SAMPLE_TITLE = "成书示例：把一篇文章做成书";

export const SAMPLE_HTML = `<article>
  <h1>成书示例：把一篇文章做成书</h1>
  <p>这是内置示例，不访问外网。它用来确认成书真的会打包，而不是播放一段假装成功的动画。</p>
  <h2>为什么要本地保存</h2>
  <p>浏览器分享出来的只是网址。阅读器要的是文件。成书接过链接，抽出正文，写成 EPUB 或纯文本。</p>
  <p>下面有一张示意图，以及一条脚注。<a href="#note">[1]</a></p>
  <p><img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jmfkAAAAASUVORK5CYII=" alt="1 像素示例图"/></p>
  <h2>代码也不应被挤成一行</h2>
  <pre><code>function hello(name) {
  return "hi " + name;
}</code></pre>
  <p id="note">[1] 脚注应能从目录或链接跳回来。</p>
</article>`;

export const SAMPLE_BR_ESSAY = `<div>July 2023<br><br>If you collected lists of techniques for doing great work, the beginning would be the most important.<br><br>Partly my goal was to create a guide that would work for as many kinds of work as possible.<br><br>The first step is to decide what to work on. The work you choose needs to have three qualities: it has to be something you have a natural aptitude for, that you have a deep interest in, and that offers scope to do great work.</div>`;
