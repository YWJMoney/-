package com.bamnat.app.ui.calendar

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.bamnat.core.holiday.KoreanHolidays

/**
 * [CAL-04] The red day's name under the date. The full name where the cell is wide (the unfolded Fold),
 * a shorter one on a phone, and only as small as 8sp when a big font leaves even that too wide.
 */
@Composable
internal fun HolidayLabel(name: String, fontSize: TextUnit, color: Color, lineHeight: TextUnit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        // [CAL-52] Hangul lines stand about 1.4 times their size: in a short cell the name gets smaller, not cut at the bottom.
        val tallest = constraints.maxHeight
        @Suppress("NAME_SHADOWING") val fontSize = if (tallest == androidx.compose.ui.unit.Constraints.Infinity) fontSize
            else with(density) { minOf(fontSize.toPx(), (tallest / HANGUL_LINE).coerceAtLeast(1f)).toSp() }
        // The line itself no taller than the room either, or the trimmed line still runs a pixel past it.
        @Suppress("NAME_SHADOWING") val lineHeight = if (tallest == androidx.compose.ui.unit.Constraints.Infinity || !lineHeight.isSp) lineHeight
            else with(density) { minOf(lineHeight.toPx(), (tallest - 1f).coerceAtLeast(1f)).toSp() }
        val (text, size) = remember(name, fontSize, width, density) {
            val labels = KoreanHolidays.labels(name)
            fun widthOf(s: String, sz: TextUnit) =
                measurer.measure(s, TextStyle(fontSize = sz, fontWeight = FontWeight.Medium), maxLines = 1).size.width
            val fits = labels.firstOrNull { widthOf(it, fontSize) <= width }
            if (fits != null) {
                fits to fontSize
            } else {
                val last = labels.last()
                val scaled = fontSize.value * width / widthOf(last, fontSize).coerceAtLeast(1)
                last to maxOf(scaled, 8f).sp
            }
        }
        Text(
            text,
            fontSize = size,
            lineHeight = lineHeight,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = FooterTrim,
        )
    }
}

private const val HANGUL_LINE = 1.5f
