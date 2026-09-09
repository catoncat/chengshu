# 成书技术设计：可靠保存、可恢复执行、可验证交付

> 本文是拟实施设计。除明确标注“基线已有”的内容，其余均未实现。不要复制本文类名就宣称接入完成。
> 基线、历史边界见 [HANDOFF.md](HANDOFF.md)；任务依赖见 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)。

## 1. 设计原则与增量路线

不重写整个 App，不先迁 Kotlin/Compose，不引入远程任务平台。保留 Java/View、Defuddle、现有 EPUB 内核与真实进程测试。新复杂性只解决已经存在的产品问题。

分两次架构推进：

- **首次可用修复版**：在现有结构上修复失败、验证旧数据和签名升级、补齐发布。这个版本不等待新数据库或后台化完成。
- **后续可靠执行版**：引入单一事务元数据目录、版本化快照、持久任务与提交凭据，再把已保存正文之后的处理交给系统调度。动态页面后台渲染另做实验，不作为已经确定可行的前提。

为什么需要事务元数据目录：当前文章清单和 PendingShares 分属两个存储操作，正文和产物缺少明确版本绑定，RUNNING 只是进程内集合。为支持进程恢复、取消竞争、旧 Worker 防覆盖、分页与垃圾回收，不能持续叠加更多独立 Properties 文件并假设它们一起提交了。

推荐新增 `ChengshuDb extends SQLiteOpenHelper`，只存元数据、状态与文件引用，不把 EPUB/HTML 大对象塞进数据库。选系统 SQLite 是为了贴合当前 Java 工程、避免再引入 ORM 编译链。若实测表明 Room 的迁移/测试收益更大，可用简短决策记录替换，但不得双轨开发两个实现。参考 [SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper)。

## 2. 目标模块与现有代码的关系

以下为拟新增模块，默认先放在现有 Java package 的子目录，最终包名由实现提交确定并更新文档。

| 模块 | 单一责任 | 不应承担 |
| --- | --- | --- |
| `ShareInputParser` | 解析、验证分享输入，保留原始 URL | 抓网页、打开应用 |
| `ArticleRepository` | 文章、快照、产物及迁移的唯一入口 | UI 生命周期 |
| `ChengshuDb` / `JobRepository` | 事务、状态迁移、去重、发布凭据 | 网络、HTML 渲染 |
| `BlobStore` | 有界写入、哈希、同步、原子落文件、校验 | 决定“任务成功” |
| `ConversionCoordinator` | 创建请求、调度、恢复对账 | 直接绑定 Activity View |
| `CaptureController` | 主线程 WebView 捕获及明确结果 | 后台无限保活 |
| `ConversionWorker` | 从持久 checkpoint 继续资源与打包 | 从 Intent 取所有状态、弹页面 |
| `ImageRepository` | 图片获取、缓存、预算、取消 | 无限制下载或 Cookie 外传 |
| `QualityReport` / `QualityEvaluator` | 结构化质量信号与提示 | 声称数学上证明全文完整 |
| `ReaderHandoff` | VIEW/SEND/保存文件及临时授权 | 改文章内容或删除失败文件 |
| `UpdateController` | 检查、下载校验、安装请求恢复 | 自行更换签名或跨 channel 升级 |
| 首页/分享页面 | 观察状态、表达选择、发命令 | 持有唯一任务真相 |

`LocalEpub` 继续作为打包内核，先不大改接口。`LocalArchive` 的不可变文件与校验实现逐步复用到 BlobStore；旧清单作为只读迁移来源。`Library` 先做兼容适配层，逐调用点替换。不能先删除旧类再追着修所有行为。

## 3. 数据模型：必须回答“这份文件来自哪次正文”

### 3.1 身份分开

