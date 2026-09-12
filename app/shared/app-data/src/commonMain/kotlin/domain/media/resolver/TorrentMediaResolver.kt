/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import me.him188.ani.app.domain.media.cache.engine.EnsureTorrentEngineIsAccessible
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.TorrentEngineAccess
import me.him188.ani.app.domain.media.cache.engine.UnsafeTorrentEngineAccessApi
import me.him188.ani.app.domain.media.cache.engine.withServiceRequest
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.media.player.data.TorrentMediaData
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.torrent.api.FetchTorrentTimeoutException
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.EncodedTorrentInfo
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.torrent.pikpak.CloudReadiness
import me.him188.ani.torrent.pikpak.PartialListing
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.contains
import me.him188.ani.datasources.api.topic.titles.RawTitleParser
import me.him188.ani.datasources.api.topic.titles.parse
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.cancellation.CancellationException

/**
 * @param fallback 本引擎自身出错时接手的解析器. 链条 ([MediaResolver.from]) 只按 [supports] 选第一个
 * 能处理的解析器, 选中之后抛出的异常不会再往下走, 所以云端引擎 (PikPak) 的密码错误、配额耗尽、离线任务
 * 失败会让这一集彻底播不了. 退化的入口有两处: 这里的 [resolve], 以及网络请求真正发生的
 * [TorrentMediaDataProvider.open].
 */
