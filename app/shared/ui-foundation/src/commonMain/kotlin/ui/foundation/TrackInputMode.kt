package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Tracks pointer events and reports the input mode implied by the latest event.
 *
 * This modifier should normally be installed once near the app root. Consumers
 * should read [LocalInputMode] instead of each component detecting pointer type
 * on its own.
 */
fun Modifier.trackInputMode(
    onInputModeChange: (InputMode) -> Unit,
): Modifier = composed {
    val onInputModeChangeState by rememberUpdatedState(onInputModeChange)

    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val inputMode = event.changes.firstNotNullOfOrNull { change ->
                    change.type.toInputModeOrNull()
                } ?: continue

                onInputModeChangeState(inputMode)
            }
        }
    }
}

private fun PointerType.toInputModeOrNull(): InputMode? {
    return when (this) {
        PointerType.Mouse -> InputMode.Mouse
        PointerType.Touch,
        PointerType.Stylus,
        PointerType.Eraser -> InputMode.Touch

        PointerType.Unknown -> null
        else -> null
    }
}
