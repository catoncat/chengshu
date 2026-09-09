# 成书施工进度：每次接手先看这里

> 更新日期：2026-09-09。
> 本文是唯一任务进度账本。任务具体步骤、依赖和完成条件以 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) 为准。

## 1. 现在最重要的结论

**当前执行：R01–R05 第一阶段可用修复版 1.12 / versionCode 13。**

R01 已在本分支实现：空/空白图片地址不再解析成文章 URL。R02 已补 `scripts/android-ci.sh` 的 `pipefail`、有效 `npm test` 入口和发布清单脚本测试。R04 发布作业改为先验证再写 `app.json`，旧运行不能覆盖更高 versionCode。R03 真机覆盖安装仍未跑；签名核验等 CI 产物与旧官方 APK 对比。R05 在本分支合并进 main 且公网下载与清单一致后才算完成。

不要从全量重构、改框架或扩格式开始。

当前代码以 `main` 最新提交为基线继续向前，不回退到交接书中的历史 SHA。

## 2. 当前事实与证据

| 项目 | 已核实的状态 |
| --- | --- |
| 文档 PR #2 | 已合并 |
| 上一轮 Android 构建 | [运行 34327606211](https://github.com/catoncat/chengshu/actions/runs/34327606211) 失败于空图片测试 |
| 旧官方 APK | 1.11 / code 12，sha256 `dccd503611a4f8aa2cd70fa0086a128bbbfd900a08b9864bf71816dfc8780edc`，与当时 `https://0nl.onl/chengshu.apk` 一致 |
| 本分支版本 | `1.12 / versionCode 13`，包名仍为 `onl.nl0.chengshu`，签名仍用仓库 stable keystore |
| 本分支新 APK | 尚未由 CI 构建；不能把本文件更新当成已发布 |
| 真机覆盖升级 | NOT_RUN：当前环境无授权测试设备 |

旧测试成绩只证明那个源提交上被执行的测试。修复后必须重新运行。

## 3. 状态怎么使用

- `TODO`：尚未按该任务完成实现与验证。
- `IN_PROGRESS`：正在执行。
- `BLOCKED`：记录具体缺失的权限、环境、外部条件。
- `DONE`：实现、测试、证据与文档已齐全。

## 4. 主任务账本

| ID | 任务 | 状态 | 完成提交 / 验证证据 |
| --- | --- | --- | --- |
| R01 | 空图片与缺图统计修复 | IN_PROGRESS | 本分支 LocalEpub 规范化空 src；保留原 1 张嵌入/2 张缺失断言；本地已跑 node 回归。Android JUnit 待 CI |
| R02 | 可重复构建与真实测试入口 | IN_PROGRESS | `scripts/android-ci.sh`、`npm test` 只跑存在的文件、pipefail 守卫测试 |
| R03 | 旧版覆盖升级与签名核验 | IN_PROGRESS | 旧 APK 已封存；缺真机安装。CI 产物到位后比对包名/versionCode/证书 |
| R04 | 自动版本与完整发布流水线 | IN_PROGRESS | `scripts/publish-manifest.mjs` 覆盖 skip-older / reuse / publish |
| R05 | 发布第一个确实能用的修复版 | TODO | 待 main CI 通过并更新 0nl.onl |
| D01 | 固定旧版数据与故障语料 | TODO | — |
| D02 | 事务元数据目录与无损迁移 | TODO | — |
| D03 | 版本快照、产物和提交凭据 | TODO | — |
| D04 | 取消、刷新、删除竞争与去重身份 | TODO | — |
| B01 | 从正文 checkpoint 后台继续 | TODO | — |
| B02 | 重启对账与真实进程恢复 | TODO | — |
| B03 | 通知、网络等待与系统停止 | TODO | — |
| B04 | 动态页面提取的有界实验 | TODO | — |
| U01 | 首次示例与目的地优先 | TODO | — |
| U02 | 首页、最近与任务状态统一 | TODO | — |
| U03 | 失败恢复、文件分享与导出 | TODO | — |
| U04 | 深色、大字体、无障碍与返回 | TODO | — |
| Q01 | 固定阅读语料与 EPUBCheck | TODO | — |
| Q02 | 图片持久资源清单与有界流水线 | TODO | — |
| Q03 | 目录、脚注、排版与语义保真 | TODO | — |
| Q04 | 结构化质量报告与异常提示 | TODO | — |
| S01 | 服务端网络、缓存与第三方边界 | TODO | — |
| S02 | TXT / Markdown / HTML 设备端输出 | TODO | — |
| S03 | 更新下载校验与长期签名方案 | TODO | — |
| P01 | 主线程、内存与延迟优化 | TODO | — |
| P02 | 安全垃圾回收与低空间恢复 | TODO | — |
| P03 | 明确导出、备份与恢复 | TODO | — |
| M01 | 官网、说明与发布可见性 | TODO | — |
| M02 | 真实日常使用、最终验收与再排序 | TODO | — |

## 5. 本轮施工记录

日期 / 任务 ID：2026-09-09 / R01–R04

状态：IN_PROGRESS

工作分支：`release/1.12-usable-fix`

本次实际改动：LocalEpub 空图片地址；android-ci pipefail；发布清单脚本；versionCode 13。

实际运行命令：`node --test scripts/chengshu-share.test.mjs scripts/publish-manifest.test.mjs scripts/pipefail-guard.test.mjs`（待填结果）；`gradle -p android testDebugUnitTest` 因本环境无 Gradle/SDK 未跑。

真实设备 / 阅读器 / 产物信息：设备验收未完成。旧官方 APK sha256 见上表。

未运行的验收项及原因：Android JUnit、assemble、真机覆盖、微信读书/KOReader 导入。

已知限制与风险：仍使用仓库 debug.keystore 作为 stable 签名，长期方案见 S03。

下一任务：CI 绿后合并 main，完成 R05 公网下载核验。

确实需要用户完成的操作：没有。真机覆盖升级证据若要闭合，需要一台已装 1.11 的测试机，不要求连接 Mac。

## 6. 文档交付记录

2026-09-09：文档 PR #2 合并。随后开始 R01–R05 代码施工，不以“写好计划”代替产品完成。
