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
import me.him188.ani.app.torrent.io.RandomAccessFile
import me.him188.ani.utils.io.SystemPath
import org.openani.mediamp.io.SeekableInput

/**
 * A completed cache file, read straight off the disk.
 *
 * No buffering and no waiting: once a file is complete there is nothing to
 * wait for, and the filesystem does the buffering. [RandomAccessFile] is not
 * thread-safe because seek and read are two steps, so both go through one lock.
 */
internal class FileSeekableInput(
    path: SystemPath,
    override val size: Long,
) : SeekableInput {
    private val lock = SynchronizedObject()
    private val file = RandomAccessFile(path, "r")

    private var pos = 0L

    override val position: Long get() = synchronized(lock) { pos }

    override val bytesRemaining: Long get() = (size - position).coerceAtLeast(0)

    override fun seekTo(position: Long) {
        require(position >= 0) { "position must be >= 0, got $position" }
        synchronized(lock) { pos = position }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        if (pos >= size) return -1
        val wanted = minOf(length.toLong(), size - pos).toInt()
        if (wanted == 0) return 0
        file.seek(pos)
        val read = file.read(buffer, offset, wanted)
        if (read > 0) pos += read
        return read
    }

    override fun close() {
        synchronized(lock) { runCatching { file.close() } }
    }
}
