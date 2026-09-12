/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.him188.ani.torrent.pikpak.PikPakDriveItem
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one-time upgrade notice: the old engine's folder still holds whole
 * downloads, and nothing needs them any more.
 *
 * Not a cleanup feature. The engine sweeps a different folder of its own, which
 * the user never sees. This says one thing instead — the drive space the old
 * version spent to make a replay instant can be had back — and it succeeds by
 * the user learning that, with keeping them a complete outcome.
 *
 * So it does not care what is in there or who put it there: something is, so
 * ask once, and never ask again.
 */
@Stable
class PikPakLegacyNoticeState(
    private val backgroundScope: CoroutineScope,
    private val fetchItems: suspend () -> List<PikPakDriveItem>,
    private val deleteItems: suspend (ids: List<String>) -> Unit,
) {
    /** 非空时显示弹窗. */
    var items: List<PikPakDriveItem> by mutableStateOf(emptyList())
        private set

    var deleting: Boolean by mutableStateOf(false)
        private set

    /** 删除失败的原因, 留在弹窗里. 失败时不关弹窗: 用户按了按钮, 得知道没成. */
    var error: String? by mutableStateOf(null)
        private set

    private var checked = false

    /**
     * 查一次云盘. 每个实例只查一次: 这是一次列目录请求, 而设置页会随着重组反复进来.
     *
     * 查询失败当作空的, 由 [fetchItems] 那一侧记日志: 这一问只为给用户一个收回空间的机会.
     */
    fun check() {
        if (checked) return
        checked = true
        backgroundScope.launch {
            items = fetchItems()
        }
    }

    /** 清空工作目录. [onAnswered] 在成功后调用, 用来记下"问过了". */
    fun deleteAll(onAnswered: () -> Unit) {
        if (deleting) return
        val targets = items.map { it.id }
        deleting = true
        error = null
        backgroundScope.launch {
            try {
                deleteItems(targets)
                items = emptyList()
                onAnswered()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // PikPakException 的 message 带真实 error_code, 比「失败」有用.
                error = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
            } finally {
                deleting = false
            }
        }
    }

    /** 用户选择保留. 不再问, 云盘上的东西原样不动. */
    fun keep(onAnswered: () -> Unit) {
        items = emptyList()
        error = null
        onAnswered()
    }
}
