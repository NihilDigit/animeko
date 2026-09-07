/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.persistent.database.dao.HttpCacheDownloadStateDao
import me.him188.ani.app.data.persistent.database.dao.createMemoryTorrentCacheInfoDao
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.DownloadStatus
import me.him188.ani.utils.httpdownloader.MediaType
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @see PikPakWebM3uCacheMigration
 */
class PikPakWebM3uCacheMigrationTest {
    @TempDir
    lateinit var dir: File

    private val magnet = "magnet:?xt=urn:btih:0000000000000000000000000000000000000001"

    private val media = createTestDefaultMedia(
        mediaId = "pikpak.1",
        mediaSourceId = "pikpak",
        originalTitle = "测试剧集",
        download = ResourceLocation.MagnetLink(magnet),
        originalUrl = "https://example.com/1",
        publishedTime = 0,
        episodeRange = EpisodeRange.single(EpisodeSort(1)),
        properties = createTestMediaProperties(),
        kind = MediaSourceKind.BitTorrent,
        location = MediaSourceLocation.Online,
    )

    /**
     * 只改 engine 的话, restore 认的 completed 和 pathInTorrent 仍然是空的, 搬过去的文件下次启动
     * 会被当成没下完, 又交给引擎开一次会话.
     */
    @Test
    fun `a completed entry carries its file identity into the metadata`() = runTest {
        val fileName = "01.mkv"
        val store = MemoryDataStore(listOf(save(metadata(completed =false))))
        val httpDao = FakeHttpCacheDownloadStateDao(
            downloadState(fileName, DownloadStatus.COMPLETED).also { writeLegacyFile(fileName) },
        )

        createMigration(store, httpDao).migrate()

        val saved = store.data.first().single()
        assertEquals(MediaCacheEngineKey.PikPak, saved.engine)
        assertTrue(saved.metadata.completed)
        assertEquals(fileName, saved.metadata.pathInTorrent)
    }

    /**
     * 没下完的条目残片已经删掉, 要从头下. 把它标成完成会让 restore 去开一个不存在的本地文件.
     */
    @Test
    fun `an incomplete entry is not marked completed`() = runTest {
        val fileName = "01.mkv"
        val store = MemoryDataStore(listOf(save(metadata(completed =true))))
        val httpDao = FakeHttpCacheDownloadStateDao(
            downloadState(fileName, DownloadStatus.PAUSED).also { writeLegacyFile(fileName) },
        )

        createMigration(store, httpDao).migrate()

        val saved = store.data.first().single()
        assertEquals(MediaCacheEngineKey.PikPak, saved.engine)
        assertEquals(false, saved.metadata.completed)
        assertNull(saved.metadata.pathInTorrent)
    }

    /**
     * 同一个 media 另有一条 anitorrent 记录时, 按 mediaId 回写会把它也改成 PikPak 引擎, 并塞进
     * 另一集的完成路径.
     */
    @Test
    fun `an anitorrent record of the same media is left alone`() = runTest {
        val fileName = "01.mkv"
        val anitorrent = save(metadata(completed = false)).copy(engine = MediaCacheEngineKey.Anitorrent)
        val store = MemoryDataStore(listOf(save(metadata(completed = false)), anitorrent))
        val httpDao = FakeHttpCacheDownloadStateDao(
            downloadState(fileName, DownloadStatus.COMPLETED).also { writeLegacyFile(fileName) },
        )

        createMigration(store, httpDao).migrate()

        val saved = store.data.first()
        assertEquals(listOf(MediaCacheEngineKey.PikPak, MediaCacheEngineKey.Anitorrent), saved.map { it.engine })
        assertEquals(anitorrent, saved[1])
    }

    ///////////////////////////////////////////////////////////////////////////
    // fixtures
    ///////////////////////////////////////////////////////////////////////////

    private fun createMigration(
        store: MemoryDataStore<List<MediaCacheSave>>,
        httpDao: HttpCacheDownloadStateDao,
    ) = PikPakWebM3uCacheMigration(
        metadataStore = store,
        httpDao = httpDao,
        torrentDao = createMemoryTorrentCacheInfoDao(),
        baseSaveDirProvider = object : MediaSaveDirProvider {
            override val saveDir: String = dir.absolutePath
        },
        pikpakSaveDir = File(dir, "pikpak").toKtPath().inSystem,
    )

    private fun save(metadata: MediaCacheMetadata) = MediaCacheSave(
        origin = media,
        metadata = metadata,
        engine = MediaCacheEngineKey.WebM3u,
    )

    private fun metadata(completed: Boolean) = MediaCacheMetadata(
        subjectId = "1",
        episodeId = "1",
        subjectNames = emptyList(),
        episodeSort = EpisodeSort(1),
        episodeName = "",
        completed = completed,
    )

    private fun writeLegacyFile(fileName: String) {
        File(dir, HttpMediaCacheEngine.MEDIA_CACHE_DIR).resolve(fileName).apply {
            parentFile.mkdirs()
            writeText("x")
        }
    }

    // 与 HttpMediaCacheEngine.toSafeDownloadId 一致, 迁移按 mediaId 反查这条记录.
    private fun downloadState(fileName: String, status: DownloadStatus) = DownloadState(
        downloadId = DownloadId(media.mediaId),
        url = "https://example.com/1.m3u8",
        relativeOutputPath = fileName,
        segments = emptyList(),
        totalSegments = 0,
        downloadedBytes = 1,
        timestamp = 0,
        status = status,
        relativeSegmentCacheDir = "segments",
        requestHeaders = emptyMap(),
        mediaType = MediaType.M3U8,
    )

    private class FakeHttpCacheDownloadStateDao(state: DownloadState) : HttpCacheDownloadStateDao {
        private val states = MutableStateFlow(listOf(state))

        override fun getAll(): Flow<List<DownloadState>> = states

        override suspend fun upsert(state: DownloadState) {
            states.value = states.value.filter { it.downloadId != state.downloadId } + state
        }

        override suspend fun updateStatus(id: DownloadId, status: DownloadStatus) {
            states.value = states.value.map { if (it.downloadId == id) it.copy(status = status) else it }
        }

        override suspend fun deleteAll() {
            states.value = emptyList()
        }

        override suspend fun deleteById(id: DownloadId) {
            states.value = states.value.filter { it.downloadId != id }
        }

        override suspend fun getById(id: DownloadId): DownloadState? = states.value.firstOrNull { it.downloadId == id }
    }
}
