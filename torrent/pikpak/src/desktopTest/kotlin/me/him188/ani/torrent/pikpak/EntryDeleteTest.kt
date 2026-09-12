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
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deleting one episode's cache must leave the entry usable, because the entry
 * outlives the deletion: it stays in its session, and the session stays open
 * for as long as any episode of the pack holds a handle.
 */
class EntryDeleteTest {
    private fun entry(
        saveDirectory: SystemPath,
        path: String,
        length: Long,
        source: FakeCloudFile,
        onHandleCountChanged: suspend () -> Unit,
    ) = PikPakFileEntry(
        index = 0,
        length = length,
        saveDirectory = saveDirectory,
        relativePath = path,
        torrentId = "test-source-key",
        parentCoroutineContext = Dispatchers.IO,
        meta = PikPakFileMeta(index = 0, pathInTorrent = path, gcid = "GCID-$path", length = length),
        source = source,
        initialVariantLength = length,
        streamVariant = { StreamVariant(mediaId = null, length = length) },
        onVariantDecided = {},
        concurrency = 2,
        onHandleCountChanged = onHandleCountChanged,
    )

    @Test
    fun `a deleted episode can be downloaded again inside a session that stayed open`() = runBlocking {
        // closeAndDelete used to close the entry: the downloader's scope was
        // cancelled for good and the reader shut. The entry stays in the
        // session, so once another episode's handle kept the session alive,
        // every later request for the deleted episode got a downloader that
        // would not start, a reader that threw, and a progress figure taken
        // from the file that had just been deleted.
        val saveDirectory = SystemPaths.createTempDirectory("pikpak-entry-delete")
        // Big enough to span the header pieces, the footer piece and more than
        // one controller window. A file of a single piece downloads again even
        // with a controller that has latched "everything is done", so a small
        // one would pass while the second download was in fact broken.
        val content = ByteArray(12 * 1024 * 1024) { (it % 251).toByte() }

        // The first episode is downloaded here rather than staged on disk,
        // because what breaks the second download is the state the first one
        // leaves in the controller. The second episode is what keeps the
        // session from closing when the first one's handle goes.
        val dataPath = saveDirectory.resolve("01.mkv")

        val source = FakeCloudFile(content)
        var session: PikPakSession? = null
        val cached = entry(
            saveDirectory, "01.mkv", content.size.toLong(), source,
            onHandleCountChanged = { session?.closeIfNotInUse() },
        )
        val sibling = entry(
            saveDirectory, "02.mkv", content.size.toLong(), FakeCloudFile(content),
            onHandleCountChanged = { session?.closeIfNotInUse() },
        )
        session = PikPakSession(
            sourceKey = "test-source-key",
            torrentName = "pack",
            saveDirectory = saveDirectory,
            entries = listOf(cached, sibling),
            listingComplete = true,
            onClosed = {},
            parentCoroutineContext = Dispatchers.IO,
        )
        val keepAlive = sibling.createHandle()
        val handle = cached.createHandle()
        handle.resume(FilePriority.NORMAL)
        withTimeout(60_000) { cached.fileStats.first { it.isDownloadFinished } }
        assertEquals(content.size.toLong(), cached.downloadedBytes)

        handle.closeAndDelete()

        assertFalse(dataPath.exists(), "the cached file is gone")
        assertEquals(0L, cached.downloadedBytes, "progress must not survive the file it described")
        assertFalse(cached.isComplete)

        val again = cached.createHandle()
        again.resume(FilePriority.NORMAL)
        withTimeout(30_000) { cached.fileStats.first { it.isDownloadFinished } }
        assertContentEquals(content, dataPath.readBytes(), "the episode downloads again from scratch")

        again.close()
        keepAlive.close()
        assertTrue(dataPath.exists())
        session.close()
    }
}
