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
import kotlinx.coroutines.withTimeoutOrNull
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
import org.openani.mediamp.source.MediaExtraFiles
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Resolver chains choose by supports(), not by success. Cloud failures need an explicit BT fallback.
class TorrentMediaResolver(
    private val engine: TorrentEngine,
    private val engineAccess: TorrentEngineAccess,
    private val fallback: MediaResolver? = null,
    private val cloudReadyTimeout: Duration = CLOUD_READY_TIMEOUT,
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
                            fallback = fallback?.takeIf { it.supports(media) }?.let {
                                suspend { it.resolve(media, episode) }
                            },
                            cloudReadyTimeout = cloudReadyTimeout,
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

            // A lone imported episode is not evidence that the torrent contains only that episode.
            if (!listingComplete) return null
            return videos.firstOrNull()
        }
    }
}

class IncompleteFileListingException(message: String) : Exception(message)

class CloudNotReadyException(message: String) : Exception(message)

// Long enough to cover a cold start that works (a sign-in, the file object, the variant and the
// signed link take about two seconds), short enough that a network which cannot reach the cloud
// sends playback to the local BT engine instead of a spinner.
private val CLOUD_READY_TIMEOUT = 15.seconds

/**
 * Marker for [MediaDataProvider]s backed by a local BitTorrent engine.
 */
interface TorrentBackedMediaDataProvider

class TorrentMediaDataProvider(
    private val engine: TorrentEngine,
    private val engineAccess: TorrentEngineAccess,
    private val encodedTorrentInfo: EncodedTorrentInfo,
    private val episodeMetadata: EpisodeMetadata,
    override val extraFiles: MediaExtraFiles,
    private val fallback: (suspend () -> MediaDataProvider<*>)? = null,
    private val cloudReadyTimeout: Duration = CLOUD_READY_TIMEOUT,
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

                    try {
                        // The cloud path retries inside the SDK, so a broken network never surfaces
                        // an error here and the fallback below stays unreachable while the player
                        // shows a spinner. withTimeoutOrNull, not withTimeout: the latter throws a
                        // CancellationException, which the catch below rethrows past the fallback.
                        val ready = withTimeoutOrNull(cloudReadyTimeout) {
                            (selected as? CloudReadiness)?.ensureCloudReady()
                            true
                        }
                        // Not a MediaSourceOpenException either, for the same reason.
                        if (ready == null) {
                            throw CloudNotReadyException(
                                "${engine.type} did not settle ${selected.fileName} within $cloudReadyTimeout",
                            )
                        }
                    } catch (e: Throwable) {
                        handle.close()
                        throw e
                    }
                } ?: run {
                    val diagnosis = """
                                Torrent files: ${files.joinToString { it.fileName }}
                                Episode metadata: $episodeMetadata
                            """.trimIndent()

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

            if (ex is CancellationException || ex is MediaSourceOpenException) throw ex
            val fallback = this.fallback ?: throw when (ex) {
                is IncompleteFileListingException ->
                    MediaSourceOpenException(OpenFailures.NO_MATCHING_FILE, ex.message.orEmpty(), ex)

                else -> ex
            }
            logger.warn(ex) { "${engine.type} failed to open '${episodeMetadata.title}', falling back" }
            // No handle was created here, so nothing downstream would ever close this session.
            torrentSession?.let { session ->
                try {
                    session.closeIfNotInUse()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn(e) { "Failed to close ${engine.type} session before falling back" }
                }
            }
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
