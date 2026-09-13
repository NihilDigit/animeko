/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.him188.ani.app.torrent.api.TorrentHandleState
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.peer.PeerInfo
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class PikPakSession(
    val sourceKey: String,
    private val torrentName: String,
    val saveDirectory: SystemPath,
    private val entries: List<PikPakFileEntry>,
    override val listingComplete: Boolean,
    private val onClosed: suspend (PikPakSession) -> Unit,
    parentCoroutineContext: CoroutineContext,
) : TorrentSession, PartialListing {
    private val logger = logger<PikPakSession>()
    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]))

    @Volatile
    private var closed = false

    val totalSize: Long get() = entries.sumOf { it.streamLength }

    override val sessionStats: Flow<TorrentSession.Stats?> = flow {
        emit(null)
        var lastDelivered = entries.sumOf { it.deliveredBytes }
        var lastMark = TimeSource.Monotonic.markNow()
        while (true) {
            delay(1.seconds)
            val delivered = entries.sumOf { it.deliveredBytes }
            val elapsed = lastMark.elapsedNow()
            val speed = if (elapsed.inWholeMilliseconds <= 0) {
                0L
            } else {
                (delivered - lastDelivered) * 1000 / elapsed.inWholeMilliseconds
            }
            lastDelivered = delivered
            lastMark = TimeSource.Monotonic.markNow()

            val requested = entries.filter { it.hasOpenHandles }.ifEmpty { entries }

            val requestedSize = requested.sumOf { it.streamLength }
            val downloaded = requested.sumOf { it.downloadedBytes }
            emit(
                TorrentSession.Stats(
                    totalSizeRequested = requestedSize,
                    downloadedBytes = downloaded,
                    downloadSpeed = speed.coerceAtLeast(0),
                    uploadedBytes = 0,
                    uploadSpeed = 0,
                    downloadProgress = if (requestedSize == 0L) 0f else {
                        (downloaded.toFloat() / requestedSize).coerceIn(0f, 1f)
                    },
                ),
            )
        }
    }

    val deliveredBytes: Long get() = entries.sumOf { it.deliveredBytes }

    val downloadedBytes: Long get() = entries.sumOf { it.downloadedBytes }

    override suspend fun getName(): String = torrentName

    override suspend fun getFiles(): List<TorrentFileEntry> = entries

    override fun getPeers(): List<PeerInfo> = emptyList()

    // The states this reports are a libtorrent handle's: checking files, allocating, seeding. A
    // cloud file passes through none of them, and the interface reads null as "engine does not
    // report one".
    override fun getState(): TorrentHandleState? = null

    override suspend fun close() {
        if (closed) return
        closed = true
        logger.info { "[pikpak] closing session $sourceKey" }
        entries.forEach { runCatching { it.close() } }
        scope.cancel()
        onClosed(this)
    }

    override suspend fun closeIfNotInUse() {
        if (entries.none { it.hasOpenHandles }) {
            close()
        }
    }
}

// A partial import cannot use the "only video in this torrent" fallback for episode selection.
interface PartialListing {
    val listingComplete: Boolean
}
