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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The policy, not its implementation: how many downloads run at once and what
 * an open stream does to the others.
 *
 * Nothing here waits for a duration to decide an outcome. Reads are recorded
 * before they reach [ReadGate] and released only when a test says so, so
 * "issued a round" and "was served a round" are separate observations and a
 * downloader can be held mid-round for as long as an assertion needs.
 */
class DownloadSchedulerTest {
    private val blockSize = 16L * 1024
    private val blocksPerFile = 6
    private val concurrency = 2

    /** One entry: its own gated source, its own scheduler slot, its own file. */
    private class Entry(
        name: String,
        scheduler: DownloadScheduler,
        blockSize: Long,
        blocks: Int,
        concurrency: Int,
        val gate: ReadGate = ReadGate(),
    ) {
        val content = ByteArray((blockSize * blocks).toInt()) { (it * 31 % 251).toByte() }
        val source = FakeRangeSource(content, gate = gate)
        val slot = scheduler.newSlot(name)
        val downloader = SequentialDownloader(
            source = source,
            target = SystemPaths.createTempDirectory("pikpak-sched-$name").resolve("$name.mkv"),
            length = content.size.toLong(),
            concurrency = concurrency,
            logTag = name,
            parentCoroutineContext = Dispatchers.IO_,
            blockSize = blockSize,
            slot = slot,
        )

        val issuedReads: Int get() = source.requests.size

        suspend fun awaitComplete() = withTimeout(TIMEOUT) {
            downloader.downloadedBytes.first { it >= content.size }
        }
    }

    private fun entries(scheduler: DownloadScheduler, vararg names: String) =
        names.map { Entry(it, scheduler, blockSize, blocksPerFile, concurrency) }

    private suspend fun awaitReads(entry: Entry, count: Int) = withTimeout(TIMEOUT) {
        while (entry.issuedReads < count) delay(POLL)
    }

    /** Asserts an absence: nothing more happens for as long as anything would have. */
    private suspend fun assertNoFurtherReads(entry: Entry, expected: Int, message: String) {
        delay(SETTLE)
        assertEquals(expected, entry.issuedReads, message)
    }

    @Test
    fun `only two downloads run at a time and the third takes the first freed slot`() = runBlocking {
        val scheduler = DownloadScheduler(limit = 2)
        val (a, b, c) = entries(scheduler, "a", "b", "c")

        // Started one at a time so the queue order is the order under test and
        // not the order three coroutines happened to be dispatched in.
        a.downloader.start()
        awaitReads(a, concurrency)
        b.downloader.start()
        awaitReads(b, concurrency)
        c.downloader.start()

        assertNoFurtherReads(c, 0, "a third download started while two were already fetching")

        a.gate.openForever()
        a.awaitComplete()
        awaitReads(c, concurrency)

        b.gate.openForever()
        c.gate.openForever()
        b.awaitComplete()
        c.awaitComplete()
        assertTrue(a.downloader.isComplete && b.downloader.isComplete && c.downloader.isComplete)
    }

    @Test
    fun `an open stream pauses the other downloads and closing it resumes them`() = runBlocking {
        val scheduler = DownloadScheduler(limit = 2)
        val (a, b, c) = entries(scheduler, "a", "b", "c")

        a.downloader.start()
        awaitReads(a, concurrency)
        b.downloader.start()
        awaitReads(b, concurrency)
        c.downloader.start()
        assertNoFurtherReads(c, 0, "the third download ran before its entry was played")

        // Playback of c's file. Its own download jumps ahead of the two that
        // hold the slots, which is the whole point: the episode being watched
        // is the one worth finishing.
        c.slot.openStream()
        c.gate.openForever()
        c.awaitComplete()

        // The rounds a and b had in flight are allowed to finish; no further
        // round may be claimed while the stream is open.
        a.gate.allow(concurrency)
        b.gate.allow(concurrency)
        withTimeout(TIMEOUT) { a.downloader.downloadedBytes.first { it >= blockSize * concurrency } }
        withTimeout(TIMEOUT) { b.downloader.downloadedBytes.first { it >= blockSize * concurrency } }
        assertNoFurtherReads(a, concurrency, "a paused download claimed another round during playback")
        assertEquals(concurrency, b.issuedReads, "a paused download claimed another round during playback")
        assertTrue(a.downloader.downloadedBytes.value < a.content.size, "the fixture downloaded too much to test a pause")

        c.slot.closeStream()
        a.gate.openForever()
        b.gate.openForever()
        a.awaitComplete()
        b.awaitComplete()
        assertTrue(a.downloader.isComplete && b.downloader.isComplete)
    }

    @Test
    fun `a second stream on the same entry keeps it playing until both close`() = runBlocking {
        val scheduler = DownloadScheduler(limit = 1)
        val (a, b) = entries(scheduler, "a", "b")

        // mpv opens a second instance for seek previews, so one entry can carry
        // two streams; a single flag would let the preview's close unpause the
        // world while the real player is still reading.
        b.slot.openStream()
        b.slot.openStream()
        b.slot.closeStream()

        a.downloader.start()
        assertNoFurtherReads(a, 0, "a download ran while another entry still had a stream open")

        b.slot.closeStream()
        a.gate.openForever()
        a.awaitComplete()
        assertTrue(a.downloader.isComplete)
    }

    private companion object {
        val TIMEOUT = 30.seconds
        val POLL = 10.milliseconds

        /** Long enough that a downloader would have issued its next round if it were going to. */
        val SETTLE = 500.milliseconds
    }
}
