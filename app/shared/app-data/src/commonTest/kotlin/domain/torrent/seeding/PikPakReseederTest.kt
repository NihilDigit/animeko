/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.seeding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.data.persistent.database.dao.createMemoryTorrentCacheInfoDao
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.cache.engine.TorrentEngineAccess
import me.him188.ani.app.domain.media.cache.engine.UnsafeTorrentEngineAccessApi
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
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
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.torrent.pikpak.PikPakSavedFiles
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.writeBytes
import me.him188.ani.utils.platform.annotations.TestOnly
import org.openani.mediamp.io.SeekableInput
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(TestOnly::class)
class PikPakReseederTest {
    private val magnet = "magnet:?xt=urn:btih:0000000000000000000000000000000000000001"
    private val otherMagnet = "magnet:?xt=urn:btih:0000000000000000000000000000000000000002"

    private fun sessionDirOf(entity: TorrentCacheInfoEntity): SystemPath =
        Path("/save", entity.relativeDir).inSystem

    ///////////////////////////////////////////////////////////////////////////
    // matchTorrentPath
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `matches a nested torrent path by its file name`() {
        // 这是这套映射存在的理由: 云盘把 Pack/01.mkv 压平成了 01.mkv.
        assertEquals(
            "Pack/01.mkv",
            matchTorrentPath(listOf("Pack/01.mkv", "Pack/02.mkv"), "01.mkv"),
        )
    }

    @Test
    fun `gives up when two torrent files share a name`() {
        assertNull(matchTorrentPath(listOf("CDs/01.mkv", "TV/01.mkv"), "01.mkv"))
    }

    @Test
    fun `gives up when the torrent has no such file`() {
        assertNull(matchTorrentPath(listOf("Pack/01.mkv"), "03.mkv"))
    }

    ///////////////////////////////////////////////////////////////////////////
    // selectSeedCandidates
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `takes a completed original record`() {
        val candidates = selectSeedCandidates(
            caches = listOf(cache("m1", magnet, completed = true, pathInTorrent = "01.mkv")),
            entities = listOf(entity("m1")),
            sessionDirOf = ::sessionDirOf,
            isSeedableOriginal = { _, _ -> true },
        )
        assertEquals(1, candidates.size)
        assertEquals(magnet, candidates.single().uri)
        assertEquals("01.mkv", candidates.single().flatName)
        assertEquals(
            Path("/save", "/pikpak/m1").inSystem.resolve("01.mkv").path,
            candidates.single().sourceFile.path,
        )
    }

    @Test
    fun `skips a record that is not complete`() {
        assertTrue(
            selectSeedCandidates(
                caches = listOf(cache("m1", magnet, completed = false, pathInTorrent = "01.mkv")),
                entities = listOf(entity("m1")),
                sessionDirOf = ::sessionDirOf,
                isSeedableOriginal = { _, _ -> true },
            ).isEmpty(),
        )
    }

    /**
     * 行是种子粒度的, 它说的是「这个种子里有文件下完了」. 拿它当每一集的答案, 包里还没下完的
     * 剧集也会被送去做种, 而那个文件在盘上只有一半.
     */
    @Test
    fun `ignores a completed row when the record itself is not complete`() {
        assertTrue(
            selectSeedCandidates(
                caches = listOf(cache("m1", magnet, completed = false, pathInTorrent = "01.mkv")),
                entities = listOf(entity("m1", completed = true, pathInTorrent = "01.mkv")),
                sessionDirOf = ::sessionDirOf,
                isSeedableOriginal = { _, _ -> true },
            ).isEmpty(),
        )
    }

    /**
     * 同一个 media 被两个引擎各缓存一次时表里有两行. 按 mediaId 取到 anitorrent 那行, sourceFile
     * 就落在 anitorrent 的保存目录里, 做种会把它自己的文件再硬链接一遍.
     */
    @Test
    fun `ignores the anitorrent row of the same media`() {
        val candidates = selectSeedCandidates(
            caches = listOf(cache("m1", magnet, completed = true, pathInTorrent = "01.mkv")),
            entities = listOf(
                entity("m1").copy(engine = MediaCacheEngineKey.Anitorrent.key, relativeDir = "/anitorrent/123"),
                entity("m1"),
            ),
            sessionDirOf = ::sessionDirOf,
            isSeedableOriginal = { _, _ -> true },
        )
        assertEquals(
            Path("/save", "/pikpak/m1").inSystem.resolve("01.mkv").path,
            candidates.single().sourceFile.path,
        )
    }

