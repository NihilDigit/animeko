# PikPak 引擎设计：作为 TorrentEngine 接入

本文承接 [pikpak-cache-analysis.md](pikpak-cache-analysis.md) 与 [pikpak-optimization-plan.md](pikpak-optimization-plan.md)，给出 2026-09-05 定稿的目标结构与分工。前两文记录的缺陷与实测数据不再重复，本文只引用结论。

animeko 行号对应 `main` 在 `748b1690d`；pikpak-kotlin 以 0.5.1 为准（`VariantPreference`、按 media_id 锁定的 `rangeReader`、`remoteSize`）。

## 一、结论先行

PikPak 不再作为「解析出直链交给播放器」的旁路，而是实现 `torrent/api` 的三个接口，成为一个 `TorrentEngine`：

```
TorrentDownloader  ← PikPakTorrentDownloader   磁链 → 云端离线任务 → 文件清单
TorrentSession     ← PikPakSession             一个磁链一个会话，持有文件条目
TorrentFileEntry   ← PikPakFileEntry           piece 列表 + 分片拉取器 + 磁盘文件
```

这样做的收益来自复用：

- 播放：`TorrentMediaResolver` → `TorrentMediaDataProvider` → `TorrentMediaData` → `entry.createInput()` → `TorrentInput`（现有 `SeekableInput` 实现，按 piece 等待、从磁盘读）。播放器两端（ExoPlayer、mpv）都只认 `SeekableInput`，一行不改。
- 缓存：`TorrentMediaCacheEngine` 与 `TorrentMediaCacheStorage` 直接复用，包括完成后不唤醒引擎的 `LocalFileMediaCache` 恢复路径、`TorrentCacheInfoEntity` 的落盘身份、`deleteUnusedCaches` 的按 mediaId 聚合白名单。
- 播放与缓存共用同一份磁盘文件和 piece 状态：边播边缓存是 torrent 链路本来就有的行为，PikPak 免费得到。前文规划的回环代理、分片调度器与「分片存储即渐进缓存」三项，都由这条路径吸收，不再单独建设。

回退链从三级变为两级：内存直链由 `RangeReader` 内部持有并按 fileId 续签；云端结构化索引简化为「bucket 存在即命中」；magnet 重提由 `startDownload` 承担。

## 二、前提事实

来自 2026-09-04 的实测与 SDK 0.5.0/0.5.1，实现时不必重测：

- 签名直链 HTTP/1.1，每条 URL 8 个并发连接上限，单连接 0.4–0.8 MB/s，8 连接 2.4–4.4 MB/s。1080p 番剧码率约 0.47 MB/s。
- 直链 24 小时过期，过期后 403 空 body。`RangeReader(fileId, mediaId)` 内部续签，调用方只给 fileId。
- 转码变体（1080P/720P/480P）是 PikPak 存好的 MPEG-TS，字节跨续签稳定，可按 fileId + media_id 缓存；总长度只能靠一次 Range 探测取得（`remoteSize`）。三档转码都没有内嵌字幕，音频 49 kbps。
- `getTask(id)` 单任务轮询已可用，热门资源第一次轮询即 COMPLETE。
- PikPak 离线产物：单文件种子落为文件，多文件种子落为文件夹，文件夹内扁平。

## 三、模块结构

```
torrent/pikpak                       PikPak 引擎（KMP，jvm/android/ios）
  PikPakTorrentDownloader            TorrentDownloader
  PikPakSession                      TorrentSession
  PikPakFileEntry                    AbstractTorrentFileEntry
  PieceFetcher                       piece 拉取调度（RangeReader 之上）
  PikPakDriveIndex                   云端 bucket 查找、任务提交与轮询、变体解析
  PikPakResumeData                   piece 位图与元数据落盘
  RandomAccessFile (expect)          按偏移写读文件，jvm/native 各一份 actual

app/shared/app-data
  domain/torrent/engines/PikPakEngine     TorrentEngine，进程内，无 Android service
  domain/torrent/TorrentManager           engines 列表加入 PikPakEngine
  domain/media/cache/...                  引擎 key 与迁移
```

`torrent/pikpak` 现有的 `OfflineDownloadEngine`、`PikPakOfflineDownloadEngine`、`PikPakSessionStoreAdapter` 中，session 适配器保留，其余在第七节退役。

## 四、引擎内部设计

### 4.1 EncodedTorrentInfo 与 fetchTorrent

`fetchTorrent(uri)` 不联网，只把 `uri`（磁链或 `.torrent` URL）序列化为 JSON 装进 `EncodedTorrentInfo`，与 anitorrent 处理磁链的方式相同。云端交互全部推迟到 `startDownload`，因为 `TorrentMediaCacheEngine.createCache` 把 `EncodedTorrentInfo` 原样存进 `torrent_cache` 表，这里放的内容决定了重启后 `restore` 能拿到什么。存 uri 足够：sourceKey 由 uri 派生，bucket 由 sourceKey 定位。

### 4.2 startDownload 与云端索引

`startDownload(data)` 按 `contentHashCode` 去重返回已有会话（同 anitorrent）。新会话的建立顺序：

1. `sourceKey = sourceKeyFor(uri)`，沿用现有的 infohash 规范化。
2. 定位 bucket：`Animeko-Playing/<sourceKey>`，slot 与 bucket 的 folderId 在进程内 memoize，404 时作废重查。命中则列 bucket 得到文件清单；未命中则建 bucket、`createUrlFile`、`getTask` 轮询到 COMPLETE。首次轮询不延迟。
3. 文件清单：单文件种子即该文件；文件夹则列其子项。每个条目记录 `fileId`、名字、原始大小。
4. 变体：读全局画质设置，对**视频**条目执行 `resolveVariant(fileId, preference)`；偏好为原画时不发请求，长度取自列表；偏好为转码时每个视频条目一次 `getFile` 加一次 `remoteSize` 探测，结果写入 4.6 的元数据文件，之后不再探测。整季包首次打开的额外代价是每个视频两次请求，接受。
5. 构造 `PikPakSession`，`getFiles()` 返回全部条目。

驱逐策略保留 `slotQueueLength`，但排除本地有未完成缓存的 bucket：`PikPakTorrentDownloader.listSaves()` 能看到本地有哪些 sourceKey 目录，驱逐候选与之求差。这回答了前文待决问题 5，信息不需要跨层传递，引擎自己两边都看得到。

