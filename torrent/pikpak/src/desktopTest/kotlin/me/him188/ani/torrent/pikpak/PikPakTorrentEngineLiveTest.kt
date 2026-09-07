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
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * End-to-end against the real service, skipped when credentials are absent.
 *
 * Two claims are checked, because they are the two the design rests on:
 *  1. Bytes served through the transport are the bytes PikPak has. The
 *     comparison is against the SDK reading the same ranges straight off the
 *     signed URL, so a block-offset or variant-length mistake shows up as a
 *     digest mismatch rather than as corrupted playback later.
 *  2. Reopening a session rebuilds itself from `meta.json` without asking
 *     PikPak anything. The request counter makes that assertion sharp: it is
 *     zero API calls, not "few".
 *
 * Env vars: PIKPAK_USERNAME, PIKPAK_PASSWORD. The build script maps them from
 * local.properties, so:
 *
 *     ./gradlew.bat :torrent:pikpak:desktopTest --tests '*PikPakTorrentEngineLiveTest*'
 */
class PikPakTorrentEngineLiveTest {

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
        // A fresh root forces the first session down the cloud-indexing path
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
        val encoded = first.fetchTorrent(magnet)

        val startedAt = TimeSource.Monotonic.markNow()
        val session = withTimeout(8.minutes) { first.startDownload(encoded) }
        val indexed = startedAt.elapsedNow()
        val entry = session.getFiles().maxBy { it.length }
        println("[live] indexed in $indexed: ${session.getName()} -> ${entry.pathInTorrent} (${entry.length} bytes)")

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

        // Same ranges, straight off the signed URL, with none of the engine in
        // between.
        val fileId = (entry as PikPakFileEntry).meta.fileId
        val reference = PikPakClient(
            account = username,
            password = password,
            sessionStore = InMemorySessionStore(),
            httpClient = http,
            cdnHttpClient = PikPakClient.tunedCdnClient(),
        )
        val url = reference.getFile(fileId).downloadUrl
            ?: error("reference lookup: file $fileId has no octet-stream link")
        assertEquals(
            sha256(head),
            sha256(reference.rangeBytes(url, 0, HEAD_LENGTH)),
            "the first $HEAD_LENGTH bytes differ from what PikPak serves",
        )
        assertEquals(
            sha256(middle),
            sha256(reference.rangeBytes(url, middleOffset, MIDDLE_LENGTH)),
            "the $MIDDLE_LENGTH bytes at $middleOffset differ from what PikPak serves",
        )

        // Streaming writes nothing: a viewer who watches an episode without
        // caching it must leave no file behind, which is the whole difference
        // between this engine and the one it replaced.
        assertEquals(
            0L,
            entry.fileStats.first().downloadedBytes,
            "playback must not have downloaded anything to disk",
        )

        handle.close()
        session.close()
        first.close()

        // Second session, same JVM, same data root. The engine must rebuild
        // its state from meta.json and the piece bitmaps alone.
        val callsBeforeRestore = apiCalls.get()
        val second = downloader()
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

    private fun countingClient(counter: AtomicInteger): HttpClient =
        createDefaultHttpClient().apply {
            plugin(HttpSend).intercept { request ->
                // CDN reads go to a different host; only control-plane traffic
                // counts, because that is what a disk restore must avoid.
                if (request.url.host.endsWith("mypikpak.com")) counter.incrementAndGet()
                execute(request)
            }
        }

    private suspend fun PikPakClient.rangeBytes(url: String, start: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        streamRangeFromUrl(url, start, length.toLong()) { stream ->
            var filled = 0
            while (filled < out.size) {
                val n = stream.channel.readAvailable(out, filled, out.size - filled)
                if (n == -1) break
                filled += n
            }
            check(filled == out.size) { "reference read got $filled of $length bytes at $start" }
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val HEAD_LENGTH = 4 * 1024 * 1024
        const val MIDDLE_LENGTH = 1024 * 1024

        /** Align the seek so the comparison is not sensitive to piece boundaries. */
        const val BLOCK = 4096L
    }
}
