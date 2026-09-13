/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.PikPakStreamReader
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import me.him188.ani.app.torrent.api.pieces.PieceList
import me.him188.ani.app.torrent.api.pieces.PieceState
import me.him188.ani.app.torrent.io.RandomAccessFile
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.io.SeekableInput
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Read completed pieces locally and stream gaps without creating a playback cache.
// Lazy cloud access keeps fully imported files playable without credentials or a GCID.
internal class HybridSeekableInput(
    private val name: String,
    private val dataPath: SystemPath,
    private val pieces: PieceList,
    override val size: Long,
    private val openStream: () -> StreamSeekableInput,
    private val onDelivered: (delta: Long) -> Unit,
    private val onCloudReadStarted: () -> Unit,
    private val onCloudReadFinished: () -> Unit,
    private val tail: TailPrefetch? = null,
) : SeekableInput {
    private val logger = logger<HybridSeekableInput>()

    private val lock = SynchronizedObject()

    private var pos = 0L
    private var file: RandomAccessFile? = null
    private var stream: StreamSeekableInput? = null
    private var reportedDelivered = 0L
    private var dataFileSeen = false
    private var tailServed = false
    private var closed = false

    init {
        require(pieces.sizes.isEmpty() || pieces.sizes.dropLast(1).all { it == pieces.sizes[0] }) {
            "HybridSeekableInput needs a uniformly sized piece list"
        }
    }

    private fun dataFileExists(): Boolean = synchronized(lock) {
        if (dataFileSeen) true else dataPath.exists().also { dataFileSeen = it }
    }

    override val position: Long get() = synchronized(lock) { pos }

    override val bytesRemaining: Long get() = (size - position).coerceAtLeast(0)

    override fun seekTo(position: Long) {
        require(position >= 0) { "position must be >= 0, got $position" }
        synchronized(lock) { pos = position }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val from = synchronized(lock) { pos }
        if (from >= size) return -1
        val wanted = minOf(length.toLong(), size - from).toInt()
        if (wanted == 0) return 0
        val onDisk = finishedRunEndFrom(from, wanted)
        val read = if (onDisk > from) {
            readFromDisk(from, buffer, offset, minOf(wanted.toLong(), onDisk - from).toInt())
        } else {
            // Serving the container's index from the prefetched tail keeps the stream's position,
            // its read-ahead window and its in-flight fetches where the playback head left them.
            val fromTail = tail?.read(from, buffer, offset, wanted) ?: 0
            if (fromTail > 0) {
                // A hit leaves no other trace: the absence of a far seek cannot tell a hit from a
                // player that never reached the index.
                if (!tailServed) {
                    tailServed = true
                    logger.info { "[$name] index read at $from served from the prefetched tail" }
                }
                fromTail
            } else {
                readFromStream(from, buffer, offset, wanted)
            }
        }
        if (read > 0) synchronized(lock) { pos = from + read }
        return read
    }

    private fun finishedRunEndFrom(from: Long, wanted: Int): Long = with(pieces) {
        if (pieces.sizes.isEmpty() || !dataFileExists()) return from
        val limit = minOf(size, from + wanted)
        var end = from
        while (end < limit) {
            val index = (end / pieces.sizes[0]).toInt()
            if (index >= pieces.sizes.size) break
            val piece = pieces.createPieceByListIndexUnsafe(index)
            if (piece.state != PieceState.FINISHED) break
            end = piece.dataEndOffset
        }
        return end.coerceAtMost(limit)
    }

    // One shared handle: seek and the read that follows must be serialized, and close must not land
    // between them, or the read throws where the closed check above promises -1.
    private fun readFromDisk(from: Long, buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        if (closed) return -1
        val handle = file ?: RandomAccessFile(dataPath, "r").also { file = it }
        handle.seek(from)
        handle.read(buffer, offset, length)
    }

    // The cloud read stays outside the lock: it blocks on the network, and close is what aborts it.
    private fun readFromStream(from: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val reader = synchronized(lock) {
            if (closed) return -1
            stream ?: openStream().also {
                // Registration must precede publication so close cannot overtake it.
                onCloudReadStarted()
                stream = it
                logger.info { "[$name] cloud stream opened at $from" }
            }
        }
        if (reader.position != from) {
            // A demuxer repositions constantly within its buffer; only a jump that leaves the
            // read-ahead window costs a range request, and only those are worth a line.
            val jump = from - reader.position
            reader.seekTo(from)
            if (jump > FAR_SEEK || jump < -FAR_SEEK) {
                logger.info { "[$name] stream seek $jump bytes to $from" }
            }
        }
        return reader.read(buffer, offset, length).also { publishDelivered() }
    }

    private fun publishDelivered() {
        val added = synchronized(lock) {
            val now = stream?.deliveredBytes ?: return
            val delta = now - reportedDelivered
            if (delta <= 0) return
            reportedDelivered = now
            delta
        }
        onDelivered(added)
    }

    override fun close() {
        publishDelivered()
        val (openFile, openReader) = synchronized(lock) {
            if (closed) return
            closed = true
            (file to stream).also { file = null; stream = null }
        }
        runCatching { openFile?.close() }
        runCatching { tail?.close() }
        if (openReader != null) {
            runCatching { openReader.close() }
            onCloudReadFinished()
        }
    }

    private companion object {
        // Below the SDK's read-ahead window a jump costs nothing; above it the stream refills.
        const val FAR_SEEK = 8L * 1024 * 1024
    }
}

