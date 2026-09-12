/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.requester

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.requester.CacheRequestStage.MediaSelected
import me.him188.ani.app.domain.media.cache.storage.MediaCacheStorage
import me.him188.ani.app.domain.media.cache.storage.contains
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.MediaFetcher
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchResult
import me.him188.ani.app.domain.media.fetch.awaitCompletion
import me.him188.ani.app.domain.media.fetch.create
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.MediaSelectorFactory
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.contains
import me.him188.ani.datasources.api.topic.isSingleEpisode
import me.him188.ani.datasources.api.unwrapCached


/**
 * 剧集缓存请求工具.
 *
 * @see CacheRequestStage
 */
interface EpisodeCacheRequester {
    // 为什么不叫 MediaCacheRequester: 
    // 因为这个类依赖 [EpisodeCacheRequest], which 需要使用 subject 和 episode 的信息. 
    // 它实际上超出了 Media 的范围.

    /**
     * 当前请求进行到的阶段
     */
    val stage: StateFlow<CacheRequestStage>

    /**
     * 取消已有的请求, 并开始一个新的请求.
     */
    suspend fun request(request: EpisodeCacheRequest): CacheRequestStage.SelectMedia

    /**
     * 取消当前请求. 若没有请求则不做任何事情.
     */
    suspend fun cancelRequest()
}

/**
 * 当前进行中的或已经完成的请求, 为 `null` 时表示没有请求.
 */
val EpisodeCacheRequester.request: Flow<EpisodeCacheRequest?>
    get() = stage.map {
        when (it) {
            is CacheRequestStage.Done -> it.request
            CacheRequestStage.Idle -> null
            is CacheRequestStage.Working -> it.request
        }
    }

val EpisodeCacheRequester.mediaSourceResults: Flow<List<MediaSourceFetchResult>>
    get() = stage.map { it.mediaSourceResults }

data class EpisodeCacheRequest(
    val subjectInfo: SubjectInfo,
    val episodeInfo: EpisodeInfo,
)

//suspend fun EpisodeCacheRequester.request(
//    subjectId: Int, episodeId: Int, subjectManager: SubjectManager,
//): CacheRequestStage.SelectMedia {
//    val subjectInfo = subjectManager.getSubjectInfo(subjectId)
//    val episodeInfo = subjectManager.getEpisodeInfo(episodeId)
//    return request(EpisodeCacheRequest(subjectInfo, episodeInfo))
//}

fun EpisodeCacheRequester(
    mediaFetcherLazy: Flow<MediaFetcher>,
    mediaSelectorFactory: MediaSelectorFactory,
    storagesLazy: Flow<List<MediaCacheStorage>>,
//    flowContext: CoroutineContext = Dispatchers.Default,
): EpisodeCacheRequester = EpisodeCacheRequesterImpl(
    mediaFetcherLazy, mediaSelectorFactory, storagesLazy,
//    flowContext
)

