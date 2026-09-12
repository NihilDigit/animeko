/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.io

import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.toFile
import java.io.RandomAccessFile as JavaRandomAccessFile

actual class RandomAccessFile internal constructor(
    private val delegate: JavaRandomAccessFile,
) : AutoCloseable {
    actual fun seek(position: Long) {
        delegate.seek(position)
    }

    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

    // JavaRandomAccessFile.write 本身就是写满语义, 不会部分写入
    actual fun write(buffer: ByteArray, offset: Int, length: Int) {
        delegate.write(buffer, offset, length)
    }

    // JavaRandomAccessFile 的写直达 fd, 没有用户态缓冲要推出去
    actual fun flush() = Unit

    actual fun setLength(newLength: Long) {
        delegate.setLength(newLength)
    }

    actual fun length(): Long = delegate.length()

    actual override fun close() {
        delegate.close()
    }
}

@Suppress("FunctionName")
actual fun RandomAccessFile(file: SystemPath, mode: String): RandomAccessFile =
    RandomAccessFile(JavaRandomAccessFile(file.toFile(), mode))
