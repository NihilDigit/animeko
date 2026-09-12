/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.asRangeSource
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getOrCreateDeepFolderId
import io.github.nihildigit.pikpak.instantCreate
import io.github.nihildigit.pikpak.rangeReader
import io.github.nihildigit.pikpak.resolveMagnet
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.readExactBytes
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.createDefaultHttpClient
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * End-to-end against the real service, skipped when credentials are absent.
 *
 * 检查的是这条路赖以成立的四件事:
 *  1. 通过引擎读到的字节就是 PikPak 持有的字节. 对照组是 SDK 直接从同一个 gcid 建出的文件对象上
 *     读同样的区间, 所以块偏移搞错会在这里变成摘要不一致, 而不是以后变成花屏.
 *  2. Closing a session leaves the engine's temp folder empty: instant upload
 *     is billed at full size, so a stranded object costs a whole episode.
 *  3. 重开会话完全从 meta.json 恢复, 一个 API 调用都不发. 请求计数让这句话是"零次"而不是"很少".
 *  4. canServe 对没有索引的磁链答 false, 而且不重复解析.
 *
 * Env vars: PIKPAK_USERNAME, PIKPAK_PASSWORD. The build script maps them from
 * local.properties, so:
 *
 *     ./gradlew.bat :torrent:pikpak:desktopTest --tests '*PikPakTorrentEngineLiveTest*'
 */
class PikPakTorrentEngineLiveTest {

    /**
     * The temp folder settles back to empty once a session is done with it.
     *
     * Every object is dropped as soon as a link has been minted from it, the
     * ones the SDK rebuilds after a rejection included -- those reach the same
     * discard through `onObjectMinted`. So a leftover here is a real leak and
     * not something the next startup sweep was always going to take.
     *
     * Waits on the listing rather than on the delete: for a few seconds after
     * batchDelete returns, a listing can still carry the object, so asking once
     * would pass and fail by turns.
     */
    private suspend fun assertTempFolderEmpty(downloader: PikPakTorrentDownloader, message: String) {
        val deadline = TimeSource.Monotonic.markNow()
        var count = downloader.tempFolderObjectCount()
        while (count > 0 && deadline.elapsedNow() < LISTING_SETTLE) {
            delay(1.seconds)
            count = downloader.tempFolderObjectCount()
        }
        assertEquals(0, count, "$message (still listed after ${deadline.elapsedNow()})")
    }

    @Test
    fun `read through the engine, verify against the SDK, then restore from disk`() = runBlocking {
        val username = System.getenv("PIKPAK_USERNAME")
        val password = System.getenv("PIKPAK_PASSWORD")
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            println("[skip] PIKPAK_USERNAME / PIKPAK_PASSWORD not set")
            return@runBlocking
        }
        val magnet = DEFAULT_MAGNET

        val apiCalls = AtomicInteger(0)
        val http = countingClient(apiCalls)
        val credentials = MutableStateFlow(PikPakCredentials(username, password))
        val config = MutableStateFlow(PikPakEngineConfig())
        // A fresh root forces the first session down the magnet-resolving path
        // and leaves the second one nothing but the disk.
        val root = SystemPaths.createTempDirectory("pikpak-engine-live").resolve("pikpak")

        fun downloader() = PikPakTorrentDownloader(
            httpClient = http,
            credentials = credentials,
            sessionStore = InMemorySessionStore(),
            rootDataDirectory = root,
            config = config,
            parentCoroutineContext = Dispatchers.IO,
        )

        val first = downloader()

        // canServe 只看视频扩展名的条目, 而这条磁链是个 ISO, 所以它答 false. 这里要验的是引擎
        // 真读得到字节, 那条路从 fetchTorrent 开始, 不经过 canServe.
        val servedAt = TimeSource.Monotonic.markNow()
        assertFalse(first.canServe(magnet), "an ISO carries no video entry, so the engine must decline it")
        println("[live] canServe answered in ${servedAt.elapsedNow()}")

        // 肯定分支单独验一次. 只验否定分支的话, canServe 恒答 false 也能全绿, 而那等于 PikPak
        // 静默地永不参与——这类退化没有任何报错, 只表现为"这个功能好像没开".
        //
        // 这条红了先确认 PikPak 是不是不再索引这个包(换一条 INDEXED_VIDEO_MAGNET 即可), 再怀疑
        // canServe 本身. 不做成跳过: 跳过等于没有覆盖, 而没有覆盖正是这里要解决的问题.
        val videoAt = TimeSource.Monotonic.markNow()
        val videoServed = first.canServe(INDEXED_VIDEO_MAGNET)
        println("[live] canServe on an indexed video pack: $videoServed in ${videoAt.elapsedNow()}")
        assertTrue(videoServed, "an indexed video pack must be accepted; see INDEXED_VIDEO_MAGNET")

        val encoded = first.fetchTorrent(magnet)
        val startedAt = TimeSource.Monotonic.markNow()
        val session = withTimeout(2.minutes) { first.startDownload(encoded) }
        val indexed = startedAt.elapsedNow()
        val entry = session.getFiles().maxBy { it.length } as PikPakFileEntry
        println("[live] indexed in $indexed: ${session.getName()} -> ${entry.pathInTorrent} (${entry.length} bytes)")
        assertTrue(entry.meta.gcid.isNotEmpty(), "the entry carries no gcid, so nothing could be recreated from it")

