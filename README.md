# 成书

分享网页，做成书。

在手机浏览器里看到想细读的长文，点系统分享，选「成书」。它抽出正文，转成 EPUB、PDF、Markdown 或纯文本，直接打开微信读书、KOReader，或交给别的应用。

- 网站：[0nl.onl](https://0nl.onl)
- 下载：[chengshu.apk](https://0nl.onl/chengshu.apk)
- English: [0nl.onl/en](https://0nl.onl/en)

装好之后，用当前页面试一次即可。

## 做什么

浏览器分享出来的只是网址，阅读器要的是本地文件。成书挂在系统分享菜单里，接过链接，剥离正文，把文件交给你指定的应用。

格式和打开方式都可以记住，也可以每次再选。重复分享过的链接不会重新抓。

## 格式

| | |
| --- | --- |
| EPUB | 微信读书、KOReader |
| PDF | 跨设备、可重排 |
| Markdown | 笔记、AI |
| HTML / 纯文本 | 任意能打开的应用 |

手机上先在 WebView 里渲染页面再抽正文（Defuddle），避免空壳 SPA 抓不到字。网站粘贴链接走同一套打包。

## 自己编译

Android 工程在 [`android/`](android/)。网站与转换接口是这个仓库的其余部分。

---

# 成书

Share a page. Get a book.

From the phone browser, share into 成书. It pulls the article, writes EPUB, PDF, Markdown, or plain text, and opens WeChat Reading, KOReader, or whatever you use.

- Site: [0nl.onl](https://0nl.onl)
- APK: [chengshu.apk](https://0nl.onl/chengshu.apk)
