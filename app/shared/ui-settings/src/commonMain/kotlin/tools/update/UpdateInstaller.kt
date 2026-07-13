/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tools.update

import me.him188.ani.app.platform.ContextMP
import me.him188.ani.utils.io.SystemPath

/**
 * 安装包安装器
 *
 * - 安卓：弹出系统 APK 安装界面
 * - Windows：使用脚本自动覆盖安装 （一键）
 * - macOS：打开 dmg 让系统去安装，需要用户手动拖拽一下
 */
interface UpdateInstaller {
    /** 是否应在后台线程等待安装器完成. */
    val installInBackground: Boolean get() = false

    /**
     * 将版本 API 返回的安装包地址转换为安装前需要下载的地址.
     *
     * 默认直接下载安装包. Linux AppImage 只需预取很小的 zsync 元数据,
     * 实际差分下载由外部更新器在安装阶段完成.
     */
    fun getInstallerDownloadUrls(packageUrls: List<String>): List<String> = packageUrls

    /**
     * 如果 [install] 可能返回 [InstallationResult.Failed], 则需实现
     */
    suspend fun openForManualInstallation(file: SystemPath, context: ContextMP): Boolean = false

    fun install(file: SystemPath, context: ContextMP): InstallationResult

    /**
     * 使用版本 API 返回的原始安装包地址执行安装.
     *
     * 默认平台仍安装已经下载到 [file] 的完整安装包.
     */
    suspend fun install(
        file: SystemPath,
        packageUrls: List<String>,
        context: ContextMP,
    ): InstallationResult = install(file, context)
}

sealed class InstallationResult {
    data object Succeed : InstallationResult() // 实际上可能不会返回, 因为安装成功会重启

    /**
     * 安装失败, 附带失败原因. UI 会展示这个失败原因
     */
    data class Failed(
        val reason: InstallationFailureReason,
        val message: String? = null,
    ) : InstallationResult()
}

enum class InstallationFailureReason {
    /**
     * 未支持的安装目录结构. 例如 Windows 上未找到 `Ani.exe`
     */
    UNSUPPORTED_FILE_STRUCTURE,

    FAILED_TO_MOUNT_DMG,
    FAILED_TO_COPY,
}
