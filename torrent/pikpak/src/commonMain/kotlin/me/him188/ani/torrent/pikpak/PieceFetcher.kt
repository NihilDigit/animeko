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
import kotlinx.atomicfu.AtomicInt
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
import kotlinx.coroutines.coroutineScope
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

// TorrentDownloadController chooses pieces; the scheduler arbitrates files across the engine.
// Only explicit cache requests write to disk, while playback uses HybridSeekableInput.
internal class PieceFetcher(
    private val source: RangeSource,
    private val file: SystemPath,
    private val pieces: MutablePieceList,
    private val totalLength: Long,
    private val concurrency: Int,
    private val logTag: String,
    private val onPieceDownloaded: (pieceIndex: Int) -> Unit,
    parentCoroutineContext: CoroutineContext,
    private val slot: DownloadScheduler.Slot? = null,
    private val retryDelay: Duration = RETRY_BACKOFF_AFTER_FAILURES,
    private val maxRequestBytes: Long = DEFAULT_MAX_REQUEST_BYTES,
) : PiecePriorities {
    private val logger = logger<PieceFetcher>()
    private val scope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]),
    )

    private val bitmap = PieceBitmap(PieceBitmap.pathFor(file), pieces.count)

    // Piece offsets may be torrent-relative; CDN ranges and the local file start at zero.
    private val fileOffsetBase = if (pieces.isEmpty()) 0L else with(pieces) { pieces.first().dataStartOffset }

    private val lock = SynchronizedObject()

    private val claimed = mutableSetOf<Int>()

    @Volatile
    private var work = Work(emptyList())

    private val workGeneration = MutableStateFlow(0)

    private val writeMutex = Mutex()

    @Volatile
    private var handle: RandomAccessFile? = null

    private val startStopMutex = Mutex()

    @Volatile
    private var workers: Job? = null

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private var lifecycleGeneration = 0L
    private var closed = false
    private val completedBytes = atomic(0L)

    private val _deliveredBytes = atomic(0L)

    val deliveredBytes: Long get() = _deliveredBytes.value

    val downloadedBytes: Long get() = completedBytes.value

    val isComplete: Boolean get() = downloadedBytes == totalLength

    init {
        val onDisk = bitmap.load()

        val currentLength = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)
        pieces.forEach { piece ->
            if (!onDisk.getOrElse(piece.indexInList) { false }) return@forEach
            if (piece.dataEndOffset - fileOffsetBase > currentLength) {
                bitmap.clear(piece.indexInList)
                return@forEach
            }
            piece.state = PieceState.FINISHED
            completedBytes.addAndGet(piece.size)
        }
    }

    override fun downloadOnly(highPriorityPieces: List<Int>, normalPriorityPieces: List<Int>) {
        work = Work(highPriorityPieces + normalPriorityPieces)
        workGeneration.update { it + 1 }
    }

    suspend fun start() {
        val generation = synchronized(lock) { lifecycleGeneration }
        startStopMutex.withLock {
            val previous = synchronized(lock) {
                check(!closed) { "PieceFetcher is closed" }
                workers
            }
            if (previous?.isActive == true) return

            // Old workers must release claims and scheduler ownership before a new run starts.
            previous?.join()
            withContext(Dispatchers.IO_) {
                file.path.parent?.inSystem?.createDirectories()
                val opened = handle ?: RandomAccessFile(file, "rw").also { handle = it }
                if (opened.length() < totalLength) opened.setLength(totalLength)
            }
            synchronized(lock) {
                if (closed || generation != lifecycleGeneration) return
                val idleWorkers = atomic(0)
                workers = scope.launch {
                    try {
                        coroutineScope {
                            launch { flushLoop() }
                            repeat(concurrency) { launch { worker(idleWorkers) } }
                        }
                    } finally {
                        slot?.leave()
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            lifecycleGeneration++
            workers?.cancel()
        }
    }

    suspend fun close() {
        synchronized(lock) { closed = true }
        stop()
        withContext(NonCancellable) {
            startStopMutex.withLock {
                workers?.cancelAndJoin()
                workers = null
                try {
                    bitmap.flush()
                } finally {
                    closeHandle()
                    scope.cancel()
                }
            }
        }
    }

    suspend fun deleteTarget() {
        stop()
        withContext(NonCancellable) {
            startStopMutex.withLock {
                workers?.cancelAndJoin()
                workers = null
                closeHandle()
                withContext(Dispatchers.IO_) { if (file.exists()) file.delete() }
                bitmap.delete()
                pieces.forEach { it.state = PieceState.READY }
                completedBytes.value = 0L
                _error.value = null
            }
        }
    }

    private suspend fun closeHandle() = withContext(Dispatchers.IO_) {
        val opened = handle
        handle = null
        runCatching { opened?.close() }
        Unit
    }

    private suspend fun worker(idleWorkers: AtomicInt) {
        while (currentCoroutineContext().isActive) {
            try {
                val generation = workGeneration.value
                val batch = claimBatch()
                if (batch == null) {
                    whileIdle(idleWorkers) { workGeneration.first { it != generation } }
                    continue
                }

                try {
                    slot?.awaitTurn()
                    fetch(batch)
                } finally {
                    synchronized(lock) { batch.pieceIndices.forEach { claimed.remove(it) } }

                    workGeneration.update { it + 1 }
                }
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = e
                logger.warn(e) { "[$logTag] piece fetch failed, retrying in $retryDelay" }

                // A worker in backoff makes no progress. Holding the scheduler slot through the delay
                // would starve every other file for the whole backoff; awaitTurn queues again on retry.
                whileIdle(idleWorkers) { delay(retryDelay) }
            }
        }
    }

    // The slot belongs to the fetcher, not to a worker, so it is released only on the all-idle edge.
    private suspend fun whileIdle(idleWorkers: AtomicInt, block: suspend () -> Unit) {
        if (idleWorkers.incrementAndGet() == concurrency) slot?.leave()
        try {
            block()
        } finally {
            idleWorkers.decrementAndGet()
        }
    }

    private suspend fun fetch(batch: Batch) {
        val bytes = source.readBytes(batch.fileOffset, batch.byteCount)
        if (bytes.size.toLong() != batch.byteCount) {
            throw IOException(
                "[$logTag] short read at ${batch.fileOffset}: got ${bytes.size} of ${batch.byteCount} bytes",
            )
        }
        withContext(Dispatchers.IO_) {
            writeMutex.withLock {
                val opened = handle ?: throw IOException("[$logTag] the data file was closed while fetching")
                opened.seek(batch.fileOffset)
                opened.write(bytes, 0, bytes.size)

                // Publish completion only after separate native playback handles can see the bytes.
                opened.flush()
            }
        }
        _deliveredBytes.addAndGet(batch.byteCount)

        with(pieces) {
            for (pieceIndex in batch.pieceIndices) {
                val piece = pieces.getByPieceIndex(pieceIndex)
                piece.state = PieceState.FINISHED
                bitmap.set(piece.indexInList)
                completedBytes.addAndGet(piece.size)
            }
        }

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

    private fun claimBatch(): Batch? = synchronized(lock) {
        val wanted = work
        val first = wanted.ordered.firstOrNull { isFetchable(it) }
        if (first == null) {
            null
        } else {
            val indices = mutableListOf(first)
            var byteCount = sizeOf(first)
            var next = first + 1
            while (next in wanted.members && isFetchable(next)) {
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

    private fun isFetchable(pieceIndex: Int): Boolean =
        pieces.containsAbsolutePieceIndex(pieceIndex) &&
                pieceIndex !in claimed &&
                stateOf(pieceIndex) != PieceState.FINISHED

    private fun stateOf(pieceIndex: Int): PieceState = with(pieces) { pieces.getByPieceIndex(pieceIndex).state }

    private fun sizeOf(pieceIndex: Int): Long = with(pieces) { pieces.getByPieceIndex(pieceIndex).size }

    private fun fileOffsetOf(pieceIndex: Int): Long =
        with(pieces) { pieces.getByPieceIndex(pieceIndex).dataStartOffset } - fileOffsetBase

    private class Work(val ordered: List<Int>) {
        val members = ordered.toHashSet()
    }

    private class Batch(
        val fileOffset: Long,
        val byteCount: Long,
        val pieceIndices: List<Int>,
    )

    private companion object {
        val FLUSH_INTERVAL = 2.seconds

        val RETRY_BACKOFF_AFTER_FAILURES = 30.seconds

        // Bound how long a background request can hold a connection needed by playback.
        const val DEFAULT_MAX_REQUEST_BYTES: Long = 1024L * 1024
    }
}
