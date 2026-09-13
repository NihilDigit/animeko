/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.io

import kotlinx.io.IOException
import me.him188.ani.utils.io.SystemPath

// Preallocation and out-of-order pieces require random writes unavailable in kotlinx-io.
// A handle is not thread-safe: seek and its subsequent I/O must be serialized together.
expect class RandomAccessFile : AutoCloseable {
    fun seek(position: Long)

    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    // Must write the full range or throw; callers publish piece completion after this returns.
    fun write(buffer: ByteArray, offset: Int, length: Int)

    // Makes prior writes visible to other handles; this is not a durable fsync guarantee.
    fun flush()

    fun setLength(newLength: Long)

    fun length(): Long

    override fun close()
}

@Suppress("FunctionName")
expect fun RandomAccessFile(file: SystemPath, mode: String): RandomAccessFile

fun RandomAccessFile.readFully(buffer: ByteArray, offset: Int, length: Int) {
    var read = 0
    while (read < length) {
        val n = read(buffer, offset + read, length - read)
        if (n < 0) throw IOException("Unexpected EOF after reading $read of $length bytes")
        read += n
    }
}
