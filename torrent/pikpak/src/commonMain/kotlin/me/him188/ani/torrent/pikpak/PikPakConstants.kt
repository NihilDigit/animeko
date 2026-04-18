package me.him188.ani.torrent.pikpak

/**
 * Constants replicating the reference Python SDK (Quan666/PikPakAPI v0.1.11)
 * which is confirmed working against today's live PikPak service.
 *
 * The magic values identify the PikPak Android client to their auth server.
 * They are widely distributed in every community SDK; the server accepts any
 * sane combination.
 */
internal object PikPakConstants {
    const val USER_HOST = "user.mypikpak.com"
    const val API_HOST = "api-drive.mypikpak.com"

    const val CLIENT_ID = "YNxT9w7GMdWvEOKa"
    const val CLIENT_SECRET = "dbw2OtmVEeuUvIptb1Coyg"
    const val CLIENT_VERSION = "1.47.1"
    const val PACKAGE_NAME = "com.pikcloud.pikpak"

    const val USER_AGENT =
        "ANDROID-$PACKAGE_NAME/$CLIENT_VERSION"

    /**
     * Salts applied iteratively in [PikPakAuth.captchaSign]. Copied verbatim
     * from the reference Python SDK (Quan666/PikPakAPI `utils.SALTS`), which
     * in turn reproduces what the PikPak Android app does in its captcha_sign
     * JavaScript stub.
     */
    val SALTS: List<String> = listOf(
        "Gez0T9ijiI9WCeTsKSg3SMlx",
        "zQdbalsolyb1R/",
        "ftOjr52zt51JD68C3s",
        "yeOBMH0JkbQdEFNNwQ0RI9T3wU/v",
        "BRJrQZiTQ65WtMvwO",
        "je8fqxKPdQVJiy1DM6Bc9Nb1",
        "niV",
        "9hFCW2R1",
        "sHKHpe2i96",
        "p7c5E6AcXQ/IJUuAEC9W6",
        "",
        "aRv9hjc9P+Pbn+u3krN6",
        "BzStcgE8qVdqjEH16l4",
        "SqgeZvL5j9zoHP95xWHt",
        "zVof5yaJkPe3VFpadPof",
    )
}
