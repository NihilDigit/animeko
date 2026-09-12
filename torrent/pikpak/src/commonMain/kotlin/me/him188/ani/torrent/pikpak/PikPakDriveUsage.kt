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
 * The account's drive usage, for the settings page.
 *
 * The engine's own footprint is not broken out: a file object is dropped a
 * round trip after it is created, so the number would be zero every time it was
 * read, and a startup sweep collects whatever a crash left.
 *
 * The account figure is worth showing for a different reason: instant upload is
 * billed at full size, so a full drive makes `instantCreate` fail, which is a
 * refusal to play rather than a slowdown, and its error text means nothing to
 * the user.
 */
data class PikPakDriveUsage(
    val accountUsedBytes: Long,
    val accountLimitBytes: Long,
)

