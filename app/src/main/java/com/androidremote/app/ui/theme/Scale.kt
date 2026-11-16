package com.androidremote.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Converts reference pixels to dp for a screen that is [screenWidthDp] wide. */
fun refPxToDp(refPx: Float, screenWidthDp: Float): Float = refPx * (screenWidthDp / Tokens.RefWidthPx)

/** Scales reference pixels to the current screen. Provided once by the remote screen. */
@Immutable
class RefScale(private val screenWidthDp: Float) {
    fun dp(refPx: Float): Dp = refPxToDp(refPx, screenWidthDp).dp
}

val LocalRefScale = staticCompositionLocalOf { RefScale(412f) }
