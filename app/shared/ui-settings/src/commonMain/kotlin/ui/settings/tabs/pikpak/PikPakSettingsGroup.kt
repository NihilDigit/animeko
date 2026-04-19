package me.him188.ani.app.ui.settings.tabs.pikpak

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_pikpak_description
import me.him188.ani.app.ui.lang.settings_pikpak_enabled
import me.him188.ani.app.ui.lang.settings_pikpak_password
import me.him188.ani.app.ui.lang.settings_pikpak_password_description
import me.him188.ani.app.ui.lang.settings_pikpak_password_hidden
import me.him188.ani.app.ui.lang.settings_pikpak_queue_description
import me.him188.ani.app.ui.lang.settings_pikpak_queue_title
import me.him188.ani.app.ui.lang.settings_pikpak_queue_unlimited
import me.him188.ani.app.ui.lang.settings_pikpak_recommend_dismiss
import me.him188.ani.app.ui.lang.settings_pikpak_recommend_message
import me.him188.ani.app.ui.lang.settings_pikpak_recommend_title
import me.him188.ani.app.ui.lang.settings_pikpak_recommend_apply
import me.him188.ani.app.ui.lang.settings_pikpak_title
import me.him188.ani.app.ui.lang.settings_pikpak_username
import me.him188.ani.app.ui.lang.settings_pikpak_username_placeholder
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SliderItem
import me.him188.ani.app.ui.settings.framework.components.SwitchItem
import me.him188.ani.app.ui.settings.framework.components.TextFieldItem
import me.him188.ani.datasources.api.source.MediaSourceKind
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsScope.PikPakSettingsGroup(
    state: SettingsState<PikPakConfig>,
    mediaSelectorSettings: SettingsState<MediaSelectorSettings>,
) {
    val config by state
    // Offer a one-shot nudge the first time the user flips PikPak on, asking
    // whether to also set MediaSelectorSettings so the default playback flow
    // actually reaches PikPak (it only resolves BT sources). The dialog is
    // fully local: if the user taps "apply", we mutate the selector settings
    // state directly, no plumbing up to the ViewModel.
    var showRecommendDialog by remember { mutableStateOf(false) }
    Group(
        title = { Text(stringResource(Lang.settings_pikpak_title)) },
        description = { Text(stringResource(Lang.settings_pikpak_description)) },
    ) {
        SwitchItem(
            checked = config.enabled,
            onCheckedChange = { newValue ->
                val wasOff = !config.enabled
                state.update(config.copy(enabled = newValue))
                if (wasOff && newValue && !isSelectorAlignedForPikPak(mediaSelectorSettings.value)) {
                    showRecommendDialog = true
                }
            },
            title = { Text(stringResource(Lang.settings_pikpak_enabled)) },
        )

        TextFieldItem(
            value = config.username,
            title = { Text(stringResource(Lang.settings_pikpak_username)) },
            placeholder = { Text(stringResource(Lang.settings_pikpak_username_placeholder)) },
            sanitizeValue = { it.trim() },
            onValueChangeCompleted = { state.update(config.copy(username = it)) },
        )

        // The password is only held until the engine signs in and stores a
        // refresh token; after that, it's wiped from disk. Keep the masked
        // placeholder visible while a refresh token is live so the user sees
        // "we're authenticated" instead of "looks like I lost my password".
        val hasLiveSession = config.refreshToken.isNotEmpty()
        TextFieldItem(
            value = config.password,
            title = { Text(stringResource(Lang.settings_pikpak_password)) },
            description = { Text(stringResource(Lang.settings_pikpak_password_description)) },
            exposedItem = { value ->
                Text(
                    if (value.isEmpty() && !hasLiveSession) ""
                    else stringResource(Lang.settings_pikpak_password_hidden),
                )
            },
            sanitizeValue = { it },
            onValueChangeCompleted = { state.update(config.copy(password = it)) },
        )

        val queueLength = config.slotQueueLength.coerceIn(1, PikPakConfig.SLOT_QUEUE_UNLIMITED)
        SliderItem(
            value = queueLength.toFloat(),
            onValueChange = { raw ->
                val rounded = raw.toInt().coerceIn(1, PikPakConfig.SLOT_QUEUE_UNLIMITED)
                if (rounded != config.slotQueueLength) {
                    state.update(config.copy(slotQueueLength = rounded))
                }
            },
            title = { Text(stringResource(Lang.settings_pikpak_queue_title)) },
            description = { Text(stringResource(Lang.settings_pikpak_queue_description)) },
            // 1..13 numeric, plus one extra stop for "unlimited" at the right
            // end — 14 discrete integer positions, so 12 steps between them.
            valueRange = 1f..PikPakConfig.SLOT_QUEUE_UNLIMITED.toFloat(),
            steps = PikPakConfig.SLOT_QUEUE_UNLIMITED - 2,
            valueLabel = {
                Text(
                    if (queueLength >= PikPakConfig.SLOT_QUEUE_UNLIMITED) stringResource(Lang.settings_pikpak_queue_unlimited)
                    else queueLength.toString(),
                )
            },
        )
    }

    if (showRecommendDialog) {
        AlertDialog(
            onDismissRequest = { showRecommendDialog = false },
            title = { Text(stringResource(Lang.settings_pikpak_recommend_title)) },
            text = { Text(stringResource(Lang.settings_pikpak_recommend_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        mediaSelectorSettings.update(
                            mediaSelectorSettings.value.copy(
                                preferKind = MediaSourceKind.BitTorrent,
                                hideSingleEpisodeForCompleted = false,
                            ),
                        )
                        showRecommendDialog = false
                    },
                ) {
                    Text(stringResource(Lang.settings_pikpak_recommend_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecommendDialog = false }) {
                    Text(stringResource(Lang.settings_pikpak_recommend_dismiss))
                }
            },
        )
    }
}

private fun isSelectorAlignedForPikPak(s: MediaSelectorSettings): Boolean =
    s.preferKind == MediaSourceKind.BitTorrent && !s.hideSingleEpisodeForCompleted
