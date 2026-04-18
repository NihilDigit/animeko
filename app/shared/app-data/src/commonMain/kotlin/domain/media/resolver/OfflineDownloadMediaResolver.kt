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
 */
class OfflineDownloadMediaResolver(
    private val engine: OfflineDownloadEngine,
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

        logger.info {
            "[${engine.id}] resolving media '${media.mediaId}' via ${engine.displayName}"
        }
        val resolved = try {
            engine.resolve(uri)
        } catch (e: OfflineDownloadAuthException) {
            logger.warn(e) { "${engine.id} auth failed" }
            throw MediaResolutionException(ResolutionFailures.ENGINE_ERROR, e)
        } catch (e: OfflineDownloadRejectedException) {
            logger.warn(e) { "${engine.id} rejected uri" }
            throw MediaResolutionException(ResolutionFailures.NO_MATCHING_RESOURCE, e)
        } catch (e: TimeoutCancellationException) {
            throw MediaResolutionException(ResolutionFailures.FETCH_TIMEOUT, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw MediaResolutionException(ResolutionFailures.NETWORK_ERROR, e)
        } catch (e: Exception) {
            logger.warn(e) { "${engine.id} unexpected error" }
            throw MediaResolutionException(ResolutionFailures.ENGINE_ERROR, e)
        }

        return HttpStreamingMediaDataProvider(
            uri = resolved.streamUrl,
            originalTitle = resolved.fileName ?: media.originalTitle,
            headers = emptyMap(),
            extraFiles = media.extraFiles.toMediampMediaExtraFiles(),
        )
    }
}
