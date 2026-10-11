package local.kuaixiu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 暖色暗色主题（睡眠场景常驻暗色）
private val Colors = darkColorScheme(
    primary = Color(0xFFFFB457),
    onPrimary = Color(0xFF241708),
    secondary = Color(0xFF7FD08A),
    onSecondary = Color(0xFF10240F),
    tertiary = Color(0xFF8FB7FF),
    background = Color(0xFF14100C),
    onBackground = Color(0xFFF3E9D8),
    surface = Color(0xFF1F1913),
    onSurface = Color(0xFFF3E9D8),
    surfaceVariant = Color(0xFF2B2117),
    onSurfaceVariant = Color(0xFFB3A385),
    outline = Color(0xFF3A2F22),
    error = Color(0xFFE57373),
)

@Composable
fun KxTheme(content: @Composable () -> Unit) {
    // 睡眠 App：无论系统明暗都用暖暗色
    MaterialTheme(colorScheme = Colors, content = content)
}
