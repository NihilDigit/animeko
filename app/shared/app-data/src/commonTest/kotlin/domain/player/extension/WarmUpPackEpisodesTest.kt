/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @see selectWarmUpTargets
 */
class WarmUpPackEpisodesTest {
    /**
     * 记录会话收到的预热目标. 真实实现由 `torrent/pikpak` 的会话提供.
     */
    private class RecordingSession {
        var targets: List<String>? = null
            private set

        fun setWarmUpTargets(entries: List<String>) {
            targets = entries
        }
    }

    private val files = (1..6).map { "[字幕组] 测试动画 - 0$it [1080p].mkv" }

    private fun episode(id: Int, sort: Int) = EpisodeCollectionInfo(
        episodeInfo = EpisodeInfo(
            episodeId = id,
            type = EpisodeType.MainStory,
            nameCn = "第 $sort 集",
            sort = EpisodeSort(sort),
            ep = EpisodeSort(sort),
        ),
        collectionType = UnifiedCollectionType.NOT_COLLECTED,
    )

    private val episodes = (1..6).map { episode(id = 100 + it, sort = it) }

    private fun warmUp(currentEpisodeId: Int, currentPath: String): RecordingSession {
        val session = RecordingSession()
        session.setWarmUpTargets(
            selectWarmUpTargets(
                files = files,
                getPath = { this },
                episodes = episodes,
                currentEpisodeId = currentEpisodeId,
                currentPath = currentPath,
            ),
        )
        return session
    }

    @Test
    fun `orders next episode first and wraps around`() {
        val session = warmUp(currentEpisodeId = 103, currentPath = files[2])

        assertEquals(
            listOf(files[3], files[4], files[5], files[0], files[1]),
            session.targets,
        )
    }

    @Test
    fun `first episode gives the rest in order`() {
        val session = warmUp(currentEpisodeId = 101, currentPath = files[0])

        assertEquals(files.drop(1), session.targets)
    }

    @Test
    fun `last episode wraps to the first`() {
        val session = warmUp(currentEpisodeId = 106, currentPath = files[5])

        assertEquals(files.take(5), session.targets)
    }

    @Test
    fun `excludes the currently playing file`() {
        val session = warmUp(currentEpisodeId = 103, currentPath = files[2])

        assertTrue(session.targets?.none { it == files[2] } == true)
    }

    @Test
    fun `drops episodes that match nothing`() {
        // 只有两个文件, 其余剧集匹配不到自己的文件, 会退回到已经出现过的文件而被去重丢掉.
        val targets = selectWarmUpTargets(
            files = listOf(files[0], files[1]),
            getPath = { this },
            episodes = episodes,
            currentEpisodeId = 101,
            currentPath = files[0],
        )

        assertEquals(listOf(files[1]), targets)
    }

    @Test
    fun `special without a file is not warmed up`() {
        // SP 的 ep 与正片集数同域, 宽松匹配会把 SP 预热成正片文件.
        val withSpecial = episodes + EpisodeCollectionInfo(
            episodeInfo = EpisodeInfo(
                episodeId = 200,
                type = EpisodeType.SP,
                nameCn = "特别篇",
                sort = EpisodeSort("SP01"),
                ep = EpisodeSort(1),
            ),
            collectionType = UnifiedCollectionType.NOT_COLLECTED,
        )

        val targets = selectWarmUpTargets(
            files = files,
            getPath = { this },
            episodes = withSpecial,
            currentEpisodeId = 101,
            currentPath = files[0],
        )

        assertEquals(files.drop(1), targets)
    }

    @Test
    fun `special is not warmed up before the user picks its file`() {
        // SP 不按编号自动匹配 (见 TorrentMediaResolver), 用户没挑过就不知道该预热哪个文件.
        val spFile = "[字幕组] 测试动画 - SP01 [1080p].mkv"
        val withSpecial = episodes + EpisodeCollectionInfo(
            episodeInfo = EpisodeInfo(
                episodeId = 200,
                type = EpisodeType.SP,
                nameCn = "特别篇",
                sort = EpisodeSort("SP01"),
                ep = EpisodeSort(1),
            ),
            collectionType = UnifiedCollectionType.NOT_COLLECTED,
        )

        val targets = selectWarmUpTargets(
            files = files + spFile,
            getPath = { this },
            episodes = withSpecial,
            currentEpisodeId = 101,
            currentPath = files[0],
        )

        assertEquals(files.drop(1), targets)
    }

    @Test
    fun `no files gives no targets`() {
        assertTrue(
            selectWarmUpTargets(
                files = emptyList<String>(),
                getPath = { this },
                episodes = episodes,
                currentEpisodeId = 101,
                currentPath = "x",
            ).isEmpty(),
        )
    }

    @Test
    fun `unknown current episode keeps the natural order`() {
        val session = warmUp(currentEpisodeId = 999, currentPath = "not-in-pack.mkv")

        assertEquals(files, session.targets)
    }
}