`startDownload` 内的写操作（驱逐、建 bucket、提交任务）按 sourceKey 互斥，只读命中路径不串行。

### 4.3 piece 模型

- piece 大小固定 2 MiB，最后一个 piece 取余数。整季包每个文件独立编号，`initialPieceIndex` 由文件在清单中的顺序累加，保证一个会话内 pieceIndex 全局唯一。`PieceList.create(totalSize, pieceSize, initialDataOffset = 0, initialPieceIndex)` 直接可用。
- `dataStartOffset` 从 0 起，即 piece 偏移就是文件内偏移，`TorrentInput` 的 `logicalStartOffset = 0`。
- 每个 `PikPakFileEntry` 一个 `TorrentDownloadController`，参数沿用 anitorrent：窗口 8 MiB / pieceSize，头部 2 MiB，尾部 0.5 MiB。`PiecePriorities.downloadOnly(high, normal)` 的实现是把两个列表交给 `PieceFetcher` 重排队列。

2 MiB 的理由：单连接 0.4 MB/s 下一片 5 秒，8 片并行时首屏头部 2 MiB 在 1–2 秒内到齐；再小则请求数翻倍，PikPak 限流 5 req/s 只约束 API，不约束 CDN，但每个 range 请求有约 200 ms 首字节延迟，太小的片让延迟占比过高。

### 4.4 PieceFetcher

每个 entry 一个拉取器，共享该文件的 `RangeReader(fileId, mediaId)`。

- 并发数取 `PikPakConfig.downloadConcurrency`，上限 8。播放与缓存是同一个 entry 的两个 handle，共用一个拉取器，因此并发之和天然不超过 8，前文担心的 503 冲突不存在。
- 队列由 `downloadOnly(high, normal)` 决定：high 全部优先，normal 按窗口顺序。worker 取队首 READY 的 piece，CAS 置为 DOWNLOADING，`reader.read(offset, size, priority)` 写入磁盘对应偏移，校验字节数后置 FINISHED，调用 `controller.onPieceDownloaded`。
- 取消：`downloadOnly` 换掉队列时，正在拉取但已不在新队列里的 piece 让它完成，不取消。取消一个 2 MiB 的读会浪费已到的字节，而 seek 后旧窗口的片也终会用到。例外是 handle 全部关闭时取消全部 worker，释放连接。
- 失败：`RangeReader` 已处理 503、续签、截断续传；拉取器只处理它抛出的最终异常，把 piece 退回 READY 并计数，同一 piece 连续失败 3 次则 entry 进入错误态，`fileStats` 停止，`TorrentInput` 的等待由上层超时处理。
- 优先级：`FilePriority.HIGH`（播放）的 handle 存在时，该 entry 的读带更高 `priority` 值进 `RangeReader`，同一 reader 上缓存任务的读排在后面。

`seekTo` 触发点是 `createInput` 的 `onWait` 回调，与 anitorrent 一致：`TorrentInput` 等某个 piece 时调用 `controller.seekTo(pieceIndex)`。

### 4.5 磁盘布局

```
<saveDir>/pikpak/<sourceKey>/
    <pathInTorrent>            视频文件，按偏移写入，创建时预分配到目标长度
    <pathInTorrent>.pieces     piece 位图，FINISHED 置位；每完成一片追加写
    meta.json                  文件清单、fileId、mediaId、变体标签、长度、探测结果
```

`getSaveDirForTorrent(data)` 返回 `<saveDir>/pikpak/<sourceKey>`，`listSaves()` 列该目录。`TorrentMediaCacheEngine.createCache` 把这个路径相对化存进 `relativeDir`，`deleteUnusedCaches` 用它做白名单，这条契约不变。

变体是磁盘身份的一部分：meta.json 的 `mediaId` 与位图对应同一份字节。用户改画质设置后，已有目录按 meta.json 记录的变体继续，新会话按新设置。同一 sourceKey 不同变体不并存，切换意味着删掉重来，由设置页文案说明。

预分配用 `RandomAccessFile.setLength`，让 `resolveFileMaybeEmptyOrNull` 的「文件存在且非零长」判断在第一片写入前就成立，`TorrentInput` 可以立即打开文件并按 piece 等待。

### 4.6 resume data 与恢复

位图文件即 resume data。`startDownload` 读位图重建 `PieceState`，meta.json 提供长度与 fileId，因此**恢复未完成缓存不需要访问云端**，只有拉取新 piece 时才需要直链。这与 anitorrent 不同：anitorrent 恢复时要重新连 DHT，PikPak 恢复时本地即可完整重建状态。

完成态由 `TorrentMediaCacheEngine.subscribeStats` 写入 `TorrentCacheInfoEntity.completed`，重启后走 `LocalFileMediaCache`，与 anitorrent 完全一致。分享率对 PikPak 无意义，`shareRatioLimitFlow` 传常量 0。

### 4.7 统计

`fileStats.downloadedBytes` 为 FINISHED piece 大小之和；`sessionStats` 的下载速度由 `RangeReader.stats.bytesRead` 做速率平均，上传恒 0。`TorrentDownloader.totalStats` 汇总所有会话。

### 4.8 跨平台

- `TorrentInput` 的 nativeMain 实现目前是 TODO 桩，iOS 没有可用的 `SeekableInput` 读取器。本方案在 iOS 落地的前提是补上它：POSIX `fopen/fseek/fread` 直译 jvm 版即可，逻辑（按 piece 等待、跨相邻 FINISHED 片合并读）在 `TorrentInput.jvm.kt` 已经写好。
- 引擎自身的文件写入同样需要一个 `expect class RandomAccessFile`，jvm 用 `java.io.RandomAccessFile`，native 用 POSIX。放在 `torrent/api`，与 `TorrentInput` 同侧。
- Android 上 `PikPakEngine` 进程内运行，不经 `AniTorrentService`。`TorrentEngineAccess` 对它传 `AlwaysUseTorrentEngineAccess`。现版本在 Android 上启用 PikPak 会同时出现「BT 引擎已启用」通知，说明某条路径向 `TorrentServiceConnectionManager` 请求了 service；接入时先定位这条路径，新引擎的任何操作都不得触发 service 启动，验收项之一。后台播放的行为随之与前文分析一致：进程被冻结时 piece 请求停止，恢复后从位图继续，直链过期由 `RangeReader` 续签。

### 4.9 直链自愈

