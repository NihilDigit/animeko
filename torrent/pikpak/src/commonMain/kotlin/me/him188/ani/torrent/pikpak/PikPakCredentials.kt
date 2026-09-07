/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

/**
 * Credentials + enabled flag for the PikPak engine. Emitted by the app-level
 * SettingsRepository wrapper so the engine doesn't know about DataStore.
 *
 * [password] may be empty when the session store already holds a usable
 * refresh token. The SDK will try the refresh path first and only fall back
 * to signin-with-password if refresh fails; this lets us stop persisting the
 * plaintext password once we've bootstrapped a session.
 */
data class PikPakCredentials(
    val username: String,
    val password: String,
) {
    val isValid: Boolean get() = username.isNotEmpty()
}
