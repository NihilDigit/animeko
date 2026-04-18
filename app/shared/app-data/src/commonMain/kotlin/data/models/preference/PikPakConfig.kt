package me.him188.ani.app.data.models.preference

import kotlinx.serialization.Serializable

/**
 * User-configurable settings for the PikPak offline-download backend.
 */
@Serializable
data class PikPakConfig(
    val enabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    /**
     * Reserved for the "re-seed PikPak files back to the BT swarm" feature
     * (V1.1). Not consumed by the MVP engine.
     */
    val defaultFolderId: String? = null,
) {
    companion object {
        val Default = PikPakConfig()
    }
}
