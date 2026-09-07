/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.time.Clock

/**
 * 一个 `MediaCache` 的元数据, 包含来源的条目和剧集信息.
 *
 * [MediaCacheMetadata] 通常在创建缓存时, 根据 [MediaFetchRequest] 中的条目和剧集信息创建.
 *
 * [MediaCacheMetadata] 可被持久化, 用于下次启动时恢复缓存任务. 恢复过程详见 `MediaCacheStorage`.
 *
 * 在播放时查询数据源时, [MediaCacheMetadata] 也被用于与 [MediaFetchRequest] 匹配缓存. 查询过程详见 `MediaCacheEngine`.
 */
@Serializable
data class MediaCacheMetadata(
    /**
     * @see MediaFetchRequest.subjectId
     */
    val subjectId: String,
    /**
     * @see MediaFetchRequest.episodeId
     */
    val episodeId: String,
    /**
     * 在创建缓存时的条目名称, 仅应当在无法获取最新的名称时, 才使用这个
     */
    val subjectNameCN: String? = null,
    /**
     * @see MediaFetchRequest.subjectNames
     */
    val subjectNames: List<String>,
    /**
     * @see MediaFetchRequest.episodeSort
     */
    val episodeSort: EpisodeSort,
    /**
     * @see MediaFetchRequest.episodeEp
     */
    val episodeEp: EpisodeSort? = episodeSort,
    /**
     * @see MediaFetchRequest.episodeName
     */
    val episodeName: String,

    val creationTime: Long = Clock.System.now().toEpochMilliseconds(),

    /**
     * @see [CacheOnBtPlayExtension]
     */
    val autoCached: Boolean = false,

    /**
     * 这条记录要播放的种子内文件, 即 `TorrentFileEntry.pathInTorrent`.
     *
     * 自动匹配不到本集时由用户指定. 记在这里而不是 `torrent_cache` 表里: 那张表按 mediaId 建主键,
     * 整季包的每一集共用同一行, 存不下每集各自的选择.
     *
     * `null` 表示没有指定, 由自动匹配决定. 旧记录反序列化后就是 `null`.
     */
    val pathInTorrent: String? = null,

    /**
     * 这条记录自己的文件是否已经下完并达到分享率.
     *
     * 与 `torrent_cache.completed` 不同: 那一行按 mediaId 建主键, 整季包各集共用, 表示的是「这个种子
     * 里有文件下完了」. 拿它判断某一集会让包里第一个下完的文件冒充所有剧集.
     */
    val completed: Boolean = false,

    @Transient @Suppress("unused") private val _primaryConstructorMarker: Byte = 0, // avoid compiler error
) {
    constructor(
        request: MediaFetchRequest,
        autoCached: Boolean = false
    ) : this(
        subjectId = request.subjectId,
        episodeId = request.episodeId,
        subjectNameCN = request.subjectNameCN,
        subjectNames = request.subjectNames,
        episodeSort = request.episodeSort,
        episodeEp = request.episodeEp,
        episodeName = request.episodeName,
        creationTime = Clock.System.now().toEpochMilliseconds(),
        autoCached = autoCached,
    )
}