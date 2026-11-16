package com.androidremote.app.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.androidremote.app.ui.theme.LocalRefScale
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

/** Plays the haptic tick for a button press. Provided by the app according to the haptics settings. */
val LocalHapticTick = staticCompositionLocalOf<() -> Unit> { {} }

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

/**
 * The one button of the remote: a filled shape with a centred icon.
 *
 * It only reports [PressEvent]s; what they do is decided by whoever places the button.
 * Position and size come from [modifier].
 */
@Composable
fun RemoteButton(
    icon: ImageVector,
    contentDescription: String,
    onPress: (PressEvent) -> Unit,
    modifier: Modifier = Modifier,
    hold: HoldBehavior = HoldBehavior.None,
    iconSize: Dp = LocalRefScale.current.dp(Tokens.IconBottom),
    fill: Color = Tokens.BottomButtonFill,
    stroke: Color = Tokens.BottomButtonStroke,
    tint: Color = Tokens.BottomIcon,
    shape: Shape = RoundedCornerShape(Tokens.ButtonCorner),
) {
    val scale = LocalRefScale.current
    val hapticTick = LocalHapticTick.current
    val currentOnPress by rememberUpdatedState(onPress)
    val currentHapticTick by rememberUpdatedState(hapticTick)
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(scale.dp(Tokens.ButtonStrokeWidth), stroke, shape)
            .pointerInput(hold) {
                detectPresses(
                    target = { },
                    hold = { hold },
                    onPressed = { pressed = it != null },
                    onEvent = { _, event ->
                        if (event == PressEvent.Down) currentHapticTick()
                        currentOnPress(event)
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick {
                    currentOnPress(PressEvent.Down)
                    currentOnPress(PressEvent.Tap)
                    currentOnPress(PressEvent.Up)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        if (pressed) Box(Modifier.matchParentSize().background(Tokens.PressedOverlay))
    }
}
