package `in`.geekofia.ripster.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BA9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001848),
    secondary = Color(0xFF585E71),
    tertiary = Color(0xFF00696E),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB2C5FF),
    onPrimary = Color(0xFF002B74),
    primaryContainer = Color(0xFF21428F),
    onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFFC0C6DC),
    tertiary = Color(0xFF6FD6DC),
    error = Color(0xFFFFB4AB),
)

/** Follows the system light/dark setting. */
@Composable
fun RipsterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