- `articleId`：用户理解的一篇文章；新数据采用强哈希/稳定 ID，不从标题生成。
- `requestId`：一次用户意图；同一未完成意图可以去重，显式刷新是新意图。
- `snapshotId`：一次捕获到的不可变正文及元数据版本。
- `artifactId`：某个 snapshot 在指定输出设置、打包器版本下生成的具体文件。
- `generation`：任务/文章更新序号，用来阻止旧回调覆盖新意图。
- `receiptId`：已经发布了哪个文件的提交凭据，不是阅读器导入回执。

不能让一个 URL 同时扮演以上所有身份。不能把“修改默认格式”变成重新解释所有旧请求。

### 3.2 建议最小表集合

下面是字段规格，不是已存在的 SQL migration。字段类型、索引、外键、默认值要在 D02 实现并测试。

| 表 | 必需字段与约束 |
| --- | --- |
| `articles` | id 主键、originalUrl、displayUrl、identityVersion、title、createdAt、updatedAt、currentSnapshotId 可空、revision、deletedAt 可空 |
| `url_aliases` | aliasKey + identityVersion 唯一、articleId；保留旧 ID 映射，禁止冲突静默合并 |
| `snapshots` | id 主键、articleId、htmlBlobHash、baseUrl、sourceTitle、byline、capturedAt、extractorVersion、qualityJson；提交后内容不变 |
| `artifacts` | id 主键、articleId、snapshotId 可空、format、optionsHash、packagerVersion、blobHash、byteLength、qualityJson、createdAt、legacySource 标志 |
| `jobs` | id 主键、articleId、requestKey、state、requestedFormat、destinationJson、snapshotId 可空、expectedArticleRevision、generation、ownerToken 可空、attempt、nextAttemptAt、errorCode 可空、createdAt、updatedAt、receiptId 可空 |
| `active_requests` | requestKey 主键、jobId；与 jobs 同事务维护，只限制未完成意图，不阻止未来用户再次分享 |
| `receipts` | id 主键、jobId + generation 唯一、artifactId、publishedAt；与任务成功同事务 |
| `blob_refs` 或等价引用查询 | 记录正文、图片、产物的持久引用，供安全清理；引用不能靠扫描文件名猜 |
| `handoffs` | 可后续增加：artifactId、目的地、请求时间、返回结果；绝不叫 imported，除非目标提供真实回执 |

枚举要由代码集中定义。JSON 只装小型、版本化的设置/质量数据，必须有最大长度和解析错误处理。SQL 参数化，数据库文件位于应用私有目录。

### 3.3 快照与产物不可混淆

刷新先写新 snapshot。只有新产物发布成功才把“当前可读结果”切到新版本；失败保留旧文件、旧标题、旧质量提示。

同一篇旧 EPUB 和新 Markdown 来自不同 snapshot 时，界面/详情能说明版本。不要把新 sourceTitle 写到旧文件上；不要把旧 warning 套在新输出上。

`artifact` 的配方标识至少包括 snapshotId、format、optionsHash、packagerVersion 和采用的资源清单标识。最终文件另有真实 byte hash。ZIP 时间戳可能使文件字节变化：稳定书籍身份与逐字节可重现是两个目标，必须分别测试。

旧文件不知道 snapshot 时，标记 legacy，不捏造正文来源。仍能打开/导出；需要新格式时有原始快照才离线生成，否则明确需要重抓。

## 4. 发布协议：文件先好，状态后成功

### 4.1 正常提交

1. Worker 获取 jobId + generation，读取持久设置，不依赖某个 Activity 字段。
2. 事务领取任务，生成新的 ownerToken。只允许匹配当前 generation 的执行者推进状态。
3. 网络、渲染和打包都在数据库事务外进行；大文件写入 staging，流式计算 hash 和长度，验证格式。
4. BlobStore 将完整文件同步并原子移动到不可变路径；不得覆盖其他版本。能复用已有同 hash 文件时先校验。
5. 开启短数据库事务，重新检查 ownerToken、generation、article revision、未取消/未删除、snapshot 对应关系。
6. 同事务写 artifact 引用、receipt、jobs=SAVED，移除 active_requests；需要切换当前版本时在同事务做。
7. 提交后通知 UI/系统通知。打开阅读器是独立可重试的动作，不属于保存事务。

