package com.bamnat.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.app.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BEA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF00897B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8F2EA),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFFE07A1F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE2CC),
    onTertiaryContainer = Color(0xFF2E1500),
    error = Color(0xFFD92D20),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF55160C),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF151927),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF151927),
    surfaceVariant = Color(0xFFE3E6F0),
    onSurfaceVariant = Color(0xFF4A5068),
    outline = Color(0xFF7A8099),
    outlineVariant = Color(0xFFD4D8E5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFECEEF6),
    surfaceContainerHigh = Color(0xFFE6E9F2),
    surfaceContainerHighest = Color(0xFFE0E3ED),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C4FF),
    onPrimary = Color(0xFF00218B),
    primaryContainer = Color(0xFF1D41C2),
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFF7FD8C8),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005047),
    onSecondaryContainer = Color(0xFFC8F2EA),
    tertiary = Color(0xFFFFB783),
    onTertiary = Color(0xFF4C2700),
    tertiaryContainer = Color(0xFF6C3A00),
    onTertiaryContainer = Color(0xFFFFE2CC),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF7A1A12),
    onErrorContainer = Color(0xFFFEE4E2),
    background = Color(0xFF0F1220),
    onBackground = Color(0xFFE3E5F0),
    surface = Color(0xFF0F1220),
    onSurface = Color(0xFFE3E5F0),
    surfaceVariant = Color(0xFF2E3347),
    onSurfaceVariant = Color(0xFFC1C6D9),
    outline = Color(0xFF8C91A6),
    outlineVariant = Color(0xFF3A3F55),
    surfaceContainerLowest = Color(0xFF0A0C17),
    surfaceContainerLow = Color(0xFF161A2A),
    surfaceContainer = Color(0xFF1A1E2F),
    surfaceContainerHigh = Color(0xFF24293B),
    surfaceContainerHighest = Color(0xFF2F3447),
)

/** Colors outside Material roles that the calendar needs. */
@Immutable
data class CalendarColors(
    val sunday: Color,
    val saturday: Color,
    val warning: Color,
    val warningContainer: Color,
    val holidayWork: Color,
    val memoDot: Color,
    /** [LEDG-01] Money in and out. */
    val income: Color = Color(0xFF12805C),
    val expense: Color = Color(0xFFD14343),
    /** [VIS-01] A day whose alarms were turned off. */
    val alarmOff: Color = Color(0xFFD92D20),
    /** [VIS-01] A day the user changed by hand. */
    val changed: Color = Color(0xFFB45309),
    /** [SLP-14] Sleep and naps on the today strip, and the button that starts a sleep. */
    val sleep: Color = Color(0xFF4F5BD5),
    val nap: Color = Color(0xFFA5ADF0),
    val sleepContainer: Color = Color(0xFFCDD1FB),
    val onSleepContainer: Color = Color(0xFF1F287A),
)

// [VIS-01] Weekend numbers and the extra-hours text reach 4.5:1 on white and on a shift's tint.
private val LightCalendarColors = CalendarColors(
    sunday = Color(0xFFD92D20),
    saturday = Color(0xFF2F5BEA),
    warning = Color(0xFFD92D20),
    warningContainer = Color(0xFFFEE4E2),
    holidayWork = Color(0xFFB45309),
    memoDot = Color(0xFF00897B),
)

private val DarkCalendarColors = CalendarColors(
    sunday = Color(0xFFFF7A7F),
    saturday = Color(0xFF8AB0FF),
    warning = Color(0xFFFF8A80),
    warningContainer = Color(0xFF4A1712),
    holidayWork = Color(0xFFFFB783),
    memoDot = Color(0xFF7FD8C8),
    income = Color(0xFF6FD3A6),
    expense = Color(0xFFFF8F8F),
    alarmOff = Color(0xFFFF8A80),
    changed = Color(0xFFFFB783),
    sleep = Color(0xFF9AA5FF),
    nap = Color(0xFF5A64B8),
    sleepContainer = Color(0xFF323A7A),
    onSleepContainer = Color(0xFFDFE1FF),
)

val LocalCalendarColors = staticCompositionLocalOf { LightCalendarColors }

/**
 * [VIS-02] Korean wraps between words, never inside one ("꺼져 있어 / 요"): phrase breaking on text
 * marked Korean, which Android 15 and later follow whatever the phone's language is (earlier versions
 * wrap as before). Titles also even out their lines.
 */
private val Korean = LocaleList("ko-KR")
private val WordWrap = LineBreak.Simple.copy(wordBreak = LineBreak.WordBreak.Phrase)
private fun TextStyle.korean(title: Boolean = false) = copy(lineBreak = if (title) LineBreak.Heading else WordWrap, localeList = Korean)

private val AppTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.korean(title = true),
        displayMedium = t.displayMedium.korean(title = true),
        displaySmall = t.displaySmall.korean(title = true),
        headlineLarge = t.headlineLarge.korean(title = true),
        headlineMedium = t.headlineMedium.korean(title = true),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp).korean(title = true),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp).korean(title = true),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold).korean(title = true),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold).korean(title = true),
        bodyLarge = t.bodyLarge.korean(),
        bodyMedium = t.bodyMedium.korean(),
        bodySmall = t.bodySmall.korean(),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold).korean(),
        labelMedium = t.labelMedium.korean(),
        labelSmall = t.labelSmall.korean(),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Tabular figures keep hour totals from jumping around as they change. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun BamnatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        // [SHFT-05] The app resolves NIGHT_SHIFT before it gets here (rememberThemeMode); elsewhere it is the phone's.
        ThemeMode.SYSTEM, ThemeMode.NIGHT_SHIFT -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalCalendarColors provides if (dark) DarkCalendarColors else LightCalendarColors) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/** Palette offered for shift types. */
val ShiftPalette: List<Long> = listOf(
    0xFF2F80ED, 0xFF2F5BEA, 0xFF6C5CE7, 0xFF9B51E0, 0xFFE84393, 0xFFE5484D,
    0xFFF2994A, 0xFFF2C94C, 0xFF27AE60, 0xFF00897B, 0xFF00A3C4, 0xFF8E9AAF,
)

/** [EXTRA-01] Palette offered for kinds of extra work; the first three are the built-in 잔업·특근·대근. */
val ExtraPalette: List<Long> = listOf(
    0xFFE8900C, 0xFFE03131, 0xFF0E9F6E, 0xFF7048E8, 0xFF1C7ED6, 0xFFD6336C,
    0xFF0B7285, 0xFF5C940D, 0xFF862E9C, 0xFFB35C00, 0xFF495057, 0xFF2B8A3E,
)

/** Soft backgrounds for memo cards: index 0 is the plain surface. */
val MemoPaletteLight: List<Color> = listOf(
    Color.Unspecified, Color(0xFFFFF4CC), Color(0xFFFFE2CC), Color(0xFFFFDDE4),
    Color(0xFFE6DDFF), Color(0xFFD9E7FF), Color(0xFFCFF3EA), Color(0xFFE8F5D0),
)
val MemoPaletteDark: List<Color> = listOf(
    Color.Unspecified, Color(0xFF4A3F12), Color(0xFF4D2E14), Color(0xFF4D2230),
    Color(0xFF332A5C), Color(0xFF1E335C), Color(0xFF16443B), Color(0xFF2E4119),
)
