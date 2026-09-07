/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.dataStores
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.repository.WindowStateRepository
import me.him188.ani.app.data.repository.WindowStateRepositoryImpl
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.cache.engine.AlwaysUseTorrentEngineAccess
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.TorrentEngineAccess
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.hls.HlsPlaybackPreparer
import me.him188.ani.app.domain.media.hls.PlatformHlsPlaybackPreparer
import me.him188.ani.app.domain.media.resolver.DesktopWebMediaResolver
import me.him188.ani.app.domain.media.resolver.HttpStreamingMediaResolver
import me.him188.ani.app.domain.media.resolver.LocalFileMediaResolver
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.TorrentMediaResolver
import me.him188.ani.app.domain.mediasource.web.DesktopOnnxImageCaptchaRecognizer
import me.him188.ani.app.domain.mediasource.web.captcha.CaptchaBrowserFactory
import me.him188.ani.app.domain.mediasource.web.captcha.DesktopCaptchaBrowserFactory
import me.him188.ani.app.domain.mediasource.web.captcha.ImageCaptchaRecognizer
import me.him188.ani.app.domain.mediasource.web.captcha.WebSessionManager
import me.him188.ani.app.domain.torrent.DefaultTorrentManager
import me.him188.ani.app.domain.torrent.TorrentEngineType
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.domain.torrent.TorrentManager
import me.him188.ani.app.domain.torrent.engines.PikPakEngine
import me.him188.ani.app.navigation.BrowserNavigator
import me.him188.ani.app.navigation.DesktopBrowserNavigator
import me.him188.ani.app.platform.AppTerminator
import me.him188.ani.app.platform.DefaultAppTerminator
import me.him188.ani.app.platform.DesktopContext
import me.him188.ani.app.platform.GrantedPermissionManager
import me.him188.ani.app.platform.PermissionManager
import me.him188.ani.app.platform.files
import me.him188.ani.app.tools.update.DesktopUpdateInstaller
import me.him188.ani.app.tools.update.UpdateInstaller
import me.him188.ani.utils.httpdownloader.HttpDownloader
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.Arch
import me.him188.ani.utils.platform.Platform
import me.him188.ani.utils.platform.currentPlatformDesktop
import org.koin.dsl.module
import org.openani.mediamp.MediampPlayerFactory
import org.openani.mediamp.MediampPlayerFactoryLoader
import org.openani.mediamp.compose.MediampPlayerSurfaceProviderLoader
import org.openani.mediamp.mpv.MpvMediampPlayerFactory
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurfaceProvider
import java.io.File
import kotlin.io.path.Path

internal fun isWindowsArm64(): Boolean {
    val platform = currentPlatformDesktop()
    return platform is Platform.Windows && platform.arch == Arch.AARCH64
}

