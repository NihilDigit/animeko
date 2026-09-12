/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.RangeSource
import io.ktor.utils.io.ByteReadChannel
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration

/**
 * A [RangeSource] over a byte array, recording what was asked for.
 *
 * The transport's scheduling is the thing under test — block sizes, how many
 * requests are outstanding, what a seek cancels — and none of it needs a
 * PikPak account or a socket.
 */
internal class FakeRangeSource(
    private val content: ByteArray,
    /** Held for this long before any byte is produced, so a test can seek while reads are in flight. */
    private val latency: Duration = Duration.ZERO,
    /** Offsets whose first attempt throws, standing in for a link the CDN rejected. */
    failFirstAttemptAt: Set<Long> = emptySet(),
    /**
     * The first this many reads throw, whatever they asked for, standing in for
     * a source that is unreachable for a while. Unlike [failFirstAttemptAt] it
     * is not tied to an offset, so a caller that retries the same round over
     * and over keeps failing until the budget runs out.
     */
    failFirstReads: Int = 0,
    /**
     * Withholds bytes until the test lets them through, so a test can assert
     * what a downloader does while its reads are outstanding without timing.
     */
    private val gate: ReadGate? = null,
) : RangeSource {
    data class Request(val start: Long, val length: Long)

    private val lock = SynchronizedObject()
    private val _requests = mutableListOf<Request>()
    private val _cancelled = mutableListOf<Request>()
    private val remainingFailures = failFirstAttemptAt.associateWith { 1 }.toMutableMap()
    private var remainingReadFailures = failFirstReads

    private val active = atomic(0)
    private val peakActive = atomic(0)

    val requests: List<Request> get() = synchronized(lock) { _requests.toList() }
    val cancelled: List<Request> get() = synchronized(lock) { _cancelled.toList() }
    val peakConcurrency: Int get() = peakActive.value

    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T {
        val request = Request(start, length)
        synchronized(lock) { _requests += request }
        val now = active.incrementAndGet()
        peakActive.update { maxOf(it, now) }
        try {
            val down = synchronized(lock) {
                if (remainingReadFailures <= 0) false else { remainingReadFailures--; true }
            }
            if (down) throw IllegalStateException("simulated outage at $start")
            if (latency > Duration.ZERO) delay(latency)
            gate?.acquire()
            val shouldFail = synchronized(lock) {
                val left = remainingFailures[start] ?: 0
                if (left <= 0) false else { remainingFailures[start] = left - 1; true }
            }
            if (shouldFail) throw IllegalStateException("simulated 403 at $start")
            val end = minOf(content.size.toLong(), start + length).toInt()
            return block(ByteReadChannel(content.copyOfRange(start.toInt(), end)))
        } catch (e: CancellationException) {
            synchronized(lock) { _cancelled += request }
            throw e
        } finally {
            active.decrementAndGet()
        }
    }
}

/**
 * Lets a test say how many reads may be served, rather than how long to wait.
 *
 * A request is recorded by [FakeRangeSource] before it reaches the gate, so
 * `requests` counts reads a downloader issued and the budget here counts reads
 * it got bytes for. That split is what makes "the round in flight finished but
 * no new round started" observable without a clock.
 */
internal class ReadGate(allowed: Int = 0) {
    private val lock = SynchronizedObject()

    /** Reads still permitted, or -1 for unlimited. */
    private val budget = MutableStateFlow(allowed)

    suspend fun acquire() {
        while (true) {
            budget.first { it != 0 }
            val taken = synchronized(lock) {
                val left = budget.value
                when {
                    left == 0 -> false
                    left < 0 -> true
                    else -> { budget.value = left - 1; true }
                }
            }
            if (taken) return
        }
    }

    fun allow(more: Int) {
        synchronized(lock) {
            if (budget.value >= 0) budget.value = budget.value + more
        }
    }

    fun openForever() {
        synchronized(lock) { budget.value = -1 }
    }
}

private fun kotlinx.atomicfu.AtomicInt.update(transform: (Int) -> Int) {
    while (true) {
        val current = value
        if (compareAndSet(current, transform(current))) return
    }
}
