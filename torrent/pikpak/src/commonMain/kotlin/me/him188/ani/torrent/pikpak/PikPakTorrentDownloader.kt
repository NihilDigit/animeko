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
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.getQuota
import io.github.nihildigit.pikpak.resolveMagnet
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
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
import me.him188.ani.utils.io.isDirectory
import me.him188.ani.utils.io.length
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.useDirectoryEntries
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

// Resolve magnets to content hashes, then serve them through the torrent playback/cache APIs.
// The injected API client must omit ContentNegotiation and HttpRequestRetry; the SDK owns both.
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

    // The SDK allows a minute of silence on a CDN read, which suits a background download and not a
    // player: a single connection going quiet stalled playback for 21 seconds while seven others
    // were moving 7 MB/s, so the block the demuxer needed was the only thing missing. Three seconds
    // of no bytes at all means that connection is gone; re-issuing the range costs one round trip.
    // config() keeps the tuned connection pool and replaces only the timeouts.
    private val cdnClient: HttpClient by lazy {
        PikPakClient.tunedCdnClient().config {
            install(HttpTimeout) {
                socketTimeoutMillis = CDN_SILENCE_TIMEOUT.inWholeMilliseconds
                // A range request is free to take as long as it needs while bytes keep arriving.
                requestTimeoutMillis = Long.MAX_VALUE
            }
        }
    }

    private val clientLock = Mutex()
    private var clientEntry: Pair<PikPakCredentials, PikPakAccount>? = null

    private val sessionLock = Mutex()
    private val sessions = mutableMapOf<String, Deferred<PikPakSession>>()

    private val downloadScheduler = DownloadScheduler()

    @Volatile
    private var closed = false

    override val vendor: TorrentLibInfo = TorrentLibInfo(
        vendor = "PikPak",
        version = SDK_VERSION,
        supportsStreaming = true,
    )

    internal val startupSweep: Job = scope.launch {
        try {
            credentials.filterNotNull().first { it.isValid }
            account().startupSweep.join()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "[pikpak] could not initialize startup sweep" }
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

    override suspend fun fetchTorrent(uri: String, timeoutSeconds: Int): EncodedTorrentInfo =
        PikPakSavedFiles.encodedTorrentInfoFor(uri)

    override suspend fun startDownload(
        data: EncodedTorrentInfo,
        parentCoroutineContext: CoroutineContext,
    ): TorrentSession {
        check(!closed) { "PikPakTorrentDownloader is closed" }
        val uri = decodeUri(data)
        val sourceKey = sourceKeyFor(uri)

        // Share creation as well as the resulting session so concurrent opens cannot write the same files.
        val deferred = sessionLock.withLock {
            sessions[sourceKey]?.takeIf { !it.isCancelled }
                ?: scope.async { createSession(uri, sourceKey, parentCoroutineContext) }
                    .also { sessions[sourceKey] = it }
        }
        return try {
            deferred.await()
        } catch (e: Throwable) {
            // Cancelling one waiter must not discard another caller's session: not one still being
            // created, and not one that completed while this caller was being cancelled either.
            if (deferred.isCancelled || (deferred.isCompleted && completedSessionOf(deferred) == null)) {
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

    suspend fun testConnection(): Boolean {
        val client = client()
        client.login()
        client.getQuota()
        return true
    }

    // Engine selection precedes episode selection, so every video currently needs a GCID.
    suspend fun canServe(uri: String): Boolean {
        val resolved = magnetResolver(uri) ?: return false

        val videos = resolved.files.filter { it.size > 0 && isVideoPath(it.path) }
        if (videos.isEmpty()) return false
        return videos.none { it.gcid.isNullOrEmpty() }
    }

    suspend fun legacyFolderItems(): List<PikPakDriveItem> =
        account().driveIndex.listLegacy().filter { it.id.isNotEmpty() }.map { PikPakDriveItem(id = it.id, name = it.name) }

    suspend fun clearLegacyFolder(ids: List<String>) {
        if (ids.isEmpty()) return
        account().driveIndex.delete(ids)
        logger.info { "[pikpak] cleared ${ids.size} item(s) from the legacy folder at the user's request" }
    }

    suspend fun driveUsage(): PikPakDriveUsage {
        val quota = client().getQuota().quota
        return PikPakDriveUsage(
            accountUsedBytes = quota.usageBytes,
            accountLimitBytes = quota.limitBytes,
        )
    }

    internal suspend fun tempFolderObjectIds(): Set<String> =
        account().driveIndex.listTemp().mapTo(mutableSetOf()) { it.id }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        cdnClient.close()
    }

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
            // Removing by key alone would let a stale session drop the entry a newer one for the
            // same save directory installed, and two live sessions would then write the same files.
            onClosed = { closing ->
                sessionLock.withLock {
                    val current = sessions[closing.sourceKey]
                    if (current != null && completedSessionOf(current) === closing) {
                        sessions.remove(closing.sourceKey)
                    }
                }
            },
            parentCoroutineContext = parentCoroutineContext,
        ).also { session = it }
    }

    // getCompleted throws unless the Deferred completed with a value, so null means there is no live
    // session behind this entry: it is still being created, or it failed, or it was cancelled.
    private fun completedSessionOf(deferred: Deferred<PikPakSession>): PikPakSession? =
        if (!deferred.isCompleted) null else runCatching { deferred.getCompleted() }.getOrNull()

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
            if (e is CancellationException) throw e
            logger.warn(e) { "[pikpak] $sourceKey holds imported files only and could not be indexed" }
            return partial
        }
        val merged = mergeImportedInto(cloud, partial)
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
        val cloudFile = CloudFile(
            gcid = fileMeta.gcid,
            size = fileMeta.length,
            name = fileMeta.pathInTorrent.substringAfterLast('/'),
            accountProvider = ::account,
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
            scheduler = downloadScheduler,
            onHandleCountChanged = onHandleCountChanged,
        )
    }

    // Existing bytes without a recorded variant predate transcoding support and are originals.
    private fun pinnedVariantOf(fileMeta: PikPakFileMeta, saveDirectory: SystemPath): PinnedVariant? = when {
        fileMeta.variantLength != null -> PinnedVariant(fileMeta.mediaId)
        saveDirectory.resolve(fileMeta.pathInTorrent).exists() -> PinnedVariant(null)
        else -> null
    }

    internal var magnetResolver: suspend (uri: String) -> MagnetResource? = { uri ->
        val client = client()
        client.login()
        client.resolveMagnet(uri)
    }

    private suspend fun indexFromMagnet(uri: String, sourceKey: String): PikPakTorrentMeta {
        val resource = magnetResolver(uri)
            ?: throw PikPakNotIndexedException(uri, "PikPak has no record of $sourceKey; use another source")

        val present = resource.files.filter { it.size > 0 }
        if (present.none { it.gcid != null }) {
            throw PikPakNotIndexedException(uri, "PikPak indexed $sourceKey but holds none of its files")
        }

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

    private suspend fun client(): PikPakClient = account().client

    private suspend fun account(): PikPakAccount = clientLock.withLock {
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
        ).let { PikPakAccount(it, scope) }.also { clientEntry = creds to it }
    }

    private companion object {
        const val SDK_VERSION = "0.6.2"

        val CDN_SILENCE_TIMEOUT = 3.seconds
    }
}

