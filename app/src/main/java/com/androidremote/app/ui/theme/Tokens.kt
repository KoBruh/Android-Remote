package com.androidremote.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A rectangle in reference pixels (see [Tokens.RefWidthPx]), origin at the top-left of the screen. */
data class RefRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

/**
 * Every visual value of the app in one place.
 *
 * Sizes are in **reference pixels**: pixels of the 1084 x 2412 reference screenshot
 * (docs/reference/google-tv-remote.png). They are converted with
 * `dp = refPx * (screenWidthDp / 1084)`, so the layout looks the same on any phone.
 * To convert a dp value you have in mind to reference pixels, multiply it by 2.63.
 */
object Tokens {

    // ---- Reference frame -------------------------------------------------------------------

    /** Width of the reference screenshot. Changing it rescales the whole UI. */
    const val RefWidthPx = 1084f

    /** Height of the reference screenshot. Used to pin the bottom rows to the screen bottom. */
    const val RefHeightPx = 2412f

    // ---- Colours ---------------------------------------------------------------------------

    /** Screen background. */
    val Background = Color(0xFF333537)

    /** Fill of the close and power buttons. */
    val TopButtonFill = Color(0xFF1E1F20)

    /** Thin edge around the close and power buttons. */
    val TopButtonStroke = Color(0xFF1B1B1B)

    /** Close X and power icon colour. */
    val TopIcon = Color(0xFFC4C7C5)

    /** Device pill and dropdown fill while connected (and while connecting). */
    val PillFill = Color(0xFF6DD58C)

    /** TV icon, device name and chevron colour on the connected pill. */
    val PillContent = Color(0xFF0A3818)

    /** Device pill and dropdown fill while disconnected. */
    val PillFillDisconnected = Color(0xFF444746)

    /** TV icon, device name and chevron colour on the disconnected pill. */
    val PillContentDisconnected = Color(0xFFE3E3E3)

    /** D-pad disc fill. */
    val DpadFill = Color(0xFF1B1B1B)

    /** D-pad outer edge and the ring around the centre button. */
    val DpadStroke = Color(0xFF0E0E0E)

    /** Centre OK button fill. */
    val DpadCenterFill = Color(0xFF444746)

    /** The four chevrons on the D-pad. */
    val DpadArrow = Color(0xFF444746)

    /** Fill of back, home, mic, mute, vol- and vol+. */
    val BottomButtonFill = Color(0xFF1B1B1B)

    /** Thin edge around the bottom buttons. */
    val BottomButtonStroke = Color(0xFF0E0E0E)

    /** Icon colour on the bottom buttons. */
    val BottomIcon = Color(0xFFE3E3E3)

    /** Overlay drawn on any button while it is pressed. */
    val PressedOverlay = Color.White.copy(alpha = 0.08f)

    /** Mic button fill while the phone microphone is streaming to the TV. */
    val MicActiveFill = Color(0xFF6DD58C)

    /** Mic icon colour while the phone microphone is streaming to the TV. */
    val MicActiveIcon = Color(0xFF0A3818)

    /** Background of the short status message shown under the top row. */
    val MessageFill = Color(0xFF1E1F20)

    /** Text colour of the short status message. */
    val MessageText = Color(0xFFE3E3E3)

    /** Accent colour for switches, text fields and dialog buttons in Settings and dialogs. */
    val Accent = Color(0xFF6DD58C)

    /** Surface colour of dialogs, the device dropdown and Settings cards. */
    val Surface = Color(0xFF1E1F20)

    /** Secondary (dimmer) text in menus, dialogs and Settings. */
    val TextSecondary = Color(0xFFC4C7C5)

    /** Colour for destructive actions and error text. */
    val Error = Color(0xFFF2B8B5)

    // ---- Corners ---------------------------------------------------------------------------

    /** Corner radius of every button except the D-pad. 0 = square. Try 28.dp for Google's pill look. */
    val ButtonCorner: Dp = 0.dp

    /** Corner radius of the D-pad disc and centre button in percent of their size. 50 = circle. */
    const val DpadCornerPercent = 50

    /** Corner radius of dialogs, the dropdown menu and Settings cards. */
    val SurfaceCorner: Dp = 0.dp

    // ---- Top row (reference px) ------------------------------------------------------------

    /** Close (X) button bounds. */
    val CloseButton = RefRect(left = 53f, top = 158f, width = 84f, height = 106f)

    /** Device pill bounds (TV icon + name). */
    val DevicePill = RefRect(left = 274f, top = 153f, width = 405f, height = 105f)

