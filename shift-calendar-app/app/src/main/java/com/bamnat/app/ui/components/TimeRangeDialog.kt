package com.bamnat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.FitText
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.hours.HoursFormat
import java.time.LocalTime

/**
 * [UX-08] A shift's times as one value: when it starts, when it ends (the next morning if it ends at or
 * before the start) and the unpaid break inside it.
 */
data class TimeRange(val start: LocalTime, val end: LocalTime, val breakMinutes: Int = 0) {
    /** Ends the next day: 22:00~06:00, or a full 24 hours (09:00~09:00). */
    val overnight: Boolean get() = !end.isAfter(start)

    /** Start to end, 1 to 1440 minutes. */
    val spanMinutes: Int get() = Math.floorMod(end.toSecondOfDay() / 60 - start.toSecondOfDay() / 60, DAY).let { if (it == 0) DAY else it }

    /** The time worked: the span less the break. */
    val workMinutes: Int get() = (spanMinutes - breakMinutes).coerceAtLeast(0)

    private companion object {
        const val DAY = 24 * 60
    }
}

/** Which part of the range the wheels below are changing. */
private enum class RangePart { START, END, BREAK }

/**
 * [UX-08] Start, end and break in one window, instead of three pickers one after another (I 리뷰 I2·I12,
 * notes/1.22-285/E.md 5장 3번). The three values sit on top as tabs; the wheels, ±10·30분 and the usual shift
 * times below change the chosen one. The line under the tabs says what the user is about to save, "9시간 ·
 * 휴게 1시간 빼면 8시간", and "다음날" when it ends the next morning. The day sheet, the shift editor and the
 * first setup share it. Survives folding and unfolding (the values are saveable).
 *
 * [note] goes above the tabs, for example the shift's own times when a day is changed ("원래 09:00~18:00").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeRangeDialog(
    title: String,
    initial: TimeRange,
    onDismiss: () -> Unit,
    onConfirm: (TimeRange) -> Unit,
    showBreak: Boolean = true,
    note: String? = null,
    confirmLabel: String = "완료",
    presets: List<LocalTime> = DEFAULT_PRESETS,
    maxBreakMinutes: Int = 4 * 60,
    /** Opens on the break, for a caller whose user tapped the break ("휴게 1시간") rather than the times. */
    startOnBreak: Boolean = false,
) {
    var startMin by rememberSaveable { mutableIntStateOf(initial.start.toSecondOfDay() / 60) }
    var endMin by rememberSaveable { mutableIntStateOf(initial.end.toSecondOfDay() / 60) }
    var breakMin by rememberSaveable { mutableIntStateOf(initial.breakMinutes.coerceIn(0, maxBreakMinutes)) }
    var partIndex by rememberSaveable { mutableIntStateOf(if (startOnBreak && showBreak) RangePart.BREAK.ordinal else RangePart.START.ordinal) }
    // Bumped by a shortcut, so the wheels jump to the time it set.
    var jump by rememberSaveable { mutableIntStateOf(0) }
    val part = RangePart.entries[partIndex]
    val range = TimeRange(LocalTime.ofSecondOfDay(startMin * 60L), LocalTime.ofSecondOfDay(endMin * 60L), breakMin)
    val breakTooLong = showBreak && breakMin >= range.spanMinutes

    fun setTime(t: LocalTime) {
        val m = t.toSecondOfDay() / 60
        if (part == RangePart.END) endMin = m else startMin = m
        jump++
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PartTab("시작", clock(range.start), part == RangePart.START, Modifier.weight(1f)) { partIndex = RangePart.START.ordinal }
                    PartTab(if (range.overnight) "끝 (다음날)" else "끝", clock(range.end), part == RangePart.END, Modifier.weight(1f)) {
                        partIndex = RangePart.END.ordinal
                    }
                    if (showBreak) {
                        PartTab("휴게", HoursFormat.korean(breakMin), part == RangePart.BREAK, Modifier.weight(1f)) { partIndex = RangePart.BREAK.ordinal }
                    }
                }
                Text(
                    when {
                        breakTooLong -> "휴게가 근무 시간보다 길어요"
                        showBreak && breakMin > 0 -> "${HoursFormat.korean(range.spanMinutes)} · 휴게 빼면 ${HoursFormat.korean(range.workMinutes)}"
                        else -> HoursFormat.korean(range.spanMinutes)
                    },
                    style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                    color = if (breakTooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                if (part == RangePart.BREAK) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        MinutesStepper(minutes = breakMin, onChange = { breakMin = it }, step = 10, max = maxBreakMinutes, dialogTitle = "휴게 시간")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                            for (m in BREAK_CHOICES) {
                                FilterChip(selected = breakMin == m, onClick = { breakMin = m }, label = { Text(HoursFormat.korean(m)) })
                            }
                        }
                    }
                } else {
                    val shown = if (part == RangePart.END) endMin else startMin
                    // A new pair of wheels for each tab, starting at that tab's time.
                    key(part) {
                        Box(Modifier.fillMaxWidth().height(WHEEL_ROW_HEIGHT * 5), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.fillMaxWidth().height(WHEEL_ROW_HEIGHT).clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                            )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                WheelHalf(count = 24, value = shown / 60, jump = jump, label = "시", onValue = { h ->
                                    val m = h * 60 + (if (part == RangePart.END) endMin else startMin) % 60
                                    if (part == RangePart.END) endMin = m else startMin = m
                                })
                                WheelHalf(count = 60, value = shown % 60, jump = jump, label = "분", onValue = { mm ->
                                    val m = (if (part == RangePart.END) endMin else startMin) / 60 * 60 + mm
                                    if (part == RangePart.END) endMin = m else startMin = m
                                })
                            }
                        }
                    }
                    // Four equal shares that shrink their words at a big font instead of breaking "+30분" into a tower.
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        for (delta in listOf(-30, -10, 10, 30)) {
                            TextButton(
                                onClick = { setTime(LocalTime.ofSecondOfDay(shown * 60L).plusMinutes(delta.toLong())) },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                            ) {
                                FitText(if (delta > 0) "+${delta}분" else "${delta}분", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in presets) AssistChip(onClick = { setTime(t) }, label = { Text(clock(t)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(range) }, enabled = !breakTooLong) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun clock(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

private val BREAK_CHOICES = listOf(0, 30, 60, 90, 120)

/** One of the three values on top: its name small, its value big; the chosen one in the [Selection] colours. */
@Composable
private fun PartTab(name: String, value: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) Selection.container else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) Selection.onContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FitText(name, style = MaterialTheme.typography.labelMedium)
            FitText(value, style = MaterialTheme.typography.titleMedium.merge(NumberStyle))
        }
    }
}