        val handle = entry.createHandle()
        handle.resume(FilePriority.HIGH)

        val head: ByteArray
        val middle: ByteArray
        val middleOffset = (entry.length * 6 / 10) / BLOCK * BLOCK
        entry.createInput().use { input ->
            val headMark = TimeSource.Monotonic.markNow()
            input.seekTo(0)
            head = input.readExactBytes(HEAD_LENGTH)
            println("[live] first $HEAD_LENGTH bytes in ${headMark.elapsedNow()}")

            val seekMark = TimeSource.Monotonic.markNow()
            input.seekTo(middleOffset)
            middle = input.readExactBytes(MIDDLE_LENGTH)
            println("[live] $MIDDLE_LENGTH bytes at $middleOffset in ${seekMark.elapsedNow()}")
        }

        // Same ranges, from a file object this test builds itself out of the
        // same gcid, with none of the engine in between.
        val reference = PikPakClient(
            account = username,
            password = password,
            sessionStore = InMemorySessionStore(),
            httpClient = http,
            cdnHttpClient = PikPakClient.tunedCdnClient(),
        )
        val resolved = reference.resolveMagnet(magnet)
            ?: error("reference lookup: PikPak does not index $magnet")
        val referenceFile = resolved.files.single { it.path == entry.pathInTorrent }
        val referenceFolder = reference.getOrCreateDeepFolderId("", "Animeko-LiveTest-Reference")
        val referenceId = reference.instantCreate(referenceFile, parentId = referenceFolder)
        try {
            val source = reference.rangeReader(referenceId).asRangeSource()
            assertEquals(
                sha256(head),
                sha256(source.readBytes(0, HEAD_LENGTH.toLong())),
                "the first $HEAD_LENGTH bytes differ from what PikPak serves",
            )
            assertEquals(
                sha256(middle),
                sha256(source.readBytes(middleOffset, MIDDLE_LENGTH.toLong())),
                "the $MIDDLE_LENGTH bytes at $middleOffset differ from what PikPak serves",
            )
        } finally {
            reference.batchDelete(listOf(referenceId))
        }

        // Playback writes nothing. Keeping what a viewer happened to watch
        // would either fill the disk with episodes nobody asked to cache or be
        // deleted at the next startup, and PikPak can serve those bytes again
        // for one round trip.
        assertEquals(
            0L,
            entry.fileStats.first().downloadedBytes,
            "playback must not have downloaded anything to disk",
        )

        handle.close()
        session.close()

        assertTempFolderEmpty(first, "closing the session left objects behind")
        first.close()

        // Second session, same JVM, same data root. The engine must rebuild its
        // state from meta.json alone.
        val second = downloader()
        // 开机清理是引擎自己发起的一次列目录, 不属于恢复路径. 等它跑完再计数, 否则它会落在
        // 恢复的窗口里被算进去.
        second.startupSweep.join()
        val callsBeforeRestore = apiCalls.get()
        val restored = second.startDownload(second.fetchTorrent(magnet))
        val restoredEntry = restored.getFiles().single { it.pathInTorrent == entry.pathInTorrent }
        val callsAfterRestore = apiCalls.get()

        assertEquals(
            callsBeforeRestore,
            callsAfterRestore,
            "restoring from disk must not talk to PikPak",
        )
        assertEquals(entry.length, restoredEntry.length)

