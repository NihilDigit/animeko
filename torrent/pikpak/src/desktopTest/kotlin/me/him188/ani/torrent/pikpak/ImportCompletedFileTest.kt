/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.InMemorySessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import me.him188.ani.app.torrent.api.pieces.PieceState
import me.him188.ani.app.torrent.io.RandomAccessFile
import me.him188.ani.app.torrent.api.pieces.forEach
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.length
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The migration off the old HTTP path hands the engine a file it did not
 * download. What it must get back is a session indistinguishable from one that
 * finished normally, without a single request: the cloud copy of a cache this
 * old has usually been evicted, and asking about it would mean resubmitting the
 * magnet just to play bytes that are already on disk.
 *
 * The client is a MockEngine that fails every request, so "no network" is
 * enforced rather than counted.
 */
class ImportCompletedFileTest {
    private val magnet =
        "magnet:?xt=urn:btih:157e0a57e1af0e1cfd46258ba6c62938c21b6ee8&dn=archlinux.iso"

    private fun downloader(
        root: me.him188.ani.utils.io.SystemPath,
        // Stands in for a drive nobody can reach, which is the situation the
        // migration leaves behind: an imported directory is not a full listing,
        // so restoring it does try the cloud once.
        cloudIndex: suspend (uri: String, sourceKey: String) -> PikPakTorrentMeta = { _, _ ->
            throw IOException("no network in this test")
        },
    ) = PikPakTorrentDownloader(
        httpClient = HttpClient(MockEngine { error("the import path must not make any request") }),
        credentials = MutableStateFlow(PikPakCredentials("nobody@example.com", "unused")),
        sessionStore = InMemorySessionStore(),
        rootDataDirectory = root,
        config = MutableStateFlow(PikPakEngineConfig()),
        parentCoroutineContext = Dispatchers.IO,
    ).also { it.cloudIndexer = cloudIndex }

    @Test
    fun `an imported file restores as fully downloaded and offline`() = runBlocking {
        val temp = SystemPaths.createTempDirectory("pikpak-import")
        val root = temp.resolve("pikpak")
        // Deliberately not a multiple of the piece size, so the tail piece is
        // short and a piece-count off-by-one would show up.
        val content = ByteArray(PikPakFileEntry.PIECE_SIZE.toInt() * 2 + 12345) { (it % 251).toByte() }
        val staged = temp.resolve("legacy-download.mp4")
        staged.writeBytes(content)

        val downloader = downloader(root)
        val imported = PikPakSavedFiles.importCompletedFile(root, magnet, staged, "01.mp4")

        assertFalse(staged.exists(), "the source is moved, not copied")
        assertEquals(content.size.toLong(), imported.length())
        assertContentEquals(content, imported.readBytes())

        val session = downloader.startDownload(downloader.fetchTorrent(magnet))
        val entry = session.getFiles().single()

        assertEquals("01.mp4", entry.pathInTorrent)
        assertEquals(content.size.toLong(), entry.length)
        with(entry.pieces) {
            entry.pieces.forEach { piece ->
                assertEquals(PieceState.FINISHED, piece.state, "piece ${piece.pieceIndex}")
            }
        }
        val stats = entry.fileStats.first()
        assertEquals(content.size.toLong(), stats.downloadedBytes)
        assertTrue(stats.isDownloadFinished)

        // Resuming a handle is what would normally warm a link. A complete file
        // must not do even that.
        val handle = entry.createHandle()
        handle.resume()
        handle.close()

        session.close()
        downloader.close()
    }

    @Test
    fun `a partial file restores as partial progress, not as complete`() = runBlocking {
        // Nothing is preallocated any more, and no bitmap records what is
        // present: the file's own length is the progress. A record whose file
        // was truncated must therefore come back as unfinished, so the
        // downloader resumes it, rather than as a complete cache of zero bytes.
        val temp = SystemPaths.createTempDirectory("pikpak-partial")
        val root = temp.resolve("pikpak")
        val content = ByteArray(PikPakFileEntry.PIECE_SIZE.toInt() * 3)
        val staged = temp.resolve("legacy.mp4").also { it.writeBytes(content) }

        val imported = PikPakSavedFiles.importCompletedFile(root, magnet, staged, "01.mp4")

        val kept = PikPakFileEntry.PIECE_SIZE
        RandomAccessFile(imported, "rw").use { it.setLength(kept) }

        val downloader = downloader(root)
        val session = downloader.startDownload(downloader.fetchTorrent(magnet))
        val entry = session.getFiles().single()

        assertEquals(content.size.toLong(), entry.length, "the recorded length is unchanged")
        val stats = entry.fileStats.first()
        assertEquals(kept, stats.downloadedBytes)
        assertFalse(stats.isDownloadFinished)
        assertEquals(kept, imported.length(), "building the session must not touch the file")

        session.close()
        downloader.close()
    }

