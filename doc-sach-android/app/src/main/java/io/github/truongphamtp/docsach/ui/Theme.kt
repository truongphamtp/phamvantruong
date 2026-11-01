package io.github.truongphamtp.docsach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.truongphamtp.docsach.core.ReaderTheme

private val LightColors = lightColorScheme(
    primary = Color(0xFF2A5D8F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF6D5D45),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4E1C1),
    onSecondaryContainer = Color(0xFF261A06),
    tertiary = Color(0xFF9A4F16),
    onTertiary = Color.White,
    background = Color(0xFFFBF8F4),
    onBackground = Color(0xFF1D1B18),
    surface = Color(0xFFFBF8F4),
    onSurface = Color(0xFF1D1B18),
    surfaceVariant = Color(0xFFECE5DB),
    onSurfaceVariant = Color(0xFF4E4639),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2EC),
    surfaceContainer = Color(0xFFF1ECE5),
    surfaceContainerHigh = Color(0xFFEBE6DF),
    surfaceContainerHighest = Color(0xFFE6E0D9),
    outline = Color(0xFF807667),
    outlineVariant = Color(0xFFD1C5B4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA2C9FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF0B4877),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFD8C4A5),
    onSecondary = Color(0xFF3C2F1B),
    secondaryContainer = Color(0xFF54462F),
    onSecondaryContainer = Color(0xFFF4E1C1),
    tertiary = Color(0xFFFFB68A),
    onTertiary = Color(0xFF532200),
    background = Color(0xFF131211),
    onBackground = Color(0xFFE8E2DA),
    surface = Color(0xFF131211),
    onSurface = Color(0xFFE8E2DA),
    surfaceVariant = Color(0xFF4E4639),
    onSurfaceVariant = Color(0xFFD1C5B4),
    surfaceContainerLowest = Color(0xFF0E0D0C),
    surfaceContainerLow = Color(0xFF1B1A18),
    surfaceContainer = Color(0xFF201E1C),
    surfaceContainerHigh = Color(0xFF2A2826),
    surfaceContainerHighest = Color(0xFF353331),
    outline = Color(0xFF9A8F80),
    outlineVariant = Color(0xFF4E4639),
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

/** Bảng màu cho trang sách và các thanh công cụ khi đọc. */
data class ReaderColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val chrome: Color,
    val onChrome: Color,
    val accent: Color,
    val selection: Color,
    val dark: Boolean,
)

fun readerColors(theme: ReaderTheme): ReaderColors = when (theme) {
    ReaderTheme.WHITE -> ReaderColors(
        background = Color(0xFFFFFFFF),
        text = Color(0xFF1B1B1B),
        secondaryText = Color(0xFF707070),
        chrome = Color(0xFFF3F3F1),
        onChrome = Color(0xFF1B1B1B),
        accent = Color(0xFF2A5D8F),
        selection = Color(0x5530A0FF),
        dark = false,
    )
    ReaderTheme.SEPIA -> ReaderColors(
        background = Color(0xFFF7EEDB),
        text = Color(0xFF3B2E1E),
        secondaryText = Color(0xFF8A7558),
        chrome = Color(0xFFEFE2C8),
        onChrome = Color(0xFF3B2E1E),
        accent = Color(0xFF8A4F14),
        selection = Color(0x55D9A441),
        dark = false,
    )
    ReaderTheme.GREEN -> ReaderColors(
        background = Color(0xFFDCEBDC),
        text = Color(0xFF1C2B1E),
        secondaryText = Color(0xFF5C735E),
        chrome = Color(0xFFCCE0CD),
        onChrome = Color(0xFF1C2B1E),
        accent = Color(0xFF2E6B3A),
        selection = Color(0x553E9B52),
        dark = false,
    )
    ReaderTheme.BLACK -> ReaderColors(
        background = Color(0xFF000000),
        text = Color(0xFFCFCBC4),
        secondaryText = Color(0xFF85817B),
        chrome = Color(0xFF1C1C1C),
        onChrome = Color(0xFFE6E2DB),
        accent = Color(0xFF9CC5F5),
        selection = Color(0x664D7FB8),
        dark = true,
    )
}

/** Áp màu theo chủ đề đọc cho các thành phần Material (thanh trượt, công tắc...). */
@Composable
fun ReaderChromeTheme(colors: ReaderColors, content: @Composable () -> Unit) {
    val scheme = if (colors.dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = Color.Black,
            secondaryContainer = colors.accent.copy(alpha = 0.28f),
            onSecondaryContainer = colors.onChrome,
            background = colors.chrome,
            onBackground = colors.onChrome,
            surface = colors.chrome,
            onSurface = colors.onChrome,
            surfaceVariant = Color(0xFF2B2B2B),
            onSurfaceVariant = colors.secondaryText,
            surfaceContainer = colors.chrome,
            surfaceContainerHigh = Color(0xFF262626),
            surfaceContainerHighest = Color(0xFF303030),
            outline = Color(0xFF5E5E5E),
            outlineVariant = Color(0xFF3A3A3A),
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            secondaryContainer = colors.accent.copy(alpha = 0.18f),
            onSecondaryContainer = colors.onChrome,
            background = colors.chrome,
            onBackground = colors.onChrome,
            surface = colors.chrome,
            onSurface = colors.onChrome,
            surfaceVariant = colors.background,
            onSurfaceVariant = colors.secondaryText,
            surfaceContainer = colors.chrome,
            surfaceContainerHigh = colors.background,
            surfaceContainerHighest = colors.background,
            outline = colors.secondaryText,
            outlineVariant = colors.secondaryText.copy(alpha = 0.35f),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
