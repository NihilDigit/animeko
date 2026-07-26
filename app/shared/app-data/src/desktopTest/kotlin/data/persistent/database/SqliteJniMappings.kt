/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database

import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * Distinct backing files of every `sqliteJni` mapping in this process, as seen by the kernel.
 * A `/proc/self/maps` line ends with the mapped path, e.g.
 * `7f..-7f.. r-xp 00000000 00:1b 123  /home/u/.cache/ani/ani-bundled-sqliteJni.so`.
 *
 * Linux-only; callers guard on the platform.
 */
internal fun sqliteJniMappings(): Set<String> =
    Path.of("/proc/self/maps").readLines()
        .mapNotNull { line ->
            line.substringAfter(" /", missingDelimiterValue = "")
                .let { if (it.isEmpty()) null else "/$it" }
        }
        .filter { it.substringAfterLast('/').contains("sqliteJni", ignoreCase = true) }
        .toSet()