    @Test
    fun `importing a second file of the same torrent keeps the first`() = runBlocking {
        // A pack is migrated one file at a time. Rewriting meta.json per import
        // left the directory describing only the last file, and every episode
        // before it became unreachable while its bytes sat on disk.
        val temp = SystemPaths.createTempDirectory("pikpak-import-pack")
        val root = temp.resolve("pikpak")
        val first = ByteArray(4096) { it.toByte() }
        val second = ByteArray(8192) { (it * 3).toByte() }

        PikPakSavedFiles.importCompletedFile(
            root, magnet, temp.resolve("a.mp4").also { it.writeBytes(first) }, "01.mp4",
        )
        PikPakSavedFiles.importCompletedFile(
            root, magnet, temp.resolve("b.mp4").also { it.writeBytes(second) }, "02.mp4",
        )

        val downloader = downloader(root)
        val session = downloader.startDownload(downloader.fetchTorrent(magnet))
        val entries = session.getFiles().sortedBy { it.pathInTorrent }

        assertEquals(listOf("01.mp4", "02.mp4"), entries.map { it.pathInTorrent })
        assertEquals(listOf(first.size.toLong(), second.size.toLong()), entries.map { it.length })
        for (entry in entries) {
            assertTrue(entry.fileStats.first().isDownloadFinished, "${entry.pathInTorrent} is not complete")
        }

        session.close()
        downloader.close()
    }

    @Test
    fun `an imported episode is filled out from the cloud, not taken for the whole pack`() = runBlocking {
        // Migrating one episode of a season pack left meta.json describing a
        // one-file torrent. Opening any other episode of the same magnet then
        // got a session with that single file in it, and the app, seeing one
        // video, played the migrated episode for every episode of the season.
        val temp = SystemPaths.createTempDirectory("pikpak-import-partial-index")
        val root = temp.resolve("pikpak")
        val imported = ByteArray(4096) { it.toByte() }
        PikPakSavedFiles.importCompletedFile(
            root, magnet, temp.resolve("a.mp4").also { it.writeBytes(imported) }, "01.mp4",
        )

        val downloader = downloader(root) { uri, sourceKey ->
            PikPakTorrentMeta(
                uri = uri,
                sourceKey = sourceKey,
                name = "Season Pack",
                bucketId = "bucket-1",
                files = listOf(
                    // The cloud's own answer for the imported episode differs in
                    // every field that describes bytes, because the account's
                    // quality preference has nothing to do with what was
                    // migrated onto this disk.
                    PikPakFileMeta(
                        index = 0, pathInTorrent = "01.mp4", fileId = "cloud-1",
                        mediaId = "m-720", variantLabel = "720P", length = 999_999,
                    ),
                    PikPakFileMeta(index = 1, pathInTorrent = "02.mp4", fileId = "cloud-2", length = 8192),
                ),
            )
        }

        val session = downloader.startDownload(downloader.fetchTorrent(magnet))
        val entries = session.getFiles().map { it as PikPakFileEntry }.sortedBy { it.pathInTorrent }

        assertEquals(listOf("01.mp4", "02.mp4"), entries.map { it.pathInTorrent })
        val first = entries.first()
        assertEquals(imported.size.toLong(), first.length, "the imported length describes the bytes on disk")
        assertEquals("Original", first.meta.variantLabel)
        assertEquals(null, first.meta.mediaId)
        assertEquals("cloud-1", first.meta.fileId, "the file id is the only thing the cloud knows better")
        assertTrue(first.fileStats.first().isDownloadFinished, "the imported episode is still complete")
        assertEquals(8192L, entries[1].length)

        val onDisk = PikPakResumeData(root.resolve(sourceKeyFor(magnet))).read()
        assertTrue(onDisk!!.indexed, "a filled-in listing must not be indexed again on the next start")

        session.close()
        downloader.close()
    }