    /**
     * 整季包各集共用一行 torrent_cache, 每集是哪个文件写在记录上. 从行里读, 两集会指向同一个文件.
     */
    @Test
    fun `two records sharing one row keep their own files`() {
        val candidates = selectSeedCandidates(
            caches = listOf(
                cache("m1", magnet, completed = true, pathInTorrent = "01.mkv"),
                cache("m1", magnet, completed = true, pathInTorrent = "02.mkv"),
            ),
            entities = listOf(entity("m1", completed = true, pathInTorrent = "01.mkv")),
            sessionDirOf = ::sessionDirOf,
            isSeedableOriginal = { _, _ -> true },
        )
        assertEquals(listOf("01.mkv", "02.mkv"), candidates.map { it.flatName })
    }

    @Test
    fun `skips a record whose file is a transcode`() {
        assertTrue(
            selectSeedCandidates(
                caches = listOf(cache("m1", magnet, completed = true, pathInTorrent = "01.mkv")),
                entities = listOf(entity("m1")),
                sessionDirOf = ::sessionDirOf,
                isSeedableOriginal = { _, _ -> false },
            ).isEmpty(),
        )
    }

    @Test
    fun `skips a record that does not know its file yet`() {
        assertTrue(
            selectSeedCandidates(
                caches = listOf(cache("m1", magnet, completed = true, pathInTorrent = null)),
                entities = listOf(entity("m1")),
                sessionDirOf = ::sessionDirOf,
                isSeedableOriginal = { _, _ -> true },
            ).isEmpty(),
        )
    }

    @Test
    fun `skips a cache with no torrent record`() {
        assertTrue(
            selectSeedCandidates(
                caches = listOf(cache("m1", magnet, completed = true, pathInTorrent = "01.mkv")),
                entities = emptyList(),
                sessionDirOf = ::sessionDirOf,
                isSeedableOriginal = { _, _ -> true },
            ).isEmpty(),
        )
    }

    ///////////////////////////////////////////////////////////////////////////
    // 放置与撤回
    ///////////////////////////////////////////////////////////////////////////

    /**
     * 撤任务时删的必须只是本次放进去的那些. 复用的那份可能是 anitorrent 自己下的正片, 用户关一下
     * 做种开关就把它删了.
     */
    @Test
    fun `only deletes the files this run created`() {
        val saveDir = Path("/save").inSystem
        val reused = decidePlacement(saveDir.resolve("01.mkv"), existingLength = 100, sourceLength = 100)
        val fresh = decidePlacement(saveDir.resolve("02.mkv"), existingLength = null, sourceLength = 100)
        val overwritten = decidePlacement(saveDir.resolve("03.mkv"), existingLength = 7, sourceLength = 100)

        assertEquals(
            listOf(saveDir.resolve("02.mkv").path, saveDir.resolve("03.mkv").path),
            createdLinksOf(listOf(reused, fresh, overwritten)).map { it.path },
        )
    }

    ///////////////////////////////////////////////////////////////////////////
    // seedPlan
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `groups two episodes of one pack under one torrent`() {
        val plan = seedPlan(
            enabled = true,
            caches = listOf(
                cache("m1", magnet, completed = true, pathInTorrent = "01.mkv"),
                cache("m2", magnet, completed = true, pathInTorrent = "02.mkv"),
                cache("m3", otherMagnet, completed = true, pathInTorrent = "01.mkv"),
            ),
            entities = listOf(entity("m1"), entity("m2"), entity("m3")),
            sessionDirOf = ::sessionDirOf,
            isSeedableOriginal = { _, _ -> true },
        )
        assertEquals(setOf(magnet, otherMagnet), plan.keys)
        assertEquals(listOf("01.mkv", "02.mkv"), plan.getValue(magnet).map { it.flatName })
    }

