/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

/**
 * One item of the legacy folder, for the one-time "you can reclaim this" notice.
 *
 * The notice and the engine's own sweep are separate things. The sweep is
 * hygiene over [PikPakDriveIndex.TEMP_FOLDER], which the user never sees and
 * never has to think about. The notice speaks about
 * [PikPakDriveIndex.LEGACY_FOLDER]: whole downloads the offline-download engine
 * parked in the drive to make a replay instant, which nothing needs any more.
 * It succeeds by the user learning that, and the user choosing to keep them is
 * a complete outcome, so it carries no cleanup duty at all.
 *
 * Which is why nothing here distinguishes a file from a folder or one creator
 * from another: something is in there, so ask once, and never ask again.
 */
data class PikPakDriveItem(
    val id: String,
    val name: String,
)
