package com.bamnat.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.ScheduleRepository
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.work.DayWork
import com.bamnat.core.work.ShiftMonth
import com.bamnat.core.work.WorkStyle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** [SHFT-03] Longest name kept for who a day was swapped with. */
internal const val MAX_SWAP_NAME = 20

/** [SHFT-03] The day sheet's work section on a shift calendar: who the shift was swapped with. */
@Composable
internal fun WorkTypeDayRow(day: ResolvedDay, snapshot: ScheduleSnapshot, vm: CalendarViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val style = settings.workStyle
    if (style == null || style == WorkStyle.SHIFT) SwapRow(day, snapshot, vm)
}

/** [DAYW-03] Until when after midnight 지금 퇴근 still ends yesterday's working day. */
internal val LEAVE_NIGHT_END: LocalTime = LocalTime.of(6, 0)

/**
 * [DAYW-03] The working day 지금 퇴근 on [day]'s sheet ends, or null for no chip, on a day calendar:
 * today's; before [LEAVE_NIGHT_END], yesterday's (work run past midnight), from its own sheet or from
 * today's while today's work has not started.
 */
internal fun leaveNowDay(style: WorkStyle?, day: ResolvedDay, today: LocalDate, now: LocalDateTime, yesterday: () -> ResolvedDay): ResolvedDay? {
    if (style != WorkStyle.DAY) return null
    fun working(d: ResolvedDay) = d.isWorkDay && d.shiftFrom != null
    val night = now.toLocalDate() == today && now.toLocalTime().isBefore(LEAVE_NIGHT_END)
    return when (day.date) {
        today -> yesterday().takeIf { y -> night && working(y) && (!working(day) || now.isBefore(day.shiftFrom)) }
            ?: day.takeIf(::working)
        today.minusDays(1) -> day.takeIf { night && working(it) }
        else -> null
    }
}

/** [SHFT-03] "근무 교환 적기" or "교환 · 김간호", and a dialog with the names used before. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwapRow(day: ResolvedDay, snapshot: ScheduleSnapshot, vm: CalendarViewModel) {
    val name = day.entry?.swapWith.orEmpty()
    val cover = day.extras.any { ShiftMonth.isCover(it.kind) }
    // Only a working day or a 대근 is swapped with someone.
    if (!day.isWorkDay && !cover && name.isEmpty()) return
    var editing by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { editing = true },
        label = {
            Text(when {
                name.isEmpty() -> if (cover) "누구 대신 대근했나요" else "근무 교환 적기"
                cover -> "대근 · $name 대신"
                else -> "교환 · $name"
            })
        },
        leadingIcon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null, Modifier.size(18.dp)) },
    )
    if (editing) {
        var text by remember { mutableStateOf(name) }
        // Names used before, most recent first, so a regular partner is one tap.
        val recent = remember(snapshot) {
            snapshot.entries.filter { it.swapWith.isNotBlank() }.sortedByDescending(DayEntry::date)
                .map { it.swapWith.trim() }.distinct().take(6)
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (cover) "누구 대신 일했나요?" else "누구와 근무를 바꿨나요?") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(MAX_SWAP_NAME) },
                        label = { Text("이름") },
                        singleLine = true,
                    )
                    if (recent.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            recent.forEach { r -> SuggestionChip(onClick = { text = r }, label = { Text(r) }) }
                        }
                    }
                    Text("통계에서 이 달의 교환·대근을 모아 볼 수 있어요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.setSwapWith(day.date, text); editing = false }) { Text("저장") }
            },
            dismissButton = {
                if (name.isNotEmpty()) TextButton(onClick = { vm.setSwapWith(day.date, ""); editing = false }) { Text("지우기") }
                else TextButton(onClick = { editing = false }) { Text("취소") }
            },
        )
    }
}

/** [DAYW-03] 지금 퇴근 chip: the time from the end to now as 잔업, or an earlier end, said on the chip. */
@Composable
internal fun LeaveNowChip(day: ResolvedDay, vm: CalendarViewModel, yesterday: Boolean = false) {
    val now = rememberMinute()
    val plan = remember(day, now) { DayWork.leaveNow(day, now) }
    val name = if (yesterday) "어제 퇴근" else "지금 퇴근"
    AssistChip(
        onClick = { vm.leaveNow(day) },
        label = {
            Text(
                when (plan) {
                    is DayWork.LeaveNow.Overtime -> "$name · +${HoursFormat.short(plan.minutes)}"
                    is DayWork.LeaveNow.EarlyEnd -> "$name · ${Fmt.time(plan.end)}"
                    DayWork.LeaveNow.OnTime -> name
                },
                maxLines = 1,
            )
        },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, Modifier.size(18.dp)) },
    )
}

/** [DAYW-03] Writes 지금 퇴근 to a calendar; the day sheet and the today widget both use it. */
internal object LeaveNowWriter {
    /**
     * What was there before (for undo) and what to say, or a null text when there is nothing to write.
     * A 잔업 already on the day is replaced, so pressing again later just moves the time.
     */
    suspend fun write(schedule: ScheduleRepository, day: ResolvedDay, now: LocalDateTime, kindName: String = "잔업"): Pair<DayEntry?, String?> =
        when (val plan = DayWork.leaveNow(day, now)) {
            is DayWork.LeaveNow.Overtime -> {
                val before = schedule.updateDay(day.date) { e ->
                    val i = e.extras.indexOfFirst { it.kindId == ExtraKind.OVERTIME_ID }
                    e.copy(extras = if (i < 0) e.extras + plan.entry else e.extras.toMutableList().also { it[i] = plan.entry })
                }
                before to "${Fmt.monthDay(day.date)}에 $kindName ${HoursFormat.korean(plan.minutes)}${Fmt.obj(HoursFormat.korean(plan.minutes))} 적었어요" +
                    " (${Fmt.timeRange(plan.entry.start!!, plan.entry.end!!)})"
            }
            is DayWork.LeaveNow.EarlyEnd -> {
                val before = schedule.updateDay(day.date) { it.copy(customStart = plan.start, customEnd = plan.end) }
                before to "${Fmt.monthDay(day.date)} 근무를 ${Fmt.time(plan.end)}에 끝냈어요"
            }
            DayWork.LeaveNow.OnTime -> null to null
        }
}
