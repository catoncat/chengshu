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

手机上先在 WebView 里渲染页面再抽正文（Defuddle）。Android 的 EPUB 在设备端生成，不把正文发给转换服务器；读取网页和下载图片仍然需要联网。PDF、Markdown、HTML、纯文本仍需把提取正文发给转换接口；网站版本的转换也仍在服务端完成。

保存后的同格式文件优先本地打开；换格式优先复用正文快照，只有明确“重新抓取”才刷新正文。图片失败或超出资源预算会在书内和打开前提示。设备端生成会保留标题层级目录、脚注锚点、表格与列表。

未完成分享保留在“最近”中供继续处理，已保存文件不再按 200 篇自动删除。当前不承诺进程被系统回收后自动后台执行，重开应用可继续。详见 [实现与验收边界](docs/RELIABLE_DELIVERY.md)。

## 自己编译

Android 工程在 [`android/`](android/)。网站与转换接口是这个仓库的其余部分。

```sh
bash scripts/test-archive.sh
node --test scripts/chengshu-share.test.mjs
gradle -p android testDebugUnitTest assembleDebug assemblePreview
```

`assemblePreview` 产物使用独立包名 `onl.nl0.chengshu.preview`，可与正式版并存，不覆盖正式版保存内容，也不会更新成正式版。开发分支构建只生成测试附件，不发布官网 APK。

---

# 成书

Share a page. Get a book.

From the phone browser, share into 成书. It pulls the article, writes EPUB, PDF, Markdown, or plain text, and opens WeChat Reading, KOReader, or whatever you use.

- Site: [0nl.onl](https://0nl.onl)
- APK: [chengshu.apk](https://0nl.onl/chengshu.apk)
