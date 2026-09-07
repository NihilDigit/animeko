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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val onClosed: suspend (PikPakSession) -> Unit,
    parentCoroutineContext: CoroutineContext,
) : TorrentSession, PackWarmUp {
    private val logger = logger<PikPakSession>()
    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]))

    @Volatile
    private var closed = false

    val totalSize: Long = entries.sumOf { it.length }

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
            val requestedSize = requested.sumOf { it.length }
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

    @Volatile
    private var warmUpJob: Job? = null

    /**
     * Resolves the signed link of every named entry, and nothing else.
     *
     * Warm-up used to fetch the head and tail of the neighbouring episodes,
     * which spent the viewer's bandwidth on files they might never open and
     * left scratch that then needed a lifetime policy of its own. What
     * switching episode actually waits for is the `getFile` round trip, so
     * that is the only thing prefetched now. Nothing is downloaded and nothing
     * is written to disk.
     */
    override fun setWarmUpTargets(entries: List<TorrentFileEntry>) {
        // Matched against this session's own entries rather than trusted: an
        // entry from another session has another file and another reader.
        val mine = entries.mapNotNull { candidate -> this.entries.firstOrNull { it === candidate } }
        warmUpJob?.cancel()
        if (mine.isEmpty()) {
            logger.info { "[pikpak] $sourceKey warm-up targets cleared" }
            return
        }
        logger.info {
            "[pikpak] $sourceKey resolving links for ${mine.joinToString(limit = 5) { it.fileName }}"
        }
        warmUpJob = scope.launch { prewarmLinks(mine) }
    }

    /**
     * The bound is on the API, not on bandwidth: these are `getFile` calls and
     * the SDK's rate limiter serialises whatever it must. Half a dozen at once
     * is enough to hide the latency of a pack without crowding out the request
     * the player itself may be making.
     */
    private suspend fun prewarmLinks(targets: List<PikPakFileEntry>) = coroutineScope {
        val gate = Semaphore(WARM_UP_LINK_CONCURRENCY)
        for (entry in targets) {
            launch {
                gate.withPermit {
                    runCatching { entry.prewarmLinkAndWait() }
                        .onFailure {
                            logger.info { "[pikpak] link prefetch of ${entry.fileName} failed: ${it.message}" }
                        }
                }
            }
        }
    }

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

    override suspend fun closeIfNotInUse(): Boolean {
        if (entries.any { it.hasOpenHandles }) return false
        close()
        return true
    }

    private companion object {
        const val WARM_UP_LINK_CONCURRENCY = 6
    }
}
