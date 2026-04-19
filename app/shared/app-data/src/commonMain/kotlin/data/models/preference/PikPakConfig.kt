package me.him188.ani.app.data.models.preference

import kotlinx.serialization.Serializable

/**
 * User-configurable settings for the PikPak offline-download backend.
 *
 * The engine runs a server-side slot cache: completed offline tasks stay in a
 * well-known working folder on the user's PikPak drive, keyed by source
 * bucket, so replays of the same magnet are served straight from the cache.
 * Old buckets are evicted to honor [slotQueueLength] — they are *not* deleted
 * immediately after each resolve. See `PikPakOfflineDownloadEngine` for the
 * full eviction policy.
 *
 * [refreshToken] is written by the engine after a successful signin/refresh
 * (not user-editable). It lets the next app launch skip the rate-limited
 * `/v1/auth/signin` endpoint and go straight to a cheap refresh.
 *
 * [password] is accepted from the settings UI to bootstrap the first signin,
 * but the engine clears it from this config once a refresh token has been
 * obtained — so the password is not persisted to disk across app restarts. If
 * the refresh token later becomes invalid, the UI will ask for the password
 * again.
 */
@Serializable
data class PikPakConfig(
    val enabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    val refreshToken: String = "",
    /**
     * How many distinct source buckets the engine keeps cached in its
     * working folder ("Animeko-Playing"). Real numeric values 1..13 are
     * bucket caps; the UI also offers a final "unlimited" stop (stored as
     * [SLOT_QUEUE_UNLIMITED]) that disables eviction entirely.
     */
    val slotQueueLength: Int = 1,
) {
    companion object {
        val Default = PikPakConfig()

        /** Last numeric step on the slider. */
        const val SLOT_QUEUE_MAX_NUMERIC: Int = 13

        /**
         * One step past [SLOT_QUEUE_MAX_NUMERIC]: the dedicated "no eviction"
         * stop. Any value ≥ this is treated as unlimited by the engine.
         */
        const val SLOT_QUEUE_UNLIMITED: Int = SLOT_QUEUE_MAX_NUMERIC + 1
    }
}
