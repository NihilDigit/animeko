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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.him188.ani.utils.coroutines.IO_
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The transport is a scheduler around range requests, so what these check is
 * the schedule: which ranges are asked for, how many at once, and what a seek
 * does to the ones already running. Correctness of the bytes is checked too,
 * because an off-by-one in the slot arithmetic would show up nowhere else
 * until playback was already corrupt.
 */
class RangeStreamInputTest {
    private val unit = 64L * 1024

    private fun payload(size: Int) = ByteArray(size) { (it * 31 % 251).toByte() }

    private fun input(
        source: FakeRangeSource,
        size: Long,
        concurrency: Int = 4,
        readAhead: Long = 1L * 1024 * 1024,
        cap: Long = 1L * 1024 * 1024,
        wideThreshold: Long = 256L * 1024,
    ) = RangeStreamInput(
        source = source,
        size = size,
        concurrency = concurrency,
        logTag = "test",
        parentCoroutineContext = Dispatchers.IO_,
        unitSize = unit,
        readAheadBytes = readAhead,
        memoryCapBytes = cap,
        wideBlockThresholdBytes = wideThreshold,
    )

    private fun readAll(input: RangeStreamInput, chunk: Int): ByteArray {
        val out = ByteArray(input.size.toInt())
        var filled = 0
        while (filled < out.size) {
            val n = input.read(out, filled, minOf(chunk, out.size - filled))
            if (n == -1) break
            filled += n
        }
        assertEquals(out.size, filled, "the stream ended early")
        return out
    }

    @Test
    fun `a sequential read returns the file, several requests at a time`() {
        val content = payload(1024 * 1024 + 12345)
        val source = FakeRangeSource(content)
        val input = input(source, content.size.toLong())

        try {
            assertContentEquals(content, readAll(input, chunk = 8 * 1024))
        } finally {
            input.close()
        }

        assertTrue(
            source.peakConcurrency > 1,
            "the whole point of the transport is parallel range requests, saw ${source.peakConcurrency}",
        )
        assertTrue(source.peakConcurrency <= 4, "never above the connection budget")
    }

    @Test
    fun `the last block stops at the end of the file`() {
        // A file whose length is not a multiple of the block size is where a
        // range that runs past the end would show up: the CDN answers it with
        // fewer bytes and the block would look truncated.
        val content = payload((unit * 2 + 7).toInt())
        val source = FakeRangeSource(content)
        val input = input(source, content.size.toLong())

        try {
            assertContentEquals(content, readAll(input, chunk = 4096))
        } finally {
            input.close()
        }

        assertTrue(
            source.requests.all { it.start + it.length <= content.size },
            "a request ran past the end of the file: ${source.requests}",
        )
    }

    @Test
    fun `a seek cancels the blocks it left behind and restarts at one block`() {
        val content = payload(8 * 1024 * 1024)
        // Long enough that nothing completes on its own during the test, so
        // what the assertions see is the schedule and not a race with it.
        val source = FakeRangeSource(content, latency = 30.seconds)
        val input = input(source, content.size.toLong())

        try {
            input.seekTo(0)
            waitUntil("the first blocks are in flight") { source.requests.size >= 4 }
            val before = source.requests.size

            val target = 6L * 1024 * 1024
            input.seekTo(target)
            waitUntil("the abandoned blocks are cancelled") { source.cancelled.size >= before }

            assertTrue(
                source.cancelled.all { it.start < target },
                "only the blocks the new position does not want may be cancelled",
            )
            val afterSeek = source.requests.drop(before)
            assertTrue(afterSeek.isNotEmpty(), "the seek issued no request")
            assertTrue(
                afterSeek.all { it.start >= target },
                "a request behind the new position survived the seek: $afterSeek",
            )
            // Which of the freed workers records its request first is a race,
            // so the block at the seek target is asserted to be among them
            // rather than to be the first of them.
            val atTarget = afterSeek.firstOrNull { it.start == target }
            assertTrue(atTarget != null, "the block the reader is blocked on was never requested: $afterSeek")
            assertEquals(unit, atTarget.length, "a seek falls back to single-block requests")
        } finally {
            input.close()
        }
    }

