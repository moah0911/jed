package dev.jed.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val JedDark = darkColorScheme(
    primary = Color(0xFFC6F24E),
    onPrimary = Color(0xFF111111),
    secondary = Color(0xFF9CCC65),
    background = Color(0xFF000000),
    surface = Color(0xFF101010),
    surfaceVariant = Color(0xFF1E1E1E),
    onBackground = Color(0xFFE8E8E8),
    onSurface = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFFA8A8A8),
    outline = Color(0xFF333333),
)

/** "system" follows the device; the default everywhere else is dark. */
@Composable
fun JedTheme(theme: String = "dark", content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) JedDark else lightColorScheme()
    MaterialTheme(colorScheme = colors) { Surface(Modifier.fillMaxSize(), content = content) }
}
