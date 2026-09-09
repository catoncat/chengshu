# 成书施工进度：每次接手先看这里

> 更新日期：2026-09-09。
> 本文是唯一任务进度账本。任务具体步骤、依赖和完成条件以 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) 为准。

## 1. 现在最重要的结论

**用户现在可以下载 1.12（versionCode 13）。** 入口：[0nl.onl/chengshu.apk](https://0nl.onl/chengshu.apk)，清单：[0nl.onl/app.json](https://0nl.onl/app.json)。

R01 已在 CI 验证：原先失败的 `failuresProduceVisiblePlaceholdersAndPersistentWarning` 通过，空图片不再被当成文章 URL。R02 的 `android-ci.sh` 带 `pipefail`，测试失败不会再发布。R04 已把 sha256/size/sourceCommit 写入公网清单。R03 真机覆盖安装未跑。R05 公网文件已更新；没有阅读器回执，不能声称“已导入微信读书”。

下一任务按依赖：D01、Q01、S01 可并行；U 系列等 R05 设备证据或接受其 NOT_RUN 后由负责人决定。不要从换框架开始。

## 2. 当前事实与证据

| 项目 | 已核实的状态 |
| --- | --- |
| 文档 PR #2 | 已合并 |
| 修复 PR #3 | 已合并，`8f01858006288bd9070b792115df2685a51ccedb` |
| 发布提交 | `ff2518e` `android: 发布 APK 8f01858` |
| 合并后 Android CI | [运行 34343742760](https://github.com/catoncat/chengshu/actions/runs/34343742760) 成功 |
| 旧官方 APK 1.11 | sha256 `dccd503611a4f8aa2cd70fa0086a128bbbfd900a08b9864bf71816dfc8780edc` |
| 现官方 APK 1.12 | sha256 `d4a360804ddf17c959d43fac9f242c1ad98618c0cce0c81e6e526e8d61489e33`，size 2407471，与 app.json 一致 |
| 公网清单 | versionCode 13，versionName 1.12，channel stable，sourceCommit `8f01858…` |
| 包名 / 签名 | 仍为 `onl.nl0.chengshu` + 仓库 stable keystore；未换钥匙 |
| 真机覆盖升级 | NOT_RUN |
| 微信读书 / KOReader | NOT_RUN |

## 3. 状态怎么使用

- `TODO` / `IN_PROGRESS` / `BLOCKED` / `DONE`
- 没跑的验收不能写成 PASS。R03 设备项未跑，故 R03、R05 不能标成全部完成。

## 4. 主任务账本

| ID | 任务 | 状态 | 完成提交 / 验证证据 |
| --- | --- | --- | --- |
| R01 | 空图片与缺图统计修复 | DONE | PR #3；原 1 张嵌入/2 张缺失断言在 CI 通过；[34343569371](https://github.com/catoncat/chengshu/actions/runs/34343569371) |
| R02 | 可重复构建与真实测试入口 | DONE | `scripts/android-ci.sh`、`npm test` 只跑存在文件、pipefail 守卫测试 |
| R03 | 旧版覆盖升级与签名核验 | IN_PROGRESS | 新旧 APK hash/版本已记录且 code 13>12；**真机 install -r 未跑** |
| R04 | 自动版本与完整发布流水线 | DONE | `publish-manifest.mjs`；公网 app.json 含 sha256/sourceCommit；旧运行不能覆盖更高 code |
| R05 | 发布第一个确实能用的修复版 | IN_PROGRESS | 用户可从 0nl.onl 下载 1.12；覆盖安装与阅读器导入未验证 |
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

日期 / 任务 ID：2026-09-09 / R01–R05

状态：公网 1.12 已上线；设备升级证据未完成

基线提交 / 实现提交：`8f01858` / 发布 `ff2518e`

工作分支或 PR：[PR #3](https://github.com/catoncat/chengshu/pull/3) 已合并

本次实际改动：LocalEpub 空 src；android-ci pipefail；publish-manifest；versionCode 13

实际运行：
- `node --test scripts/chengshu-share.test.mjs scripts/publish-manifest.test.mjs scripts/pipefail-guard.test.mjs` → 22 pass
- GitHub `gradle -p android testDebugUnitTest assembleDebug assemblePreview` → [34343742760](https://github.com/catoncat/chengshu/actions/runs/34343742760) success
- `curl https://0nl.onl/app.json` → 1.12 / 13，sha256 与 APK 一致

真实设备 / 阅读器：设备验收未完成

未运行：`adb install -r`、微信读书/KOReader 打开、低内存、深色模式

已知限制：仍用仓库 debug.keystore 作 stable 签名（S03）；进程被杀后不自动后台抓页

下一任务：D01 或 Q01/S01；R03 若要闭合需要一台已装 1.11 的测试机做覆盖安装

确实需要用户完成的操作：若要验证覆盖升级，在已装 1.11 的手机上打开 App 检查更新或下载 1.12 覆盖安装。不是必须。不要求连接 Mac。

## 6. 文档交付记录

2026-09-09：文档 PR #2 合并。随后 PR #3 修复空图片并发布 1.12。不以“写好计划”代替产品完成；也不把“已请求打开阅读器”说成已导入。
