package com.bamnat.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.WorkTime
import java.time.LocalTime

/** What [TimeRangeDialog] hands back: a shift's clock span and unpaid break. */
data class TimeRange(val start: LocalTime, val end: LocalTime, val breakMinutes: Int)

/**
 * [DAYW-11] Start, end and break of a shift in one window (ux-review E 후보 3, I 리뷰 I2·I12): the two times
 * sit side by side, the one being turned is underlined, and the work time under them follows every turn,
 * so a night shift's "22:00 → 07:00, 1시간 쉬고 8시간" is seen whole before it is saved. Used by the day
 * sheet and the shift type editor; [note] says what the edit changes (e.g. "연차 4시간은 끝에서 빠져요").
 */
@Composable
fun TimeRangeDialog(
    title: String,
    initial: TimeRange,
    onDismiss: () -> Unit,
    presets: List<LocalTime> = DEFAULT_PRESETS,
    note: String? = null,
    startOnEnd: Boolean = false,
    onConfirm: (TimeRange) -> Unit,
) {
    var start by rememberSaveable { mutableIntStateOf(initial.start.toSecondOfDay() / 60) }
    var end by rememberSaveable { mutableIntStateOf(initial.end.toSecondOfDay() / 60) }
    var rest by rememberSaveable { mutableIntStateOf(initial.breakMinutes) }
    // 0 = start, 1 = end: which time the wheels turn. Survives a fold like the values.
    var which by rememberSaveable { mutableIntStateOf(if (startOnEnd) 1 else 0) }
    var jump by rememberSaveable { mutableIntStateOf(0) }
    val startTime = LocalTime.of(start / 60, start % 60)
    val endTime = LocalTime.of(end / 60, end % 60)
    val span = WorkTime.spanMinutes(startTime, endTime)
    val work = WorkTime.workMinutes(startTime, endTime, rest)
    val turning = if (which == 0) start else end
    val setTurning: (Int) -> Unit = { m -> if (which == 0) start = m else end = m }
    val jumpTo: (LocalTime) -> Unit = { t ->
        setTurning(t.hour * 60 + t.minute)
        jump++
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EndPoint("시작", startTime, which == 0, Modifier.weight(1f)) { which = 0; jump++ }
                    Text("→", style = MaterialTheme.typography.titleMedium)
                    EndPoint("끝", endTime, which == 1, Modifier.weight(1f)) { which = 1; jump++ }
                }
                Box(Modifier.fillMaxWidth().height(WHEEL_ROW_HEIGHT * 3), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(WHEEL_ROW_HEIGHT)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    )
                    // Keyed on which end is turning, so switching ends shows that end's time at once.
                    androidx.compose.runtime.key(which) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            WheelHalf(count = 24, value = turning / 60, jump = jump, label = "시", onValue = { setTurning(it * 60 + turning % 60) })
                            WheelHalf(count = 60, value = turning % 60, jump = jump, label = "분", onValue = { setTurning(turning / 60 * 60 + it) })
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (t in presets) AssistChip(onClick = { jumpTo(t) }, label = { Text(Fmt.time(t)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("휴게 시간", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    MinutesStepper(minutes = rest, onChange = { rest = it.coerceAtMost(span) }, step = 10, max = 12 * 60, dialogTitle = "휴게 시간")
                }
                Text(
                    buildString {
                        append("근무 ${HoursFormat.korean(work)}")
                        if (WorkTime.isOvernight(startTime, endTime)) append(" · 다음 날 ${Fmt.time(endTime)}에 끝나요")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(TimeRange(startTime, endTime, rest.coerceAtMost(span))) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun EndPoint(label: String, time: LocalTime, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier
            .heightIn(min = 56.dp)
            .semantics { selected = chosen }
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Tab, onClickLabel = "$label 시각 고르기", onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (chosen) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Fmt.time(time), style = MaterialTheme.typography.headlineSmall.merge(NumberStyle))
        }
    }
}
