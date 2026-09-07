/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.engines

import io.github.nihildigit.pikpak.SessionStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.domain.torrent.TorrentDownloaderInitializationException
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.domain.torrent.TorrentEngineType
import me.him188.ani.app.torrent.api.TorrentDownloader
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.torrent.pikpak.PikPakCredentials
import me.him188.ani.torrent.pikpak.PikPakEngineConfig
import me.him188.ani.torrent.pikpak.PikPakTorrentDownloader
import me.him188.ani.utils.coroutines.childScope
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.UnsafeScopedHttpClientApi
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 把 PikPak 云离线下载包装成 [TorrentEngine]. 磁链交给 PikPak 转成云端文件, 再按 piece 拉回本地,
 * 因此播放与缓存都能复用 torrent 链路 (`TorrentMediaResolver`、`TorrentMediaCacheEngine`).
 *
 * 不继承 `AbstractTorrentEngine`: 那个基类把 downloader 绑定在配置 flow 的首个值上, 而 PikPak 的
 * 画质、并发、凭据都要在运行中生效, downloader 自己订阅 flow. 该类在所有平台都进程内运行,
 * 安卓上不经过 `AniTorrentService`.
 */
class PikPakEngine(
    private val config: StateFlow<PikPakConfig>,
    private val credentials: StateFlow<PikPakCredentials?>,
    private val sessionStore: SessionStore,
    client: ScopedHttpClient,
    override val saveDir: SystemPath,
    parentCoroutineContext: CoroutineContext,
) : TorrentEngine {
    private val logger = logger<PikPakEngine>()
    private val scope = parentCoroutineContext.childScope()

    override val type: TorrentEngineType get() = TorrentEngineType.PikPak

    override val location: MediaSourceLocation get() = MediaSourceLocation.Local

    override val isSupported: Boolean
        get() = config.value.enabled && credentials.value != null

    // SDK 需要一个生命周期稳定的 HttpClient. 引擎是进程单例, 不归还不构成泄漏.
    @OptIn(UnsafeScopedHttpClientApi::class)
    private val httpClient = client.borrowForever().client

    private val engineConfig: StateFlow<PikPakEngineConfig> = config
        .map { it.toEngineConfig() }
        .stateIn(scope, SharingStarted.Eagerly, config.value.toEngineConfig())

    private val downloaderLock = Mutex()

    @Volatile
    private var downloader: PikPakTorrentDownloader? = null

    override suspend fun testConnection(): Boolean {
        if (!isSupported) return false
        return try {
            (getDownloader() as PikPakTorrentDownloader).testConnection()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "PikPak connection test failed" }
            false
        }
    }

    override suspend fun getDownloader(): TorrentDownloader {
        if (!isSupported) throw UnsupportedOperationException("PikPakEngine is not enabled")
        downloader?.let { return it }
        return downloaderLock.withLock {
            downloader?.let { return it }
            try {
                PikPakTorrentDownloader(
                    httpClient = httpClient,
                    credentials = credentials,
                    sessionStore = sessionStore,
                    rootDataDirectory = saveDir,
                    config = engineConfig,
                    parentCoroutineContext = scope.coroutineContext,
                ).also { created ->
                    downloader = created
                    scope.coroutineContext.job.invokeOnCompletion { created.close() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw TorrentDownloaderInitializationException(cause = e)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    // 预热只解析直链, 不取字节, 计费网络下也不必关.
    private fun PikPakConfig.toEngineConfig() = PikPakEngineConfig(
        variant = variant,
        // 一条签名直链最多接受 8 个连接, 弱网下单连接只有几百 KB/s, 8 条并行才凑得出码率, 所以并发数
        // 由引擎定死, 不是可调项.
        concurrency = 8,
        slotQueueLength = slotQueueLength,
    )
}
