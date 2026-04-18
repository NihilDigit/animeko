package me.him188.ani.torrent.pikpak

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.him188.ani.torrent.offline.ResolvedMedia
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.ktor.createDefaultHttpClient
import me.him188.ani.utils.ktor.registerLogging
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Hits the real PikPak service with credentials from env vars. Skipped in
 * CI / when creds absent.
 *
 * Env vars:
 *   PIKPAK_USERNAME
 *   PIKPAK_PASSWORD
 *   PIKPAK_MAGNET   optional; defaults to a well-seeded Arch Linux ISO magnet.
 *
 * Run with:
 *   ./gradlew :torrent:pikpak:jvmTest --tests '*PikPakLiveSmokeTest*' --info
 */
class PikPakLiveSmokeTest {

    @Test
    fun `resolve returns a playable stream url`() = runBlocking {
        val username = System.getenv("PIKPAK_USERNAME")
        val password = System.getenv("PIKPAK_PASSWORD")
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            println("[skip] PIKPAK_USERNAME / PIKPAK_PASSWORD not set")
            return@runBlocking
        }
        val magnet = System.getenv("PIKPAK_MAGNET")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MAGNET

        // Hand-build the engine the way Koin would, but stripped down so the
        // test runs without the app's DI graph.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = createDefaultHttpClient().apply { registerLogging() }
        val http = client.asScopedHttpClient()
        val credentialsFlow = MutableStateFlow(PikPakCredentials(username, password))

        val engine = PikPakOfflineDownloadEngine(
            httpClient = http,
            credentials = credentialsFlow,
            scope = scope,
            pollInterval = 3.seconds,
            resolveTimeout = 3.minutes,
        )

        println("[1/2] resolving: ${magnet.take(80)}...")
        val resolved: ResolvedMedia = engine.resolve(magnet)
        println("[2/2] resolved -> url=${resolved.streamUrl.take(120)}... fileName=${resolved.fileName} fileSize=${resolved.fileSize}")

        assertTrue(resolved.streamUrl.startsWith("http"), "streamUrl should be http(s)")

        client.close()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        // Arch Linux ISO — widely seeded, PikPak caches it, resolves in seconds.
        private const val DEFAULT_MAGNET =
            "magnet:?xt=urn:btih:157e0a57e1af0e1cfd46258ba6c62938c21b6ee8" +
                    "&dn=archlinux-2026.04.01-x86_64.iso"
    }
}
