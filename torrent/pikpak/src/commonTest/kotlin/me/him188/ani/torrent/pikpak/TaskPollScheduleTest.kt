/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TaskPollScheduleTest {
    private val steady = 2.seconds

    @Test
    fun `ramps 1s 1s then holds at the steady state`() {
        assertEquals(1.seconds, taskPollDelay(0, steady))
        assertEquals(1.seconds, taskPollDelay(1, steady))
        assertEquals(2.seconds, taskPollDelay(2, steady))
        assertEquals(2.seconds, taskPollDelay(9, steady))
    }

    @Test
    fun `never sleeps longer than the configured ceiling`() {
        val tight = 300.milliseconds
        repeat(6) { i ->
            assertTrue(taskPollDelay(i, tight) <= tight, "poll $i exceeded the ceiling")
        }
    }
}
