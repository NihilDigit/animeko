/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.RangeSource
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.VariantPreference
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.instantCreate
import io.github.nihildigit.pikpak.resolveVariant
import io.ktor.utils.io.ByteReadChannel
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// GCID identifies content across accounts. Cloud objects exist only to mint signed links,
// which remain readable after those objects are deleted.
internal class CloudFile(
    private val gcid: String,
    private val size: Long,
    private val name: String,
    private val accountProvider: suspend () -> PikPakAccount,
    private val connectionBudget: Int,
    private val preferenceProvider: () -> VariantPreference,
    private val pinned: PinnedVariant?,
    private val scope: CoroutineScope,
) : RangeSource {
    private val logger = logger<CloudFile>()

    private val mutex = Mutex()

    private var handle: Pair<PikPakAccount, PikPakFileHandle>? = null
    private var variantDecided = false

    @Volatile
    private var mediaId: String? = null

    suspend fun streamVariant(): StreamVariant {
        val handle = openHandle()
        return StreamVariant(mediaId, handle.streamSize())
    }

    // Range requests go straight to the CDN, not through the app's HTTP client, so this is the only
    // place they can be seen at all.
    private val inFlight = atomic(0)

    private val rangeLog = ThroughputLog { bytes, requests, elapsed ->
        logger.info {
            "[pikpak] $name $requests range requests, $bytes B in $elapsed, " +
                    "${kilobytesPerSecond(bytes, elapsed)} kB/s"
        }
    }

    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T = timeRange(start, length) { openHandle().read(start, length, priority, block) }

    override suspend fun readBytes(start: Long, length: Long, priority: Int): ByteArray =
        timeRange(start, length) { openHandle().readBytes(start, length, priority) }

    private suspend inline fun <T> timeRange(start: Long, length: Long, body: () -> T): T {
        val mark = TimeSource.Monotonic.markNow()
        // PikPak refuses a ninth simultaneous connection to one signed URL. A refusal that hangs
        // instead of failing looks exactly like a stalled read, so a stall has to say how many
        // requests were in flight beside it.
        val entered = inFlight.incrementAndGet()
        try {
            val result = body()
            val elapsed = mark.elapsedNow()
            if (elapsed >= SLOW_RANGE) {
                logger.warn {
                    "[pikpak] $name range $start+$length took $elapsed, " +
                            "$entered in flight when it started, ${inFlight.value} now"
                }
            }
            rangeLog.record(length)
            return result
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private suspend fun openHandle(): PikPakFileHandle = mutex.withLock {
        val account = accountProvider()
        handle?.takeIf { it.first === account }?.let { return@withLock it.second }
        // In-flight reads may still hold the old handle; let them finish on their original client.
        handle = null

        if (gcid.isEmpty()) {
            throw PikPakNotIndexedException(name, "$name has no gcid; it exists only on disk")
        }
        val sweepMark = TimeSource.Monotonic.markNow()
        account.startupSweep.join()
        if (sweepMark.elapsedNow() >= SLOW_RANGE) {
            logger.warn { "[pikpak] $name waited ${sweepMark.elapsedNow()} for the startup sweep" }
        }
        val client = account.client
        val parentId = account.driveIndex.tempFolderId()
        val fileId = client.instantCreate(
            ResolvedFile(path = name, size = size, gcid = gcid),
            parentId = parentId,
            name = name,
        )

        // Changing accounts must preserve the encoding of bytes already cached locally.
        if (!variantDecided) {
            mediaId = if (pinned != null) pinned.mediaId else selectVariant(client, fileId)
            variantDecided = true
        }
        PikPakFileHandle(
            client = client,
            gcid = gcid,
            size = size,
            name = name,
            initialFileId = fileId,
            mediaId = mediaId,
            parentId = parentId,
            connectionBudget = connectionBudget,
            onObjectMinted = { discard(account, it) },
        ).also {
            val mark = TimeSource.Monotonic.markNow()
            it.prewarm()
            it.streamSize()
            logger.info { "[pikpak] $name link resolved in ${mark.elapsedNow()}" }
            handle = account to it
        }
    }

    private suspend fun discard(account: PikPakAccount, fileId: String) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    account.driveIndex.delete(listOf(fileId))
                    logger.info { "[pikpak] dropped the file object for $name; its link stands on its own" }
                } catch (e: Throwable) {
                    logger.warn(e) { "[pikpak] could not drop $fileId; the next startup sweep takes it" }
                }
            }
        }
    }

    private suspend fun selectVariant(client: PikPakClient, fileId: String): String? {
        val preference = preferenceProvider()
        if (preference !is VariantPreference.Resolution) return null
        val detail = client.getFile(fileId)
        val resolved = detail.resolveVariant(preference)
        if (resolved.isOrigin) {
            val transcoding = detail.medias.any {
                !it.isOrigin && (it.resolutionName == preference.name || it.mediaName == preference.name)
            }
            logger.info {
                "[pikpak] $name wanted ${preference.name}, reading the original instead: " +
                        if (transcoding) "that resolution is still transcoding" else "PikPak has no such variant"
            }
        }
        return resolved.mediaId
    }
}

private val SLOW_RANGE = 5.seconds

internal class PinnedVariant(val mediaId: String?)

internal data class StreamVariant(val mediaId: String?, val length: Long)
