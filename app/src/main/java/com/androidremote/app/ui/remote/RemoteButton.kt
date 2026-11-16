package com.androidremote.app.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.androidremote.app.ui.theme.ButtonStyle
import com.androidremote.app.ui.theme.LocalRefScale
import com.androidremote.app.ui.theme.Tokens

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
    iconSize: Dp,
    style: ButtonStyle,
    onPress: (PressEvent) -> Unit,
    modifier: Modifier = Modifier,
    hold: HoldBehavior = HoldBehavior.None,
    shape: Shape = RoundedCornerShape(Tokens.ButtonCorner),
) {
    val scale = LocalRefScale.current
    val currentOnPress by rememberUpdatedState(onPress)
    val hapticTick by rememberUpdatedState(LocalHapticTick.current)
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(style.fill)
            .border(scale.dp(Tokens.ButtonStrokeWidth), style.stroke, shape)
            .pointerInput(hold) {
                detectPresses(
                    // The whole button is one target.
                    target = { Unit },
                    hold = { hold },
                    onPressed = { pressed = it != null },
                    onEvent = { _, event ->
                        if (event == PressEvent.Down) hapticTick()
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
        Icon(imageVector = icon, contentDescription = null, tint = style.icon, modifier = Modifier.size(iconSize))
        if (pressed) Box(Modifier.matchParentSize().background(Tokens.PressedOverlay))
    }
}