直链只存在于 `RangeReader` 内存中，上层握 fileId。`urlProvider` 按请求类型分层回退，每层成功后把新 fileId 写回 meta.json：

| 请求 | 动作 | 代价 |
|---|---|---|
| `Initial` / `Expired` | `getFile(fileId)` | 一次请求 |
| `Rejected(404)` | 按 sourceKey 找 bucket，按 `pathInTorrent` 取新 fileId | bucket 仍在时两次请求 |
| bucket 不存在 | 重提 magnet，`getTask` 轮询，取新 fileId | 已缓存资源秒完成 |

三处让路径更短：handle 首次 `resume` 时调 `reader.prewarm()`，把首次 `getFile` 从第一帧的关键路径上挪走；`urlProvider` 记录 `link.expiresAt`，临近过期时在下一次读之前主动换链，而不是等 403 再换；`Rejected` 层用 memoize 的 bucketId 直接列 bucket，省掉从 slot 根查起的一次请求。整季包在 `Rejected` 层必须先列再重提，否则重提会在 bucket 里产生第二份整包。

成立的前提是本地身份 `(sourceKey, pathInTorrent)` 与云端 fileId 解耦：PikPak 按 GCID 去重，重提得到的文件与被删的文件逐字节相同，本地已有 piece 不作废；转码变体的字节同样跨文件稳定。驱逐因此无害，播放中的 reader 在下一次读时自愈，用户可感知的只是一次缓冲。

## 五、缓存层接入

### 5.1 引擎注册

`TorrentEngineType` 增加 `PikPak("pikpak")`。`DefaultTorrentManager.engines` 返回 `[anitorrent, pikpak]`，`PikPakEngine` 的 `isSupported` 跟随 `PikPakConfig.enabled` 且凭据完整。`CommonKoinModule` 的循环为每个引擎建一对 `TorrentMediaCacheStorage`/`TorrentMediaCacheEngine`，PikPak 自动被覆盖，`engineKey = MediaCacheEngineKey("pikpak")`。

多个 storage 共用 `LOCAL_FS_MEDIA_SOURCE_ID` 是既有状态：今天 `TorrentMediaCacheStorage` 与 `HttpMediaCacheStorage` 已经如此。`DefaultTorrentManager` 的注释称同 id 会只剩一个 storage 被查询，与 Http storage 并存的现状矛盾，实现时在 `MediaFetcher` 里验证同 id 多实例是否都参与 fetch；若确实只取一个，修 `domain/media/fetch` 的实例注册，不改 id。缓存 UI 把本地缓存视为一个数据源，id 不动。

### 5.2 引擎选择

`EpisodeCacheRequester.preferredCacheEngineKey` 的分支改为：PikPak 引擎启用时 BT 类 media 优先 `pikpak`，否则 `anitorrent`。播放侧 `TorrentMediaResolver` 的顺序同理：PikPak 的 resolver 排在 anitorrent 之前，`supports` 跟随 `isSupported`。`OfflineDownloadMediaResolver` 的回退语义由 `MediaResolver.from(list)` 的顺序天然提供。

### 5.3 存量迁移

- `MediaCacheSave.engine == WebM3u && origin.kind == BitTorrent` 的条目：这些是旧 HTTP 链路的 PikPak 缓存，整季包的已损坏（前文 3.1），单集的文件完整。迁移一次性执行：文件完整且 `http_cache_download_state` 有行的，复制到新布局并写位图全 1，`engine` 改为 `pikpak`；其余标记重下。
- `DownloadId` 派生问题随旧链路退役消失，新链路的文件身份是 `(sourceKey, pathInTorrent)`，与 anitorrent 同构。

## 六、播放层

`TorrentMediaResolver(PikPakEngine, AlwaysUseTorrentEngineAccess)` 加入 resolver 链即可。`selectVideoFileEntry` 对 `getFiles()` 返回的条目名做选择，与 anitorrent 同一函数。

外挂字幕、弹幕等 `extraFiles` 路径不变。变体为转码时 `TorrentMediaData` 递给播放器的是 MPEG-TS 流，mpv 与 ExoPlayer 都能探测容器，不需要额外提示 MIME。

## 七、退役

按依赖顺序移除，每步可独立编译：

1. `OfflineDownloadMediaResolver` 及其在三端 DI 的注册；`OfflineDownloadEngine`、`ResolvedMedia`、`PikPakOfflineDownloadEngine`、`PikPakConnectionTest`。设置页的连接测试改调 `PikPakEngine.testConnection()`。
2. `HttpMediaCacheEngine` 中的 BT 分支：`supports` 不再接受磁链，`pikpakConfig` 参数、并发数与 `Accept` 头的特判删除。`HttpMediaCacheEngine` 回到只服务 WEB 源。
3. `EpisodeCacheRequester.preferredCacheEngineKey` 中指向 `WebM3u` 的分支。
4. 前文规划中的回环代理、分片调度器、云端标记文件夹三项不再实施。

## 八、设置项

`PikPakConfig` 增加 `variant: String`，取值 `"original"`、`"1080P"`、`"720P"`、`"480P"`，默认原画。设置页新增单选，文案说明转码画质不含内嵌字幕且音频为低码率。`downloadConcurrency` 语义改为「每个文件的并发连接数」，范围 1–8，默认 4。

## 九、分工

三个并行工作流，文件归属互不重叠；集成编译由主会话收口时统一跑。

| 工作流 | 归属 | 内容 |
|---|---|---|
| A 引擎 | `torrent/pikpak/**`，`torrent/api` 新增 `RandomAccessFile` expect/actual 与 `TorrentInput.native.kt` | 第四节全部 |
| B 接入 | `app/shared/app-data/.../domain/torrent/**`、`domain/media/cache/**`、`domain/media/resolver/TorrentMediaResolver.kt`、`CommonKoinModule.kt`、`PikPakConfig.kt`、设置页 | 第五、六、八节 |
| C 退役 | `HttpMediaCacheEngine.kt`、`OfflineDownloadMediaResolver.kt` 及测试、三端 DI 中的旧注册、`EpisodeCacheRequester.kt` | 第七节 |

A 与 B 之间的契约是 `torrent/api` 的接口加 `PikPakEngine` 的构造参数（`PikPakConfig` flow、`SessionStore`、`ScopedHttpClient`、saveDir）。B 先以桩实现接线，A 完成后替换。C 依赖 B 的 resolver 与 engineKey 替换先落地，否则删旧路径会让 PikPak 用户暂时无法播放。

