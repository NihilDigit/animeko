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

/**
 * 随机读写的文件句柄, 语义对齐 `java.io.RandomAccessFile`.
 *
 * 该抽象存在的原因是 kotlinx-io 只提供顺序的 [kotlinx.io.Source] / [kotlinx.io.Sink],
 * 而 PikPak 引擎需要预分配文件长度并在任意 offset 处写入已下载的分片.
 *
 * 非线程安全: [seek] 与随后的 [read] / [write] 是两步操作, 并发调用会互相干扰.
 */
expect class RandomAccessFile : AutoCloseable {
    /**
     * 将文件指针移动到 [position], 允许超过当前文件长度 (写入时会自动扩展).
     */
    fun seek(position: Long)

    /**
     * 从当前位置最多读取 [length] 字节到 [buffer] 的 [offset] 处.
     *
     * @return 实际读取的字节数; 已到文件末尾时返回 -1. 与 `InputStream.read` 一样, 可能少于 [length].
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * 从当前位置写入 [buffer] 中 [offset] 起的 [length] 字节. 写不完会一直重试, 不会部分写入后返回.
     */
    fun write(buffer: ByteArray, offset: Int, length: Int)

    /**
     * 设置文件长度. 变短时截断; 变长时新增部分的内容未定义.
     */
    fun setLength(newLength: Long)

    fun length(): Long

    override fun close()
}

/**
 * @param mode `"r"` 只读, `"rw"` 读写 (文件不存在时创建, 存在时不截断).
 */
@Suppress("FunctionName")
expect fun RandomAccessFile(file: SystemPath, mode: String): RandomAccessFile

/**
 * 读满 [length] 字节, 不足则抛 [IOException]. 对齐 `java.io.RandomAccessFile.readFully`.
 */
fun RandomAccessFile.readFully(buffer: ByteArray, offset: Int, length: Int) {
    var read = 0
    while (read < length) {
        val n = read(buffer, offset + read, length - read)
        if (n < 0) throw IOException("Unexpected EOF after reading $read of $length bytes")
        read += n
    }
}
