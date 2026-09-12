/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.io

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import me.him188.ani.utils.io.SystemPath
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.errno
import platform.posix.fclose
import platform.posix.feof
import platform.posix.ferror
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.ftruncate
import platform.posix.fwrite
import platform.posix.strerror

// 基于 stdio FILE* 而非裸 fd: pread/pwrite 在 Kotlin/Native 的 posix 包里不完整, 而 fseeko/ftello 覆盖 64 位 offset.
// C 标准要求 "r+b" 下读写切换之间必须有定位操作, 调用方每次读写前都会先 seek, 满足该要求.
@OptIn(ExperimentalForeignApi::class)
actual class RandomAccessFile internal constructor(
    private val handle: CPointer<FILE>,
    private val path: String,
) : AutoCloseable {
    private var closed = false

    actual fun seek(position: Long) {
        require(position >= 0) { "position must be non-negative, but was $position" }
        checkNotClosed()
        if (fseeko(handle, position.convert(), SEEK_SET) != 0) {
            throw IOException("Failed to seek to $position in $path: ${lastErrorMessage()}")
        }
    }

    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkBounds(buffer, offset, length)
        checkNotClosed()
        if (length == 0) return 0

        val read = buffer.usePinned { pinned ->
            fread(pinned.addressOf(offset), 1.convert(), length.convert(), handle).toInt()
        }
        if (read > 0) return read
        // fread 返回 0 时无法区分 EOF 与错误, 必须查 ferror
        if (ferror(handle) != 0) {
            throw IOException("Failed to read $length bytes from $path: ${lastErrorMessage()}")
        }
        check(feof(handle) != 0) { "fread returned 0 on $path without EOF or error" }
        return -1
    }

    actual fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkBounds(buffer, offset, length)
        checkNotClosed()
        if (length == 0) return

        buffer.usePinned { pinned ->
            var written = 0
            while (written < length) {
                val n = fwrite(
                    pinned.addressOf(offset + written),
                    1.convert(),
                    (length - written).convert(),
                    handle,
                ).toInt()
                if (n <= 0) {
                    throw IOException(
                        "Failed to write $length bytes to $path after $written bytes: ${lastErrorMessage()}",
                    )
                }
                written += n
            }
        }
    }

    actual fun setLength(newLength: Long) {
        require(newLength >= 0) { "newLength must be non-negative, but was $newLength" }
        checkNotClosed()
        // ftruncate 走的是 fd, 绕过 stdio 缓冲, 不先 flush 会让缓冲中的写入在截断之后落盘
        flush()
        if (ftruncate(fileno(handle), newLength.convert()) != 0) {
            throw IOException("Failed to set length of $path to $newLength: ${lastErrorMessage()}")
        }
        // 对齐 java.io.RandomAccessFile: 截断后指针超出文件末尾时移到末尾
        if (position() > newLength) {
            seek(newLength)
        }
    }

    actual fun length(): Long {
        checkNotClosed()
        flush()
        val current = position()
        if (fseeko(handle, 0.convert(), SEEK_END) != 0) {
            throw IOException("Failed to seek to end of $path: ${lastErrorMessage()}")
        }
        val end = position()
        if (fseeko(handle, current.convert(), SEEK_SET) != 0) {
            throw IOException("Failed to restore position $current in $path: ${lastErrorMessage()}")
        }
        return end
    }

    actual override fun close() {
        if (closed) return
        closed = true
        if (fclose(handle) != 0) {
            throw IOException("Failed to close $path: ${lastErrorMessage()}")
        }
    }

    private fun position(): Long {
        val pos = ftello(handle).toLong()
        if (pos < 0) throw IOException("Failed to get position in $path: ${lastErrorMessage()}")
        return pos
    }

    actual fun flush() {
        checkNotClosed()
        if (fflush(handle) != 0) {
            throw IOException("Failed to flush $path: ${lastErrorMessage()}")
        }
    }

    private fun checkNotClosed() {
        if (closed) throw IOException("RandomAccessFile for $path is closed")
    }

    private fun checkBounds(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0) { "offset must be non-negative, but was $offset" }
        require(length >= 0) { "length must be non-negative, but was $length" }
        require(offset + length <= buffer.size) {
            "offset + length must not exceed buffer size, but was ${offset + length} > ${buffer.size}"
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun lastErrorMessage(): String = strerror(errno)?.toKString() ?: "errno=$errno"

@OptIn(ExperimentalForeignApi::class)
@Suppress("FunctionName")
actual fun RandomAccessFile(file: SystemPath, mode: String): RandomAccessFile {
    val path = file.path.toString()
    val handle = when (mode) {
        // "r+b" 不会创建文件, "w+b" 会截断已有文件, 两者都不单独等价于 java 的 "rw"; 先试前者再退到后者
        "rw" -> fopen(path, "r+b") ?: fopen(path, "w+b")
        "r" -> fopen(path, "rb")
        else -> throw IllegalArgumentException("Unsupported mode: $mode, expected \"r\" or \"rw\"")
    } ?: throw IOException("Failed to open $path in mode $mode: ${lastErrorMessage()}")

    return RandomAccessFile(handle, path)
}
