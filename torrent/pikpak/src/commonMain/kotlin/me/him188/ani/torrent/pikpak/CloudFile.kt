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

/**
 * One file in the cloud, as a [RangeSource].
 *
 * A signed CDN link outlives the file object that minted it: measured
 * 2026-09-12, it keeps answering 206 for the 12 hours it is good for while
 * `getFile` on the deleted id answers `file_not_found`. So an object is created,
 * asked for a link and dropped, which matters because instant upload transfers
 * no bytes but is billed at the file's full size.
 *
 * Past those 12 hours, or if the CDN turns a link down, [PikPakFileHandle]
 * mints a new one by rebuilding the object from the gcid. Those arrive through
 * the same [discard] by way of `onObjectMinted`, so the rule holds for every
 * object that exists here however it came to be, and the startup sweep is left
 * with only what a crash stranded.
 */
internal class CloudFile(
    private val gcid: String,
    private val size: Long,
    private val name: String,
    private val clientProvider: suspend () -> PikPakClient,
    private val driveIndex: PikPakDriveIndex,
    private val connectionBudget: Int,
    /**
     * Read at creation time rather than captured in the constructor: a session
     * outlives a playback, because the auto-cache record holds a handle, so a
     * captured value would have the pack's unwatched episodes still reading at
     * the setting the user has since changed.
     */
    private val preferenceProvider: () -> VariantPreference,
    /** The variant `meta.json` records, which overrides [preferenceProvider]. */
    private val pinned: PinnedVariant?,
    /** Where the fire-and-forget delete runs. */
    private val scope: CoroutineScope,
) : RangeSource {
    private val logger = logger<CloudFile>()

    private val mutex = Mutex()

    private var handle: PikPakFileHandle? = null

    /** The variant being read, null being the original. Settled before [handle] is published. */
    @Volatile
    private var mediaId: String? = null

    /**
     * Which variant is being read, and how many bytes it has.
     *
     * This is also what mints the link, so an account that stopped being valid
     * surfaces here rather than inside the player's first read. There is no
     * separate open call: every caller that needs the cloud needs this answer.
     */
    suspend fun streamVariant(): StreamVariant {
        val handle = openHandle()
        return StreamVariant(mediaId, handle.streamSize())
    }

    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T = openHandle().read(start, length, priority, block)

    override suspend fun readBytes(start: Long, length: Long, priority: Int): ByteArray =
        openHandle().readBytes(start, length, priority)

    /**
     * Creates the file object, takes what only it can give, and drops it.
     *
     * The object is not left for [PikPakFileHandle] to create on its own: its
     * rebuild is deliberately unsynchronised, and a first play brings eight
     * reads in at once, each of which would find no id and make one.
     *
     * `prewarm` mints the link and `streamSize` probes a transcode's length;
     * both have to reach the object, which is why [discard] is left to the
     * handle's own signal rather than called here — it fires once a link is in
     * hand, and every later rebuild reports the same way.
     */
    private suspend fun openHandle(): PikPakFileHandle = handle ?: mutex.withLock {
        handle?.let { return@withLock it }
        // An empty gcid means a complete local file the migration moved in, or
        // a file PikPak did not index while the rest of its pack was. Failing
        // here is what sends TorrentMediaResolver to anitorrent, so an episode
        // BT can serve stays playable.
        if (gcid.isEmpty()) {
            throw PikPakNotIndexedException(name, "$name has no gcid; it exists only on disk")
        }
        val client = clientProvider()
        val parentId = driveIndex.tempFolderId()
        val fileId = client.instantCreate(
            ResolvedFile(path = name, size = size, gcid = gcid),
            parentId = parentId,
            name = name,
        )
        // Not `pinned?.mediaId ?: selectVariant(...)`: a recorded original is
        // itself a null mediaId, which the elvis would fall through and pick
        // again from the setting.
        mediaId = if (pinned != null) pinned.mediaId else selectVariant(client, fileId)
        PikPakFileHandle(
            client = client,
            gcid = gcid,
            size = size,
            name = name,
            initialFileId = fileId,
            mediaId = mediaId,
            parentId = parentId,
            connectionBudget = connectionBudget,
            onObjectMinted = ::discard,
        ).also {
            it.prewarm()
            it.streamSize()
            handle = it
        }
    }

    /**
     * Drops a file object whose link has been minted.
     *
     * Not awaited: the round trip is about 550 ms and nothing downstream waits
     * on it, and the handle calls this from the path a read is waiting on. A
     * failure leaves the object for the next startup sweep, which beats turning
     * a cloud hiccup into a failed playback.
     */
    private suspend fun discard(fileId: String) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    driveIndex.delete(listOf(fileId))
                    logger.info { "[pikpak] dropped the file object for $name; its link stands on its own" }
                } catch (e: Throwable) {
                    logger.warn(e) { "[pikpak] could not drop $fileId; the next startup sweep takes it" }
                }
            }
        }
    }

    /**
     * The mediaId of the variant to read; the original is null and costs no
     * request. A transcode costs one `getFile` and buys an id the handle looks
     * links up by from then on, so the variant cannot change mid-read.
     */
    private suspend fun selectVariant(client: PikPakClient, fileId: String): String? {
        val preference = preferenceProvider()
        if (preference !is VariantPreference.Resolution) return null
        val detail = client.getFile(fileId)
        val resolved = detail.resolveVariant(preference)
        if (resolved.isOrigin) {
            // resolveVariant falls back silently, which looks like the quality
            // setting did nothing. Only one of the two reasons is worth waiting out.
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

/** The variant `meta.json` records. A null [mediaId] means the original, which differs from "not recorded". */
internal class PinnedVariant(val mediaId: String?)

/** The stream an entry is actually reading. */
internal data class StreamVariant(val mediaId: String?, val length: Long)
