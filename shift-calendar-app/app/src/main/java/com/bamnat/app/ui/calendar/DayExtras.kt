package com.bamnat.app.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleRepository
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.MinutesStepper
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.WorkTime
import com.bamnat.core.pay.ExtraMinutes
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.ResolvedExtra
import java.time.LocalTime

/**
 * [EXTRA-01][EXTRA-02][EXTRA-05] The day's extra work: each piece with its hours, what the law makes
 * of it and, when shown, what it adds to pay. A tap opens the piece to change it in place; chips
 * below add more with one tap, on a working day or a day off alike.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtrasContent(
    day: ResolvedDay,
    snapshot: ScheduleSnapshot,
    vm: CalendarViewModel,
    insight: DayInsight,
    showAmounts: Boolean,
    onOpenExtraKinds: () -> Unit,
) {
    val cal = LocalCalendarColors.current
    // The piece open for changes; a piece just added opens so its hours can be checked.
    var editing by rememberSaveable(day.date) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (day.extras.any { it.entry.needsReview }) {
            Surface(color = cal.warningContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("예전 기록에서 옮겨 온 추가 근무예요", style = MaterialTheme.typography.titleSmall, color = cal.warning)
                        Text(
                            "이제 추가 근무마다 시각이 있어요. 종류와 시간이 맞는지 보고, 틀리면 눌러서 고치거나 빼 주세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    TextButton(onClick = { vm.markExtrasReviewed(day.date) }) { Text("맞아요") }
                }
            }
        }

        day.extras.forEachIndexed { i, e ->
            ExtraRow(
                day = day, index = i, extra = e, snapshot = snapshot, vm = vm,
                minutes = insight.minutes?.extra(i),
                amount = insight.extraAmounts[i]?.takeIf { showAmounts },
                open = editing == i,
                onToggle = { editing = if (editing == i) null else i },
            )
        }

        if (ExtraText.overlaps(day)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WarningAmber, null, Modifier.size(18.dp), tint = cal.warning)
                Spacer(Modifier.width(8.dp))
                Text("시간이 겹치는 근무가 있어요. 겹친 시간도 따로따로 세니 시각을 확인해 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = cal.warning)
            }
        }

        val choices = remember(snapshot, day) {
            ExtraText.choices(snapshot.kinds, snapshot.shiftTypes, day).filter { ExtraText.indexOf(day, it) < 0 }
        }
        if (day.extras.size < ScheduleRepository.MAX_EXTRAS && choices.isNotEmpty()) {
            if (day.extras.isNotEmpty()) {
                Text("더 넣기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in choices) {
                    AssistChip(
                        onClick = {
                            editing = day.extras.size
                            vm.addExtra(day.date, c.entry, c.name)
                        },
                        label = { Text(c.label, maxLines = 1) },
                        leadingIcon = { ExtraKindMark(c.kind, size = 18.dp) },
                    )
                }
            }
        }
        if (day.extras.isEmpty()) {
            Text(
                if (day.isRestDay) "쉬는 날 일했다면 여기서 넣어 주세요. 근무표는 그대로 두고 일한 시간만 더해져서 급여가 맞게 계산돼요."
                else "근무 뒤 잔업이나 같은 날 대근을 넣어 주세요. 넣은 뒤 눌러서 시각을 바꿀 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenExtraKinds, modifier = Modifier.padding(start = 0.dp)) {
            Icon(Icons.Filled.Tune, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("연장 휴무일 근무 지정")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraRow(
    day: ResolvedDay,
    index: Int,
    extra: ResolvedExtra,
    snapshot: ScheduleSnapshot,
    vm: CalendarViewModel,
    minutes: ExtraMinutes?,
    amount: Long?,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val cal = LocalCalendarColors.current
    val scheme = MaterialTheme.colorScheme
    val name = ExtraText.name(extra)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (open) scheme.surfaceContainerHigh else scheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = if (open) "접기" else "고치기", onClick = onToggle)
            .semantics { stateDescription = if (open) "고치는 중" else "" }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExtraKindMark(extra.kind, size = 24.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(ExtraText.time(extra) + if (extra.entry.compLeave) " · 보상휴가로" else "",
                    style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = scheme.onSurfaceVariant)
            }
            if (amount != null && amount != 0L) {
                Text(
                    (if (amount > 0) "+" else "") + Fmt.won(amount),
                    style = MaterialTheme.typography.titleSmall.merge(NumberStyle),
                    color = scheme.primary,
                )
            }
            IconButton(onClick = { vm.removeExtra(day.date, index, name) }) {
                Icon(Icons.Filled.Close, "$name 빼기", tint = scheme.onSurfaceVariant)
            }
        }
        val law = ExtraText.kind(minutes, day)
        val night = minutes?.minutes?.night ?: 0
        if (law != null) {
            Text(
                law + if (night > 0) " · 야간 ${HoursFormat.korean(night)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = cal.holidayWork,
                modifier = Modifier.padding(start = 36.dp, end = 12.dp),
            )
        }
        if (extra.estimated) {
            Text(
                "근무가 없는 날이라 시각을 몰라 야간 시간은 빼고 셌어요. 눌러서 시각을 적어 주세요.",
                style = MaterialTheme.typography.bodySmall,
                color = cal.warning,
                modifier = Modifier.padding(start = 36.dp, end = 12.dp, top = 2.dp),
            )
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            ExtraEditor(day, index, extra, snapshot, vm, onDone = onToggle)
        }
    }
}

/** [EXTRA-01] Changes one piece in place: its kind, the shift it covered, its clock times and break, or its length. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraEditor(day: ResolvedDay, index: Int, extra: ResolvedExtra, snapshot: ScheduleSnapshot, vm: CalendarViewModel, onDone: () -> Unit) {
    val e = extra.entry
    val update: (ExtraEntry) -> Unit = { vm.updateExtra(day.date, index, it) }
    var picking by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 8.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("종류", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in snapshot.kinds.visible(listOf(e.kindId))) {
                FilterChip(
                    selected = k.id == e.kindId,
                    onClick = { if (k.id != e.kindId) update(e.copy(kindId = k.id)) },
                    label = { Text(k.name, maxLines = 1) },
                    leadingIcon = { ExtraKindMark(k, size = 18.dp) },
                )
            }
        }
        val timedTypes = snapshot.shiftTypes.filter { !it.isOff && it.hasTimes }
        if ((extra.kind.timing is ExtraTiming.AsShift || e.coveredShiftId != null) && timedTypes.isNotEmpty()) {
            Text("대신한 근무", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in timedTypes) {
                    FilterChip(
                        selected = e.coveredShiftId == t.id,
                        // Picking the shift takes its hours, which is what a 대근 of it means.
                        onClick = { if (e.coveredShiftId != t.id) update(ExtraText.cover(extra.kind, t).copy(kindId = e.kindId)) },
                        label = { Text(t.name, maxLines = 1) },
                        leadingIcon = { ShiftBadge(t, size = 18.dp) },
                    )
                }
            }
        }
        val from = extra.start
        val to = extra.end
        if ((e.hasTimes || extra.covered != null) && from != null && to != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeBox(label = "시작", time = from, onClick = { picking = "start" }, modifier = Modifier.weight(1f))
                TimeBox(label = "끝", time = to, onClick = { picking = "end" }, modifier = Modifier.weight(1f))
            }
            val span = WorkTime.spanMinutes(from, to)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("휴게 시간", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                MinutesStepper(
                    minutes = extra.breakMinutes,
                    onChange = { b -> update(e.copy(start = from, end = to, breakMinutes = b, breakStart = e.breakStart ?: extra.breakStart)) },
                    step = 10,
                    max = (span - 10).coerceAtLeast(0),
                    dialogTitle = "휴게 시간",
                )
            }
            Text(
                "일한 시간 ${HoursFormat.korean(extra.minutes)}" + if (WorkTime.isOvernight(from, to)) " · 끝은 다음 날" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (extra.kind.timing) {
                        is ExtraTiming.BeforeShift -> "근무 전 시간"
                        else -> if (day.isWorkDay) "근무 뒤 시간" else "일한 시간"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                MinutesStepper(
                    minutes = e.minutes,
                    onChange = { m -> update(e.copy(minutes = m)) },
                    step = 30,
                    max = ScheduleRepository.MAX_EXTRA_MINUTES,
                    dialogTitle = "${extra.kind.name} 시간",
                )
            }
            // Clock times make night minutes exact; the start follows the shift when there is one.
            TextButton(onClick = {
                val start = from ?: LocalTime.of(9, 0)
                update(e.copy(start = start, end = start.plusMinutes(e.minutes.coerceAtLeast(30).toLong()), minutes = 0))
            }) {
                Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("시각으로 적기")
            }
        }
        // [PAYX-18] 근로기준법 제57조: the company may give time off instead of the pay, by 근로자대표 agreement.
        FilterChip(
            selected = e.compLeave,
            onClick = { update(e.copy(compLeave = !e.compLeave)) },
            label = { Text("돈 대신 보상휴가로 받기") },
        )
        if (e.compLeave) {
            Text(
                "이 시간은 급여에서 빠지고, 가산을 더한 시간만큼 연차 화면의 보상휴가로 쌓여요. 회사가 근로자대표와 서면 합의를 했을 때만 이렇게 받아요(근로기준법 제57조).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // [PAYX-30] 휴일대체: work on a holiday swapped in advance for another day is a working day's; that day is the holiday.
        if (day.isRestDay || com.bamnat.core.holiday.KoreanHolidays.isHoliday(day.date)) {
            com.bamnat.app.ui.work.DateField("휴일대체: 대신 쉬는 날", e.substitute, { update(e.copy(substitute = it?.takeIf { d -> d != day.date })) })
            val sub = e.substitute
            Text(
                if (sub == null) "회사가 미리(보통 24시간 전) 이 휴일과 다른 날을 바꿨다면 그 날을 골라요. 그러면 이 근무는 휴일근로가 아니라 평일 근무로 계산해요."
                else "이 근무는 평일 근무로, ${sub.monthValue}/${sub.dayOfMonth}이 휴일로 계산돼요. 공휴일을 바꾸려면 근로자대표와 서면 합의가 있어야 하고, 노동절은 바꿀 수 없어요(근로기준법 제55조).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("완료") }
        }
    }
    picking?.let { which ->
        val start = extra.start ?: LocalTime.of(9, 0)
        val end = extra.end ?: start.plusHours(8)
        TimePickerDialog(
            title = if (which == "start") "시작 시각" else "끝 시각",
            initial = if (which == "start") start else end,
            presets = timePresets(snapshot),
            onDismiss = { picking = null },
        ) { t ->
            picking = null
            val brk = extra.breakMinutes
            val next = if (which == "start") e.copy(start = t, end = end) else e.copy(start = start, end = t)
            val span = WorkTime.spanMinutes(next.start!!, next.end!!)
            update(next.copy(breakMinutes = brk.coerceAtMost((span - 10).coerceAtLeast(0)), breakStart = e.breakStart ?: extra.breakStart, minutes = 0))
        }
    }
}

/** Shortcuts in the time picker from the user's own shifts: their starts and ends. */
internal fun timePresets(snapshot: ScheduleSnapshot): List<LocalTime> =
    snapshot.shiftTypes.flatMap { listOfNotNull(it.start, it.end) }.distinct().sorted().take(10)
        .ifEmpty { com.bamnat.app.ui.components.DEFAULT_PRESETS }
