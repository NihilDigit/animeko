/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.app.torrent.api.pieces.PieceList
import me.him188.ani.app.torrent.api.pieces.PieceState
import me.him188.ani.app.torrent.io.RandomAccessFile
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import org.openani.mediamp.io.SeekableInput

/**
 * Reads a piece that a cache download has already written from the disk, and
 * everything else from the cloud.
 *
 * Playback writes nothing of its own. A byte it reads is cheap to read again --
 * PikPak is a CDN, not a swarm -- while keeping it would either fill the disk
 * with episodes nobody asked to cache or be deleted at the next startup, which
 * makes the write pure wear. So the disk is read, never written, here.
 *
 * The case this exists for is caching an episode and watching it at the same
 * time. Without it the player refetches bytes that are already on disk, and the
 * fetcher stands aside for those reads -- so the bandwidth freed up is spent
 * redownloading what the fetcher itself just wrote.
 *
 * [openStream] is called only on the first byte that is not on disk, so a fully
 * cached file never contacts PikPak. That is what keeps an imported file
 * playable: it has no gcid, so opening a stream for it would throw.
 */
internal class HybridSeekableInput(
    private val dataPath: SystemPath,
    private val pieces: PieceList,
    override val size: Long,
    private val openStream: () -> StreamSeekableInput,
    /**
     * Reports what the CDN delivered, in increments. Pushed rather than polled
     * because several inputs can be open at once, each with its own lifetime;
     * the entry would otherwise have to hold them all and never learn when to
     * let one go. Disk reads report nothing, which is the point of this class.
     */
    private val onDelivered: (delta: Long) -> Unit,
) : SeekableInput {
    private val lock = SynchronizedObject()

    private var pos = 0L
    private var file: RandomAccessFile? = null
    private var stream: StreamSeekableInput? = null
    private var reportedDelivered = 0L
    private var dataFileSeen = false
    private var closed = false

    init {
        // Locating a piece by dividing the offset below only works because the
        // entry lays the list out with one uniform piece size from offset zero.
        require(pieces.sizes.isEmpty() || pieces.sizes.dropLast(1).all { it == pieces.sizes[0] }) {
            "HybridSeekableInput needs a uniformly sized piece list"
        }
    }

    /**
     * Checked until it is true, then remembered. The fetcher preallocates
     * before writing anything, so the file never goes away again while this
     * input is open, and a filesystem call per read is worth not paying.
     */
    private fun dataFileExists(): Boolean = synchronized(lock) {
        if (dataFileSeen) true else dataPath.exists().also { dataFileSeen = it }
    }

    override val position: Long get() = synchronized(lock) { pos }

    override val bytesRemaining: Long get() = (size - position).coerceAtLeast(0)

    override fun seekTo(position: Long) {
        require(position >= 0) { "position must be >= 0, got $position" }
        synchronized(lock) { pos = position }
    }

    /**
     * The lock covers the position and the two lazily opened readers, never the
     * read itself: a read that misses the disk goes to the CDN and can take
     * seconds, and holding the lock across it would block close() and the
     * position getters for that whole time. Reads are serial by the
     * [SeekableInput] contract, so the position cannot move underneath one.
     */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val from = synchronized(lock) { pos }
        if (from >= size) return -1
        val wanted = minOf(length.toLong(), size - from).toInt()
        if (wanted == 0) return 0
        val onDisk = finishedRunEndFrom(from, wanted)
        val read = if (onDisk > from) {
            readFromDisk(from, buffer, offset, minOf(wanted.toLong(), onDisk - from).toInt())
        } else {
            readFromStream(from, buffer, offset, wanted)
        }
        if (read > 0) synchronized(lock) { pos = from + read }
        return read
    }

    /**
     * Where the run of pieces on disk that starts at [from] ends, looking no
     * further than [wanted] bytes ahead.
     *
     * The bound is what keeps this cheap: a fully cached file is one run of
     * thousands of pieces, and scanning it whole on every read would walk the
     * entire list for every few kilobytes the player asks for. Nothing past the
     * read needs deciding.
     *
     * A run rather than a single piece, so a cached stretch still costs one
     * file read; and never past the run's end, because a piece the fetcher has
     * not finished is preallocated zeros on disk.
     */
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

    // Both readers are opened under the lock and refused once closed. A read
    // that has already taken its position can otherwise run past a concurrent
    // close and open a reader nothing will ever close -- for the stream that
    // means a PikPakStreamReader left holding connections and workers.
    private fun readFromDisk(from: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val handle = synchronized(lock) {
            if (closed) return -1
            file ?: RandomAccessFile(dataPath, "r").also { file = it }
        }
        handle.seek(from)
        return handle.read(buffer, offset, length)
    }

    private fun readFromStream(from: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val reader = synchronized(lock) {
            if (closed) return -1
            stream ?: openStream().also { stream = it }
        }
        if (reader.position != from) reader.seekTo(from)
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
        val (openFile, openStream) = synchronized(lock) {
            closed = true
            (file to stream).also { file = null; stream = null }
        }
        runCatching { openFile?.close() }
        runCatching { openStream?.close() }
    }
}