    @Test
    fun `plans nothing while the switch is off`() {
        val caches = listOf(cache("m1", magnet, completed = true, pathInTorrent = "01.mkv"))
        val entities = listOf(entity("m1"))
        val on = seedPlan(true, caches, entities, ::sessionDirOf) { _, _ -> true }
        val off = seedPlan(false, caches, entities, ::sessionDirOf) { _, _ -> true }

        assertEquals(1, on.size)
        // 关掉之后计划是空的, 而不是保持不变: 上层拿它跟正在做种的集合求差, 空集合正是「全撤掉」.
        assertTrue(off.isEmpty())
    }

    ///////////////////////////////////////////////////////////////////////////
    // 引擎请求与做种流程
    ///////////////////////////////////////////////////////////////////////////

    /**
     * 安卓上 `getDownloader()` 会挂起到 torrent service 连上为止, 而让它连上的正是协调器自己的
     * 请求. 请求晚于第一个 start 就是死等: 收集是顺序的, 挂住之后连开关被关掉都收不到.
     */
    @Test
    fun `starts seeding without the torrent service already connected`() = runTest(timeout = 60.seconds) {
        val env = prepareCompletedOriginal()
        val access = FakeEngineAccess()
        val seedingStarted = CompletableDeferred<Unit>()
        val downloader = FakeTorrentDownloader(
            saveDir = env.anitorrentSaveDir,
            pathsInTorrent = listOf("Pack/01.mkv"),
            onHandleResumed = { seedingStarted.complete(Unit) },
        )
        val enabled = MutableStateFlow(true)

        val job = launch(Dispatchers.Default) { env.reseeder(downloader, access, enabled).run() }
        try {
            seedingStarted.await()
            assertEquals(listOf(true), access.requests.value)
            assertEquals(1, downloader.discardedResumeData.value)

            enabled.value = false
            // 撤种和释放请求分属两步, 等第二次请求出现即可, 不必猜它们的先后.
            assertEquals(listOf(true, false), access.requests.first { it.size == 2 })
        } finally {
            job.cancel()
        }
    }

