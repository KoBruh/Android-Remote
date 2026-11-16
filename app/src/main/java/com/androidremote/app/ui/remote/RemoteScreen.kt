package com.androidremote.app.ui.remote

import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.androidremote.app.LinkState
import com.androidremote.app.R
import com.androidremote.app.RemoteUiState
import com.androidremote.app.tv.KeyCodes
import com.androidremote.app.ui.icons.Icons
import com.androidremote.app.ui.theme.ButtonStyle
import com.androidremote.app.ui.theme.LocalRefScale
import com.androidremote.app.ui.theme.RefRect
import com.androidremote.app.ui.theme.RefScale
import com.androidremote.app.ui.theme.Tokens

/** What a button does. */
sealed interface ButtonAction {
    /** Sends an Android KeyEvent code to the TV (see [KeyCodes], or any KEYCODE_* value). */
    data class Key(val code: Int) : ButtonAction

    /** Closes the app. */
    data object Close : ButtonAction

    /** Toggles power, or wakes the TV when it is not reachable. */
    data object Power : ButtonAction

    /** Hold to talk to the TV. */
    data object Mic : ButtonAction
}

/** Which screen edge a button's reference position is measured from. */
enum class Anchor { Top, Bottom }

/** One button of the remote. */
data class RemoteButtonSpec(
    val icon: ImageVector,
    @StringRes val label: Int,
    /** Position and size in reference pixels (see Tokens). */
    val bounds: RefRect,
    val anchor: Anchor,
    val action: ButtonAction,
    val hold: HoldBehavior = HoldBehavior.None,
    /** Icon size in reference pixels. */
    val iconSize: Float = Tokens.IconBottom,
    val style: ButtonStyle = Tokens.BottomButtonStyle,
    /** Corner radius. Set it to give one button its own shape. */
    val corner: Dp = Tokens.ButtonCorner,
)

/**
 * Every button on the remote except the device pill and the D-pad.
 * **To add, remove or change a button, edit this list**: give it an icon (ui/icons/Icons.kt),
 * bounds (ui/theme/Tokens.kt) and an action.
 */
val RemoteButtons: List<RemoteButtonSpec> = listOf(
    RemoteButtonSpec(
        icon = Icons.Close, label = R.string.cd_close, bounds = Tokens.CloseButton, anchor = Anchor.Top,
        action = ButtonAction.Close, iconSize = Tokens.IconClose, style = Tokens.TopButtonStyle,
    ),
    RemoteButtonSpec(
        icon = Icons.Power, label = R.string.cd_power, bounds = Tokens.PowerButton, anchor = Anchor.Top,
        action = ButtonAction.Power, iconSize = Tokens.IconPower, style = Tokens.TopButtonStyle,
    ),
    RemoteButtonSpec(
        icon = Icons.ArrowBack, label = R.string.cd_back, bounds = Tokens.BackButton, anchor = Anchor.Bottom,
        action = ButtonAction.Key(KeyCodes.BACK), hold = HoldBehavior.LongPress,
    ),
    RemoteButtonSpec(
        icon = Icons.Home, label = R.string.cd_home, bounds = Tokens.HomeButton, anchor = Anchor.Bottom,
        action = ButtonAction.Key(KeyCodes.HOME),
    ),
    RemoteButtonSpec(
        icon = Icons.Mic, label = R.string.cd_mic, bounds = Tokens.MicButton, anchor = Anchor.Bottom,
        action = ButtonAction.Mic,
    ),
    RemoteButtonSpec(
        icon = Icons.VolumeOff, label = R.string.cd_mute, bounds = Tokens.MuteButton, anchor = Anchor.Bottom,
        action = ButtonAction.Key(KeyCodes.VOLUME_MUTE), iconSize = Tokens.IconMute,
    ),
    RemoteButtonSpec(
        icon = Icons.VolumeDown, label = R.string.cd_volume_down, bounds = Tokens.VolDownButton, anchor = Anchor.Bottom,
        action = ButtonAction.Key(KeyCodes.VOLUME_DOWN), hold = HoldBehavior.Repeat,
    ),
    RemoteButtonSpec(
        icon = Icons.VolumeUp, label = R.string.cd_volume_up, bounds = Tokens.VolUpButton, anchor = Anchor.Bottom,
        action = ButtonAction.Key(KeyCodes.VOLUME_UP), hold = HoldBehavior.Repeat,
    ),
)

