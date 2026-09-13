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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

// A demuxer cannot build a seek table without the container's index, and mkv writes that index last:
// the player reads the header, jumps to the end for the Cues, then jumps back, all before the first
// frame. Both jumps are free on a local file and cost a round trip each here, so fetch the tail
// while the header is being read and serve those reads from memory.
//
// Deliberately not routed through PikPakStreamReader: its read-ahead only claims blocks inside the
// window around the read position, and moving that position with seekTo would cancel the header
// fetches already in flight — the very cost this is meant to remove.
internal class TailPrefetch(
    private val name: String,
    private val source: RangeSource,
    size: Long,
    parentCoroutineContext: CoroutineContext,
    tailBytes: Long = TAIL_BYTES,
    private val blockSize: Long = BLOCK_SIZE,
    concurrency: Int = CONCURRENCY,
) {
    private val logger = logger<TailPrefetch>()

    private val lock = SynchronizedObject()

    /** First offset this holds. Reads below it are none of its business. */
    val start: Long = (size - tailBytes).coerceAtLeast(0)

    private val end: Long = size

    private val blocks = arrayOfNulls<ByteArray>(((end - start + blockSize - 1) / blockSize).toInt())

    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]))

    private var remaining = blocks.size

    init {
        val mark = TimeSource.Monotonic.markNow()
        // Last block first: both measured players' first tail read landed within 2 KiB of the end,
        // so fetching backwards makes the bytes most likely to be wanted the first ones to arrive.
        val pending = ArrayDeque(blocks.indices.reversed().toList())
        repeat(concurrency) {
            scope.launch {
                while (true) {
                    val index = synchronized(lock) { pending.removeFirstOrNull() } ?: break
                    val offset = start + index * blockSize
                    val length = minOf(blockSize, end - offset)
                    try {
                        val bytes = source.readBytes(offset, length, PREFETCH_PRIORITY)
                        val left = synchronized(lock) {
                            blocks[index] = bytes
                            --remaining
                        }
                        if (left == 0) {
                            logger.info { "[$name] tail prefetch of ${end - start} B done in ${mark.elapsedNow()}" }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // The read path falls back to the stream, so a failure here costs nothing
                        // beyond the round trip it already spent.
                        logger.warn(e) { "[$name] tail prefetch stopped at $offset" }
                        break
                    }
                }
            }
        }
    }

    /**
     * Copies what it already holds for [from], or returns 0 when it holds nothing there yet.
     *
     * Never waits: a block that has not arrived is served by the stream instead, so a slow prefetch
     * can only cost bandwidth, never latency.
     */
    fun read(from: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (from < start || from >= end) return 0
        val index = ((from - start) / blockSize).toInt()
        val block = synchronized(lock) { blocks.getOrNull(index) } ?: return 0
        val inBlock = (from - (start + index * blockSize)).toInt()
        if (inBlock >= block.size) return 0
        val count = minOf(length, block.size - inBlock)
        block.copyInto(buffer, offset, inBlock, inBlock + count)
        return count
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        // Measured: Cues began 47 KB before the end of a 307 MB file, Tags 1.3 KB before it, and the
        // first tail read of a 694 MB file landed 2 KB before it. Cues grow with the number of
        // clusters, so this leaves room for a long episode without holding a pointless amount.
        const val TAIL_BYTES: Long = 2L * 1024 * 1024

        const val BLOCK_SIZE: Long = 256L * 1024

        // Two of the file's eight connection slots. The read at the playback head outranks these and
        // is never queued behind them; what they displace is the tail end of the read-ahead.
        const val CONCURRENCY = 2

        // Below the SDK's read-ahead priority, which is 10, and far below its blocking read at 100.
        const val PREFETCH_PRIORITY = 5
    }
}
