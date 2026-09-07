/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A season pack with a `specials/` folder is the layout that broke: the file in
 * it never reached the index, so the app could not find the special episode.
 */
class BucketWalkTest {
    private fun folder(id: String, name: String) = FileStat(kind = FileKind.FOLDER, id = id, name = name)

    private fun file(id: String, name: String, size: Long = 1024) =
        FileStat(kind = FileKind.FILE, id = id, name = name, size = size.toString())

    private fun walk(tree: Map<String, List<FileStat>>) = runBlocking {
        walkBucket("bucket") { tree[it] ?: emptyList() }
    }

    @Test
    fun `a file in a subfolder keeps the subfolder in its path`() {
        val entries = walk(
            mapOf(
                "bucket" to listOf(folder("pack", "Bocchi the Rock S01 [KitaujiSub]")),
                "pack" to listOf(folder("sp", "specials"), file("e01", "S01E01.mkv")),
                "sp" to listOf(file("s01", "S00E01.mkv")),
            ),
        )

        assertEquals(listOf("specials/S00E01.mkv", "S01E01.mkv"), entries.map { it.path })
        assertEquals(listOf("S00E01.mkv", "S01E01.mkv"), entries.map { it.name })
    }

    @Test
    fun `a top-level file keeps a bare name`() {
        val entries = walk(mapOf("bucket" to listOf(file("only", "Bocchi.mkv"))))

        assertEquals(listOf("Bocchi.mkv"), entries.map { it.path })
    }

    @Test
    fun `the pack folder is stripped but deeper folders are not`() {
        val entries = walk(
            mapOf(
                "bucket" to listOf(folder("pack", "Pack")),
                "pack" to listOf(folder("cd", "CDs")),
                "cd" to listOf(folder("d1", "Disc 1")),
                "d1" to listOf(file("t1", "01.flac")),
            ),
        )

        assertEquals(listOf("CDs/Disc 1/01.flac"), entries.map { it.path })
    }

    @Test
    fun `a listing that loops back on itself stops instead of spinning`() {
        val entries = walk(
            mapOf(
                "bucket" to listOf(folder("pack", "Pack")),
                "pack" to listOf(folder("pack", "Pack"), file("f", "01.mkv")),
            ),
        )

        assertEquals(MAX_BUCKET_DEPTH, entries.size, "one file per level walked, and no more")
    }
}
