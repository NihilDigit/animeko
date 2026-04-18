package me.him188.ani.app.ui.settings.tabs.pikpak

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_pikpak_description
import me.him188.ani.app.ui.lang.settings_pikpak_enabled
import me.him188.ani.app.ui.lang.settings_pikpak_password
import me.him188.ani.app.ui.lang.settings_pikpak_password_description
import me.him188.ani.app.ui.lang.settings_pikpak_password_hidden
import me.him188.ani.app.ui.lang.settings_pikpak_title
import me.him188.ani.app.ui.lang.settings_pikpak_username
import me.him188.ani.app.ui.lang.settings_pikpak_username_placeholder
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SwitchItem
import me.him188.ani.app.ui.settings.framework.components.TextFieldItem
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsScope.PikPakSettingsGroup(
    state: SettingsState<PikPakConfig>,
) {
    val config by state
    Group(
        title = { Text(stringResource(Lang.settings_pikpak_title)) },
        description = { Text(stringResource(Lang.settings_pikpak_description)) },
    ) {
        SwitchItem(
            checked = config.enabled,
            onCheckedChange = { state.update(config.copy(enabled = it)) },
            title = { Text(stringResource(Lang.settings_pikpak_enabled)) },
        )

        TextFieldItem(
            value = config.username,
            title = { Text(stringResource(Lang.settings_pikpak_username)) },
            placeholder = { Text(stringResource(Lang.settings_pikpak_username_placeholder)) },
            sanitizeValue = { it.trim() },
            onValueChangeCompleted = { state.update(config.copy(username = it)) },
        )

        TextFieldItem(
            value = config.password,
            title = { Text(stringResource(Lang.settings_pikpak_password)) },
            description = { Text(stringResource(Lang.settings_pikpak_password_description)) },
            exposedItem = { value ->
                Text(
                    if (value.isEmpty()) ""
                    else stringResource(Lang.settings_pikpak_password_hidden),
                )
            },
            sanitizeValue = { it },
            onValueChangeCompleted = { state.update(config.copy(password = it)) },
        )
    }
}
