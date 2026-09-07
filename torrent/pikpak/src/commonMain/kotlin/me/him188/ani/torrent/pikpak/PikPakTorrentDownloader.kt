/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.SessionStore
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.getQuota
import io.github.nihildigit.pikpak.remoteSize
import io.github.nihildigit.pikpak.resolveVariant
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
import kotlinx.coroutines.flow.flow
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
 * The engine turns a magnet into a cloud offline-download task, then reads the
 * resulting files over signed CDN links. Playback streams the ranges the read
 * position implies and writes nothing; a cache record downloads the file front
 * to back to disk, where its own length is the progress and there is no bitmap.
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

    /** Serialises cloud writes: bucket creation, eviction and task submission all touch the shared slot folder. */
    private val driveMutex = Mutex()

    private val sessionLock = Mutex()
    private val sessions = mutableMapOf<String, Deferred<PikPakSession>>()

    // Engine-wide, not per session: the bandwidth the cache downloads compete
    // for is one line, and the CDN client they share is one dispatcher.
    private val downloadScheduler = DownloadScheduler()

    @Volatile
    private var closed = false

    override val vendor: TorrentLibInfo = TorrentLibInfo(
        vendor = "PikPak",
        version = SDK_VERSION,
        supportsStreaming = true,
    )

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
    // Shared by every session's readers so a link resolved by pack warm-up
    // outlives the session that resolved it; see CachingVariantLinkSource.
    private val linkSource: VariantLinkSource = CachingVariantLinkSource(PikPakVariantLinkSource(::client))

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
        // await one creation instead of racing to submit two tasks.
        val deferred = sessionLock.withLock {
            sessions[sourceKey]?.takeIf { !it.isCancelled }
                ?: scope.async { createSession(uri, sourceKey, parentCoroutineContext) }
                    .also { sessions[sourceKey] = it }
        }
        return try {
            deferred.await()
        } catch (e: Throwable) {
            // Only a failed creation is forgotten. A waiter that was cancelled
            // (the user switched episode while the cloud was indexing) leaves
            // the deferred running in this scope, and dropping it here would
            // let the next start of the same magnet build a second session on
            // the same files.
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

        // Cold start used to disappear into a six-second stretch with no log
        // line and no request in it. Every phase below is timed so the next
        // report says which one grew.
        val started = TimeSource.Monotonic.markNow()
        val restored = resumeData.read()?.takeIf { it.files.isNotEmpty() && filesConsistent(saveDirectory, it) }
        val meta = when {
            restored == null -> cloudIndexer(uri, sourceKey).also {
                logger.info { "[pikpak] $sourceKey indexed from cloud in ${started.elapsedNow()}" }
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
                meta, fileMeta, sourceKey, saveDirectory, resumeData, parentCoroutineContext,
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
            onClosed = { closing -> sessionLock.withLock { sessions.remove(closing.sourceKey) } },
            parentCoroutineContext = parentCoroutineContext,
        ).also { session = it }
    }

    /**
     * Fills a partial listing in from the cloud, or keeps it as it is when the
     * cloud cannot be reached.
     *
     * A failure here is not fatal by design: the imported file's bytes are on
     * disk and playable with no link at all, and the next start tries again. It
     * would be fatal if this threw, because the migration's episode would then
     * be unplayable exactly while offline.
     */
    private suspend fun completeIndex(
        partial: PikPakTorrentMeta,
        uri: String,
        sourceKey: String,
        saveDirectory: SystemPath,
        resumeData: PikPakResumeData,
    ): PikPakTorrentMeta {
        val cloud = try {
            cloudIndexer(uri, sourceKey)
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            logger.warn(e) { "[pikpak] $sourceKey holds imported files only and could not be indexed; using them as is" }
            return partial
        }
        val (merged, relocations) = mergeImportedInto(cloud, partial).let { it.meta to it.relocations }
        for ((from, to) in relocations) {
            val source = saveDirectory.resolve(from)
            if (!source.exists()) continue
            val target = saveDirectory.resolve(to)
            target.path.parent?.inSystem?.createDirectories()
            source.moveTo(target)
            logger.info { "[pikpak] $sourceKey moved imported $from to its cloud path $to" }
        }
        // The merged result keeps local lengths, so it can disagree with the
        // disk only where the cloud listing does; there the cloud is the
        // fresher answer and re-indexing is exactly the repair.
        val result = if (filesConsistent(saveDirectory, merged)) merged else cloud
        resumeData.write(result)
        logger.info { "[pikpak] $sourceKey filled in from the cloud, ${result.files.size} file(s)" }
        return result
    }

    private fun buildEntry(
        meta: PikPakTorrentMeta,
        fileMeta: PikPakFileMeta,
        sourceKey: String,
        saveDirectory: SystemPath,
        resumeData: PikPakResumeData,
        parentCoroutineContext: CoroutineContext,
        onHandleCountChanged: suspend () -> Unit,
    ): PikPakFileEntry {
        // No file is created here. Playback writes nothing at all, and a cache
        // record's file appears when its downloader makes its first write.
        val reader = VariantReader(
            clientProvider = ::client,
            mediaId = fileMeta.mediaId,
            connectionBudget = config.value.effectiveConcurrency,
            initialFileId = fileMeta.fileId,
            linkSource = linkSource,
            onRelocated = { newFileId, newBucketId ->
                resumeData.updateFileId(fileMeta.pathInTorrent, newFileId, newBucketId)
            },
            relocate = { driveMutex.withLock { driveIndex.relocate(sourceKey, meta.uri, fileMeta.pathInTorrent) } },
        )

        return PikPakFileEntry(
            index = fileMeta.index,
            length = fileMeta.length,
            saveDirectory = saveDirectory,
            relativePath = fileMeta.pathInTorrent,
            torrentId = sourceKey,
            parentCoroutineContext = parentCoroutineContext,
            meta = fileMeta,
            reader = reader,
            concurrency = config.value.effectiveConcurrency,
            scheduler = downloadScheduler,
            onHandleCountChanged = onHandleCountChanged,
        )
    }

    /**
     * How a session's file listing is obtained when the disk does not have a
     * complete one. Only a test replaces it: standing up a fake drive any other
     * way means answering the SDK's HTTP calls, and the cloud listing is the one
     * thing the restore paths here branch on.
     */
    internal var cloudIndexer: suspend (uri: String, sourceKey: String) -> PikPakTorrentMeta =
        { uri, sourceKey -> indexFromCloud(uri, sourceKey) }

    private suspend fun indexFromCloud(uri: String, sourceKey: String): PikPakTorrentMeta =
        driveMutex.withLock {
            val client = client()
            client.login()

            val memoized = driveIndex.findBucket(sourceKey)
            // A memoized bucket id can name a folder that was deleted from
            // outside this process; listing it is the only way to find out.
            val listed: List<RemoteEntry>? = memoized?.let { id ->
                try {
                    driveIndex.listBucket(id)
                } catch (e: PikPakException) {
                    if (!isFolderGone(e)) throw e
                    logger.info { "[pikpak] bucket $sourceKey ($id) no longer exists; recreating" }
                    driveIndex.invalidate(sourceKey)
                    null
                }
            }
            val bucketId: String
            var remote: List<RemoteEntry>
            if (listed != null) {
                bucketId = checkNotNull(memoized)
                remote = listed
            } else {
                // Local files of an evicted bucket are deliberately kept: the
                // bytes stay valid, PikPak deduplicates by content, and a
                // resubmit yields the identical file.
                driveIndex.evict(
                    currentSourceKey = sourceKey,
                    queueLength = config.value.slotQueueLength,
                    protectedKeys = sessions.keys,
                )
                bucketId = driveIndex.createBucketOrRefreshSlot(sourceKey)
                remote = emptyList()
            }

            if (remote.isEmpty()) {
                logger.info { "[pikpak] bucket $sourceKey is empty, submitting ${uri.take(60)}" }
                driveIndex.submitAndAwait(bucketId, uri)
                remote = driveIndex.listBucket(bucketId)
            }
            val usable = remote.filter { it.sizeBytes > 0 }
            if (usable.isEmpty()) {
                throw PikPakTaskException("Bucket $sourceKey holds no non-empty file after the offline task finished")
            }

            val preference = config.value.variantPreference()
            // By path, not by name: a pack with a subfolder has files whose
            // bare names sort into an order that says nothing about where they
            // are, and the index a file gets is part of its identity above.
            val files = usable.sortedBy { it.path }.mapIndexed { index, entry ->
                val variant = resolveVariantFor(entry, preference)
                PikPakFileMeta(
                    index = index,
                    pathInTorrent = entry.path,
                    fileId = entry.fileId,
                    mediaId = variant.mediaId,
                    variantLabel = variant.label,
                    length = variant.length,
                )
            }

            PikPakTorrentMeta(
                uri = uri,
                sourceKey = sourceKey,
                name = driveIndex.bucketDisplayName(bucketId, fallback = sourceKey),
                bucketId = bucketId,
                files = files,
            )
        }

    private data class ResolvedFileVariant(val mediaId: String?, val label: String, val length: Long)

    /**
     * A transcode costs one extra `getFile` plus one one-byte range probe per
     * video, because PikPak does not carry a transcode's length in the file
     * metadata. The probe result is written to `meta.json` and never repeated.
     * With the original preferred, neither request happens.
     */
    private suspend fun resolveVariantFor(
        entry: RemoteEntry,
        preference: VariantPreference,
    ): ResolvedFileVariant {
        if (preference is VariantPreference.Original || !isVideoFile(entry.name)) {
            return ResolvedFileVariant(null, "Original", entry.sizeBytes)
        }
        return try {
            val client = client()
            val resolved = client.resolveVariant(entry.fileId, preference)
            if (resolved.isOrigin) {
                ResolvedFileVariant(null, resolved.label, entry.sizeBytes)
            } else {
                val size = resolved.sizeBytes ?: client.remoteSize(resolved.link.url)
                ResolvedFileVariant(resolved.mediaId, resolved.label, size)
            }
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            logger.warn(e) { "[pikpak] variant resolution failed for ${entry.name}, falling back to original" }
            ResolvedFileVariant(null, "Original", entry.sizeBytes)
        }
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
        const val SDK_VERSION = "0.5.2"
    }
}

