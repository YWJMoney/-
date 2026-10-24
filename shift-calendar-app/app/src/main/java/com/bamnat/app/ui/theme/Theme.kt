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

// [A11Y-01] Text colours reach 4.5:1 on the page, cards and sheets (ContrastTest); secondary, tertiary and error
// were made a shade darker in 1.22 for that.
internal val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BEA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF00756A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8F2EA),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFFA14F00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE2CC),
    onTertiaryContainer = Color(0xFF2E1500),
    error = Color(0xFFC4261A),
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

internal val DarkColors = darkColorScheme(
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
    val income: Color = Color(0xFF0F7A56),
    val expense: Color = Color(0xFFC53030),
    /** [VIS-01] A day whose alarms were turned off. */
    val alarmOff: Color = Color(0xFFC4261A),
    /** [VIS-01] A day the user changed by hand. */
    val changed: Color = Color(0xFFA14F00),
    /** [SLP-14] Sleep and naps on the today strip, and the button that starts a sleep. */
    val sleep: Color = Color(0xFF4F5BD5),
    val nap: Color = Color(0xFFA5ADF0),
    val sleepContainer: Color = Color(0xFFCDD1FB),
    val onSleepContainer: Color = Color(0xFF1F287A),
    /**
     * [VIS-03] Leave marks (연·반·¼ squares and the leave lines): violet for 연차, blue for other paid leave,
     * grey for unpaid, teal for 보상휴가 [FEAT-10, PAYX-18]. Letters on them come from contentColorOn (4.45:1),
     * and the square itself stands out from the page at 3:1 (ContrastTest).
     */
    val leaveAnnual: Color = Color(0xFF7048E8),
    val leavePaid: Color = Color(0xFF1A75CC),
    val leaveUnpaid: Color = Color(0xFF666E77),
    val leaveComp: Color = Color(0xFF0B7F92),
    /**
     * [VIS-03] The pay lines' colours, matching the walkthrough's time bar; the base pay line is the theme's
     * primary. They are also written as text (an amount's colour), so 4.5:1 on cards and sheets (ContrastTest).
     */
    val payOvertime: Color = Color(0xFFA35200),
    val payNight: Color = Color(0xFF6741D9),
    val payHoliday: Color = Color(0xFFC92A2A),
    val payExtra: Color = Color(0xFFC2255C),
    val payExtraHours: Color = Color(0xFF1864AB),
    val payAllowance: Color = Color(0xFF237032),
    val payLeave: Color = Color(0xFF7048E8),
)

// [VIS-01] Weekend numbers and the extra-hours text reach 4.5:1 on white and on a shift's tint.
// [A11Y-01] And on cards and sheets too (ContrastTest): the reds, oranges and greens are a shade darker since 1.22.
internal val LightCalendarColors = CalendarColors(
    sunday = Color(0xFFC4261A),
    saturday = Color(0xFF2F5BEA),
    warning = Color(0xFFC4261A),
    warningContainer = Color(0xFFFEE4E2),
    holidayWork = Color(0xFFA14F00),
    memoDot = Color(0xFF00897B),
)

internal val DarkCalendarColors = CalendarColors(
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
    leaveAnnual = Color(0xFFB197FC),
    leavePaid = Color(0xFF74C0FC),
    leaveUnpaid = Color(0xFFADB5BD),
    leaveComp = Color(0xFF66D9E8),
    payOvertime = Color(0xFFFFA94D),
    payNight = Color(0xFFB197FC),
    payHoliday = Color(0xFFFF8787),
    payExtra = Color(0xFFF783AC),
    payExtraHours = Color(0xFF74C0FC),
    payAllowance = Color(0xFF69DB7C),
    payLeave = Color(0xFFB197FC),
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

/** Palette offered for shift types. [A11Y-03] Every colour carries its letters at 4.45:1 or more (ContrastTest). */
val ShiftPalette: List<Long> = listOf(
    0xFF2F80ED, 0xFF2F5BEA, 0xFF6C5CE7, 0xFF9B51E0, 0xFFE84393, 0xFFE5484D,
    0xFFF2994A, 0xFFF2C94C, 0xFF27AE60, 0xFF00857A, 0xFF00A3C4, 0xFF8E9AAF,
)

/** [EXTRA-01] Palette offered for kinds of extra work; the first three are the built-in 잔업·특근·대근. */
val ExtraPalette: List<Long> = listOf(
    0xFFE8900C, 0xFFE03131, 0xFF0E9F6E, 0xFF7048E8, 0xFF1A75CC, 0xFFD6336C,
    0xFF0B7285, 0xFF5C940D, 0xFF862E9C, 0xFFB35C00, 0xFF495057, 0xFF268038,
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