fun getDesktopModules(getContext: () -> DesktopContext, scope: CoroutineScope) = module {
    single<TorrentEngineAccess> { AlwaysUseTorrentEngineAccess }

    single<MediaSaveDirProvider> {
        val settings = get<SettingsRepository>().mediaCacheSettings
        val defaultTorrentCachePath = getContext().files.defaultMediaCacheBaseDir

        val baseSaveDir = runBlocking {
            val saveDirSettings = settings.flow.first().saveDir
            // 首次启动设置默认 dir
            if (saveDirSettings == null) {
                val finalPathString = defaultTorrentCachePath.absolutePath
                settings.update { copy(saveDir = finalPathString) }
                return@runBlocking finalPathString
            }

            // 如果当前目录没有权限读写, 直接使用默认目录
            if (!File(saveDirSettings).run { canRead() && canWrite() }) {
                val fallbackPathString = defaultTorrentCachePath.absolutePath
                settings.update { copy(saveDir = fallbackPathString) }
                return@runBlocking fallbackPathString
            }

            saveDirSettings
        }

        object : MediaSaveDirProvider {
            override val saveDir: String = baseSaveDir
        }
    }

    single<TorrentManager> {
        if (isWindowsArm64()) {
            // No Windows ARM64 anitorrent runtime is published; match iOS by exposing no local torrent engine.
            logger<TorrentManager>().info { "Anitorrent is disabled on Windows ARM64" }
            // PikPak 不依赖 native 库, 在这里仍然可用.
            return@single object : TorrentManager {
                override val engines: List<TorrentEngine> = listOf(get<PikPakEngine>())
            }
        }

        val saveDir = get<MediaSaveDirProvider>().saveDir
        logger<TorrentManager>().info { "TorrentManager base save dir: $saveDir" }

        DefaultTorrentManager.create(
            scope.coroutineContext,
            get(),
            client = get<HttpClientProvider>().get(ScopedHttpClientUserAgent.ANI),
            get(),
            get(),
            baseSaveDir = { Path(saveDir).toKtPath().inSystem },
            pikpak = get<PikPakEngine>(),
        )
    }
    single<HttpMediaCacheEngine> {
        val saveDir = Path(get<MediaSaveDirProvider>().saveDir).resolve(HttpMediaCacheEngine.MEDIA_CACHE_DIR)
        logger<TorrentManager>().info { "HttpMediaCacheEngine base save dir: $saveDir" }

        HttpMediaCacheEngine(
            dao = get<AniDatabase>().httpCacheDownloadStateDao(),
            mediaSourceId = MediaCacheManager.LOCAL_FS_MEDIA_SOURCE_ID,
            downloader = get<HttpDownloader>(),
            saveDir = saveDir.toKtPath(),
            mediaResolver = get<MediaResolver>(),
        )
    }

    single<MediampPlayerFactory<*>> {
        MediampPlayerFactoryLoader.register(MpvMediampPlayerFactory())
        MediampPlayerSurfaceProviderLoader.register(MpvMediampPlayerSurfaceProvider())
        MediampPlayerFactoryLoader.first()
    }
    single<BrowserNavigator> { DesktopBrowserNavigator() }
    single<CaptchaBrowserFactory> { DesktopCaptchaBrowserFactory() }
    single<ImageCaptchaRecognizer> { DesktopOnnxImageCaptchaRecognizer() }
    single<HlsPlaybackPreparer> { PlatformHlsPlaybackPreparer(get()) }
    factory<MediaResolver> {
        val engines = get<TorrentManager>().engines
        // PikPak 进程内运行, engineAccess 用 AlwaysUseTorrentEngineAccess,
        // 否则安卓上它会去唤起 AniTorrentService.
        val localTorrentResolvers = engines.filter { it.type != TorrentEngineType.PikPak }
            .map { TorrentMediaResolver(it, get()) }
        // PikPak 排在本地 BT 之前, 失败时交给本地 BT: 登录、配额、离线任务、列目录的错误都在解析或打开
        // 时退化, 用户仍能播这一集. 不退化的只有「资源里没有这一集」, 那是文件清单已经拿到之后的结论,
        // 换引擎得到的是同一份清单, 退化只会顶掉用户挑文件的对话框.
        val pikpakResolvers = engines.filter { it.type == TorrentEngineType.PikPak }
            .map {
                TorrentMediaResolver(
                    it, AlwaysUseTorrentEngineAccess,
                    fallback = localTorrentResolvers.firstOrNull(),
                )
            }
        MediaResolver.from(
            pikpakResolvers
                .plus<MediaResolver>(localTorrentResolvers)
                .plus(LocalFileMediaResolver())
                .plus(HttpStreamingMediaResolver())
                .plus(
                    DesktopWebMediaResolver(
                        getContext(),
                        get<MediaSourceManager>().webVideoMatcherLoader,
                        get<WebSessionManager>(),
                    ),
                ),
        )
    }
    single<UpdateInstaller> { DesktopUpdateInstaller.currentOS() }
    single<PermissionManager> { GrantedPermissionManager }
    single<WindowStateRepository> { WindowStateRepositoryImpl(getContext().dataStores.savedWindowStateStore) }
    single<AppTerminator> { DefaultAppTerminator }
}
