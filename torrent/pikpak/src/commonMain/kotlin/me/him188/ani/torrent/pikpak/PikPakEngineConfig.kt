/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.VariantPreference

/**
 * Runtime knobs of [PikPakTorrentDownloader]. Supplied as a `StateFlow` so the
 * settings page can change them without recreating the engine.
 */
data class PikPakEngineConfig(
    /**
     * `"original"`, or a PikPak resolution name: `"1080P"`, `"720P"`, `"480P"`.
     * Transcodes are MPEG-TS without embedded subtitles.
     *
     * 只影响还没定下变体的目录. 盘上已经有字节的目录按 `meta.json` 记下的变体继续, 因为那些
     * 字节属于那一份流.
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
) {
    companion object {
        const val VARIANT_ORIGINAL = "original"

        const val MAX_CONCURRENCY: Int = 8
    }

    val effectiveConcurrency: Int get() = concurrency.coerceIn(1, MAX_CONCURRENCY)

    val prefersOriginal: Boolean get() = variant.isEmpty() || variant.equals(VARIANT_ORIGINAL, ignoreCase = true)

    /**
     * 这个设置对应的 SDK 偏好.
     *
     * 请求的分辨率不存在或者还在转码时, `resolveVariant` 透明地回退到原画; 回退在那一处发生,
     * 这里不预判.
     */
    val variantPreference: VariantPreference
        get() = if (prefersOriginal) VariantPreference.Original else VariantPreference.Resolution(variant)
}
