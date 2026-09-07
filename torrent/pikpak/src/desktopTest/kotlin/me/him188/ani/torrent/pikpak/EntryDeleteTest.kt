/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.ktor.utils.io.ByteReadChannel
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
    /**
     * Stands in for [VariantReader], including the part that made the bug
     * visible: once closed it refuses to serve a byte.
     */
    private class FakeEntrySource(content: ByteArray) : EntrySource {
        private val delegate = FakeRangeSource(content)
        var closed = false
            private set

        override suspend fun <T> read(
            start: Long,
            length: Long,
            priority: Int,
            block: suspend (ByteReadChannel) -> T,
        ): T {
            check(!closed) { "FakeEntrySource is closed" }
            return delegate.read(start, length, priority, block)
        }

        override suspend fun prewarm() {
            check(!closed) { "FakeEntrySource is closed" }
        }

        override fun close() {
            closed = true
        }
    }

    private fun entry(
        saveDirectory: SystemPath,
        path: String,
        length: Long,
        source: EntrySource,
        scheduler: DownloadScheduler,
        onHandleCountChanged: suspend () -> Unit,
    ) = PikPakFileEntry(
        index = 0,
        length = length,
        saveDirectory = saveDirectory,
        relativePath = path,
        torrentId = "test-source-key",
        parentCoroutineContext = Dispatchers.IO,
        meta = PikPakFileMeta(index = 0, pathInTorrent = path, fileId = "f-$path", length = length),
        reader = source,
        concurrency = 2,
        scheduler = scheduler,
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
        val content = ByteArray(64 * 1024) { (it % 251).toByte() }
        val scheduler = DownloadScheduler()

        // The first episode is already cached in full, as a restore would find
        // it; the second is what keeps the session from closing when the first
        // one's handle goes.
        val dataPath = saveDirectory.resolve("01.mkv").also { it.writeBytes(content) }

        val source = FakeEntrySource(content)
        var session: PikPakSession? = null
        val cached = entry(
            saveDirectory, "01.mkv", content.size.toLong(), source, scheduler,
            onHandleCountChanged = { session?.closeIfNotInUse() },
        )
        val sibling = entry(
            saveDirectory, "02.mkv", content.size.toLong(), FakeEntrySource(content), scheduler,
            onHandleCountChanged = { session?.closeIfNotInUse() },
        )
        session = PikPakSession(
            sourceKey = "test-source-key",
            torrentName = "pack",
            saveDirectory = saveDirectory,
            entries = listOf(cached, sibling),
            onClosed = {},
            parentCoroutineContext = Dispatchers.IO,
        )
        assertEquals(content.size.toLong(), cached.downloadedBytes)

        val keepAlive = sibling.createHandle()
        val handle = cached.createHandle()
        handle.closeAndDelete()

        assertFalse(dataPath.exists(), "the cached file is gone")
        assertEquals(0L, cached.downloadedBytes, "progress must not survive the file it described")
        assertFalse(cached.isComplete)
        assertFalse(source.closed, "the entry is still in the session, so its reader must stay usable")

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