`domain/media/selector` 不在任何工作流内。

三个工作流落在同一分支，各自独立 commit，合并为一个 draft PR，最终 squash。

## 十、执行状态与约定（2026-09-05 00:40）

本节是跨会话的工作记录，完成后删除。

**已完成**

- pikpak-kotlin 0.5.1 已打 tag 并推送，发布流水线首轮在 iOS 模拟器上因 `RateLimiterTest` 的计时断言抖动失败，重跑后测试全绿，Maven Central 发布 job 进行中；确认方式 `curl -sI https://repo1.maven.org/maven2/io/github/nihildigit/pikpak-kotlin-jvm/0.5.1/pikpak-kotlin-jvm-0.5.1.pom`。mavenLocal 发布因本机无签名配置不可用，不再尝试。
- Animeko 本地分支 `pikpak-torrent-engine`（自 `main` 748b1690d），首个提交 60274b79f：依赖升到 0.5.1、三处 API 适配、测试任务读取 `local.properties` 的 `pikpak-username` / `pikpak-password` / `pikpak-magnet`、本文档。
- 0.5.1 已在 Maven Central 可解析。桌面 live 冒烟 `:torrent:pikpak:desktopTest --tests '*PikPakLiveSmokeTest*'` 在 0.5.1 上失败：首次 drive 请求 HTTP 400，包体 `error_code=9 captcha_invalid`，detail「no client info found」。根因两层：Animeko 注入的 client 装了 Ktor `UserAgent` 插件，它在管线里追加而非覆盖，请求带两个 User-Agent 值，服务端认不出客户端而拒绝 captcha；SDK 0.5.0 起非 2xx 不读包体直接抛「HTTP 400」，0.4.x 会解析包体、刷新 captcha 重试成功。
- SDK 侧已修（pikpak-kotlin main ef1df58，未推送）：非 2xx 解析错误包，异常带真实 `error_code` 与 `httpStatus`；`request()` 对 captcha 异常刷新重试；refresh token 失效走 signin 回退同样覆盖 4xx。版本 0.5.2-SNAPSHOT 发布到 mavenLocal（签名用 init script 关闭，见 scratchpad `nosign.init.gradle.kts`），Animeko 暂依赖该版本，Ani 稳定后发 0.5.2 正式版并回改依赖。用户明示：SDK 不合 Ani 用法的部分可以改，联调期间依赖本地副本。
- Animeko 侧已做（B，d46827e96）：`ScopedHttpClientUserAgent.NONE` 加 `createDefaultHttpClient(installBrowserUserAgent)`，SDK 拿到的 client 不装 `UserAgent` 插件，代理配置照走。
- A 完成（分支 `pikpak-engine-a`，已合并进 `pikpak-torrent-engine`，合并提交 0ca9793f5）：引擎、`RandomAccessFile` expect/actual、`TorrentInput` native 实现、单元测试与 live 测试。piece 由 2 MiB 改为 512 KiB（5a46205e0），首 4 MiB 18.5 s → 7.2 s，60% 处 1 MiB 9.3 s → 2.9 s，本机单连接约 0.22 MB/s。piece 大小记录在 meta.json 并在恢复时读取。iOS 任务在本机不存在（`ani.enable.ios` 未设），native 文件未经编译器验证。
- B 完成（8 个提交，133100e0a 是桩，合并时已被 A 的实现覆盖）：`TorrentEngineType.PikPak`、`PikPakEngine`、engines 列表、缓存 storage 与 engine key、resolver 链首位、设置项 `variant`、连接测试改走引擎、WebM3u→pikpak 迁移。`MediaFetcher` 同 id 多实例确认都参与 fetch，`DefaultTorrentManager` 里的旧注释已改，加了 `MediaFetcherSameSourceIdTest`。Android 通知根因：`TorrentMediaCacheStorage` 每次启动调 `deleteUnusedCaches`，其中 `getDownloader()` 走 `withServiceRequest`，非空请求队列即触发 `AniTorrentService` 启动；修法是 `TorrentEngine.saveDir` 不存在或引擎不可用时直接返回；`CacheOnBtPlayExtension` 不再写死 anitorrent storage。
- 集成编译通过：torrent-api/pikpak（desktop+android）、utils/ktor-client、app-data（desktop+android）、application desktop、app:desktop、app:android `compileDefaultDebugKotlin`。`:torrent:pikpak:desktopTest` 11 个套件全绿，含两个 live 测试。
- 迁移格式问题已修（A，345e1e349，合并提交 ba5909702）：`PikPakTorrentDownloader.importCompletedFile(uri, source, pathInTorrent)` 在引擎内写位图与 meta.json（`fileId` 为空），`VariantReader` 对空 fileId 直接走按 bucket 与路径重定位，已完成的条目 `resume` 不再 prewarm，因此导入的缓存播放时零请求。迁移只调这个 API。遗留问题：迁移把 `relativeOutputPath` 的最后一段当 `pathInTorrent`，同一磁链下同名不同目录的两个旧缓存会撞名，旧链路的整季包本就损坏，单集不受影响。
- C 完成（9021640d7、d33c179f0、3ad060130）：删除 `OfflineDownloadMediaResolver`、`OfflineDownloadEngine`、`ResolvedMedia`、`PikPakOfflineDownloadEngine`、`PikPakConnectionTest` 及其测试；`PikPakCredentials` 独立成文件；live 测试磁链移到 `LiveTestMagnet.kt`；`HttpMediaCacheEngine.supports` 拒绝磁链与 `.torrent`；`preferredCacheEngineKey` 由 pikpak 直接落到 anitorrent。`AniIos.kt` 改动未经编译器验证。
- 模拟器（`A17_tablet_root`，emulator-5678，包名 `me.him188.ani.debug2`）：启动、进设置、开启 PikPak 全程无 `AniTorrentService`、无通知；PikPak 分组显示用户名、密码、缓存槽位数、播放画质、下载并发数、测试连接。截图工具在该模拟器上常抓到半帧，用 `uiautomator dump` 到 `/data/local/tmp` 作为界面事实来源（`/sdcard` 路径会被 Git Bash 改写，加 `MSYS_NO_PATHCONV=1`）。
- 桌面端实测（2026-09-05，13 集整季包，Windows，`:app:desktop:run`）：冷启动从选中到条目就绪 15.6 s，其中任务轮询 5 s（间隔 2.4 s，首轮已 90%）、列目录后 6.3 s 无请求无日志的空白；E03 首播条目就绪后 mpv 读前 11.6 MB 用了 56 s，同会话切 E07 读前 12.9 MB 只用 10 s，mkv 内嵌字体附件让头部远超 2 MiB；重进 E03 秒开。模拟器因宿主机 DNS 是代理的 fake-IP 地址无法解析域名，需 `-dns-server 8.8.8.8,1.1.1.1` 启动，未继续。
- A 第三轮完成（合并提交 44bf6f687）：6.3 s 空白是启动时给全部 13 个文件预分配（实测 13 个 700 MB 文件 `SetEndOfFile` 共 4 s），改为条目首次打开时分配；NTFS 补零假设被实测否定（越过有效数据长度 717 MB 写 512 KiB 为 0 ms）；56 s 未定位，CDN 读走 SDK 自己的 client 不进应用日志，已加 piece 级 DEBUG 日志与每 16 片一条速率 INFO，`createSession` 分阶段计时，下次桌面复测定位，首要嫌疑是 mpv 头尾探测触发 `seekTo` 反复整窗重排；`getTask` 轮询 0/1/1/2 s 再进入常规间隔；默认连接数 4 → 8，单连接实测 0.32 MB/s，8 连接持续 2.4–2.7 MB/s，首 4 MiB 7.2 s → 4.1 s；`filesPresent` 接受短于记录长度的文件（不预分配后中断会话的常态）。整包预热：只预热 app 通过 `PackWarmUp.setWarmUpTargets` 指定的条目（无启发式，无目标不预热），同时一个文件、`PieceFetcher.activeLimit` 限 2 连接、`concurrency == 1` 时不预热、播放条目有活时暂停领新片（500 ms 轮询窗口）、`warmUpSiblings` 每次决策都读。
- B2 完成（f699013a9、73ce1b080、e1bae9706、531359bc5）：`MediaCacheStorageSource` 对整季包缓存给出派生命中（`enableSiblingEpisodeHits`，只对 PikPak storage 开）；`CacheOnBtPlayExtension` 对派生命中也建真实记录；选择器与统计页按 `Media.originalMediaSourceId` 显示原数据源名，两个 torrent storage 的 `displayName` 都是 `LocalTorrent`；`PikPakEngine` 按计费网络置 `warmUpSiblings`；新增 `WarmUpPackEpisodesExtension`，用播放同一套 `selectVideoFileEntry` 对番剧全部剧集算出文件，下一集优先回绕，交给会话的 `PackWarmUp`；`TorrentMediaData` 新增 `session` 以便扩展拿到会话。用户决定：预热目标完全信任 Ani 侧匹配算法，引擎不再自己猜。
- 记录不落码（selector 归他人）：同一整季包切集时选择器每次重新取流，可能抖动；理想是首次选中整季包后同一番剧后续各集直接沿用，B2 的派生命中只解决「有缓存」这一种情况。
- 桌面复测（2026-09-05 13:46 起）发现并修复：整季包 "01-12+SPx1" 对 SP01、SP02 给出派生命中，因为判断用了 `range.contains(episodeEp)`，特别篇的 ep 与正片同一编号空间；SP02 无文件时 `selectVideoFileEntry` 回退到第一个视频，放成 E01。修法（B2，02ec81fa7）：建缓存或恢复时把会话文件清单存进 `TorrentCacheInfoEntity.filesInTorrent`（Room 23，自动迁移，旧记录下次恢复补上，null 表示未知不给命中）；新增严格版 `selectVideoFileEntryExact`（不回退、特别篇不与正片同号匹配、特别篇忽略 ep），派生命中只在严格匹配到文件时给出，并把路径带在 `MediaCacheProperties.pathInTorrent` 上由 `TorrentMediaDataProvider` 直接打开；预热扩展同样用严格版。`EpisodeRange.contains` 把特别篇当同号正片这一点是既有行为，只在严格版里绕开。
- 复测中确认正常：集内播放进度本地存储（`PlaybackHistoryDao`）跨进程恢复；详情页高亮停在第一集是未登录的预期行为（「看过」由 Bangumi 收藏接口写入）；预热按下一集优先滚动更新，S01E03 已预热完成。
- 子 piece 并行拆分（a1c43434d）：把高优先级 piece 拆成 8 段分给多个连接，目的是让首 piece 用满带宽。实测负结果：激进版（任何 READY piece 都拆）整体慢 3.5 倍，range 请求数翻倍而 CDN 单连接吞吐不变；保留的保守版只在队列里没有其他 READY piece 时拆，冷启动阶段队列总是满的，因此对首帧无效，只对 seek 到未缓存位置有帮助。暂留，是否回退待定。
- 脏缓存现象：复测中 P10 与 P11 解析到同一个视频、EP2 打开错误文件，根因是 `datastore/mediaCacheMetadataV2` 里 22 条实验期间用旧匹配逻辑建立的记录，不是代码问题。用户确认为实验数据，已清空。
- 播放模型改动（B2，3038b2f2b、159bb113b；修订于 34e63550c）：第一版让 `CacheOnBtPlayExtension` 只在 `reseedingEnabled` 开启时建记录，结果默认状态下本地一条记录都没有，重进本集与整季包切集都回到网络选择器（约 16 s）。用户判定 Reseeding 管得太宽，它只该回答两个问题：自动记录要不要下满、下满的要不要做种。修订后：播放一律建 `autoCached` 记录；下载策略挂在记录上，显式记录永远下满，自动记录由 `TorrentMediaCacheEngine.fullDownloadForAutoCaches`（PikPak 取 Reseeding 开关，anitorrent 恒 true）决定，关时不给句柄提优先级、只跟随播放，开关翻转时未完成的自动记录当场升级或退回；pin 只给下满的记录，跟随播放的字节留在预算 LRU 里，meta.json 不随字节淘汰所以本地命中不受影响。跟随播放用「不 resume 句柄」而不是 `resume(IGNORE)`，因为 `PikPakFileEntry` 的 resumeImpl 无视优先级直接启动 fetcher。缓存管理页对这类记录照实显示盘上字节，被淘汰后会回缩，用户接受零改动。用户明示「不应该像 BT 那样无条件下」「显式缓存在不开 Reseeding 的时候也不做 Reseeding」。设置里的「下载并发数」滑杆删除，引擎固定每文件 8 连接，多文件各自并行；`PikPakConfig.downloadConcurrency` 移除。
- 暂存生命周期（A 第四轮，f31d1a098、7ba2f5ef6）：引擎目录里的所有字节视为暂存，统一进一个 LRU，预算 `PikPakEngineConfig.scratchBudgetBytes` 默认 512 MiB（用户定）；淘汰按 meta.json 里记录的最近触碰时间，正在读的文件与 pin 住的文件不计入也不淘汰；被淘汰的文件截断到 0 但保留 meta.json 条目，再次播放不查云端。预热广度另有 K=6 的 LRU（用户选 K=6），超出的预热文件先丢。淘汰在句柄关闭、预热完成、首次收到 pin 集合三个时机触发，崩溃残留由此顺带清掉，不另设扫描。`PinnedSource(uri, pathsInTorrent)` 与 `PikPakTorrentDownloader.setPinnedSources()` 由 app 传入完整集合，未列出的一律可丢。
- pin 接线（主会话，4cae042b9；B2 原任务因会话限额中断）：`TorrentMediaCacheStorage` 在启动恢复完成后把 `listFlow` 交给 `TorrentMediaCacheEngine.keepRecordsPinned`，它与 `torrent_cache` 表联合订阅，路径未知时 pin 整个来源，`pathInTorrent` 写入后收窄到单文件；`PikPakEngine` 缓存最新集合，downloader 晚创建也能拿到。`deleteUnusedCaches` 对 PikPak 直接返回：没有记录的目录是流式播放与预热的暂存，由预算管理，启动时删掉会让每次重进都回到冷启动。
- 句柄优先级坍缩（f23bc8101）：`AbstractTorrentFileHandle` 的 equals 按所属文件比较，`priorityRequests` 以句柄为键，同一文件的所有句柄落到一个键上，后来的请求覆盖先前的。跟随播放的记录对自己的句柄 `pause()` 写入 null，盖掉了播放句柄的 HIGH，文件停止取流，表现为 seek 后不取流、网速归零。改为按身份比较，文件优先级是各句柄请求的最大值；anitorrent 顺带受益，播放的 HIGH 不再被缓存记录的 NORMAL 拉低。
- 播放时整文件顺序下载（9ac970d92）：进度条上灰色（已下载）从播放位置一直延伸到 70%、黄色（下载中）在 73%，不是绘制偏移，是 `TorrentDownloadController` 给出的「窗口之外的全部片」也被排入了取流队列，BT 引擎这样做是为了做种，PikPak 只是把暂存越堆越多。现在只有句柄请求 NORMAL（下满的缓存记录）时才排入窗口之外的片；只有播放句柄的 HIGH 时维持 8 MiB 预读窗口。
- 缓存页状态（af6da212c）：跟随播放的自动记录显示「暂停」而非「缓存中」，用户提出。
- 工作流 D 完成（cb6a3825a、2e8083957、8f5c0a9f8、f933fe70e，合并 011ee9add，接线 b6e255677）：设置页加 Reseeding 开关（默认关）；`PikPakReseeder` 订阅 PikPak storage 的记录列表、`torrent_cache` 表与开关，把已完成的原画文件硬链接进 anitorrent 保存目录，同一磁链交给 anitorrent 校验并分享，开关关闭时撤种删链接。native 层不在仓库内（`anitorrent-native` 来自 Maven），没有 `force_recheck` 与 `upload_mode`，也都不需要：anitorrent 会话已设 `default_dont_download`，无句柄的文件优先级为 0；丢掉 fast-resume 记录（新增 `AnitorrentTorrentDownloader.discardResumeData`）即可让 libtorrent 重新校验磁盘。前提「PikPak 相对路径与种子路径逐字相同」被证伪：云盘把包压平，只剩裸文件名，协调器按文件名匹配到种子路径，同名不唯一则放弃。每个种子加入两次，第一次只取文件清单。原画判定读引擎 meta.json 的 `variantLabel`（`PikPakSavedFiles.isOriginal`）。待观察：anitorrent storage 启动时的 `deleteUnusedCaches` 会删掉没有 anitorrent 记录的保存目录，做种用的硬链接目录属于此类，协调器随后会重建，但存在先后顺序的竞争。
- 取流层重写（2026-09-06 凌晨，合并 d96e92e65；引擎 3f1a02a18、0fdcd891c、b69b8bea3、5b62c96d2，app 侧 88f3ab2ae）：用户判定之前是在把 PikPak 当 BT 管，一周的播放 bug 全出在片、窗口、句柄优先级、暂存 LRU 这一层的配合上，且这是在重写一套会分叉的在线源播放逻辑。新边界：解析层继续借 TorrentEngine 的形状（文件清单、剧集匹配、按磁链建记录、派生命中、`filesInTorrent`），取流层是纯传输，播放器决定读什么。`RangeStreamInput : SeekableInput`：8 条连接并行取读位置之后的块，块进内存不落盘，256 KiB 槽位按偏移建 LRU 缓存，上限 64 MiB（mpv 打开时读头、跳尾读 Cues、再跳回，头尾都留在缓存里）；开始或 seek 后每次认领一个槽，读位置之后 32 MiB 都在手后改认领两个；seek 取消完全落在新窗口外的在途请求，窗口内的保留。`SequentialDownloader`：缓存记录用，8 连接按顺序写整文件，进度就是文件长度，续传从当前长度开始。`PikPakFileEntry` 变薄适配器：完整文件走本地读，否则流式；句柄 NORMAL 起顺序下载，HIGH 什么都不做；`pieces` 只按已下长度合成给进度条与 `isFinished`。预热改为并行解析相邻集直链（`setWarmUpTargets`），不取字节。删除：`PieceFetcher`、`TorrentDownloadController` 接入、位图、子片拆分、512 MiB 暂存 LRU 与 pin（`PinnedSource`、`setPinnedSources`、`keepRecordsPinned`）、按片预热与 K=6、`warmUpSiblings`、`scratchBudgetBytes`、计费网络到引擎的管线。meta.json 版本升级，旧目录重新索引。多连接不做开关：弱网下单连接只有几百 KB/s，8 条并行是能不能播的门槛，「PikPak 加速」这个功能本身就是它。live 测试实跑：首 4 MiB 1.79 s，seek 后 1 MiB 760 ms，与 SDK 直读逐字节一致。9ac970d92 与 286bec045 两个窗口截断提交随之作废。
- 重写后首轮复测（2026-09-06 00:24 起）：旧引擎预分配到完整长度的数据文件被新引擎按「长度等于记录长度」判成完整，从本地读出空洞，mpv 报 Corrupt 直奔 EOF，进度条跳到末尾并自动切集。用户决定旧格式只在开发中出现，不加迁移代码，把 `media-downloads/pikpak`（7 个目录、11.8 GB）送进回收站一次性清理。清盘后 Corrupt 为 0；未缓存位置 seek 到首字节 0.2 到 0.9 s，缓存内回跳几百微秒；打开一集固定是读头、跳尾读 Cues、跳回文件头三步，头尾都留在 64 MiB 缓存里。
- 复测发现并修（c06a41b60、6935f610b、4c129c38f）：标题解析器把 `S00E01` 读成正片 01（原有 `TODO: consider season`），整季包里 S00E01 排在 S01E01 前面，第一集因此选到 SP 文件，SP01 也经宽松匹配落到它；季号 0 现在解析成 SP。切集没拿到预热直链是两处叠加：自动记录因「0 字节」规则在离开时被删，句柄一关会话关闭，预热进各条目 `VariantReader` 的直链随之作废；修法是跟随播放的记录（状态 PAUSED）不再删除，引擎持有跨会话的 `CachingVariantLinkSource`，过期前 5 分钟内不再发放，CDN 拒绝时作废。修后日志：第一集选 S01E01；孤独摇滚与石头门各切三集，预热之后再无 `getFile` 调用；会话关闭 0 次、记录删除 0 次。
- 待观察：切集瞬间有一串 `JNI exception in SeekableInput.read`，来自进度条帧预览用的第二个 mpv 实例，它在上一集输入关闭后仍在读；旧引擎同样存在，不影响播放。包内 S00E01 与 Bangumi 的 SP01 是否同一节目文件名判断不了，按「特别篇 1」对应是文件名能给的全部信息。
- 下一项，工作流 D「PikPak 缓存做种」原计划（用户确认「这个做种反而是应该做掉以完善用户体验的」）：原画缓存的字节与种子逐字节相同，缓存记录完成时把文件硬链接进 anitorrent 的 `<saveDir>/anitorrent/<hash>/<路径>`，向 anitorrent 提交同一磁链并加 `upload_mode`，强制 recheck 让 libtorrent 自行发现盘上的 piece，整包中未下载的文件优先级置 0；会话关闭与记录完成时各 recheck 一次以纳入新到的 piece。设置项就是上面的 Reseeding 开关（`PikPakConfig.reseedingEnabled`，默认关，UI 待加），分享率、上传限速、计费网络限制全部沿用。转码变体永不做种。前提待验证：PikPak 目录里的相对路径与种子文件路径逐字相同（重名会加 (1)）；Android 上做种会启动 `AniTorrentService`，这是 BT 分享本来的行为，与「开 PikPak 不出现 BT 通知」的验收项分开看。
- 推送前置条件：pikpak-kotlin 0.5.2 需先发布到 Maven Central（推送 ef1df58 并打 tag），Animeko 依赖从 `0.5.2-SNAPSHOT` 改回 `0.5.2`，再推到 fork；仍不建 PR。
- 低优先级不排期：piece 无内容校验，位图置位只代表写完，磁盘损坏或外部改动不会被发现，靠选择器换源兜底；可在 meta.json 记抽样校验，恢复时抽查。用户判断概率很低。
- 待做：桌面端在清空缓存记录后复测 SP02、EP2 解析、播放不整文件下载、切集耗时、进度条缓冲位置（用户报告缓冲标记离播放位置很远）；Reseeding 开关 UI；可选：首批连接只有一半速率，考虑预热全部 8 条连接；模拟器带 DNS 参数重启后做 Android 端到端；B 指出的遗留：曾用过 anitorrent 的用户启动时服务仍会因清理路径启动，这是上游既有行为，未改。
- 验证方式的限制：`.claude/skills/desktop-ui-verify` 只支持 macOS；Windows 桌面端只能靠引擎 live 测试与手动运行。设备上输入 PikPak 账号密码由用户操作，不由代理输入。
- 2026-09-06 桌面复测与修复（8 个提交）：pikpak-kotlin 0.5.2 已发布，依赖改回正式版。启动恢复曾全部失败（"Media is not supported by this engine"），根因是 `PikPakEngine` 的配置 StateFlow 以 `PikPakConfig.Default` 起步，恢复跑在设置读出之前；修法与代理设置同款，`runBlocking` 读一次存档作初值。WebM3u 迁移改为不依赖引擎实例，`PikPakSavedFiles` 提供无实例的目录与导入函数。云端索引改为递归遍历包目录（`specials/` 一类子目录里的 SP 此前根本不在文件清单里），meta.json 版本升到 3 并由 `write()` 盖章，此前 `version` 字段从未被编码、默认值又跟着 CURRENT_VERSION 走，版本门从未生效。新增 `DownloadScheduler`：同时最多 2 个顺序下载，正在播放的条目有流打开时其他下载器让路，播放条目自己的下载器优先。缓存记录模型：播放整季包任一集一律建记录，默认跟随播放（`autoCached`），缓存页按恢复即转显式并全量下载；`completed` 与 `pathInTorrent` 记在记录上，`torrent_cache` 行只保留种子级事实；删一条记录不再删整季包共用的行与目录，最后一条才删；datastore 读取时按 (mediaId, subjectId, episodeId) 去重，恢复失败期间重复写入的记录不再成倍出现。
- 文件匹配与 SP（用户裁定）：对 nyaa 上 7³ACG、VCB-Studio、DBD-Raws、Moozzi2、ANK-Raws、Beatrice-Raws、jsum、UCCUSS 二十余个合集抽样（scratchpad `nyaa-sp-survey.md`），结论是特别篇的类型能从文件名读出（S00Exx、[SP01]、OAD/OVA、NCOP/NCED、PV/CM、Menu/Interview），序号不能作数：無職転生 II 唯一的 SP 叫 S00E02，巨人的 OAD02 是 3.25 话早于 OAD01，jsum 与 Moozzi2 的 `[SP01]` 只是花絮流水号，孤独摇滚包里的 S00E01 是 11.5 话而 Bangumi 的 SP01 是 ABEMA 特番。据此 `TorrentFileLabel` 做类型分类；正片只在无标签文件里匹配，数字兜底要求整段数字（08 不再落进 [1080P]）；特别篇只认集名命中与用户选择，编号相等也不自动选（试过「编号相等」与「唯一 SP 给第一个 SP」两版，均被孤独摇滚的实例否决），其余弹「资源中未找到本集」让用户从清单里挑，同类文件排最前，选择落进记录的 `pathInTorrent`。整季包对 SP 也给派生命中，条件是包里有同类文件，`pathInTorrent` 可为空。记录上的 `pathInTorrent` 只在用户选择或下完时写入，此前统计订阅把每次自动匹配结果都写了进去，旧规则的错配因此被冻结；本机 datastore 已清理。对话框「这次不挑了」的状态键加上剧集 id，同一包的两个 SP 文件清单逐项相同，只按清单记会把上一集的关闭带到下一集。
- 记录不落码（selector 归他人）：`awaitCompletedAndSelectDefault` 等全部源返回后 `trySelectDefault` 只接受已存字幕组，不像 `trySelectFromMediaSources` 那样退到任意字幕组，单集切换时下一集同字幕组已在列表里却不选。
- 复审点：`TorrentFileOverrideStore.Default` 是进程单例，通过构造参数默认值注入到 resolver、cache engine 与 `EpisodeFetchSelectPlayState`。播放器内没有「换一个文件」的入口，自动匹配错了只能删记录重来。

