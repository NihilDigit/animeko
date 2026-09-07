/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import me.him188.ani.app.torrent.io.RandomAccessFile
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.length
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Downloads one remote file to disk, front to back, for a cache record.
 *
 * Separate from [RangeStreamInput] on purpose. Playback wants the bytes around
 * a read position and nothing else; a cache record wants the whole file and
 * does not care in what order it arrives. Serving both from one policy is what
 * the engine used to do, and it meant every playback session downloaded whole
 * files nobody had asked to keep.
 *
 * Bytes are written strictly in order, so the file's length on disk is the
 * progress: resuming means continuing from that length, and there is no
 * bitmap, no preallocation and no hole to reason about. [concurrency] blocks
 * are fetched at once and written when the round completes.
 *
 * The rejected alternative was a sliding window that writes each block the
 * moment it lands. It fetches marginally faster, because a slow block does not
 * hold up the ones behind it, and it costs the property above: the file then
 * has holes and needs its own record of which ranges are real.
 */
internal class SequentialDownloader(
    private val source: RangeSource,
    private val target: SystemPath,
    private val length: Long,
    private val concurrency: Int,
    private val logTag: String,
    parentCoroutineContext: CoroutineContext,
    private val blockSize: Long = BLOCK_SIZE,
    /** Between rounds of a run that is otherwise healthy. */
    private val roundRetryDelay: Duration = RETRY_DELAY,
    /** Between whole attempts, after enough rounds failed in a row to be worth waiting out. */
    private val attemptRetryDelay: Duration = RETRY_BACKOFF_AFTER_FAILURES,
    /** Decides when this downloader may fetch. Null means "no other downloader exists", as in tests. */
    private val slot: DownloadScheduler.Slot? = null,
) {
    private val logger = logger<SequentialDownloader>()
    private val scope = CoroutineScope(
        parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]) + Dispatchers.IO_,
    )

    private val _downloadedBytes = MutableStateFlow(existingLength())

    /** Bytes on disk. Equals [length] exactly when the file is complete. */
    val downloadedBytes: StateFlow<Long> = _downloadedBytes.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    /** Bytes the CDN handed over in this process. Monotonic; feeds the speed readout. */
    @Volatile
    var deliveredBytes: Long = 0
        private set

    @Volatile
    private var job: Job? = null

    val isComplete: Boolean get() = _downloadedBytes.value >= length

    /** Idempotent: a second call while a run is in flight does nothing. */
    fun start() {
        if (isComplete) return
        if (job?.isActive == true) return
        // Whatever the previous run published is about to be retried, so it is
        // not the state of this one.
        _error.value = null
        // The slot is left on every way out of the run, cancellation included,
        // or a stopped downloader would keep its place in the queue forever.
        job = scope.launch { try { run() } finally { slot?.leave() } }
    }

    /** Stops fetching. The bytes already written stay, and [start] continues from them. */
    fun stop() {
        // The reference is kept: cancellation does not close the file handle
        // synchronously, and deleteTarget() must be able to join the writer
        // that a preceding stop() cancelled, or the delete fails on Windows.
        job?.cancel()
    }

    /**
     * Throws the downloaded file away and returns this downloader to the state
     * it had before anything was fetched, so [start] works afterwards.
     *
     * Deleting used to go through [close], which cancels the scope for good.
     * The entry it belongs to stays in its session, so a later playback or
     * cache request of the deleted episode reused a downloader that could not
     * be started and a progress figure taken from a file that no longer
     * existed.
     *
     * The join is not optional: the run closes its [RandomAccessFile] in a
     * finally, and on Windows deleting a file another handle still has open
     * fails outright.
     */
    suspend fun deleteTarget() {
        val running = job
        job = null
        running?.cancelAndJoin()
        withContext(Dispatchers.IO_) { runCatching { if (target.exists()) target.delete() } }
        _downloadedBytes.value = 0
        _error.value = null
    }

    fun close() {
        scope.cancel()
    }

    private fun existingLength(): Long =
        runCatching { if (target.exists()) target.length().coerceAtMost(length) else 0L }.getOrDefault(0L)

    /**
     * Retries until the file is complete or the job is cancelled.
     *
     * Nothing above this catches anything: it is the whole body of a launched
     * coroutine, so a throw would reach the scope's uncaught handler and, on
     * Android, end the process. Opening the file, `setLength` and every write
     * are inside the loop for the same reason, and reopening the file is also
     * what an IO error on the handle itself needs.
     *
     * Giving up permanently was the previous behaviour, and it meant a cache
     * record interrupted by a lost network never resumed even once the network
     * came back: nothing restarted the run, and [start] saw a job that had
     * already finished.
     */
    private suspend fun run() {
        while (currentCoroutineContext().isActive && !isComplete) {
            try {
                downloadToEnd()
                _error.value = null
                logger.info { "[$logTag] cache download complete ($length bytes)" }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = e
                logger.warn(e) { "[$logTag] cache download attempt failed, retrying in $attemptRetryDelay" }
                delay(attemptRetryDelay)
            }
        }
    }

    /** One attempt over one file handle. Returns when the file is complete, throws otherwise. */
    private suspend fun downloadToEnd() {
        // A pack folder can have subfolders, so the target is not always a
        // direct child of the session directory, and "rw" does not create the
        // directories on the way to it.
        target.path.parent?.inSystem?.createDirectories()
        val file = RandomAccessFile(target, "rw")
        try {
            var written = _downloadedBytes.value
            // A file longer than its recorded length is the one inconsistency
            // that matters: it means the recorded length is wrong and the tail
            // is bytes of some other variant.
            if (file.length() > length) file.setLength(written)
            var failures = 0
            while (written < length) {
                // The turn is taken per round, not once per run: that is what
                // makes a downloader give way to a stream that opens while it
                // is already fetching, without abandoning the blocks in flight.
                slot?.awaitTurn()
                val blocks = planRound(written)
                val bytes = try {
                    fetchRound(blocks)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failures++
                    logger.warn(e) { "[$logTag] round at $written failed ($failures in a row)" }
                    // Past this the caller waits out a longer backoff and comes
                    // back on a fresh file handle, which is what an outage and
                    // a bad handle both need.
                    if (failures >= MAX_ROUND_FAILURES) throw e
                    delay(roundRetryDelay)
                    continue
                }
                failures = 0
                file.seek(written)
                for (block in bytes) {
                    file.write(block, 0, block.size)
                    written += block.size
                    _downloadedBytes.value = written
                }
                // A round that landed says whatever was published earlier is over.
                if (_error.value != null) _error.value = null
            }
        } finally {
            runCatching { file.close() }
        }
    }

    private fun planRound(from: Long): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var offset = from
        while (offset < length && out.size < concurrency) {
            val end = (offset + blockSize).coerceAtMost(length)
            out += offset until end
            offset = end
        }
        return out
    }

    private suspend fun fetchRound(blocks: List<LongRange>): List<ByteArray> = coroutineScope {
        blocks.map { block ->
            async { readRange(block.first, block.last - block.first + 1) }
        }.awaitAll()
    }

    private suspend fun readRange(offset: Long, size: Long): ByteArray {
        val buffer = ByteArray(size.toInt())
        var filled = 0
        source.read(offset, size, PRIORITY) { channel ->
            while (filled < buffer.size) {
                val n = channel.readAvailable(buffer, filled, buffer.size - filled)
                if (n == -1) break
                filled += n
            }
        }
        if (filled != buffer.size) {
            throw IOException("[$logTag] got $filled of ${buffer.size} bytes at $offset")
        }
        deliveredBytes += buffer.size
        return buffer
    }

    internal companion object {
        /**
         * Larger than the streaming block because nobody is waiting on any
         * particular one of these, so the only thing that matters is keeping
         * the per-request latency small next to the transfer.
         */
        const val BLOCK_SIZE: Long = 512L * 1024

        /** Below a playing stream's blocking reads on the same signed URL. */
        private const val PRIORITY = 1

        /** Rounds that may fail in a row before the attempt is abandoned and the file reopened. */
        const val MAX_ROUND_FAILURES = 3

        val RETRY_DELAY = 3.seconds

        /**
         * Waited out between attempts. Long because reaching it means the
         * source has been unreachable for three rounds, which is an outage
         * rather than a hiccup, and nothing is waiting on these bytes.
         */
        val RETRY_BACKOFF_AFTER_FAILURES = 30.seconds
    }
}
