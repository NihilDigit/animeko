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
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Progress is the file's length, which only holds if the writes really are in
 * order. Both tests are about that: a resumed download must continue where the
 * length says, and a finished one must be exactly as long as the record.
 */
class SequentialDownloaderTest {
    private val block = 16L * 1024

    private fun payload(size: Int) = ByteArray(size) { (it * 17 % 253).toByte() }

    @Test
    fun `a download writes the whole file and reports finished at its length`() = runBlocking {
        val content = payload((block * 5 + 123).toInt())
        val target = SystemPaths.createTempDirectory("pikpak-seqdl").resolve("01.mkv")
        val downloader = SequentialDownloader(
            source = FakeRangeSource(content),
            target = target,
            length = content.size.toLong(),
            concurrency = 3,
            logTag = "test",
            parentCoroutineContext = Dispatchers.IO_,
            blockSize = block,
        )

        downloader.start()
        withTimeout(30.seconds) { downloader.downloadedBytes.first { it >= content.size } }
        downloader.close()

        assertContentEquals(content, target.readBytes())
        assertTrue(downloader.isComplete)
    }

    @Test
    fun `a file in a subfolder of the pack gets its directory created`() = runBlocking {
        val content = payload(block.toInt())
        val target = SystemPaths.createTempDirectory("pikpak-seqdl-nested").resolve("specials/S00E01.mkv")
        val downloader = SequentialDownloader(
            source = FakeRangeSource(content),
            target = target,
            length = content.size.toLong(),
            concurrency = 2,
            logTag = "test",
            parentCoroutineContext = Dispatchers.IO_,
            blockSize = block,
        )

        downloader.start()
        withTimeout(30.seconds) { downloader.downloadedBytes.first { it >= content.size } }
        downloader.close()

        assertContentEquals(content, target.readBytes())
    }

    @Test
    fun `a download that outlasts an outage finishes and clears its error`() = runBlocking {
        // One block per round, so the number of failed reads is the number of
        // failed rounds. Four of them is one more than a single attempt
        // tolerates, which is what makes this cross the reopen-and-back-off
        // path rather than only the per-round retry.
        val content = payload((block * 4).toInt())
        val target = SystemPaths.createTempDirectory("pikpak-seqdl-outage").resolve("01.mkv")
        val downloader = SequentialDownloader(
            source = FakeRangeSource(content, failFirstReads = 4),
            target = target,
            length = content.size.toLong(),
            concurrency = 1,
            logTag = "test",
            parentCoroutineContext = Dispatchers.IO_,
            blockSize = block,
            roundRetryDelay = 10.milliseconds,
            attemptRetryDelay = 10.milliseconds,
        )

        downloader.start()
        withTimeout(30.seconds) { downloader.downloadedBytes.first { it >= content.size } }
        downloader.close()

        assertContentEquals(content, target.readBytes())
        assertNull(downloader.error.value, "a finished download must not still report the outage it survived")
    }

    @Test
    fun `a source that keeps failing publishes an error and keeps retrying`() = runBlocking {
        // Three failed rounds used to end the run for good, so a cache record
        // was lost to any outage longer than a few seconds even after the
        // network came back.
        val content = payload((block * 4).toInt())
        val target = SystemPaths.createTempDirectory("pikpak-seqdl-down").resolve("01.mkv")
        val source = FakeRangeSource(content, failFirstReads = Int.MAX_VALUE)
        val downloader = SequentialDownloader(
            source = source,
            target = target,
            length = content.size.toLong(),
            concurrency = 1,
            logTag = "test",
            parentCoroutineContext = Dispatchers.IO_,
            blockSize = block,
            roundRetryDelay = 10.milliseconds,
            attemptRetryDelay = 10.milliseconds,
        )

        downloader.start()
        withTimeout(30.seconds) {
            val error = downloader.error.first { it != null }
            assertNotNull(error)
            // Still trying: the error is a report, not a stop.
            val seen = source.requests.size
            while (source.requests.size <= seen) delay(10.milliseconds)
        }
        downloader.close()
    }

    @Test
    fun `a partial file is continued rather than refetched`() = runBlocking {
        val content = payload((block * 4).toInt())
        val target = SystemPaths.createTempDirectory("pikpak-seqdl-resume").resolve("01.mkv")
        val already = (block * 2).toInt()
        target.writeBytes(content.copyOfRange(0, already))

        val source = FakeRangeSource(content)
        val downloader = SequentialDownloader(
            source = source,
            target = target,
            length = content.size.toLong(),
            concurrency = 2,
            logTag = "test",
            parentCoroutineContext = Dispatchers.IO_,
            blockSize = block,
        )

        assertEquals(already.toLong(), downloader.downloadedBytes.value, "the file's length is the progress")

        downloader.start()
        withTimeout(30.seconds) { downloader.downloadedBytes.first { it >= content.size } }
        downloader.close()

        assertContentEquals(content, target.readBytes())
        assertTrue(
            source.requests.all { it.start >= already },
            "bytes already on disk were fetched again: ${source.requests}",
        )
    }
}
