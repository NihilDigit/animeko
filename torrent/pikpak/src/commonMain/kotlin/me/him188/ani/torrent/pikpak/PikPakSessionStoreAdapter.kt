package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.SessionStore

/**
 * Bridges the SDK's [SessionStore] to whatever refresh-token persistence the
 * app already has (typically [PikPakConfig] via DataStore). Only the refresh
 * token is persisted; the short-lived access token is recomputed on each app
 * start via a single refresh round-trip, which is cheaper than the schema
 * migration needed to widen [PikPakConfig] with accessToken/expiresAt/sub.
 *
 * [load] synthesises a [Session] with `expiresAt = 0` so the SDK treats the
 * cached access token as already stale and goes straight into refresh. If
 * [readRefreshToken] yields an empty string, we return `null` and the SDK
 * falls through to full credentials sign-in.
 */
class PikPakSessionStoreAdapter(
    private val readRefreshToken: () -> String,
    private val writeRefreshToken: suspend (String) -> Unit,
) : SessionStore {

    override suspend fun load(account: String): Session? {
        // TEMPORARY: always force fresh signin. Why:
        // The SDK's HttpEngine.requestRaw uses the HttpClient we pass in.
        // animeko's ScopedHttpClient ships with `expectSuccess = true`, so
        // when PikPak returns HTTP 400 on an expired/revoked refresh token,
        // Ktor throws ClientRequestException *before* the SDK can inspect
        // the JSON `error_code=4126` and fall through to signInLocked.
        // Result: a stale refresh_token in our DataStore wedges the engine
        // permanently.
        // Proper fix lives in pikpak-kotlin SDK: `expectSuccess = false`
        // per-request in the auth path. Until that ships, returning null
        // forces every resolve() to do a fresh signin (costs one extra
        // captcha-init + auth/signin round trip per app launch — cheap).
        return null
    }

    override suspend fun save(account: String, session: Session) {
        writeRefreshToken(session.refreshToken)
    }

    override suspend fun clear(account: String) {
        writeRefreshToken("")
    }
}
