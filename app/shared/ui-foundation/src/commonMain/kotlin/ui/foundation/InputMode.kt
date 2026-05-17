package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import me.him188.ani.utils.platform.Platform
import me.him188.ani.utils.platform.currentPlatform

/**
 * Describes the user's most recent pointer-oriented input style.
 *
 * This is intentionally separate from [Platform]:
 * - platform answers "which OS/runtime are we on?"
 * - input mode answers "how is the user interacting right now?"
 *
 * Window size and adaptive layout should not read this value. They should keep
 * using adaptive/window information instead.
 */
@Stable
enum class InputMode {
    Touch,
    Mouse,
}

/**
 * Default input mode used before the first pointer event is observed.
 *
 * The default keeps existing behavior stable:
 * - mobile platforms start as touch-first
 * - desktop platforms start as mouse-first
 *
 * Runtime pointer tracking can override this through [LocalInputMode].
 */
val Platform.defaultInputMode: InputMode
    get() = when (this) {
        is Platform.Mobile -> InputMode.Touch
        is Platform.Desktop -> InputMode.Mouse
    }

@Stable
val LocalInputMode = staticCompositionLocalOf {
    currentPlatform().defaultInputMode
}