数据库事务内不联网、不等待图片、不进行整本哈希。校验后的文件由 staging pin/引用协议保护，避免 GC 与发布竞争。

### 4.2 崩溃窗口及恢复规则

| 中断点 | 允许留下什么 | 重启后必须怎么做 |
| --- | --- | --- |
| 接收前 | 没有请求 | 不能显示“已接住” |
| 请求提交后、调度前 | QUEUED job，无 WorkRequest | 启动对账补调度 |
| 正文提交后 | snapshot，未完成 job | 从该 snapshot 继续，不重抓 |
| staging 写一半 | 临时文件，无产物记录 | 不显示成功；稍后清理临时文件 |
| 文件完成、数据库未提交 | 无引用的完整文件 | 可复用或延迟清理；任务仍可重试 |
| 数据库提交后、回调前 | receipt + SAVED | 返回已有结果，不重复生成或切换版本 |
| 交接请求后 | 已保存文件、未知导入结果 | 保留结果，允许再次打开，不说已导入 |

不追求“CPU 只运行一次”的假承诺。允许至少一次执行，但同一有效请求代次只形成一个权威发布结果。重试先查 receipt，不能仅凭 Worker 返回状态决定重跑。

### 4.3 取消、刷新、删除

取消任务在数据库中标记终态并递增 generation/清除 owner；旧 Worker 即使网络回调晚到，发布事务也必须失败。若保存事务先提交，随后用户取消的是交接/后续工作，已经保存的文件仍保留。

删除文章先同事务建立 tombstone、递增 article revision、取消相关未完成 job；再异步清理无引用文件。旧 Worker 的 expected revision 不匹配，不得让删除的文章复活。

显式刷新产生新 revision/request，旧刷新不能在新刷新后覆盖当前结果。单靠 `Future.cancel()` 或 `isStopped()` 检查不足以防止晚到提交；最终事务必须校验。

ownerToken 用不可预测的执行实例标识即可，不是对外认证 token，不应增加安全产品概念。租约时间只帮助恢复；权限来自事务比较与 fencing，而不是“时间到了所以一定没有另一个执行者”。跨重启不要直接比较旧进程的 monotonic clock。

## 5. 迁移协议：先不丢旧文件，再追求整洁

### 5.1 迁移来源

支持两种已知来源：旧 `library/index.json` 与旧文件；当前 `LocalArchive` 的每篇 record.properties 和 hash 文件。先读基线 Library 实际路径，不按猜测创建相似目录。

### 5.2 每篇迁移

1. 读取旧记录、校验 ID/格式/文件路径；路径必须仍在允许的根目录内。
2. 校验可用文件，收集现有标题、格式、时间、快照、warning。
3. 在一次数据库事务导入元数据与对旧不可变文件的引用，写 migration marker。
4. 事务提交前不让新目录把该篇当作迁移完成。中途失败保留原文件，重新进入可幂等导入。
5. 已导入的文章只通过新 repository 写，不同时更新旧清单和新库；未导入的旧文章可只读展示，写前先迁移。
6. tombstone 必须保留；导入扫描不能复活已删除内容。

不要启动就复制所有 EPUB，优先复用合法只读旧文件。未校验或损坏记录逐篇隔离，不因一篇坏记录清空整个列表。

默认不删除迁移来源。后续显式清理需证明所有被删除文件都有可读替代和备份/导出路径。旧版 APK 不一定能理解新数据库；回退发布应采用理解新数据的修复代码并提升 versionCode，不能直接发旧二进制要求用户降级。

### 5.3 迁移必须具备的证明

20 篇多格式旧文章、同标题、中文/emoji 文件名、body.ext、缺失文件、损坏 index、迁移中断、重复启动、删除标记、低空间。比较字节 hash 与可打开性，不只比较列表数量。

