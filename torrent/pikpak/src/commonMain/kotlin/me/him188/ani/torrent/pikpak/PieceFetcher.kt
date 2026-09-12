/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.RangeSource
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import me.him188.ani.app.torrent.api.pieces.MutablePieceList
import me.him188.ani.app.torrent.api.pieces.PieceState
import me.him188.ani.app.torrent.api.pieces.PiecePriorities
import me.him188.ani.app.torrent.api.pieces.containsAbsolutePieceIndex
import me.him188.ani.app.torrent.api.pieces.count
import me.him188.ani.app.torrent.api.pieces.first
import me.him188.ani.app.torrent.api.pieces.forEach
import me.him188.ani.app.torrent.api.pieces.isEmpty
import me.him188.ani.app.torrent.api.pieces.sumOf
import me.him188.ani.app.torrent.io.RandomAccessFile
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.length
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Fetches the pieces the app asks for, and nothing else.
 *
 * PikPak is treated as a swarm that holds every piece, so there is no peer selection and no
 * rarity: [TorrentDownloadController][me.him188.ani.app.torrent.api.pieces.TorrentDownloadController]
 * decides what to want through [downloadOnly] and this class just keeps [concurrency] requests
 * in flight. Measured 2026-09-12, neither request shape nor request size moves throughput —
 * the connection count is the whole of it, and PikPak answers the 9th with 503.
 */
