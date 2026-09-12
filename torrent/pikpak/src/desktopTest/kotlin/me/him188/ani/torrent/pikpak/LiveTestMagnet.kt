/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

// 一个 PikPak 确实索引过的视频合集, 用来验 canServe 的肯定分支.
//
// DEFAULT_MAGNET 是 ISO, 而 canServe 只认视频条目, 所以它答 false——那条断言验不到"答 true"
// 这一半, 而 canServe 一旦错答 false, PikPak 就静默地永远不参与, 是最难被发现的那类退化.
//
// 索引可能随时消失, 所以用它的测试要在 resolveMagnet 返回 null 时跳过而不是失败.
internal const val INDEXED_VIDEO_MAGNET =
    "magnet:?xt=urn:btih:7af771b417c55ebc86caa0cb82cdb7faac90c04c"

// Arch Linux ISO — widely seeded, PikPak caches it, resolves in seconds.
// Shared by the live tests so none of them invents a magnet of its own.
internal const val DEFAULT_MAGNET =
    "magnet:?xt=urn:btih:157e0a57e1af0e1cfd46258ba6c62938c21b6ee8" +
            "&dn=archlinux-2026.04.01-x86_64.iso"