## 6. 持久状态机

建议状态：

```text
RECEIVED -> NEEDS_CHOICE 或 QUEUED
QUEUED -> CAPTURING -> SNAPSHOT_READY -> RESOLVING_ASSETS -> PACKAGING -> SAVED
CAPTURING -> NEEDS_USER / WAITING_NETWORK / FAILED_RETRYABLE / FAILED_FINAL
RESOLVING_ASSETS -> WAITING_NETWORK 或 PACKAGING（带质量提醒）
任一未终态 -> CANCELLED
FAILED_RETRYABLE -> QUEUED（受重试预算约束）
SAVED -> 不再转换；交接由独立命令处理
```

`NEEDS_USER` 用于登录、可见渲染或尚未选择目的地等可解释情况，不自动每分钟重新抓。`WAITING_NETWORK` 不计为已完成。严重无正文是失败；有正文但少图可以 SAVED + QualityReport。

错误分类最少包括：INVALID_INPUT、NETWORK_UNAVAILABLE、SOURCE_TIMEOUT、AUTH_REQUIRED、CONTENT_EMPTY、CONTENT_SUSPECT、IMAGE_PARTIAL、STORAGE_FULL、STORAGE_CORRUPT、CONVERSION_FAILED、HANDOFF_UNAVAILABLE、UPDATE_INVALID。最后一个交接错误不把保存任务改成失败。

可重试错误采用有限退避。推荐初始策略为自动最多 3 次；尊重 Retry-After，网络约束等待不高频计次，用户手动重试形成新的尝试窗口。实际数值可基于测试调整，但不得无限重试。

## 7. 系统调度：先把可以确定的部分做好

### 7.1 WorkManager 的边界

采用 WorkManager 调度已持久化任务，输入只传 jobId，不塞 HTML/图片/完整设置。使用唯一任务名防重复；已存在的同一有效请求优先 KEEP，显式刷新使用新的请求身份。不要将所有文章连成一条会被前一个失败传播取消的依赖链。

WorkManager 是执行调度层，不是文章元数据真相；其 SQLite 数据库与产品数据库不能假装是一个事务。通过“先存 job，后 enqueue；启动/恢复时对账”弥合窗口。

