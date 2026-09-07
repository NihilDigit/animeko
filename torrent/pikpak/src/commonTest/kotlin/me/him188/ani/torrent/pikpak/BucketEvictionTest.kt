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
import kotlin.test.Test
import kotlin.test.assertEquals

class BucketEvictionTest {
    private fun bucket(name: String, createdTime: String) = FileStat(
        kind = FileKind.FOLDER,
        id = "id-$name",
        name = name,
        createdTime = createdTime,
    )

    private val slot = listOf(
        bucket("A", "2026-09-01T00:00:00.000+0000"),
        bucket("B", "2026-09-02T00:00:00.000+0000"),
        bucket("C", "2026-09-03T00:00:00.000+0000"),
        bucket("D", "2026-09-04T00:00:00.000+0000"),
    )

    private fun evictedNames(
        current: String,
        queueLength: Int,
        protected: Set<String> = emptySet(),
    ) = pickBucketsToEvict(slot, current, queueLength, protected).map { it.name }.toSet()

    @Test
    fun `a single slot keeps only the source being started`() {
        assertEquals(setOf("A", "B", "C", "D"), evictedNames(current = "E", queueLength = 1))
    }

    @Test
    fun `a longer queue keeps the newest of the rest`() {
        assertEquals(setOf("A", "B"), evictedNames(current = "E", queueLength = 3))
    }

    @Test
    fun `the current source is never a candidate`() {
        assertEquals(setOf("A", "B", "D"), evictedNames(current = "C", queueLength = 1))
    }

    @Test
    fun `a bucket whose local cache is unfinished is spared and does not use up the quota`() {
        // C is protected, so the one keep slot goes to D rather than being
        // consumed by C. Without the exclusion, resuming C would have to
        // resubmit the magnet before it could fetch another piece.
        assertEquals(setOf("A", "B"), evictedNames(current = "E", queueLength = 2, protected = setOf("C")))
    }

    @Test
    fun `the unlimited sentinel disables eviction entirely`() {
        assertEquals(
            emptySet(),
            evictedNames(current = "E", queueLength = PikPakEngineConfig.SLOT_QUEUE_UNLIMITED),
        )
    }
}
