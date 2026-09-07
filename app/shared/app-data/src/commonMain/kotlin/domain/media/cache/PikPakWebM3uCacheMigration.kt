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
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 把旧 HTTP 链路 (`MediaCacheEngineKey.WebM3u`) 上的 BT 缓存搬到 PikPak 引擎的布局里.
 *
 * 旧链路把 PikPak 解析出的直链当作普通 HTTP 下载, 缓存落在 `<saveDir>/web-m3u/` 下; 新链路的
 * 文件身份是 `(sourceKey, pathInTorrent)`, 落在 `<saveDir>/pikpak/<sourceKey>/` 下.
 *
 * 已下载完的条目原地改名搬过去并补一份全 1 的 piece 位图, 之后走 `LocalFileMediaCache`, 不再访问云端.
 * 没下完的条目丢弃残片, 重新下载: 旧链路的分段产物与新链路的 piece 布局对不上, 换算不划算.
 *
 * 迁移是幂等的 —— 判据是「engine 为 WebM3u 且来源是 BT」, 处理过的条目 engine 已改成 pikpak.
 *
 * 目录名、piece 大小、位图格式都是引擎的私有约定, 本文件一概不碰: 搬运由
 * [PikPakSavedFiles.importCompletedFile] 完成, 这里只改数据库里的账. 见
 * docs/pikpak-ani-design.md 4.5.
 *
 * 只需要引擎的数据目录, 不需要引擎本身: 搬的是已经完整的文件, 与账号是否启用、凭据是否有效无关.
 * 经 `getDownloader()` 去拿会门控在 `isSupported` 上, 设置尚未读出时它恒为 false, 迁移就会被无故推迟.
 */
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
                // 同一个 media 可能另有一条 anitorrent 记录, 它不在候选里, 不能跟着改引擎.
                if (!save.isLegacyPikPakCache()) return@map save
                val result = migrated[save.origin.mediaId] ?: return@map save
                // 完成与文件路径要一并写进记录: restore 认的是记录上的这两个字段, 只改 engine
                // 的话搬过去的文件下次启动仍然会被当成没下完, 交给引擎重新开一次会话.
                save.copy(
                    engine = MediaCacheEngineKey.PikPak,
                    metadata = if (result.complete) {
                        save.metadata.copy(completed = true, pathInTorrent = result.pathInTorrent)
                    } else {
                        // 残片已经删掉了, 这条记录要从头下. pathInTorrent 不动: 它可能是用户
                        // 为这一集挑的文件, 与下没下完无关.
                        save.metadata.copy(completed = false)
                    },
                )
            }
        }
        logger.info { "Migrated ${migrated.size} legacy PikPak caches to the torrent engine layout." }
    }

    /** [migrateOne] 对一条记录的判定, 由 [migrate] 写回 metadata. */
    private class MigratedFile(val complete: Boolean, val pathInTorrent: String)

    private suspend fun migrateOne(origin: Media): MigratedFile {
        val downloadId = origin.toLegacyDownloadId()
        val state = httpDao.getById(downloadId)
        val webM3uDir = Path(baseSaveDirProvider.saveDir, HttpMediaCacheEngine.MEDIA_CACHE_DIR)

        val uri = origin.download.uri
        // 目录与 sourceKey 的换算由引擎模块决定, 这里不复制那套规则.
        val torrentData = PikPakSavedFiles.encodedTorrentInfoFor(uri)
        val targetDir = PikPakSavedFiles.saveDirectoryFor(pikpakSaveDir, uri)

        val sourceFile = state?.relativeOutputPath
            ?.let { Path(webM3uDir, it).inSystem }
            ?.takeIf { withContext(Dispatchers.IO_) { it.exists() } }
        val complete = state?.status == DownloadStatus.COMPLETED && sourceFile != null

        val pathInTorrent = if (complete) {
            val fileName = checkNotNull(state).relativeOutputPath.substringAfterLast('/')
            // piece 大小、位图编码与 meta.json 是引擎的私有格式, 由引擎自己写.
            // 在这里复制一份的代价已经发生过一次: 迁移首版写的是按位打包的位图和
            // 硬编码的 2 MiB piece, 引擎读不出来, 而且没有报错的地方.
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
                engine = MediaCacheEngineKey.PikPak.key,
                torrentData = torrentData.data,
                relativeDir = relativeDir,
                completed = complete,
                pathInTorrent = pathInTorrent,
            ),
        )
        state?.let { httpDao.deleteById(it.downloadId) }
        return MigratedFile(complete = complete, pathInTorrent = pathInTorrent)
    }

    private fun MediaCacheSave.isLegacyPikPakCache(): Boolean =
        engine == MediaCacheEngineKey.WebM3u && origin.kind == MediaSourceKind.BitTorrent

    // 与 HttpMediaCacheEngine.toSafeDownloadId 相同; 那个是 private, 且旧链路即将退役, 不值得为它开放 API.
    private fun Media.toLegacyDownloadId(): DownloadId =
        DownloadId(mediaId.replace(PATH_AFFECTING_CHARS_REGEX, "-"))

    private companion object {
        private val PATH_AFFECTING_CHARS_REGEX = Regex("[\\\\/:*?\"<>|]")
    }
}