/** Reference-pixel edges of the two button groups: the top row (with the device pill) and the bottom rows. */
private val TopRowTop = minOf(RemoteButtons.filter { it.anchor == Anchor.Top }.minOf { it.bounds.top }, Tokens.DevicePill.top)
private val TopRowBottom = maxOf(RemoteButtons.filter { it.anchor == Anchor.Top }.maxOf { it.bounds.bottom }, Tokens.DevicePill.bottom)
private val BottomRowsTop = RemoteButtons.filter { it.anchor == Anchor.Bottom }.minOf { it.bounds.top }
private val BottomRowsBottom = RemoteButtons.filter { it.anchor == Anchor.Bottom }.maxOf { it.bounds.bottom }

/**
 * The main screen: a copy of the Google TV remote. Nothing else is visible here; everything
 * else is reached through the device pill.
 *
 * @param onButton a button from [RemoteButtons] reported a press event.
 * @param onDpad a D-pad zone reported a press event.
 * @param onOpenDevices the device pill or its chevron was tapped.
 * @param deviceMenu the dropdown menu, placed so that it opens under the pill.
 */
@Composable
fun RemoteScreen(
    state: RemoteUiState,
    onButton: (ButtonAction, PressEvent) -> Unit,
    onDpad: (DpadZone, PressEvent) -> Unit,
    onOpenDevices: () -> Unit,
    deviceMenu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Tokens.Background)) {
        val scale = remember(maxWidth) { RefScale(maxWidth.value) }
        val density = LocalDensity.current
        val statusBar = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val navBar = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }

        // The reference positions already leave room for a normal status bar and gesture bar.
        // On phones with taller bars, push the top row down and the bottom rows up just enough.
        val topShift = (statusBar + scale.dp(Tokens.TopRowMinGapBelowStatusBar) - scale.dp(TopRowTop)).coerceAtLeast(0.dp)
        val bottomLift = (navBar + scale.dp(Tokens.BottomMinGapAboveNavBar) - scale.dp(Tokens.RefHeightPx - BottomRowsBottom))
            .coerceAtLeast(0.dp)

        /** Where a reference y position ends up on this screen. */
        fun y(refY: Float, anchor: Anchor): Dp = when (anchor) {
            Anchor.Top -> scale.dp(refY) + topShift
            Anchor.Bottom -> maxHeight - scale.dp(Tokens.RefHeightPx - refY) - bottomLift
        }

        fun Modifier.place(rect: RefRect, anchor: Anchor): Modifier =
            offset(x = scale.dp(rect.left), y = y(rect.top, anchor)).size(scale.dp(rect.width), scale.dp(rect.height))

        // The D-pad sits at a fixed fraction of the height and shrinks before it touches a row.
        val topRowBottom = y(TopRowBottom, Anchor.Top)
        val bottomRowsTop = y(BottomRowsTop, Anchor.Bottom)
        val dpadCenterY = maxHeight * Tokens.DpadCenterYFraction
        val gap = scale.dp(Tokens.DpadMinGap)
        val dpadDiameter = min(
            scale.dp(Tokens.DpadDiameter),
            min(dpadCenterY - topRowBottom - gap, bottomRowsTop - dpadCenterY - gap) * 2,
        ).coerceAtLeast(0.dp)

        CompositionLocalProvider(LocalRefScale provides scale) {
            for (spec in RemoteButtons) {
                val micActive = spec.action == ButtonAction.Mic && state.micActive
                RemoteButton(
                    icon = spec.icon,
                    contentDescription = stringResource(spec.label),
                    onPress = { onButton(spec.action, it) },
                    modifier = Modifier.place(spec.bounds, spec.anchor),
                    hold = spec.hold,
                    iconSize = scale.dp(spec.iconSize),
                    style = if (micActive) Tokens.MicActiveStyle else spec.style,
                    shape = RoundedCornerShape(spec.corner),
                )
            }

            Box(Modifier.offset(x = scale.dp(Tokens.DevicePill.left), y = y(Tokens.DevicePill.top, Anchor.Top))) {
                DevicePill(state = state, onClick = onOpenDevices)
                deviceMenu()
            }

            Dpad(
                diameter = dpadDiameter,
                onPress = onDpad,
                modifier = Modifier.offset(x = (maxWidth - dpadDiameter) / 2, y = dpadCenterY - dpadDiameter / 2),
            )

            StatusMessage(
                text = state.message?.let { stringResource(it.text) },
                modifier = Modifier.align(Alignment.TopCenter).offset(y = scale.dp(Tokens.MessageTop) + topShift),
            )
        }

        if (state.settings.referenceOverlay) ReferenceOverlay()
    }
}

