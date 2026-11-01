package io.github.truongphamtp.docsach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.truongphamtp.docsach.core.ReaderTheme

/** Màu Material lấy theo bảng màu HyperOS (cho các thành phần Material còn dùng). */
private fun materialColors(p: HyperPalette): ColorScheme = if (p.dark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accent.copy(alpha = 0.28f),
        onPrimaryContainer = p.text,
        secondary = p.accentLight,
        onSecondary = Color.Black,
        secondaryContainer = p.controlTrack,
        onSecondaryContainer = p.text,
        background = p.backgroundBottom,
        onBackground = p.text,
        surface = Color(0xFF121A2B),
        onSurface = p.text,
        surfaceVariant = Color(0xFF1C2538),
        onSurfaceVariant = p.textSecondary,
        surfaceContainer = Color(0xFF151D30),
        surfaceContainerHigh = Color(0xFF1A2337),
        surfaceContainerHighest = Color(0xFF212B40),
        outline = Color(0x40FFFFFF),
        outlineVariant = Color(0x1FFFFFFF),
        error = p.danger,
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = Color(0xFFDCE9FF),
        onPrimaryContainer = p.text,
        secondary = p.accent,
        onSecondary = p.onAccent,
        secondaryContainer = Color(0xFFE3ECFA),
        onSecondaryContainer = p.text,
        background = p.backgroundBottom,
        onBackground = p.text,
        surface = Color(0xFFF7FAFF),
        onSurface = p.text,
        surfaceVariant = Color(0xFFEAF0FA),
        onSurfaceVariant = p.textSecondary,
        surfaceContainer = Color(0xFFF0F5FD),
        surfaceContainerHigh = Color(0xFFEAF1FC),
        surfaceContainerHighest = Color(0xFFE4ECFA),
        outline = Color(0x3314213D),
        outlineVariant = Color(0x1A14213D),
        error = p.danger,
    )
}

/** Chữ theo phong cách HyperOS: tiêu đề lớn, đậm (trên máy Xiaomi dùng phông MiSans của hệ thống). */
private val HyperTypography: Typography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) HyperDark else HyperLight
    MaterialTheme(colorScheme = materialColors(palette), typography = HyperTypography) {
        CompositionLocalProvider(LocalHyper provides palette, content = content)
    }
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

/** Thanh công cụ khi đọc: kính sáng trên nền trang sáng, kính tối khi đọc nền đen. */
@Composable
fun ReaderChromeTheme(colors: ReaderColors, content: @Composable () -> Unit) {
    val palette = if (colors.dark) HyperDark else HyperLight
    MaterialTheme(colorScheme = materialColors(palette), typography = HyperTypography) {
        CompositionLocalProvider(
            LocalHyper provides palette,
            LocalGlassBackdrop provides colors.background,
            content = content,
        )
    }
}
