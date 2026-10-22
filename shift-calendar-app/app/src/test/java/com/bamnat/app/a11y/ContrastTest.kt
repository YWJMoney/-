package com.bamnat.app.a11y

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.bamnat.app.ui.theme.CalendarColors
import com.bamnat.app.ui.theme.DarkCalendarColors
import com.bamnat.app.ui.theme.DarkColors
import com.bamnat.app.ui.theme.LightCalendarColors
import com.bamnat.app.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [A11Y-01] The app's colours keep text readable: 4.5:1 for text, 3:1 for borders and icons that carry meaning
 * (WCAG 2.1 success criteria 1.4.3 and 1.4.11, https://www.w3.org/TR/WCAG21/#contrast-minimum and
 * #non-text-contrast). Checked for the light and the dark theme, on the backgrounds text sits on: the page
 * (surface), cards and sheets (surfaceContainerLowest/Low/surfaceContainer). The phone's own colours
 * (다이내믹 컬러, off by default) come from Android's palette and are not ours to check.
 */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(theme: String, s: ColorScheme, cal: CalendarColors): List<String> {
        val out = mutableListOf<String>()
        fun need(name: String, fg: Color, bg: Color, min: Double) {
            val r = ratio(fg, bg)
            if (r < min) out += "$theme $name ${"%.2f".format(r)} < $min"
        }
        // Material's pairs: what is written on each filled colour.
        listOf(
            Triple("onPrimary/primary", s.onPrimary, s.primary), Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
            Triple("onSecondary/secondary", s.onSecondary, s.secondary),
            Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
            Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
            Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
            Triple("onError/error", s.onError, s.error), Triple("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
            Triple("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant),
            Triple("onSleepContainer/sleepContainer", cal.onSleepContainer, cal.sleepContainer),
        ).forEach { (name, fg, bg) -> need(name, fg, bg, TEXT) }

        // Text colours on the page and on cards and sheets.
        val backgrounds = listOf("surface" to s.surface, "lowest" to s.surfaceContainerLowest, "low" to s.surfaceContainerLow,
            "container" to s.surfaceContainer)
        val text = listOf(
            "onSurface" to s.onSurface, "onSurfaceVariant" to s.onSurfaceVariant, "primary" to s.primary, "secondary" to s.secondary,
            "tertiary" to s.tertiary, "error" to s.error, "sunday" to cal.sunday, "saturday" to cal.saturday, "warning" to cal.warning,
            "holidayWork" to cal.holidayWork, "income" to cal.income, "expense" to cal.expense, "alarmOff" to cal.alarmOff,
            "changed" to cal.changed, "sleep" to cal.sleep,
        )
        for ((bgName, bg) in backgrounds) for ((name, fg) in text) need("$name on $bgName", fg, bg, TEXT)
        // A warning written inside its own box.
        need("warning on warningContainer", cal.warning, cal.warningContainer, TEXT)
        // Borders of fields and dots that mark a day.
        for ((bgName, bg) in backgrounds) {
            need("outline on $bgName", s.outline, bg, NON_TEXT)
            need("memoDot on $bgName", cal.memoDot, bg, NON_TEXT)
        }
        return out
    }

    @Test fun lightThemeTextIsReadable() = assertEquals(emptyList<String>(), check("light", LightColors, LightCalendarColors))

    @Test fun darkThemeTextIsReadable() = assertEquals(emptyList<String>(), check("dark", DarkColors, DarkCalendarColors))

    @Test fun theRatioIsWcags() {
        assertEquals(21.0, ratio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, ratio(Color.White, Color.White), 0.001)
        // #767676 on white is the usual example of the lightest grey that passes 4.5:1.
        assertEquals(4.54, ratio(Color(0xFF767676), Color.White), 0.01)
    }

    private companion object {
        const val TEXT = 4.5
        const val NON_TEXT = 3.0
    }
}
