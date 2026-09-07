/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.cache.requester.preferredCacheEngineKey
import me.him188.ani.app.domain.media.resolver.toEpisodeMetadata
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.core.Koin

/**
 * Automatically create a cache task when playback is handed to the local
 * BitTorrent engine.
 *
 * The gate uses the post-resolve [VideoLoadingState.Succeed.isBt] flag rather
 * than the pre-resolve [me.him188.ani.datasources.api.source.MediaSourceKind],
 * so a cloud offline backend (e.g. PikPak) that intercepts BT magnets and
 * returns a plain HTTPS URL does not trigger a redundant anitorrent download
 * in the background — and a runtime fallback from such a backend back to
 * anitorrent is still caught.
 *
 * 记录一定建立, 不看任何开关, 建立之后也不再撤销. 记录在, 本地缓存数据源下次才有命中, 重进这一集
 * 或在整季包里切集才不用再走一遍网络选择器 (约 16 秒). 「要不要把整个文件下下来」是记录上的另一件事,
 * 见 [me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine].
 *
 * 曾经在切集和关闭时删掉「一个字节都没下」的自动记录. 那条规则按下载进度判断记录有没有用, 而记录的
 * 用处本来就不是下载: 播了几秒就切走的一集同样需要它来命中本地. 实际后果是下载饥饿时记录被删掉,
 * 整季包重新回到网络选择器. 空记录不占什么, 规则已删除.
 */
class CacheOnBtPlayExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
) : PlayerExtension("CacheOnBtPlay") {
    private val mediaCacheManager: MediaCacheManager by koin.inject()

    override fun onStart(episodeSession: EpisodeSession, backgroundTaskScope: ExtensionBackgroundTaskScope) {
        backgroundTaskScope.launch("CacheOnBtPlay") {
            context.sessionFlow.collectLatest { session ->
                val episodeMetadata = session.infoBundleFlow.filterNotNull().first().episodeInfo.toEpisodeMetadata()

                session.fetchSelectFlow.collectLatest fsf@{ bundle ->
                    if (bundle == null) return@fsf

                    context.videoLoadingStateFlow.collectLatest { state ->
                        if (state !is VideoLoadingState.Succeed || !state.isBt) return@collectLatest

                        val selected = bundle.mediaSelector.selected.filterNotNull().first()
                        val request = bundle.mediaFetchSession.request.first()
                        // 整季包为其中一集建立缓存后, 包内其他剧集在选择器里也是本地命中, 同样是 CachedMedia,
                        // 但这一集还没有自己的缓存记录, 要在这里补上. 已有记录的 (正在下载中的 BT 缓存) 才跳过.
                        val media = if (selected is CachedMedia) {
                            if (hasCacheRecordFor(selected, request)) return@collectLatest
                            selected.origin
                        } else {
                            selected
                        }

                        // 不能写死 anitorrent: PikPak 也是一个 TorrentEngine, 它播放时这里同样成立,
                        // 写死会在后台再起一份 anitorrent 下载 (安卓上还会拉起 BT service).
                        val supported = mediaCacheManager.storagesIncludingDisabled
                            .filter { it.engine.supports(media) }
                        // 记录跟着真正提供播放的引擎走. 首选引擎失败时播放由后面的引擎接手, 按首选引擎建记录
                        // 就是在一个根本没在播的引擎上凭空起一份下载. 拿不到时 (旧路径、非 torrent 播放)
                        // 退回与用户手动缓存 (EpisodeCacheRequester) 一致的规则.
                        val engineKey = state.engineKey
                            ?: preferredCacheEngineKey(media, supported.map { it.engine.engineKey })
                        val storage = supported.firstOrNull { it.engine.engineKey == engineKey }
                            ?: supported.firstOrNull()
                        if (storage == null) {
                            logger.warn { "No cache storage supports $media, skipping auto cache." }
                            return@collectLatest
                        }
                        logger.info { "Auto cache BitTorrent media on play with ${storage.engine.engineKey}: $media" }

                        val metadata = MediaCacheMetadata(request, autoCached = true)
                        storage.cache(media, metadata, episodeMetadata, resume = true)
                    }
                }
            }
        }
    }

    private suspend fun hasCacheRecordFor(media: CachedMedia, request: MediaFetchRequest): Boolean =
        mediaCacheManager.storagesIncludingDisabled.any { storage ->
            storage.listFlow.first().any {
                it.origin.mediaId == media.origin.mediaId &&
                        it.metadata.subjectId == request.subjectId &&
                        it.metadata.episodeId == request.episodeId
            }
        }

    companion object : EpisodePlayerExtensionFactory<CacheOnBtPlayExtension> {
        private val logger = logger<CacheOnBtPlayExtension>()
        override fun create(context: PlayerExtensionContext, koin: Koin): CacheOnBtPlayExtension {
            return CacheOnBtPlayExtension(context, koin)
        }
    }
}
