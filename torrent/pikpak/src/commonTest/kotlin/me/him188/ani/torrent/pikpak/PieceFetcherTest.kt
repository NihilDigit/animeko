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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.torrent.api.pieces.MutablePieceList
import me.him188.ani.app.torrent.api.pieces.PieceList
import me.him188.ani.app.torrent.api.pieces.PieceState
import me.him188.ani.app.torrent.io.RandomAccessFile
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolve
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the fetcher owes the rest of the engine: bytes at the offsets the piece list says, a
 * bitmap that makes a restart skip them, and a worker that outlives a failed read.
 *
 * Everything is asserted on what reached disk or what the source was asked for; nothing here
 * waits out a duration to decide an outcome.
 */
class PieceFetcherTest {
    private val pieceSize = 1024L
    private val pieceCount = 8
    private val totalLength = pieceSize * pieceCount
    private val content = ByteArray(totalLength.toInt()) { (it * 31 % 251).toByte() }

    private fun newPieces(): MutablePieceList = PieceList.create(totalLength, pieceSize)

    private fun newFetcher(
        dir: SystemPath,
        source: RangeSource,
        pieces: MutablePieceList,
        concurrency: Int = 1,
        retryDelay: Duration = 50.milliseconds,
        maxRequestBytes: Long = 4 * pieceSize,
    ) = PieceFetcher(
        source = source,
        file = dir.resolve(DATA_NAME),
        pieces = pieces,
        totalLength = totalLength,
        concurrency = concurrency,
        logTag = "test",
        onPieceDownloaded = {},
        parentCoroutineContext = Dispatchers.IO_,
        retryDelay = retryDelay,
        maxRequestBytes = maxRequestBytes,
    )

    private suspend fun awaitFinished(pieces: MutablePieceList, pieceIndices: List<Int>) = withTimeout(TIMEOUT) {
        while (pieceIndices.any { with(pieces) { pieces.getByPieceIndex(it).state } != PieceState.FINISHED }) {
            delay(POLL)
        }
    }

    private fun assertPieceContent(dir: SystemPath, pieceIndices: List<Int>) {
        val onDisk = dir.resolve(DATA_NAME).readBytes()
        assertEquals(totalLength, onDisk.size.toLong(), "the data file was not preallocated to its full length")
        for (index in pieceIndices) {
            val start = (index * pieceSize).toInt()
            val end = start + pieceSize.toInt()
            assertContentEquals(
                content.copyOfRange(start, end),
                onDisk.copyOfRange(start, end),
                "piece $index does not hold the bytes at its own offset",
            )
        }
    }

    @Test
    fun `writes every piece at its own offset`() = runBlocking {
        val dir = SystemPaths.createTempDirectory("pikpak-fetch-offsets")
        val source = RecordingRangeSource(content)
        val pieces = newPieces()
        val fetcher = newFetcher(dir, source, pieces)

        // Scattered and out of order: a fetcher that wrote sequentially, or that took the
        // request offset from the batch's position in the work list, passes a contiguous list.
        val wanted = listOf(7, 0, 4, 5, 1)
        fetcher.start()
        fetcher.downloadOnly(emptyList(), wanted)
        awaitFinished(pieces, wanted)
        fetcher.close()

        assertPieceContent(dir, wanted)
    }

    @Test
    fun `contiguous pieces share one request`() = runBlocking {
        val dir = SystemPaths.createTempDirectory("pikpak-fetch-coalesce")
        val source = RecordingRangeSource(content)
        val pieces = newPieces()
        val fetcher = newFetcher(dir, source, pieces)

        val wanted = listOf(0, 1, 2, 3)
        fetcher.start()
        fetcher.downloadOnly(emptyList(), wanted)
        awaitFinished(pieces, wanted)
        fetcher.close()

        assertEquals(
            1, source.requests.size,
            "contiguous pieces within one request's budget were fetched one by one: ${source.requests}",
        )
        assertEquals(RecordingRangeSource.Request(0L, 4 * pieceSize), source.requests.single())
        assertPieceContent(dir, wanted)
    }

    @Test
    fun `pieces recorded in the bitmap are not fetched again`() = runBlocking {
        val dir = SystemPaths.createTempDirectory("pikpak-fetch-resume")
        val first = RecordingRangeSource(content)
        val firstPieces = newPieces()
        val firstFetcher = newFetcher(dir, first, firstPieces)
        val done = listOf(0, 1, 2, 3)
        firstFetcher.start()
        firstFetcher.downloadOnly(emptyList(), done)
        awaitFinished(firstPieces, done)
        firstFetcher.close()

        val second = RecordingRangeSource(content)
        val secondPieces = newPieces()
        val secondFetcher = newFetcher(dir, second, secondPieces)
        val rest = listOf(4, 5, 6, 7)
        assertTrue(
            done.all { with(secondPieces) { secondPieces.getByPieceIndex(it).state } == PieceState.FINISHED },
            "the bitmap was not applied to the piece list of the restarted download",
        )

        secondFetcher.start()
        secondFetcher.downloadOnly(emptyList(), done + rest)
        awaitFinished(secondPieces, done + rest)
        secondFetcher.close()

        assertTrue(
            second.requests.all { it.start >= 4 * pieceSize },
            "the restarted download fetched bytes it already had: ${second.requests}",
        )
        assertTrue(secondFetcher.isComplete)
        assertPieceContent(dir, done + rest)
    }

