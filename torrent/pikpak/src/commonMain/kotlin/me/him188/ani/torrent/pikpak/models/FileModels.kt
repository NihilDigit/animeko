package me.him188.ani.torrent.pikpak.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from `GET /drive/v1/files/{file_id}`.
 *
 * Only the fields we actually consume are declared. Ktor's Json decoder is
 * configured with `ignoreUnknownKeys = true` so the many extra fields
 * (thumbnails, modification times, parents, etc.) are dropped.
 */
@Serializable
internal data class FileInfo(
    val id: String = "",
    val kind: String = "",
    val name: String = "",
    val size: String = "0",
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("web_content_link") val webContentLink: String = "",
    /**
     * Present only for video files. Each entry is one resolution / version;
     * `link.url` is the streaming-optimised URL (~15× bandwidth of
     * [webContentLink]).
     */
    val medias: List<PikPakMedia> = emptyList(),
)

@Serializable
internal data class PikPakMedia(
    @SerialName("media_id") val mediaId: String = "",
    @SerialName("media_name") val mediaName: String = "",
    @SerialName("resolution_name") val resolutionName: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("is_origin") val isOrigin: Boolean = false,
    val priority: Int = 0,
    val category: String = "",
    val video: VideoInfo? = null,
    val link: MediaLink? = null,
)

@Serializable
internal data class VideoInfo(
    val width: Int = 0,
    val height: Int = 0,
    val duration: Long = 0,
    @SerialName("bit_rate") val bitRate: Long = 0,
    @SerialName("frame_rate") val frameRate: Int = 0,
    @SerialName("video_codec") val videoCodec: String = "",
    @SerialName("audio_codec") val audioCodec: String = "",
)

@Serializable
internal data class MediaLink(
    val url: String = "",
    val token: String = "",
    val expire: String = "",
    val type: String = "",
    val mirrors: List<String> = emptyList(),
    val fallbacks: List<String> = emptyList(),
)
