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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeText

/**
 * What a PikPak session needs to come back from disk without touching the
 * network. Written next to the media files as `meta.json`.
 *
 * The cloud file ids in here go stale on their own: eviction deletes a bucket,
 * a resubmit produces new ids for the same bytes. That is fine and is the
 * reason the local identity of a file is `(sourceKey, pathInTorrent)` rather
 * than its file id — [PikPakDriveIndex.relocate] repairs the id and the new
 * value is written back here.
 *
 * What is deliberately absent is any record of which bytes are present. A
 * cache record is written strictly front to back, so the file's own length on
 * disk answers that, and playback writes nothing at all.
 */
@Serializable
internal data class PikPakTorrentMeta(
    // Defaults to 0, not CURRENT_VERSION: a file written before the field was
    // encoded has no version, and a missing field decodes to the default. With
    // CURRENT_VERSION as the default every old file passed the gate, which is
    // why the version 2 to 3 bump never re-indexed anything. write() stamps
    // the real version; constructors never need to.
    val version: Int = 0,
    /** The magnet or `.torrent` URL this session was created from. */
    val uri: String,
    val sourceKey: String,
    /** Display name of the torrent, i.e. the bucket's single top-level entry. */
    val name: String,
    /** Last known folder id of `Animeko-Playing/<sourceKey>`; "" when unknown. */
    val bucketId: String = "",
    /**
     * Whether [files] is the whole cloud listing rather than a few files known
     * locally. False only for a directory built by
     * [PikPakSavedFiles.importCompletedFile], which knows the one file it moved
     * and nothing about the rest of the pack.
     *
     * Defaults to true so that every meta written by a cloud index keeps its
     * meaning without the field being encoded, including the ones already on
     * disk: what those describe is exactly a full listing.
     */
    val indexed: Boolean = true,
    val files: List<PikPakFileMeta>,
) {
    companion object {
        /**
         * Version 2 dropped the piece bitmap, the piece size and the touch
         * timestamps. A directory written by version 1 is simply re-indexed
         * from the cloud: that costs one listing, and its scratch described a
         * download policy that no longer exists.
         *
         * Version 3 walks the pack folder recursively, so [files] now lists the
         * files in its subfolders too and their paths carry the subfolder. A
         * version 2 directory is not wrong, only short: it is missing whatever
         * sat below the top level. Re-indexing is again the repair, and again
         * one listing per pack, which is why the version is bumped rather than
         * the old entries being patched in place.
         *
         * [indexed] deliberately did not bump the version: a file written
         * before it existed reads as indexed, which is the truth for every
         * meta a cloud index wrote, and the only files that would be
         * mislabelled are imported ones that predate the field on a dev
         * machine.
         */
        const val CURRENT_VERSION = 3
        const val FILE_NAME = "meta.json"
    }
}

@Serializable
internal data class PikPakFileMeta(
    val index: Int,
    /**
     * Path relative to the session directory, `/`-separated. The pack folder is
     * stripped, so a file at the pack's top level has a bare name and one in a
     * subfolder keeps that subfolder, e.g. `specials/S00E01.mkv`.
     */
    val pathInTorrent: String,
    val fileId: String,
    /** `null` reads the original file; otherwise the transcode's `media_id`. */
    val mediaId: String? = null,
    /** `"Original"` or a resolution name. Part of the on-disk identity. */
    val variantLabel: String = "Original",
    /** Byte count of the chosen variant, which for a transcode is not the file's listed size. */
    val length: Long,
)

/**
 * Reads and writes the per-session on-disk state in [saveDirectory].
 *
 * Every mutation rewrites the whole `meta.json`. The file is a few hundred
 * bytes and is only written when a file id changes, so a smarter scheme would
 * buy nothing.
 */
internal class PikPakResumeData(
    private val saveDirectory: SystemPath,
) : SynchronizedObject() {
    private val metaPath get() = saveDirectory.resolve(PikPakTorrentMeta.FILE_NAME)

    /**
     * Returns null when there is no usable metadata, including when the file is
     * corrupt: the caller's fallback is to rebuild from the cloud, which is
     * always correct, so a parse failure is not worth propagating.
     */
    fun read(): PikPakTorrentMeta? = synchronized(this) {
        if (!metaPath.exists()) return null
        return try {
            val meta = json.decodeFromString(PikPakTorrentMeta.serializer(), metaPath.readText())
            if (meta.version != PikPakTorrentMeta.CURRENT_VERSION) null else meta
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            null
        }
    }

    fun write(meta: PikPakTorrentMeta): Unit = synchronized(this) {
        val stamped = meta.copy(version = PikPakTorrentMeta.CURRENT_VERSION)
        metaPath.writeText(json.encodeToString(PikPakTorrentMeta.serializer(), stamped))
    }

    /**
     * Points [pathInTorrent] at a new cloud file id after the old one stopped
     * resolving. No-op when nothing on disk mentions that path.
     */
    fun updateFileId(pathInTorrent: String, newFileId: String, newBucketId: String?): Unit = synchronized(this) {
        val meta = read() ?: return
        val updated = meta.copy(
            bucketId = newBucketId ?: meta.bucketId,
            files = meta.files.map { if (it.pathInTorrent == pathInTorrent) it.copy(fileId = newFileId) else it },
        )
        write(updated)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
