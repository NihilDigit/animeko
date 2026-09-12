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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.torrent.pikpak.PikPakDriveUsage
import kotlin.coroutines.cancellation.CancellationException

@Stable
sealed interface PikPakDriveUsagePresentation {
    /**
     * 还没查过. 查询是用户按下去的动作, 不在进入设置页时自动发起: 它要登录、过 captcha、请求云盘,
     * 而进设置页的人多数不是来看这个数字的.
     */
    data object Idle : PikPakDriveUsagePresentation

    data object Loading : PikPakDriveUsagePresentation

    /**
     * 引擎未启用或凭据不足, 两者在设置页里无法区分, 也不需要区分: 用户接下来要做的事都是填账号.
     */
    data object SignedOut : PikPakDriveUsagePresentation

    data class Failed(val message: String) : PikPakDriveUsagePresentation

    data class Loaded(val usage: PikPakDriveUsage) : PikPakDriveUsagePresentation {
        val used: FileSize get() = usage.accountUsedBytes.bytes
        val limit: FileSize get() = usage.accountLimitBytes.bytes
        val free: FileSize get() = (usage.accountLimitBytes - usage.accountUsedBytes).coerceAtLeast(0L).bytes

        val freeSpaceLow: Boolean get() = free.inBytes < LOW_FREE_SPACE_BYTES
    }

    companion object {
        /**
         * 秒传按整包尺寸计费, 一个季度包就能吃掉二十多 GB, 所以余量低于一个季度包的量级时就该提示.
         */
        const val LOW_FREE_SPACE_BYTES: Long = 50L * 1024 * 1024 * 1024
    }
}

/**
 * PikPak 账号网盘用量的读取状态.
 *
 * 这是设置页里 PikPak 的连接测试: 数字能读出来就说明登录、captcha、云盘三层都通了, 而它比一个
 * 布尔值多回答一件事——还能不能再建文件对象. 秒传按全尺寸计费, 云盘写满后播放会失败.
 *
 * 只在用户按下时请求一次, 不自动发起也不轮询.
 */
@Stable
class PikPakDriveUsageState(
    private val backgroundScope: CoroutineScope,
    private val fetchUsage: suspend () -> PikPakDriveUsage?,
) {
    var presentation: PikPakDriveUsagePresentation by mutableStateOf(PikPakDriveUsagePresentation.Idle)
        private set

    private var loadJob: Job? = null

    fun check() {
        loadJob?.cancel()
        loadJob = backgroundScope.launch {
            presentation = PikPakDriveUsagePresentation.Loading
            presentation = load()
        }
    }

    private suspend fun load(): PikPakDriveUsagePresentation = try {
        val usage = fetchUsage()
        if (usage == null) PikPakDriveUsagePresentation.SignedOut
        else PikPakDriveUsagePresentation.Loaded(usage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        PikPakDriveUsagePresentation.Failed(e.describe())
    }
}

// PikPakException 的 message 里带真实 error_code, 比「失败」有用; 没有 message 的异常退回类名.
private fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: toString()
