/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

// Arch Linux ISO — widely seeded, PikPak caches it, resolves in seconds.
// Shared by the live tests so none of them invents a magnet of its own.
internal const val DEFAULT_MAGNET =
    "magnet:?xt=urn:btih:157e0a57e1af0e1cfd46258ba6c62938c21b6ee8" +
            "&dn=archlinux-2026.04.01-x86_64.iso"
