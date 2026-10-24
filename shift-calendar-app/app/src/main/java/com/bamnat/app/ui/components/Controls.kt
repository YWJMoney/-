package com.bamnat.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.app.ui.theme.NumberStyle

/**
 * [UX-08] The one "+ 추가" button: a text button with a plus, the same on every screen (it replaces the
 * AssistChips and outlined buttons that each screen had for adding a row; notes/1.22-285/E.md 5장 2번).
 * [label] says what is added ("지출 적기", "알람 추가"); the plus says it adds.
 */
@Composable
fun AddButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
}

/**
 * [UX-08] The one − value + control for whole numbers (days, people, times, months): the same buttons as
 * [MinutesStepper], so holding keeps stepping and the ends answer with a firmer tick. The value sits in a
 * fixed-width box, so "1명" and "12명" never move the buttons, and shrinks rather than wrapping at a big font.
 * TalkBack hears "[name] 3명" on the value and "[name] 줄이기/늘리기" on the buttons. [onValueClick] lets
 * the user type an exact value (screens that had a dialog for it keep it).
 */
@Composable
fun CountStepper(
    value: Int,
    onChange: (Int) -> Unit,
    name: String,
    modifier: Modifier = Modifier,
    range: IntRange = 0..99,
    step: Int = 1,
    format: (Int) -> String = { it.toString() },
    valueWidth: Dp = COUNT_VALUE_WIDTH,
    enabled: Boolean = true,
    onValueClick: (() -> Unit)? = null,
    /** Runs past the end back to the start, like a day of the month (말일 → 1일). */
    wrap: Boolean = false,
) {
    // The shown value runs ahead of the caller's state, so quick taps and a hold all count before it answers.
    var shown by remember { mutableIntStateOf(value) }
    androidx.compose.runtime.LaunchedEffect(value) { shown = value }
    val save by rememberUpdatedState(onChange)
    val haptics = LocalHapticFeedback.current
    val stepTo: (Int) -> Unit = { raw ->
        val v = when {
            !wrap -> raw.coerceIn(range.first, range.last)
            raw > range.last -> range.first
            raw < range.first -> range.last
            else -> raw
        }
        haptics.performHapticFeedback(
            if (!wrap && (v == range.first || v == range.last)) HapticFeedbackType.Reject else HapticFeedbackType.SegmentFrequentTick,
        )
        if (v != shown) {
            shown = v
            save(v)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        RepeatIconButton(onStep = { stepTo(shown - step) }, enabled = enabled && (wrap || shown > range.first)) {
            Icon(Icons.Filled.Remove, contentDescription = "$name 줄이기")
        }
        val style = MaterialTheme.typography.titleMedium.merge(NumberStyle)
        Box(
            Modifier
                .width(valueWidth)
                .clip(MaterialTheme.shapes.small)
                .then(if (onValueClick != null && enabled) Modifier.clickable(onClickLabel = "$name 직접 입력", onClick = onValueClick) else Modifier)
                .padding(vertical = 8.dp)
                .semantics {
                    contentDescription = name
                    stateDescription = format(shown)
                },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                format(shown),
                style = style.copy(
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize),
            )
        }
        RepeatIconButton(onStep = { stepTo(shown + step) }, enabled = enabled && (wrap || shown < range.last)) {
            Icon(Icons.Filled.Add, contentDescription = "$name 늘리기")
        }
    }
}

private val COUNT_VALUE_WIDTH = 72.dp

/**
 * [UX-08] A stepper row: what it sets on the left (and a hint under it), the stepper on the right. On a narrow
 * cover screen or at a big font the stepper moves under the words instead of squeezing them into a tower.
 */
@Composable
fun StepperRow(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    stepper: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val stacked = maxWidth < STACK_BELOW || fontScale >= 1.5f
        val words: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                words(Modifier.fillMaxWidth())
                Box(Modifier.padding(top = 4.dp)) { stepper() }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                words(Modifier.weight(1f))
                stepper()
            }
        }
    }
}

/** Narrower than this, the words and the stepper (about 200dp) cannot share a line with room for the words. */
private val STACK_BELOW = 300.dp
