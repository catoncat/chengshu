# 成书发布操作手册：直到用户能下载安装才算结束

> 本文是执行手册，不是本次发版记录。本次文档 PR 没有生成或发布新版 APK。
> 配套门禁见 [ACCEPTANCE.md](ACCEPTANCE.md)，任务 R01–R05/S03 见 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)。

## 1. 发布完成的定义

必须依次区分以下五件事：

1. 代码已经提交/合并。
2. 指定源提交已经通过测试并生成 APK。
3. APK 的包名、版本、签名、内容与升级路径已经验证。
4. 发布位置与版本清单已经更新，用户可从真实下载入口获得该文件。
5. 旧版能识别新版本，覆盖安装保留数据，用户能使用承诺的新能力。

任何一步失败，不把后面几步也说成完成。PR 合并、CI artifact 上传、仓库 public 文件变化、官网部署成功分别是不同证据。

## 2. 基线事实与第一项修复

基线提交：`0914e18e85a7a402ee34ef5174aa2a30aa52f0e9`。

当前仓库版本为 1.11/code 12；android Actions 运行 34327606211 在 LocalEpub 缺图测试失败。先做 R01，不能只重跑同一失败提交。

在基线仍未变化的前提下，第一版修复可使用 1.12/code 13；执行时必须重新读取源代码与已发布版本，若已有更高版本就采用更高 code。不要机械照抄 13。

当前构建签名来自 `android/debug.keystore` 的 stable 配置。正式旧 APK 的真实证书才是兼容判断依据。不要打印私钥/密码，不要重新生成 keystore 让构建“先过了再说”。公开仓库中的签名材料有长期安全风险，按 S03 独立处置。

## 3. 发布前准备

使用已授权的开发或 CI 环境，不依赖连接用户 Mac。先确认任务允许发布；文档任务不应执行本手册的写入/发布操作。

```sh
git status --short --branch
git diff --stat
git diff
git log -8 --oneline
java -version
gradle --version
node --version
```

基线需要 Java 17；CI 固定 Gradle 8.9；实际 AGP、SDK 要读当前文件。工具链升级是单独变更，不在发版当天顺手更新全部依赖。

存在他人工作区修改就保护并分离，不 reset、不清空。发布必须针对一个明确提交，不能从混有未提交改动的目录生成包却标为该提交。

确认空间、网络、Android SDK、构建依赖和可用测试设备。没有真机/阅读器时报告设备验收缺口，可以生成明确预览包，但不能把未验收包发布成完整可靠升级。

## 4. 最小修复与版本递增

先运行失败测试，修复空/空白图来源，不更改正确断言。保留新回归用例。版本递增使用构建的单一权威配置；不要先手改 `public/app.json` 宣称新版本存在。

如果 R04 自动分配已经实现，使用其输出作为构建参数；若尚未实现，手动修改当前 `versionCode/versionName` 并提交，不假装自动化已经存在。

包身份：stable 保持 `onl.nl0.chengshu`；preview 保持 `onl.nl0.chengshu.preview`。正式升级不能误装独立 preview 包。

## 5. 构建与保存真实退出码

在仓库根目录，基线已有命令：

```sh
set -euo pipefail
mkdir -p build-evidence
bash scripts/test-archive.sh 2>&1 | tee build-evidence/archive.log
node --test scripts/chengshu-share.test.mjs 2>&1 | tee build-evidence/share.log
gradle -p android testDebugUnitTest assembleDebug assemblePreview \
  2>&1 | tee build-evidence/android.log
```

`build-evidence` 是示例本地输出目录，加入忽略或在临时目录存放，不把 APK/私人日志误提交。命令中的 pipefail 保留，测试失败就停止发布。

基线没有 `./gradlew`。R02 添加并验证 wrapper 后统一改命令，不能在没有文件时报告 wrapper 构建成功。

已有输出路径：

```text
android/app/build/outputs/apk/debug/app-debug.apk
android/app/build/outputs/apk/preview/app-preview.apk
```

基线官方流水线发布的是带 stable 签名的 debug 构建。切换到 release build 可作为 S03 的受控改进，但必须再验证签名、调试标志、WebView/网络行为与旧包兼容；不能仅改复制路径。

改到网站/服务端时运行其真实受影响测试与纯构建。基线 `npm run build` 会继续执行 db:migrate，不能在不知目标环境的情况下用它做无害验证；先使用经核实的隔离环境/纯构建入口。

## 6. APK 身份检查

下面是标准工具用法示例；OLD_APK/NEW_APK/TEST_DEVICE 由执行者设置成实际测试文件与专用设备，不能让用户猜。工具需在已配置的 SDK PATH 中。