// Player reads block; SDK workers must run on a separate dispatcher from these runBlocking calls.
internal class StreamSeekableInput(
    private val name: String,
    private val reader: PikPakStreamReader,
    private val retryDelay: Duration = READ_RETRY_DELAY,
    private val retryWindow: Duration = READ_RETRY_WINDOW,
) : SeekableInput {
    private val logger = logger<StreamSeekableInput>()

    private var awaitingFirstByte = true

    @Volatile
    private var closed = false

    private val throughput = ThroughputLog { bytes, reads, elapsed ->
        logger.info { "[$name] $bytes B over $reads reads in $elapsed, ${kilobytesPerSecond(bytes, elapsed)} kB/s" }
    }

    override val size: Long get() = reader.size

    override val position: Long get() = reader.position

    override val bytesRemaining: Long get() = reader.bytesRemaining

    val deliveredBytes: Long get() = reader.deliveredBytes

    override fun seekTo(position: Long) {
        require(position >= 0) { "position must be >= 0, got $position" }
        asIoFailure { runBlockingInterruptible { reader.seekTo(position) } }
        awaitingFirstByte = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val mark = TimeSource.Monotonic.markNow()
        val read = readRidingOutFailures(buffer, offset, length)
        val waited = mark.elapsedNow()
        // A read served from the read-ahead buffer returns in microseconds; only one that actually
        // waited on the network says anything, and after a seek the first such read is the recovery.
        if (waited >= NOTABLE_WAIT) {
            logger.info { "[$name] read at ${reader.position} waited $waited" }
        }
        if (awaitingFirstByte) {
            awaitingFirstByte = false
            throughput.restart()
        }
        if (read > 0) throughput.record(read.toLong())
        return read
    }

    // PieceFetcher and startFetching retry the same way, but they are background loops where a 30s
    // delay costs nothing. This one sits on the player's blocking read: the network returns a second
    // or two after screen-on while the player gives up after about three, which is why a failure
    // there ends playback by luck. Retry briefly and for a bounded stretch, so a link that is really
    // gone still reaches the player as a failure.
    private fun readRidingOutFailures(buffer: ByteArray, offset: Int, length: Int): Int {
        val since = TimeSource.Monotonic.markNow()
        while (true) {
            try {
                return asIoFailure { runBlockingInterruptible { reader.read(buffer, offset, length) } }
            } catch (e: Throwable) {
                // Closing is how playback is torn down, and the reader reports it as a failure like
                // any other. Retrying it would spin for the whole window on every episode switch.
                if (closed || e is CancellationException || e.isReadInterruption()) throw e
                val waited = since.elapsedNow()
                if (waited >= retryWindow) {
                    logger.warn(e) { "[$name] read at ${reader.position} failed, gave up after $waited" }
                    throw e
                }
                logger.warn(e) { "[$name] read at ${reader.position} failed, retrying in $retryDelay" }
                runBlockingInterruptible { delay(retryDelay) }
            }
        }
    }

    override fun close() {
        closed = true
        reader.close()
    }

    private companion object {
        val NOTABLE_WAIT = 200.milliseconds

        val READ_RETRY_DELAY = 500.milliseconds
        val READ_RETRY_WINDOW = 10.seconds
    }
}

// The SDK reports every failure as PikPakException, which is a RuntimeException. ExoPlayer's Loader
// retries an IOException but treats anything else as fatal, so the DNS lookup that fails in the
// second between screen-on and the network coming back ends playback with "播放失败" instead of
// resuming. What the player does with it is its policy to decide; the type must let it decide.
private inline fun <T> asIoFailure(block: () -> T): T =
    try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e)
    }
