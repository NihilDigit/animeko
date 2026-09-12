/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.MagnetResource
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.SessionStore
import io.github.nihildigit.pikpak.getQuota
import io.github.nihildigit.pikpak.resolveMagnet
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.torrent.api.TorrentDownloader
import me.him188.ani.app.torrent.api.TorrentLibInfo
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.EncodedTorrentInfo
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.isDirectory
import me.him188.ani.utils.io.length
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.useDirectoryEntries
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource


/**
 * PikPak as a [TorrentDownloader].
 *
 * PikPak stores by content hash and both ends of that are reachable: one magnet
 * resolves to a gcid per file without creating anything, and one gcid turns
 * back into a file object in a single request that transfers no bytes. So this
 * engine submits no offline task — `resolveMagnet` answers in 150 ms, and in
 * 146 ms when PikPak holds nothing, against 5 to 10 seconds for content it has
 * and minutes or a failure for content it does not.
 *
 * A file object exists only long enough to mint a CDN link, which outlives it
 * by 12 hours. See [CloudFile].
 *
 * Playback fetches the ranges the player reads and writes nothing; a cache
 * record fills a file front to back, so its length on disk is the progress and
 * there is no bitmap.
 *
 * @param httpClient the API client. Must not install ContentNegotiation or
 *  HttpRequestRetry; the SDK parses JSON itself and runs its own retry loop.
 * @param rootDataDirectory `<saveDir>/pikpak`. One subdirectory per source key.
 */