**流程约定（用户明示）**

- 2026-09-06 起推送到 fork `origin/pikpak-torrent-engine`，不建 PR。多个 commit 可以，最终单 PR squash。
- 用户放手自动运行直到「完全可用」：桌面端用真实账号播放与缓存验证，Android 用模拟器验证（`android-ui-verify` skill），iOS 只保证编译。
- 设计对齐清单（12 条）用户未逐条反对，视为接受；其中第 11 条已改为单 PR 多 commit。
- 新发现的 bug 一并修：Android 上启用 PikPak 会出现「BT 引擎已启用」通知，见 4.8。

**派工方式**

- A（引擎）用 Opus 子代理在独立 worktree 上做，worktree 没有 `local.properties`，需从主仓库复制一份（不打印内容、不提交）。B（接入）用 Opus 子代理在主工作区做。C（退役）等 B 落地后再派。各自只编译自己模块，集成编译由主会话统一跑。
- A 与 B 的构造契约：

```kotlin
// torrent/pikpak, package me.him188.ani.torrent.pikpak
data class PikPakEngineConfig(
    val variant: String = "original",   // "original" | "1080P" | "720P" | "480P"
    val concurrency: Int = 4,           // 每文件并发 range 读，1..8
    val slotQueueLength: Int = 1,       // 语义同现有 PikPakConfig.slotQueueLength，>= 14 为不限
)
class PikPakTorrentDownloader(
    httpClient: HttpClient,                       // API client，构造方式同现有 clientFor
    credentials: StateFlow<PikPakCredentials?>,
    sessionStore: SessionStore,
    rootDataDirectory: SystemPath,                // <saveDir>/pikpak
    config: StateFlow<PikPakEngineConfig>,
    parentCoroutineContext: CoroutineContext,
) : TorrentDownloader {
    suspend fun testConnection(): Boolean         // login + getQuota
}
```

  `PikPakClient` 构造时传 `cdnHttpClient = PikPakClient.tunedCdnClient()`。B 侧 `PikPakEngine : TorrentEngine` 直接实现接口，不继承 `AbstractTorrentEngine`；`TorrentEngineType.PikPak("pikpak")`；`DefaultTorrentManager.engines = [pikpak, anitorrent]`；`CommonKoinModule` 的存储循环里 PikPak 的 `TorrentEngineAccess` 用 `AlwaysUseTorrentEngineAccess`、`shareRatioLimitFlow` 用常量 0；resolver 链里 PikPak 的 `TorrentMediaResolver` 排在 anitorrent 与 `OfflineDownloadMediaResolver` 之前。
