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
 * A file entry whose first read depends on the cloud.
 *
 * A session restored from disk contacts PikPak for nothing, and the link a
 * read needs is resolved lazily, so an account that is no longer valid only
 * fails inside the player's first read, past the point where playback could
 * still fall back to another engine. A caller that wants that failure early
 * awaits [ensureCloudReady] before handing the entry to the player.
 */
interface CloudReadiness {
    /**
     * Returns once the first read can proceed without a further round trip
     * that could fail, or throws with the failure that read would have hit.
     * No-op for a file that is complete on disk.
     */
    suspend fun ensureCloudReady()
}
