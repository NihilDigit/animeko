/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import androidx.datastore.core.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import me.him188.ani.app.data.persistent.database.dao.HttpCacheDownloadStateDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.toSafeDownloadId
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.httpdownloader.DownloadStatus
import me.him188.ani.torrent.pikpak.PikPakSavedFiles
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.length
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

// Completed HTTP downloads are imported as local files. Partial downloads restart because
// the old representation may differ from the variant selected by the new engine.
class PikPakWebM3uCacheMigration(
    private val metadataStore: DataStore<List<MediaCacheSave>>,
    private val httpDao: HttpCacheDownloadStateDao,
    private val torrentDao: TorrentCacheInfoDao,
    private val baseSaveDirProvider: MediaSaveDirProvider,
    private val pikpakSaveDir: SystemPath,
) {
    private val logger = logger<PikPakWebM3uCacheMigration>()

    suspend fun migrate() {
        val candidates = metadataStore.data.first().filter { it.isLegacyPikPakCache() }
        if (candidates.isEmpty()) return

        val migrated = mutableMapOf<String, MigratedFile>()
        for (save in candidates) {
            try {
                migrated[save.origin.mediaId] = migrateOne(save.origin)
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to migrate legacy PikPak cache ${save.origin.mediaId}, leaving it as is." }
            }
        }
        if (migrated.isEmpty()) return

        metadataStore.updateData { list ->
            list.map { save ->
                if (!save.isLegacyPikPakCache()) return@map save
                val result = migrated[save.origin.mediaId] ?: return@map save

                save.copy(
                    engine = MediaCacheEngineKey.PikPak,
                    metadata = if (result.complete) {
                        save.metadata.copy(completed = true, pathInTorrent = result.pathInTorrent)
                    } else {
                        save.metadata.copy(completed = false)
                    },
                )
            }
        }

        // Metadata is the migration commit point; retain HTTP rows until it is persisted for crash recovery.
        for (result in migrated.values) {
            result.legacyDownloadId?.let {
                runCatching { httpDao.deleteById(it) }
                    .onFailure { e -> logger.warn(e) { "Failed to drop legacy HTTP cache row $it" } }
            }
        }
        logger.info { "Migrated ${migrated.size} legacy PikPak caches to the torrent engine layout." }
    }

    private class MigratedFile(
        val complete: Boolean,
        val pathInTorrent: String,
        val legacyDownloadId: DownloadId?,
    )

    private suspend fun migrateOne(origin: Media): MigratedFile {
        val downloadId = origin.toSafeDownloadId()
        val state = httpDao.getById(downloadId)
        val webM3uDir = Path(baseSaveDirProvider.saveDir, HttpMediaCacheEngine.MEDIA_CACHE_DIR)

        val uri = origin.download.uri

        val torrentData = PikPakSavedFiles.encodedTorrentInfoFor(uri)
        val targetDir = PikPakSavedFiles.saveDirectoryFor(pikpakSaveDir, uri)

        val sourceFile = state?.relativeOutputPath
            ?.let { Path(webM3uDir, it).inSystem }
            ?.takeIf { withContext(Dispatchers.IO_) { it.exists() } }

        val importedName = state?.relativeOutputPath?.substringAfterLast('/')
        // A previous attempt may have moved the file but stopped before committing metadata.
        val alreadyImported = sourceFile == null && importedName != null &&
                withContext(Dispatchers.IO_) {
                    targetDir.resolve(importedName).run { exists() && length() > 0 }
                }

        val complete = alreadyImported ||
                (state?.status == DownloadStatus.COMPLETED && sourceFile != null)

        val pathInTorrent = if (alreadyImported) {
            checkNotNull(importedName)
        } else if (complete) {
            val fileName = checkNotNull(state).relativeOutputPath.substringAfterLast('/')

            PikPakSavedFiles.importCompletedFile(
                rootDataDirectory = pikpakSaveDir,
                uri = uri,
                source = checkNotNull(sourceFile),
                pathInTorrent = fileName,
            )
            fileName
        } else {
            withContext(Dispatchers.IO_) {
                sourceFile?.deleteRecursively()
                state?.relativeSegmentCacheDir
                    ?.let { Path(webM3uDir, it).inSystem }
                    ?.takeIf { it.exists() }
                    ?.deleteRecursively()
            }
            ""
        }

        val relativeDir = targetDir.absolutePath.substringAfter(baseSaveDirProvider.saveDir).also {
            check(it != targetDir.absolutePath) {
                "Failed to strip ${targetDir.absolutePath} of base ${baseSaveDirProvider.saveDir}"
            }
        }
        torrentDao.upsert(
            TorrentCacheInfoEntity(
                mediaId = origin.mediaId,
                torrentData = torrentData.data,
                relativeDir = relativeDir,
                completed = complete,
                pathInTorrent = pathInTorrent,
            ),
        )

        return MigratedFile(complete = complete, pathInTorrent = pathInTorrent, legacyDownloadId = state?.downloadId)
    }

    private fun MediaCacheSave.isLegacyPikPakCache(): Boolean =
        engine == MediaCacheEngineKey.WebM3u && origin.kind == MediaSourceKind.BitTorrent

}
