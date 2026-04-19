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
 *
 * [onSessionSaved] is invoked after a successful [save] — platform modules
 * hook this up to wipe the plaintext password from disk. The password is
 * only needed until we have a working refresh token; keeping it around
 * past that point would be a real liability if the DataStore file ever
 * leaks.
 */
class PikPakSessionStoreAdapter(
    private val readRefreshToken: () -> String,
    private val writeRefreshToken: suspend (String) -> Unit,
    private val onSessionSaved: suspend () -> Unit = {},
) : SessionStore {

    override suspend fun load(account: String): Session? {
        val rt = readRefreshToken()
        if (rt.isEmpty()) return null
        return Session(
            accessToken = "",
            refreshToken = rt,
            sub = "",
            expiresAt = 0L,
        )
    }

    override suspend fun save(account: String, session: Session) {
        writeRefreshToken(session.refreshToken)
        onSessionSaved()
    }

    override suspend fun clear(account: String) {
        writeRefreshToken("")
    }
}
