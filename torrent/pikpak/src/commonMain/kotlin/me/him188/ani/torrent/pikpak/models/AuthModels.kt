package me.him188.ani.torrent.pikpak.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class CaptchaInitRequest(
    val client_id: String,
    val action: String,
    val device_id: String,
    val meta: Map<String, String>,
)

@Serializable
internal data class CaptchaInitResponse(
    val captcha_token: String = "",
    val expires_in: Long = 0,
    val url: String? = null,
)

@Serializable
internal data class SigninRequest(
    val client_id: String,
    val client_secret: String,
    val username: String,
    val password: String,
    val captcha_token: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class RefreshTokenRequest(
    val client_id: String,
    val client_secret: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val grant_type: String = "refresh_token",
    val refresh_token: String,
)

@Serializable
internal data class AuthTokenResponse(
    val token_type: String = "Bearer",
    val access_token: String = "",
    val refresh_token: String = "",
    val expires_in: Long = 0,
    val sub: String = "",
)

@Serializable
internal data class PikPakErrorResponse(
    val error: String = "",
    val error_code: Int = 0,
    val error_description: String = "",
    @SerialName("error_url") val errorUrl: String? = null,
)
