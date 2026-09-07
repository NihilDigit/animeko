/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.cache.TestMediaCache
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 整季包为其中一集建立缓存之后, 包内其他剧集也应当是本地命中.
 *
 * @see MediaCacheStorageSource
 */
class MediaCacheStorageSourceSiblingTest {
    private companion object {
        val CACHE_SOURCE_ID = MediaCacheManager.LOCAL_FS_MEDIA_SOURCE_ID
        const val SUBJECT_ID = "1"
    }

    private fun createMedia(
        mediaId: String,
        episodeRange: EpisodeRange,
    ): DefaultMedia = createTestDefaultMedia(
        mediaId = mediaId,
        mediaSourceId = "dmhy",
        originalTitle = "[桜都字幕组] 测试动画",
        download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:$mediaId"),
        originalUrl = "https://example.com/$mediaId",
        publishedTime = 1,
        episodeRange = episodeRange,
        properties = createTestMediaProperties(),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.BitTorrent,
    )

    private val packMedia = createMedia("dmhy.pack", EpisodeRange.range(1, 13))

    private fun createCache(origin: DefaultMedia, episode: Int): MediaCache = TestMediaCache(
        CachedMedia(origin, CACHE_SOURCE_ID, download = origin.download),
        MediaCacheMetadata(
            subjectId = SUBJECT_ID,
            episodeId = episode.toString(),
            subjectNames = listOf("测试动画"),
            episodeSort = EpisodeSort(episode),
            episodeEp = EpisodeSort(episode),
            episodeName = "第 $episode 集",
        ),
    )

    private fun createRequest(
        episodeSort: EpisodeSort,
        episodeEp: EpisodeSort? = episodeSort,
        episodeId: String = episodeSort.toString(),
    ) = MediaFetchRequest(
        subjectId = SUBJECT_ID,
        episodeId = episodeId,
        subjectNames = listOf("测试动画"),
        episodeSort = episodeSort,
        episodeEp = episodeEp,
        episodeName = "第 $episodeSort 集",
    )

    /**
     * 十二集正片加一个 SP. 集数从文件名解析, 与线上包同构.
     */
    private val packFiles = (1..12).map { "[桜都字幕组] 测试动画 - ${it.toString().padStart(2, '0')} [1080p].mkv" } +
            "[桜都字幕组] 测试动画 - SP01 [1080p].mkv"

    private suspend fun fetch(
        caches: List<MediaCache>,
        request: MediaFetchRequest,
        files: List<String> = packFiles,
        hitsEnabled: Boolean = true,
    ): List<MediaMatch> {
        val storage = TestMediaCacheStorage().apply { listFlow.value = caches }
        return MediaCacheStorageSource(
            storage,
            displayName = "本地",
            packFilesProvider = if (hitsEnabled) {
                { files }
            } else {
                null
            },
        ).fetch(request).results.toList()
    }

    private suspend fun fetch(
        caches: List<MediaCache>,
        episode: Int,
        files: List<String> = packFiles,
        hitsEnabled: Boolean = true,
    ): List<MediaMatch> = fetch(
        caches,
        createRequest(EpisodeSort(episode), episodeId = episode.toString()),
        files,
        hitsEnabled,
    )

    @Test
    fun `pack cached for one episode hits for another episode in range`() = runTest {
        val results = fetch(listOf(createCache(packMedia, 3)), episode = 7)

        val media = results.single().media
        assertIs<CachedMedia>(media)
        assertEquals(packMedia.mediaId, media.origin.mediaId)
        assertEquals(CACHE_SOURCE_ID, media.mediaSourceId)
        // 必须给磁链而不是 E03 已下好的文件, 播放才会走 torrent 链路并打开 E07 的文件.
        assertEquals(packMedia.download, media.download)
        assertEquals(packFiles[6], media.cacheProperties?.pathInTorrent)
    }

    @Test
    fun `SP hits a pack with special files but never by number`() = runTest {
        // 包的范围是 01..13, 按范围 SP 永远不命中; 判断依据是包里有特别篇文件.
        // 包里的 SP01 与 Bangumi 的 SP01 编号相同也不自动选, 留给用户.
        val media = fetch(
            listOf(createCache(packMedia, 3)),
            createRequest(EpisodeSort("SP01"), episodeEp = EpisodeSort(1), episodeId = "sp1"),
        ).single().media
        assertIs<CachedMedia>(media)
        assertNull(media.cacheProperties?.pathInTorrent)
    }

    @Test
    fun `SP with no labelled file hits the pack but leaves the file to the user`() = runTest {
        // 碟片的 S00E 编号与 Bangumi 的 SP 编号是两套体系, 多个 SP 文件而编号对不上时 pathInTorrent 留空,
        // 播放时弹「资源中未找到本集」由用户挑.
        val files = packFiles.dropLast(1) + "测试动画 S00E01-[1080p].mkv" + "测试动画 S00E02-[1080p].mkv"
        val media = fetch(
            listOf(createCache(packMedia, 3)),
            createRequest(EpisodeSort("SP03"), episodeEp = EpisodeSort(3), episodeId = "sp3"),
            files = files,
        ).single().media
        assertIs<CachedMedia>(media)
        assertNull(media.cacheProperties?.pathInTorrent)
    }

    @Test
    fun `SP does not hit a pack without special files`() = runTest {
        assertTrue(
            fetch(
                listOf(createCache(packMedia, 3)),
                createRequest(EpisodeSort("SP01"), episodeEp = EpisodeSort(1), episodeId = "sp1"),
                files = packFiles.dropLast(1),
            ).isEmpty(),
        )
    }

    @Test
    fun `no hit when the file list is unknown`() = runTest {
        assertTrue(fetch(listOf(createCache(packMedia, 3)), episode = 7, files = emptyList()).isEmpty())
    }

    @Test
    fun `no hit for episode outside the pack range`() = runTest {
        assertTrue(fetch(listOf(createCache(packMedia, 3)), episode = 20).isEmpty())
    }

    @Test
    fun `no hit from a single-episode cache`() = runTest {
        val single = createMedia("dmhy.single", EpisodeRange.single(EpisodeSort(3)))
        assertTrue(fetch(listOf(createCache(single, 3)), episode = 7).isEmpty())
    }

    @Test
    fun `no hit when disabled`() = runTest {
        assertTrue(
            fetch(listOf(createCache(packMedia, 3)), episode = 7, hitsEnabled = false).isEmpty(),
        )
    }

    @Test
    fun `real record for the episode replaces the derived hit`() = runTest {
        val realRecord = createCache(packMedia, 7)
        val results = fetch(listOf(createCache(packMedia, 3), realRecord), episode = 7)

        assertSame(realRecord.getCachedMedia(), results.single().media)
    }

    @Test
    fun `no hit for a different subject`() = runTest {
        val cache = TestMediaCache(
            CachedMedia(packMedia, CACHE_SOURCE_ID, download = packMedia.download),
            MediaCacheMetadata(
                subjectId = "999",
                episodeId = "3",
                subjectNames = listOf("别的番"),
                episodeSort = EpisodeSort(3),
                episodeEp = EpisodeSort(3),
                episodeName = "第 3 集",
            ),
        )
        assertTrue(fetch(listOf(cache), episode = 7).isEmpty())
    }
}
