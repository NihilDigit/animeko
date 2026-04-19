package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.io.IOException
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.torrent.offline.OfflineDownloadAuthException
import me.him188.ani.torrent.offline.OfflineDownloadEngine
import me.him188.ani.torrent.offline.OfflineDownloadRejectedException
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resolves BT-kind [Media] items by delegating the magnet / `.torrent` URL to
 * a cloud offline-download provider (PikPak today, 115/迅雷/etc. in the future).
 *
 * Placed **first** in the resolver chain so it intercepts magnets before the
 * local anitorrent-based [TorrentMediaResolver]. If the engine is disabled or
 * unconfigured, [supports] returns `false` and the chain falls through.
 *
 * When [fallback] is supplied, any engine-side failure (auth, network, rejected
 * uri, timeout, unknown) is caught and delegated to it instead of surfacing as
 * a [MediaResolutionException]. This keeps the offline provider from turning
 * "external service disabled/broken" into a hard precondition for BT playback
 * — the local torrent resolver stays reachable for the same magnet.
 * [CancellationException] is always rethrown, never fallen through.
 */
class OfflineDownloadMediaResolver(
    private val engine: OfflineDownloadEngine,
    private val fallback: MediaResolver? = null,
) : MediaResolver {
    private val logger = logger<OfflineDownloadMediaResolver>()

    override fun supports(media: Media): Boolean {
        if (!engine.isSupported.value) return false
        return when (media.download) {
            is ResourceLocation.MagnetLink -> true
            is ResourceLocation.HttpTorrentFile -> true
            else -> false
        }
    }

    override suspend fun resolve(
        media: Media,
        episode: EpisodeMetadata,
    ): MediaDataProvider<MediaData> {
        if (!supports(media)) throw UnsupportedMediaException(media)
        val uri = when (val d = media.download) {
            is ResourceLocation.MagnetLink -> d.uri
            is ResourceLocation.HttpTorrentFile -> d.uri
            else -> throw UnsupportedMediaException(media)
        }

        // Season-pack handling: when the provider unpacks a multi-file torrent
        // into a folder, the engine asks us which child video to pick. Reuse
        // the same selection logic anitorrent uses (`selectVideoFileEntry`) so
        // offline and local-torrent picks behave identically.
        val episodeTitles = buildList {
            if (episode.title.isNotBlank()) add(episode.title)
            if (media.originalTitle.isNotBlank()) add(media.originalTitle)
        }
        val pickVideoFile: (List<String>) -> String? = { names ->
            TorrentMediaResolver.selectVideoFileEntry(
                entries = names,
                getPath = { this },
                episodeTitles = episodeTitles,
                episodeSort = episode.sort,
                episodeEp = episode.ep,
            )
        }

        logger.info {
            "[${engine.id}] resolving media '${media.mediaId}' via ${engine.displayName}"
        }
        val resolved = try {
            engine.resolve(uri, pickVideoFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = when (e) {
                is OfflineDownloadAuthException -> ResolutionFailures.ENGINE_ERROR
                is OfflineDownloadRejectedException -> ResolutionFailures.NO_MATCHING_RESOURCE
                is TimeoutCancellationException -> ResolutionFailures.FETCH_TIMEOUT
                is IOException -> ResolutionFailures.NETWORK_ERROR
                else -> ResolutionFailures.ENGINE_ERROR
            }
            val fb = fallback
            if (fb != null && fb.supports(media)) {
                logger.warn(e) {
                    "[${engine.id}] resolve failed ($reason); falling back to local resolver"
                }
                return fb.resolve(media, episode)
            }
            logger.warn(e) { "[${engine.id}] resolve failed ($reason); no fallback available" }
            throw MediaResolutionException(reason, e)
        }

        return HttpStreamingMediaDataProvider(
            uri = resolved.streamUrl,
            originalTitle = resolved.fileName ?: media.originalTitle,
            headers = emptyMap(),
            extraFiles = media.extraFiles.toMediampMediaExtraFiles(),
        )
    }
}
