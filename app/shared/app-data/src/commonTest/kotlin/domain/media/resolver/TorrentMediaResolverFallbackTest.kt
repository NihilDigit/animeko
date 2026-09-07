/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import me.him188.ani.app.domain.media.cache.engine.AlwaysUseTorrentEngineAccess
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.domain.torrent.TorrentEngineType
import me.him188.ani.app.torrent.api.TorrentDownloader
import me.him188.ani.app.torrent.api.TorrentLibInfo
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.EncodedTorrentInfo
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.app.torrent.api.peer.PeerInfo
import me.him188.ani.app.torrent.api.pieces.PieceList
import me.him188.ani.torrent.pikpak.CloudReadiness
import org.openani.mediamp.io.SeekableInput
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.inSystem
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * 首选引擎失败时链条不会自己往下走: [MediaResolver.from] 只按 [MediaResolver.supports] 选第一个,
 * 选中之后抛出的异常直达播放器. 回退因此是 [TorrentMediaResolver] 自己的事.
 *
 * @see TorrentMediaResolver
 */
class TorrentMediaResolverFallbackTest {
    private val media = createTestDefaultMedia(
        mediaId = "1",
        mediaSourceId = "test",
        originalUrl = "https://example.com/1",
        download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:1"),
        originalTitle = "test 01",
        publishedTime = 0,
        properties = createTestMediaProperties(),
        episodeRange = EpisodeRange.single(EpisodeSort(1)),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.BitTorrent,
    )

    private val episode = EpisodeMetadata("test 01", EpisodeSort(1), EpisodeSort(1))

    private fun resolver(
        engine: TorrentEngine,
        fallback: MediaResolver? = TestUniversalMediaResolver,
    ) = TorrentMediaResolver(engine, AlwaysUseTorrentEngineAccess, fallback = fallback)

    @Test
    fun `falls back when the engine cannot be created`() = runTest {
        val provider = resolver(FakeTorrentEngine { throw IllegalStateException("bad credentials") })
            .resolve(media, episode)
        assertIs<TestMediaDataProvider>(provider)
    }

    /**
     * 云端引擎的登录、离线任务、列目录都发生在 open 里, 解析阶段是纯本地的, 所以真正的失败大多在这里.
     */
    @Test
    fun `falls back when opening fails`() = runTest {
        val provider = resolver(FakeTorrentEngine { FakeDownloader { throw IllegalStateException("quota exceeded") } })
            .resolve(media, episode)
        val data = provider.open(this)
        assertEquals("https://example.com", assertIs<UriMediaData>(data).uri)
    }

    /**
     * 文件清单已经拿到, 另一个引擎面对同一份文件名会得出同样的结果; 回退只会把用户挑文件的对话框
     * 换成一次同样的失败.
     */
    @Test
    fun `does not fall back when no file matches`() = runTest {
        val provider = resolver(FakeTorrentEngine { FakeDownloader { FakeSession } })
            .resolve(media, episode)
        val e = assertFailsWith<MediaSourceOpenException> { provider.open(this) }
        assertEquals(OpenFailures.NO_MATCHING_FILE, e.reason)
    }

    /**
     * 恢复自磁盘的会话在 open 里不碰云端, 账号失效要到第一次读才暴露. 把直链解析提前到 open 里,
     * 失败才落在回退范围内, 并且已经开的句柄要关掉.
     */
    @Test
    fun `falls back when the cloud is not ready for the selected file`() = runTest {
        val entry = FakeCloudEntry("test 01.mp4") { throw IllegalStateException("bad credentials") }
        val provider = resolver(FakeTorrentEngine { FakeDownloader { FakeSessionOf(entry) } })
            .resolve(media, episode)
        val data = provider.open(this)
        assertEquals("https://example.com", assertIs<UriMediaData>(data).uri)
        assertEquals(1, entry.closedHandles, "the handle opened before the check must be released")
    }

