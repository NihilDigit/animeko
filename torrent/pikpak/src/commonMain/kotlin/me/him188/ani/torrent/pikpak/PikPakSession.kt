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

/**
 * One magnet, one session, one directory under the engine's data root.
 *
 * There is no peer state and no share ratio here: PikPak downloads from a CDN,
 * so [getPeers] is always empty and uploaded bytes are always zero.
 */
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

    /** Paired with [downloadedBytes], so it counts the streams being read rather than the torrent's own sizes. */
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

            // "Requested" means a handle is open on it. With none open the
            // whole torrent is the denominator, otherwise progress would be
            // 0/0 for a session nobody has started reading yet.
            val requested = entries.filter { it.hasOpenHandles }.ifEmpty { entries }
            // The stream's length, not the torrent's: the bytes counted below
            // belong to whichever variant is being read, and a transcode is a
            // different size from the original.
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

    /** Bytes the CDN has delivered in this session. Feeds the downloader's aggregate speed. */
    val deliveredBytes: Long get() = entries.sumOf { it.deliveredBytes }

    val downloadedBytes: Long get() = entries.sumOf { it.downloadedBytes }

    override suspend fun getName(): String = torrentName

    override suspend fun getFiles(): List<TorrentFileEntry> = entries

    override fun getPeers(): List<PeerInfo> = emptyList()

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
