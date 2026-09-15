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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.download.selectTorrentStorage
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
 */
class CacheOnBtPlayExtension(
    private val context: PlayerExtensionContext,
    koin: Koin,
) : PlayerExtension("CacheOnBtPlay") {
    private val downloadManager: MediaDownloadManager by koin.inject()

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

                        val media = if (selected is CachedMedia) {
                            // 选中了正在下载中的 BT 源.
                            if (hasCacheRecordFor(selected, request)) return@collectLatest
                            selected.origin
                        } else {
                            selected
                        }

                        val storage = selectTorrentStorage(
                            downloadManager.storages,
                            media,
                            playedWith = state.engineKey,
                        )
                        if (storage == null) {
                            logger.warn { "No cache storage supports $media, skipping auto cache." }
                            return@collectLatest
                        }
                        logger.info { "Auto cache BitTorrent media on play with ${storage.engine.engineKey}: $media" }

                        val metadata = MediaCacheMetadata(request, autoCached = true)
                        downloadManager.createDownload(media, metadata, episodeMetadata, storage)
                    }
                }
            }
        }
    }

    private suspend fun hasCacheRecordFor(media: CachedMedia, request: MediaFetchRequest): Boolean =
        downloadManager.findCaches {
            it.origin.mediaId == media.origin.mediaId &&
                    it.metadata.subjectId == request.subjectId &&
                    it.metadata.episodeId == request.episodeId
        }.isNotEmpty()

    companion object : EpisodePlayerExtensionFactory<CacheOnBtPlayExtension> {
        private val logger = logger<CacheOnBtPlayExtension>()
        override fun create(context: PlayerExtensionContext, koin: Koin): CacheOnBtPlayExtension {
            return CacheOnBtPlayExtension(context, koin)
        }
    }
}
