/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.length
import me.him188.ani.utils.io.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The piece list of a transcode is laid out against its probed length, not
 * against the length the torrent gives.
 *
 * This is the one place restoring a quality setting can really break: a
 * transcode is a different encoding with a different byte count, so a piece
 * list built at the torrent's length never fills when the transcode is shorter
 * and is declared complete early when it is longer.
 */
class TranscodeCacheLengthTest {
    @Test
    fun `a cached transcode finishes at its probed length`() = runBlocking {
        val saveDirectory = SystemPaths.createTempDirectory("pikpak-transcode-length")
        val transcode = ByteArray(48 * 1024) { (it % 251).toByte() }
        // The original is longer. Used as the finish line, the download below
        // could never reach it.
        val torrentLength = transcode.size + 32L * 1024

        val decided = mutableListOf<StreamVariant>()
        val entry = PikPakFileEntry(
            index = 0,
            length = torrentLength,
            saveDirectory = saveDirectory,
            relativePath = "01.mkv",
            torrentId = "test-source-key",
            parentCoroutineContext = Dispatchers.IO,
            meta = PikPakFileMeta(index = 0, pathInTorrent = "01.mkv", gcid = "GCID-01", length = torrentLength),
            source = FakeRangeSource(transcode),
            initialVariantLength = torrentLength,
            streamVariant = { StreamVariant(mediaId = "media-720", length = transcode.size.toLong()) },
            onVariantDecided = { decided += it },
            concurrency = 2,
            onHandleCountChanged = {},
        )

        val handle = entry.createHandle()
        handle.resume(FilePriority.NORMAL)
        withTimeout(30_000) { entry.fileStats.first { it.isDownloadFinished } }

        assertTrue(entry.isComplete)
        assertEquals(transcode.size.toLong(), saveDirectory.resolve("01.mkv").length())
        // Recorded before the first byte lands, or the disk would hold bytes
        // belonging to a stream nothing names.
        assertEquals(listOf(StreamVariant("media-720", transcode.size.toLong())), decided)

        handle.close()
        entry.close()
    }
}