class EpisodeCacheRequesterImpl(
    private val mediaFetcherLazy: Flow<MediaFetcher>,
    private val mediaSelectorFactory: MediaSelectorFactory,
    private val storagesLazy: Flow<List<MediaCacheStorage>>,
//    private val flowContext: CoroutineContext = EmptyCoroutineContext,
//    private val enableCaching: Boolean = true,
) : EpisodeCacheRequester { // TODO: consider lifecycle of EpisodeCacheRequesterImpl
    override val stage = MutableStateFlow<CacheRequestStage>(CacheRequestStage.Idle)

    sealed class AbstractWorkingStage(
        override val request: EpisodeCacheRequest,
    ) : CacheRequestStage.Working {
        private val _attemptedTrySelect = MutableStateFlow(false)
        final override val attemptedTrySelect: StateFlow<Boolean> get() = _attemptedTrySelect
        final override fun markAttemptedTrySelect() {
            _attemptedTrySelect.value = true
        }
    }

    sealed interface CloseableStage : CacheRequestStage, AutoCloseable {
        override fun close()
    }

    inner class SelectMedia(
        request: EpisodeCacheRequest,
        override val fetchSession: MediaFetchSession,
    ) : CacheRequestStage.SelectMedia, AbstractWorkingStage(request), CloseableStage {
        override val mediaSelector: MediaSelector by lazy {
            mediaSelectorFactory.create(
                request.subjectInfo.subjectId,
                episodeId = request.episodeInfo.episodeId,
                fetchSession.cumulativeResults,
            )
        }

        override suspend fun select(media: Media): CacheRequestStage.SelectStorage {
            stageLock.withLock {
                checkStageLocked()
                mediaSelector.select(media) // always success
                return switchStageLocked {
                    SelectStorage(
                        this,
                        media,
                        candidateStorages(media),
                    )
                }
            }
        }

        override suspend fun tryAutoSelectByCachedSeason(existingCaches: List<MediaCache>): MediaSelected? {
            val existing: MediaCache
            return stageLock.withLock {
                checkStageLocked()

                try {
                    existing = existingCaches.firstOrNull {
                        val episodeRange = it.origin.episodeRange
                        if (episodeRange == null || episodeRange.isSingleEpisode()) return@firstOrNull false

                        (request.episodeInfo.ep != null && episodeRange.contains(request.episodeInfo.ep))
                                || episodeRange.contains(request.episodeInfo.sort)
                    } ?: return null

                    val origin = existing.origin.unwrapCached()
                    switchStageLocked {
                        SelectStorage(
                            this,
                            origin,
                            candidateStorages(origin),
                        )
                    }
                } finally {
                    markAttemptedTrySelect() // 仅在确认状态修改完成, 或者不需要修改状态后才更新
                }
            }.run {
                try {
                    trySelectByCache(existing) ?: this
                } catch (_: StaleStageException) {
                    // This can happen because we left lock before attempting trySelectByCache.
                    // It means someone else has already changed the stage. So we just ignore the exception.
                    this
                }
            }
        }

        override suspend fun tryAutoSelectByPreference(): CacheRequestStage.SelectStorage? {
            stageLock.withLock {
                checkStageLocked()
                try {
                    fetchSession.awaitCompletion()
                    val selected = mediaSelector.trySelectDefault()

                    if (selected != null) {
                        return switchStageLocked {
                            SelectStorage(
                                this,
                                selected,
                                candidateStorages(selected),
                            )
                        }
                    }
                } finally {
                    markAttemptedTrySelect() // 仅在确认状态修改完成, 或者不需要修改状态后才更新
                }
                return null
            }
        }

        override suspend fun cancel(): CacheRequestStage.Idle {
            stageLock.withLock {
                checkStageLocked()
                return cancelRequestLocked(CacheRequestStage.Idle)
            }
        }

        override fun close() {
        }
    }

    inner class SelectStorage(
        private val previous: SelectMedia,
        private val selectedMedia: Media,
        // 已经由 candidateStorages 过滤好. 过滤本身要问云端引擎供不供得了这条资源, 是挂起的,
        // 放不进构造器.
        override val storages: List<MediaCacheStorage>,
    ) : CacheRequestStage.SelectStorage, AbstractWorkingStage(previous.request), CloseableStage {

        override val fetchSession: MediaFetchSession get() = previous.fetchSession
        override val mediaSelector: MediaSelector get() = previous.mediaSelector

        override suspend fun select(storage: MediaCacheStorage): CacheRequestStage.Done {
            stageLock.withLock {
                checkStageLocked()
                return switchStageLocked {
                    CacheRequestStage.Done(
                        request, selectedMedia, storage,
                        MediaCacheMetadata(fetchSession.request.first()),
                    )
                }.also {
                    this.close()
                }
            }
        }

        override suspend fun trySelectByCache(mediaCache: MediaCache): CacheRequestStage.Done? {
            stageLock.withLock {
                checkStageLocked()
                try {
                    val storage = storages.firstOrNull { it.contains(mediaCache) } ?: return null
                    return switchStageLocked {
                        CacheRequestStage.Done(
                            request, selectedMedia, storage,
                            MediaCacheMetadata(fetchSession.request.first()),
                        )
                    }.also {
                        this.close()
                    }
                } finally {
                    markAttemptedTrySelect() // 仅在确认状态修改完成, 或者不需要修改状态后才更新
                }
            }
        }

        override suspend fun cancel(): CacheRequestStage.SelectMedia {
            stageLock.withLock {
                checkStageLocked()
                return cancelRequestLocked(previous)
            }
        }

        override fun close() {
            previous.close()
        }
    }

    private val stageLock = Mutex()

    /**
     * 能承担 [media] 的缓存目标, 已按首选引擎收窄.
     */
    private suspend fun candidateStorages(media: Media): List<MediaCacheStorage> {
        val supported = storagesLazy.first().filter { it.engine.supports(media) }
        val preferred = preferredCacheEngineKey(media, supported.map { it.engine }) ?: return supported
        return supported.filter { it.engine.engineKey == preferred }
    }

    override suspend fun request(request: EpisodeCacheRequest): CacheRequestStage.SelectMedia {
        stageLock.withLock {
            val current = stage.value
            current.close()
            val new = SelectMedia(
                request,
                mediaFetcherLazy.first().newSession(
                    MediaFetchRequest.Companion.create(request.subjectInfo, request.episodeInfo),
                ),
            )
            stage.value = new
            return new
        }
    }

    override suspend fun cancelRequest() {
        stageLock.withLock {
            cancelRequestLocked(CacheRequestStage.Idle)
        }
    }

    private fun CacheRequestStage.close() {
        if (this is CloseableStage) close()
    }

    private fun <T : CacheRequestStage> cancelRequestLocked(previous: T): T {
        val current = stage.value
        current.close()
        stage.value = previous
        return previous
    }

    private fun CacheRequestStage.checkStageLocked() {
        val curr = stage.value
        if (curr !== this) throw StaleStageException(this, curr)
    }

    private inline fun <R : CacheRequestStage> CacheRequestStage.switchStageLocked(
        block: () -> R,
    ): R {
        checkStageLocked()
        return block().also {
            stage.value = it
        }
    }
}

