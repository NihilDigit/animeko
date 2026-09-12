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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration

/**
 * Stands in for [CloudFile], keeping only the side an entry can observe: a link
 * is minted once and every read after that goes straight to the bytes.
 *
 * There is no cloud object to model. The real one is dropped a round trip after
 * it is created, so nothing outside [CloudFile] can tell whether it still
 * exists — that is what a signed link outliving its object buys.
 */
internal class FakeCloudFile(
    content: ByteArray,
    latency: Duration = Duration.ZERO,
    gate: ReadGate? = null,
) : RangeSource {
    private val delegate = FakeRangeSource(content, latency = latency, gate = gate)
    private val lock = SynchronizedObject()

    /** How many times a link was minted. One open plus any number of reads must leave this at 1. */
    var createCount = 0
        private set

    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T {
        mintOnce()
        return delegate.read(start, length, priority, block)
    }

    suspend fun open() = mintOnce()

    private fun mintOnce() {
        synchronized(lock) { if (createCount == 0) createCount++ }
    }
}
