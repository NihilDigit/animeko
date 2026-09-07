/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.seeding

import me.him188.ani.utils.io.SystemPath

/**
 * iOS 上没有 anitorrent, 做种这条路走不到这里. 与其为一个不会被调用的函数引入 cinterop,
 * 不如让它一律失败, 调用方本来就有复制这条退路.
 */
actual fun createHardLink(source: SystemPath, target: SystemPath): Boolean = false