data class PikPakEngineConfig(
    val variant: String = VARIANT_ORIGINAL,
    val concurrency: Int = 8,
) {
    companion object {
        const val VARIANT_ORIGINAL = "original"

        // PikPak rejects a ninth simultaneous connection to the same signed URL.
        const val MAX_CONCURRENCY: Int = 8
    }

    val effectiveConcurrency: Int get() = concurrency.coerceIn(1, MAX_CONCURRENCY)

    val prefersOriginal: Boolean get() = variant.isEmpty() || variant.equals(VARIANT_ORIGINAL, ignoreCase = true)

    val variantPreference: VariantPreference
        get() = if (prefersOriginal) VariantPreference.Original else VariantPreference.Resolution(variant)
}

data class PikPakCredentials(
    val username: String,
    val password: String,
) {
    val isValid: Boolean get() = username.isNotEmpty()
}

data class PikPakDriveItem(
    val id: String,
    val name: String,
)

data class PikPakDriveUsage(
    val accountUsedBytes: Long,
    val accountLimitBytes: Long,
)

class PikPakNotConfiguredException(message: String) : Exception(message)

class PikPakNotIndexedException(
    val uri: String,
    message: String = "PikPak has no record of $uri",
) : Exception(message)

@Serializable
internal data class PikPakEncodedTorrent(val uri: String)