    /**
     * 同一个磁链在 anitorrent 里只有一个会话, 探测时拿到的可能是别人 (在播的本地 BT 回退, 或者
     * anitorrent 自己的缓存记录) 正开着的那个. 关掉它就把对方的播放或下载掐断了.
     */
    @Test
    fun `leaves a torrent alone while another consumer holds its session`() = runTest(timeout = 60.seconds) {
        val env = prepareCompletedOriginal()
        val access = FakeEngineAccess()
        val downloader = FakeTorrentDownloader(
            saveDir = env.anitorrentSaveDir,
            pathsInTorrent = listOf("Pack/01.mkv"),
            onHandleResumed = {},
            sessionInUse = true,
        )

        val job = launch(Dispatchers.Default) { env.reseeder(downloader, access, MutableStateFlow(true)).run() }
        try {
            // 一轮 reconcile 的末尾按实际起来的任务收敛请求, 一个都没起来就把开头那次请求还回去,
            // 所以第二次请求就是这一轮走完了.
            assertEquals(listOf(true, false), access.requests.first { it.size == 2 })
            // 只开了探测那一个会话, 没有第二次加入.
            assertEquals(1, downloader.sessions.value.size)
            // 丢 resume 记录只在没有会话开着时成立, 放文件更是直接改对方正在读的目录.
            assertEquals(0, downloader.discardedResumeData.value)
            assertFalse(env.anitorrentSaveDir.resolve("Pack/01.mkv").exists())
        } finally {
            job.cancel()
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // fakes
    ///////////////////////////////////////////////////////////////////////////

    private inner class Env(
        val pikpakRoot: SystemPath,
        val anitorrentSaveDir: SystemPath,
        val dao: TorrentCacheInfoDao,
    ) {
        fun reseeder(
            downloader: TorrentDownloader,
            access: TorrentEngineAccess,
            enabled: Flow<Boolean>,
        ) = PikPakReseeder(
            pikpakCaches = MutableStateFlow(
                listOf(cache("m1", magnet, completed = true, pathInTorrent = "01.mkv")),
            ),
            cacheInfo = dao,
            anitorrentEngine = FakeTorrentEngine(downloader, access, anitorrentSaveDir),
            anitorrentAccess = access,
            reseedingEnabled = enabled,
            baseSaveDirProvider = object : MediaSaveDirProvider {
                override val saveDir: String get() = pikpakRoot.absolutePath
            },
        )
    }

    /** 造一份「已下完的原画」和指向它的表行. 候选判定读的是引擎自己写的 meta.json, 所以这份文件也由引擎自己造. */
    private suspend fun prepareCompletedOriginal(): Env {
        val root = SystemPaths.createTempDirectory("pikpak-reseed-test")
        val pikpakRoot = root.resolve("pikpak").also { it.createDirectories() }
        val anitorrentSaveDir = root.resolve("anitorrent").also { it.createDirectories() }

        val bytes = root.resolve("source.mkv")
        bytes.writeBytes(ByteArray(64) { 1 })
        PikPakSavedFiles.importCompletedFile(pikpakRoot, magnet, bytes, "01.mkv")
        val sessionDir = PikPakSavedFiles.saveDirectoryFor(pikpakRoot, magnet)

        val dao = createMemoryTorrentCacheInfoDao()
        dao.upsert(entity("m1").copy(relativeDir = sessionDir.path.name))
        return Env(pikpakRoot, anitorrentSaveDir, dao)
    }

    /** 请求到达之前 service 没连上, 请求之后才连上, 与安卓上的行为一致. */
    private class FakeEngineAccess : TorrentEngineAccess {
        override val isServiceConnected = MutableStateFlow(false)

        /** 用 flow 而不是 list, 收集发生在别的线程上. */
        val requests = MutableStateFlow(emptyList<Boolean>())

        @UnsafeTorrentEngineAccessApi
        override fun requestService(token: Any, use: Boolean): Boolean {
            requests.value += use
            isServiceConnected.value = use
            return true
        }
    }

    private class FakeTorrentEngine(
        private val downloader: TorrentDownloader,
        private val access: TorrentEngineAccess,
        override val saveDir: SystemPath,
    ) : TorrentEngine {
        override val type: TorrentEngineType get() = TorrentEngineType.RemoteAnitorrent
        override val location: MediaSourceLocation get() = MediaSourceLocation.Local
        override val isSupported: Boolean get() = true
        override suspend fun testConnection(): Boolean = true

        override suspend fun getDownloader(): TorrentDownloader {
            access.isServiceConnected.first { it }
            return downloader
        }

        override fun close() {}
    }

    private class FakeTorrentDownloader(
        private val saveDir: SystemPath,
        private val pathsInTorrent: List<String>,
        private val onHandleResumed: () -> Unit,
        /** 同一个磁链已经有别的使用方开着文件, 会话关不掉. */
        private val sessionInUse: Boolean = false,
    ) : TorrentDownloader {
        val discardedResumeData = MutableStateFlow(0)
        val sessions = MutableStateFlow(emptyList<FakeTorrentSession>())

        override suspend fun fetchTorrent(uri: String, timeoutSeconds: Int): EncodedTorrentInfo =
            EncodedTorrentInfo.createRaw(uri.encodeToByteArray())

        override suspend fun startDownload(
            data: EncodedTorrentInfo,
            parentCoroutineContext: CoroutineContext,
        ): TorrentSession = FakeTorrentSession(
            files = pathsInTorrent.map { FakeFileEntry(it, onHandleResumed) },
            inUse = sessionInUse,
        ).also { sessions.value += it }

        override fun getSaveDirForTorrent(data: EncodedTorrentInfo): SystemPath = saveDir
        override fun discardResumeData(data: EncodedTorrentInfo) {
            discardedResumeData.value++
        }

        override fun listSaves(): List<SystemPath> = emptyList()
        override fun close() {}
        override val totalStats: Flow<TorrentDownloader.Stats> get() = throw UnsupportedOperationException()
        override val vendor: TorrentLibInfo get() = throw UnsupportedOperationException()
    }

    private class FakeTorrentSession(
        private val files: List<TorrentFileEntry>,
        private val inUse: Boolean = false,
    ) : TorrentSession {
        override suspend fun getName(): String = "fake"
        override suspend fun getFiles(): List<TorrentFileEntry> = files
        override fun getPeers(): List<PeerInfo> = emptyList()
        override suspend fun close() {
            // 别人还开着文件时被关掉正是要抓的那个 bug. 当场炸在调用方的协程里, 比等测试超时看得清楚.
            check(!inUse) { "closed a session that another consumer is using" }
        }

        override suspend fun closeIfNotInUse(): Boolean {
            if (inUse) return false
            close()
            return true
        }

        override val sessionStats: Flow<TorrentSession.Stats?> get() = throw UnsupportedOperationException()
    }

    private class FakeFileEntry(
        override val pathInTorrent: String,
        private val onHandleResumed: () -> Unit,
    ) : TorrentFileEntry {
        override val fileName: String get() = pathInTorrent.substringAfterLast('/')
        override val supportsStreaming: Boolean get() = false

        override fun createHandle(): TorrentFileHandle = object : TorrentFileHandle {
            override val entry: TorrentFileEntry get() = this@FakeFileEntry
            override fun resume(priority: FilePriority) = onHandleResumed()
            override fun pause() = throw UnsupportedOperationException()
            override suspend fun close() {}
            override suspend fun closeAndDelete() = throw UnsupportedOperationException()
        }

        override val fileStats: Flow<TorrentFileEntry.Stats> get() = throw UnsupportedOperationException()
        override val length: Long get() = throw UnsupportedOperationException()
        override val pieces: PieceList get() = throw UnsupportedOperationException()
        override suspend fun resolveFile(): SystemPath = throw UnsupportedOperationException()
        override fun resolveFileMaybeEmptyOrNull(): SystemPath = throw UnsupportedOperationException()
        override suspend fun createInput(awaitCoroutineContext: CoroutineContext): SeekableInput =
            throw UnsupportedOperationException()
    }

    /**
     * 行只提供保存目录, 所以默认值就够用; completed 和 pathInTorrent 只在要证明「行说了不算」的
     * 用例里显式给出.
     */
    private fun entity(
        mediaId: String,
        completed: Boolean = false,
        pathInTorrent: String = "",
    ) = TorrentCacheInfoEntity(
        mediaId = mediaId,
        engine = MediaCacheEngineKey.PikPak.key,
        torrentData = ByteArray(0),
        relativeDir = "/pikpak/$mediaId",
        completed = completed,
        pathInTorrent = pathInTorrent,
    )

    private fun cache(
        mediaId: String,
        uri: String,
        completed: Boolean,
        pathInTorrent: String?,
    ): MediaCache = FakeMediaCache(
        (TestMediaList.first() as DefaultMedia).copy(
            mediaId = mediaId,
            download = ResourceLocation.MagnetLink(uri),
        ),
        completed = completed,
        pathInTorrent = pathInTorrent,
    )

    /**
     * 只有 [origin] 和 [metadata] 是被测代码会读的, 其余成员在这几个用例里被碰到就说明筛选逻辑
     * 走错了路, 所以让它们抛异常而不是返回零值.
     */
    private class FakeMediaCache(
        override val origin: Media,
        completed: Boolean,
        pathInTorrent: String?,
    ) : MediaCache {
        override val metadata: MediaCacheMetadata = MediaCacheMetadata(
            subjectId = "1",
            episodeId = "1",
            subjectNames = emptyList(),
            episodeSort = EpisodeSort(1),
            episodeName = "",
            episodeEp = null,
            completed = completed,
            pathInTorrent = pathInTorrent,
        )
        override val state = MutableStateFlow(MediaCacheState.COMPLETED)
        override val isDeleted = MutableStateFlow(false)
        override val sessionStats: Flow<MediaCache.SessionStats> get() = throw UnsupportedOperationException()
        override val fileStats: Flow<MediaCache.FileStats> get() = throw UnsupportedOperationException()
        override suspend fun getCachedMedia(): Nothing = throw UnsupportedOperationException()
        override suspend fun pause(): Nothing = throw UnsupportedOperationException()
        override suspend fun resume(): Nothing = throw UnsupportedOperationException()
        override suspend fun close(): Nothing = throw UnsupportedOperationException()
        override suspend fun closeAndDeleteFiles(): Nothing = throw UnsupportedOperationException()
    }
}
