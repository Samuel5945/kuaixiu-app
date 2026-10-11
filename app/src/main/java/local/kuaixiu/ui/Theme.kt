package local.kuaixiu.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 暖色暗色主题（睡眠场景常驻暗色）——全槽位定义，避免 M3 默认蓝灰渗入
private val Colors = darkColorScheme(
    primary = Color(0xFFFFB457),
    onPrimary = Color(0xFF241708),
    primaryContainer = Color(0xFF3D2B12),
    onPrimaryContainer = Color(0xFFFFDDB8),
    secondary = Color(0xFF7FD08A),
    onSecondary = Color(0xFF10240F),
    secondaryContainer = Color(0xFF4A3A24),
    onSecondaryContainer = Color(0xFFFFE9C8),
    tertiary = Color(0xFF8FB7FF),
    onTertiary = Color(0xFF0F1B33),
    tertiaryContainer = Color(0xFF24344F),
    onTertiaryContainer = Color(0xFFCCE0FF),
    background = Color(0xFF14100C),
    onBackground = Color(0xFFF3E9D8),
    surface = Color(0xFF1F1913),
    onSurface = Color(0xFFF3E9D8),
    surfaceVariant = Color(0xFF2B2117),
    onSurfaceVariant = Color(0xFFB3A385),
    surfaceContainerLowest = Color(0xFF0E0B08),
    surfaceContainerLow = Color(0xFF1A140E),
    surfaceContainer = Color(0xFF1F1913),
    surfaceContainerHigh = Color(0xFF292016),
    surfaceContainerHighest = Color(0xFF33281A),
    surfaceDim = Color(0xFF14100C),
    surfaceBright = Color(0xFF3B2F22),
    outline = Color(0xFF4A3B2A),
    outlineVariant = Color(0xFF2B2117),
    error = Color(0xFFE57373),
    onError = Color(0xFF2B0F0F),
    errorContainer = Color(0xFF4A1F1F),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFF3E9D8),
    inverseOnSurface = Color(0xFF14100C),
    inversePrimary = Color(0xFF8A5B1A),
    scrim = Color(0xFF000000),
)

@Composable
fun KxTheme(content: @Composable () -> Unit) {
    // 睡眠 App：无论系统明暗都用暖暗色
    MaterialTheme(colorScheme = Colors, content = content)
}