internal class PieceFetcher(
    private val source: RangeSource,
    private val file: SystemPath,
    private val pieces: MutablePieceList,
    private val totalLength: Long,
    private val concurrency: Int,
    private val logTag: String,
    private val onPieceDownloaded: (pieceIndex: Int) -> Unit,
    parentCoroutineContext: CoroutineContext,
    private val retryDelay: Duration = RETRY_BACKOFF_AFTER_FAILURES,
    private val maxRequestBytes: Long = 4L * 1024 * 1024,
) : PiecePriorities {
    private val logger = logger<PieceFetcher>()
    private val scope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]) + Dispatchers.IO_,
    )

    private val bitmap = PieceBitmap(PieceBitmap.pathFor(file), pieces.count)

    /**
     * Data offset of byte 0 of [file]. A piece list can be a slice of a multi-file torrent, in
     * which case its offsets are counted from the start of the torrent, not of this file.
     */
    private val fileOffsetBase = if (pieces.isEmpty()) 0L else with(pieces) { pieces.first().dataStartOffset }

    private val lock = SynchronizedObject()

    /** Piece indices some worker is already fetching. Guarded by [lock]. */
    private val claimed = mutableSetOf<Int>()

    /** High priority first; the order is the priority, as [PiecePriorities] documents. */
    @Volatile
    private var workList: List<Int> = emptyList()

    /** Bumped by [downloadOnly] so workers that found nothing to do can wait instead of spinning. */
    private val workGeneration = MutableStateFlow(0)

    /** [RandomAccessFile] is not thread safe and seek+write is two steps. */
    private val writeMutex = Mutex()

    @Volatile
    private var handle: RandomAccessFile? = null

    private val startStopMutex = Mutex()

    @Volatile
    private var workers: Job? = null

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _deliveredBytes = atomic(0L)

    /**
     * Bytes this process pulled from the CDN, monotonic. Feeds the speed readout, so unlike
     * [downloadedBytes] it must survive [deleteTarget] without going back.
     */
    val deliveredBytes: Long get() = _deliveredBytes.value

    val downloadedBytes: Long
        get() = pieces.sumOf { if (it.state == PieceState.FINISHED) it.size else 0L }

    val isComplete: Boolean
        get() {
            pieces.forEach { if (it.state != PieceState.FINISHED) return false }
            return true
        }

    init {
        val onDisk = bitmap.load()
        // A file shorter than totalLength was truncated by something outside this engine — a disk
        // repair, a partial copy — because start() preallocates before any byte is fetched, so
        // "short" is never a normal mid-download state. Believing the bitmap over the file here
        // would report those pieces complete and the player would read zeros.
        val currentLength = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)
        pieces.forEach { piece ->
            if (!onDisk.getOrElse(piece.indexInList) { false }) return@forEach
            if (piece.dataEndOffset - fileOffsetBase > currentLength) {
                bitmap.clear(piece.indexInList)
                return@forEach
            }
            piece.state = PieceState.FINISHED
        }
    }

    override fun downloadOnly(highPriorityPieces: List<Int>, normalPriorityPieces: List<Int>) {
        // Called while TorrentDownloadController holds its own lock, so this may neither
        // suspend nor block.
        workList = highPriorityPieces + normalPriorityPieces
        workGeneration.update { it + 1 }
    }

    /**
     * Idempotent. Returns once the file covers every piece: playback opens
     * [TorrentInput][me.him188.ani.app.torrent.io.TorrentInput] over it right after, and that
     * rejects a file shorter than the pieces it is given.
     */
    suspend fun start() {
        startStopMutex.withLock {
            if (workers?.isActive == true) return
            withContext(Dispatchers.IO_) {
                // A pathInTorrent can carry a subfolder and only the session root
                // exists; RandomAccessFile does not create one, so a season pack
                // laid out in folders would fail here and retry forever.
                file.path.parent?.inSystem?.createDirectories()
                val opened = handle ?: RandomAccessFile(file, "rw").also { handle = it }
                if (opened.length() < totalLength) opened.setLength(totalLength)
            }
            workers = scope.launch {
                launch { flushLoop() }
                repeat(concurrency) { launch { worker() } }
            }
        }
    }

    /** Stops fetching. The bytes and the bitmap stay, so [start] resumes from them. */
    fun stop() {
        workers?.cancel()
    }

    suspend fun close() {
        workers?.cancelAndJoin()
        workers = null
        withContext(NonCancellable) {
            bitmap.flush()
            closeHandle()
        }
        scope.cancel()
    }

    /**
     * Throws the downloaded bytes away and goes back to having fetched nothing, [start] still
     * usable afterwards.
     *
     * Separate from [close] because that is the shutdown path: once the scope is cancelled it
     * does not come back, while the entry deleting its data is still in the session and can be
     * asked for the same episode again.
     */
    suspend fun deleteTarget() {
        val running = workers
        workers = null
        // Joining is not politeness: on Windows the delete fails while any handle is still open,
        // and a cancelled worker closes nothing by itself.
        running?.cancelAndJoin()
        withContext(NonCancellable) {
            closeHandle()
            withContext(Dispatchers.IO_) { runCatching { if (file.exists()) file.delete() } }
            bitmap.delete()
        }
        synchronized(lock) { claimed.clear() }
        pieces.forEach { it.state = PieceState.READY }
        _error.value = null
    }

    private suspend fun closeHandle() = withContext(Dispatchers.IO_) {
        val opened = handle
        handle = null
        runCatching { opened?.close() }
        Unit
    }

    /**
     * The entire body of a launched coroutine, so nothing may escape it: on Android an uncaught
     * exception reaches the scope's handler and ends the process.
     */
    private suspend fun worker() {
        while (currentCoroutineContext().isActive) {
            try {
                val generation = workGeneration.value
                val batch = claimBatch()
                if (batch == null) {
                    workGeneration.first { it != generation }
                    continue
                }
                try {
                    fetch(batch)
                } finally {
                    synchronized(lock) { claimed.removeAll(batch.pieceIndices.toSet()) }
                    // A batch released after a failure is fetchable again by anyone; without
                    // this the workers waiting for the next downloadOnly sleep through it and
                    // only the worker that failed retries it, 30 seconds later.
                    workGeneration.update { it + 1 }
                }
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = e
                logger.warn(e) { "[$logTag] piece fetch failed, retrying in $retryDelay" }
                // Long on purpose: the SDK has already used up its own retries before it gets
                // here, so this is a fault rather than a flicker.
                delay(retryDelay)
            }
        }
    }

    private suspend fun fetch(batch: Batch) {
        val bytes = source.readBytes(batch.fileOffset, batch.byteCount)
        if (bytes.size.toLong() != batch.byteCount) {
            throw IOException(
                "[$logTag] short read at ${batch.fileOffset}: got ${bytes.size} of ${batch.byteCount} bytes",
            )
        }
        writeMutex.withLock {
            val opened = handle ?: throw IOException("[$logTag] the data file was closed while fetching")
            opened.seek(batch.fileOffset)
            opened.write(bytes, 0, bytes.size)
            // Before the pieces below are published, not after: on native the
            // write is buffered, and a reader on its own handle -- playback,
            // reading what this download has already written -- would see the
            // preallocated zeros for a piece already marked FINISHED. A handle
            // that stays open for the life of the download never flushes on its
            // own, so the last partial piece could sit in the buffer for good.
            opened.flush()
        }
        _deliveredBytes.addAndGet(batch.byteCount)

        with(pieces) {
            for (pieceIndex in batch.pieceIndices) {
                val piece = pieces.getByPieceIndex(pieceIndex)
                piece.state = PieceState.FINISHED
                bitmap.set(piece.indexInList)
            }
        }
        // Feeds TorrentDownloadController.onPieceDownloaded, which is the only thing that moves
        // its window: skip it and the download stops after the first window.
        for (pieceIndex in batch.pieceIndices) onPieceDownloaded(pieceIndex)
    }

    private suspend fun flushLoop() {
        while (currentCoroutineContext().isActive) {
            delay(FLUSH_INTERVAL)
            try {
                bitmap.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "[$logTag] failed to persist the piece bitmap" }
            }
        }
    }

    /**
     * Takes the first wanted piece nobody holds, then extends the request over the pieces
     * immediately after it that are also wanted, free and unfinished.
     *
     * One request for several pieces is not about throughput — that is bounded by the connection
     * count either way — but about not spending one of the eight connections on 512 KiB.
     */
    private fun claimBatch(): Batch? = synchronized(lock) {
        val wanted = workList
        val first = wanted.firstOrNull { isFetchable(it) }
        if (first == null) {
            null
        } else {
            val wantedSet = wanted.toHashSet()
            val indices = mutableListOf(first)
            var byteCount = sizeOf(first)
            var next = first + 1
            while (next in wantedSet && isFetchable(next)) {
                val size = sizeOf(next)
                if (byteCount + size > maxRequestBytes) break
                indices.add(next)
                byteCount += size
                next++
            }
            claimed.addAll(indices)
            Batch(fileOffsetOf(first), byteCount, indices)
        }
    }

    /** Must be called under [lock]. */
    private fun isFetchable(pieceIndex: Int): Boolean =
        pieces.containsAbsolutePieceIndex(pieceIndex) &&
                pieceIndex !in claimed &&
                stateOf(pieceIndex) != PieceState.FINISHED

    private fun stateOf(pieceIndex: Int): PieceState = with(pieces) { pieces.getByPieceIndex(pieceIndex).state }

    private fun sizeOf(pieceIndex: Int): Long = with(pieces) { pieces.getByPieceIndex(pieceIndex).size }

    private fun fileOffsetOf(pieceIndex: Int): Long =
        with(pieces) { pieces.getByPieceIndex(pieceIndex).dataStartOffset } - fileOffsetBase

    private class Batch(
        val fileOffset: Long,
        val byteCount: Long,
        val pieceIndices: List<Int>,
    )

    private companion object {
        /**
         * Losing this much to a crash costs a few pieces re-fetched, which beats a write per
         * piece.
         */
        val FLUSH_INTERVAL = 2.seconds

        val RETRY_BACKOFF_AFTER_FAILURES = 30.seconds
    }
}
