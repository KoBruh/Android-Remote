package com.androidremote.app.ui.remote

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalView
import com.androidremote.app.data.Settings
import com.androidremote.app.tv.KeyDirection
import com.androidremote.app.ui.theme.Tokens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What happens when a button is held down. */
enum class HoldBehavior {
    /** Nothing: the button only taps. */
    None,

    /** Holding sends a long press: [PressEvent.LongStart] when the hold begins, [PressEvent.LongEnd] on release. */
    LongPress,

    /** Holding repeats the tap: [PressEvent.Repeat] at a steady rate until release. */
    Repeat,
}

/** What a button reports while it is being pressed. */
enum class PressEvent {
    /** A finger went down on the button. */
    Down,

    /** The button was released before a hold started. */
    Tap,

    /** A hold started on a [HoldBehavior.LongPress] button. */
    LongStart,

    /** A hold ended on a [HoldBehavior.LongPress] button. */
    LongEnd,

    /** Sent repeatedly while a [HoldBehavior.Repeat] button is held. */
    Repeat,

    /** The finger left the button, however the press went. */
    Up,
}

/** How a key button's press event goes out to the TV, or null if the event sends nothing. */
fun PressEvent.toKeyDirection(): KeyDirection? = when (this) {
    PressEvent.Tap, PressEvent.Repeat -> KeyDirection.SHORT
    PressEvent.LongStart -> KeyDirection.START_LONG
    PressEvent.LongEnd -> KeyDirection.END_LONG
    PressEvent.Down, PressEvent.Up -> null
}

/** Plays the haptic tick for a button press. Provide it with [rememberHapticTick]. */
val LocalHapticTick = staticCompositionLocalOf<() -> Unit> { {} }

/** The haptic tick for the current haptics settings. */
@Composable
fun rememberHapticTick(settings: Settings): () -> Unit {
    val view = LocalView.current
    return remember(settings.hapticsEnabled, settings.hapticStrength, view) {
        val feedback = Tokens.hapticFeedback(settings.hapticStrength)
        val tick: () -> Unit = { if (settings.hapticsEnabled) view.performHapticFeedback(feedback) }
        tick
    }
}

/**
 * Press detection shared by every button and the D-pad.
 *
 * @param target which control is under a touch position, or null if none (the touch is ignored).
 * @param hold how [target]'s control reacts to being held.
 * @param onPressed the control being pressed right now, or null. Drives the pressed overlay.
 */
suspend fun <T : Any> PointerInputScope.detectPresses(
    target: (Offset) -> T?,
    hold: (T) -> HoldBehavior,
    onPressed: (T?) -> Unit,
    onEvent: (T, PressEvent) -> Unit,
) = coroutineScope {
    awaitEachGesture {
        val down = awaitFirstDown()
        val pressed = target(down.position) ?: return@awaitEachGesture
        down.consume()
        val behavior = hold(pressed)
        var held = false
        onPressed(pressed)
        onEvent(pressed, PressEvent.Down)
        val holdTimer = launch {
            if (behavior == HoldBehavior.None) return@launch
            delay(Tokens.HoldDelayMs)
            held = true
            if (behavior == HoldBehavior.LongPress) {
                onEvent(pressed, PressEvent.LongStart)
            } else {
                while (true) {
                    onEvent(pressed, PressEvent.Repeat)
                    delay(Tokens.RepeatIntervalMs)
                }
            }
        }
        val up = try {
            waitForUpOrCancellation()
        } finally {
            holdTimer.cancel()
            onPressed(null)
            // A long press that has started must always be ended, even if the gesture is cancelled.
            if (held && behavior == HoldBehavior.LongPress) onEvent(pressed, PressEvent.LongEnd)
            onEvent(pressed, PressEvent.Up)
        }
        if (up != null) {
            up.consume()
            if (!held) onEvent(pressed, PressEvent.Tap)
        }
    }
}
