/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import me.him188.ani.app.data.network.AniApiProvider
import me.him188.ani.app.data.network.AniCommentReportService
import me.him188.ani.app.data.network.AniEpisodeCommentService
import me.him188.ani.app.data.network.AniPersonCommentService
import me.him188.ani.app.data.network.AniSubjectRelationIndexService
import me.him188.ani.app.data.network.AniSubjectSearchService
import me.him188.ani.app.data.network.AnimeScheduleService
import me.him188.ani.app.data.network.AutoSkipRepository
import me.him188.ani.app.data.network.BangumiBangumiCommentServiceImpl
import me.him188.ani.app.data.network.BangumiCommentService
import me.him188.ani.app.data.network.BangumiRelatedPeopleService
import me.him188.ani.app.data.network.DefaultWatchTogetherApiService
import me.him188.ani.app.data.network.EpisodeService
import me.him188.ani.app.data.network.EpisodeServiceImpl
import me.him188.ani.app.data.network.RecommendationRepository
import me.him188.ani.app.data.network.RemoteSubjectService
import me.him188.ani.app.data.network.SubjectService
import me.him188.ani.app.data.network.TrendsRepository
import me.him188.ani.app.data.network.WatchTogetherApiService
import me.him188.ani.app.data.persistent.dataStores
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.MIGRATION_19_20
import me.him188.ani.app.data.persistent.database.createDatabaseBuilder
import me.him188.ani.app.data.repository.episode.AnimeScheduleRepository
import me.him188.ani.app.data.repository.episode.BangumiCommentRepository
import me.him188.ani.app.data.repository.episode.EpisodeCollectionRepository
import me.him188.ani.app.data.repository.episode.EpisodeCommentRepository
import me.him188.ani.app.data.repository.episode.EpisodeProgressRepository
import me.him188.ani.app.data.repository.media.EpisodePreferencesRepository
import me.him188.ani.app.data.repository.media.EpisodePreferencesRepositoryImpl
import me.him188.ani.app.data.repository.media.MediaSourceInstanceRepository
import me.him188.ani.app.data.repository.media.MediaSourceInstanceRepositoryImpl
import me.him188.ani.app.data.repository.media.MediaSourceSaves
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.data.repository.media.MikanIndexCacheRepository
import me.him188.ani.app.data.repository.media.MikanIndexCacheRepositoryImpl
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepository
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepositoryImpl
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepositoryImpl
import me.him188.ani.app.data.repository.player.EpisodeScreenshotRepository
import me.him188.ani.app.data.repository.player.PlaybackHistorySyncer
import me.him188.ani.app.data.repository.player.WhatslinkEpisodeScreenshotRepository
import me.him188.ani.app.data.repository.person.PersonCommentRepository
import me.him188.ani.app.data.repository.person.PersonDetailsRepository
import me.him188.ani.app.data.repository.repositoryModules
import me.him188.ani.app.data.repository.subject.DefaultSubjectRelationsRepository
import me.him188.ani.app.data.repository.subject.FollowedSubjectsRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepositoryImpl
import me.him188.ani.app.data.repository.subject.SubjectSearchCompletionRepository
import me.him188.ani.app.data.repository.subject.SubjectRelationsRepository
import me.him188.ani.app.data.repository.subject.SubjectSearchHistoryRepository
import me.him188.ani.app.data.repository.subject.SubjectSearchRepository
import me.him188.ani.app.data.repository.torrent.peer.PeerFilterSubscriptionRepository
import me.him188.ani.app.data.repository.user.AccessTokenSession
import me.him188.ani.app.data.repository.user.PreferencesRepositoryImpl
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.torrent.TorrentEngineType
import me.him188.ani.app.domain.torrent.engines.PikPakEngine
import me.him188.ani.torrent.pikpak.PikPakCredentials
import me.him188.ani.torrent.pikpak.PikPakSessionStoreAdapter
import me.him188.ani.utils.io.inSystem
import me.him188.ani.app.data.repository.user.TokenRepository
import me.him188.ani.app.domain.danmaku.DanmakuRepository
import me.him188.ani.app.domain.foundation.ConvertSendCountExceedExceptionFeature
import me.him188.ani.app.domain.foundation.ConvertSendCountExceedExceptionFeatureHandler
import me.him188.ani.app.domain.foundation.CookieJarFeatureHandler
import me.him188.ani.app.domain.foundation.WebSourceIdentityFeatureHandler
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider.HoldingInstanceMatrix
import me.him188.ani.app.domain.foundation.DefaultVersionExpiryService
import me.him188.ani.app.domain.foundation.DistributionChannelFeatureHandler
import me.him188.ani.app.domain.foundation.GlobalHttpEventBus
import me.him188.ani.app.domain.foundation.GlobalHttpEvents
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.ServerListFeature
import me.him188.ani.app.domain.foundation.ServerListFeatureConfig
import me.him188.ani.app.domain.foundation.ServerListFeatureHandler
import me.him188.ani.app.domain.foundation.SseFeatureHandler
import me.him188.ani.app.domain.foundation.UseAniTokenFeatureHandler
import me.him188.ani.app.domain.foundation.UserAgentFeature
import me.him188.ani.app.domain.foundation.UserAgentFeatureHandler
import me.him188.ani.app.domain.foundation.VersionExpiryFeatureHandler
import me.him188.ani.app.domain.foundation.VersionExpiryService
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.foundation.withValue
import me.him188.ani.app.domain.mediasource.web.PageEvaluator
import me.him188.ani.app.domain.mediasource.web.captcha.BrowserImageCaptchaSolver
import me.him188.ani.app.domain.mediasource.web.captcha.CaptchaBrowserFactory
import me.him188.ani.app.domain.mediasource.web.captcha.GirigiriSearchRoute
import me.him188.ani.app.domain.mediasource.web.captcha.ImageCaptchaRecognizer
import me.him188.ani.app.domain.mediasource.web.captcha.MacCmsImageCaptchaSolver
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceCookieJar
import me.him188.ani.app.domain.mediasource.web.captcha.WebSourceIdentityRegistry
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.cache.MediaCacheManagerImpl
import me.him188.ani.app.domain.media.cache.PikPakWebM3uCacheMigration
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.KtorPersistentHttpDownloader
import me.him188.ani.app.domain.media.cache.engine.AlwaysUseTorrentEngineAccess
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.storage.HttpMediaCacheStorage
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.media.cache.storage.TorrentMediaCacheStorage
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.fetch.MediaSourceManagerImpl
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscriptionRequesterImpl
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscriptionUpdater
import me.him188.ani.app.domain.session.AniSessionRefresher
import me.him188.ani.app.domain.session.SessionManager
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.settings.ProxyProvider
import me.him188.ani.app.domain.settings.SettingsBasedProxyProvider
import me.him188.ani.app.domain.torrent.TorrentManager
import me.him188.ani.app.domain.torrent.seeding.PikPakReseeder
import me.him188.ani.app.domain.update.UpdateManager
import me.him188.ani.app.domain.watchtogether.LocalPlaybackBridge
import me.him188.ani.app.domain.watchtogether.PlaybackAutomationGate
import me.him188.ani.app.domain.watchtogether.WatchTogetherManager
import me.him188.ani.app.domain.usecase.useCaseModules
import me.him188.ani.app.ui.subject.details.state.DefaultSubjectDetailsStateFactory
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsStateFactory
import me.him188.ani.datasources.bangumi.BangumiClient
import me.him188.ani.datasources.bangumi.BangumiClientImpl
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.coroutines.childScope
import me.him188.ani.utils.coroutines.childScopeContext
import me.him188.ani.utils.httpdownloader.HttpDownloader
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.core.KoinApplication
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Duration.Companion.minutes

