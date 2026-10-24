package com.bamnat.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.app.ui.theme.NumberStyle
import java.time.LocalTime
import kotlin.math.roundToLong

/*
 * [INPUT-01] 묶음 Q's shared direct inputs (계획/1.24-Q-개발계획.md 347): a value is tapped, picked or stepped,
 * never typed as text. Each one keeps its words on one line at the phone's 200% text by shrinking them.
 */

/**
 * [INPUT-01] A day of the month picked on a 7-wide grid of 1~31. With [lastIsMonthEnd], 31 reads "말일" (a short
 * month takes its last day). TalkBack hears "매달 25일, 선택됨".
 */
@Composable
fun DayOfMonthPicker(day: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier, lastIsMonthEnd: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..31).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { d ->
                    val selected = d == day
                    val label = if (d == 31 && lastIsMonthEnd) "말일" else "$d"
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) Selection.container else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (selected) Selection.onContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onPick(d)
                            }
                            .semantics { contentDescription = if (d == 31 && lastIsMonthEnd) "매달 말일" else "매달 ${d}일" },
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp, vertical = 10.dp)) {
                            val style = MaterialTheme.typography.titleSmall.merge(NumberStyle)
                            BasicText(
                                label,
                                style = style.copy(color = androidx.compose.material3.LocalContentColor.current, textAlign = TextAlign.Center),
                                maxLines = 1,
                                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize),
                            )
                        }
                    }
                }
                // The last row keeps the grid's widths.
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** [INPUT-01] "매달 25일" or "매달 말일", as the grid says it. */
fun dayOfMonthLabel(day: Int, lastIsMonthEnd: Boolean = true): String = if (day >= 31 && lastIsMonthEnd) "매달 말일" else "매달 ${day}일"

/**
 * [INPUT-01] A clock time shown like a field and set with the two-wheel [TimePickerDialog]: nothing is typed.
 * [time] null shows [empty] ("넣지 않음"); with [clearable] a ✕ takes the time out again.
 */
@Composable
fun TimeField(
    label: String,
    time: LocalTime?,
    onChange: (LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = false,
    empty: String = "넣지 않음",
    start: LocalTime = LocalTime.of(9, 0),
    presets: List<LocalTime> = DEFAULT_PRESETS,
) {
    var picking by remember { mutableStateOf(false) }
    val shown = time?.let { "%02d:%02d".format(it.hour, it.minute) }
    Surface(
        onClick = { picking = true },
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = shown ?: empty
            },
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                val style = MaterialTheme.typography.titleMedium.merge(NumberStyle)
                BasicText(
                    shown ?: empty,
                    style = style.copy(color = if (shown != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize),
                )
            }
            if (clearable && time != null) {
                IconButton(onClick = { onChange(null) }) { Icon(Icons.Filled.Close, contentDescription = "$label 지우기") }
            }
        }
    }
    if (picking) TimePickerDialog(
        title = label,
        initial = time ?: start,
        presets = presets,
        onDismiss = { picking = false },
        onConfirm = { onChange(it); picking = false },
    )
}

/**
 * [INPUT-01] A small number picked from the values people use ([quick], one tap) or stepped by [step] with − and +
 * (held, it keeps stepping): 공수, 인상률, 수수료율, 연차 일수. [value] null (not set yet) shows "–" and the first
 * step starts from [start]. The value box has a fixed width so the buttons never move.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickDecimalChips(
    value: Double?,
    onChange: (Double) -> Unit,
    name: String,
    quick: List<Double>,
    step: Double,
    range: ClosedFloatingPointRange<Double>,
    modifier: Modifier = Modifier,
    unit: String = "",
    start: Double = quick.firstOrNull() ?: range.start,
    format: (Double) -> String = { decimalText(it) + unit },
    chipLabel: (Double) -> String = format,
    valueWidth: Dp = 96.dp,
) {
    val haptics = LocalHapticFeedback.current
    val stepTo: (Double) -> Unit = { raw ->
        val v = snap(raw, step).coerceIn(range.start, range.endInclusive)
        haptics.performHapticFeedback(if (v == range.start || v == range.endInclusive) HapticFeedbackType.Reject else HapticFeedbackType.SegmentFrequentTick)
        if (v != value) onChange(v)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (quick.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            quick.forEach { q -> FilterChip(selected = value == q, onClick = { onChange(q) }, label = { Text(chipLabel(q)) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatIconButton(onStep = { stepTo((value ?: start) - step) }, enabled = value == null || value > range.start) {
                Icon(Icons.Filled.Remove, contentDescription = "$name ${format(step)} 줄이기")
            }
            val style = MaterialTheme.typography.titleMedium.merge(NumberStyle)
            Box(
                Modifier.width(valueWidth).semantics {
                    contentDescription = name
                    stateDescription = value?.let(format) ?: "넣지 않음"
                },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    value?.let(format) ?: "–",
                    style = style.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize),
                )
            }
            RepeatIconButton(onStep = { stepTo((value ?: (start - step)) + step) }, enabled = value == null || value < range.endInclusive) {
                Icon(Icons.Filled.Add, contentDescription = "$name ${format(step)} 늘리기")
            }
        }
    }
}

/** 1.0 → "1", 0.25 → "0.25", 3.50 → "3.5". */
fun decimalText(v: Double): String =
    if (v == v.roundToLong().toDouble()) v.roundToLong().toString() else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

/** [raw] on the [step] grid, without the float drift of adding 0.1 ten times. */
internal fun snap(raw: Double, step: Double): Double = (Math.round(raw / step) * step * 10_000).roundToLong() / 10_000.0