    @Test
    fun `without a fallback the failure surfaces`() = runTest {
        val resolver = resolver(FakeTorrentEngine { throw IllegalStateException("bad credentials") }, fallback = null)
        val e = assertFailsWith<MediaResolutionException> { resolver.resolve(media, episode) }
        assertEquals(ResolutionFailures.ENGINE_ERROR, e.reason)
    }
}

private class FakeTorrentEngine(
    private val getDownloader: suspend () -> TorrentDownloader,
) : TorrentEngine {
    override val type: TorrentEngineType get() = TorrentEngineType.PikPak
    override val location: MediaSourceLocation get() = MediaSourceLocation.Online
    override val isSupported: Boolean get() = true
    override val saveDir: SystemPath get() = Path("build/fake-torrent-engine").inSystem
    override suspend fun testConnection(): Boolean = true
    override suspend fun getDownloader(): TorrentDownloader = getDownloader.invoke()
    override fun close() {}
}

private class FakeDownloader(
    private val startDownload: suspend () -> TorrentSession,
) : TorrentDownloader {
    override val totalStats: Flow<TorrentDownloader.Stats> get() = emptyFlow()
    override val vendor: TorrentLibInfo get() = TorrentLibInfo("fake", "0", supportsStreaming = true)

    override suspend fun fetchTorrent(uri: String, timeoutSeconds: Int): EncodedTorrentInfo =
        EncodedTorrentInfo.createRaw(byteArrayOf(1))

    override suspend fun startDownload(data: EncodedTorrentInfo, parentCoroutineContext: CoroutineContext) =
        startDownload.invoke()

    override fun getSaveDirForTorrent(data: EncodedTorrentInfo): SystemPath =
        Path("build/fake-torrent-engine").inSystem

    override fun listSaves(): List<SystemPath> = emptyList()
    override fun close() {}
}

private class FakeSessionOf(private vararg val entries: TorrentFileEntry) : TorrentSession {
    override val sessionStats: Flow<TorrentSession.Stats?> get() = emptyFlow()
    override suspend fun getName(): String = "fake"
    override suspend fun getFiles(): List<TorrentFileEntry> = entries.toList()
    override fun getPeers(): List<PeerInfo> = emptyList()
    override suspend fun close() {}
    override suspend fun closeIfNotInUse(): Boolean = true
}

private class FakeCloudEntry(
    override val pathInTorrent: String,
    private val ready: suspend () -> Unit,
) : TorrentFileEntry, CloudReadiness {
    var closedHandles = 0
    override val fileName: String get() = pathInTorrent
    override val supportsStreaming: Boolean get() = true
    override suspend fun ensureCloudReady() = ready()

    override fun createHandle(): TorrentFileHandle = object : TorrentFileHandle {
        override val entry: TorrentFileEntry get() = this@FakeCloudEntry
        override fun resume(priority: FilePriority) {}
        override fun pause() {}
        override suspend fun close() { closedHandles++ }
        override suspend fun closeAndDelete() = close()
    }

    override val fileStats: Flow<TorrentFileEntry.Stats> get() = emptyFlow()
    override val length: Long get() = 1
    override val pieces: PieceList get() = throw UnsupportedOperationException()
    override suspend fun resolveFile(): SystemPath = throw UnsupportedOperationException()
    override fun resolveFileMaybeEmptyOrNull(): SystemPath? = null
    override suspend fun createInput(awaitCoroutineContext: CoroutineContext): SeekableInput =
        throw UnsupportedOperationException()
}

/**
 * 没有任何文件, 于是 [TorrentMediaResolver.selectVideoFileEntry] 匹配不上.
 */
private object FakeSession : TorrentSession {
    override val sessionStats: Flow<TorrentSession.Stats?> get() = emptyFlow()
    override suspend fun getName(): String = "fake"
    override suspend fun getFiles(): List<TorrentFileEntry> = emptyList()
    override fun getPeers(): List<PeerInfo> = emptyList()
    override suspend fun close() {}
    override suspend fun closeIfNotInUse(): Boolean = true
}
