package me.him188.ani.torrent.pikpak

import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.encodeToByteString
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.digest
import kotlin.time.Clock
import kotlin.time.Instant
import me.him188.ani.torrent.offline.OfflineDownloadAuthException
import me.him188.ani.torrent.pikpak.models.AuthTokenResponse
import me.him188.ani.torrent.pikpak.models.CaptchaInitRequest
import me.him188.ani.torrent.pikpak.models.CaptchaInitResponse
import me.him188.ani.torrent.pikpak.models.RefreshTokenRequest
import me.him188.ani.torrent.pikpak.models.SigninRequest
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Holds PikPak auth state (access token, refresh token, expiry) and implements
 * the captcha → signin → refresh flow.
 *
 * Constructed with credentials; re-signs transparently when the token expires.
 * Not thread-safe beyond the internal mutex — callers should share one instance
 * per account.
 */
internal class PikPakAuth(
    private val httpClient: ScopedHttpClient,
    private val username: String,
    private val password: String,
    val deviceId: String = generateDeviceId(),
    private val clock: Clock = Clock.System,
) {
    private val logger = logger<PikPakAuth>()
    private val mutex = Mutex()

    @Volatile private var accessToken: String = ""
    @Volatile private var refreshToken: String = ""
    @Volatile private var tokenExpiresAt: Instant = Instant.DISTANT_PAST
    @Volatile private var userId: String = ""

    /**
     * The most recent captcha token from [captchaInit]. PikPak requires this
     * as an `X-Captcha-Token` header on write requests like offline-task submit.
     */
    @Volatile var captchaToken: String = ""
        private set

    /**
     * Returns a valid bearer token, doing a login or refresh if needed.
     * Thread-safe; serialises concurrent logins behind a mutex.
     */
    suspend fun getAccessToken(): String {
        if (accessToken.isNotEmpty() && clock.now() < tokenExpiresAt - REFRESH_BEFORE_EXPIRY) {
            return accessToken
        }
        return mutex.withLock {
            // Double-check after acquiring the lock.
            if (accessToken.isNotEmpty() && clock.now() < tokenExpiresAt - REFRESH_BEFORE_EXPIRY) {
                return@withLock accessToken
            }
            if (refreshToken.isNotEmpty()) {
                try {
                    doRefresh()
                    return@withLock accessToken
                } catch (e: Exception) {
                    logger.warn(e) { "Refresh token failed, falling back to full signin" }
                }
            }
            doSignin()
            accessToken
        }
    }

    /** Force a fresh signin on next [getAccessToken] call. */
    fun invalidate() {
        accessToken = ""
        tokenExpiresAt = Instant.DISTANT_PAST
    }

    private suspend fun doSignin() {
        val loginUrl = "https://${PikPakConstants.USER_HOST}/v1/auth/signin"
        val captchaToken = captchaInit(action = "POST:$loginUrl", meta = detectMetaFromUsername())

        val response = httpClient.use {
            post(loginUrl) {
                expectSuccess = false
                headers { standardHeaders() }
                contentType(ContentType.Application.Json)
                setBody(
                    SigninRequest(
                        client_id = PikPakConstants.CLIENT_ID,
                        client_secret = PikPakConstants.CLIENT_SECRET,
                        username = username,
                        password = password,
                        captcha_token = captchaToken,
                    ),
                )
            }
        }
        if (!response.status.isSuccess()) {
            throw OfflineDownloadAuthException(
                "PikPak signin rejected: ${response.status} ${response.bodyAsText().take(200)}",
            )
        }
        val body = response.body<AuthTokenResponse>()
        if (body.access_token.isEmpty()) {
            throw OfflineDownloadAuthException("PikPak signin returned empty access_token")
        }
        acceptToken(body)
        logger.info { "PikPak signin ok, user_id=$userId, expires_in=${body.expires_in}s" }
    }

    private suspend fun doRefresh() {
        val url = "https://${PikPakConstants.USER_HOST}/v1/auth/token"
        val response = httpClient.use {
            post(url) {
                expectSuccess = false
                headers { standardHeaders() }
                contentType(ContentType.Application.Json)
                setBody(
                    RefreshTokenRequest(
                        client_id = PikPakConstants.CLIENT_ID,
                        client_secret = PikPakConstants.CLIENT_SECRET,
                        refresh_token = refreshToken,
                    ),
                )
            }
        }
        if (!response.status.isSuccess()) {
            throw OfflineDownloadAuthException(
                "PikPak refresh rejected: ${response.status}",
            )
        }
        val body = response.body<AuthTokenResponse>()
        if (body.access_token.isEmpty()) {
            throw OfflineDownloadAuthException("PikPak refresh returned empty access_token")
        }
        acceptToken(body)
    }

    private suspend fun captchaInit(action: String, meta: Map<String, String>): String {
        val url = "https://${PikPakConstants.USER_HOST}/v1/shield/captcha/init"
        val response = httpClient.use {
            post(url) {
                expectSuccess = false
                headers { standardHeaders() }
                contentType(ContentType.Application.Json)
                setBody(
                    CaptchaInitRequest(
                        client_id = PikPakConstants.CLIENT_ID,
                        action = action,
                        device_id = deviceId,
                        meta = meta,
                    ),
                )
            }
        }
        if (!response.status.isSuccess()) {
            val snippet = runCatching { response.bodyAsText().take(400) }.getOrDefault("")
            throw OfflineDownloadAuthException(
                "PikPak captcha_init rejected: ${response.status} body=$snippet",
            )
        }
        val body = response.body<CaptchaInitResponse>()
        if (body.captcha_token.isEmpty()) {
            throw OfflineDownloadAuthException(
                "PikPak captcha_init returned no token (challenge required?) url=${body.url}",
            )
        }
        captchaToken = body.captcha_token
        return body.captcha_token
    }

    /**
     * Refresh the captcha_token for a specific write/read action (e.g.
     * submitting an offline task). Unlike the login captcha_init which takes
     * `{email|phone|username}` meta, non-login actions require the
     * device-identifying meta (`captcha_sign` + client/package/user/timestamp).
     */
    suspend fun ensureCaptchaFor(action: String) {
        mutex.withLock {
            captchaInit(action = action, meta = buildClientInfoMeta())
        }
    }

    private fun buildClientInfoMeta(): Map<String, String> {
        val timestamp = clock.now().toEpochMilliseconds().toString()
        return mapOf(
            "captcha_sign" to captchaSign(deviceId, timestamp),
            "client_version" to PikPakConstants.CLIENT_VERSION,
            "package_name" to PikPakConstants.PACKAGE_NAME,
            "user_id" to userId,
            "timestamp" to timestamp,
        )
    }

    /**
     * PikPak's captcha signature algorithm. Matches the reference Python SDK:
     *   seed = CLIENT_ID + CLIENT_VERSION + PACKAGE_NAME + device_id + timestamp
     *   for each salt in SALTS: seed = md5hex(seed + salt)
     *   return "1." + seed
     * The `"1."` prefix is a version tag required by the server.
     */
    private fun captchaSign(deviceId: String, timestamp: String): String {
        var sign = PikPakConstants.CLIENT_ID +
                PikPakConstants.CLIENT_VERSION +
                PikPakConstants.PACKAGE_NAME +
                deviceId +
                timestamp
        for (salt in PikPakConstants.SALTS) {
            sign = md5Hex(sign + salt)
        }
        return "1.$sign"
    }

    private fun md5Hex(input: String): String {
        val bytes = input.encodeToByteString().digest(DigestAlgorithm.MD5)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun io.ktor.http.HeadersBuilder.standardHeaders() {
        append(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
        append("X-Client-Id", PikPakConstants.CLIENT_ID)
        append("X-Client-Version", PikPakConstants.CLIENT_VERSION)
        append("X-Device-Id", deviceId)
    }

    private fun detectMetaFromUsername(): Map<String, String> = when {
        EMAIL_REGEX.matches(username) -> mapOf("email" to username)
        PHONE_REGEX.matches(username) -> mapOf("phone_number" to username)
        else -> mapOf("username" to username)
    }

    private fun acceptToken(token: AuthTokenResponse) {
        accessToken = token.access_token
        refreshToken = token.refresh_token.ifEmpty { refreshToken }
        tokenExpiresAt = clock.now() + token.expires_in.seconds
        if (token.sub.isNotEmpty()) userId = token.sub
    }

    companion object {
        private val REFRESH_BEFORE_EXPIRY = 60.seconds
        private val EMAIL_REGEX = Regex("""[\w.+-]+@\w+(\.\w+)+""")
        private val PHONE_REGEX = Regex("""\d{11,18}""")

        fun generateDeviceId(): String = buildString(32) {
            repeat(32) { append(Random.nextInt(16).toString(16)) }
        }
    }
}