class PikPakTorrentDownloader(
    private val httpClient: HttpClient,
    private val credentials: StateFlow<PikPakCredentials?>,
    private val sessionStore: SessionStore,
    private val rootDataDirectory: SystemPath,
    private val config: StateFlow<PikPakEngineConfig>,
    parentCoroutineContext: CoroutineContext,
) : TorrentDownloader {
    private val logger = logger<PikPakTorrentDownloader>()
    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]))

    // Kept separate from the API client so CDN reads get a connection pool
    // sized for the eight concurrent connections one signed URL accepts; the
    // app-wide client's per-host cap is lower.
    private val cdnClient: HttpClient by lazy { PikPakClient.tunedCdnClient() }

    private val clientLock = Mutex()
    private var clientEntry: Pair<PikPakCredentials, PikPakClient>? = null

    private val driveIndex = PikPakDriveIndex(clientProvider = ::client)

    private val sessionLock = Mutex()
    private val sessions = mutableMapOf<String, Deferred<PikPakSession>>()

    @Volatile
    private var closed = false

    override val vendor: TorrentLibInfo = TorrentLibInfo(
        vendor = "PikPak",
        version = SDK_VERSION,
        supportsStreaming = true,
    )

    /**
     * Empties the temp folder at startup.
     *
     * A file object lives for the two round trips between being created and
     * having minted its link, so anything still in that folder was left by a
     * process that died inside that window. Nothing else writes there, which is
     * why the folder itself is the criterion and no local bookkeeping is
     * needed — see [PikPakDriveIndex].
     *
     * Exposed as a Job only so tests can await it: it is the one request in
     * this class the caller did not ask for.
     */
    internal val startupSweep: Job = scope.launch {
        runCatching {
            credentials.filterNotNull().first { it.isValid }
            val leftovers = driveIndex.listTemp().map { it.id }.filter { it.isNotEmpty() }
            if (leftovers.isEmpty()) return@runCatching
            driveIndex.delete(leftovers)
            logger.info { "[pikpak] startup sweep removed ${leftovers.size} leftover object(s)" }
        }.onFailure {
            logger.warn(it) { "[pikpak] startup sweep failed; the leftovers wait for the next one" }
        }
    }

    override val totalStats: Flow<TorrentDownloader.Stats> = flow {
        var lastDelivered = 0L
        var lastMark = TimeSource.Monotonic.markNow()
        while (true) {
            val live = sessionLock.withLock { sessions.values.mapNotNull { it.takeIf { d -> d.isCompleted } } }
                .mapNotNull { runCatching { it.getCompleted() }.getOrNull() }
            val delivered = live.sumOf { it.deliveredBytes }
            val elapsed = lastMark.elapsedNow()
            val speed = if (elapsed.inWholeMilliseconds <= 0) 0L else {
                ((delivered - lastDelivered) * 1000 / elapsed.inWholeMilliseconds).coerceAtLeast(0)
            }
            lastDelivered = delivered
            lastMark = TimeSource.Monotonic.markNow()

            val totalSize = live.sumOf { it.totalSize }
            val downloaded = live.sumOf { it.downloadedBytes }
            emit(
                TorrentDownloader.Stats(
                    totalSize = totalSize,
                    downloadedBytes = downloaded,
                    downloadSpeed = speed,
                    uploadedBytes = 0,
                    uploadSpeed = 0,
                    downloadProgress = if (totalSize == 0L) 0f else {
                        (downloaded.toFloat() / totalSize).coerceIn(0f, 1f)
                    },
                ),
            )
            delay(1.seconds)
        }
    }

    /**
     * No network. The uri is all a later [startDownload] needs, and this value
     * is what `TorrentMediaCacheEngine` stores in the `torrent_cache` table, so
     * anything expensive put here would be paid for again on every restart.
     */
    override suspend fun fetchTorrent(uri: String, timeoutSeconds: Int): EncodedTorrentInfo =
        PikPakSavedFiles.encodedTorrentInfoFor(uri)

    override suspend fun startDownload(
        data: EncodedTorrentInfo,
        parentCoroutineContext: CoroutineContext,
    ): TorrentSession {
        check(!closed) { "PikPakTorrentDownloader is closed" }
        val uri = decodeUri(data)
        val sourceKey = sourceKeyFor(uri)

        // Memoizing the Deferred rather than the session gives per-source
        // mutual exclusion for free: two concurrent starts of the same magnet
        // await one creation instead of racing to resolve the same magnet twice.
        val deferred = sessionLock.withLock {
            sessions[sourceKey]?.takeIf { !it.isCancelled }
                ?: scope.async { createSession(uri, sourceKey, parentCoroutineContext) }
                    .also { sessions[sourceKey] = it }
        }
        return try {
            deferred.await()
        } catch (e: Throwable) {
            // Only a failed creation is forgotten. A waiter that was cancelled
            // (the user switched episode mid-resolve) leaves the deferred
            // running in this scope, and dropping it here would let the next
            // start of the same magnet build a second session on the same files.
            if (!deferred.isActive) {
                sessionLock.withLock { if (sessions[sourceKey] === deferred) sessions.remove(sourceKey) }
            }
            throw e
        }
    }

    override fun getSaveDirForTorrent(data: EncodedTorrentInfo): SystemPath =
        PikPakSavedFiles.saveDirectoryFor(rootDataDirectory, decodeUri(data))

    override fun listSaves(): List<SystemPath> {
        if (!rootDataDirectory.exists()) return emptyList()
        return rootDataDirectory.useDirectoryEntries { entries -> entries.filter { it.isDirectory() }.toList() }
    }

    /** Signs in and asks for the account quota. Used by the settings page's connection test. */
    suspend fun testConnection(): Boolean {
        val client = client()
        client.login()
        client.getQuota()
        return true
    }

    ///////////////////////////////////////////////////////////////////////////
    // 对外查询
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Whether this engine can serve this torrent: PikPak has indexed every one
     * of its files.
     *
     * Not cached, and [startDownload] resolves again right after.
     * `resolveMagnet` is a pure query of 146 ms and asking twice is idempotent,
     * whereas a cache would either remember a magnet as unindexed after it had
     * been indexed, or need an expiry picked out of the air.
     *
     * Only video entries count: whatever ends up being played or cached is a
     * video, and an unindexed NFO or sample says nothing about the torrent. All
     * of the video entries have to be indexed, because this is asked while
     * choosing an engine, before it is known which file will be read. One
     * unindexed episode hands the whole pack to BT: that episode throws
     * [PikPakNotIndexedException] from [CloudFile], which playback falls back
     * from but caching does not — once the engine is chosen, caching can only
     * fail.
     */
    suspend fun canServe(uri: String): Boolean {
        val resolved = magnetResolver(uri) ?: return false
        // A zero-length entry is a directory placeholder: it needs no file object and cannot have one.
        val videos = resolved.files.filter { it.size > 0 && isVideoPath(it.path) }
        if (videos.isEmpty()) return false
        return videos.none { it.gcid.isNullOrEmpty() }
    }

    /**
     * What the old engine left parked in the drive, for the one-time upgrade
     * notice to offer clearing. See [PikPakDriveItem].
     *
     * The engine's own folder is a different one and is not reported here: it
     * holds nothing the user has to decide about.
     */
    suspend fun legacyFolderItems(): List<PikPakDriveItem> =
        driveIndex.listLegacy().filter { it.id.isNotEmpty() }.map { PikPakDriveItem(id = it.id, name = it.name) }

    /** Clears the old folder, pressed by the user in that notice. */
    suspend fun clearLegacyFolder(ids: List<String>) {
        if (ids.isEmpty()) return
        driveIndex.delete(ids)
        logger.info { "[pikpak] cleared ${ids.size} item(s) from the legacy folder at the user's request" }
    }

    /** Drive usage. Nothing of the engine's own is counted in it for longer than a round trip. */
    suspend fun driveUsage(): PikPakDriveUsage {
        val quota = client().getQuota().quota
        return PikPakDriveUsage(
            accountUsedBytes = quota.usageBytes,
            accountLimitBytes = quota.limitBytes,
        )
    }

    /**
     * How many objects the temp folder holds. Lists, never deletes.
     *
     * For a live test to assert that an open leaves nothing behind. A call that
     * also deleted would make a real leak visible only on the first run.
     */
    internal suspend fun tempFolderObjectCount(): Int = driveIndex.listTemp().size

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        cdnClient.close()
    }

    ///////////////////////////////////////////////////////////////////////////
    // session construction
    ///////////////////////////////////////////////////////////////////////////

    private suspend fun createSession(
        uri: String,
        sourceKey: String,
        parentCoroutineContext: CoroutineContext,
    ): PikPakSession {
        val saveDirectory = rootDataDirectory.resolve(sourceKey)
        saveDirectory.createDirectories()
        val resumeData = PikPakResumeData(saveDirectory)

        val started = TimeSource.Monotonic.markNow()
        val restored = resumeData.read()?.takeIf { it.files.isNotEmpty() && filesConsistent(saveDirectory, it) }
        val meta = when {
            restored == null -> indexFromMagnet(uri, sourceKey).also {
                logger.info { "[pikpak] $sourceKey resolved from the magnet in ${started.elapsedNow()}" }
                resumeData.write(it)
            }

            restored.indexed -> {
                logger.info { "[pikpak] restored $sourceKey from disk, ${restored.files.size} file(s), no API call" }
                restored
            }

            else -> completeIndex(restored, uri, sourceKey, saveDirectory, resumeData)
        }
        val indexedAt = started.elapsedNow()

        // The entries need a way to tell the session that its last handle went
        // away, and the session needs the entries to exist first. One late-bound
        // reference is cheaper than splitting the session's construction.
        var session: PikPakSession? = null
        val entries = meta.files.map { fileMeta ->
            buildEntry(
                fileMeta, sourceKey, saveDirectory, resumeData, parentCoroutineContext,
                onHandleCountChanged = { session?.closeIfNotInUse() },
            )
        }
        logger.info {
            "[pikpak] $sourceKey ready in ${started.elapsedNow()} " +
                    "(index $indexedAt, ${entries.size} entries built in ${started.elapsedNow() - indexedAt})"
        }
        return PikPakSession(
            sourceKey = sourceKey,
            torrentName = meta.name,
            saveDirectory = saveDirectory,
            entries = entries,
            listingComplete = meta.indexed,
            // Nothing of this session's is left in the cloud to collect: each
            // file object was dropped as soon as it had minted its link.
            onClosed = { closing -> sessionLock.withLock { sessions.remove(closing.sourceKey) } },
            parentCoroutineContext = parentCoroutineContext,
        ).also { session = it }
    }

    /**
     * Fills a partial listing in from the cloud, or keeps the imported files as they are.
     *
     * 云端不可达时交出的是残缺清单, 会话据此报告 [PikPakSession.listingComplete] = false, 由
     * 选择器拒绝"这个种子唯一的视频"那条兜底. 试过用磁链的 `dn` 匹配裸名、只留单文件种子, 桌面端
     * 实跑日志里 6 条磁链的 `dn` 全是空值 (dmhy 一类源就这么发), 那条判据恒为假, 会让所有已迁移
     * 缓存的离线播放一律失败.
     */
    private suspend fun completeIndex(
        partial: PikPakTorrentMeta,
        uri: String,
        sourceKey: String,
        saveDirectory: SystemPath,
        resumeData: PikPakResumeData,
    ): PikPakTorrentMeta {
        val cloud = try {
            indexFromMagnet(uri, sourceKey)
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            logger.warn(e) { "[pikpak] $sourceKey holds imported files only and could not be indexed" }
            return partial
        }
        val (merged, relocations) = mergeImportedInto(cloud, partial).let { it.meta to it.relocations }
        for ((from, to) in relocations) {
            val source = saveDirectory.resolve(from)
            if (!source.exists()) continue
            val target = saveDirectory.resolve(to)
            target.path.parent?.inSystem?.createDirectories()
            source.moveTo(target)
            // The bitmap is the record of what is on disk, so leaving it behind
            // turns a complete import into an empty one -- and an imported file
            // has no gcid to refetch from.
            val bitmap = PieceBitmap.pathFor(source)
            if (bitmap.exists()) bitmap.moveTo(PieceBitmap.pathFor(target))
            logger.info { "[pikpak] $sourceKey moved imported $from to its torrent path $to" }
        }
        val result = if (filesConsistent(saveDirectory, merged)) merged else cloud
        resumeData.write(result)
        logger.info { "[pikpak] $sourceKey filled in from the magnet, ${result.files.size} file(s)" }
        return result
    }

    private suspend fun buildEntry(
        fileMeta: PikPakFileMeta,
        sourceKey: String,
        saveDirectory: SystemPath,
        resumeData: PikPakResumeData,
        parentCoroutineContext: CoroutineContext,
        onHandleCountChanged: suspend () -> Unit,
    ): PikPakFileEntry {
        // Nothing is created here, locally or in the cloud: playback writes
        // nothing, a cache record's file appears on its first write, and the
        // cloud object waits for this entry's first read.
        val cloudFile = CloudFile(
            gcid = fileMeta.gcid,
            size = fileMeta.length,
            name = fileMeta.pathInTorrent.substringAfterLast('/'),
            clientProvider = ::client,
            driveIndex = driveIndex,
            connectionBudget = config.value.effectiveConcurrency,
            preferenceProvider = { config.value.variantPreference },
            pinned = pinnedVariantOf(fileMeta, saveDirectory),
            scope = scope,
        )

        return PikPakFileEntry(
            index = fileMeta.index,
            length = fileMeta.length,
            saveDirectory = saveDirectory,
            relativePath = fileMeta.pathInTorrent,
            torrentId = sourceKey,
            parentCoroutineContext = parentCoroutineContext,
            meta = fileMeta,
            source = cloudFile,
            initialVariantLength = fileMeta.variantLength ?: fileMeta.length,
            streamVariant = cloudFile::streamVariant,
            onVariantDecided = { resumeData.recordVariant(fileMeta.pathInTorrent, it.mediaId, it.length) },
            concurrency = config.value.effectiveConcurrency,
            onHandleCountChanged = onHandleCountChanged,
        )
    }

    /**
     * 这个条目的变体定下了没有, 定的是哪个.
     *
     * 盘上的字节属于一个具体的变体, 所以已经定下的目录不跟画质设置走——改设置只影响还没定下的.
     * 第二条分支是历史包袱: `variantLength` 存在之前写下的目录, 以及迁移导入进来的文件, meta 里
     * 都没有变体记录而盘上已经有字节, 而那些字节一律是原画. 不认这一条, 用户一旦把设置改成转码,
     * 这些字节就会被当成另一份流的前缀接着往下写.
     */
    private fun pinnedVariantOf(fileMeta: PikPakFileMeta, saveDirectory: SystemPath): PinnedVariant? = when {
        fileMeta.variantLength != null -> PinnedVariant(fileMeta.mediaId)
        saveDirectory.resolve(fileMeta.pathInTorrent).exists() -> PinnedVariant(null)
        else -> null
    }

    ///////////////////////////////////////////////////////////////////////////
    // 磁链解析
    ///////////////////////////////////////////////////////////////////////////

    /**
     * How a magnet becomes a file listing. Replaced only by tests: faking a
     * drive any other way means answering the SDK's HTTP calls, and this
     * listing is where the restore paths below diverge.
     */
    internal var magnetResolver: suspend (uri: String) -> MagnetResource? = { uri ->
        val client = client()
        client.login()
        client.resolveMagnet(uri)
    }

    private suspend fun indexFromMagnet(uri: String, sourceKey: String): PikPakTorrentMeta {
        val resource = magnetResolver(uri)
            ?: throw PikPakNotIndexedException(uri, "PikPak has no record of $sourceKey; use another source")

        // gcid 为 null 的条目也留着, 和 resolveMagnet 保留它们是同一个理由: 清单短了一个文件,
        // "PikPak 没索引这一集"就变成了"种子里没有这一集". 后者会让选择器抛 NO_MATCHING_FILE,
        // 而那个异常按设计不回退——它假定别的引擎面对同一份文件名会得出同样结论, 这对过滤过的
        // 清单不成立, 结果是 BT 明明放得出来的一集彻底播不了.
        //
        // 这样的条目建不出文件对象, 选中它时 CloudFile 抛 PikPakNotIndexedException, 那条路
        // 是会回退的.
        val present = resource.files.filter { it.size > 0 }
        if (present.none { it.gcid != null }) {
            throw PikPakNotIndexedException(uri, "PikPak indexed $sourceKey but holds none of its files")
        }

        // 按路径排, 不按文件名: 带子目录的整包里, 裸名的排序说明不了文件在哪, 而下面给出的 index
        // 是这个文件身份的一部分.
        val files = present.sortedBy { it.path }.mapIndexed { index, file ->
            PikPakFileMeta(
                index = index,
                pathInTorrent = file.path,
                gcid = file.gcid.orEmpty(),
                length = file.size,
            )
        }
        return PikPakTorrentMeta(
            uri = uri,
            sourceKey = sourceKey,
            name = resource.name.ifEmpty { sourceKey },
            files = files,
        )
    }

    ///////////////////////////////////////////////////////////////////////////
    // client
    ///////////////////////////////////////////////////////////////////////////

    private suspend fun client(): PikPakClient = clientLock.withLock {
        val creds = credentials.value
            ?: throw PikPakNotConfiguredException("PikPak credentials are not set")
        if (!creds.isValid) throw PikPakNotConfiguredException("PikPak credentials are incomplete")
        clientEntry?.takeIf { it.first == creds }?.second?.let { return it }
        PikPakClient(
            account = creds.username,
            password = creds.password,
            sessionStore = sessionStore,
            httpClient = httpClient,
            cdnHttpClient = cdnClient,
        ).also { clientEntry = creds to it }
    }

    private companion object {
        const val SDK_VERSION = "0.6.0"
    }
}

