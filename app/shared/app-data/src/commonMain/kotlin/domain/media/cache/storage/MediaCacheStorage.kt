/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.cache.engine.DummyMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.domain.media.fetch.MediaFetcher
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.TorrentMediaResolver
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaCacheProperties
import me.him188.ani.datasources.api.paging.SinglePagePagedSource
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.source.matches
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.contains
import me.him188.ani.datasources.api.topic.flowOfFileSizeZero
import me.him188.ani.datasources.api.topic.isSingleEpisode

/**
 * 表示一个媒体缓存的存储空间, 例如一个本地目录.
 *
 * ## Identity
 *
 * [MediaCacheStorage] and [MediaSource] use the same ID system and,
 * there can be a [MediaSource] with the same ID as this [MediaCacheStorage].
 *
 * By having a [MediaSource] with the same ID,
 * a [MediaCacheStorage] can participate in the [MediaFetcher.newSession] process (and usually it should).
 */
interface MediaCacheStorage : AutoCloseable {
    /**
     * ID of this media source.
     */
    val mediaSourceId: String

    /**
     * 此空间的 [MediaSource]. 调用 [MediaSource.fetch] 则可从此空间中查询缓存, 作为 [Media].
     */
    val cacheMediaSource: MediaSource

    val engine: MediaCacheEngine

    /**
     * 此存储的总体统计
     */
    val stats: Flow<MediaStats>

    /**
     * A flow that subscribes on all the caches in the storage.
     *
     * Note that to retrieve [Media] (more specifically, [CachedMedia]) from the cache storage, you might want to use [cacheMediaSource].
     */
    val listFlow: Flow<List<MediaCache>>

    /**
     * 重新加载那些上次 APP 运行时保存在本地的缓存.
     *
     * 通常在 APP 启动时调用.
     */
    suspend fun restorePersistedCaches()

    /**
     * Finds the existing cache for the media or adds the media to the cache (queue).
     *
     * When this function returns, A new [MediaSource] can then be listed by [listFlow].
     *
     * Caching is made asynchronously. This function might only adds a job to the queue and does not guarantee when the cache will be done.
     *
     * This function returns only if the cache configuration is persisted.
     *
     * @param metadata The request to fetch the media.
     */
    suspend fun cache(
        media: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        resume: Boolean = true,
    ): MediaCache

    /**
     * 改写一条已有记录的 metadata, 返回按新 metadata 重新打开的记录. 没有这条记录时返回 `null`.
     */
    suspend fun updateMetadata(cache: MediaCache, metadata: MediaCacheMetadata): MediaCache?

    /**
     * Delete the cache if it exists.
     * @return `true` if a cache was deleted, `false` if there wasn't such a cache.
     */
    suspend fun delete(cache: MediaCache): Boolean =
        deleteFirst { it == cache }

    /**
     * Delete the cache if it exists.
     * @return `true` if a cache was deleted, `false` if there wasn't such a cache.
     */
    suspend fun deleteFirst(predicate: (MediaCache) -> Boolean): Boolean
}

/**
 * 持久化的媒体缓存数据, 用于在 APP 重启后恢复缓存.
 */
@Serializable
data class MediaCacheSave(
    val origin: Media,
    val metadata: MediaCacheMetadata,
    /**
     * 创建此缓存的的引擎 key.
     */
    val engine: MediaCacheEngineKey,
) {
}

/**
 * 所有缓存项目的大小总和
 */
val MediaCacheStorage.totalSize: Flow<FileSize>
    get() = listFlow.flatMapLatest { caches ->
        if (caches.isEmpty()) {
            return@flatMapLatest flowOfFileSizeZero
        }
        combine(caches.map { cache -> cache.fileStats.map { it.totalSize } }) { sizes ->
            sizes.sumOf { it.inBytes }.bytes
        }
    }

/**
 * Number of caches in this storage.
 */
val MediaCacheStorage.count: Flow<Int>
    get() = listFlow.map { it.size }

suspend inline fun MediaCacheStorage.contains(cache: MediaCache): Boolean =
    listFlow.first().any { it === cache }

/**
 * Provide base directory of [MediaCacheEngine].
 *
 * All [MediaCacheEngine]s should create their caches in this directory.
 *
 * 读取和写入缓存的目录的操作使用此目录, 不要读取 SettingsRepository 保存的目录.
 * 只有修改缓存目录和迁移可以从 SettingsRepository 中读取.
 */
interface MediaSaveDirProvider {
    val saveDir: String
}

/**
 * 将 [MediaCacheStorage] 作为 [MediaSource], 这样可以被 [MediaFetcher] 搜索到以播放.
 */
