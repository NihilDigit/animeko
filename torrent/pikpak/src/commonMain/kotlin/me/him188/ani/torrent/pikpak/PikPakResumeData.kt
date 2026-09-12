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
 * A torrent's file listing, stored as `meta.json` beside its media files so
 * that opening it again does not cost another login and `resolveMagnet`. A
 * season pack is one directory shared by every episode in it, so the listing is
 * resolved once and every later episode of that pack reads it from here.
 *
 * The durable identity is each file's gcid: a cloud object is deleted as soon
 * as nothing reads it, so its id outlives nothing, while one `instantCreate`
 * turns a gcid back into a readable object.
 *
 * Which bytes are already there is deliberately absent: a cache download writes
 * strictly in order, so the file's own length on disk is the answer.
 */
@Serializable
internal data class PikPakTorrentMeta(
    // Defaults to 0, not CURRENT_VERSION: a file written before this field
    // existed decodes to the default, and a default of CURRENT_VERSION waves
    // every one of them through the version gate — which is why the 2 to 3 bump
    // re-indexed nothing. write() stamps the real number.
    val version: Int = 0,
    val uri: String,
    val sourceKey: String,
    val name: String,
    /**
     * Whether [files] is the torrent's own listing rather than the few files
     * known locally. Only [PikPakSavedFiles.importCompletedFile] writes false.
     */
    val indexed: Boolean = true,
    val files: List<PikPakFileMeta>,
) {
    companion object {
        /**
         * A directory of any older version is re-indexed. Its bytes survive:
         * PikPak addresses by content, so the gcid a magnet resolves to now is
         * the same content that was downloaded then.
         *
         * [PikPakFileMeta.mediaId] and [PikPakFileMeta.variantLength] did not
         * get a bump of their own when they appeared. Re-indexing writes a meta
         * without variant records either, so the test has to be "bytes on disk
         * but no variant recorded means the original", and that holds for a
         * version 4 directory and a re-indexed one alike.
         */
        const val CURRENT_VERSION = 4
        const val FILE_NAME = "meta.json"
    }
}

@Serializable
internal data class PikPakFileMeta(
    val index: Int,
    /** Path inside the torrent, `/`-separated, e.g. `specials/S00E01.mkv`. */
    val pathInTorrent: String,
    /**
     * Empty for a file that exists only on disk: what
     * [PikPakSavedFiles.importCompletedFile] moves in needs nothing from the cloud.
     */
    val gcid: String = "",
    /** The torrent's own length. Listing, episode matching and display use it. */
    val length: Long,
    /**
     * Which variant the bytes on disk belong to; null is the original. Written
     * with [variantLength] before the first byte and never rewritten: another
     * variant is another stream, and continuing one from the other's bytes
     * corrupts silently.
     */
    val mediaId: String? = null,
    /**
     * Byte count of [mediaId]'s stream, probed. Completion and resume compare
     * against it, not [length]. Null with bytes already on disk means those
     * bytes predate this field or came from an import, both of them originals.
     */
    val variantLength: Long? = null,
)

/** Reads and writes the listing in [saveDirectory]. Every change rewrites the whole file. */
internal class PikPakResumeData(
    private val saveDirectory: SystemPath,
) : SynchronizedObject() {
    private val metaPath get() = saveDirectory.resolve(PikPakTorrentMeta.FILE_NAME)

    /** Null when there is nothing usable, a corrupt file included: the caller re-indexes, which is always correct. */
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

    /**
     * Records one file's variant and writes the other entries back unchanged: a
     * pack shares one `meta.json` and each episode settles its variant on its
     * own first download. Does nothing before the file exists; the download
     * carries on and the next session decides again.
     */
    fun recordVariant(pathInTorrent: String, mediaId: String?, variantLength: Long): Unit = synchronized(this) {
        val meta = read() ?: return
        write(
            meta.copy(
                files = meta.files.map {
                    if (it.pathInTorrent == pathInTorrent) {
                        it.copy(mediaId = mediaId, variantLength = variantLength)
                    } else {
                        it
                    }
                },
            ),
        )
    }

    fun write(meta: PikPakTorrentMeta): Unit = synchronized(this) {
        val stamped = meta.copy(version = PikPakTorrentMeta.CURRENT_VERSION)
        metaPath.writeText(json.encodeToString(PikPakTorrentMeta.serializer(), stamped))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