private val Scope.client get() = get<BangumiClient>()
private val Scope.database get() = get<AniDatabase>()
private val Scope.settingsRepository get() = get<SettingsRepository>()
private val Scope.aniApiProvider get() = get<AniApiProvider>()

fun KoinApplication.getCommonKoinModule(getContext: () -> Context, coroutineScope: CoroutineScope) =
    listOf(useCaseModules(), repositoryModules(getContext().dataStores), otherModules(getContext, coroutineScope))

private fun KoinApplication.otherModules(getContext: () -> Context, coroutineScope: CoroutineScope) = module {
    // Repositories
    single<ProxyProvider> { SettingsBasedProxyProvider(get(), coroutineScope) }
    single<SessionManager> {
        SessionManager(
            tokenRepository = get(),
            coroutineScope = coroutineScope,
            refreshSession = AniSessionRefresher { aniApiProvider.userAuthApi },
        )
    }
    single<SessionStateProvider> {
        get<SessionManager>().stateProvider
    }
    single<ServerSelector> {
        ServerSelector(
            settingsRepository.danmakuSettings.flow.map { it.useGlobal },
            proxyProvider = get(),
            coroutineScope,
        )
    }
    single<HttpClientProvider> {
        val sessionManager by inject<SessionManager>()
        DefaultHttpClientProvider(
            get(), coroutineScope,
            featureHandlers = listOf(
                UserAgentFeatureHandler,
                UseAniTokenFeatureHandler(
                    sessionManager.sessionFlow.map {
                        (it as? AccessTokenSession)?.tokens?.aniAccessToken
                    },
                    onRefresh = { null },
                ),
                ServerListFeatureHandler(
                    get<ServerSelector>().flow,
                ),
                DistributionChannelFeatureHandler { currentAniBuildConfig.distroChannel },
                ConvertSendCountExceedExceptionFeatureHandler,
                VersionExpiryFeatureHandler, // handle 426 Upgrade Required -> show blocking dialog
                SseFeatureHandler,
                CookieJarFeatureHandler, // web 数据源统一 cookie jar (构造时注入)
                WebSourceIdentityFeatureHandler, // web 数据源 per-host UA 对齐
            ),
        )
    }
    // Web 数据源验证码处理 (docs/dev/media/web-captcha.md)
    single<WebSourceCookieJar> { WebSourceCookieJar() }
    single<WebSourceIdentityRegistry> { WebSourceIdentityRegistry() }
    single<WebSessionManager> {
        val browserFactory = get<CaptchaBrowserFactory>()
        val evaluator = PageEvaluator()
        val recognizer = get<ImageCaptchaRecognizer>()
        val settingsRepository = get<SettingsRepository>()
        WebSessionManager(
            browserFactory = browserFactory,
            evaluator = evaluator,
            cookieJar = get(),
            identityRegistry = get(),
            client = get<HttpClientProvider>().get(
                userAgent = ScopedHttpClientUserAgent.BROWSER,
                cookieJar = get(),
                identityRegistry = get(),
            ),
            backgroundScope = coroutineScope,
            solvers = listOf(
                MacCmsImageCaptchaSolver(recognizer),
                BrowserImageCaptchaSolver(recognizer),
            ),
            solverEnabled = {
                settingsRepository.mediaSelectorSettings.flow.first().enableImageCaptchaAutoSolve
            },
            searchRoutes = listOf(GirigiriSearchRoute(evaluator)),
            maxSessions = browserFactory.recommendedMaxSessions,
        )
    }
    single<VersionExpiryService> { DefaultVersionExpiryService() }
    // Wire Global HTTP event bus to VersionExpiryService
    run {
        val service = koin.inject<VersionExpiryService>()
        GlobalHttpEventBus = object : GlobalHttpEvents {
            override fun onVersionExpired(latestVersion: String?) {
                service.value.onVersionExpired(latestVersion)
            }
        }
    }
    single<AniApiProvider> { AniApiProvider(get<HttpClientProvider>().get(useAniToken = true)) }
    single<WatchTogetherApiService> {
        DefaultWatchTogetherApiService(
            provider = get(),
            eventsClient = get<HttpClientProvider>().get(useAniToken = true, useSse = true),
        )
    }
    single<LocalPlaybackBridge> { LocalPlaybackBridge() }
    single<PlaybackAutomationGate> { PlaybackAutomationGate() }
    single(createdAtStart = true) {
        WatchTogetherManager(
            scope = coroutineScope,
            api = get(),
            settings = get<SettingsRepository>().watchTogetherSettings,
            sessionStateProvider = get(),
            playbackBridge = get(),
            automationGate = get(),
        ).also { it.start() }
    }
    single<TokenRepository> { TokenRepository(getContext().dataStores.tokenStore) }
    single<EpisodePreferencesRepository> {
        EpisodePreferencesRepositoryImpl(
            getContext().dataStores.preferredAllianceStore,
            database.preferredWebMediaSourceDao(),
        )
    }
    single<BangumiClient> {
        BangumiClientImpl(
            get<HttpClientProvider>().get(
                userAgent = ScopedHttpClientUserAgent.ANI,
            ),
        )
    }

    single<SubjectCollectionRepository> {
        SubjectCollectionRepositoryImpl(
            subjectService = get(),
            subjectCollectionDao = database.subjectCollection(),
//            characterDao = database.character(),
//            characterActorDao = database.characterActor(),
//            personDao = database.person(),
//            subjectCharacterRelationDao = database.subjectCharacterRelation(),
//            subjectPersonRelationDao = database.subjectPersonRelation(),
            subjectRelationsDao = database.subjectRelations(),
            episodeCollectionRepository = get(),
            animeScheduleRepository = get(),
            episodeService = get(),
            episodeCollectionDao = database.episodeCollection(),
            sessionManager = get(),
            nsfwModeSettingsFlow = settingsRepository.uiSettings.flow.map { it.searchSettings.nsfwMode },
            getEpisodeTypeFiltersUseCase = get(),
        )
    }
    single<FollowedSubjectsRepository> {
        FollowedSubjectsRepository(
            subjectCollectionRepository = get(),
            animeScheduleRepository = get(),
            episodeCollectionRepository = get(),
            settingsRepository = get(),
            sessionManager = get(),
        )
    }
    single<AniSubjectSearchService> {
        AniSubjectSearchService(
            subjectApi = aniApiProvider.subjectApi,
        )
    }
    single<SubjectSearchRepository> {
        SubjectSearchRepository(
            aniSubjectSearchService = get(),
            subjectCollectionRepository = get(),
        )
    }
    single<SubjectSearchCompletionRepository> {
        SubjectSearchCompletionRepository(
            aniSubjectSearchService = get(),
            subjectCollectionRepository = get(),
            settingsRepository = get(),
        )
    }
    single<SubjectSearchHistoryRepository> {
        SubjectSearchHistoryRepository(database.searchHistory(), database.searchTag())
    }
    single<SubjectRelationsRepository> {
        DefaultSubjectRelationsRepository(
            database.subjectCollection(),
            database.subjectRelations(),
            subjectService = get(),
            subjectCollectionRepository = get(),
            aniSubjectRelationIndexService = get(),
        )
    }

    // Data layer network services
    single<SubjectService> {
        RemoteSubjectService(
            aniApiProvider.subjectApi,
            sessionManager = get(),
        )
    }
    single<EpisodeService> { EpisodeServiceImpl(aniApiProvider.subjectApi) }

    single<BangumiRelatedPeopleService> { BangumiRelatedPeopleService(get<AniApiProvider>().subjectApi) }
    single<PersonDetailsRepository> {
        PersonDetailsRepository(
            personsApi = aniApiProvider.personsApi,
            charactersApi = aniApiProvider.charactersApi,
        )
    }
    single<AnimeScheduleRepository> { AnimeScheduleRepository(get()) }
    single<BangumiCommentRepository> {
        BangumiCommentRepository(
            get(),
            database.subjectReviews(),
        )
    }
    single<EpisodeCollectionRepository> {
        EpisodeCollectionRepository(
            subjectDao = database.subjectCollection(),
            episodeCollectionDao = database.episodeCollection(),
            episodeService = get(),
            animeScheduleRepository = get(),
            subjectCollectionRepository = inject(),
            getEpisodeTypeFiltersUseCase = get(),
        )
    }
    single<EpisodeProgressRepository> {
        EpisodeProgressRepository(
            episodeCollectionRepository = get(),
            cacheManager = get(),
        )
    }
    single<EpisodeScreenshotRepository> { WhatslinkEpisodeScreenshotRepository() }
    single<BangumiCommentService> { BangumiBangumiCommentServiceImpl(get<AniApiProvider>().subjectApi) }
    single<AniEpisodeCommentService> { AniEpisodeCommentService(get<AniApiProvider>().episodesApi) }
    single<AniCommentReportService> { AniCommentReportService(get<AniApiProvider>().commentsApi) }
    single<EpisodeCommentRepository> { EpisodeCommentRepository(aniCommentService = get()) }
    single<AniPersonCommentService> {
        AniPersonCommentService(
            personsApi = get<AniApiProvider>().personsApi,
            charactersApi = get<AniApiProvider>().charactersApi,
        )
    }
    single<PersonCommentRepository> { PersonCommentRepository(aniCommentService = get()) }
    single<MediaSourceInstanceRepository> {
        MediaSourceInstanceRepositoryImpl(getContext().dataStores.mediaSourceSaveStore)
    }
    single<MediaSourceSubscriptionRepository> {
        MediaSourceSubscriptionRepository(getContext().dataStores.mediaSourceSubscriptionStore)
    }
    single<EpisodePlayHistoryRepository> {
        EpisodePlayHistoryRepositoryImpl(
            dataStore = getContext().dataStores.episodeHistoryStore,
            playbackHistoryDao = database.playbackHistoryDao(),
            onDirtyChanged = { get<PlaybackHistorySyncer>().requestSync() },
        )
    }
    single(createdAtStart = true) {
        PlaybackHistorySyncer(
            repository = get(),
            api = aniApiProvider.playbackHistoryApi,
            sessionStateProvider = get(),
            scope = coroutineScope,
        ).also { it.start() }
    }
    single<AniSubjectRelationIndexService> {
        val provider = get<AniApiProvider>()
        AniSubjectRelationIndexService(provider.subjectRelationsApi)
    }

    single<PeerFilterSubscriptionRepository> {
        PeerFilterSubscriptionRepository(
            dataStore = getContext().dataStores.peerFilterSubscriptionStore,
            ruleSaveDir = getContext().files.dataDir.resolve("peerfilter-subs"),
            httpClient = get<HttpClientProvider>().get(ScopedHttpClientUserAgent.ANI),
            builtinPeerFilterRuleApi = get<AniApiProvider>().pfRuleApi,
        )
    }
    single<AnimeScheduleService> { AnimeScheduleService(get<AniApiProvider>().scheduleApi) }
    single<TrendsRepository> { TrendsRepository(get<AniApiProvider>().trendsApi) }
    single<RecommendationRepository> { RecommendationRepository(get<AniApiProvider>().homeApi) }
    single<AutoSkipRepository> { AutoSkipRepository(get<AniApiProvider>().episodesApi) }

    single<DanmakuRepository> {
        DanmakuRepository(
            parentCoroutineContext = coroutineScope.coroutineContext,
            danmakuApi = aniApiProvider.danmakuApi,
            danmakuDao = database.danmakuDao(),
            httpClientProvider = get(),
            getMediaCacheUseCase = get(),
            getSubjectEpisodeInfoBundleFlowUseCase = get(),
            settingsRepository = get(),
        )
    }
    single<UpdateManager> {
        UpdateManager(
            saveDir = getContext().files.cacheDir.resolve("updates/download"),
        )
    }
    single<SettingsRepository> { PreferencesRepositoryImpl(getContext().dataStores.preferencesStore) }
    single<DanmakuRegexFilterRepository> { DanmakuRegexFilterRepositoryImpl(getContext().dataStores.danmakuFilterStore) }
    single<MikanIndexCacheRepository> { MikanIndexCacheRepositoryImpl(getContext().dataStores.mikanIndexStore) }

    single<AniDatabase> {
        getContext().createDatabaseBuilder()
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .fallbackToDestructiveMigrationFrom(
                dropAllTables = true,
                startVersions = buildList {
                    addAll(1..15) // 16 is destructive
                }.toIntArray(),
            )
            .addMigrations(MIGRATION_19_20)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO_)
            .build()
    }

    single<HttpDownloader> {
        KtorPersistentHttpDownloader(
            dao = database.httpCacheDownloadStateDao(),
            get<HttpClientProvider>().get(),
            fileSystem = SystemFileSystem,
            baseSaveDir = get<MediaSaveDirProvider>().saveDir
                .let { Path(it).resolve(HttpMediaCacheEngine.MEDIA_CACHE_DIR) },
            scope = coroutineScope,
        )
    }

    // PikPak 引擎在三端都是进程内运行, 因此接线放在公共模块里, 不重复三份.
    single<PikPakEngine> {
        val settings = get<SettingsRepository>()
        // 初值必须是已保存的设置, 不能拿 PikPakConfig.Default 占位: 引擎的 isSupported 是同步快照,
        // 启动时的迁移与缓存恢复读到占位值会把引擎当成已关闭, 恢复失败的记录不会再重试.
        val savedConfig = runBlocking { settings.pikpakConfig.flow.first() }
        val configState = settings.pikpakConfig.flow
            .stateIn(coroutineScope, SharingStarted.Eagerly, savedConfig)
        val credentials = configState
            .map { cfg ->
                if (cfg.enabled && cfg.username.isNotEmpty() &&
                    (cfg.password.isNotEmpty() || cfg.refreshToken.isNotEmpty())
                ) {
                    PikPakCredentials(cfg.username, cfg.password)
                } else null
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, initialValue = null)

        PikPakEngine(
            config = configState,
            credentials = credentials,
            sessionStore = PikPakSessionStoreAdapter(
                readRefreshToken = { configState.value.refreshToken },
                writeRefreshToken = { rt -> settings.pikpakConfig.update { copy(refreshToken = rt) } },
            ),
            // SDK 自己写 User-Agent, 再叠一个插件会让请求带两个值, PikPak 据此拒绝 captcha.
            client = get<HttpClientProvider>().get(ScopedHttpClientUserAgent.NONE),
            saveDir = Path(get<MediaSaveDirProvider>().saveDir, TorrentEngineType.PikPak.id).inSystem,
            parentCoroutineContext = coroutineScope.coroutineContext,
        )
    }

    // Media
    single<MediaCacheManager> {
        val id = MediaCacheManager.LOCAL_FS_MEDIA_SOURCE_ID
        val engines = get<TorrentManager>().engines
        val metadataStore = getContext().dataStores.mediaCacheMetadataStore

        MediaCacheManagerImpl(
            storagesIncludingDisabled = buildList(capacity = engines.size) {
                /*if (currentAniBuildConfig.isDebug) {
                    // 注意, 这个必须要在第一个, 见 [DefaultTorrentManager.engines] 注释
                    add(
                        @Suppress("DEPRECATION")
                        TorrentMediaCacheStorage(
                            mediaSourceId = "test-in-memory",
                            store = metadataStore,
                            engine = DummyMediaCacheEngine("test-in-memory"),
                            "[debug]dummy",
                            coroutineScope.childScopeContext(),
                        ),
                    )
                }*/
                for (engine in engines) {
                    val isPikPak = engine.type == TorrentEngineType.PikPak
                    add(
                        @Suppress("DEPRECATION")
                        TorrentMediaCacheStorage(
                            mediaSourceId = id,
                            store = metadataStore,
                            torrentEngine = TorrentMediaCacheEngine(
                                mediaSourceId = id,
                                engineKey = MediaCacheEngineKey(engine.type.id),
                                torrentEngine = engine,
                                // PikPak 进程内运行, 安卓上不能让它唤起 AniTorrentService.
                                engineAccess = if (isPikPak) AlwaysUseTorrentEngineAccess else get(),
                                dao = database.torrentCacheInfoDao(),
                                baseSaveDirProvider = get(),
                                // BT 播放本来就边播边下, 顺带把这一份留给别人; PikPak 能按需取
                                // 任意字节, 自动记录下满只有做种一个理由, 那正是 Reseeding 开关.
                                fullDownloadForAutoCaches = if (isPikPak) {
                                    settingsRepository.pikpakConfig.flow.map { it.reseedingEnabled }
                                } else flowOf(true),
                            ),
                            // 缓存的 media 在选择器里显示原数据源的名称, 这个名字只剩下调试用途,
                            // 三个 storage 又共用同一个 mediaSourceId, 按 id 查到哪一个本就不确定.
                            displayName = "LocalTorrent",
                            parentCoroutineContext = coroutineScope.childScopeContext(),
                            // 分享率对云端离线没有意义.
                            shareRatioLimitFlow = if (isPikPak) flowOf(0f)
                            else settingsRepository.anitorrentConfig.flow.map { it.shareRatioLimit },
                            enableSiblingEpisodeHits = isPikPak,
                        ),
                    )
                }
                add(
                    @Suppress("DEPRECATION")
                    HttpMediaCacheStorage(
                        mediaSourceId = id,
                        store = metadataStore,
                        dao = database.httpCacheDownloadStateDao(),
                        httpEngine = get<HttpMediaCacheEngine>(),
                        displayName = "LocalWebM3u",
                        coroutineScope.childScopeContext(),
                    ),
                )
            },
            backgroundScope = coroutineScope.childScope(),
        )
    }


    single<MediaSourceCodecManager> {
        MediaSourceCodecManager()
    }
    single<MediaSourceManager> {
        MediaSourceManagerImpl(
            additionalSources = {
                get<MediaCacheManager>().storagesIncludingDisabled.map { it.cacheMediaSource }
            },
        )
    }
    single<MediaSourceSubscriptionUpdater> {
        val settings = koin.get<ProxyProvider>()
        val client = get<HttpClientProvider>().get(ScopedHttpClientUserAgent.ANI)
        MediaSourceSubscriptionUpdater(
            get<MediaSourceSubscriptionRepository>(),
            get<MediaSourceManager>(),
            get<MediaSourceCodecManager>(),
            requester = MediaSourceSubscriptionRequesterImpl(client, get<AniApiProvider>().subscriptionApi),
        )
    }
    single<SelectorMediaSourceEpisodeCacheRepository> {
        SelectorMediaSourceEpisodeCacheRepository(
            dao = database.webSearchSessionCacheDao(),
            userTtlFlow = get<SettingsRepository>().mediaSelectorSettings.flow.map { it.webSearchCacheTtl },
        )
    }

    // Caching
    single<MeteredNetworkDetector> { createMeteredNetworkDetector(getContext()) }
    single<SubjectDetailsStateFactory> { DefaultSubjectDetailsStateFactory() }
}

