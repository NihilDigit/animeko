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
import kotlinx.coroutines.runBlocking
import org.openani.mediamp.io.SeekableInput

/**
 * Adapts the SDK's [PikPakStreamReader] to the blocking [SeekableInput] the
 * player wants.
 *
 * The block cache keyed by offset, the read head outranking read-ahead, and a
 * seek cancelling only the requests outside the new window all live in the SDK.
 * What is left here is the boundary between blocking and suspending.
 *
 * [runBlocking] is the whole of this layer and the reason it exists: read and
 * seekTo are blocking by contract and the player calls them on its own thread,
 * which is exactly the decision the SDK declines to make for its callers. It
 * cannot deadlock: the reader's workers run on the dispatcher passed at
 * construction, not on the event loop started here.
 */
internal class StreamSeekableInput(
    private val reader: PikPakStreamReader,
) : SeekableInput {
    override val size: Long get() = reader.size

    override val position: Long get() = reader.position

    override val bytesRemaining: Long get() = reader.bytesRemaining

    /** Bytes the CDN has delivered, monotonic. The source of the speed readout. */
    val deliveredBytes: Long get() = reader.deliveredBytes

    override fun seekTo(position: Long) {
        require(position >= 0) { "position must be >= 0, got $position" }
        runBlocking { reader.seekTo(position) }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        runBlocking { reader.read(buffer, offset, length) }

    override fun close() {
        reader.close()
    }
}
