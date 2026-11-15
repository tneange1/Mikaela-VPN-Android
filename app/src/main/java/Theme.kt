package xyz.mikaelavpn.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ok = Color(0xFF34C759)
val Warn = Color(0xFFFF9F0A)
val Bad = Color(0xFFFF453A)

@Composable
fun MikaelaTheme(mode: String, content: @Composable () -> Unit) {
    val light = mode == "light" || (mode == "system" && !isSystemInDarkTheme())
    val scheme = when {
        light -> lightColorScheme(
            background = Color(0xFFF5F5F7), surface = Color(0xFFF5F5F7), surfaceVariant = Color.White,
            onBackground = Color(0xFF111111), onSurface = Color(0xFF111111), onSurfaceVariant = Color(0xFF6E6E73),
            outline = Color(0xFFDCDCE0), primary = Color(0xFF111111), onPrimary = Color.White,
        )
        mode == "gray" -> darkColorScheme(
            background = Color(0xFF1C1C1E), surface = Color(0xFF1C1C1E), surfaceVariant = Color(0xFF252528),
            onBackground = Color(0xFFF5F5F7), onSurface = Color(0xFFF5F5F7), onSurfaceVariant = Color(0xFF98989D),
            outline = Color(0xFF343438), primary = Color(0xFFF5F5F7), onPrimary = Color(0xFF111111),
        )
        else -> darkColorScheme(
            background = Color(0xFF111111), surface = Color(0xFF111111), surfaceVariant = Color(0xFF181818),
            onBackground = Color(0xFFF2F2F2), onSurface = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF8E8E93),
            outline = Color(0xFF2A2A2A), primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF111111),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
