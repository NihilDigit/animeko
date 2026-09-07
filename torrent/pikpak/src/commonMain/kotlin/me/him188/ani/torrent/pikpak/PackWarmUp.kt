/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.TorrentFileEntry

/**
 * Tells a PikPak session which files of a pack the viewer is likely to open
 * next, so their signed links are resolved before they are needed.
 *
 * No bytes are fetched. What switching episode waits for is the `getFile`
 * round trip that turns a cloud file id into a signed URL, and that is what
 * this removes from the critical path. Fetching the head and tail of the
 * neighbours as well was tried and reverted: it spent the viewer's bandwidth
 * on episodes they might never open and left scratch on disk that then needed
 * a lifetime policy of its own.
 *
 * The engine has no opinion about which files those are, deliberately. It
 * could pick the episodes out of a pack by file name, but the app's episode
 * selector has already made that call with far more to go on, and it is the
 * same call playback follows; a second guess down here could only disagree
 * with it, silently.
 *
 * The session returned by
 * [startDownload][me.him188.ani.app.torrent.api.TorrentDownloader.startDownload]
 * implements this; cast to it, or use [asPackWarmUp].
 */
interface PackWarmUp {
    /**
     * Sets the entries whose links to resolve, in priority order, next episode
     * first.
     *
     * Replaces any previous list, cancelling whatever it was still resolving.
     * Entries that do not belong to this session are ignored. An empty list
     * resolves nothing, which is also the state a session starts in.
     */
    fun setWarmUpTargets(entries: List<TorrentFileEntry>)
}

/** This session's warm-up control, or null when it is not a PikPak session. */
fun TorrentSession.asPackWarmUp(): PackWarmUp? = this as? PackWarmUp
