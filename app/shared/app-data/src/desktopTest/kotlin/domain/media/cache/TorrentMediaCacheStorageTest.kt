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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.cache.storage.TorrentMediaCacheStorage
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.torrent.api.files.AbstractTorrentFileEntry
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.unwrapCached
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * @see TorrentMediaCacheStorage
 */
class TorrentMediaCacheStorageTest : AbstractTorrentMediaCacheEngineTest() {

    private val metadataStore: DataStore<List<MediaCacheSave>> = MemoryDataStore(emptyList())
    private val storages = mutableListOf<TorrentMediaCacheStorage>()

    private val metadataFlow = metadataStore.data
        .map { list ->
            list
                .filter { it.engine == CacheEngineKey }
                .sortedBy { it.origin.mediaId } // consistent stable order
        }


    private fun cleanup() {
        storages.forEach { it.close() }
        storages.clear()
    }

    private fun runTest(
        context: CoroutineContext = EmptyCoroutineContext,
        timeout: Duration = 5.seconds,
        testBody: suspend TestScope.() -> Unit
    ) = kotlinx.coroutines.test.runTest(context, timeout) {
        try {
            testBody()
        } finally {
            cleanup()
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // 下载策略
    ///////////////////////////////////////////////////////////////////////////

    /**
     * 自动记录在开关关闭时只跟随播放: 记录建立并 resume, 但不给文件提优先级, 引擎因此不为它取字节.
     */
    @Test
    fun `auto cache follows playback while full download is off`() = runTest {
        val storage = createStorage(
            createEngine(
                fullDownloadForAutoCaches = false,
                onDownloadStarted = { it.onTorrentChecked() },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(autoCached = true), resume = true)

        assertEquals(FilePriority.IGNORE, cache.requestingPriority())
    }

    @Test
    fun `explicit cache downloads fully while full download is off`() = runTest {
        val storage = createStorage(
            createEngine(
                fullDownloadForAutoCaches = false,
                onDownloadStarted = { it.onTorrentChecked() },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(autoCached = false), resume = true)

        assertEquals(FilePriority.NORMAL, cache.requestingPriority())
    }

    /**
     * 缓存页上的恢复按钮表达「我要这一条」, 自动记录就此转成显式记录, 之后一律下满.
     */
    @Test
    fun `resumeByUser turns an auto cache into an explicit one`() = runTest {
        val storage = createStorage(
            createEngine(
                fullDownloadForAutoCaches = false,
                onDownloadStarted = { it.onTorrentChecked() },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(autoCached = true), resume = true)
        assertEquals(FilePriority.IGNORE, cache.requestingPriority())

        cache.resumeByUser()
        advanceUntilIdle()
        assertEquals(false, cache.metadata.autoCached)
        assertEquals(FilePriority.NORMAL, cache.requestingPriority())
        // 转换要落盘, 否则重启后又变回自动记录
        assertEquals(
            false,
            metadataFlow.first().single { it.origin.mediaId == cache.origin.mediaId }.metadata.autoCached,
        )
    }

    /**
     * 整季包各集的记录共用一行 torrent_cache. 曾经删掉其中一集就把行删了, 同一个包的其他剧集在下次
     * 启动时 restore 返回 null, 无声消失.
     */
    @Test
    fun `deleting one record of a pack keeps the row for the others`() = runTest {
        val storage = createStorage(
            createEngine(onDownloadStarted = { it.onTorrentChecked() }),
        )

        val ep1 = storage.cache(testMedia, mediaCacheMetadata(episodeId = "1"), resume = false)
        val ep2 = storage.cache(testMedia, mediaCacheMetadata(episodeId = "2"), resume = false)
        assertEquals(2, storage.listFlow.first().size)

        assertEquals(true, storage.delete(ep1))
        assertEquals(listOf(ep2), storage.listFlow.first())
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))

        assertEquals(true, storage.delete(ep2))
        assertNull(torrentInfoDatabase.get(testMedia.mediaId))
    }

    /**
     * 「下完了」是每条记录自己的事. 数据库行按 mediaId 建主键, 整季包里最先下完的那个文件写进行里,
     * 曾经让包内每一集都恢复成指向那一个文件的 LocalFileMediaCache.
     */
    @Test
    fun `a completed record does not make its pack siblings completed`() = runTest {
        val storage = createStorage(
            createEngine(onDownloadStarted = { it.onTorrentChecked() }),
        )
        val ep1 = storage.cache(testMedia, mediaCacheMetadata(episodeId = "1"), resume = false)
        storage.cache(testMedia, mediaCacheMetadata(episodeId = "2"), resume = false)

        val path = assertNotNull(ep1.fileHandle.entry.first()).pathInTorrent
        val row = assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
        // 行是种子粒度的, 它确实记着「有文件下完了」
        torrentInfoDatabase.upsert(row.copy(completed = true, pathInTorrent = path))
        File(dir, row.relativeDir).resolve(path).apply {
            parentFile.mkdirs()
            writeText("x")
        }
        // 只有第一集自己标记了完成
        metadataStore.updateData { list ->
            list.map { save ->
                if (save.metadata.episodeId == "1") {
                    save.copy(metadata = save.metadata.copy(completed = true, pathInTorrent = path))
                } else {
                    save
                }
            }
        }
        storage.close()

        val restored = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        restored.restorePersistedCaches()

        // 恢复跑在 IO 线程上, 虚拟时间推不动它; 列表只在全部恢复完成后整体更新, 等它出现两条即可.
        val byEpisode = restored.listFlow.first { it.size == 2 }.associateBy { it.metadata.episodeId }
        assertIs<LocalFileMediaCache>(byEpisode.getValue("1"))
        assertIs<TorrentMediaCacheEngine.TorrentMediaCache>(byEpisode.getValue("2"))
    }

    /**
     * 引擎在设置里被关掉 (PikPak 被禁用或凭据被清空) 时 isSupported 为 false. restore 曾经先问
     * supports 再看有没有本地文件, 已经下完的记录就此在重启后从缓存列表里消失.
     */
    @Test
    fun `a completed record restores while the engine is disabled`() = runTest {
        val storage = createStorage(
            createEngine(onDownloadStarted = { it.onTorrentChecked() }),
        )
        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        val path = assertNotNull(cache.fileHandle.entry.first()).pathInTorrent
        val row = assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
        File(dir, row.relativeDir).resolve(path).apply {
            parentFile.mkdirs()
            writeText("x")
        }
        metadataStore.updateData { list ->
            list.map { save -> save.copy(metadata = save.metadata.copy(completed = true, pathInTorrent = path)) }
        }
        storage.close()

        val disabled = UnsupportedTorrentEngine(createTestAnitorrentEngine(coroutineContext))
        val restored = createStorage(createEngine(engine = disabled))
        restored.restorePersistedCaches()

        // 恢复跑在 IO 线程上, 虚拟时间推不动它.
        assertIs<LocalFileMediaCache>(restored.listFlow.first { it.isNotEmpty() }.single())
    }

    /** 除 [isSupported] 之外都照旧: 要测的正是引擎关掉之后还能不能恢复出已完成的记录. */
    private class UnsupportedTorrentEngine(
        private val delegate: TorrentEngine,
    ) : TorrentEngine by delegate {
        override val isSupported: Boolean get() = false
    }

    private suspend fun TorrentMediaCacheEngine.TorrentMediaCache.requestingPriority(): FilePriority {
        val entry = assertNotNull(fileHandle.entry.first())
        return (entry as AbstractTorrentFileEntry).requestingPriority
    }

    private fun TestScope.createStorage(engine: TorrentMediaCacheEngine = createEngine()): TorrentMediaCacheStorage {
        return TorrentMediaCacheStorage(
            CACHE_MEDIA_SOURCE_ID,
            metadataStore,
            engine.also { cacheEngine = it },
            MutableStateFlow(1.2f),
            "本地",
            this.coroutineContext,
        ).also {
            storages.add(it)
        }
    }

    private fun mediaCacheMetadata(autoCached: Boolean = false, episodeId: String = "1") = MediaCacheMetadata(
        subjectId = "1",
        episodeId = episodeId,
        subjectNameCN = "1",
        subjectNames = emptyList(),
        episodeSort = EpisodeSort("02"),
        episodeEp = EpisodeSort("02"),
        episodeName = "测试剧集",
        autoCached = autoCached,
    )

    ///////////////////////////////////////////////////////////////////////////
    // simple create, restore, find
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `create cache then get from listFlow`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        assertSame(cache, storage.listFlow.first().single())
        assertNotNull(torrentInfoDatabase.get("dmhy.2"))
    }

    private suspend fun TorrentMediaCacheStorage.cache(
        media: DefaultMedia,
        metadata: MediaCacheMetadata,
        resume: Boolean
    ) = cache(
        media,
        metadata,
        EpisodeMetadata("Test", null, EpisodeSort(1)), // doesn't matter, as we only test BT engine.
        resume,
    )

    @Test
    fun `create cache saves metadata`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)

        metadataFlow.first().filter { it.origin.mediaId == cache.origin.mediaId }.run {
            assertEquals(1, size)
            assertEquals(cache.origin.mediaId, first().origin.mediaId)
        }

        assertSame(cache, storage.listFlow.first().single())
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
    }

