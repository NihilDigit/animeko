/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.torrent.api.files.AbstractTorrentFileEntry
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.app.torrent.api.pieces.MutablePieceList
import me.him188.ani.app.torrent.api.pieces.PieceList
import me.him188.ani.app.torrent.api.pieces.PieceState
import me.him188.ani.app.torrent.api.pieces.count
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.info
import org.openani.mediamp.io.SeekableInput
import kotlin.coroutines.CoroutineContext

/**
 * One file of a PikPak-backed torrent.
 *
 * The engine deliberately has no download policy of its own. Playback opens a
 * [RangeStreamInput], which fetches what the player reads and keeps nothing on
 * disk; a cache record opens a handle at a priority below HIGH, which starts a
 * [SequentialDownloader] that writes the whole file front to back. Nothing
 * else fetches anything.
 *
 * The piece list is a fiction maintained for the app: the progress bar and the
 * cache engine both read piece states, and there is no other way to tell them
 * how much of a file is on disk. Its states are derived from the downloaded
 * length, so a stream-only entry has none finished and a completed cache has
 * them all.
 */
internal class PikPakFileEntry(
    index: Int,
    length: Long,
    private val saveDirectory: SystemPath,
    relativePath: String,
    torrentId: String,
    parentCoroutineContext: CoroutineContext,
    val meta: PikPakFileMeta,
    private val reader: EntrySource,
    private val concurrency: Int,
    scheduler: DownloadScheduler,
    private val onHandleCountChanged: suspend () -> Unit,
) : AbstractTorrentFileEntry(
    index = index,
    length = length,
    saveDirectory = saveDirectory,
    relativePath = relativePath,
    torrentId = torrentId,
    isDebug = false,
    parentCoroutineContext = parentCoroutineContext,
), CloudReadiness {
    override val supportsStreaming: Boolean get() = true

    /**
     * A complete file needs nothing from the cloud. Otherwise the first read
     * needs a signed link, and resolving it is where an account that stopped
     * being valid shows up; awaiting it here costs nothing the first read
     * would not have paid, and puts the failure where a caller can still act.
     */
    override suspend fun ensureCloudReady() {
        if (!isComplete) reader.prewarm()
    }

    override val pieces: MutablePieceList = PieceList.create(totalSize = length, pieceSize = PIECE_SIZE)

    private val dataPath: SystemPath get() = saveDirectory.resolve(relativePath)

    // The base class takes the parent context but does not keep it, and the
    // stream inputs need one of their own: they outlive no handle and must not
    // be cancelled when a priority changes.
    private val streamContext: CoroutineContext = parentCoroutineContext + Dispatchers.IO_

    /** This entry's standing in the engine-wide download queue, and where its streams are reported. */
    private val slot = scheduler.newSlot("$torrentId/$fileName")

    private val downloader = SequentialDownloader(
        source = reader,
        target = dataPath,
        length = length,
        concurrency = concurrency,
        logTag = "$torrentId/$fileName",
        parentCoroutineContext = parentCoroutineContext,
        slot = slot,
    )

    /** Bytes handed over by the CDN since this session started. Monotonic; the basis of the speed readout. */
    private val _streamDeliveredBytes = MutableStateFlow(0L)

    val deliveredBytes: Long get() = _streamDeliveredBytes.value + downloader.deliveredBytes

    val error: StateFlow<Throwable?> = downloader.error

    private val openHandles = MutableStateFlow(0)
    val hasOpenHandles: Boolean get() = openHandles.value > 0

    val downloadedBytes: Long get() = downloader.downloadedBytes.value

    init {
        applyPieceStates(downloader.downloadedBytes.value)
        scope.launch { downloader.downloadedBytes.collect { applyPieceStates(it) } }
    }

    override val fileStats: Flow<TorrentFileEntry.Stats> = downloader.downloadedBytes.map { downloaded ->
        TorrentFileEntry.Stats(
            downloadedBytes = downloaded.coerceAtMost(length),
            downloadProgress = if (length == 0L) 1f else (downloaded.toFloat() / length).coerceIn(0f, 1f),
        )
    }

    /**
     * Whether a handle wants the file on disk rather than just streamed.
     *
     * The player asks HIGH and means "serve me what I read"; a cache record
     * asks NORMAL and means "keep all of it". Anything below HIGH therefore
     * starts the downloader and HIGH alone starts nothing, because the input
     * the player opens does its own fetching.
     */
    private val wantsWholeFile: Boolean
        get() = priorityRequests.values.any { it != null && it != FilePriority.IGNORE && it < FilePriority.HIGH }

    inner class EntryHandle : AbstractTorrentFileHandle() {
        override val entry get() = this@PikPakFileEntry

        override fun resumeImpl(priority: FilePriority) {
            if (wantsWholeFile) downloader.start()
            // A complete file never needs a link, so warming one would be pure
            // cost. It is the common case for a cache imported from the old
            // HTTP path, where resolving a link means relisting the bucket or
            // resubmitting the source.
            if (!isComplete) prewarmLink()
        }

        override suspend fun closeImpl() {
            openHandles.update { (it - 1).coerceAtLeast(0) }
            onHandleCountChanged()
        }

        override suspend fun closeAndDelete() {
            // close() first, so nothing is fetching while the file goes away:
            // dropping the last handle of the session closes the session and
            // with it this entry, and deleteFiles() has to delete the file in
            // that case too. It does, because cancelling and joining a job
            // whose scope is already cancelled returns at once.
            close()
            deleteFiles()
        }
    }

    override fun createHandle(): TorrentFileHandle {
        openHandles.update { it + 1 }
        return EntryHandle()
    }

    override fun updatePriority() {
        val priority = requestingPriority
        if (wantsWholeFile) downloader.start() else downloader.stop()
        logger.info { "[$torrentId] $fileName priority -> $priority" }
    }

    override suspend fun createInput(awaitCoroutineContext: CoroutineContext): SeekableInput =
        withContext(Dispatchers.IO_) {
            if (isComplete) {
                // Reads no bytes over the line, so it is not reported to the
                // scheduler: playing a finished file must not pause anyone.
                FileSeekableInput(dataPath, length)
            } else {
                StreamInput()
            }
        }

    /**
     * Wraps [RangeStreamInput] so the bytes it moves reach the session's speed
     * readout. The entry cannot hold the input itself: several can be open at
     * once and each has its own lifetime.
     */
    private inner class StreamInput : SeekableInput {
        private val delegate = RangeStreamInput(
            source = reader,
            size = length,
            concurrency = concurrency,
            logTag = "$torrentId/$fileName",
            parentCoroutineContext = streamContext,
        )

        private var reported = 0L
        private var registered = true

        init {
            slot.openStream()
        }

        override val position: Long get() = delegate.position
        override val bytesRemaining: Long get() = delegate.bytesRemaining
        override val size: Long get() = delegate.size

        override fun seekTo(position: Long) = delegate.seekTo(position)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length).also { publishDelivered() }

        override fun close() {
            delegate.close()
            publishDelivered()
            // A player may close an input twice; the count must not go down
            // twice for one stream.
            if (registered) {
                registered = false
                slot.closeStream()
            }
        }

        private fun publishDelivered() {
            val now = delegate.deliveredBytes
            val added = now - reported
            if (added <= 0) return
            reported = now
            _streamDeliveredBytes.update { it + added }
        }
    }

    /** Whether the whole file is on disk. */
    val isComplete: Boolean
        get() = length > 0 && downloader.downloadedBytes.value >= length

    /**
     * Resolves the signed link now, so the first read does not pay for it.
     * This is all pack warm-up does: no bytes are fetched ahead.
     */
    fun prewarmLink() {
        scope.launch {
            runCatching { reader.prewarm() }
                .onFailure { logger.info { "[$torrentId] prewarm of $fileName failed (non-fatal): ${it.message}" } }
        }
    }

    /** Resolves the signed link and suspends until it is in hand. */
    suspend fun prewarmLinkAndWait() {
        reader.prewarm()
    }

    // Streaming writes nothing, so for most entries there is no file at all
    // and null is the honest answer. The base implementation would report the
    // path of a file that does not exist.
    override fun resolveFileMaybeEmptyOrNull(): SystemPath? = dataPath.takeIf { it.exists() }

    /**
     * Mirrors the downloaded length into the piece list.
     *
     * Bytes are written strictly in order, so the finished pieces are exactly
     * a prefix. Nothing here drives a fetch: the states are read by the app's
     * progress bar and by the cache engine's completion check, and by nothing
     * inside this engine.
     */
    private fun applyPieceStates(downloaded: Long) {
        with(pieces) {
            for (i in 0 until pieces.count) {
                val piece = pieces.createPieceByListIndexUnsafe(i)
                val wanted = if (piece.dataEndOffset <= downloaded) PieceState.FINISHED else PieceState.READY
                if (piece.state != wanted) piece.state = wanted
            }
        }
    }

    fun close() {
        downloader.close()
        reader.close()
        slot.release()
        // The init block's collector runs in the entry scope, which is a child
        // of the storage's long-lived context for entries built during cache
        // restoration; left running it would keep this entry, its piece list
        // and the session callback alive until the app exits.
        scope.cancel()
    }

    /**
     * Deletes this entry's bytes without retiring the entry.
     *
     * It used to call [close], which is the shutdown path: the downloader's
     * scope was cancelled for good, the reader closed and the scheduler slot
     * released. The entry itself stays in its session, so as long as another
     * episode of the same pack held a handle the session survived and the next
     * request for this file reused a downloader that could not be started, a
     * reader that threw, and a downloaded length taken from a file that had
     * been deleted.
     */
    private suspend fun deleteFiles() {
        downloader.deleteTarget()
    }

    companion object {
        /**
         * Granularity of the synthesised piece list. Not a transport
         * parameter: nothing fetches a piece. It only decides how coarse the
         * progress bar is.
         */
        const val PIECE_SIZE: Long = 512L * 1024
    }
}