class MediaCacheStorageSource(
    private val storage: MediaCacheStorage,
    private val displayName: String,
    override val location: MediaSourceLocation = MediaSourceLocation.Local,
    /**
     * 给出一个种子里的所有文件路径, 用来判断整季包里有没有某一集的文件. 见 [siblingEpisodesInPack].
     *
     * 为 `null` 时不产生派生命中. anitorrent 走这条路: 派生命中会让 P2P 下载一个用户没要的文件.
     */
    private val packFilesProvider: (suspend (MediaCache) -> List<String>)? = null,
) : MediaSource {
    override val mediaSourceId: String get() = storage.mediaSourceId
    override val kind: MediaSourceKind get() = MediaSourceKind.LocalCache

    override suspend fun checkConnection(): ConnectionStatus = ConnectionStatus.SUCCESS

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        return SinglePagePagedSource {
            val caches = storage.listFlow.first()
            val matched = caches.mapNotNull { cache ->
                val kind = query.matches(cache.metadata)
                if (kind == null) null
                else MediaMatch(cache.getCachedMedia(), kind)
            }
            // 本集已有缓存记录时不再派生, 否则同一个 mediaId 会出现两次.
            val provider = packFilesProvider
            val result = if (provider != null && matched.isEmpty()) {
                siblingEpisodesInPack(caches, query, provider)
            } else {
                matched
            }
            result.asFlow()
        }
    }

    /**
     * 整季包已经为其中一集建立了缓存, 本地就有这个种子的会话与文件清单; 包内其他剧集同样能从这份
     * 会话打开, 不必再走网络数据源. 派生出的 [CachedMedia] 不是缓存记录, 也不启动下载:
     * 真正的记录由 `CacheOnBtPlayExtension` 在该集被播放时创建.
     *
     * 下载地址取原 media 的磁链而非已完成文件的路径, 播放因此走 `TorrentMediaResolver`.
     * 命中的文件在这里就选定并写进 [MediaCacheProperties.pathInTorrent], 播放时不再选一次.
     *
     * 判断依据是种子里真的有这一集的文件, 而不是 [Media.episodeRange] 覆盖了这个集数:
     * 「01-12+SP」这种包解析出的范围是 01..12, 而 SP01 的 number 也是 1, 只看范围会让每个 SP
     * 都命中, 再由播放时的兜底选中正片第一集.
     */
    private suspend fun siblingEpisodesInPack(
        caches: List<MediaCache>,
        query: MediaFetchRequest,
        packFiles: suspend (MediaCache) -> List<String>,
    ): List<MediaMatch> {
        // SP 的判断依据换成「包里有特别篇文件」而不是集数范围: 「01-24+SPx6」解析出的范围是 01..24,
        // 按范围 SP 永远不命中, 用户选过的包就白选了. 文件可以选不出来: 碟片的 S00E01 与 Bangumi 的
        // SP01 是两套编号, 匹配只认文件名里写明的标签, 选不出就把 pathInTorrent 留空, 播放时弹
        // 「资源中未找到本集」让用户挑, 挑的结果落进记录, 下次直接用.
        val isSpecial = query.episodeSort is EpisodeSort.Special

        return caches.asSequence()
            .filter { it.metadata.subjectId == query.subjectId }
            .filter { cache ->
                val range = cache.origin.episodeRange ?: return@filter false
                !range.isSingleEpisode() && (isSpecial || range.contains(query.episodeSort))
            }
            .distinctBy { it.origin.mediaId }
            .toList()
            .mapNotNull { cache ->
                val files = packFiles(cache)
                if (isSpecial && !TorrentMediaResolver.hasVideoFileOfKind(files, { this }, query.episodeSort)) {
                    return@mapNotNull null
                }

                val path = TorrentMediaResolver.selectVideoFileEntryExact(
                    files,
                    { this },
                    listOf(query.episodeName),
                    episodeSort = query.episodeSort,
                    episodeEp = query.episodeEp,
                )
                if (path == null && !isSpecial) return@mapNotNull null

                MediaMatch(
                    CachedMedia(
                        cache.origin,
                        storage.mediaSourceId,
                        download = cache.origin.download,
                        cacheProperties = MediaCacheProperties(pathInTorrent = path),
                    ),
                    MatchKind.FUZZY,
                )
            }
    }

    override val info: MediaSourceInfo = MediaSourceInfo(
        displayName,
        "本地缓存",
        isSpecial = true,
    )
}


class TestMediaCacheStorage : MediaCacheStorage {
    override val mediaSourceId: String
        get() = MediaCacheManager.LOCAL_FS_MEDIA_SOURCE_ID
    override val cacheMediaSource: MediaSource
        get() = throw UnsupportedOperationException()
    override val engine: MediaCacheEngine = DummyMediaCacheEngine(mediaSourceId)
    override val listFlow: MutableStateFlow<List<MediaCache>> =
        MutableStateFlow(listOf())

    override suspend fun restorePersistedCaches() {
    }

    override val stats: Flow<MediaStats> = flowOf(MediaStats.Unspecified)

    override suspend fun cache(
        media: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        resume: Boolean
    ): MediaCache {
        throw UnsupportedOperationException()
    }

    override suspend fun updateMetadata(cache: MediaCache, metadata: MediaCacheMetadata): MediaCache {
        throw UnsupportedOperationException()
    }

    override suspend fun delete(cache: MediaCache): Boolean {
        if (listFlow.first().any { it == cache }) {
            listFlow.value = listFlow.first().filter { it != cache }
            return true
        }
        return false
    }

    override suspend fun deleteFirst(predicate: (MediaCache) -> Boolean): Boolean {
        val list = listFlow.first()
        val cache = list.firstOrNull(predicate) ?: return false
        listFlow.value = list.filter { it != cache }
        return true
    }

    override fun close() {
    }
}

