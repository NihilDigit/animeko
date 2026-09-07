/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.player.data.TorrentMediaData
import me.him188.ani.app.domain.media.resolver.TorrentMediaResolver
import me.him188.ani.app.domain.media.resolver.toEpisodeMetadata
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.torrent.pikpak.asPackWarmUp
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin

/**
 * 播放整季包中的一集时, 把包内其他剧集对应的文件告诉会话, 让它按顺序预热.
 *
 * 目标用播放本身的选择逻辑算出来, 也就是 [TorrentMediaResolver.selectVideoFileEntry], 避免预热的文件
 * 和真正播放时选中的文件不是同一个. 顺序从下一集开始并回绕, 因为用户几乎总是顺着看下去.
 *
 * 预热本身由引擎决定要不要做 (计量网络下关闭, 见 `PikPakEngineConfig.warmUpSiblings`), 这里只负责给出目标.
 */
class WarmUpPackEpisodesExtension(
    private val context: PlayerExtensionContext,
    private val warmUpSinkOf: (TorrentSession) -> WarmUpTargetSink? = ::defaultWarmUpSinkOf,
) : PlayerExtension("WarmUpPackEpisodes") {

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("WarmUpPackEpisodes") {
            context.sessionFlow.collectLatest { session ->
                val bundle = session.infoBundleFlow.filterNotNull().first()

                context.videoLoadingStateFlow.collectLatest { state ->
                    if (state !is VideoLoadingState.Succeed || !state.isBt) return@collectLatest

                    val mediaData = context.player.mediaData
                        .filterIsInstance<TorrentMediaData>()
                        .first()
                    val torrentSession = mediaData.session ?: return@collectLatest
                    val sink = warmUpSinkOf(torrentSession) ?: return@collectLatest

                    val targets = selectWarmUpTargets(
                        files = torrentSession.getFiles(),
                        getPath = { pathInTorrent },
                        episodes = bundle.subjectCollectionInfo.episodes,
                        currentEpisodeId = bundle.episodeId,
                        currentPath = mediaData.pathInTorrent,
                    )
                    if (targets.isEmpty()) return@collectLatest

                    logger.info {
                        "Warming up ${targets.size} sibling episodes: ${targets.joinToString { it.fileName }}"
                    }
                    sink.setWarmUpTargets(targets)
                }
            }
        }
    }

    companion object : EpisodePlayerExtensionFactory<WarmUpPackEpisodesExtension> {
        private val logger = logger<WarmUpPackEpisodesExtension>()

        override fun create(context: PlayerExtensionContext, koin: Koin): WarmUpPackEpisodesExtension =
            WarmUpPackEpisodesExtension(context)
    }
}

/**
 * 接收预热目标的一方, 由 [TorrentSession] 实现.
 */
fun interface WarmUpTargetSink {
    fun setWarmUpTargets(entries: List<TorrentFileEntry>)
}

// 只有 PikPak 会话实现 PackWarmUp; anitorrent 会话返回 null, 扩展对它什么都不做.
private fun defaultWarmUpSinkOf(session: TorrentSession): WarmUpTargetSink? =
    session.asPackWarmUp()?.let { WarmUpTargetSink(it::setWarmUpTargets) }

/**
 * 按播放顺序给出要预热的文件: 当前集之后的先来, 到末尾回绕到第一集.
 *
 * 匹配不到文件的剧集直接丢掉; 匹配到当前正在播放的文件的也丢掉, 那个文件已经在下载了.
 * 用严格匹配, 匹配不上就是没有: [TorrentMediaResolver.selectVideoFileEntry] 还会拿集数去撞文件名,
 * 撞上的可能是分辨率之类的数字, 拿它预热等于下载一个无关的文件.
 *
 * 泛型而非直接收 [TorrentFileEntry], 与 [TorrentMediaResolver.selectVideoFileEntry] 同样是为了可测试.
 */
internal fun <T> selectWarmUpTargets(
    files: List<T>,
    getPath: T.() -> String,
    episodes: List<EpisodeCollectionInfo>,
    currentEpisodeId: Int,
    currentPath: String,
): List<T> {
    if (files.isEmpty() || episodes.isEmpty()) return emptyList()

    val sorted = episodes.sortedBy { it.episodeInfo.sort }
    val currentIndex = sorted.indexOfFirst { it.episodeId == currentEpisodeId }
    val ordered = if (currentIndex == -1) {
        sorted
    } else {
        sorted.subList(currentIndex + 1, sorted.size) + sorted.subList(0, currentIndex)
    }

    val seen = mutableSetOf(currentPath)
    return ordered.mapNotNull { episode ->
        val metadata = episode.episodeInfo.toEpisodeMetadata()
        val entry = TorrentMediaResolver.selectVideoFileEntryExact(
            files,
            getPath,
            listOf(metadata.title),
            episodeSort = metadata.sort,
            episodeEp = metadata.ep,
        ) ?: return@mapNotNull null
        if (!seen.add(entry.getPath())) null else entry
    }
}
