package com.androidremote.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Material theme for dialogs, menus and Settings, built from [Tokens]. The remote itself draws from Tokens directly. */
@Composable
fun AndroidRemoteTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Tokens.Accent,
        onPrimary = Tokens.PillContent,
        secondary = Tokens.Accent,
        onSecondary = Tokens.PillContent,
        secondaryContainer = Tokens.DpadCenterFill,
        onSecondaryContainer = Tokens.TextPrimary,
        background = Tokens.Background,
        onBackground = Tokens.TextPrimary,
        surface = Tokens.Surface,
        onSurface = Tokens.TextPrimary,
        surfaceVariant = Tokens.Surface,
        onSurfaceVariant = Tokens.TextSecondary,
        surfaceContainer = Tokens.Surface,
        surfaceContainerHigh = Tokens.Surface,
        surfaceContainerHighest = Tokens.DpadCenterFill,
        outline = Tokens.DpadCenterFill,
        error = Tokens.Error,
    )
    val corner = RoundedCornerShape(Tokens.SurfaceCorner)
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(extraSmall = corner, small = corner, medium = corner, large = corner, extraLarge = corner),
        content = content,
    )
}
