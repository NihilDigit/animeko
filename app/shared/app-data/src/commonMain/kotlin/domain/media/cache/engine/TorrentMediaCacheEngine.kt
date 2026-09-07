/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.domain.media.cache.LocalFileMediaCache
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.TorrentFileOverrideStore
import me.him188.ani.app.domain.media.resolver.TorrentMediaResolver
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.domain.torrent.engines.PikPakEngine
import me.him188.ani.app.tools.toProgress
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.EncodedTorrentInfo
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.app.torrent.api.files.isFinished
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaCacheProperties
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.actualSize
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.isDirectory
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes


//private const val EXTRA_TORRENT_CACHE_FILE =
//    "torrentCacheFile" // MediaCache 所对应的视频文件. 该文件一定是 [EXTRA_TORRENT_CACHE_DIR] 目录中的文件 (的其中一个)

/**
 * 以 [TorrentEngine] 实现的 [MediaCacheEngine], 意味着通过 BT 缓存 media.
 * 为每个 [MediaCache] 创建一个 [TorrentSession].
 */
class TorrentMediaCacheEngine(
    /**
     * 创建的 [CachedMedia] 将会使用此 [mediaSourceId]
     */
    private val mediaSourceId: String,
    override val engineKey: MediaCacheEngineKey,
    val torrentEngine: TorrentEngine,
    private val engineAccess: TorrentEngineAccess,
    private val dao: TorrentCacheInfoDao,
    val flowDispatcher: CoroutineContext = Dispatchers.Default,
    private val baseSaveDirProvider: MediaSaveDirProvider,
    /**
     * 自动建立的记录 (播放时顺带建的) 要不要把整个文件下下来. 用户显式建立的记录不看这个值,
     * 一律下满. anitorrent 恒为 true; PikPak 取 Reseeding 开关, 关掉时自动记录只跟随播放.
     *
     * 是 flow 不是常量: 用户在设置里翻开关时, 尚未下完的自动记录要当场改变行为, 不必重启.
     */
    private val fullDownloadForAutoCaches: Flow<Boolean> = flowOf(true),
    private val onDownloadStarted: suspend (session: TorrentSession) -> Unit = {},
    private val fileOverrideStore: TorrentFileOverrideStore = TorrentFileOverrideStore.Default,
) : MediaCacheEngine, AutoCloseable {
    companion object {
        private val logger = logger<TorrentMediaCacheEngine>()
        private val unspecifiedFileStatsFlow = flowOf(MediaCache.FileStats.Unspecified)
        private val unspecifiedSessionStatsFlow = flowOf(MediaCache.SessionStats.Unspecified)
        private val unspecifiedFileSizeFlow = flowOf(FileSize.Unspecified)

        const val LEGACY_MEDIA_CACHE_DIR = "torrent-caches"
    }

    val isServiceConnected = engineAccess.isServiceConnected

    /**
     * 同一个 media 现存的其他记录. 由 storage 装上, 删除时用来判断文件和数据库行还有没有人用.
     *
     * 整季包各集的记录共用一个种子目录和一行 torrent_cache, 删掉其中一集不能连带删掉它们.
     * 返回的是删除动作发生之后还剩下的记录.
     */
    var remainingRecordsOfMedia: (suspend (mediaId: String) -> List<MediaCacheMetadata>)? = null

    class FileHandle(val state: Flow<State?>) {
        val handle = state.map { it?.handle } // single emit
        val entry = state.map { it?.entry } // single emit
        val session = state.map { it?.session }

        suspend fun close() {
            handle.first()?.close()
        }

        class State(
            val session: TorrentSession,
            val entry: TorrentFileEntry?,
            val handle: TorrentFileHandle?,
        )
    }

    inner class TorrentMediaCache(
        override val origin: Media,
        initialMetadata: MediaCacheMetadata, // 注意, 我们不能写 check 检查这些属性, 因为可能会有旧版本的数据
        val fileHandle: FileHandle
    ) : MediaCache, SynchronizedObject() {
        /**
         * metadata 在记录的生命周期内会变: 下完了、用户在缓存页上按了恢复. 用 flow 存是为了
         * [state] 和 [applyDownloadPolicy] 能当场跟着变, 不必等下次重启.
         */
        private val metadataFlow = MutableStateFlow(initialMetadata)
        override val metadata: MediaCacheMetadata get() = metadataFlow.value

        /**
         * 把 metadata 的变化写进 datastore. 由 storage 在接管这条记录时装上, 见 `TorrentMediaCacheStorage`.
         */
        var onMetadataUpdated: (suspend (MediaCacheMetadata) -> Unit)? = null

        private val metadataUpdateLock = Mutex()

        private suspend fun updateMetadata(transform: (MediaCacheMetadata) -> MediaCacheMetadata) {
            metadataUpdateLock.withLock {
                val current = metadataFlow.value
                val updated = transform(current)
                if (updated != current) {
                    metadataFlow.value = updated
                    onMetadataUpdated?.invoke(updated)
                }
            }
        }

        private val desiredState = MutableStateFlow(
            MediaCacheState.IN_PROGRESS,
        )

        override suspend fun getCachedMedia(): CachedMedia {
            // 获取 cached media 不需要让 torrent engine 一直可用
            @OptIn(EnsureTorrentEngineIsAccessible::class)
            engineAccess.withServiceRequest("TorrentMediaCache#$this-getCachedMedia:${origin.mediaId}") {
                val file = fileHandle.handle.first()
                if (file != null && file.entry.isFinished()) {
                    val filePath = file.entry.resolveFile()
                    if (!filePath.exists()) {
                        error("TorrentFileHandle has finished but file does not exist: $filePath")
                    }
                    logger.info { "getCachedMedia: Torrent has already finished, returning file $filePath" }
                    return CachedMedia(
                        origin,
                        mediaSourceId,
                        download = ResourceLocation.LocalFile(filePath.toString()),
                    )
                } else {
                    logger.info { "getCachedMedia: Torrent has not yet finished, returning torrent" }
                    return CachedMedia(
                        origin,
                        mediaSourceId,
                        download = origin.download,
                        // 这条记录已经确定了种子里的哪个文件, 带上它, 播放时不必再匹配一次.
                        // 用户手动挑过文件的整季包尤其需要: 再匹配一次还是匹配不上.
                        cacheProperties = MediaCacheProperties(
                            pathInTorrent = metadata.pathInTorrent
                                ?: fileHandle.entry.first()?.pathInTorrent,
                        ),
                    )
                }
            }
        }

        override val fileStats: Flow<MediaCache.FileStats> = fileHandle.entry.flatMapLatest { entry ->
            if (entry == null) return@flatMapLatest unspecifiedFileStatsFlow

            entry.fileStats.map { stats ->
                MediaCache.FileStats(
                    totalSize = entry.length.bytes,
                    downloadedBytes = stats.downloadedBytes.bytes,
                    downloadProgress = stats.downloadProgress.toProgress(),
                )
            }
        }.flowOn(flowDispatcher)

        override val sessionStats: Flow<MediaCache.SessionStats> = fileHandle.session.flatMapLatest { handle ->
            if (handle == null) return@flatMapLatest unspecifiedSessionStatsFlow
            handle.sessionStats
                .map { stats ->
                    if (stats == null) return@map MediaCache.SessionStats.Unspecified
                    MediaCache.SessionStats(
                        totalSize = stats.totalSizeRequested.bytes,
                        downloadedBytes = stats.downloadedBytes.bytes,
                        downloadSpeed = stats.downloadSpeed.bytes,
                        uploadedBytes = stats.uploadedBytes.bytes,
                        uploadSpeed = stats.uploadSpeed.bytes,
                        downloadProgress = stats.downloadProgress.toProgress(),
                    )
                }
        }.flowOn(flowDispatcher)

        /**
         * 这条记录要不要下满. 显式建立的记录一律下满, 自动记录看引擎的开关.
         */
        private val downloadsFully: Flow<Boolean> =
            metadataFlow.flatMapLatest {
                if (it.autoCached) fullDownloadForAutoCaches else flowOf(true)
            }

        // 跟随播放的记录对外是「暂停」: 它确实没有在下载, 显示「缓存中」会让用户等一个不会涨的进度.
        override val state: Flow<MediaCacheState> =
            combine(desiredState, fileHandle.state, fileStats, downloadsFully) { currentState, handleState, stats, full ->
                when {
                    handleState == null -> MediaCacheState.FAILED
                    stats.isDownloadFinished -> MediaCacheState.COMPLETED
                    currentState == MediaCacheState.PAUSED || !full -> MediaCacheState.PAUSED
                    else -> MediaCacheState.IN_PROGRESS
                }
            }.flowOn(flowDispatcher)

        override suspend fun pause() {
            if (isDeleted.value) return
            desiredState.value = MediaCacheState.PAUSED
            fileHandle.handle.first()?.pause()
        }

        override suspend fun close() {
            if (isDeleted.value) return
            fileHandle.close()
        }

        /**
         * 下满的记录照常提优先级. 跟随播放的记录只是「存在」: 不给句柄提优先级, 播放器自己的
         * 句柄要哪段就取哪段, 缓存不额外拉整个文件.
         *
         * 跟随播放用的是「不 resume 句柄」而不是 `resume(FilePriority.IGNORE)`:
         * [me.him188.ani.torrent.pikpak.PikPakFileEntry] 的 resumeImpl 无视优先级直接启动
         * fetcher 并预热, 传 IGNORE 反而会开始取字节. 没有优先级请求的句柄, 实体算出的
         * requestingPriority 就是 IGNORE, 正是要的效果. 统计不受影响, 它读的是文件实体.
         */
        override suspend fun resume() {
            if (isDeleted.value) return
            desiredState.value = MediaCacheState.IN_PROGRESS
            if (!downloadsFully.first()) {
                logger.info { "Cache ${origin.mediaId} follows playback, not raising file priority." }
                return
            }
            val file = fileHandle.handle.first()
            logger.info { "Resuming file: $file" }
            file?.resume(FilePriority.NORMAL)
        }

        /**
         * 缓存页上按恢复表达的是「我要这一条」, 于是自动记录就此转成显式记录: 之后一律下满,
         * 不再随 Reseeding 开关摆动. [resume] 不能这么做, 它同时是建立和恢复记录时的生命周期调用,
         * 那样每条自动记录一建立就变成显式的了.
         */
        override suspend fun resumeByUser() {
            if (isDeleted.value) return
            updateMetadata { it.copy(autoCached = false) }
            resume()
        }

        /**
         * 跟踪开关的变化: 打开时未下完的自动记录升级为下满, 关闭时退回跟随播放.
         * 用户手动暂停的记录不动.
         *
         * 由 storage 在缓存的生命周期内持续调用.
         */
        suspend fun applyDownloadPolicy() {
            combine(desiredState, downloadsFully) { state, full -> state to full }
                .distinctUntilChanged()
                .collectLatest { (state, full) ->
                    if (isDeleted.value || state != MediaCacheState.IN_PROGRESS) return@collectLatest
                    val file = fileHandle.handle.first() ?: return@collectLatest
                    // 句柄可能已经关闭 (缓存 close 之后开关才翻), 这时无事可做.
                    try {
                        if (full) file.resume(FilePriority.NORMAL) else file.pause()
                    } catch (e: IllegalStateException) {
                        logger.debug { "Cache ${origin.mediaId} handle already closed, ignoring policy change: $e" }
                    }
                }
        }

        override val isDeleted: MutableStateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun closeAndDeleteFiles() {
            logger.info { "closeAndDeleteFiles is called" }
            if (isDeleted.value) return
            synchronized(this) {
                if (isDeleted.value) return
                isDeleted.value = true
            }

            // 这一步已经把自己从 storage 的列表里摘掉了, 剩下的就是同一个种子里还活着的记录.
            val remaining = remainingRecordsOfMedia?.invoke(origin.mediaId).orEmpty()
            // 整季包里两集可能指向同一个文件 (例如用户给两集都挑了同一个文件), 还有人用就不能删.
            val fileStillInUse = remaining.any { it.pathInTorrent != null && it.pathInTorrent == metadata.pathInTorrent }

            // 只需要在删除缓存的时候 torrent engine 可用, 不需要保证一直可用
            @OptIn(EnsureTorrentEngineIsAccessible::class)
            val handle =
                engineAccess.withServiceRequest("TorrentMediaCache#$this-closeAndDeleteFiles:${origin.mediaId}") {
                    logger.info { "Getting handle" }
                    val handle = fileHandle.handle.first() ?: kotlin.run {
                        // did not even selected a file
                        logger.info { "Deleting torrent cache: No file selected" }
                        close()
                        deleteRowIfLastRecord(remaining)
                        return
                    }

                    logger.info { "Closing TorrentCache" }
                    close()

                    if (fileStillInUse) {
                        logger.info { "Another record still plays ${metadata.pathInTorrent}, keeping the file" }
                        handle.close()
                        deleteRowIfLastRecord(remaining)
                        return
                    }

                    logger.info { "Closing torrent file handle" }
                    handle.closeAndDelete()

                    handle
                }

            withContext(Dispatchers.IO_) {
                val file = handle.entry.resolveFileMaybeEmptyOrNull() ?: kotlin.run {
                    logger.warn { "No file resolved for torrent entry '${handle.entry.fileName}'" }
                    return@withContext
                }
                if (file.exists()) {
                    logger.info { "Deleting torrent cache: $file" }
                    try {
                        file.delete()
                    } catch (_: FileNotFoundException) {
                    } catch (e: IOException) {
                        logger.warn("Failed to delete cache file $file", e)
                    }
                } else {
                    logger.info { "Torrent cache does not exist, ignoring: $file" }
                }
            }
            deleteRowIfLastRecord(remaining)
        }

        /**
         * 行里存的是种子文件和保存目录, 同一个种子的每条记录恢复时都要读它. 只有最后一条记录走了
         * 才能删; 删早了, 同一个包的其他剧集下次启动就恢复不出来.
         *
         * 保存目录不在这里删: [deleteUnusedCaches] 在启动时按现存记录清理目录, 行没了目录自然
         * 不再被认领, 下次启动一并回收.
         */
        private suspend fun deleteRowIfLastRecord(remaining: List<MediaCacheMetadata>) {
            if (remaining.isNotEmpty()) {
                logger.info {
                    "${remaining.size} record(s) of ${origin.mediaId} remain, keeping torrent_cache row"
                }
                return
            }
            dao.deleteByMediaId(origin.mediaId, engineKey.key)
        }

        /**
         * 订阅当前 TorrentMediaCache 的统计信息以更新它的 metadata.
         */
        suspend fun subscribeStats(shareRatioLimitFlow: Flow<Float>) {
            isServiceConnected.collectLatest { serviceStarted ->
                if (!serviceStarted) return@collectLatest

                coroutineScope {
                    val fileEntryFlow = fileHandle.entry.filterNotNull()
                        .shareIn(this, SharingStarted.Lazily, replay = 1)
                    val sessionStatsFlow = fileHandle.session.filterNotNull()
                        .flatMapLatest { it.sessionStats }.filterNotNull()
                        .shareIn(this, SharingStarted.Lazily, replay = 1)

                    val fileEntry = fileEntryFlow.first()
                    val entryFileStats = fileEntry.fileStats.filterNotNull().first()
                    val sessionStats = sessionStatsFlow.first()

                    val currentShareRatioLimit = shareRatioLimitFlow.first()
                    val currentShareRatio = sessionStats.uploadedBytes /
                            entryFileStats.downloadedBytes.coerceAtLeast(1).toFloat()

                    val entity = dao.get(origin.mediaId, engineKey.key)
                        ?: error(
                            "No entity with id ${origin.mediaId} exists for engine ${engineKey.key} " +
                                    "while subscribing cache.",
                        )

                    val finished = metadata.completed || // 记录已标记完成
                            (entryFileStats.isDownloadFinished && currentShareRatio >= currentShareRatioLimit) // 统计判断达到条件也是完成

                    // 完成与文件路径是这一集自己的事, 写在记录上. 整季包各集共用一行 torrent_cache,
                    // 写在行里会让包里第一个下完的文件冒充所有剧集.
                    // 文件路径只在下完时落盘: 下完的文件就是这条记录的内容, 之后按它开本地文件.
                    // 没下完时不写, 自动匹配的结果一旦写进记录, 匹配规则改了也追不回来, 旧规则把 SP02
                    // 匹配到正片 02 的结果就这样被冻结过.
                    updateMetadata {
                        it.copy(
                            completed = finished,
                            pathInTorrent = if (finished) fileEntry.pathInTorrent else it.pathInTorrent,
                        )
                    }
                    // 行里的 completed/pathInTorrent 仍然写, 但含义是「这个种子里有文件下完了, 是哪个」:
                    // 安卓的 TorrentServiceConnectionManager 靠它决定 BT 服务能不能停, PikPakReseeder
                    // 靠它挑要做种的种子, stats 靠它累计已完成的上传下载量.
                    dao.upsert(
                        entity.copy(
                            completed = entity.completed || finished,
                            pathInTorrent = fileEntry.pathInTorrent,
                            downloadSize = entryFileStats.downloadedBytes,
                            uploadSize = sessionStats.uploadedBytes,
                        ),
                    )

                    // 如果种子任务已经完成了就不启动了
                    if (finished) {
                        logger.debug { "Cache task ${origin.mediaId} is already finished, ignore stats subscription." }
                        return@coroutineScope
                    }

                    // 最后一次有上传活动的时间
                    var lastUploadActivity = currentTimeMillis()
                    // 当更新完 metadata 后需要停止 stats collector
                    // 因为 TorrentMediaCache 没有订阅 metadata 的能力, 使用一个 flow 来辅助停止
                    val finishedFlow = MutableStateFlow(false) // always false initially

                    finishedFlow.collectLatest finished@{ f ->
                        if (f) {
                            logger.debug { "Cache task ${origin.mediaId} is finished, stop stats subscription." }
                            return@finished
                        }

                        logger.debug { "Subscribed stats of cache task ${origin.mediaId}." }

                        combine(
                            sessionStatsFlow,
                            fileEntryFlow.flatMapLatest { it.fileStats.filterNotNull() },
                            shareRatioLimitFlow,
                        ) task@{ sessionStats, fileStats, shareRatioLimit ->
                            if (!fileStats.isDownloadFinished) return@task

                            val shareRatio = sessionStats.uploadedBytes /
                                    fileStats.downloadedBytes.coerceAtLeast(1).toFloat()

                            // 没达到分享率才进入这里的逻辑, 达到分享率直接更新 metadata
                            if (shareRatio < shareRatioLimit) {
                                val currentTimeMillis = currentTimeMillis()

                                // 如果距离上次上传活动小于 10 分钟, 不能更新 metadata, 因为 10 分钟内还可能有上传
                                if (currentTimeMillis - lastUploadActivity < 10.minutes.inWholeMilliseconds) {
                                    // 如果有上传活动, 更新最后的活动时间
                                    if (sessionStats.uploadSpeed > 0L) {
                                        lastUploadActivity = currentTimeMillis
                                    }
                                    return@task
                                }
                                // 如果距离上次上传活动大于 10 分钟, 直接更新 metadata
                            }

                            // pathInTorrent 与上面那条分支写的是同一件事, 不能只写一半:
                            // resolveCompletedFromDataStore 要 completed 和 pathInTorrent 同时
                            // 具备才认这条记录, 缺一个的话从零下完的缓存下次启动仍然走引擎恢复,
                            // 而不是直接开本地文件.
                            // fileHandle.entry 在这条记录的生命周期内只发一次, 所以 fileEntry
                            // 就是当前的那个.
                            updateMetadata {
                                it.copy(completed = true, pathInTorrent = fileEntry.pathInTorrent)
                            }
                            dao.upsert(
                                entity.copy(
                                    completed = true,
                                    pathInTorrent = fileEntry.pathInTorrent,
                                    downloadSize = fileStats.downloadedBytes,
                                    uploadSize = sessionStats.uploadedBytes,
                                ),
                            )

                            finishedFlow.value = true // side effect.
                        }.run {
                            try {
                                collect()
                            } catch (ex: CancellationException) {
                                logger.debug { "Stat subscription of cache task ${origin.mediaId} is cancelled." }
                                throw ex // re-throw it. 
                            }
                        }
                    }
                }
            }
        }

        override fun toString(): String {
            return "TorrentMediaCache(subjectName='${metadata.subjectNames.firstOrNull()}', " +
                    "episodeSort=${metadata.episodeSort}, " +
                    "episodeName='${metadata.episodeName}', " +
                    "origin.mediaSourceId='${origin.mediaSourceId}')"
        }
    }

    override val stats: Flow<MediaStats> = engineAccess.isServiceConnected
        .flatMapLatest { useEngine ->
            val finishedMediaStats = dao.getAll().map { saveList ->
                var totalFinishedDownloaded = 0L.bytes
                var totalFinishedUploaded = 0L.bytes

                // getAll() 是全表, 两个引擎的行都在里面; 不过滤的话每个引擎的统计都会算上另一个的字节.
                saveList.filter { it.completed && it.engine == engineKey.key }.forEach { save ->
                    val downloaded = save.downloadSize.bytes
                    val uploaded = save.uploadSize.bytes

                    if (downloaded != FileSize.Unspecified) totalFinishedDownloaded += downloaded
                    if (uploaded != FileSize.Unspecified) totalFinishedUploaded += uploaded
                }

                MediaStats(
                    uploaded = totalFinishedUploaded,
                    downloaded = totalFinishedDownloaded,
                    uploadSpeed = 0L.bytes,
                    downloadSpeed = 0L.bytes,
                )
            }

            if (!useEngine) {
                return@flatMapLatest finishedMediaStats
            }

            flow { emit(torrentEngine.getDownloader()) }
                .flatMapLatest {
                    combine(finishedMediaStats, it.totalStats) { finished, engineStats ->
                        MediaStats(
                            uploaded = engineStats.uploadedBytes.bytes + finished.uploaded,
                            downloaded = engineStats.downloadedBytes.bytes + finished.downloaded,
                            uploadSpeed = engineStats.uploadSpeed.bytes + finished.uploadSpeed,
                            downloadSpeed = engineStats.downloadSpeed.bytes + finished.downloadSpeed,
                        )
                    }
                }
        }
        .flowOn(flowDispatcher)

    override fun supports(media: Media): Boolean {
        // 引擎不可用时不能声称支持: 缓存目标的候选列表由此得出, 选中一个跑不起来的引擎
        // 会让缓存请求停在创建阶段. PikPak 在设置里关掉时正是这种情况.
        if (!torrentEngine.isSupported) return false
        return media.download is ResourceLocation.HttpTorrentFile
                || media.download is ResourceLocation.MagnetLink
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun restore(
        origin: Media,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext
    ): MediaCache? {
        val data = dao.get(origin.mediaId, engineKey.key)?.torrentData ?: kotlin.run {
            // 整季包各集共用这一行, 删掉其中一集时曾经把行也删了, 剩下的记录就在这里无声地消失.
            // 现在删除会保留最后一条记录之前的行, 真出现缺行时要能从日志里看出来.
            logger.warn { "No torrent_cache row for ${origin.mediaId}, cannot restore cache: $metadata" }
            return null
        }

        // 先判断有没有已经下完的本地文件, 再看引擎支不支持. 这条记录在盘上就是一个完整文件,
        // 打开它不需要引擎; 反过来先问 supports, PikPak 在设置里被关掉或凭据被清空时, 已经下完的
        // 缓存会在重启后从列表里消失.
        val localFile = origin.resolveCompletedFromDataStore(metadata)
        if (localFile != null) {
            return LocalFileMediaCache(origin, metadata, localFile) { file ->
                @OptIn(DelicateCoroutinesApi::class)
                GlobalScope.launch {
                    try {
                        if (!torrentEngine.isSupported) {
                            // 引擎关掉了, getDownloader 会抛; 这里又在 GlobalScope 里, 抛出去
                            // 就是一次没人接的崩溃. 已完成的记录本来也不需要引擎参与.
                            deleteCompletedLocalFile(origin.mediaId, metadata, file)
                            return@launch
                        }
                        // 如果想删除 LocalFileMediaCache 类型的缓存, 需要启动 torrent engine 删除.
                        // 启动后马上恢复这个缓存并删除, 这个操作需要保证 torrent engine 可用, 删除完成后释放 torrent engine 可用性.
                        @OptIn(EnsureTorrentEngineIsAccessible::class)
                        engineAccess
                            .withServiceRequest("LocalFileMediaCache#$this-closeAndDeleteFiles:${origin.mediaId}") {
                                TorrentMediaCache(
                                    origin = origin,
                                    initialMetadata = metadata,
                                    fileHandle = getFileHandle(
                                        EncodedTorrentInfo.createRaw(data),
                                        metadata,
                                        coroutineContext,
                                        origin.mediaId,
                                    ),
                                ).apply {
                                    resume()
                                    closeAndDeleteFiles()
                                }
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.error(e) { "Failed to delete local cache of ${origin.mediaId}" }
                    }
                }
            }
        }

        if (!supports(origin)) throw UnsupportedOperationException("Media is not supported by this engine $this: ${origin.download}")

        @OptIn(EnsureTorrentEngineIsAccessible::class)
        return engineAccess.withServiceRequest("TorrentMediaCacheEngine#$this-restore:${origin.mediaId}") {
            TorrentMediaCache(
                origin = origin,
                initialMetadata = metadata,
                fileHandle = getFileHandle(
                    EncodedTorrentInfo.createRaw(data), metadata, parentContext, origin.mediaId,
                ),
            )
        }
    }

    /**
     * 引擎不可用时删除一条已完成记录. 规则与 [TorrentMediaCache.closeAndDeleteFiles] 相同: 同一个
     * 种子里还有别的记录指向这个文件就留着文件, 还有别的记录用这一行就留着行.
     *
     * 不删保存目录: [deleteUnusedCaches] 在启动时按现存记录清理目录.
     */
    private suspend fun deleteCompletedLocalFile(
        mediaId: String,
        metadata: MediaCacheMetadata,
        file: SystemPath,
    ) {
        val remaining = remainingRecordsOfMedia?.invoke(mediaId).orEmpty()
        val fileStillInUse = remaining.any { it.pathInTorrent != null && it.pathInTorrent == metadata.pathInTorrent }
        if (fileStillInUse) {
            logger.info { "Another record still plays ${metadata.pathInTorrent}, keeping the file" }
        } else {
            withContext(Dispatchers.IO_) {
                if (!file.exists()) {
                    logger.info { "Torrent cache does not exist, ignoring: $file" }
                    return@withContext
                }
                logger.info { "Deleting torrent cache without the engine: $file" }
                try {
                    file.delete()
                } catch (_: FileNotFoundException) {
                } catch (e: IOException) {
                    logger.warn("Failed to delete cache file $file", e)
                }
            }
        }
        if (remaining.isNotEmpty()) {
            logger.info { "${remaining.size} record(s) of $mediaId remain, keeping torrent_cache row" }
            return
        }
        dao.deleteByMediaId(mediaId, engineKey.key)
    }

    /**
     * 这个种子里的所有文件路径, 在建立缓存或恢复缓存时记下. 空表示还没记过, 不表示种子是空的.
     *
     * 整季包为其中一集建立缓存后, 判断包里有没有另一集的文件只需要这份清单, 不必开启会话.
     */
    suspend fun getFilesInTorrent(mediaId: String): List<String> =
        dao.get(mediaId, engineKey.key)?.filesInTorrent.orEmpty()

    private suspend fun getFileHandle(
        encoded: EncodedTorrentInfo,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext,
        mediaId: String,
    ): FileHandle {
        val downloader = torrentEngine.getDownloader()
        val res = kotlinx.coroutines.withTimeoutOrNull(30_000) {
            val session = downloader.startDownload(encoded, parentContext)
            logger.info { "$mediaSourceId: waiting for files" }
            onDownloadStarted(session)

            val files = session.getFiles()
            // 每次拿到清单都写一遍: 这样在本次改动之前建立的记录也会在第一次恢复时补上.
            dao.updateFilesInTorrent(mediaId, engineKey.key, files.map { it.pathInTorrent })

            // 用户挑过文件就用他挑的, 不再匹配. 本次会话内的选择先落在内存里 (挑的那一刻这条记录
            // 可能还不存在), 已经落到记录上的写在 metadata.pathInTorrent.
            val selectedFile = (fileOverrideStore.get(mediaId, metadata.episodeSort) ?: metadata.pathInTorrent)
                ?.let { path -> files.firstOrNull { it.pathInTorrent == path } }
                ?: TorrentMediaResolver.selectVideoFileEntry(
                    files,
                    { pathInTorrent },
                    listOf(metadata.episodeName),
                    episodeSort = metadata.episodeSort,
                    episodeEp = metadata.episodeEp,
                )

            if (selectedFile == null) {
                logger.error {
                    """
                            $mediaSourceId: Selected null file to download. Diagnosis:
                            - Files: ${files.map { it.fileName }}
                            - Metadata: $metadata
                        """.trimIndent()
                }
            } else {
                logger.info { "$mediaSourceId: Selected file to download: $selectedFile" }
            }

            val handle = selectedFile?.createHandle()
            if (handle == null) {
                session.closeIfNotInUse()
            }
            FileHandle.State(session, selectedFile, handle)
        }

        if (res == null) {
            logger.error { "$mediaSourceId: Timed out while starting download or selecting file. Returning null handle. episode name: ${metadata.episodeName}" }
        }

        return FileHandle(flowOf(res))
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun createCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        parentContext: CoroutineContext
    ): TorrentMediaCache {
        if (!supports(origin)) throw UnsupportedOperationException("Media is not supported by this engine $this: ${origin.download}")
        // 用户挑过文件之后才会走到这里 (记录由 CacheOnBtPlayExtension 在播放成功后才建立), 把选择写进
        // metadata, 存下来的记录从一开始就是对的. 放在这里而不是 CacheOnBtPlayExtension 里, 是因为
        // 那个扩展不该知道种子内部有哪些文件.
        @Suppress("NAME_SHADOWING")
        val metadata = fileOverrideStore.get(origin.mediaId, metadata.episodeSort)
            ?.let { metadata.copy(pathInTorrent = it) }
            ?: metadata
        // 创建缓存需要保证 torrent engine 一直可用, 所以 getFileHandle 直接启动协程创建好缓存.
        @OptIn(EnsureTorrentEngineIsAccessible::class)
        engineAccess.withServiceRequest("TorrentMediaCacheEngine#$this-createCache:${origin.mediaId}") {
            val downloader = torrentEngine.getDownloader()
            val data = downloader.fetchTorrent(origin.download.uri)

            dao.upsert(
                TorrentCacheInfoEntity(
                    mediaId = origin.mediaId,
                    engine = engineKey.key,
                    torrentData = data.data,
                    relativeDir = downloader.getSaveDirForTorrent(data).absolutePath.let { path ->
                        val stripped = path.substringAfter(baseSaveDirProvider.saveDir)
                        if (path == stripped) {
                            throw UnsupportedOperationException(
                                "Failed to strip torrent save path of media ${origin.mediaId}, " +
                                        "path: $path, base: ${baseSaveDirProvider.saveDir}",
                            )
                        }
                        stripped
                    },
                ),
            )

            return TorrentMediaCache(
                origin = origin,
                initialMetadata = metadata,
                fileHandle = getFileHandle(data, metadata, parentContext, origin.mediaId),
            )
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun deleteUnusedCaches(all: List<MediaCache>) {
        // PikPak 播放不落盘, 没有记录的目录里只剩 meta.json (云端文件清单), 留着能省一次云端查询,
        // 也谈不上占空间. 这里的清理针对 anitorrent 留下的整个种子目录.
        if (torrentEngine is PikPakEngine) return
        // 引擎的数据目录不存在, 就没有可清理的残留.
        // 这条早退不是优化: storage 在每次启动恢复缓存之后都会调到这里, 而下面的 getDownloader
        // 在安卓上会启动 AniTorrentService 并弹出「BT 引擎」前台通知. 从没用过 anitorrent 的
        // 用户 (例如只开了 PikPak) 因此每次启动都会看到那条通知.
        if (!withContext(Dispatchers.IO_) { torrentEngine.saveDir.exists() }) {
            logger.debug { "$mediaSourceId: engine save dir does not exist, skipping cache pruning." }
            return
        }
        if (!torrentEngine.isSupported) {
            logger.debug { "$mediaSourceId: engine is not supported, skipping cache pruning." }
            return
        }

        // 只需要在删除缓存的时候 torrent engine 可用, 不需要保证一直可用
        @OptIn(EnsureTorrentEngineIsAccessible::class)
        engineAccess.withServiceRequest("TorrentMediaCacheEngine#$this-deleteUnusedCaches") {
            val downloader = torrentEngine.getDownloader()

            val allowedAbsolute = buildSet {
                dao.batchGet(all.map { it.origin.mediaId }, engineKey.key).forEach {
                    add(Path(baseSaveDirProvider.saveDir).resolve(it.relativeDir).inSystem.absolutePath)
                    add(downloader.getSaveDirForTorrent(EncodedTorrentInfo.createRaw(it.torrentData)).absolutePath)
                }
            }

            withContext(Dispatchers.IO_) {
                val saves = downloader.listSaves()
                for (save in saves) {
                    if (save.absolutePath !in allowedAbsolute) {
                        logger.warn { "本地种子缓存文件未找到匹配的 MediaCache, 已释放 ${save.actualSize().bytes}: ${save.absolutePath}" }
                        save.deleteRecursively()
                    }
                }
            }
        }
    }

    override fun close() {
        torrentEngine.close()
    }

    /**
     * 完成与否、下的是哪个文件都取自记录自己的 metadata; 数据库行只提供种子的保存目录.
     * 行是按 mediaId 建的, 整季包各集共用, 用它判断某一集会让第一个下完的文件冒充所有剧集.
     */
    private suspend fun Media.resolveCompletedFromDataStore(metadata: MediaCacheMetadata): SystemPath? {
        if (!metadata.completed) return null
        val pathInTorrent = metadata.pathInTorrent?.takeIf { it.isNotEmpty() } ?: return null
        val entity = dao.get(mediaId, engineKey.key) ?: return null

        val file = Path(baseSaveDirProvider.saveDir, entity.relativeDir).resolve(pathInTorrent).inSystem
        if (!file.exists() || file.isDirectory()) {
            return null
        }

        return file
    }
}
