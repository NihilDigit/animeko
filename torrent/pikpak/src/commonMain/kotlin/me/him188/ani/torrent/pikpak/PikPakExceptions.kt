/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

/** No account configured, or incomplete credentials. The engine can do nothing in this state. */
class PikPakNotConfiguredException(message: String) : Exception(message)

/**
 * PikPak's content index has no record of this torrent, so instant upload is
 * closed to it.
 *
 * A type of its own rather than an IOException: the caller falls back to
 * anitorrent on this, and a network or auth failure must not take the same
 * branch — retrying those is worth something, retrying this is not.
 */
class PikPakNotIndexedException(
    val uri: String,
    message: String = "PikPak has no record of $uri",
) : Exception(message)
