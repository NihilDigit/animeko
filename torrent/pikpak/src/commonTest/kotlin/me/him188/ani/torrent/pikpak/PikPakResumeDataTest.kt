/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeBytes
import me.him188.ani.utils.io.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PikPakResumeDataTest {
    private fun meta(vararg files: PikPakFileMeta) = PikPakTorrentMeta(
        uri = "magnet:?xt=urn:btih:157E0A57E1AF0E1CFD46258BA6C62938C21B6EE8",
        sourceKey = "157E0A57E1AF0E1CFD46258BA6C62938C21B6EE8",
        name = "pack",
        bucketId = "bucket-1",
        files = files.toList(),
    )

    private fun file(path: String, fileId: String, mediaId: String? = null, length: Long = 5L * 1024 * 1024) =
        PikPakFileMeta(
            index = 0,
            pathInTorrent = path,
            fileId = fileId,
            mediaId = mediaId,
            variantLabel = if (mediaId == null) "Original" else "1080P",
            length = length,
        )

    @Test
    fun `meta survives a round trip`() {
        val dir = SystemPaths.createTempDirectory("pikpak-meta")
        val resume = PikPakResumeData(dir)
        val original = meta(file("01.mkv", "id-1"), file("02.mkv", "id-2", mediaId = "media-9"))

        resume.write(original)

        // write() stamps the version; everything else must come back as written.
        assertEquals(original.copy(version = PikPakTorrentMeta.CURRENT_VERSION), resume.read())
    }

    @Test
    fun `a corrupt meta reads as absent so the caller rebuilds from the cloud`() {
        val dir = SystemPaths.createTempDirectory("pikpak-meta-corrupt")
        dir.resolve(PikPakTorrentMeta.FILE_NAME).writeText("{ this is not json")

        assertNull(PikPakResumeData(dir).read())
    }

    @Test
    fun `a meta from an older version reads as absent`() {
        // Version 1 carried a piece bitmap and a piece size. Its file list is
        // not wrong, but nothing left can interpret what it claimed was
        // present, and re-indexing from the cloud is one listing.
        val dir = SystemPaths.createTempDirectory("pikpak-meta-v1")
        dir.resolve(PikPakTorrentMeta.FILE_NAME).writeText(
            """
            {
              "version": 1,
              "uri": "magnet:?xt=urn:btih:157E0A57E1AF0E1CFD46258BA6C62938C21B6EE8",
              "sourceKey": "157E0A57E1AF0E1CFD46258BA6C62938C21B6EE8",
              "name": "pack",
              "pieceSize": 524288,
              "files": []
            }
            """.trimIndent(),
        )

        assertNull(PikPakResumeData(dir).read())
    }

    @Test
    fun `a meta without a version field reads as absent`() {
        // Every file the engine wrote before 2026-09-06 looks like this: the
        // field was never encoded, so on read it took the constructor default,
        // which tracked CURRENT_VERSION and let every old file through.
        val dir = SystemPaths.createTempDirectory("pikpak-meta-unversioned")
        dir.resolve(PikPakTorrentMeta.FILE_NAME).writeText(
            """
            {
              "uri": "magnet:?xt=urn:btih:157E0A57E1AF0E1CFD46258BA6C62938C21B6EE8",
              "sourceKey": "157E0A57E1AF0E1CFD46258BA6C62938C21B6EE8",
              "name": "pack",
              "files": []
            }
            """.trimIndent(),
        )

        assertNull(PikPakResumeData(dir).read())
    }

    @Test
    fun `updateFileId rewrites only the named path`() {
        val dir = SystemPaths.createTempDirectory("pikpak-meta-relocate")
        val resume = PikPakResumeData(dir)
        resume.write(meta(file("01.mkv", "old-1"), file("02.mkv", "old-2")))

        resume.updateFileId("02.mkv", newFileId = "new-2", newBucketId = "bucket-2")

        val reread = resume.read()!!
        assertEquals("old-1", reread.files.single { it.pathInTorrent == "01.mkv" }.fileId)
        assertEquals("new-2", reread.files.single { it.pathInTorrent == "02.mkv" }.fileId)
        assertEquals("bucket-2", reread.bucketId)
    }

    @Test
    fun `a missing media file is normal but an over-long one forces a re-index`() {
        // Playback writes nothing, so most entries of a restored session have
        // no file at all. Only a file longer than its recorded length is a
        // contradiction: it means the length describes another variant.
        val dir = SystemPaths.createTempDirectory("pikpak-consistency")
        val meta = meta(file("01.mkv", "id-1", length = 4096))

        assertTrue(filesConsistent(dir, meta), "an absent file is not an inconsistency")

        dir.resolve("01.mkv").writeBytes(ByteArray(1024))
        assertTrue(filesConsistent(dir, meta), "a partial cache download is not an inconsistency")

        dir.resolve("01.mkv").writeBytes(ByteArray(8192))
        assertFalse(filesConsistent(dir, meta))
    }
}