/** The device pill (TV icon + name) and the dropdown button next to it. Both open the device menu. */
@Composable
private fun DevicePill(state: RemoteUiState, onClick: () -> Unit) {
    val scale = LocalRefScale.current
    // Connecting shows the connected colours too, with a pulse on top.
    val active = state.link != LinkState.Disconnected
    val fill = if (active) Tokens.PillFill else Tokens.PillFillDisconnected
    val content = if (active) Tokens.PillContent else Tokens.PillContentDisconnected
    val shape = RoundedCornerShape(Tokens.ButtonCorner)
    val pill = Tokens.DevicePill
    val dropdown = Tokens.DropdownButton
    val alpha = if (state.link == LinkState.Connecting) pulseAlpha() else 1f
    val openLabel = stringResource(R.string.cd_devices)

    // Sized to hold both parts: the pulse's alpha layer clips to these bounds.
    Box(Modifier.size(scale.dp(dropdown.right - pill.left), scale.dp(maxOf(pill.height, dropdown.height))).alpha(alpha)) {
        Box(
            Modifier
                .size(scale.dp(pill.width), scale.dp(pill.height))
                .clip(shape)
                .background(fill)
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick),
        ) {
            Icon(
                imageVector = Icons.Tv,
                contentDescription = null,
                tint = content,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = scale.dp(Tokens.PillIconCenterX - Tokens.IconTv / 2f))
                    .size(scale.dp(Tokens.IconTv)),
            )
            Text(
                text = state.selected?.name ?: stringResource(R.string.no_tv),
                color = content,
                // Sized in dp so the pill matches the reference whatever the system font scale is.
                fontSize = with(LocalDensity.current) { scale.dp(Tokens.PillTextSize).toSp() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = scale.dp(Tokens.PillTextStartX + Tokens.PillTextPadding))
                    .width(scale.dp(pill.width - Tokens.PillTextStartX - 2 * Tokens.PillTextPadding)),
            )
        }
        Box(
            modifier = Modifier
                .offset(x = scale.dp(dropdown.left - pill.left), y = scale.dp(dropdown.top - pill.top))
                .size(scale.dp(dropdown.width), scale.dp(dropdown.height))
                .clip(shape)
                .background(fill)
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.ChevronDown,
                contentDescription = openLabel,
                tint = content,
                modifier = Modifier.offset(x = scale.dp(Tokens.IconChevronOffsetX)).size(scale.dp(Tokens.IconChevron)),
            )
        }
    }
}

/** Slow alpha pulse shown while connecting or waking the TV. */
@Composable
private fun pulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "pill pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = Tokens.PulseMinAlpha,
        animationSpec = infiniteRepeatable(tween(Tokens.PulseHalfPeriodMs), RepeatMode.Reverse),
        label = "pill alpha",
    )
    return alpha
}

/** A short message that fades in under the top row and out again. */
@Composable
private fun StatusMessage(text: String?, modifier: Modifier = Modifier) {
    val scale = LocalRefScale.current
    Crossfade(targetState = text, modifier = modifier, label = "status message") { message ->
        if (message != null) {
            Text(
                text = message,
                color = Tokens.MessageText,
                fontSize = with(LocalDensity.current) { scale.dp(Tokens.MessageTextSize).toSp() },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = scale.dp(Tokens.CloseButton.left))
                    .clip(RoundedCornerShape(Tokens.ButtonCorner))
                    .background(Tokens.MessageFill)
                    .padding(horizontal = scale.dp(Tokens.MessagePaddingHorizontal), vertical = scale.dp(Tokens.MessagePaddingVertical)),
            )
        }
    }
}

/** Debug builds: the reference screenshot drawn half-transparent over the remote for a pixel check. */
@Composable
private fun ReferenceOverlay() {
    val context = LocalContext.current
    val reference = remember {
        runCatching {
            context.assets.open(REFERENCE_ASSET).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()
    } ?: return
    Image(
        bitmap = reference,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        alignment = Alignment.TopCenter,
        alpha = Tokens.ReferenceOverlayAlpha,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Bundled into debug builds only, from docs/reference (see app/build.gradle.kts). */
private const val REFERENCE_ASSET = "google-tv-remote.png"
