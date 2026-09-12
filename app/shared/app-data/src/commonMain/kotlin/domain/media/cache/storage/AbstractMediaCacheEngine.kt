/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import androidx.datastore.core.DataStore
import kotlinx.collections.immutable.minus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.plus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.media.cache.LocalFileMediaCache
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.domain.media.cache.engine.sum
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.coroutines.childScope
import me.him188.ani.utils.coroutines.update
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.thisLogger
import kotlin.coroutines.CoroutineContext

abstract class AbstractDataStoreMediaCacheStorage(
    override val mediaSourceId: String,
    private val datastore: DataStore<List<MediaCacheSave>>,
    override val engine: MediaCacheEngine,
    private val displayName: String,
    parentCoroutineContext: CoroutineContext,
) : MediaCacheStorage {
    protected val logger = thisLogger()
    protected val scope: CoroutineScope = parentCoroutineContext.childScope()

    protected val metadataFlow = datastore.data
        .map { list ->
            list.filter { it.engine == engine.engineKey }
                // cache() 只对内存列表去重. 启动恢复失败过的那几次, 列表是空的, 同一集每播一次就多写一行,
                // 这些行已经落在 datastore 里. 读的时候按同一键收敛, 恢复与删除都只见一条.
                .distinctBy { Triple(it.origin.mediaId, it.metadata.subjectId, it.metadata.episodeId) }
                .sortedBy { it.origin.mediaId } // consistent stable order
        }

    /**
     * 已经恢复的 [LocalFileMediaCache], 不会重复恢复.
     *
     * 按 media 加剧集记键, 不能只用 mediaId: 整季包的每一集是各自的记录却共用一个 mediaId,
     * 只用 mediaId 会让包里第一条恢复成 [LocalFileMediaCache] 之后, 其余各集被当成重复而跳过, 全部消失.
     */
    protected val restoredLocalFileMediaCacheIds = MutableStateFlow(persistentListOf<String>())

    protected fun recordKey(mediaId: String, metadata: MediaCacheMetadata): String =
        "$mediaId/${metadata.subjectId}/${metadata.episodeId}"

    private fun recordKey(cache: MediaCache): String = recordKey(cache.origin.mediaId, cache.metadata)

    open suspend fun refreshCache(): List<MediaCache> {
        val allRecovered = MutableStateFlow(persistentListOf<MediaCache>())
        val metadataFlowSnapshot = metadataFlow.first()
        logger.info { "Restoring media cache, cache count in datastore: ${metadataFlowSnapshot.size}" }
        val semaphore = Semaphore(8)

        supervisorScope {
            metadataFlowSnapshot.forEach { (origin, metadata, _) ->
                if (recordKey(origin.mediaId, metadata) in restoredLocalFileMediaCacheIds.value) return@forEach

                semaphore.acquire()
                @OptIn(DelicateCoroutinesApi::class)
                launch(start = CoroutineStart.ATOMIC) {
                    try {
                        restoreFile(origin, metadata) {
                            if (it is LocalFileMediaCache) {
                                restoredLocalFileMediaCacheIds.update { plus(recordKey(it)) }
                            }
                            allRecovered.update { plus(it) }
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }

        // 新 restore 的加上 list 中已经有的 LocalFileMediaCache
        listFlow.update {
            allRecovered.value +
                    listFlow.value.filter { recordKey(it) in restoredLocalFileMediaCacheIds.value }
        }
        return allRecovered.value
    }

    open suspend fun restoreFile(
        origin: Media,
        metadata: MediaCacheMetadata,
        reportRecovered: suspend (MediaCache) -> Unit,
    ): MediaCache? {
        val cache = engine.restore(origin, metadata, scope.coroutineContext) ?: return null
        logger.info { "Cache restored: ${origin.mediaId}, result=${cache}" }

        reportRecovered(cache)
        cache.resume()

        logger.info { "Cache resumed: $cache" }
        return cache
    }

    override val listFlow: MutableStateFlow<List<MediaCache>> = MutableStateFlow(emptyList())

    override val cacheMediaSource: MediaSource by lazy {
        MediaCacheStorageSource(this, displayName, MediaSourceLocation.Local)
    }
    override val stats: Flow<MediaStats> = listFlow.flatMapLatest { caches ->
        if (caches.isEmpty()) {
            return@flatMapLatest flowOf(MediaStats.Zero)
        }

        combine(
            caches.distinctBy { it.origin.download.uri }.map { cache ->
                cache.sessionStats.map { stats ->
                    MediaStats(
                        uploaded = stats.uploadedBytes,
                        downloaded = stats.downloadedBytes,
                        uploadSpeed = stats.uploadSpeed,
                        downloadSpeed = stats.downloadSpeed,
                    )
                }
            },
        ) { cacheStats ->
            cacheStats.sum()
        }
    }

    override suspend fun cache(
        media: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        resume: Boolean
    ): MediaCache {
        logger.info { "$mediaSourceId creating cache, metadata=$metadata" }
        listFlow.value.firstOrNull {
            isSameMediaAndEpisode(it, media, metadata)
        }?.let { return it }

        if (!engine.supports(media)) {
            throw UnsupportedOperationException("Engine does not support media: $media")
        }
        val cache = engine.createCache(
            media, metadata,
            episodeMetadata,
            scope.coroutineContext,
        )

        withContext(Dispatchers.IO_) {
            datastore.updateData { list ->
                list + MediaCacheSave(cache.origin, cache.metadata, engine.engineKey)
            }
        }

        listFlow.update { plus(cache) }

        if (resume) {
            cache.resume()
        }

        return cache
    }

    /**
     * 只把新的 metadata 写进 datastore, 不重开文件, 也不动 [listFlow] 里的对象.
     *
     * 记录自己的 metadata 由它自己改, 这里只负责落盘. 下完了、用户按了恢复这类变化不换文件,
     * 重开会白白关掉正在用的句柄.
     */
    protected suspend fun persistMetadata(cache: MediaCache, metadata: MediaCacheMetadata) {
        withContext(Dispatchers.IO_) {
            datastore.updateData { list ->
                list.map { save ->
                    if (isSameMediaAndEpisode(cache, save)) save.copy(metadata = metadata) else save
                }
            }
        }
    }

    override suspend fun delete(cache: MediaCache): Boolean {
        return deleteFirst { isSameMediaAndEpisode(it, cache.origin, cache.metadata) }
    }

    override suspend fun deleteFirst(predicate: (MediaCache) -> Boolean): Boolean {
        val cache = listFlow.value.firstOrNull(predicate) ?: return false
        listFlow.update { minus(cache) }
        restoredLocalFileMediaCacheIds.update { minus(recordKey(cache)) }
        withContext(Dispatchers.IO_) {
            datastore.updateData { list ->
                list.filterNot { isSameMediaAndEpisode(cache, it) }
            }
        }
        cache.closeAndDeleteFiles()
        return true
    }

    override fun close() {
        scope.cancel()
    }

    protected fun isSameMediaAndEpisode(
        cache: MediaCache,
        media: Media,
        metadata: MediaCacheMetadata = cache.metadata
    ) = cache.origin.mediaId == media.mediaId &&
            metadata.subjectId == cache.metadata.subjectId &&
            metadata.episodeId == cache.metadata.episodeId

    protected fun isSameMediaAndEpisode(cache: MediaCache, save: MediaCacheSave): Boolean =
        isSameMediaAndEpisode(cache, save.origin, save.metadata)
}
