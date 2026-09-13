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
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.torrent.api.pieces.MutablePieceList
import me.him188.ani.app.torrent.api.pieces.PieceList
import me.him188.ani.app.torrent.api.pieces.PieceState
import me.him188.ani.app.torrent.io.RandomAccessFile
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.resolve
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * media3 walks the container element by element, so a player read is a byte or two. What matters is
 * therefore not what comes back but how many of those reads reach the cloud: one measured file spent
 * 28.5 s on 742 KB at 1.4 bytes a read, every byte of it already in the SDK's read-ahead. Reading
 * the right bytes back cannot tell that apart, so these tests count the crossings instead.
 */
class HybridSeekableInputBufferingTest {
    private val pieceSize = 1024L

    private fun pieces(size: Long): MutablePieceList = PieceList.create(size, pieceSize)

    private fun finish(pieces: MutablePieceList, indices: IntRange) = with(pieces) {
        for (index in indices) pieces.getByPieceIndex(index).state = PieceState.FINISHED
    }

    private fun input(
        dataPath: SystemPath,
        pieces: MutablePieceList,
        size: Long,
        stream: CountingCloudStream,
        bufferSize: Int,
        tail: TailPrefetch? = null,
    ) = HybridSeekableInput(
        name = "buffering.mkv",
        dataPath = dataPath,
        pieces = pieces,
        size = size,
        openStream = { stream },
        onDelivered = {},
        onCloudReadStarted = {},
        onCloudReadFinished = {},
        tail = tail,
        bufferSize = bufferSize,
    )

    private fun readOneByOne(input: HybridSeekableInput, count: Int): ByteArray {
        val out = ByteArray(count)
        val one = ByteArray(1)
        for (index in 0 until count) {
            assertEquals(1, input.read(one, 0, 1), "one-byte read $index came back short")
            out[index] = one[0]
        }
        return out
    }

    @Test
    fun `a run of one-byte reads costs one cloud read per window, not one per byte`() {
        val size = 8 * pieceSize
        val content = ByteArray(size.toInt()) { (it * 31 % 251).toByte() }
        val absent = SystemPaths.createTempDirectory("pikpak-buffered-stream").resolve("absent.mkv")
        val stream = CountingCloudStream(content)
        val input = input(absent, pieces(size), size, stream, bufferSize = pieceSize.toInt())

        val read = readOneByOne(input, size.toInt())

        assertContentEquals(content, read)
        // 8192 player reads, eight 1 KiB windows. Unbuffered this was 8192 crossings; the number
        // is asserted exactly because "fewer" would still pass at one per byte minus one.
        assertEquals(8, stream.reads, "one-byte reads still reach the cloud one by one")
        input.close()
    }

    @Test
    fun `a seek inside the window does not reach the cloud at all`() {
        val size = 4 * pieceSize
        val content = ByteArray(size.toInt()) { (it * 7 % 251).toByte() }
        val absent = SystemPaths.createTempDirectory("pikpak-buffered-seek").resolve("absent.mkv")
        val stream = CountingCloudStream(content)
        val input = input(absent, pieces(size), size, stream, bufferSize = size.toInt())

        val one = ByteArray(1)
        input.seekTo(0)
        input.read(one, 0, 1)
        val afterFirstFill = stream.reads
        assertEquals(1, afterFirstFill)

        // Reading the header, jumping to the index and jumping back is the pattern this has to be
        // free for; all three land in one window here.
        input.seekTo(size - 1)
        input.read(one, 0, 1)
        assertEquals(content[(size - 1).toInt()], one[0])
        input.seekTo(0)
        input.read(one, 0, 1)
        assertEquals(content[0], one[0])

        assertEquals(afterFirstFill, stream.reads, "a seek inside the buffered window refilled it")
        input.close()
    }

    @Test
    fun `a window is never filled across the seam between the cloud and the data file`() {
        val size = 8 * pieceSize
        val content = ByteArray(size.toInt()) { (it * 13 % 251).toByte() }
        val dir = SystemPaths.createTempDirectory("pikpak-buffered-seam")
        val dataPath = dir.resolve("video.mkv")
        RandomAccessFile(dataPath, "rw").use { it.write(content, 0, content.size) }

        val pieces = pieces(size)
        finish(pieces, 2..7)
        val stream = CountingCloudStream(content)
        // Four times the piece size, so a window reaching from 0 would run well past the first
        // finished piece if nothing clipped it.
        val input = input(dataPath, pieces, size, stream, bufferSize = 4 * pieceSize.toInt())

        val read = readOneByOne(input, size.toInt())

        assertContentEquals(content, read)
        assertTrue(stream.requests.isNotEmpty(), "the unfinished head should have come from the cloud")
        assertTrue(
            stream.requests.all { it.start + it.length <= 2 * pieceSize },
            "a fill ran past the first finished piece and refetched bytes already on disk: ${stream.requests}",
        )
        input.close()
    }

