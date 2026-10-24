package com.bamnat.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * [UX-06] One line that stays whole at every text size: at the largest sizes a tab's name or the month
 * was cut ("근무·") or pushed what came after it off the screen, so the text shrinks just enough to fit
 * the room it has, never below what [minSp] is at the normal text size (so at 200% it may shrink further
 * than at 100%, but never below the smallest size the app uses anyway). TalkBack reads the same words.
 */
@Composable
internal fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, minSp: Float = 9f) =
    FitText(androidx.compose.ui.text.AnnotatedString(text), style, modifier, minSp)

/** [CAL-52] [FitText] for text with smaller parts in it ("2026년" small before "10월"), shrinking all of it together. */
@Composable
internal fun FitText(text: androidx.compose.ui.text.AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, minSp: Float = 9f) {
    if (!style.fontSize.isSp) {
        Text(text, style = style, modifier = modifier, maxLines = 1, softWrap = false)
        return
    }
    val floor = minSp / LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val width = constraints.maxWidth
        val size = remember(text, width, style, floor) {
            var sp = style.fontSize.value
            while (sp > floor && measurer.measure(text, style.copy(fontSize = sp.sp), maxLines = 1, softWrap = false).size.width > width) {
                sp = maxOf(floor, sp * 0.92f)
            }
            sp.sp
        }
        Text(text, style = style.copy(fontSize = size), maxLines = 1, softWrap = false)
    }
}

/**
 * [VIS-05] A search box's hint: [full] with its examples where it fits on one line, else [short] (1.23 점검: the
 * examples were cut with … on the Fold, the tablet's side pane and large text).
 */
@Composable
internal fun HintText(full: String, short: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val style = androidx.compose.material3.LocalTextStyle.current
        val fits = remember(full, style, constraints.maxWidth) {
            measurer.measure(full, style, maxLines = 1, softWrap = false).size.width <= constraints.maxWidth
        }
        Text(if (fits) full else short, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
