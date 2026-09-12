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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The migration off the old HTTP path hands the engine a file it did not
 * download. What it must get back is a session indistinguishable from one that
 * finished normally, and without needing anything from the cloud: the bytes are
 * on disk and a complete file never builds a cloud file object, which would be
 * charged at its full size.
 *
 * The client is a MockEngine that fails every request, so "no network" is
 * enforced rather than counted. 引擎自己的开机清理仍然会试一次并失败, 那是引擎的行为, 不是导入
 * 路径的.
 */
class ImportCompletedFileTest {
    private val magnet =
        "magnet:?xt=urn:btih:157e0a57e1af0e1cfd46258ba6c62938c21b6ee8&dn=01.mp4"

    private fun downloader(
        root: me.him188.ani.utils.io.SystemPath,
        // Stands in for a drive nobody can reach, which is the situation the
        // migration leaves behind: an imported directory is not a full listing,
        // so restoring it does try to resolve the magnet once.
        resolve: suspend (uri: String) -> io.github.nihildigit.pikpak.MagnetResource? = {
            throw IOException("no network in this test")
        },
    ) = PikPakTorrentDownloader(
        httpClient = HttpClient(MockEngine { error("the import path must not make any request") }),
        credentials = MutableStateFlow(PikPakCredentials("nobody@example.com", "unused")),
        sessionStore = InMemorySessionStore(),
        rootDataDirectory = root,
        config = MutableStateFlow(PikPakEngineConfig()),
        parentCoroutineContext = Dispatchers.IO,
    ).also { it.magnetResolver = resolve }

    /** 一个种子里的文件, 按 `resolveMagnet` 的形状. */
    private fun resolved(path: String, length: Long, gcid: String = "GCID-$path") =
        io.github.nihildigit.pikpak.ResolvedFile(path = path, size = length, gcid = gcid)

    private fun torrent(name: String, vararg files: io.github.nihildigit.pikpak.ResolvedFile) =
        io.github.nihildigit.pikpak.MagnetResource(name = name, files = files.toList())

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
        // The bitmap records what is present, so a file truncated behind the
        // app's back would otherwise still read as complete and the player
        // would get zeros. The fetcher bounds the bitmap by the file's actual
        // length, which is sound because a real download preallocates to the
        // full length: anything shorter was cut by something outside the engine.
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

        // 云端要能连上: 两个文件的包在云端不可达时不该起会话, 那是另一个测试.
        val downloader = downloader(root) {
            torrent("Season Pack", resolved("01.mp4", 4096), resolved("02.mp4", 8192))
        }
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

        val downloader = downloader(root) {
            // 种子说这一集有 999_999 字节, 而盘上的那一份是另一个引擎从网页源取来的, 未必是同一
            // 份编码. 长度必须跟着盘上的字节走, 否则一个完整的文件会被判成不完整并从别的编码续传.
            torrent("Season Pack", resolved("01.mp4", 999_999), resolved("02.mp4", 8192))
        }

        val session = downloader.startDownload(downloader.fetchTorrent(magnet))
        val entries = session.getFiles().map { it as PikPakFileEntry }.sortedBy { it.pathInTorrent }

        assertEquals(listOf("01.mp4", "02.mp4"), entries.map { it.pathInTorrent })
        val first = entries.first()
        assertEquals(imported.size.toLong(), first.length, "the imported length describes the bytes on disk")
        assertEquals("GCID-01.mp4", first.meta.gcid, "the gcid is what the torrent knows and the disk does not")
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

        val downloader = downloader(root) {
            torrent("Season Pack", resolved("01.mp4", 8192), resolved("specials/SP01.mp4", 4096))
        }

        val session = downloader.startDownload(downloader.fetchTorrent(magnet))
        val entries = session.getFiles().map { it as PikPakFileEntry }.associateBy { it.pathInTorrent }

        assertEquals(setOf("01.mp4", "specials/SP01.mp4", "orphan.mp4"), entries.keys)
        val moved = entries.getValue("specials/SP01.mp4")
        assertEquals("GCID-specials/SP01.mp4", moved.meta.gcid)
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
    fun `a session built from imported files alone reports an incomplete listing`() = runBlocking {
        // 整季包只迁移过第 1 集时, 一个只含第 1 集的会话看起来就是个单文件种子, 而选择器最后一条
        // 兜底是"只有一个视频就选它", 于是每一集都播成第 1 集. 会话要如实说出清单不全, 选择器据此
        // 拒绝那条兜底并退到 BT.
        val temp = SystemPaths.createTempDirectory("pikpak-import-offline-pack")
        val root = temp.resolve("pikpak")
        val pack = "magnet:?xt=urn:btih:2f7e0a57e1af0e1cfd46258ba6c62938c21b6ee8&dn=Season%20Pack"
        PikPakSavedFiles.importCompletedFile(
            root, pack, temp.resolve("a.mp4").also { it.writeBytes(ByteArray(4096)) }, "01.mp4",
        )

        val downloader = downloader(root)
        val session = downloader.startDownload(downloader.fetchTorrent(pack))

        assertFalse(assertIs<PartialListing>(session).listingComplete)
        assertEquals(listOf("01.mp4"), session.getFiles().map { it.pathInTorrent })

        session.close()
        downloader.close()
    }

    @Test
    fun `a listing filled in from the cloud is complete`() = runBlocking {
        val temp = SystemPaths.createTempDirectory("pikpak-import-complete-listing")
        val root = temp.resolve("pikpak")
        PikPakSavedFiles.importCompletedFile(
            root, magnet, temp.resolve("a.mp4").also { it.writeBytes(ByteArray(4096)) }, "01.mp4",
        )

        val downloader = downloader(root) {
            torrent("Season Pack", resolved("01.mp4", 4096), resolved("02.mp4", 8192))
        }
        val session = downloader.startDownload(downloader.fetchTorrent(magnet))

        assertTrue(assertIs<PartialListing>(session).listingComplete)

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
