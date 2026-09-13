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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Reads are far too frequent to log one by one. A byte-sized window would stay silent exactly when
// the stream is slow, which is when the numbers are worth having, so the window is wall-clock.
internal class ThroughputLog(
    private val interval: Duration = 5.seconds,
    private val report: (bytes: Long, count: Int, elapsed: Duration) -> Unit,
) {
    private val lock = SynchronizedObject()

    // Started at the first record, not at construction: a reader can sit idle for a minute before
    // its first request, and counting that idle time reports a rate the transfer never ran at.
    private var mark: TimeSource.Monotonic.ValueTimeMark? = null
    private var bytes = 0L
    private var count = 0

    fun record(bytes: Long) {
        val due = synchronized(lock) {
            this.bytes += bytes
            this.count++
            val elapsed = (mark ?: TimeSource.Monotonic.markNow().also { mark = it }).elapsedNow()
            if (elapsed < interval) return
            Report(this.bytes, this.count, elapsed).also {
                this.bytes = 0
                this.count = 0
                mark = null
            }
        }
        report(due.bytes, due.count, due.elapsed)
    }

    fun restart() = synchronized(lock) {
        bytes = 0
        count = 0
        mark = null
    }

    private class Report(val bytes: Long, val count: Int, val elapsed: Duration)
}

// Bytes per millisecond is kB/s, no conversion needed.
internal fun kilobytesPerSecond(bytes: Long, elapsed: Duration): Long =
    bytes / elapsed.inWholeMilliseconds.coerceAtLeast(1)
