package me.him188.ani.torrent.pikpak.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `POST /drive/v1/files` when adding an offline download.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SubmitOfflineTaskRequest(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val kind: String = "drive#file",
    /**
     * Display name for the task. PikPak rejects requests with empty/missing
     * `name` ("file_name_empty"), so callers must supply a non-empty string —
     * the server later replaces it with the real filename once metadata is
     * retrieved, so any placeholder is fine.
     */
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("upload_type") val uploadType: String = "UPLOAD_TYPE_URL",
    val url: UrlUpload,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("folder_type") val folderType: String = "DOWNLOAD",
    @SerialName("parent_id") val parentId: String? = null,
) {
    @Serializable
    data class UrlUpload(val url: String)
}

/**
 * Response from `POST /drive/v1/files`: contains the submitted task.
 * `task.file_id` may initially be empty; becomes populated as the task progresses.
 */
@Serializable
internal data class SubmitOfflineTaskResponse(
    @SerialName("upload_type") val uploadType: String = "",
    val task: OfflineTask? = null,
)

/**
 * Response from `GET /drive/v1/tasks`.
 */
@Serializable
internal data class TaskListResponse(
    val tasks: List<OfflineTask> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
)

/**
 * A single offline download task.
 *
 * Observations from live smoke tests:
 * - [phase] is one of `PHASE_TYPE_PENDING`, `PHASE_TYPE_RUNNING`,
 *   `PHASE_TYPE_COMPLETE`, `PHASE_TYPE_ERROR`. When polling the RUNNING+ERROR
 *   filtered list, a task disappearing from the list means it's COMPLETE.
 * - [fileId] empty until the task is further along; always populated once
 *   metadata has been fetched.
 * - [fileSize] comes as a string (bytes), per PikPak's convention of
 *   quoting large integers.
 */
@Serializable
internal data class OfflineTask(
    val id: String = "",
    val kind: String = "",
    val name: String = "",
    val type: String = "",
    @SerialName("user_id") val userId: String = "",
    val phase: String = "",
    val progress: Int = 0,
    val message: String = "",
    @SerialName("status_size") val statusSize: Int = 0,
    val params: Map<String, String> = emptyMap(),
    @SerialName("file_id") val fileId: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size") val fileSize: String = "0",
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("updated_time") val updatedTime: String? = null,
)