    @Test
    fun `a file inside a subfolder of the torrent creates its directories`() = runBlocking {
        // A season pack lists its episodes under folders, and only the session
        // root exists when a download starts. Opening the file does not make
        // one, so this failed before a byte was fetched and then retried for
        // as long as the cache record lived.
        val dir = SystemPaths.createTempDirectory("pikpak-fetch-nested")
        val source = RecordingRangeSource(content)
        val pieces = newPieces()
        val nested = dir.resolve("Season 1").resolve(DATA_NAME)
        val fetcher = PieceFetcher(
            source = source,
            file = nested,
            pieces = pieces,
            totalLength = totalLength,
            concurrency = 2,
            logTag = "test",
            onPieceDownloaded = {},
            parentCoroutineContext = Dispatchers.IO_,
        )

        val wanted = (0 until pieceCount).toList()
        fetcher.start()
        fetcher.downloadOnly(emptyList(), wanted)
        awaitFinished(pieces, wanted)
        fetcher.close()

        assertContentEquals(content, nested.readBytes())
    }

    @Test
    fun `a truncated data file invalidates the pieces past its end`() = runBlocking {
        // Before the bitmap, progress was the file's own length, so a file cut
        // by something outside the engine simply came back as unfinished. With
        // the bitmap as the record that correction has to be made explicitly,
        // or the player reads whatever the filesystem left behind.
        val dir = SystemPaths.createTempDirectory("pikpak-fetch-truncated")
        val source = RecordingRangeSource(content)
        val pieces = newPieces()
        val fetcher = newFetcher(dir, source, pieces)
        val wanted = (0 until pieceCount).toList()
        fetcher.start()
        fetcher.downloadOnly(emptyList(), wanted)
        awaitFinished(pieces, wanted)
        fetcher.close()

        val kept = 3
        RandomAccessFile(dir.resolve(DATA_NAME), "rw").use { it.setLength(kept * pieceSize) }

        val reopened = newFetcher(dir, RecordingRangeSource(content), newPieces())
        assertFalse(reopened.isComplete, "a truncated file was reported complete")
        assertEquals(kept * pieceSize, reopened.downloadedBytes, "pieces past the end of the file survived")
        reopened.close()
    }

    @Test
    fun `a failing read is retried instead of killing the worker`() = runBlocking {
        val dir = SystemPaths.createTempDirectory("pikpak-fetch-retry")
        val source = RecordingRangeSource(content, failFirstReads = 2)
        val pieces = newPieces()
        val fetcher = newFetcher(dir, source, pieces)

        val wanted = (0 until pieceCount).toList()
        fetcher.start()
        fetcher.downloadOnly(emptyList(), wanted)
        awaitFinished(pieces, wanted)

        assertEquals(null, fetcher.error.value, "the error of an attempt that later succeeded was not cleared")
        assertEquals(totalLength, fetcher.downloadedBytes)
        assertTrue(fetcher.deliveredBytes >= totalLength)
        fetcher.close()
        assertPieceContent(dir, wanted)
    }

    @Test
    fun `deleteTarget leaves the fetcher usable`() = runBlocking {
        val dir = SystemPaths.createTempDirectory("pikpak-fetch-delete")
        val source = RecordingRangeSource(content)
        val pieces = newPieces()
        val fetcher = newFetcher(dir, source, pieces)

        val wanted = (0 until pieceCount).toList()
        fetcher.start()
        fetcher.downloadOnly(emptyList(), wanted)
        awaitFinished(pieces, wanted)
        val deliveredBeforeDelete = fetcher.deliveredBytes

        fetcher.deleteTarget()
        assertFalse(fetcher.isComplete)
        assertFalse(dir.resolve(DATA_NAME).exists(), "the data file survived deleteTarget")
        assertFalse(PieceBitmap.pathFor(dir.resolve(DATA_NAME)).exists(), "the bitmap survived deleteTarget")

        fetcher.start()
        fetcher.downloadOnly(emptyList(), wanted)
        awaitFinished(pieces, wanted)
        fetcher.close()

        assertTrue(
            fetcher.deliveredBytes > deliveredBeforeDelete,
            "delivered bytes went backwards or the second run fetched nothing",
        )
        assertPieceContent(dir, wanted)
    }

    private companion object {
        const val DATA_NAME = "video.mkv"
        val TIMEOUT = 30.seconds
        val POLL = 10.milliseconds
    }
}

/**
 * A [RangeSource] over a byte array, recording what it was asked for.
 *
 * Local to this test rather than reusing the module's other fake: the assertions here are about
 * request offsets and counts, so the fake must not merge, split or reorder anything.
 */
private class RecordingRangeSource(
    private val content: ByteArray,
    /** The first this many reads throw, standing in for a source that is unreachable for a while. */
    failFirstReads: Int = 0,
) : RangeSource {
    data class Request(val start: Long, val length: Long)

    private val lock = SynchronizedObject()
    private val _requests = mutableListOf<Request>()
    private var remainingFailures = failFirstReads

    val requests: List<Request> get() = synchronized(lock) { _requests.toList() }

    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T = block(ByteReadChannel(readBytes(start, length, priority)))

    override suspend fun readBytes(start: Long, length: Long, priority: Int): ByteArray {
        val down = synchronized(lock) {
            _requests += Request(start, length)
            if (remainingFailures <= 0) {
                false
            } else {
                remainingFailures--
                true
            }
        }
        if (down) throw IllegalStateException("simulated outage at $start")
        val end = minOf(content.size.toLong(), start + length).toInt()
        return content.copyOfRange(start.toInt(), end)
    }
}