Android 官方说明它适合跨应用重启/设备重启的持久工作，但不是所有即时执行任务的通用保证；唯一工作和取消仍要配合业务幂等。[持久任务](https://developer.android.com/develop/background-work/background-tasks/persistent)、[唯一任务/取消](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)。

基线 minSdk 26、compile/targetSdk 34，AGP/Gradle 版本见构建文件。接入时先核对候选 WorkManager 的 minSdk、compileSdk、AGP 要求，固定一个可兼容版本；需要升级工具链就单独任务验证，不顺手全升最新。

### 7.2 第一阶段后台能力

正文快照提交后，图片处理、EPUB 打包、保存与完成通知不再依赖 Activity。用户切走或 Activity 重建，工作仍从数据库观察和恢复。没有快照、且必须可见 WebView 的任务进入 NEEDS_USER，链接仍在。

该阶段诚实承诺是“已保存正文的任务可在系统允许时继续”，不是“任何动态网页都能离线后台抓完”。

### 7.3 动态页面提取实验

现有 `PageExtractor.extract(Activity, ...)` 会把 WebView 挂入视图树，并使用静态 live/ticket。不能从 Worker 后台线程直接调用。实验拆为两条：

- 将渲染控制从具体 Activity 解耦，所有 WebView 创建、JS、销毁仍在正确主线程；验证无可见页面时动态 DOM 是否实际推进、内存是否释放。
- 对无需交互的页面增加受预算限制的静态提取快路径；必须与基准 DOM 语料比较，不能因为字符串更长就替换正文。

实验最多形成一个隔离实现与结果报告。若代表页面和目标系统不能可靠通过，保留“需要在成书中继续读取”的明确产品状态，不使用悬浮窗、隐藏 Activity、无障碍权限、无限前台服务来伪造保证。

### 7.4 前台服务、通知与系统停止

仅在任务确实需要且平台允许时使用前台执行，配置正确类型/权限并响应停止。Android 15 的部分前台服务存在时间预算；Android 16 上长时间 WorkManager 前台工作仍可能耗尽 JobScheduler 配额。不要靠一直保活解决恢复问题。[服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)、[长任务限制](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)。

通知按任务/分组合并，使用明确 PendingIntent，点开定位具体结果。通知权限拒绝不使转换失败。[通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。

区分：Activity 重建、应用退后台、系统杀进程、设备重启、用户“强行停止”。强行停止后不能承诺系统自动复活；用户重开时恢复账本。测试和宣传必须采用相同的边界。

## 8. URL 身份与输入安全

### 8.1 保留三种 URL

保存 originalUrl、实际解析 baseUrl、去重用 identityKey。去重时清理追踪参数不代表请求时也删掉它们。网站的 canonical 标签只是候选别名，不能无条件信任跨域 canonical。

当前 `Library.normalizeUrl()` 会去掉 `ref`、`si`、尾斜杠，并使用解码后的 path。后续必须补差异语料：这些操作可能改变有业务含义的 URL，不能不经测试扩大规范化。

保守策略：scheme/host 大小写和默认端口规范化；保留查询顺序/重复值、编码后的路径、可能路由用的 fragment；仅清理明确追踪参数，站点特例要有测试。新 identityVersion 与旧 ID 别名并存，不静默合并不同旧文章。

### 8.2 分享输入

支持 text/plain、text/html、text/uri-list 中的合法 http(s) URL；解析多个候选时显式选定，不默认拿第一个不相关链接。URL 长度和文本输入有上限；非法 scheme、控制字符、空 host、带认证信息等明确拒绝。短链接重定向保留原始来源与最终来源。

服务端对内网 URL 的限制与用户设备访问本地页面是两个策略，不要混为一个正则。未经单独需求，本计划服务端只允许公开 http(s) 资源。

## 9. 图片与资源流水线

### 9.1 保留现有预算，再用证据调整

基线 Android：单图 4 MiB、嵌入总量 24 MiB、图片阶段约 30 秒、最多 4 个同时请求、额外解码串行与像素限制。它们是现有实现值，不是已证明最优的性能目标。

优先改为流式请求到临时文件，不把所有原图、解码位图、ZIP 缓冲同时留在堆里。总下载预算、嵌入预算、解码像素预算和超时分别计量；Content-Length 不可信时仍按实际读取字节中止。

### 9.2 资源清单

每个图位置记录 sourceRef、解析后的 URL、alt、caption、资源 hash、MIME、大小、状态和 failureCode。相同资源可共用字节，但正文位置不合并。资源清单随 snapshot/构建配方冻结，便于重试同版本。

空地址不发送请求；data URL 验证 MIME 和长度；srcset/lazy 属性按确定规则解析；下载返回 HTML 错误页时不能仅凭 Content-Type 认图片。按字节嗅探再有界解码。SVG/数学先诚实降级，不把任意 XML 丢给不安全解析器。

### 9.3 缓存和取消

已有 image blob 校验可用则复用，失败图片可以单独补抓，不重新抓正文。缓存键不能只含不完整 URL，也不能把不同认证上下文的资源无条件共用。基线无跨账号体系，不额外保存 Cookie。

取消要关闭请求/响应流并停止后续调度，不只从队列移走 Future。必要 Referer 仅按实际来源规则发送；Cookie 只按 WebView/目标域匹配与明确授权策略使用，不把整份 Cookie jar 发给任意重定向主机。

资源 hash 使内容复用不依赖来源在线；但“正文快照存在”本身不等于所有图片离线可用。UI 与测试都必须区分。

## 10. EPUB 与质量报告

保持 EPUB 2 兼容输出直到真实目标阅读器矩阵证明更换版本有收益。现有内核保留并逐项测试 NCX 层级、XML 字符、锚点、脚注、列表起点、table、pre/code、来源和 metadata。

引入 EPUBCheck 作为结构门禁，但它只证明标准符合性，不证明内容没漏或阅读器显示漂亮。[EPUBCheck](https://www.w3.org/publishing/epubcheck/) 支持 EPUB 2 与 EPUB 3。工具版本固定，升级有记录。

建议 QualityReport 字段：schemaVersion、textLength、headingCount、imageExpected、imageEmbedded、missingImageReasons、unsupportedMedia、extractionSignals、severity、ruleVersion。每个 warning 有 code、可选正文定位与可理解摘要。

质量信号包括登录提示/验证码、提取文本明显不足、标题与正文异常、正文混入大量导航、结构化数据与 DOM 分歧、图片未获取。阈值在固定语料上校准。报告必须叫“疑似不完整”，不能叫“100% 完整度认证”。

中英文、多语言、emoji、RTL 混排和长代码测试分开；不要凭中文字符是否存在就解决全部语言 metadata。

## 11. UI 性能与共享文件

数据库、索引迁移、全文件 hash、图片处理、EPUB 打包与大文件复制都不能阻塞 UI 主线程。首页分页读取最小摘要，打开时异步验证所需文件；不能每次刷新列表重算所有 EPUB hash。

先用 StrictMode/trace 定位现有主线程 I/O，再用 repository executor 和生命周期观察改造。ViewModel 可用于 UI 状态，但不替代持久任务。不要用一个静态 Activity/Handler 保活状态。

FileProvider 只暴露被选中的文件，URI 与 artifact identity 绑定。同标题不同文章不能共享同一个会被覆盖的缓存文件。不能交接后立即删缓存，因为目标可能异步读取。导出和引用清理要协调。

ACTION_VIEW、ACTION_SEND、系统创建文档是三个适配器；都使用正确 MIME、content URI、必要 ClipData/临时读权限。文件可读性由接收方测试 app 实际打开流验证。[安全文件分享](https://developer.android.com/training/secure-file-sharing/share-file)。

## 12. 服务端链路也必须保持可信

基线服务端仍有 Defuddle/Readability/JSON/Jina 等提取链、12 张图上限、URL 正则过滤、完整 buffer 读取、短时进程内缓存。Android EPUB 改成本地不代表这些问题消失。

后续任务：请求体/响应体流式上限；每次重定向检查；解析 DNS/IP 并限制非公开目标，处理 IPv6/映射地址；连接使用实际验证的目的地址，避免只验证一次 DNS 后再次解析形成窗口；图片、JSON API 和正文请求共用受控请求器。

不要把关闭全部网络请求当修复。用本地可控服务与 DNS/transport 注入测试拒绝路径和合法公网路径，禁止对真实内网做探测。部署网络出口限制可作为纵深防护，但不能假装正则已证明完整 SSRF 防护。

服务端缓存键必须含完整内容摘要和所有影响输出的元数据。基线 `/export` 的文本 key 只用前 120 字、HTML key 缺少 title/byline，存在不同输入错误复用风险；先写相同前缀不同正文/同 HTML 不同 metadata 测试。无授权缓存不得跨用户认证上下文共享。

第三方 Jina 兜底改为明确可选策略，不悄悄改变隐私边界。错误响应使用稳定 code 与用户消息，原始详情只留经过脱敏的开发诊断。

## 13. 更新与发布身份

更新 manifest 建议向后兼容保留 versionCode、versionName、apk，新增可选 sha256、size、sourceCommit、channel、minSdk。先让老客户端仍能读，再逐步让新客户端强校验。hash 只能验证与清单一致，不替代 HTTPS、签名和可信发布源。

下载 APK 要有体积上限、临时文件、超时、取消、hash/包名/versionCode/签名检查，成功后才请求安装。系统安装确认仍由用户完成，不能承诺静默安装。

签名风险独立处理：仓库内的 debug keystore 不是可长期依赖的私密签名材料。先验证当前官方旧 APK 的真实证书，再评估受保护的正式签名/轮换与旧系统兼容。不要把删掉仓库文件当成撤销泄露，也不要无迁移方案直接换钥匙。见 [签名指南](https://developer.android.com/studio/publish/app-signing) 和 [apksigner](https://developer.android.com/tools/apksigner)。

发布版本必须严格单调，源提交与 APK 对应。机器人向仓库推 APK 后，不能假设另一个 push workflow 自动触发；`GITHUB_TOKEN` 事件存在防递归规则，网站是否部署也要独立核实。[GitHub workflow triggers](https://docs.github.com/actions/using-workflows/triggering-a-workflow)。

具体发布操作与回退见 [RELEASE_RUNBOOK.md](RELEASE_RUNBOOK.md)。

## 14. 清理、导出与长期运行

垃圾回收只删除没有任何文章/snapshot/artifact/job pin 引用的文件；采用标记/清扫、宽限期和发布互斥协议。损坏数据库或引用查询失败时停止清理，不“为了腾空间”猜哪些可删。

先提供清理预览和 dry-run。staging 可按生命周期清理，已保存文章不是缓存。迁移来源默认不删。连续生成大图文章、重复刷新、重试和升级后测实际占用，不能只按输出 ZIP 大小估算内存。

离线导出最初只需文件；完整备份包/恢复在核心闭合后作为独立能力。备份包含 schemaVersion、清单和 checksum，导入严格防路径穿越、ZIP bomb、重名覆盖和部分导入损坏，不自创加密协议。

## 15. 关键决策记录

| ID | 默认决定 | 变更需要的证据 |
| --- | --- | --- |
| ADR-01 | 先修复并交付现有版本，不等待全新架构 | 发现明确会造成数据损坏/错误交付的 blocker |
| ADR-02 | Java/View 增量改进，保留 EPUB 内核 | 有对比证明框架迁移直接降低风险/成本 |
| ADR-03 | 新持久协调用单一事务元数据目录 + 不可变文件 | 更简单方案也能证明跨阶段发布、取消、删除与恢复一致 |
| ADR-04 | 后台化先处理已有 snapshot，动态渲染单独实验 | 真实系统/动态语料生命周期证据 |
| ADR-05 | 业务状态是权威，WorkManager 只负责调度 | 不允许变更成多个独立“成功”真相 |
| ADR-06 | 正常流程不强制预览，异常按严重性解释 | 用户测试与错误漏报数据 |
| ADR-07 | 设备 EPUB 默认不上传正文 | 新增上传必须单独取得明确产品授权 |
| ADR-08 | 正式签名变更单独评审，不隐式破坏更新 | 真实旧包、新包、各受支持系统的升级验证 |

修改决策时记录：日期、原因、被替代方案、受影响任务、迁移与验证。没有性能/兼容证据时，不使用“理论上更先进”作为理由。

## 13. 2026-09-09 决策记录

- 元数据目录用文件 JSON + FileLock（`Store`），不在 1.13 接入 SQLiteOpenHelper。原因：现有 JVM 真实进程测试无法打开 Android SQLite；原子文件与 `LocalArchive` 同一套崩溃窗口。
- 已保存正文之后的打包由 `ConversionCoordinator` 单线程执行器 + 启动对账完成，不引入 WorkManager。原因：当前 AGP/JVM 单测不能初始化 WorkManager ContentProvider。进程被系统杀死后的自动唤醒仍未承诺。
- 动态网页后台 WebView（B04）结论：不可行，保持 `NEEDS_USER`。
- 正式签名仍用仓库 stable keystore。S03 不在本版本更换钥匙。

