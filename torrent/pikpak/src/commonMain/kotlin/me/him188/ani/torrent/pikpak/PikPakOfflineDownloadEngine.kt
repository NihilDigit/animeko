package me.him188.ani.torrent.pikpak

import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import kotlin.time.Instant
import me.him188.ani.torrent.offline.OfflineDownloadEngine
import me.him188.ani.torrent.offline.OfflineDownloadRejectedException
import me.him188.ani.torrent.offline.ResolvedMedia
import me.him188.ani.torrent.pikpak.models.FileInfo
import me.him188.ani.torrent.pikpak.models.OfflineTask
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Credentials + enabled flag for the PikPak engine. Emitted by the app-level
 * SettingsRepository wrapper so the engine doesn't know about DataStore.
 */
data class PikPakCredentials(
    val username: String,
    val password: String,
) {
    val isValid: Boolean get() = username.isNotEmpty() && password.isNotEmpty()
}

/**
 * The PikPak implementation of [OfflineDownloadEngine].
 *
 * Accepts a [StateFlow] of current credentials (null when the user has
 * disabled the integration). On each [resolve] call it reuses an in-memory
 * [PikPakAuth] for the current credentials so bearer tokens can be cached
 * across resolves within the same account.
 */
class PikPakOfflineDownloadEngine(
    private val httpClient: ScopedHttpClient,
    private val credentials: StateFlow<PikPakCredentials?>,
    scope: CoroutineScope,
    private val pollInterval: kotlin.time.Duration = 5.seconds,
    private val resolveTimeout: kotlin.time.Duration = 5.minutes,
) : OfflineDownloadEngine {

    private val logger = logger<PikPakOfflineDownloadEngine>()

    override val id: String = "pikpak"
    override val displayName: String = "PikPak"

    override val isSupported: StateFlow<Boolean> = credentials
        .map { it != null && it.isValid }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = credentials.value?.isValid == true)

    @Volatile
    private var authEntry: Pair<PikPakCredentials, PikPakAuth>? = null

    override suspend fun resolve(uri: String): ResolvedMedia = withTimeout(resolveTimeout) {
        val creds = credentials.value
            ?: throw me.him188.ani.torrent.offline.OfflineDownloadAuthException("PikPak not configured")
        if (!creds.isValid) {
            throw me.him188.ani.torrent.offline.OfflineDownloadAuthException("PikPak credentials incomplete")
        }
        val client = PikPakClient(httpClient, authFor(creds))

        logger.info { "[pikpak] submit offline task for ${uri.take(60)}..." }
        val task = client.submitOfflineTask(uri, name = deriveTaskName(uri))
        logger.debug { "[pikpak] submitted task id=${task.id} file_id=${task.fileId} file_name=${task.fileName}" }

        val fileId = awaitCompletion(client, task)
        val fileInfo = client.getFileInfo(fileId)
        buildResolvedMedia(fileInfo)
    }

    /**
     * PikPak requires a non-empty `name` on the submit request. The server
     * overwrites it with the torrent's real filename once metadata resolves,
     * so the only constraint is "non-empty". We try to be useful:
     *   1. For magnet URIs: use the `dn=` display name if present.
     *   2. For HTTP .torrent URLs: use the basename of the path.
     *   3. Fallback: a short placeholder — never empty.
     */
    internal fun deriveTaskName(uri: String): String {
        if (uri.startsWith("magnet:", ignoreCase = true)) {
            val dn = Regex("[?&]dn=([^&]*)").find(uri)?.groupValues?.get(1)
                ?.let { runCatching { it.decodeURLQueryComponent() }.getOrDefault(it) }
            if (!dn.isNullOrBlank()) return dn
            val infoHash = Regex("xt=urn:btih:([A-Za-z0-9]+)").find(uri)?.groupValues?.get(1)
            if (!infoHash.isNullOrBlank()) return "magnet-$infoHash"
        } else {
            val tail = uri.substringAfterLast('/').substringBefore('?')
            if (tail.isNotBlank()) return tail
        }
        return "ani-offline-task"
    }

    private fun authFor(creds: PikPakCredentials): PikPakAuth {
        val current = authEntry
        if (current != null && current.first == creds) return current.second
        val fresh = PikPakAuth(httpClient, creds.username, creds.password)
        authEntry = creds to fresh
        return fresh
    }

    private suspend fun awaitCompletion(
        client: PikPakClient,
        initialTask: OfflineTask,
    ): String {
        var fileId = initialTask.fileId
        var attempt = 0
        // Include PENDING so a freshly queued task doesn't look "already gone"
        // and trip the "Task completed but no file_id" branch below.
        val activePhases = "PHASE_TYPE_PENDING,PHASE_TYPE_RUNNING,PHASE_TYPE_ERROR"
        while (true) {
            delay(pollInterval)
            attempt++
            val list = client.listOfflineTasks(phaseFilter = activePhases)
            val match = list.tasks.firstOrNull { it.id == initialTask.id }
            if (match == null) {
                // Task left the PENDING/RUNNING/ERROR filter => completed.
                logger.info { "[pikpak] task ${initialTask.id} completed after $attempt polls" }
                return fileId.ifEmpty {
                    throw OfflineDownloadRejectedException(
                        "Task completed but no file_id was observed; re-submit may be needed",
                    )
                }
            }
            if (match.fileId.isNotEmpty()) fileId = match.fileId
            if (match.phase == "PHASE_TYPE_ERROR") {
                throw OfflineDownloadRejectedException(
                    "PikPak task failed: phase=${match.phase} message=${match.message}",
                )
            }
            logger.debug {
                "[pikpak] poll $attempt: phase=${match.phase} progress=${match.progress} file_id=$fileId"
            }
        }
    }

    private fun buildResolvedMedia(file: FileInfo): ResolvedMedia {
        // Prefer a media link (CDN streaming-rate) over web_content_link.
        // Within medias, prefer is_default, then highest priority, then is_origin.
        val primary = file.medias
            .filter { it.link?.url?.isNotEmpty() == true }
            .sortedWith(
                compareByDescending<me.him188.ani.torrent.pikpak.models.PikPakMedia> { it.isDefault }
                    .thenByDescending { it.priority }
                    .thenByDescending { it.isOrigin },
            )
            .firstOrNull()

        val streamUrl = primary?.link?.url
            ?: file.webContentLink.takeIf { it.isNotEmpty() }
            ?: throw OfflineDownloadRejectedException(
                "PikPak file has no playable link (file_id=${file.id})",
            )

        val expiresAt: Instant? = primary?.link?.expire
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

        return ResolvedMedia(
            streamUrl = streamUrl,
            expiresAt = expiresAt,
            fileName = file.name.takeIf { it.isNotEmpty() },
            fileSize = file.size.toLongOrNull(),
        )
    }
}