@Serializable
internal data class PikPakEncodedTorrent(val uri: String)

private val encodedTorrentJson = Json { ignoreUnknownKeys = true }

internal fun decodeUri(data: EncodedTorrentInfo): String =
    encodedTorrentJson.decodeFromString(PikPakEncodedTorrent.serializer(), data.data.decodeToString()).uri

internal fun encodeUri(uri: String): ByteArray =
    encodedTorrentJson.encodeToString(PikPakEncodedTorrent.serializer(), PikPakEncodedTorrent(uri))
        .encodeToByteArray()

/** 选引擎时只看这些条目, 见 [PikPakTorrentDownloader.canServe]. */
private val VIDEO_EXTENSIONS =
    setOf("mkv", "mp4", "ts", "m2ts", "avi", "mov", "flv", "webm", "rm", "rmvb", "wmv", "mpeg", "m4v")

internal fun isVideoPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

/**
 * 种子的清单 [cloud], 加上 [local] 知道的、已经在盘上的文件.
 *
 * 种子决定有哪些文件、什么顺序、什么 gcid. 本地条目决定长度, 因为长度描述的是盘上那些字节: 迁移
 * 过来的文件是另一个引擎从网页源取的, 它未必是种子里那一份编码, 拿种子的长度去盖会把一个完整的
 * 文件说成不完整并从别的编码续传.
 *
 * 路径先精确匹配; 云端在那个路径下没有列出的本地条目再按裸名匹配: 迁移是按裸名导入的, 而种子保留
 * 整包的子目录, 所以同一个文件可能本地叫 `01.mkv`、种子里叫 `specials/01.mkv`. 这种匹配记在
 * [Merged.relocations] 里让调用方把文件挪过去. 裸名在种子里出现多于一次、或者一次都没有的, 不去
 * 猜: 本地条目作为额外条目保留, 它的字节仍然能播.
 */
