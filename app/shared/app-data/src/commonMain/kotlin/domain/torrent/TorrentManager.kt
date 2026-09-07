/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import me.him188.ani.app.data.repository.torrent.peer.PeerFilterSubscriptionRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.torrent.peer.PeerFilterSettings
import me.him188.ani.app.platform.MeteredNetworkDetector
import me.him188.ani.datasources.api.topic.FileSize.Companion.kiloBytes
import me.him188.ani.utils.coroutines.childScope
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.CoroutineContext

/**
 * 管理本地 BT 下载器的实现. 根据配置选择不同的下载器.
 *
 * 目前支持的下载实现:
 * - anitorrent
 */
interface TorrentManager {
    val engines: List<TorrentEngine>
}

enum class TorrentEngineType(
    val id: String,
) {
    Anitorrent("anitorrent"),
    RemoteAnitorrent("anitorrent"),
    PikPak("pikpak")
}

/**
 * Default implementation of [TorrentManager], which manages parameters of the torrent engine.
 */
class DefaultTorrentManager(
    parentCoroutineContext: CoroutineContext,
    factory: TorrentEngineFactory,
    settingsRepository: SettingsRepository,
    client: ScopedHttpClient,
    subscriptionRepository: PeerFilterSubscriptionRepository,
    meteredNetworkDetector: MeteredNetworkDetector,
    baseSaveDir: () -> SystemPath,
    private val pikpak: TorrentEngine? = null,
) : TorrentManager {
    private val scope = parentCoroutineContext.childScope()
    private val logger = logger<DefaultTorrentManager>()

    private val anitorrent: TorrentEngine by lazy {
        factory.createTorrentEngine(
            scope.coroutineContext + CoroutineName("AnitorrentEngine"),
            combine(
                settingsRepository.anitorrentConfig.flow,
                meteredNetworkDetector.isMeteredNetworkFlow.distinctUntilChanged(),
            ) { config, isMetered ->
                val isUploadLimited = isMetered && config.limitUploadOnMeteredNetwork
                val limit = if (isUploadLimited) 10.kiloBytes else config.uploadRateLimit
                logger.debug { "Anitorrent upload rate limit: $limit/s" }
                config.copy(uploadRateLimit = limit)
            },
            client = client,
            combine(
                settingsRepository.torrentPeerConfig.flow,
                subscriptionRepository.rulesFlow,
            ) { config, rules ->
                PeerFilterSettings(
                    rules + config.createRuleWithEnabled(),
                    config.enableIdFilter && config.blockInvalidId,
                )
            },
            baseSaveDir().resolve(TorrentEngineType.Anitorrent.id),
        )
    }

    override val engines: List<TorrentEngine> by lazy {
        // 每个引擎都会有一个 storage, 它们共用 MediaCacheManager.LOCAL_FS_MEDIA_SOURCE_ID.
        // 这是安全的: MediaFetcher 遍历 MediaSourceInstance 列表, 不按 mediaSourceId 建 map,
        // 同 id 的多个实例都会参与 fetch. HttpMediaCacheStorage 早就与 torrent storage 同 id 并存.
        // 见 MediaFetcherSameSourceIdTest.
        //
        // PikPak 排在前面: 它启用时应当接管 BT 源, anitorrent 只作回退.
        listOfNotNull(pikpak, anitorrent)
    }

    companion object {
        fun create(
            parentCoroutineContext: CoroutineContext,
            settingsRepository: SettingsRepository,
            client: ScopedHttpClient,
            subscriptionRepository: PeerFilterSubscriptionRepository,
            meteredNetworkDetector: MeteredNetworkDetector,
            baseSaveDir: () -> SystemPath,
            torrentEngineFactory: TorrentEngineFactory = LocalAnitorrentEngineFactory,
            pikpak: TorrentEngine? = null,
        ): DefaultTorrentManager {
            return DefaultTorrentManager(
                parentCoroutineContext = parentCoroutineContext,
                factory = torrentEngineFactory,
                client = client,
                settingsRepository = settingsRepository,
                meteredNetworkDetector = meteredNetworkDetector,
                subscriptionRepository = subscriptionRepository,
                baseSaveDir = baseSaveDir,
                pikpak = pikpak,
            )
        }
    }
}
