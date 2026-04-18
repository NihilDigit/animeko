package me.him188.ani.torrent.offline

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * 云端离线下载后端 (Cloud offline download backend).
 *
 * 将一个磁链/种子提交到云盘服务 (PikPak, 115, 迅雷, ...), 等待云端下载完成,
 * 然后返回一个可直接流式播放的 HTTPS URL.
 */
interface OfflineDownloadEngine {
    /** Stable identifier, e.g. `"pikpak"`, `"cloud115"`. */
    val id: String

    /** Human-readable name for UI. */
    val displayName: String

    /**
     * `true` iff the engine is configured and ready to accept [resolve] calls.
     * Drives the `supports()` check in `OfflineDownloadMediaResolver`.
     */
    val isSupported: StateFlow<Boolean>

    /**
     * Submit the magnet (or http `.torrent` URL) to the provider, wait for the
     * offline download to finish, and return a playable HTTPS URL plus metadata.
     *
     * Throws on failure; callers translate exceptions to domain-level
     * `MediaResolutionException`. Coroutine cancellation cancels the resolve
     * cleanly.
     */
    suspend fun resolve(uri: String): ResolvedMedia
}

/**
 * The outcome of a successful [OfflineDownloadEngine.resolve].
 *
 * @property streamUrl HTTPS URL that `mediamp` can open. Usually short-lived
 *                    (PikPak URLs expire in ~24h) — callers should treat it as
 *                    fresh-at-this-moment and refetch if playback hits 403.
 * @property expiresAt Best-effort expiry instant; null if the provider doesn't
 *                    surface one. Not load-bearing — used only for diagnostics.
 * @property fileName Original filename from the torrent.
 * @property fileSize In bytes, if known.
 */
data class ResolvedMedia(
    val streamUrl: String,
    val expiresAt: Instant? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
)

/**
 * Provider rejected the submitted magnet (unsupported scheme, dead torrent,
 * content violates ToS, etc.). Maps to `NO_MATCHING_RESOURCE` upstream.
 */
class OfflineDownloadRejectedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Provider authentication failed (wrong credentials, expired refresh token,
 * captcha challenge we cannot solve). Maps to `ENGINE_ERROR` upstream.
 */
class OfflineDownloadAuthException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