internal fun mergeImportedInto(cloud: PikPakTorrentMeta, local: PikPakTorrentMeta): Merged {
    val byPath = local.files.associateBy { it.pathInTorrent }
    val cloudPaths = cloud.files.mapTo(HashSet()) { it.pathInTorrent }
    val unmatched = local.files.filter { it.pathInTorrent !in cloudPaths }
    val cloudByName = cloud.files.groupBy { it.pathInTorrent.substringAfterLast('/') }
    val relocations = mutableMapOf<String, String>()
    val byName = mutableMapOf<String, PikPakFileMeta>()
    val extras = mutableListOf<PikPakFileMeta>()
    for (entry in unmatched) {
        val target = cloudByName[entry.pathInTorrent.substringAfterLast('/')]?.singleOrNull()
        if (target == null || target.pathInTorrent in byPath || target.pathInTorrent in byName) {
            extras += entry
        } else {
            byName[target.pathInTorrent] = entry
            relocations[entry.pathInTorrent] = target.pathInTorrent
        }
    }

    var nextIndex = cloud.files.size
    val files = cloud.files.map { remote ->
        val onDisk = byPath[remote.pathInTorrent] ?: byName[remote.pathInTorrent] ?: return@map remote
        remote.copy(length = onDisk.length)
    } + extras.map { it.copy(index = nextIndex++) }
    return Merged(cloud.copy(indexed = true, files = files), relocations)
}

/** @param relocations local path to torrent path, for files the merge placed under a different path. */
internal class Merged(val meta: PikPakTorrentMeta, val relocations: Map<String, String>)

/**
 * Nothing recorded in `meta.json` is contradicted by what is on disk.
 *
 * A file that is absent is normal and not a reason to re-index: playback
 * writes nothing, so most entries of a restored session have no file at all.
 * A file longer than its recorded length is the one real inconsistency, and
 * re-resolving the magnet is the repair.
 *
 * 比的是下载那份流的长度. 转码流比原画长是正常的, 拿种子给的长度去比会让每一次恢复都判成不一致.
 */
internal fun filesConsistent(saveDirectory: SystemPath, meta: PikPakTorrentMeta): Boolean = meta.files.all {
    val path = saveDirectory.resolve(it.pathInTorrent)
    !path.exists() || path.length() <= (it.variantLength ?: it.length)
}
