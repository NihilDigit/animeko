/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.torrent.api.files.EncodedTorrentInfo
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.copyTo
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.length
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * The on-disk layout of the engine, for callers outside it.
 *
 * Everything here is a function of a directory and a URI, never of a
 * [PikPakTorrentDownloader]: the migration off the old HTTP path works on files
 * that are already complete, and the downloader is only obtainable once the
 * account is enabled and has credentials. Tying a local file operation to the
 * account state would make it skip whenever the settings are still loading, and
 * the directory format is private to this module either way.
 */
object PikPakSavedFiles {
    private val logger = logger<PikPakSavedFiles>()

    /** The directory under [rootDataDirectory] that holds everything for [uri]. */
    fun saveDirectoryFor(rootDataDirectory: SystemPath, uri: String): SystemPath =
        rootDataDirectory.resolve(sourceKeyFor(uri))

    /**
     * What [PikPakTorrentDownloader.fetchTorrent] returns for [uri]. It does not
     * go to the network, so a caller that only records the identity of a torrent
     * need not obtain a downloader for it.
     */
    fun encodedTorrentInfoFor(uri: String): EncodedTorrentInfo =
        EncodedTorrentInfo.createRaw(encodeUri(uri))

    /**
     * Adopts a file that is already complete on disk into this engine's layout,
     * so a later `startDownload` restores it as fully downloaded and contacts
     * PikPak for nothing.
     *
     * The one caller is the migration off the old HTTP path, where the bytes
     * were fetched by a different engine and only the bookkeeping has to move.
     * The piece size, the bitmap encoding and the presence of meta.json are this
     * engine's private format; a second implementation of them elsewhere is a
     * silent-corruption bug waiting for the format to change, which it already
     * has once.
     *
     * The gcid is unknown offline and is recorded empty. That is not a
     * placeholder to fix later: a complete file never needs anything from the
     * cloud, and the next `startDownload` re-resolves the magnet, which fills
     * in the gcid of every file the torrent actually carries.
     *
     * The meta this writes is marked not indexed, because it lists the imported
     * files and nothing else. One episode of a season pack was migrated and the
     * whole magnet then restored as a one-file torrent, so opening any other
     * episode of it got the imported episode's bytes: the app picks the single
     * video of a torrent that has only one. The next `startDownload` fills the
     * listing in from the cloud, and until it succeeds the imported file stays
     * playable on its own.
     *
     * Must not run while a session for [uri] is open; the migration runs before
     * any cache is restored, which is the only time that holds by construction.
     *
     * @param source the existing file; it is moved, not copied.
     * @param pathInTorrent the name to store it under, which is the identity
     *  the rest of the engine keys on.
     * @return the file's new location.
     */
    suspend fun importCompletedFile(
        rootDataDirectory: SystemPath,
        uri: String,
        source: SystemPath,
        pathInTorrent: String,
    ): SystemPath {
        require(pathInTorrent.isNotEmpty()) { "pathInTorrent must not be empty" }
        val sourceKey = sourceKeyFor(uri)
        return withContext(Dispatchers.IO_) {
            val saveDirectory = rootDataDirectory.resolve(sourceKey)
            saveDirectory.createDirectories()
            val target = saveDirectory.resolve(pathInTorrent)
            // The one caller passes a bare name, but pathInTorrent may carry a
            // subfolder, and neither moveTo nor copyTo creates one.
            target.path.parent?.inSystem?.createDirectories()
            if (source.absolutePath != target.absolutePath) {
                try {
                    source.moveTo(target)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    // atomicMove cannot cross a filesystem boundary, and the
                    // media directory is routinely on a different volume from
                    // the app's own storage.
                    logger.info { "[pikpak] atomic move failed (${e.message}), copying instead" }
                    source.copyTo(target)
                    source.delete()
                }
            }

            // Completeness lives in the bitmap, and this file arrives complete
            // without anything having fetched it. Left unwritten, the next open
            // would read "nothing downloaded" and try to fetch the file -- which
            // does not merely waste bandwidth, it throws: an imported file has
            // no gcid, so there is nothing to recreate in the cloud from.
            val length = target.length()
            markFullyDownloaded(target, length)
            val entry = PikPakFileMeta(
                index = 0,
                pathInTorrent = pathInTorrent,
                gcid = "",
                length = length,
            )
            val resumeData = PikPakResumeData(saveDirectory)
            // A pack is imported one file at a time, so writing a fresh meta
            // here would leave the directory describing only whichever file was
            // imported last and every earlier one unreachable. read() returns
            // null for a file written by an older version, and starting over is
            // the right answer there: the directory is re-indexed later anyway.
            // Merging keeps the existing `indexed`: importing into a directory
            // that already holds the full cloud listing leaves it full.
            val existing = resumeData.read()?.takeIf { it.uri == uri && it.sourceKey == sourceKey }
            val meta = if (existing == null) {
                PikPakTorrentMeta(
                    uri = uri,
                    sourceKey = sourceKey,
                    name = pathInTorrent,
                    indexed = false,
                    files = listOf(entry),
                )
            } else if (existing.files.any { it.pathInTorrent == pathInTorrent }) {
                // Re-importing a path replaces its entry in place, keeping the
                // index, so a retried migration changes nothing.
                existing.copy(
                    files = existing.files.map {
                        if (it.pathInTorrent == pathInTorrent) entry.copy(index = it.index) else it
                    },
                )
            } else {
                existing.copy(
                    files = existing.files + entry.copy(index = (existing.files.maxOfOrNull { it.index } ?: -1) + 1),
                )
            }
            resumeData.write(meta)
            logger.info { "[pikpak] imported $pathInTorrent ($length bytes) into $sourceKey" }
            target
        }
    }

    /**
     * Writes the bitmap of a file that is already whole.
     *
     * The piece size is this engine's private format, shared with
     * [PikPakFileEntry]; a second literal for it elsewhere is a silent
     * corruption waiting for the value to change, which it already has once.
     */
    suspend fun markFullyDownloaded(target: SystemPath, length: Long) {
        val pieceCount = ((length + PikPakFileEntry.PIECE_SIZE - 1) / PikPakFileEntry.PIECE_SIZE).toInt()
        val bitmap = PieceBitmap(PieceBitmap.pathFor(target), pieceCount)
        for (i in 0 until pieceCount) bitmap.set(i)
        bitmap.flush()
    }
}