class TorrentMediaResolver(
    private val engine: TorrentEngine,
    private val engineAccess: TorrentEngineAccess,
    private val fallback: MediaResolver? = null,
) : MediaResolver {
    override fun supports(media: Media): Boolean {
        if (!engine.isSupported) return false
        return media.download is ResourceLocation.HttpTorrentFile || media.download is ResourceLocation.MagnetLink
    }

    @Throws(MediaResolutionException::class, CancellationException::class)
    override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
        @OptIn(EnsureTorrentEngineIsAccessible::class)
        engineAccess.withServiceRequest("TorrentMediaResolver#$this-resolve:${media.mediaId}") {
            val downloader = try {
                engine.getDownloader()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                resolveWithFallback(media, episode, e)?.let { return it }
                throw MediaResolutionException(ResolutionFailures.ENGINE_ERROR, e)
            }

            return when (val location = media.download) {
                is ResourceLocation.HttpTorrentFile,
                is ResourceLocation.MagnetLink
                    -> {
                    try {
                        TorrentMediaDataProvider(
                            engine,
                            engineAccess = engineAccess,
                            encodedTorrentInfo = downloader.fetchTorrent(location.uri),
                            episodeMetadata = episode,
                            extraFiles = media.extraFiles.toMediampMediaExtraFiles(),
                            // 不在这里就把回退解析出来: anitorrent 的 resolve 会去唤起 BT 服务,
                            // 而绝大多数播放根本用不到回退.
                            fallback = fallback?.takeIf { it.supports(media) }?.let {
                                suspend { it.resolve(media, episode) }
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        resolveWithFallback(media, episode, e)?.let { return it }
                        throw when (e) {
                            is FetchTorrentTimeoutException ->
                                MediaResolutionException(ResolutionFailures.FETCH_TIMEOUT)

                            is IOException ->
                                MediaResolutionException(ResolutionFailures.NETWORK_ERROR, e)

                            else -> MediaResolutionException(ResolutionFailures.ENGINE_ERROR, e)
                        }
                    }
                }

                else -> throw UnsupportedMediaException(media)
            }
        }
    }

    private suspend fun resolveWithFallback(
        media: Media,
        episode: EpisodeMetadata,
        cause: Throwable,
    ): MediaDataProvider<*>? {
        val fallback = fallback ?: return null
        if (!fallback.supports(media)) return null
        logger.warn(cause) { "${engine.type} failed to resolve $media, falling back to $fallback" }
        return fallback.resolve(media, episode)
    }

    companion object {
        private val logger = logger<TorrentMediaResolver>()

        private val DEFAULT_VIDEO_EXTENSIONS =
            setOf("mp4", "mkv", "avi", "mpeg", "mov", "flv", "wmv", "webm", "rm", "rmvb")

        /**
         * 黑名单词, 包含这些词的文件会被放到最后.
         *
         * 黑名单也有顺序, 在黑名单中的越靠前越容易被选择.
         */
        @Suppress("RegExpRedundantEscape")
        private val BLACKLIST_WORDS = // 性能还可以, regex 只是让 70ms 变成了 150ms (不过它可能在指数的复杂度)
            setOf(
                Regex("""\[SP[0-9]*\]"""),
                Regex("""\[OVA[0-9]*\]"""),
                // SP 和 OVA 要放到前面, 因为用户可能就是要看这个
                Regex("""PV[0-9]*"""),
                Regex("""NCOP[0-9]*"""),
                Regex("""NCED[0-9]*"""),
                Regex("""OP[0-9]+"""),
                Regex("""ED[0-9]+"""), // 必须匹配数字防止名字带有 OP ED 的情况
                Regex("""\[OP[0-9]*\]"""),
                Regex("""\[ED[0-9]*\]"""),
                Regex("""\[CM[0-9]*\]"""),
            )

        /**
         * @param episodeSort 在系列中的集数, 例如第二季的第一集为 26
         * @param episodeEp 在当前季度中的集数, 例如第二季的第一集为 01
         * @param listingComplete [entries] 是否列全了种子里的文件. 见 [PartialListing]
         */
        fun <T> selectVideoFileEntry(
            entries: List<T>,
            getPath: T.() -> String,
            episodeTitles: List<String>,
            episodeSort: EpisodeSort,
            episodeEp: EpisodeSort?,
            videoExtensions: Set<String> = DEFAULT_VIDEO_EXTENSIONS,
            listingComplete: Boolean = true,
        ): T? {
            // Filter by file extension
            val videos = entries
                .filterTo(ArrayList(entries.size)) {
                    videoExtensions.any { fileType -> it.getPath().endsWith(fileType, ignoreCase = true) }
                }

            videos.sortByDescending {
                BLACKLIST_WORDS.forEachIndexed { index, blacklistWord ->
                    if (it.getPath().contains(blacklistWord)) {
                        return@sortByDescending -index // 包含黑名单词的放到最后
                    }
                }
                1
            }

            // Find by name match
            for (episodeTitle in episodeTitles) {
                val entry = videos.singleOrNull {
                    it.getPath().contains(episodeTitle, ignoreCase = true)
                }
                if (entry != null) return entry
            }

            // 解析标题匹配集数
            val parsedTitles = buildMap { // similar to `associateWith`, but ignores nulls
                for (entry in videos) {
                    val title = RawTitleParser.getDefault()
                        .parse(
                            entry.getPath()
                                .substringBeforeLast(".")
                                .substringAfterLast("\\")
                                .substringAfterLast("/"),
                            null,
                        )
                        .episodeRange
                    if (title != null) { // difference between `associateWith`
                        put(entry, title)
                    }
                }
            }
            // 优先按系列集数 sort 匹配 (数字较大)
            if (parsedTitles.isNotEmpty()) {
                parsedTitles.entries.firstOrNull {
                    it.value.contains(episodeSort, allowSeason = false) // 季度全集在匹配文件时是无意义的
                }?.key?.let { return it }
            }
            // 然后按季度集数 ep 匹配
            if (episodeEp != null && parsedTitles.isNotEmpty()) {
                parsedTitles.entries.firstOrNull {
                    it.value.contains(episodeEp, allowSeason = false)
                }?.key?.let { return it }
            }

            // 解析失败, 尽可能匹配一个
            episodeSort.toString().let { number ->
                videos.firstOrNull { it.getPath().contains(number, ignoreCase = true) }
                    ?.let { return it }
            }

            // 残缺清单上这条兜底会选错集: 整季包只迁移过第 1 集时清单里就只有第 1 集, 它什么都
            // 匹配不上却是唯一的视频, 于是每一集都播成第 1 集. 清单完整时同样的选择是对的——种子里
            // 只有这一个视频, 它就是这一集.
            if (!listingComplete) return null
            return videos.firstOrNull()
        }
    }
}

/**
 * 本引擎交出的文件清单不全, 这一集该换一个引擎.
 *
 * 刻意不是 [MediaSourceOpenException]: 那个类型按设计不回退, 它假定另一个引擎面对同一份文件名会
 * 得出同样的结论, 而这里的前提正是这份文件名不全.
 */
class IncompleteFileListingException(message: String) : Exception(message)

/**
 * Marker for [MediaDataProvider]s backed by a local BitTorrent engine.
 */
interface TorrentBackedMediaDataProvider

class TorrentMediaDataProvider(
    private val engine: TorrentEngine,
    private val engineAccess: TorrentEngineAccess,
    private val encodedTorrentInfo: EncodedTorrentInfo,
    private val episodeMetadata: EpisodeMetadata,
    override val extraFiles: org.openani.mediamp.source.MediaExtraFiles,
    /**
     * 本引擎打不开时接手的数据源. 云端引擎的登录、离线任务、列目录都发生在 [open] 里, 解析阶段是纯本地的,
     * 所以真正的失败大多在这里出现. 惰性求值: 解析回退本身可能唤起 BT 服务.
     */
    private val fallback: (suspend () -> MediaDataProvider<*>)? = null,
) : MediaDataProvider<MediaData>, TorrentBackedMediaDataProvider {
    @OptIn(ExperimentalStdlibApi::class)
    val uri: String by lazy {
        "torrent://${encodedTorrentInfo.data.toHexString().take(32) + "..."}"
    }

    @Throws(MediaSourceOpenException::class, CancellationException::class)
    override suspend fun open(scopeForCleanup: CoroutineScope): MediaData {
        // 注意, 这个函数须支持 cancellation. 它会在任意时刻被取消.

        logger.info {
            "TorrentVideoSource '${episodeMetadata.title}' opening a VideoData"
        }

        val requestToken = "TorrentMediaDataProvider#$this-open:${encodedTorrentInfo.data}"
        // 使用 MediaDataProvider.open 通常是在播放临时 BT 源, 在下面的 onClose 里再释放.
        // 也就是说进入从开启这个 MediaData 开始, 到下面 onClose 释放期间, 需要始终保持 BT 服务可用.
        @OptIn(UnsafeTorrentEngineAccessApi::class)
        engineAccess.requestService(requestToken, true)

        var torrentSession: TorrentSession? = null
        val handle = try {
            val downloader = engine.getDownloader()
            withContext(Dispatchers.IO_) {
                logger.info {
                    "TorrentVideoSource '${episodeMetadata.title}' waiting for files"
                }
                val session = downloader.startDownload(encodedTorrentInfo)
                torrentSession = session
                val files = session.getFiles()

                val listingComplete = (session as? PartialListing)?.listingComplete ?: true
                val selected = TorrentMediaResolver.selectVideoFileEntry(
                    files,
                    { fileName },
                    listOf(episodeMetadata.title),
                    episodeSort = episodeMetadata.sort,
                    episodeEp = episodeMetadata.ep,
                    listingComplete = listingComplete,
                )
                selected?.also {
                    logger.info {
                        "TorrentVideoSource selected file: ${it.fileName}"
                    }
                }?.createHandle()?.also { handle ->
                    handle.resume(FilePriority.HIGH)
                    // 恢复自磁盘的会话到这里都没碰过云端, 账号失效要到播放器第一次读才暴露, 那时已经
                    // 出了下面的回退范围. 先把首次读要用的直链要到手, 失败就在这里失败.
                    try {
                        (selected as? CloudReadiness)?.ensureCloudReady()
                    } catch (e: Throwable) {
                        handle.close()
                        throw e
                    }
                } ?: run {
                    val diagnosis = """
                                Torrent files: ${files.joinToString { it.fileName }}
                                Episode metadata: $episodeMetadata
                            """.trimIndent()
                    // 清单不全时匹配不上说明不了种子里没有这一集, 而 BT 手上是完整的种子, 所以这里
                    // 要回退. NO_MATCHING_FILE 按设计不回退, 用它会把一集能播的片子变成播不了.
                    if (listingComplete) {
                        throw MediaSourceOpenException(OpenFailures.NO_MATCHING_FILE, diagnosis)
                    }
                    throw IncompleteFileListingException("${engine.type} lists only part of the torrent. $diagnosis")
                }
            }
        } catch (ex: Exception) {
            // 如果上面发生了异常或被取消, 下面的 onClose 就永远不会被调用, 需要手动释放.
            @OptIn(UnsafeTorrentEngineAccessApi::class)
            engineAccess.requestService(requestToken, false)

            // NO_MATCHING_FILE 不回退: 文件清单已经拿到了, 另一个引擎面对同一份文件名会得出同样的结果,
            // 回退只会把一次失败换成另一次同样的失败.
            if (ex is CancellationException || ex is MediaSourceOpenException) throw ex
            val fallback = this.fallback ?: throw when (ex) {
                // 没有回退可走时残缺清单就是"没有匹配的文件", 让播放器拿到一个已知原因而不是裸异常.
                is IncompleteFileListingException ->
                    MediaSourceOpenException(OpenFailures.NO_MATCHING_FILE, ex.message.orEmpty(), ex)

                else -> ex
            }
            logger.warn(ex) { "${engine.type} failed to open '${episodeMetadata.title}', falling back" }
            return fallback().open(scopeForCleanup)
        }

        return TorrentMediaData(
            handle,
            engineKey = MediaCacheEngineKey(engine.type.id),
            session = torrentSession,
            onClose = {
                logger.info {
                    "TorrentVideoSource '${episodeMetadata.title}' closing"
                }
                scopeForCleanup.launch(NonCancellable + CoroutineName("TorrentMediaDataProvider.close")) {
                    try {
                        handle.close()
                    } finally {
                        // 对应了上面的 requestUseEngine(true)
                        @OptIn(UnsafeTorrentEngineAccessApi::class)
                        engineAccess.requestService(requestToken, false)
                    }
                }
            },
        )
    }

    override fun toString(): String = "TorrentVideoSource(uri=$uri, episodeMetadata=${episodeMetadata})"

    companion object {
        private val logger = logger<TorrentMediaDataProvider>()
    }
}