```sh
: "${OLD_APK:?设置为实际旧官方 APK 路径}"
: "${NEW_APK:?设置为本次候选 APK 路径}"
test -f "$OLD_APK"
test -f "$NEW_APK"
apksigner verify --verbose --print-certs "$OLD_APK"
apksigner verify --verbose --print-certs "$NEW_APK"
aapt dump badging "$OLD_APK"
aapt dump badging "$NEW_APK"
sha256sum "$OLD_APK" "$NEW_APK"
```

核对：包名、versionCode 严格更大、versionName、minSdk、证书或合法签名演进关系。证书 hash 可以记录，私钥和密码不能记录。单纯 SHA-256 相同只证明两个下载的字节一样，不证明签名安全。

[Android 签名指南](https://developer.android.com/studio/publish/app-signing)、[apksigner 参考](https://developer.android.com/tools/apksigner) 是平台依据。钥匙不同的新装包不能当成旧用户可升级包。

## 7. 真实覆盖升级

只在专用测试设备/模拟器执行，不对用户正在使用的手机做卸载或清数据。

```sh
: "${TEST_DEVICE:?设置为已授权测试设备序列号}"
adb -s "$TEST_DEVICE" get-state
adb -s "$TEST_DEVICE" install "$OLD_APK"
# 通过旧 App 的正常界面生成文章、格式与偏好，并记录文件校验值。
adb -s "$TEST_DEVICE" install -r "$NEW_APK"
```

若设备已装更高版本，不用 `-d`、卸载或清数据绕过来宣称升级成功；创建合适的独立测试环境重做。

必须检查：旧文章数量与字节；同标题文章；多格式；默认去向；待处理请求；新文章保存；缺图提示；离线重开；目标不存在/选择器取消；导出；新旧数据路径混合。按 ACCEPTANCE 保留证据。

WeRead 与 KOReader 核心路径要实际读到文章、点目录/脚注；发送 Intent 返回成功不算导入确认。无法访问真实阅读器时单列缺口，不用测试接收 App 替代全部验收。

## 8. 自动发布流水线的目标结构（R04 拟实现）

### 8.1 build 与 publish 分离

验证作业尽量 contents:read。发布作业只在可信 main 来源、授权事件和所有门禁满足后取得必要写权限。不要用 pull_request_target 执行不可信 PR 代码来拿到发布凭据。

文档-only 不发布；PR 上传 preview 与报告，不写 stable；正式运行携带不可变 sourceCommit。

### 8.2 串行分配版本

一个发布并发组，正式发布不在关键写入中被随意取消。分配 versionCode 前读当前已发布记录；新提交分配高于旧值，已发布同一提交重跑复用身份。运行晚到时如果已有更高且更新的已发布版本，旧运行不能改 latest。

不得仅使用日期秒数、commit 数量或 workflow run_number 而不处理重跑、回退、workflow 更换和并发。

### 8.3 构建来源与版本的绑定

记录 sourceCommit、source tree、versionCode、versionName、构建参数、工具链、artifact hash、证书摘要、测试运行。版本通过已实现的 Gradle 参数入口传入。入口未实现时不在 CI 写一个无效 `-P...` 并假装版本已变。

生成版本参数属于构建配方，不改变所标注的源提交；复现包时同时提供配方。发布机器人更新 public 文件的提交与 App 源提交区分记录。

### 8.4 先不可变文件，后 latest

推荐新增版本化 APK 地址，验证成功后才更新最新清单。已有 `https://0nl.onl/chengshu.apk` 与 `app.json` 保留兼容；URL 路径设计确定后同步代码/README，不假装推荐路径已经在线。

失败时 latest 保留旧值。发布清单至少包括旧字段，新增字段向后兼容；同一 code 对应的文件不得事后更换不同字节。修复生成新 code。

### 8.5 网站部署不能靠猜

当前 workflow 是将 public/APK 与 app.json 提交回 main。执行者必须核实实际 hosting 集成是否会对该机器人提交部署，以及部署的结果。

GitHub 对 `GITHUB_TOKEN` 触发事件有防递归规则，不能依赖“我 push 了，另一个 push workflow 一定会跑”。优先同一发布链显式执行/等待既有部署，或采用经授权、已经验证的触发路径，不索取宽泛 token 作为第一方案。参考 [GitHub 触发规则](https://docs.github.com/actions/using-workflows/triggering-a-workflow)。

## 9. 使用 GitHub 查看构建时要核对源提交

可以使用 GitHub 工具或已经配置好的 CLI。以下是 CLI 示例，不表示本环境已安装或本轮已执行：

```sh
gh run list --repo catoncat/chengshu --branch main --workflow android.yml --limit 5
gh run view "$RUN_ID" --repo catoncat/chengshu --json status,conclusion,headSha,jobs
gh run download "$RUN_ID" --repo catoncat/chengshu \
  --name chengshu-apk --dir build-evidence/apk
```

核对 headSha 是本次候选。不要拿最近一个绿色运行替代当前红色运行。失败时读 job 日志和 JUnit XML；没有 APK artifact 是失败结果的一部分，不是“下载链接暂时找不到”。

修复后触发新的提交构建，不能在旧 run 上 rerun 然后指望它包含新代码。需要手动 workflow_dispatch 时明确 ref，并遵守同样门禁。

## 10. 公网验证与 App 更新验证

发布完成后从真实用户入口重新下载，不能只读仓库文件。这些命令会访问实际网站，但不代表本次文档工作已经执行：

```sh
curl --fail --location --connect-timeout 10 --max-time 30 \
  -H 'Cache-Control: no-cache' \
  'https://0nl.onl/app.json' -o build-evidence/public-app.json
curl --fail --location --connect-timeout 10 --max-time 120 \
  -H 'Cache-Control: no-cache' \
  'https://0nl.onl/chengshu.apk' -o build-evidence/public-chengshu.apk
apksigner verify --verbose --print-certs build-evidence/public-chengshu.apk
aapt dump badging build-evidence/public-chengshu.apk
sha256sum "$NEW_APK" build-evidence/public-chengshu.apk
```

检查 HTTP 状态、最终主机、MIME、大小、manifest 字段、APK 版本与 hash。CDN 还在提供旧包时，不得说发布完成。带 cache-bust 参数的诊断成功还不够，用户正常地址也要正确。

从旧 App 内触发“检查更新”，确认显示新版本，下载并请求系统安装；验证拒绝权限、取消安装、断网可恢复。用户点安装的系统确认不由模型替代，不声称已经安装到用户本人手机。

## 11. 发布记录模板

建议每次在 `docs/releases/<versionCode>.md` 留记录；该目录是后续任务新增，不是本轮已有证据。

```text
版本：
versionCode：
App 源提交：
发布文件提交/部署 ID：
Actions run：
构建环境与参数：
APK SHA-256/大小/包名/证书摘要：
旧版来源及版本：
覆盖安装设备与结果：
WeRead/KOReader 版本与结果：
公网下载验证结果：
App 内更新检查结果：
改进：
已知限制：
回退方式：
未验证项：
```

小记录进仓库，大附件进 CI/Release。正文/Cookie/私密 URL 不进公开日志。没有结果的项目保留 NOT_RUN，不能删掉以制造完整。

## 12. 失败处理表

| 失败位置 | 该做什么 | 不能做什么 |
| --- | --- | --- |
| 单测失败 | 定位并修复，重跑相关测试 | 改正确断言、跳 test |
| 依赖/工具链错误 | 固定环境、记录真实错误 | 顺手全量升级包 |
| APK 缺失 | 检查构建步骤退出码与路径 | 把源码 zip 当安装包 |
| 签名不兼容 | 停止 stable 发布，核实旧包与迁移方案 | 重生成钥匙、让用户卸载 |
| 迁移丢数据 | 阻塞发版，保留原始 fixture 复现 | 清数据重新测成功 |
| 上传成功但网站没更新 | 查部署触发/结果、验证正常 URL | 只看 git 提交就宣布发布 |
| manifest 新、APK 旧 | 修复原子发布/缓存，不让坏配对继续 | 让用户反复刷新碰运气 |
| 更新下载不完整 | 临时文件清理与重试，保留旧版 | 请求安装半截 APK |
| 工具权限被拒 | 记录具体未执行动作与现有成果 | 反复换接口规避检查、声称后台会完成 |

## 13. 回退：保护已经升级的用户

尚未对外发布：保留旧 latest，废弃坏候选；不复用同一个发布身份换字节。

已经发布但发现 blocker：先停止推荐坏版本，发布理解当前数据 schema 的修复版本，使用更高 versionCode。必要时将下载入口暂时指回旧版供尚未升级的人使用，但明确已升级用户不能靠普通降级恢复，不称这一步为完整回滚。

数据库迁移应可恢复且保留旧数据，但不能因此承诺旧 APK 能读取新版 schema。正式修复必须考虑已经迁移的设备。不要要求卸载作为默认修复方法。

签名泄露/变更不是普通版本回退问题，按 S03 的独立风险处置与平台兼容证据执行。

## 14. 给用户的最终话术

完成时：

“新版已经发布。打开这里下载，直接覆盖安装即可。旧文章和设置的升级保留已验证。这版新增/修复的是……目前仍不支持的是……”

以上只有真实证据满足时才说。没有替用户本人安装，不说“你的手机已经更新了”。

未完成时：

“还没有可用新版。已经完成的是……卡在……。需要你做的只有……/目前不需要你操作。”

不把几十项技术日志、patch、SHA 当作用户需要处理的下一步。把细节留在仓库，明确交付物和责任归属。
