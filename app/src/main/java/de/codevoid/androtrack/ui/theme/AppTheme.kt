package de.codevoid.androtrack.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Orange600,
    onPrimary = OnOrange,
    primaryContainer = OrangeContainer,
    onPrimaryContainer = Orange600,
    secondary = Orange700,
    onSecondary = OnOrange,
    background = Background,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = OnSurface,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceCardElevated,
    error = Destructive,
    onError = TextPrimary
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
