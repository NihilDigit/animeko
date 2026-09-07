/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

/**
 * Runtime knobs of [PikPakTorrentDownloader]. Supplied as a `StateFlow` so the
 * settings page can change them without recreating the engine.
 *
 * [variant] only affects sessions that have no on-disk state yet: the variant a
 * session was started with is recorded in its `meta.json` and keeps being used,
 * because the transcoded byte stream differs from the original one and any
 * bytes already on disk are one of them and not the other.
 */
data class PikPakEngineConfig(
    /**
     * `"original"`, or a PikPak resolution name: `"1080P"`, `"720P"`, `"480P"`.
     * Transcodes are MPEG-TS without embedded subtitles.
     */
    val variant: String = VARIANT_ORIGINAL,
    /**
     * Concurrent range reads per file, 1..8. A signed PikPak URL accepts eight
     * concurrent connections and answers the ninth with 503, so eight is a
     * property of the CDN rather than a tuning ceiling we picked.
     *
     * A single connection measured about 0.22 MB/s, which four of them cannot
     * turn into a sustainable 1080p stream, so the default sits at the ceiling.
     */
    val concurrency: Int = 8,
    /**
     * How many source buckets may sit in the cloud slot folder at once. Values
     * `>= SLOT_QUEUE_UNLIMITED` disable eviction.
     */
    val slotQueueLength: Int = 1,
) {
    companion object {
        const val VARIANT_ORIGINAL = "original"

        /**
         * At or above this queue length the engine stops evicting; the user's
         * PikPak quota becomes the only limit. Mirrors
         * `PikPakConfig.SLOT_QUEUE_UNLIMITED` on the app side.
         */
        const val SLOT_QUEUE_UNLIMITED: Int = 14

        const val MAX_CONCURRENCY: Int = 8
    }

    val effectiveConcurrency: Int get() = concurrency.coerceIn(1, MAX_CONCURRENCY)

    val prefersOriginal: Boolean get() = variant.isEmpty() || variant.equals(VARIANT_ORIGINAL, ignoreCase = true)
}
