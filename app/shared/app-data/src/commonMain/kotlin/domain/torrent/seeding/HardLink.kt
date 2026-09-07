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
 * 在 [target] 建立 [source] 的硬链接.
 *
 * @return 成功时 `true`. 跨卷、目标文件系统不支持硬链接、或平台没有这个能力时返回 `false`,
 * 由调用方决定是复制还是放弃. 不抛异常: 这三种情况都是「换条路」而不是错误.
 */
expect fun createHardLink(source: SystemPath, target: SystemPath): Boolean