/**
 * 给定一个 media 和当前可用的引擎, 选出应当承担缓存的那个. 播放时自动缓存 (CacheOnBtPlayExtension)
 * 与用户手动缓存必须选同一个引擎, 否则会出现两份下载.
 *
 * 挂起是因为 PikPak 供不供得了某条磁链只有问过云端才知道, 约 150 ms 的纯查询. 没有在创建失败后
 * 重试, 是因为洞在这里: [MediaCacheEngine.supports] 只看
 * download 是不是磁链, 静态地永远为真, 选错引擎之后 createCache 只会抛出去.
 */
internal suspend fun preferredCacheEngineKey(
    media: Media,
    supported: List<MediaCacheEngine>,
): MediaCacheEngineKey? {
    if (media.kind != MediaSourceKind.BitTorrent) return null

    // PikPak 引擎启用时接管 BT 源: 磁链交给云端离线, 再按 piece 拉回本地. 它索引不到这条磁链
    // (或云盘已满) 时退回 anitorrent, 后者不占云端配额.
    val pikPak = supported.firstOrNull { it.engineKey == MediaCacheEngineKey.PikPak }
    if (pikPak != null && pikPak.canServe(media.download.uri)) {
        return MediaCacheEngineKey.PikPak
    }
    return MediaCacheEngineKey.Anitorrent.takeIf { key -> supported.any { it.engineKey == key } }
}

private suspend fun MediaCacheEngine.canServe(uri: String): Boolean =
    this !is TorrentMediaCacheEngine || torrentEngine.canServe(uri)