- A 的 live 测试：`PikPakTorrentEngineLiveTest`（desktopTest，无凭据自动跳过）用 Arch ISO 磁链，读头 4 MiB 与 60% 处 1 MiB，与 SDK `streamRangeFromUrl` 直读的 SHA-256 比对；关闭后同 JVM 内再开一次会话，断言位图恢复且不发 API 请求。

**已知陷阱**

- pikpak-kotlin 的 Android publication 需要 `ANDROID_HOME`，值取 Animeko `local.properties` 的 `sdk.dir`。
- SDK 的 `RateLimiterTest.concurrent waiters are staggered not thundering` 依赖挂钟间隔，慢 runner 上会抖，遇到直接 `gh run rerun --failed`。
- `local.properties` 的 `pikpak-*` 键只对 `:torrent:pikpak` 的 Test 任务生效；其他模块的 live 测试要自行读取。
- 在 Windows 上 `publishToMavenLocal` 也产出 iOS klib（编译 klib 不需要 macOS，只有链接需要），三端依赖都能解析。
- 参考实现 `C:\Codes\pikpakcli`（Go，52funny/pikpakcli），查 PikPak 接口行为时可对照。
- A 的 worktree 在 `C:\Codes\animeko-pikpak-a`，分支 `pikpak-engine-a`，已复制 `local.properties`；收口时 cherry-pick 或 merge 回 `pikpak-torrent-engine`。

## 十一、验收

- 播放：冷启动播一集 1080p 番剧，从点击到出画面 ≤ 3 秒（slot 命中）；seek 到未缓存位置 ≤ 2 秒出画面；播放中断网 30 秒恢复继续；切后台 10 分钟返回继续；连续播放 25 小时以上跨越直链过期不中断（可用缩短 `expire` 的伪造 URL 在 mock 层验证）。
- 缓存：整季包各集缓存指向各自文件；缓存中途杀进程，重启后无网也能恢复进度并在联网后继续；完成后重启不触发任何 PikPak 请求。
- 变体：设置 1080P 后播放与缓存均为 TS 流，字幕轨缺失时外挂字幕仍可用；变体不可用时透明回退原画并在日志记录原因。
- 退役：存量 WebM3u 类 BT 缓存迁移后可播放；`HttpMediaCacheEngine` 对磁链 `supports` 返回 false。
- Android：启用 PikPak、播放与缓存全程不出现 BT 引擎通知，`AniTorrentService` 未被启动。
- 三端：desktop 与 Android 走 `TorrentInput.jvm`，iOS 走新的 native 实现，三端各跑一次端到端。
