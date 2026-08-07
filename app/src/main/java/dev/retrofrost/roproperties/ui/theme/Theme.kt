package dev.retrofrost.roproperties.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    background = Color(0xFFFDFDFD),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFDFDFD),
    onSurface = Color(0xFF111111),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE7E7E7),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFF9A9A9A),
    outlineVariant = Color(0xFFE1E1E1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFECECEC),
    onPrimary = Color(0xFF171717),
    background = Color(0xFF212121),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF212121),
    onSurface = Color(0xFFECECEC),
    surfaceContainerLow = Color(0xFF2A2A2A),
    surfaceContainer = Color(0xFF2F2F2F),
    surfaceContainerHigh = Color(0xFF353535),
    surfaceContainerHighest = Color(0xFF3A3A3A),
    onSurfaceVariant = Color(0xFFB4B4B4),
    outline = Color(0xFF777777),
    outlineVariant = Color(0xFF404040),
)

@Composable
fun ROPropertiesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
