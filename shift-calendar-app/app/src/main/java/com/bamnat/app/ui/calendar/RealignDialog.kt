package com.bamnat.app.ui.calendar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.FilterChip
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.PatternTimeline
import java.time.LocalDate

/**
 * [SCHED-03] I 리뷰 I5·G P26: "이 날부터 순서 다시 맞추기" from a day's long-press menu. The user says which step of
 * the order [date] is; the order restarts there and every day before stays. The days set by hand in the next
 * [AHEAD_DAYS] days are listed, ticked, because they were usually the workaround for the order being off.
 */
@Composable
internal fun RealignDialog(
    date: LocalDate,
    snap: ScheduleSnapshot,
    onDismiss: () -> Unit,
    onConfirm: (step: Int, clear: List<LocalDate>) -> Unit,
) {
    val rotation = snap.resolver.patternOn(date) as? WorkPattern.Rotation ?: return
    var step by rememberSaveable(date) { mutableIntStateOf(rotation.stepIndexOn(date)) }
    val handChanged = remember(snap, date) { PatternTimeline.handChangedFrom(snap.entries, date, date.plusDays(AHEAD_DAYS)) }
    // Kept as the days left out, so a fold keeps the ticks.
    var keep by rememberSaveable(date) { mutableStateOf(listOf<Long>()) }
    val clear = handChanged.filter { it.toEpochDay() !in keep }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${Fmt.monthDay(date)}부터 다시 맞추기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${Fmt.dayTitle(date)}은 순서의 몇 번째 날인가요? 그 전날까지는 그대로 남아요.", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rotation.steps.forEachIndexed { i, id ->
                        val type = id?.let { snap.typesById[it] }
                        FilterChip(
                            selected = step == i,
                            onClick = { step = i },
                            label = { Text("${i + 1}") },
                            leadingIcon = { ShiftBadge(type, size = 18.dp, text = type?.label ?: "휴") },
                        )
                    }
                }
                if (handChanged.isNotEmpty()) {
                    Text("직접 바꾼 날이 있어요. 패턴대로 돌릴 날을 고르세요.", style = MaterialTheme.typography.titleSmall)
                    for (d in handChanged) {
                        val day = snap.resolver.resolve(d)
                        val on = d.toEpochDay() !in keep
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(value = on, role = Role.Checkbox) {
                                    keep = if (it) keep - d.toEpochDay() else keep + d.toEpochDay()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text("${Fmt.dayTitle(d)} · ${day.shiftType?.name ?: "쉬는 날"}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(step, clear) }) { Text("다시 맞추기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** How far ahead hand-set days are offered for clearing: two months of shifts. */
private const val AHEAD_DAYS = 62L
