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
import io.github.nihildigit.pikpak.MagnetResource
import io.github.nihildigit.pikpak.ResolvedFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.SystemPaths
import me.him188.ani.utils.io.createTempDirectory
import me.him188.ani.utils.io.resolve
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 磁链解析出来的清单决定了这个引擎供不供得了一条资源, 以及会话里有哪些文件.
 *
 * 解析结果不缓存: 播放先问 [PikPakTorrentDownloader.canServe] 再
 * [PikPakTorrentDownloader.startDownload], 两次各解析一次. 缓存要么记着一条后来才被索引进来的
 * 磁链说它没索引, 要么就得挑一个过期时间, 而重问一次是 146 ms 的纯查询.
 */
class MagnetResolveTest {
    private val magnet = "magnet:?xt=urn:btih:157e0a57e1af0e1cfd46258ba6c62938c21b6ee8&dn=pack"

    private fun downloader(
        root: SystemPath,
        resolve: suspend (uri: String) -> MagnetResource?,
    ) = PikPakTorrentDownloader(
        httpClient = HttpClient(MockEngine { error("this test resolves magnets through the injected resolver") }),
        credentials = MutableStateFlow(PikPakCredentials("nobody@example.com", "unused")),
        sessionStore = InMemorySessionStore(),
        rootDataDirectory = root,
        config = MutableStateFlow(PikPakEngineConfig()),
        parentCoroutineContext = Dispatchers.IO,
    ).also {
        it.magnetResolver = resolve
    }

    @Test
    fun `asking twice answers the same, and the session is built from a fresh resolve`() = runBlocking {
        val calls = AtomicInteger(0)
        val root = SystemPaths.createTempDirectory("pikpak-resolve").resolve("pikpak")
        val downloader = downloader(root) {
            calls.incrementAndGet()
            MagnetResource("pack", listOf(ResolvedFile("01.mkv", 8192, "GCID-1")))
        }

        assertTrue(downloader.canServe(magnet))
        assertTrue(downloader.canServe(magnet))
        val session = downloader.startDownload(downloader.fetchTorrent(magnet))

        assertEquals(3, calls.get(), "each question goes out on its own; nothing here may be memoized")
        assertEquals(listOf("01.mkv"), session.getFiles().map { it.pathInTorrent })

        session.close()
        downloader.close()
    }

    @Test
    fun `an unindexed magnet answers false and then fails as not indexed`() = runBlocking {
        val root = SystemPaths.createTempDirectory("pikpak-resolve-miss").resolve("pikpak")
        val downloader = downloader(root) { null }

        assertFalse(downloader.canServe(magnet))
        // 类型要能和网络故障区分开: 调用方据此透明退到 anitorrent, 而网络故障值得重试.
        val failure = runCatching { downloader.startDownload(downloader.fetchTorrent(magnet)) }.exceptionOrNull()
        assertIs<PikPakNotIndexedException>(failure)

        downloader.close()
    }

    @Test
    fun `a torrent whose files are all unindexed is treated as a miss`() = runBlocking {
        // resolveMagnet 保留 gcid 为 null 的条目, 因为按名字挑集数要看到整棵树. 但一个都建不出
        // 文件对象时, 这和"没有索引"是一个意思, 不能做成一个每个文件都读不动的会话.
        val root = SystemPaths.createTempDirectory("pikpak-resolve-nogcid").resolve("pikpak")
        val downloader = downloader(root) {
            MagnetResource("pack", listOf(ResolvedFile("01.mkv", 8192, null)))
        }

        val failure = runCatching { downloader.startDownload(downloader.fetchTorrent(magnet)) }.exceptionOrNull()
        assertIs<PikPakNotIndexedException>(failure)

        downloader.close()
    }

    @Test
    fun `a partially indexed pack keeps its unindexed files in the listing`() = runBlocking {
        // 少列一个文件, "PikPak 没索引这一集"就变成了"种子里没有这一集", 而后者让选择器抛
        // NO_MATCHING_FILE, 那个异常不回退, BT 放得出来的一集就彻底播不了.
        val root = SystemPaths.createTempDirectory("pikpak-resolve-partial").resolve("pikpak")
        val downloader = downloader(root) {
            MagnetResource(
                "pack",
                listOf(
                    ResolvedFile("01.mkv", 8192, "GCID-1"),
                    ResolvedFile("02.mkv", 8192, null),
                ),
            )
        }

        val session = downloader.startDownload(downloader.fetchTorrent(magnet))
        assertEquals(
            listOf("01.mkv", "02.mkv"),
            session.getFiles().map { it.pathInTorrent },
            "the unindexed episode must stay in the listing",
        )

        session.close()
        downloader.close()
    }

    @Test
    fun `a partially indexed pack is left to BT`() = runBlocking {
        // 清单里留着没索引的那一集是对的(见上一个测试), 但选引擎的这一问不知道最终要读哪个文件.
        // 读到那一集时 CloudFile 抛 NotIndexed, 播放会回退而缓存不会——缓存那条路没有运行时回退.
        val root = SystemPaths.createTempDirectory("pikpak-canserve-partial").resolve("pikpak")
        val downloader = downloader(root) {
            MagnetResource(
                "pack",
                listOf(
                    ResolvedFile("01.mkv", 8192, "GCID-1"),
                    ResolvedFile("02.mkv", 8192, null),
                ),
            )
        }

        assertFalse(downloader.canServe(magnet), "a pack missing one gcid must go to BT whole")

        downloader.close()
    }

    @Test
    fun `an unindexed non-video file does not push the pack to BT`() = runBlocking {
        // 判据只看视频条目: 最终被选中播放或缓存的一定是视频文件, 一个没索引的 NFO 或者 sample
        // 不影响这个包能不能用.
        val root = SystemPaths.createTempDirectory("pikpak-canserve-nonvideo").resolve("pikpak")
        val downloader = downloader(root) {
            MagnetResource(
                "pack",
                listOf(
                    ResolvedFile("01.mkv", 8192, "GCID-1"),
                    ResolvedFile("01.nfo", 1024, null),
                    ResolvedFile("sample/sample.txt", 512, null),
                ),
            )
        }

        assertTrue(downloader.canServe(magnet))

        downloader.close()
    }

    @Test
    fun `a torrent with no video at all is refused`() = runBlocking {
        val root = SystemPaths.createTempDirectory("pikpak-canserve-novideo").resolve("pikpak")
        val downloader = downloader(root) {
            MagnetResource("pack", listOf(ResolvedFile("readme.txt", 1024, "GCID-1")))
        }

        assertFalse(downloader.canServe(magnet))

        downloader.close()
    }
}
