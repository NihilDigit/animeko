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
 * A session whose file listing may be short of what the torrent actually holds.
 *
 * PikPak 的清单来自云端索引, 而索引请求可能失败. 那时留在手上的只有本地导入过的那几个文件: 整季包
 * 只迁移过第 1 集时, 会话看起来就是个单文件种子. 选择器最后一条兜底是"只有一个视频就选它", 于是
 * 第 2 集会选中第 1 集, 而那个文件本地完整、读得动, 结果是播放成功但播的是错的一集——比失败更糟,
 * 失败至少会退到 BT.
 *
 * 所以清单残缺这件事要由会话自己说出来, 由选择器决定拒绝哪条兜底. 会话不实现这个接口时按清单完整
 * 处理, 这也是本地 BT 引擎的实情.
 */
interface PartialListing {
    /** 这份清单列全了种子里的文件吗. */
    val listingComplete: Boolean
}