private val encodedTorrentJson = Json { ignoreUnknownKeys = true }

internal fun decodeUri(data: EncodedTorrentInfo): String =
    encodedTorrentJson.decodeFromString(PikPakEncodedTorrent.serializer(), data.data.decodeToString()).uri

internal fun encodeUri(uri: String): ByteArray =
    encodedTorrentJson.encodeToString(PikPakEncodedTorrent.serializer(), PikPakEncodedTorrent(uri))
        .encodeToByteArray()

private val VIDEO_EXTENSIONS =
    setOf("mkv", "mp4", "ts", "m2ts", "avi", "mov", "flv", "webm", "rm", "rmvb", "wmv", "mpeg", "m4v")

internal fun isVideoPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

// Cache records retain imported paths. Cloud access uses GCIDs independently of the local layout.
internal fun mergeImportedInto(cloud: PikPakTorrentMeta, local: PikPakTorrentMeta): PikPakTorrentMeta {
    val byPath = local.files.associateBy { it.pathInTorrent }
    val cloudPaths = cloud.files.mapTo(HashSet()) { it.pathInTorrent }
    val unmatched = local.files.filter { it.pathInTorrent !in cloudPaths }
    val cloudByName = cloud.files.groupBy { it.pathInTorrent.substringAfterLast('/') }
    val unmatchedByName = unmatched.groupBy { it.pathInTorrent.substringAfterLast('/') }
    val byName = mutableMapOf<String, PikPakFileMeta>()
    val extras = mutableListOf<PikPakFileMeta>()
    for (entry in unmatched) {
        val target = cloudByName[entry.pathInTorrent.substringAfterLast('/')]?.singleOrNull()
        if (target == null || target.pathInTorrent in byPath ||
            unmatchedByName[entry.pathInTorrent.substringAfterLast('/')]?.size != 1
        ) {
            extras += entry
        } else {
            byName[target.pathInTorrent] = entry
        }
    }

    var nextIndex = cloud.files.size
    val files = cloud.files.map { remote ->
        val onDisk = byPath[remote.pathInTorrent] ?: byName[remote.pathInTorrent] ?: return@map remote
        remote.copy(
            pathInTorrent = onDisk.pathInTorrent,
            length = onDisk.length,
            mediaId = onDisk.mediaId,
            variantLength = onDisk.variantLength,
        )
    } + extras.map { it.copy(index = nextIndex++) }
    return cloud.copy(indexed = true, files = files)
}

// Missing files are normal for streaming sessions; preallocation makes shorter files resumable.
internal fun filesConsistent(saveDirectory: SystemPath, meta: PikPakTorrentMeta): Boolean = meta.files.all {
    val path = saveDirectory.resolve(it.pathInTorrent)
    !path.exists() || path.length() <= (it.variantLength ?: it.length)
}

// Directory IDs and cleanup requests must use the client that created them, even after an account switch.
internal class PikPakAccount(val client: PikPakClient, scope: CoroutineScope) {
    val driveIndex = PikPakDriveIndex(clientProvider = { client })

    val startupSweep = scope.launch {
        try {
            // Every other entry point logs in before its first request. Skipping it here sent the
            // first request of a cold start out unauthenticated: the 401 recovery path reads only
            // the in-memory session, never the store, so a stored refresh token could not be used
            // and the client fell back to a full captcha sign-in.
            val mark = TimeSource.Monotonic.markNow()
            client.login()
            logger<PikPakAccount>().info { "[pikpak] startup login took ${mark.elapsedNow()}" }
            val cutoff = Clock.System.now() - 1.days
            val leftovers = driveIndex.listTemp().filter {
                // Other devices may still be minting links. Unknown timestamps are not evidence of abandonment.
                it.isFile && it.id.isNotEmpty() &&
                        runCatching { Instant.parse(it.createdTime) < cutoff }.getOrDefault(false)
            }
            driveIndex.delete(leftovers.map { it.id })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger<PikPakAccount>().warn(e) { "[pikpak] startup sweep failed" }
        }
    }
}