        restored.close()
        second.close()
        http.close()
    }

    /**
     * 画质设置真的改变了播放读的那份流.
     *
     * 判据是流的长度: 转码是另一份编码, 字节数与原画不等, 而条目长度是种子给的原画长度, 两端都
     * 不变. PikPak 手上没有 720P 转码时 `resolveVariant` 回退原画, 那时两个长度相等才是对的,
     * 所以这条测先问 PikPak 有没有, 再决定断言哪一边.
     */
    @Test
    fun `the 720P setting reads a different stream from the original`() = runBlocking {
        val username = System.getenv("PIKPAK_USERNAME")
        val password = System.getenv("PIKPAK_PASSWORD")
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            println("[skip] PIKPAK_USERNAME / PIKPAK_PASSWORD not set")
            return@runBlocking
        }
        // 默认那条磁链是 Arch 的 ISO, PikPak 不会给它转码, 所以默认跑到的是回退那一侧. 真正
        // 有转码的一侧要一条视频磁链: local.properties 的 `pikpak-magnet` 会被映射成这个变量.
        val magnet = System.getenv("PIKPAK_MAGNET")?.takeIf { it.isNotEmpty() } ?: DEFAULT_MAGNET
        val http = createDefaultHttpClient()

        fun streamSizeWith(variant: String): Pair<Long, Long> = runBlocking {
            val downloader = PikPakTorrentDownloader(
                httpClient = http,
                credentials = MutableStateFlow(PikPakCredentials(username, password)),
                sessionStore = InMemorySessionStore(),
                // 每个变体一个全新的数据根: 同一个目录里两个变体不并存, 第二次会按第一次记下的
                // 变体继续, 那样这条测就只是在测 pin 而不是在测设置.
                rootDataDirectory = SystemPaths.createTempDirectory("pikpak-live-$variant").resolve("pikpak"),
                config = MutableStateFlow(PikPakEngineConfig(variant = variant)),
                parentCoroutineContext = Dispatchers.IO,
            )
            val session = withTimeout(2.minutes) { downloader.startDownload(downloader.fetchTorrent(magnet)) }
            val entry = session.getFiles().maxBy { it.length } as PikPakFileEntry
            val handle = entry.createHandle()
            handle.resume(FilePriority.HIGH)
            val streamSize = entry.createInput().use { input ->
                // 真读一段: 长度对而字节读不出来的情况这里才会暴露.
                input.seekTo(0)
                assertEquals(PROBE_LENGTH, input.readExactBytes(PROBE_LENGTH).size)
                input.size
            }
            handle.close()
            session.close()
            assertTempFolderEmpty(downloader, "$variant left objects behind")
            downloader.close()
            entry.length to streamSize
        }

        val (torrentLength, originalStream) = streamSizeWith(PikPakEngineConfig.VARIANT_ORIGINAL)
        assertEquals(torrentLength, originalStream, "the original stream is the length the torrent gave")

        // PikPak 手上到底有没有 720P: 用一个这条测自己建的文件对象去问, 引擎不参与.
        val reference = PikPakClient(
            account = username,
            password = password,
            sessionStore = InMemorySessionStore(),
            httpClient = http,
            cdnHttpClient = PikPakClient.tunedCdnClient(),
        )
        val resolved = reference.resolveMagnet(magnet) ?: error("reference lookup: PikPak does not index $magnet")
        val referenceFile = resolved.files.maxBy { it.size }
        val referenceFolder = reference.getOrCreateDeepFolderId("", "Animeko-LiveTest-Reference")
        val referenceId = reference.instantCreate(referenceFile, parentId = referenceFolder)
        val has720 = try {
            reference.getFile(referenceId).medias.any {
                !it.isOrigin && it.resolutionName == "720P" && it.video != null && it.link.url.isNotBlank()
            }
        } finally {
            reference.batchDelete(listOf(referenceId))
        }

        val (_, transcodeStream) = streamSizeWith("720P")
        println("[live] torrent $torrentLength, original $originalStream, 720P $transcodeStream (has720=$has720)")
        if (has720) {
            assertTrue(
                transcodeStream != originalStream,
                "720P served $transcodeStream bytes, the same as the original; the setting did not take",
            )
        } else {
            assertEquals(originalStream, transcodeStream, "with no 720P transcode the fallback must be the original")
        }

        http.close()
    }

    @Test
    fun `a magnet PikPak has never seen is answered quickly and negatively`() = runBlocking {
        val username = System.getenv("PIKPAK_USERNAME")
        val password = System.getenv("PIKPAK_PASSWORD")
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            println("[skip] PIKPAK_USERNAME / PIKPAK_PASSWORD not set")
            return@runBlocking
        }
        // 随机的 info hash, PikPak 不可能见过.
        val magnet = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567"

        val http = createDefaultHttpClient()
        val downloader = PikPakTorrentDownloader(
            httpClient = http,
            credentials = MutableStateFlow(PikPakCredentials(username, password)),
            sessionStore = InMemorySessionStore(),
            rootDataDirectory = SystemPaths.createTempDirectory("pikpak-engine-live-miss").resolve("pikpak"),
            config = MutableStateFlow(PikPakEngineConfig()),
            parentCoroutineContext = Dispatchers.IO,
        )

        val mark = TimeSource.Monotonic.markNow()
        assertFalse(downloader.canServe(magnet))
        println("[live] a miss took ${mark.elapsedNow()}")

        // 这是引擎快速失败的那条路, 调用方据此透明退到 anitorrent.
        val failure = runCatching { downloader.startDownload(downloader.fetchTorrent(magnet)) }.exceptionOrNull()
        assertTrue(
            failure is PikPakNotIndexedException,
            "an unindexed magnet must fail as PikPakNotIndexedException, got $failure",
        )

        downloader.close()
        http.close()
    }

    private fun countingClient(counter: AtomicInteger): HttpClient =
        createDefaultHttpClient().apply {
            plugin(HttpSend).intercept { request ->
                // CDN reads go to a different host; only control-plane traffic
                // counts, because that is what a disk restore must avoid.
                if (request.url.host.endsWith("mypikpak.com")) counter.incrementAndGet()
                execute(request)
            }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** 删除生效到列目录看不见它, 实测在几秒内. */
        val LISTING_SETTLE = 30.seconds

        const val HEAD_LENGTH = 4 * 1024 * 1024
        const val MIDDLE_LENGTH = 1024 * 1024

        /** 变体那条测只要证明字节读得出来, 不必搬一整块. */
        const val PROBE_LENGTH = 256 * 1024

        /** Align the seek so the comparison is not sensitive to piece boundaries. */
        const val BLOCK = 4096L
    }
}