/**
 * 会在非 preview 环境调用. 用来初始化一些模块
 */
fun KoinApplication.startCommonKoinModule(
    context: Context,
    coroutineScope: CoroutineScope,
): KoinApplication {
    // Start the proxy provider very soon (before initialization of any other components)
    runBlocking {
        koin.get<SessionManager>().clearSessionIfAccessTokenExpired()
        // We have to block here to read the saved proxy settings
        when (val proxyProvider = koin.get<HttpClientProvider>()) {
            // compile-safe type cast
            is DefaultHttpClientProvider -> proxyProvider.startProxyListening(holdingInstanceMatrixSequence())
        }
    }
    // Now, the proxy settings is ready. Other components can use http clients.

    coroutineScope.launch {
        koin.get<HttpDownloader>().init() // restore http download states first
        // 迁移要在恢复之前跑完: 恢复按 MediaCacheSave.engine 分派到各个 storage,
        // 迁移改的正是这个字段.
        PikPakWebM3uCacheMigration(
            metadataStore = context.dataStores.mediaCacheMetadataStore,
            httpDao = koin.get<AniDatabase>().httpCacheDownloadStateDao(),
            torrentDao = koin.get<AniDatabase>().torrentCacheInfoDao(),
            baseSaveDirProvider = koin.get(),
            pikpakSaveDir = koin.get<PikPakEngine>().saveDir,
        ).migrate()
        val manager = koin.get<MediaCacheManager>()
        for (storage in manager.storagesIncludingDisabled) {
            storage.restorePersistedCaches()
        }

        // 做种协调器订阅 PikPak storage 的记录列表, 恢复前列表为空, 空集合对它意味着「什么都不做种」,
        // 所以放在恢复请求之后启动. 开关关闭时它不会触碰 anitorrent.
        launch {
            val pikpakStorage = manager.storagesIncludingDisabled.firstOrNull {
                it.engine.engineKey == MediaCacheEngineKey.PikPak
            } ?: return@launch
            val anitorrent = koin.get<TorrentManager>().engines.firstOrNull {
                it.type != TorrentEngineType.PikPak
            } ?: return@launch
            PikPakReseeder(
                pikpakCaches = pikpakStorage.listFlow,
                cacheInfo = koin.get<AniDatabase>().torrentCacheInfoDao(),
                anitorrentEngine = anitorrent,
                anitorrentAccess = koin.get(),
                reseedingEnabled = koin.get<SettingsRepository>().pikpakConfig.flow.map { it.reseedingEnabled },
                baseSaveDirProvider = koin.get(),
            ).run()
        }
    }

    coroutineScope.launch {
        val subscriptionUpdater = koin.get<MediaSourceSubscriptionUpdater>()
        while (currentCoroutineContext().isActive) {
            val nextDelay = subscriptionUpdater.updateAllOutdated()
            delay(nextDelay.coerceAtLeast(10.minutes))
        }
    }

    coroutineScope.launch {
        val currentSaves = context.dataStores.mediaSourceSaveStore.data.first()
        val defaultInstanceIds = MediaSourceSaves.Default.instances.map { it.instanceId }
        // 如果当前的数据源列表的 instance ids 都在默认列表里, 说明用户没有自定义过数据源, 直接写入默认源
        if (currentSaves.instances.all { it.instanceId in defaultInstanceIds }) {
            context.dataStores.mediaSourceSaveStore.updateData { MediaSourceSaves.Default }
        }
    }

    coroutineScope.launch {
        val peerFilterRepo = koin.get<PeerFilterSubscriptionRepository>()
        peerFilterRepo.updateOrLoadAll()
    }

    koin.get<SessionManager>().startBackgroundJob()
    return this
}

/**
 * 需要一直持有的 http client 实例列表
 */
private fun holdingInstanceMatrixSequence() = sequence {
    for (userAgent in ScopedHttpClientUserAgent.entries) {
        yield(
            HoldingInstanceMatrix(
                setOf(
                    UserAgentFeature.withValue(userAgent),
                    ServerListFeature.withValue(ServerListFeatureConfig.Default),
                    ConvertSendCountExceedExceptionFeature.withValue(true),
                ),
            ),
        )
    }

    yield(
        HoldingInstanceMatrix(
            setOf(
                UserAgentFeature.withValue(ScopedHttpClientUserAgent.ANI),
                ServerListFeature.withValue(ServerListFeatureConfig.Default),
                ConvertSendCountExceedExceptionFeature.withValue(true),
            ),
        ),
    )
}


fun createAppRootCoroutineScope(): CoroutineScope {
    val logger = logger("ani-root")
    return CoroutineScope(
        CoroutineExceptionHandler { coroutineContext, throwable ->
            logger.warn(throwable) {
                "Uncaught exception in coroutine $coroutineContext"
            }
        } + SupervisorJob() + Dispatchers.Default,
    )
}
