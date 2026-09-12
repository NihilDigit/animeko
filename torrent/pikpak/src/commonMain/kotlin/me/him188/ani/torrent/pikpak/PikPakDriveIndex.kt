/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FolderNotFoundException
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.getOrCreateDeepFolderId
import io.github.nihildigit.pikpak.getPathFolderId
import io.github.nihildigit.pikpak.listFiles
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * The engine's two folders in the user's drive, and the only place that names them.
 *
 * [TEMP_FOLDER] holds the file objects this engine creates to mint a CDN link,
 * each dropped a round trip later, so it is empty unless something is mid-open
 * or a crash stranded one. Deleting anything in it is always safe, an object
 * another device just made included: a minted link outlives its object.
 *
 * [LEGACY_FOLDER] is what the offline-download engine parked in the drive to
 * make a replay instant, billed at full size. Nothing writes there any more and
 * the sweep never touches it; the one-time upgrade notice is the only thing that
 * offers to clear it, and the user keeping it is a complete outcome.
 *
 * Two folders rather than one because "is this mine to delete?" has no answer
 * inside a shared one. A local journal of every id this process created used to
 * answer it, which the link-outlives-object measurement made pointless.
 */
internal class PikPakDriveIndex(
    private val clientProvider: suspend () -> PikPakClient,
    private val tempFolderName: String = TEMP_FOLDER,
    private val legacyFolderName: String = LEGACY_FOLDER,
) {
    private val logger = logger<PikPakDriveIndex>()

    private val lock = Mutex()

    /** Resolving costs a listing of the drive root and sits on the path of every file open. */
    private var cachedTempFolderId: String? = null

    /**
     * The temp folder, created if absent. A file object needs a parent, so
     * creating here is unavoidable.
     *
     * The lock is held across the call rather than around each read of the
     * field: releasing it would let every concurrent first open resolve the
     * folder for itself.
     */
    suspend fun tempFolderId(): String = lock.withLock {
        cachedTempFolderId ?: clientProvider()
            .getOrCreateDeepFolderId(parentId = "", path = tempFolderName)
            .also { cachedTempFolderId = it }
    }

    /**
     * What the temp folder holds right now, empty when there is no such folder.
     *
     * Resolved without creating: the sweep runs at every startup, and mkdir -p
     * semantics would plant an empty folder in the drive root of every user who
     * enables PikPak and never plays anything.
     */
    suspend fun listTemp(): List<FileStat> = try {
        listFolder(existingTempFolderId())
    } catch (e: PikPakException) {
        // A remembered id can point at a folder deleted from elsewhere, and
        // listing it is the only way to find out. Once: a second 404 is not a
        // stale id.
        if (!isFolderGone(e)) throw e
        logger.info { "[pikpak] temp folder no longer exists; resolving it again" }
        invalidate()
        listFolder(existingTempFolderId())
    }

    /** What the old engine left in the drive, empty when there is no such folder. Read by the upgrade notice. */
    suspend fun listLegacy(): List<FileStat> = listFolder(folderIdOrNull(legacyFolderName))

    suspend fun delete(fileIds: List<String>) {
        if (fileIds.isEmpty()) return
        clientProvider().batchDelete(fileIds)
    }

    private suspend fun invalidate() {
        lock.withLock { cachedTempFolderId = null }
        clientProvider().clearFolderIdCache()
    }

    private suspend fun existingTempFolderId(): String? = lock.withLock {
        cachedTempFolderId ?: folderIdOrNull(tempFolderName)?.also { cachedTempFolderId = it }
    }

    private suspend fun folderIdOrNull(name: String): String? = try {
        clientProvider().getPathFolderId(name)
    } catch (e: FolderNotFoundException) {
        null
    }

    private suspend fun listFolder(id: String?): List<FileStat> =
        id?.let { clientProvider().listFiles(parentId = it) } ?: emptyList()

    companion object {
        /** Renamed along with the change of meaning; [LEGACY_FOLDER] is the old name and is never written to. */
        const val TEMP_FOLDER = "Animeko-Temp"

        const val LEGACY_FOLDER = "Animeko-Playing"
    }
}

/**
 * Whether a call failed because the folder it named is gone, rather than because
 * this one request was unlucky: a 5xx or a timeout must not cost a still-valid
 * remembered id. PikPak answers 404 with `file_not_found` for a deleted folder
 * and a deleted file alike.
 */
internal fun isFolderGone(e: PikPakException): Boolean =
    e.httpStatus == 404 || e.errorMessage.contains("not_found")