    @Test
    fun `a window is never filled across the seam between the cloud and the prefetched tail`() = runBlocking {
        val size = 8 * pieceSize
        val content = ByteArray(size.toInt()) { (it * 17 % 251).toByte() }
        val tailBytes = 2 * pieceSize
        val tailStart = size - tailBytes
        val tail = TailPrefetch(
            name = "tail.mkv",
            source = FakeRangeSource(content),
            size = size,
            parentCoroutineContext = Dispatchers.IO_,
            tailBytes = tailBytes,
            blockSize = pieceSize,
        )
        awaitTail(tail, tailStart, tailBytes)

        val absent = SystemPaths.createTempDirectory("pikpak-buffered-tail").resolve("absent.mkv")
        val stream = CountingCloudStream(content)
        val input = input(absent, pieces(size), size, stream, bufferSize = 4 * pieceSize.toInt(), tail = tail)

        val read = readOneByOne(input, size.toInt())

        assertContentEquals(content, read)
        assertTrue(
            stream.requests.all { it.start + it.length <= tailStart },
            "a fill ran into the prefetched tail and paid for bytes already in memory: ${stream.requests}",
        )
        // Two 1 KiB blocks behind one 4 KiB window: the fill has to cross a block boundary, which a
        // single TailPrefetch.read cannot do.
        assertTrue(tailBytes > pieceSize, "the tail must span more than one block for this to prove anything")
        tail.close()
    }

    @Test
    fun `the tail reports a contiguous length across its block boundaries`() = runBlocking {
        val size = 8 * pieceSize
        val content = ByteArray(size.toInt()) { (it * 19 % 251).toByte() }
        val tailBytes = 3 * pieceSize
        val tailStart = size - tailBytes
        val tail = TailPrefetch(
            name = "tail.mkv",
            source = FakeRangeSource(content),
            size = size,
            parentCoroutineContext = Dispatchers.IO_,
            tailBytes = tailBytes,
            blockSize = pieceSize,
        )
        awaitTail(tail, tailStart, tailBytes)

        assertEquals(tailBytes, tail.availableFrom(tailStart), "availableFrom stopped at a block boundary")
        assertEquals(tailBytes - 1, tail.availableFrom(tailStart + 1))
        assertEquals(0L, tail.availableFrom(tailStart - 1), "a position below the tail is none of its business")
        assertEquals(0L, tail.availableFrom(size), "past the end there is nothing to report")
        tail.close()
    }

    private suspend fun awaitTail(tail: TailPrefetch, from: Long, expected: Long) = withTimeout(10.seconds) {
        while (tail.availableFrom(from) < expected) delay(10.milliseconds)
    }
}

/**
 * A cloud stream that records every read the buffering was supposed to remove.
 *
 * Deliberately hands back the whole requested length in one call, so the read count is exactly the
 * number of fills and a regression shows up as a number rather than as a slower test.
 */
internal class CountingCloudStream(private val content: ByteArray) : CloudStream {
    data class Request(val start: Long, val length: Int)

    private var pos = 0L

    val requests = mutableListOf<Request>()

    val reads: Int get() = requests.size

    var seeks: Int = 0
        private set

    override val size: Long get() = content.size.toLong()

    override val position: Long get() = pos

    override val bytesRemaining: Long get() = (size - pos).coerceAtLeast(0)

    override var deliveredBytes: Long = 0L
        private set

    override fun seekTo(position: Long) {
        require(position >= 0) { "position must be >= 0, got $position" }
        seeks++
        pos = position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= size) return -1
        val count = minOf(length.toLong(), size - pos).toInt()
        requests += Request(pos, count)
        content.copyInto(buffer, offset, pos.toInt(), pos.toInt() + count)
        pos += count
        deliveredBytes += count
        return count
    }

    override fun close() = Unit
}
