package com.androidremote.app.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.androidremote.app.R
import com.androidremote.app.tv.KeyCodes
import com.androidremote.app.ui.theme.Tokens
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The five parts of the D-pad.
 *
 * @param angle direction of the zone's pie slice in canvas degrees (0 = right, clockwise).
 */
enum class DpadZone(val keyCode: Int, val angle: Float) {
    Right(KeyCodes.DPAD_RIGHT, 0f),
    Down(KeyCodes.DPAD_DOWN, 90f),
    Left(KeyCodes.DPAD_LEFT, 180f),
    Up(KeyCodes.DPAD_UP, 270f),
    Center(KeyCodes.DPAD_CENTER, 0f),
}

/**
 * The round D-pad: a disc split into four pie-slice zones around a centre OK button.
 * Every zone supports long press and shows its own pressed overlay.
 *
 * All proportions come from [Tokens] and scale with [diameter], so the D-pad can shrink on short screens.
 */
@Composable
fun Dpad(diameter: Dp, onPress: (DpadZone, PressEvent) -> Unit, modifier: Modifier = Modifier) {
    val hapticTick by rememberUpdatedState(LocalHapticTick.current)
    val currentOnPress by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf<DpadZone?>(null) }
    val description = stringResource(R.string.cd_dpad)

    Canvas(
        modifier = modifier
            .size(diameter)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectPresses(
                    target = { position -> zoneAt(position, size.width.toFloat()) },
                    hold = { HoldBehavior.LongPress },
                    onPressed = { pressed = it },
                    onEvent = { zone, event ->
                        if (event == PressEvent.Down) hapticTick()
                        currentOnPress(zone, event)
                    },
                )
            },
    ) {
        val unit = size.width / Tokens.DpadDiameter // px per reference px
        val stroke = Tokens.DpadStrokeWidth * unit
        val centerDiameter = Tokens.DpadCenterDiameter * unit

        val disc = roundedSquare(center, size.width)
        val discInner = roundedSquare(center, size.width - 2 * stroke)
        val ring = roundedSquare(center, centerDiameter)
        val button = roundedSquare(center, centerDiameter - 2 * stroke)

        drawPath(disc, Tokens.DpadStroke)
        drawPath(discInner, Tokens.DpadFill)

        pressed?.takeIf { it != DpadZone.Center }?.let { zone ->
            clipPath(discInner) {
                clipPath(ring, clipOp = ClipOp.Difference) {
                    // A 90 degree wedge, larger than the disc so it also covers squared-off corners.
                    drawArc(
                        color = Tokens.PressedOverlay,
                        startAngle = zone.angle - 45f,
                        sweepAngle = 90f,
                        useCenter = true,
                        topLeft = Offset(center.x - size.width, center.y - size.width),
                        size = Size(size.width * 2, size.width * 2),
                    )
                }
            }
        }

        for (zone in DpadZone.entries) {
            if (zone != DpadZone.Center) drawChevron(zone.angle, unit)
        }

        drawPath(ring, Tokens.DpadStroke)
        drawPath(button, Tokens.DpadCenterFill)
        if (pressed == DpadZone.Center) drawPath(button, Tokens.PressedOverlay)
    }
}

/** A square of [side] around [center] with [Tokens.DpadCornerPercent] corners: a circle at 50 percent. */
private fun roundedSquare(center: Offset, side: Float): Path {
    val radius = side * Tokens.DpadCornerPercent.coerceIn(0, 50) / 100f
    return Path().apply {
        addRoundRect(RoundRect(Rect(center, side / 2f), CornerRadius(radius)))
    }
}

/** Draws the chevron that points towards [angle] (0 = right, clockwise). */
private fun DrawScope.drawChevron(angle: Float, unit: Float) {
    val stroke = Tokens.DpadChevronStroke * unit
    // Half the distance between the two arm ends, measured along the centre line of the stroke.
    val arm = (Tokens.DpadChevronWidth * unit - stroke) / 2f
    // Drawn pointing right, then rotated into place. The round join sticks out half a stroke past the tip.
    val tipX = size.width - Tokens.DpadChevronInset * unit - stroke / 2f
    val path = Path().apply {
        moveTo(tipX - arm, center.y - arm)
        lineTo(tipX, center.y)
        lineTo(tipX - arm, center.y + arm)
    }
    rotate(angle, center) {
        drawPath(path, Tokens.DpadArrow, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Which zone is under [position] on a D-pad that is [diameter] pixels wide, or null if outside. */
internal fun zoneAt(position: Offset, diameter: Float): DpadZone? {
    val dx = position.x - diameter / 2f
    val dy = position.y - diameter / 2f
    val distance = hypot(dx, dy)
    val centerRadius = diameter * (Tokens.DpadCenterDiameter / Tokens.DpadDiameter) / 2f
    return when {
        distance <= centerRadius -> DpadZone.Center
        Tokens.DpadCornerPercent >= 50 && distance > diameter / 2f -> null
        else -> when (Math.toDegrees(atan2(dy, dx).toDouble())) {
            in -45.0..45.0 -> DpadZone.Right
            in 45.0..135.0 -> DpadZone.Down
            in -135.0..-45.0 -> DpadZone.Up
            else -> DpadZone.Left
        }
    }
}