class PikPakNotConfiguredException(message: String) : Exception(message)

@Serializable
internal data class PikPakEncodedTorrent(val uri: String)

private val encodedTorrentJson = Json { ignoreUnknownKeys = true }

internal fun decodeUri(data: EncodedTorrentInfo): String =
    encodedTorrentJson.decodeFromString(PikPakEncodedTorrent.serializer(), data.data.decodeToString()).uri

internal fun encodeUri(uri: String): ByteArray =
    encodedTorrentJson.encodeToString(PikPakEncodedTorrent.serializer(), PikPakEncodedTorrent(uri))
        .encodeToByteArray()

internal fun PikPakEngineConfig.variantPreference(): VariantPreference =
    if (prefersOriginal) VariantPreference.Original else VariantPreference.Resolution(variant)

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "ts", "m2ts", "webm", "rmvb", "iso")

internal fun isVideoFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

/**
 * The cloud listing [cloud], with what [local] knows about the files already on
 * disk carried over.
 *
 * The cloud decides which files exist, in what order and under which id. The
 * local entry decides the variant, its media id and its length, because those
 * three describe the bytes that are on disk: a directory continues with the
 * variant it recorded, whatever the current quality setting says.
 *
 * Paths are matched exactly first. A local entry the cloud does not list under
 * that path is then matched by bare name: the migration off the old HTTP path
 * imported files under their bare name, while the cloud keeps a pack's
 * subfolders, so the same file can be `01.mkv` locally and `specials/01.mkv`
 * in the cloud. Such a match is reported in [Merged.relocations] so the caller
 * can move the file; the entry itself is already under the cloud path. A bare
 * name that the cloud lists more than once, or not at all, is not guessed at:
 * the local entry is kept as an extra so its bytes stay playable and seedable.
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
        if (target == null || target.pathInTorrent in byPath) {
            extras += entry
        } else {
            byName[target.pathInTorrent] = entry
            relocations[entry.pathInTorrent] = target.pathInTorrent
        }
    }

    var nextIndex = cloud.files.size
    val files = cloud.files.map { remote ->
        val onDisk = byPath[remote.pathInTorrent] ?: byName[remote.pathInTorrent] ?: return@map remote
        remote.copy(
            mediaId = onDisk.mediaId,
            variantLabel = onDisk.variantLabel,
            length = onDisk.length,
        )
    } + extras.map { it.copy(index = nextIndex++) }
    return Merged(cloud.copy(indexed = true, files = files), relocations)
}

/** @param relocations local path to cloud path, for files the merge placed under a different path. */
internal class Merged(val meta: PikPakTorrentMeta, val relocations: Map<String, String>)

/**
 * Nothing recorded in `meta.json` is contradicted by what is on disk.
 *
 * A file that is absent is normal and not a reason to re-index: playback
 * writes nothing, so most entries of a restored session have no file at all.
 * A file longer than its recorded length is the one real inconsistency — it
 * means the recorded length describes a different variant — and re-indexing
 * from the cloud is the repair.
 */
internal fun filesConsistent(saveDirectory: SystemPath, meta: PikPakTorrentMeta): Boolean = meta.files.all {
    val path = saveDirectory.resolve(it.pathInTorrent)
    !path.exists() || path.length() <= it.length
}
