# 成书施工进度：每次接手先看这里

> 更新日期：2026-09-09。
> 本文是唯一任务进度账本。任务具体步骤、依赖和完成条件以 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) 为准。

## 1. 现在最重要的结论

**公网已发布 1.15 / versionCode 16。** 下载 [0nl.onl/chengshu.apk](https://0nl.onl/chengshu.apk)，sha256 `4fc600b8b4b07ac4947428d8be394dafb32233b43267a0d0d830f1dcbbe0c384`，sourceCommit `8ad778a`。

**本分支：1.16 / versionCode 17。** 每行有「更多」；可保存到文件夹；查看原文；图片缓存可复用/取消；EPUB 保留 ol start、表头 colspan、嵌套列表，列表不再被正文缩进挤歪。

真机覆盖安装、阅读器导入、系统杀死后的 WorkManager 唤醒、官方 EPUBCheck 二进制仍未验证。

## 2. 当前事实与证据

| 项目 | 已核实的状态 |
| --- | --- |
| 公网 1.15 | [0nl.onl/chengshu.apk](https://0nl.onl/chengshu.apk) versionCode 16，hash 已核对 |
| 本分支版本 | 1.16 / 17，待 CI 与发布 |
| 真机覆盖 / 阅读器 | NOT_RUN |
| B04 后台 WebView | 实验结论：不可行，NEEDS_USER |

## 3. 状态怎么使用

没跑的验收不能写成 PASS。

## 4. 主任务账本

| ID | 任务 | 状态 | 完成提交 / 验证证据 |
| --- | --- | --- | --- |
| R01 | 空图片与缺图统计修复 | DONE | 1.12 / PR #3 |
| R02 | 可重复构建与真实测试入口 | DONE | android-ci.sh |
| R03 | 旧版覆盖升级与签名核验 | IN_PROGRESS | 1.15 可下载；真机覆盖未跑 |
| R04 | 自动版本与完整发布流水线 | DONE | publish-manifest.mjs；1.15 已发布 |
| R05 | 发布第一个确实能用的修复版 | IN_PROGRESS | 1.15 可下载；覆盖升级未跑 |
| D01 | 固定旧版数据与故障语料 | DONE | tests/fixtures/articles；legacy 迁移测试 |
| D02 | 事务元数据目录与无损迁移 | DONE | Store + ArticleRepository；JVM 测试，非 SQLite |
| D03 | 版本快照、产物和提交凭据 | DONE | snapshot/artifact/receipt；重复 runInline 复用 |
| D04 | 取消、刷新、删除竞争与去重身份 | DONE | 取消后不入库产物；commit 与 catalog 同一事务 |
| B01 | 从正文 checkpoint 后台继续 | DONE | ConversionCoordinator 不依赖 Activity 字段；无 WorkManager |
| B02 | 重启对账与真实进程恢复 | IN_PROGRESS | JobRepository.unfinished；无设备重启证据 |
| B03 | 通知、网络等待与系统停止 | IN_PROGRESS | ResultsNotifier + POST_NOTIFICATIONS；切走才发；无设备验证 |
| B04 | 动态页面提取的有界实验 | DONE | CaptureController：不可后台捕获，NEEDS_USER |
| U01 | 首次示例与目的地优先 | DONE | 试一篇文章 / 粘贴链接；示例走 LocalEpub |
| U02 | 首页、最近与任务状态统一 | IN_PROGRESS | 「需要注意」与「最近」；每行「更多」；1000 条列表未测 |
| U03 | 失败恢复、文件分享与导出 | IN_PROGRESS | 分享/保存到文件夹/查看原文；取消文件选择器不删书 |
| U04 | 深色、大字体、无障碍与返回 | IN_PROGRESS | values-night；48dp「更多」；TalkBack 设备未跑 |
| Q01 | 固定阅读语料与 EPUBCheck | IN_PROGRESS | EpubInspect 结构门禁；未接官方 EPUBCheck 发行包 |
| Q02 | 图片持久资源清单与有界流水线 | IN_PROGRESS | ImageRepository 缓存复用与取消测试；未接全预算矩阵 |
| Q03 | 目录、脚注、排版与语义保真 | IN_PROGRESS | ol start、th colspan、嵌套列表、列表不缩进 |
| Q04 | 结构化质量报告与异常提示 | DONE | QualityReport 规则与登录页误报测试 |
| S01 | 服务端网络、缓存与第三方边界 | DONE | fetchPublic 跳转校验；cacheKey 全文；私网 IP 测试 |
| S02 | TXT / Markdown / HTML 设备端输出 | DONE | LocalPack；PDF 仍服务端 |
| S03 | 更新下载校验与长期签名方案 | IN_PROGRESS | sha256/来源/大小校验；不换钥匙 |
| P01 | 主线程、内存与延迟优化 | IN_PROGRESS | 打包仍在后台线程；无 StrictMode 设备报告 |
| P02 | 安全垃圾回收与低空间恢复 | DONE | Gc.dryRun/sweep 不删引用 |
| P03 | 明确导出、备份与恢复 | IN_PROGRESS | ZIP 导出含清单；导入不覆盖；单文件可保存到文件夹 |
| M01 | 官网、说明与发布可见性 | DONE | 中英首页写明本地/PDF 边界；网站可粘贴/示例/转本页 |
| M02 | 真实日常使用、最终验收与再排序 | IN_PROGRESS | 无 WeRead/KOReader 设备证据 |

## 5. 本轮施工记录

日期 / 任务 ID：2026-09-09 / 1.16 行内操作 + 保存到文件夹 + 列表表格保真

状态：IN_PROGRESS

工作分支：`feat/1.16-row-actions`

1.15 已发布：versionCode 16，公网 APK hash 已核对。本分支：

- 最近/需要注意每一行有可点的「更多」，不再只能长按。
- 已保存文章可「保存到文件夹」（系统文件选择器）；取消选择不删除原文件。可查看原文。
- 设备端打包复用 ImageRepository 缓存；取消后不再发起新的图片请求。
- EPUB 保留编号起点、表头合并单元格、嵌套列表；列表项不再使用正文首行缩进。

未运行：真机覆盖、阅读器、WorkManager 设备重启、官方 EPUBCheck JAR、TalkBack。

下一任务：CI 绿后合并并发布 1.16。

确实需要用户完成的操作：没有。已装旧版的手机可直接覆盖安装 1.16。