    @Test
    fun `an imported file moves to the subfolder the cloud lists it under`() = runBlocking {
        // The migration imports a bare file name; the cloud keeps the pack's
        // subfolders. Matching paths exactly dropped the import, so the cloud
        // entry had no file behind it and the bytes on disk were orphaned.
        val temp = SystemPaths.createTempDirectory("pikpak-import-relocate")
        val root = temp.resolve("pikpak")
        val imported = ByteArray(4096) { it.toByte() }
        PikPakSavedFiles.importCompletedFile(
            root, magnet, temp.resolve("a.mp4").also { it.writeBytes(imported) }, "SP01.mp4",
        )
        PikPakSavedFiles.importCompletedFile(
            root, magnet, temp.resolve("b.mp4").also { it.writeBytes(imported) }, "orphan.mp4",
        )

        val downloader = downloader(root) { uri, sourceKey ->
            PikPakTorrentMeta(
                uri = uri, sourceKey = sourceKey, name = "Season Pack", bucketId = "bucket-1",
                files = listOf(
                    PikPakFileMeta(index = 0, pathInTorrent = "01.mp4", fileId = "cloud-1", length = 8192),
                    PikPakFileMeta(index = 1, pathInTorrent = "specials/SP01.mp4", fileId = "cloud-2", length = 4096),
                ),
            )
        }

        val session = downloader.startDownload(downloader.fetchTorrent(magnet))
        val entries = session.getFiles().map { it as PikPakFileEntry }.associateBy { it.pathInTorrent }

        assertEquals(setOf("01.mp4", "specials/SP01.mp4", "orphan.mp4"), entries.keys)
        val moved = entries.getValue("specials/SP01.mp4")
        assertEquals("cloud-2", moved.meta.fileId)
        assertTrue(moved.fileStats.first().isDownloadFinished, "the bytes followed the entry to the cloud path")
        val saveDir = root.resolve(sourceKeyFor(magnet))
        assertTrue(saveDir.resolve("specials/SP01.mp4").exists())
        assertTrue(!saveDir.resolve("SP01.mp4").exists())
        assertTrue(entries.getValue("orphan.mp4").fileStats.first().isDownloadFinished, "an unmatched import is kept")

        session.close()
        downloader.close()
    }

    @Test
    fun `an imported episode stays playable while the cloud is unreachable`() = runBlocking {
        val temp = SystemPaths.createTempDirectory("pikpak-import-offline")
        val root = temp.resolve("pikpak")
        val imported = ByteArray(4096) { it.toByte() }
        PikPakSavedFiles.importCompletedFile(
            root, magnet, temp.resolve("a.mp4").also { it.writeBytes(imported) }, "01.mp4",
        )

        val downloader = downloader(root)
        val session = downloader.startDownload(downloader.fetchTorrent(magnet))
        val entry = session.getFiles().single()

        assertEquals("01.mp4", entry.pathInTorrent)
        assertTrue(entry.fileStats.first().isDownloadFinished)

        val onDisk = PikPakResumeData(root.resolve(sourceKeyFor(magnet))).read()
        assertFalse(onDisk!!.indexed, "a failed index must leave the retry in place")

        session.close()
        downloader.close()
    }

    @Test
    fun `importing twice in a row is idempotent`() = runBlocking {
        val temp = SystemPaths.createTempDirectory("pikpak-import-twice")
        val root = temp.resolve("pikpak")
        val content = ByteArray(4096) { it.toByte() }
        val downloader = downloader(root)

        val first = temp.resolve("a.mp4").also { it.writeBytes(content) }
        val importedOnce = PikPakSavedFiles.importCompletedFile(root, magnet, first, "01.mp4")

        // A retried migration re-imports the file it already moved.
        val importedTwice = PikPakSavedFiles.importCompletedFile(root, magnet, importedOnce, "01.mp4")

        assertContentEquals(content, importedTwice.readBytes())
        val session = downloader.startDownload(downloader.fetchTorrent(magnet))
        assertTrue(session.getFiles().single().fileStats.first().isDownloadFinished)
        session.close()
        downloader.close()
    }
}
