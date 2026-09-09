# 成书施工进度：每次接手先看这里

> 更新日期：2026-09-09。
> 本文是唯一任务进度账本。任务具体步骤、依赖和完成条件以 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) 为准。

## 1. 现在最重要的结论

**公网已发布 1.13 / versionCode 14。** 下载 [0nl.onl/chengshu.apk](https://0nl.onl/chengshu.apk)，sha256 `e77b11e1559765f70e69e96958f62d94c1e9153c885a9196a414bb9aba4bd46e`，sourceCommit `c1d3c63`。

**本分支：1.14 / versionCode 15。** 分享路径走事务目录打包；首页可导出 ZIP 备份。合并后发布作业会更新官网 APK。

真机覆盖安装、阅读器导入、系统杀死后的 WorkManager 唤醒仍未验证。

## 2. 当前事实与证据

| 项目 | 已核实的状态 |
| --- | --- |
| 公网 1.13 | [0nl.onl/chengshu.apk](https://0nl.onl/chengshu.apk) versionCode 14，hash 已核对 |
| 本分支版本 | 1.14 / 15，待 CI 与发布 |
| 真机覆盖 / 阅读器 | NOT_RUN |
| B04 后台 WebView | 实验结论：不可行，NEEDS_USER |

## 3. 状态怎么使用

没跑的验收不能写成 PASS。

## 4. 主任务账本

| ID | 任务 | 状态 | 完成提交 / 验证证据 |
| --- | --- | --- | --- |
| R01 | 空图片与缺图统计修复 | DONE | 1.12 / PR #3 |
| R02 | 可重复构建与真实测试入口 | DONE | android-ci.sh |
| R03 | 旧版覆盖升级与签名核验 | IN_PROGRESS | 1.13 可下载；真机覆盖未跑 |
| R04 | 自动版本与完整发布流水线 | DONE | publish-manifest.mjs；1.13 已发布 |
| R05 | 发布第一个确实能用的修复版 | IN_PROGRESS | 1.13 可下载；覆盖升级未跑 |
| D01 | 固定旧版数据与故障语料 | DONE | tests/fixtures/articles；legacy 迁移测试 |
| D02 | 事务元数据目录与无损迁移 | DONE | Store + ArticleRepository；JVM 测试，非 SQLite |
| D03 | 版本快照、产物和提交凭据 | DONE | snapshot/artifact/receipt；重复 runInline 复用 |
| D04 | 取消、刷新、删除竞争与去重身份 | DONE | 取消后不入库产物；commit 与 catalog 同一事务 |
| B01 | 从正文 checkpoint 后台继续 | DONE | ConversionCoordinator 不依赖 Activity 字段；无 WorkManager |
| B02 | 重启对账与真实进程恢复 | IN_PROGRESS | JobRepository.unfinished；无设备重启证据 |
| B03 | 通知、网络等待与系统停止 | IN_PROGRESS | 不抢前台已有；无通知 channel 设备验证 |
| B04 | 动态页面提取的有界实验 | DONE | CaptureController：不可后台捕获，NEEDS_USER |
| U01 | 首次示例与目的地优先 | DONE | 试一篇文章 / 粘贴链接；示例走 LocalEpub |
| U02 | 首页、最近与任务状态统一 | IN_PROGRESS | 首页文案与入口；1000 条列表未测 |
| U03 | 失败恢复、文件分享与导出 | IN_PROGRESS | 既有长按分享/失败文案保留 |
| U04 | 深色、大字体、无障碍与返回 | IN_PROGRESS | values-night；TalkBack 设备未跑 |
| Q01 | 固定阅读语料与 EPUBCheck | IN_PROGRESS | 示例 fixture；多文件 spine 测试；未接 EPUBCheck 发行包 |
| Q02 | 图片持久资源清单与有界流水线 | IN_PROGRESS | ImageRepository 缓存；未接全预算矩阵 |
| Q03 | 目录、脚注、排版与语义保真 | IN_PROGRESS | 两个 h2 拆成独立 spine；body title；中文首行缩进 |
| Q04 | 结构化质量报告与异常提示 | DONE | QualityReport 规则与登录页误报测试 |
| S01 | 服务端网络、缓存与第三方边界 | DONE | fetchPublic 跳转校验；cacheKey 全文；私网 IP 测试 |
| S02 | TXT / Markdown / HTML 设备端输出 | DONE | LocalPack；PDF 仍服务端 |
| S03 | 更新下载校验与长期签名方案 | IN_PROGRESS | sha256/来源/大小校验；不换钥匙 |
| P01 | 主线程、内存与延迟优化 | IN_PROGRESS | 打包仍在后台线程；无 StrictMode 设备报告 |
| P02 | 安全垃圾回收与低空间恢复 | DONE | Gc.dryRun/sweep 不删引用 |
| P03 | 明确导出、备份与恢复 | IN_PROGRESS | 单文件分享已有；ZIP 备份待本分支发布 |
| M01 | 官网、说明与发布可见性 | DONE | 中英首页写明本地/PDF 边界；网站可粘贴/示例/转本页 |
| M02 | 真实日常使用、最终验收与再排序 | IN_PROGRESS | 无 WeRead/KOReader 设备证据 |

## 5. 本轮施工记录

日期 / 任务 ID：2026-09-09 / 1.14 目录打包 + 备份

状态：IN_PROGRESS

工作分支：`feat/1.14-catalog-backup`

1.13 已发布：versionCode 14，公网 APK hash 已核对。本分支让分享路径走 ConversionCoordinator（本地格式），首页可导出 ZIP 备份，不删除原文件。

未运行：真机覆盖、阅读器、WorkManager 设备重启、EPUBCheck 发行包。

下一任务：CI 绿后合并并发布 1.14。

确实需要用户完成的操作：没有。已装 1.12 的手机可先装 1.13，再覆盖 1.14。