    @Test
    fun `blocks double in size once the near read-ahead is in hand`() {
        val content = payload(2 * 1024 * 1024)
        val source = FakeRangeSource(content)
        // One connection makes the request order deterministic, which is what
        // lets the sizes be asserted positionally.
        val input = input(source, content.size.toLong(), concurrency = 1)

        try {
            val buffer = ByteArray(1)
            input.read(buffer, 0, 1)
            waitUntil("enough blocks have been requested") { source.requests.size >= 8 }

            val lengths = source.requests.take(8).map { it.length }
            // The threshold is four blocks' worth ahead of the read position,
            // and the read position sits inside the first block, so covering
            // it takes either four blocks or five. What matters is that the
            // switch happens after the threshold and never reverts.
            assertEquals(List(4) { unit }, lengths.take(4), "the first blocks must be single: $lengths")
            assertEquals(unit * 2, lengths.last(), "blocks never doubled: $lengths")
            val firstDouble = lengths.indexOfFirst { it == unit * 2 }
            assertTrue(
                lengths.drop(firstDouble).all { it == unit * 2 },
                "a single block reappeared after the switch: $lengths",
            )
        } finally {
            input.close()
        }
    }

    @Test
    fun `a block whose first attempt is rejected is retried and completes`() {
        val content = payload((unit * 4).toInt())
        // In production the link refresh happens inside the SDK's RangeReader,
        // below this class; what is exercised here is that the transport gives
        // it the second attempt to do it in.
        val source = FakeRangeSource(content, failFirstAttemptAt = setOf(unit))
        val input = input(source, content.size.toLong(), concurrency = 1)

        try {
            assertContentEquals(content, readAll(input, chunk = 4096))
        } finally {
            input.close()
        }

        assertEquals(2, source.requests.count { it.start == unit }, "the failed block was not retried")
    }

    @Test
    fun `the cache never exceeds its cap`() {
        val cap = 256L * 1024
        val content = payload(4 * 1024 * 1024)
        val source = FakeRangeSource(content)
        val input = input(source, content.size.toLong(), concurrency = 2, readAhead = cap, cap = cap)

        try {
            val buffer = ByteArray(4096)
            var read = 0L
            while (read < content.size) {
                val n = input.read(buffer, 0, buffer.size)
                if (n == -1) break
                read += n
                assertTrue(
                    input.cachedBytesForTest <= cap,
                    "held ${input.cachedBytesForTest} bytes against a $cap cap",
                )
            }
            assertEquals(content.size.toLong(), read)
        } finally {
            input.close()
        }
    }

    @Test
    fun `a fetch abandoned before its body runs releases its slots and wakes its waiters`() {
        // A seek can cancel the lazy fetch job after it is registered and
        // before start() runs it, and then the fetch body never executes. The
        // window is too narrow to hit on purpose through seekTo, so the
        // cleanup is driven directly. That every other test in this file now
        // passes a completed fetch through the same call is the other half of
        // the check: the cleanup must be a no-op after a success.
        val content = payload((unit * 8).toInt())
        val source = FakeRangeSource(content, latency = 30.seconds)
        val input = input(source, content.size.toLong(), concurrency = 1)

        try {
            val fetch = assertNotNull(input.claimForTest(), "there was nothing to claim")
            val claimed = input.inFlightSlotsForTest

            input.abandonForTest(fetch)

            assertTrue(fetch.done.isCompleted, "a reader parked on the abandoned fetch would never wake")
            assertEquals(
                claimed - fetch.slotCount,
                input.inFlightSlotsForTest,
                "the abandoned fetch kept its slots, so claimNext can never take them again",
            )

            // A second pass stands in for the worker's finally running over a
            // fetch some other path already released.
            input.abandonForTest(fetch)
            assertEquals(claimed - fetch.slotCount, input.inFlightSlotsForTest, "abandoning twice is not idempotent")
        } finally {
            input.close()
        }
    }

    @Test
    fun `a position still reads correctly after seeking away and back`() {
        // Seeking away cancels the fetches for the position seeked from, and
        // seeking back has to be able to claim those slots again. A leaked
        // claim shows up here as a read that never returns.
        val content = payload((unit * 16).toInt())
        val source = FakeRangeSource(content, latency = 2.milliseconds)
        val input = input(source, content.size.toLong(), concurrency = 4)

        try {
            val buffer = ByteArray(1024)
            repeat(100) { round ->
                val pos = (round % 6) * unit
                input.seekTo(content.size - unit)
                input.seekTo(pos)
                val n = input.read(buffer, 0, buffer.size)
                assertEquals(buffer.size, n, "short read at $pos in round $round")
                assertContentEquals(
                    content.copyOfRange(pos.toInt(), pos.toInt() + buffer.size),
                    buffer,
                    "wrong bytes at $pos in round $round",
                )
            }
        } finally {
            input.close()
        }
    }

    private fun waitUntil(what: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (deadline.elapsedNow().inWholeMilliseconds > timeoutMillis) {
                throw AssertionError("timed out waiting until $what")
            }
            runBlocking { delay(10.milliseconds) }
        }
    }
}