    @Test
    fun `create same cache twice`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        assertSame(cache, storage.listFlow.first().single())
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
        assertSame(cache, storage.cache(testMedia, mediaCacheMetadata(), resume = false))
        assertSame(cache, storage.listFlow.first().single())
        assertEquals(1, torrentInfoDatabase.getAll().first().size)
    }

    @Test
    fun `create and delete`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)

        metadataFlow.first().filter { it.origin.mediaId == cache.origin.mediaId }.run {
            assertEquals(1, size)
            assertEquals(cache.origin.mediaId, first().origin.mediaId)
        }
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))

        assertNotNull(cache.fileHandle.state.first()).run {
            assertNotNull(handle)
            assertNotNull(entry)
        }

        assertEquals(cache, storage.listFlow.first().single())
        assertEquals(true, storage.delete(cache))

        metadataFlow.first().filter { it.origin.mediaId == cache.origin.mediaId }.run {
            assertEquals(0, size)
        }
        assertEquals(null, storage.listFlow.first().firstOrNull())
        assertNull(torrentInfoDatabase.get(testMedia.mediaId))
    }

    ///////////////////////////////////////////////////////////////////////////
    // restore
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `restorePersistedCaches - nothing`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )
        storage.restorePersistedCaches()
        assertEquals(0, storage.listFlow.first().size)
    }

    @Test
    fun `restorePersistedCaches restores cache when requested immediately after construction`() = runTest {
        val originalStorage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )
        val metadata = mediaCacheMetadata()
        val originalCache = originalStorage.cache(testMedia, metadata, resume = false)
        originalStorage.close()

        val restoredStorage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        restoredStorage.restorePersistedCaches()

        val restoredCache = restoredStorage.listFlow.first { it.isNotEmpty() }.single()
        assertEquals(originalCache.origin.mediaId, restoredCache.origin.mediaId)
        assertEquals(metadata, restoredCache.metadata)
    }

    ///////////////////////////////////////////////////////////////////////////
    // cacheMediaSource
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `query cacheMediaSource`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val metadata = mediaCacheMetadata()
        val cache = storage.cache(testMedia, metadata, resume = false)

        assertEquals(
            cache.getCachedMedia().unwrapCached(),
            storage.cacheMediaSource.fetch(
                MediaFetchRequest(
                    subjectId = "1",
                    episodeId = "1",
                    subjectNames = metadata.subjectNames,
                    episodeSort = metadata.episodeSort,
                    episodeName = metadata.episodeName,
                ),
            ).results.toList().single().media.unwrapCached(),
        )
        assertNotNull(torrentInfoDatabase.get(cache.origin.mediaId))
    }

    ///////////////////////////////////////////////////////////////////////////
    // metadata
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `cached media id`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)

        assertNotNull(cache.fileHandle.state.first()).run {
            assertNotNull(handle)
        }

        val cachedMedia = cache.getCachedMedia()
        assertEquals("$CACHE_MEDIA_SOURCE_ID:${testMedia.mediaId}", cachedMedia.mediaId)
        assertEquals(CACHE_MEDIA_SOURCE_ID, cachedMedia.mediaSourceId)
        assertEquals(testMedia, cachedMedia.origin)
    }

    @Test
    fun `create two caches with same episode id`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val metadata = mediaCacheMetadata()
        val testMedia2 = createTestDefaultMedia(
            mediaId = "dmhy.3",
            mediaSourceId = "dmhy",
            originalTitle = "夜晚的水母不会游泳 02 测试剧集2",
            download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:2"),
            originalUrl = "https://example.com/2",
            publishedTime = 1724493292759,
            episodeRange = EpisodeRange.single(EpisodeSort(2)),
            properties = createTestMediaProperties(),
            kind = MediaSourceKind.BitTorrent,
            location = MediaSourceLocation.Online,
        )

        storage.cache(testMedia, metadata, resume = false)
        storage.cache(testMedia2, metadata, resume = false)

        assertEquals(2, storage.listFlow.first().size)
        assertEquals(2, torrentInfoDatabase.getAll().first().size)
    }
}