    /** Dropdown (chevron) button bounds. The 5 px gap to the pill comes from these two rects. */
    val DropdownButton = RefRect(left = 684f, top = 153f, width = 126f, height = 105f)

    /** Power button bounds. */
    val PowerButton = RefRect(left = 937f, top = 158f, width = 106f, height = 106f)

    /** Horizontal centre of the TV icon inside the pill, measured from the pill's left edge. */
    const val PillIconCenterX = 69f

    /** Where the device-name area starts, measured from the pill's left edge. The name is centred in the rest. */
    const val PillTextStartX = 81f

    /** Padding on both sides of the device-name area, so long names do not touch the icon or the edge. */
    const val PillTextPadding = 16f

    /** Device-name text size. */
    const val PillTextSize = 42f

    /** Smallest gap kept between the top row and the status bar on phones with a tall status bar. */
    const val TopRowMinGapBelowStatusBar = 10f

    // ---- D-pad (reference px) --------------------------------------------------------------

    /** Outer diameter of the D-pad disc, including its edge. */
    const val DpadDiameter = 916f

    /** Vertical centre of the D-pad as a fraction of the screen height. */
    const val DpadCenterYFraction = 0.443f

    /** Width of the D-pad's outer edge and of the ring around the centre button. */
    const val DpadStrokeWidth = 6f

    /** Diameter of the centre OK button, including its ring. */
    const val DpadCenterDiameter = 358f

    /** Width of a chevron, tip to tip. */
    const val DpadChevronWidth = 78f

    /** Line thickness of the chevrons. */
    const val DpadChevronStroke = 12.5f

    /** Distance from the disc's outer edge to the outer tip of each chevron. */
    const val DpadChevronInset = 110f

    /** Space kept free between the D-pad and the rows above and below it before it starts to shrink. */
    const val DpadMinGap = 40f

    // ---- Bottom rows (reference px) --------------------------------------------------------
    // These are pinned to the bottom of the screen: only their distance to the bottom edge
    // (RefHeightPx - top) matters, so they stay put on taller or shorter phones.

    /** Back button bounds. */
    val BackButton = RefRect(left = 63f, top = 1866f, width = 210f, height = 210f)

    /** Home button bounds. */
    val HomeButton = RefRect(left = 374f, top = 1845f, width = 336f, height = 252f)

    /** Mic button bounds. */
    val MicButton = RefRect(left = 811f, top = 1866f, width = 210f, height = 210f)

    /** Mute button bounds. */
    val MuteButton = RefRect(left = 63f, top = 2160f, width = 189f, height = 147f)

    /** Vol- button bounds. */
    val VolDownButton = RefRect(left = 315f, top = 2160f, width = 347f, height = 147f)

    /** Vol+ button bounds. */
    val VolUpButton = RefRect(left = 673f, top = 2160f, width = 348f, height = 147f)

    /** Smallest gap kept between the lowest button and the navigation bar on phones with a tall nav bar. */
    const val BottomMinGapAboveNavBar = 16f

    // ---- Buttons and icons (reference px) --------------------------------------------------

    /** Width of the thin edge drawn around every button. 0 hides it. */
    const val ButtonStrokeWidth = 3f

    /** Close X icon size. */
    const val IconClose = 66f

    /** TV icon size in the device pill. */
    const val IconTv = 53f

    /** Dropdown chevron icon size. */
    const val IconChevron = 63f

    /** Power icon size. */
    const val IconPower = 64f

    /** Icon size on back, home, mic, vol- and vol+. */
    const val IconBottom = 84.5f

    /** Mute icon size. */
    const val IconMute = 63.5f

    // ---- Status message (reference px) -----------------------------------------------------

    /** Top edge of the short status message ("TV didn't wake..."). */
    const val MessageTop = 330f

    /** Text size of the short status message. */
    const val MessageTextSize = 37f

    // ---- Feel ------------------------------------------------------------------------------

    /** How long a button must be held before it counts as a long press, in milliseconds. */
    const val HoldDelayMs = 400L

    /** Time between repeats while a repeating button (volume) is held, in milliseconds. */
    const val RepeatIntervalMs = 100L

    /** Lowest alpha of the pill while it pulses (connecting or waking). */
    const val PulseMinAlpha = 0.6f

    /** Duration of one half of the pill pulse, in milliseconds. */
    const val PulseHalfPeriodMs = 800

    /** How long a status message stays on screen, in milliseconds. */
    const val MessageDurationMs = 4000L

    /** Default haptic strength for new installs: 0 = light, 1 = medium, 2 = strong. */
    const val DefaultHapticStrength = 1

    /** Alpha of the reference screenshot overlay (debug builds). */
    const val ReferenceOverlayAlpha = 0.5f
}
