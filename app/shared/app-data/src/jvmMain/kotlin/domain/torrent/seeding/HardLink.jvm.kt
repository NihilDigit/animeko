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
import me.him188.ani.utils.io.absolutePath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

actual fun createHardLink(source: SystemPath, target: SystemPath): Boolean = try {
    Files.createLink(Paths.get(target.absolutePath), Paths.get(source.absolutePath))
    true
} catch (e: IOException) {
    // 跨卷是 FileSystemException, exFAT 一类不支持硬链接是 UnsupportedOperationException,
    // 两者都是 IOException 的子类, 也都只说明这条路走不通.
    false
} catch (e: UnsupportedOperationException) {
    false
}
