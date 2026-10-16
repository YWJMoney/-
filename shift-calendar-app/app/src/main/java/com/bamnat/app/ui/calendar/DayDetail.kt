package com.bamnat.app.ui.calendar

import com.bamnat.core.holiday.KoreanLunar
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.core.pay.LeaveBook
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.Leave
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.model.DayAlarm
import com.bamnat.core.alarm.PlannedAlarm
import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.app.data.ScheduleRepository
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
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
import com.bamnat.app.ui.money.DayMoneySections
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.MinutesStepper
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.app.ui.components.memoBackground
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.FeatureGate
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.pay.WageType
import com.bamnat.core.memo.Memo
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.schedule.DaySource
import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.LocalTime
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bamnat.core.model.ExtraWork

private const val DEFAULT_HOLIDAY_WORK_MINUTES = 8 * 60
private const val DEFAULT_OVERTIME_MINUTES = 2 * 60

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayDetail(
    date: LocalDate,
    today: LocalDate,
    snapshot: ScheduleSnapshot,
    vm: CalendarViewModel,
    onOpenMemo: (Long) -> Unit,
    onNewMemo: (LocalDate) -> Unit,
    onOpenShiftTypes: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPay: () -> Unit = {},
) {
    val day = remember(snapshot, date) { snapshot.resolver.resolve(date) }
    val memos by remember(date) { vm.memosOn(date) }.collectAsStateWithLifecycle(emptyList())

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val settings by vm.settings.collectAsStateWithLifecycle()
        DayHeader(day = day, isToday = date == today, showLunar = settings.showLunar)

        // [WORK-01] Field and shop work lead with money; shift details stay one tap away.
        val style = settings.workStyle
        val book by vm.moneyBook.collectAsStateWithLifecycle()
        val dayBook = remember(book, date) { book.on(date) }
        val moneyFirst = style != null && !style.usesPayEngine
        var showShifts by rememberSaveable(date) { mutableStateOf(false) }
        val shiftsVisible = !moneyFirst || showShifts || day.source == DaySource.OVERRIDE || day.entry != null
        if (moneyFirst) DayMoneySections(date, style, dayBook, vm)
        if (!shiftsVisible) {
            TextButton(onClick = { showShifts = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("근무·알람도 적기")
            }
        }
        if (shiftsVisible) {
            // Shift choice
            SectionCard(title = "근무") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (type in snapshot.shiftTypes) {
                        FilterChip(
                            selected = day.shiftType?.id == type.id,
                            onClick = { vm.assign(date, ShiftOverride.Shift(type.id)) },
                            label = { Text(type.name) },
                            leadingIcon = { ShiftBadge(type, size = 18.dp, outlined = type.isOff) },
                        )
                    }
                    if (snapshot.shiftTypes.none { it.isOff }) {
                        FilterChip(
                            selected = day.source == DaySource.OVERRIDE && day.shiftType == null,
                            onClick = { vm.assign(date, ShiftOverride.Off) },
                            label = { Text("쉬는 날") },
                        )
                    }
                    FilterChip(
                        selected = day.source != DaySource.OVERRIDE,
                        onClick = { vm.assign(date, ShiftOverride.None) },
                        label = { Text(if (snapshot.hasPattern) "패턴대로" else "지정 안 함") },
                        leadingIcon = { Icon(Icons.Filled.Autorenew, contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
                if (day.source == DaySource.OVERRIDE) {
                    val patternType = snapshot.resolver.patternShiftIdOn(date)?.let { snapshot.typesById[it] }
                    Text(
                        "직접 바꾼 날이에요 · 패턴: ${patternType?.name ?: "쉬는 날"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            if (day.isWorkDay) {
                WorkTimeSection(day = day, vm = vm)
            }

            if (day.leave != null || day.shiftType?.hasTimes == true && day.isWorkDay) {
                LeaveSection(day = day, vm = vm)
            }

            ExtraWorkSection(day = day, vm = vm)

            SecondShiftSection(day = day, snapshot = snapshot, vm = vm)

            DayPaySection(day = day, snapshot = snapshot, vm = vm, onOpenPay = onOpenPay)

            AlarmSection(day = day, snapshot = snapshot, vm = vm, onOpenShiftTypes = onOpenShiftTypes)
        }

        if (!moneyFirst) DayMoneySections(date, style, dayBook, vm)

        MemoSection(
            date = date,
            memos = memos,
            onQuickAdd = { vm.addQuickMemo(date, it) },
            onOpenMemo = onOpenMemo,
            onDeleteMemo = vm::deleteMemo,
            onNewMemo = { onNewMemo(date) },
        )

        if (day.entry != null) {
            TextButton(onClick = { vm.resetDay(date) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("이 날 바꾼 내용 모두 지우기")
            }
        }
    }
}

@Composable
private fun DayHeader(day: ResolvedDay, isToday: Boolean, showLunar: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.dayTitle(day.date), style = MaterialTheme.typography.headlineSmall)
            if (isToday) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "오늘",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (showLunar) {
            Text(KoreanLunar.fromSolar(day.date).label, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        KoreanHolidays.nameOf(day.date)?.let { name ->
            Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp))
        }
        val type = day.shiftType
        val start = day.start
        val end = day.end
        val summary = when {
            day.leave?.isFullDay == true -> "${type?.name ?: "근무"} · ${day.leave!!.label} (${HoursFormat.korean(day.leaveMinutes)})"
            day.isWorkDay && start != null && end != null ->
                "${type?.name ?: "근무"} · ${Fmt.timeRange(start, end)} · ${HoursFormat.korean(day.scheduledMinutes)}"
            type != null -> type.name
            else -> "쉬는 날"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            if (type != null) {
                ShiftBadge(type, size = 20.dp, outlined = type.isOff)
                Spacer(Modifier.width(8.dp))
            }
            Text(summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        day.second?.let { s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                ShiftBadge(s.type, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text("+ ${s.type.name} · ${Fmt.timeRange(s.start, s.end)} · ${HoursFormat.korean(s.minutes)}",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (day.overtimeMinutes > 0 || day.holidayWorkMinutes > 0 || day.second != null) {
            Text(
                "이 날 합계 ${HoursFormat.korean(day.totalMinutes)}",
                style = MaterialTheme.typography.titleSmall.merge(NumberStyle),
                color = LocalCalendarColors.current.holidayWork,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun WorkTimeSection(day: ResolvedDay, vm: CalendarViewModel) {
    val start = day.start ?: return
    val end = day.end ?: return
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }
    val hasCustom = day.hasCustomTimes || day.entry?.customBreakMinutes != null

    SectionCard(
        title = "근무 시간",
        trailing = if (hasCustom) {
            {
                TextButton(onClick = {
                    vm.clearCustomTimes(day.date)
                }) { Text("기본값으로") }
            }
        } else {
            null
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeBox(label = "시작", time = start, onClick = { editingStart = true }, modifier = Modifier.weight(1f))
            TimeBox(label = "종료", time = end, onClick = { editingEnd = true }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("휴게 시간", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            MinutesStepper(
                minutes = day.breakMinutes,
                onChange = { vm.setBreak(day.date, it) },
                step = 10,
                max = 12 * 60,
                dialogTitle = "휴게 시간",
            )
        }
        Text(
            "근무 ${HoursFormat.korean(day.scheduledMinutes)}" +
                if (day.hasCustomTimes) " · 이 날만 시간을 바꿨어요" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (editingStart) {
        TimePickerDialog(
            title = "시작 시각",
            initial = start,
            onDismiss = { editingStart = false },
            onConfirm = {
                editingStart = false
                vm.setCustomTimes(day.date, it, end)
            },
        )
    }
    if (editingEnd) {
        TimePickerDialog(
            title = "종료 시각",
            initial = end,
            onDismiss = { editingEnd = false },
            onConfirm = {
                editingEnd = false
                vm.setCustomTimes(day.date, start, it)
            },
        )
    }
}

@Composable
private fun TimeBox(label: String, time: LocalTime, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Fmt.time(time), style = MaterialTheme.typography.headlineSmall.merge(NumberStyle))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraWorkSection(day: ResolvedDay, vm: CalendarViewModel) {
    val cal = LocalCalendarColors.current
    val workDay = day.isWorkDay
    // On a working day extra hours are 연장; on a day off they are 휴일 근무.
    val minutes = if (workDay) day.overtimeMinutes else day.holidayWorkMinutes
    val kind = day.extraWork
    val active = kind != null || minutes > 0
    val defaultMinutes = if (workDay) DEFAULT_OVERTIME_MINUTES else DEFAULT_HOLIDAY_WORK_MINUTES

    SectionCard(title = if (workDay) "잔업 · 특근 · 대근" else "쉬는 날 근무 (특근 · 대근 · 잔업)") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in ExtraWork.entries) {
                FilterChip(
                    selected = kind == k,
                    onClick = {
                        if (kind == k) vm.setExtraWork(day, null, 0)
                        else vm.setExtraWork(day, k, if (minutes > 0) minutes else defaultMinutes)
                    },
                    label = { Text(k.label) },
                    leadingIcon = { ExtraWorkMark(k, size = 18.dp) },
                )
            }
        }
        if (active) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("일한 시간", style = MaterialTheme.typography.bodyLarge,
                        color = if (minutes > 0) cal.holidayWork else MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (workDay) "근무 시간에 더해져요" else "쉬는 날 일한 시간도 주·월 합계에 들어가요",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MinutesStepper(
                    minutes = minutes,
                    onChange = { vm.setExtraWork(day, kind, it) },
                    dialogTitle = (kind?.label ?: "추가 근무") + " 시간",
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in listOf(1, 2, 3, 4, 8, 12, 24)) {
                    SuggestionChip(onClick = { vm.setExtraWork(day, kind, h * 60) }, label = { Text("${h}시간") })
                }
            }
        } else {
            Text(
                "고르면 달력에 표시되고 시간이 합계에 들어가요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * 연차·반차·반반차 [FEAT-10]. Partial leave comes off the end of the shift; the pay screen and
 * the day's pay below follow at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeaveSection(day: ResolvedDay, vm: CalendarViewModel) {
    val leave = day.leave
    SectionCard(title = "휴가") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (q in Leave.QUARTER_OPTIONS) {
                val option = Leave(leave?.kind ?: LeaveKind.ANNUAL, q)
                FilterChip(
                    selected = leave?.quarters == q,
                    onClick = { vm.setLeave(day.date, if (leave?.quarters == q) null else option) },
                    label = { Text(when (q) { 4 -> "종일"; 2 -> "반차"; 1 -> "반반차"; else -> "0.75일" }) },
                    leadingIcon = { LeaveMark(option, size = 18.dp) },
                )
            }
        }
        if (leave != null) {
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LeaveKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = leave.kind == k,
                        onClick = { vm.setLeave(day.date, leave.copy(kind = k)) },
                        shape = SegmentedButtonDefaults.itemShape(i, LeaveKind.entries.size),
                    ) { Text(k.label, maxLines = 1) }
                }
            }
            val shift = day.shiftType
            Text(
                buildString {
                    append("${leave.label} · ${HoursFormat.korean(day.leaveMinutes)} 쉼")
                    if (!leave.isFullDay && day.start != null && day.end != null) {
                        append(" · 남은 근무 ${Fmt.timeRange(day.start!!, day.end!!)}")
                    }
                    if (leave.kind.annual) append(" · 연차에서 ${LeaveBook.days(leave.days)} 차감")
                    if (!leave.kind.paid) append(" · 무급")
                    if (shift == null) append(" · 근무가 없는 날이라 계산에서 빠져요")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Text(
                "반차는 근무 뒤쪽 절반을 쉬는 것으로 계산해요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 같은 날 근무 하나 더 [FEAT-02]: e.g. 데이 then a 나이트 대근, each with its own times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondShiftSection(day: ResolvedDay, snapshot: ScheduleSnapshot, vm: CalendarViewModel) {
    val choices = snapshot.shiftTypes.filter { it.hasTimes }
    if (choices.isEmpty()) return
    val current = day.second?.type?.id
    SectionCard(title = "같은 날 근무 하나 더") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = current == null, onClick = { vm.setSecondShift(day.date, null) }, label = { Text("없음") })
            for (t in choices) {
                FilterChip(
                    selected = current == t.id,
                    onClick = { vm.setSecondShift(day.date, if (current == t.id) null else t.id) },
                    label = { Text(t.name) },
                    leadingIcon = { ShiftBadge(t, size = 18.dp) },
                )
            }
        }
        Text(
            if (current == null) "데이 뒤 나이트 대근처럼 하루에 근무를 두 번 했을 때 고르세요. 달력 칸에 두 개가 나란히 보여요."
            else "두 번째 근무는 모두 추가 근무로 계산하고 알람도 따로 울려요. 대근·특근 단가를 쓰려면 위에서 표시해 주세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Small colored square with 잔/특/대, used in cells and chips. */
@Composable
fun ExtraWorkMark(kind: ExtraWork, size: androidx.compose.ui.unit.Dp = 16.dp) {
    val color = extraWorkColor(kind)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(kind.short, color = androidx.compose.ui.graphics.Color.White, fontSize = (size.value * 0.6f).sp,
            fontWeight = FontWeight.Bold, lineHeight = (size.value * 0.6f).sp)
    }
}

@Composable
fun extraWorkColor(kind: ExtraWork): androidx.compose.ui.graphics.Color = when (kind) {
    ExtraWork.OVERTIME -> androidx.compose.ui.graphics.Color(0xFFE8900C)
    ExtraWork.SPECIAL -> androidx.compose.ui.graphics.Color(0xFFE03131)
    ExtraWork.COVER -> androidx.compose.ui.graphics.Color(0xFF0E9F6E)
}

@Composable
private fun AlarmSection(day: ResolvedDay, snapshot: ScheduleSnapshot, vm: CalendarViewModel, onOpenShiftTypes: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val type = day.shiftType?.takeIf { day.isWorkDay }
    val custom = day.entry?.customAlarms
    // Without the skip, to show what turning the day back on would ring.
    val planned = remember(snapshot, day.date) {
        AlarmPlanner.alarmsOn(snapshot.resolver, day.date).ifEmpty {
            if (!day.alarmSkipped) emptyList()
            else AlarmPlanner.alarmsOn(
                ScheduleResolver(snapshot.shiftTypes, snapshot.patterns,
                    snapshot.entries.map { if (it.date == day.date) it.copy(alarmSkipped = false) else it }),
                day.date,
            )
        }
    }
    var editing by remember(day.date) { mutableStateOf(false) }
    var pendingSave by remember(day.date) { mutableStateOf<List<DayAlarm>?>(null) }

    SectionCard(
        title = "알람",
        trailing = if (custom != null) {
            { TextButton(onClick = { vm.setDayAlarms(day.date, null) }) { Text("근무 알람으로") } }
        } else {
            null
        },
    ) {
        if (!settings.alarmsEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(Icons.Filled.NotificationsOff, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text("알람 사용이 꺼져 있어 아무 알람도 울리지 않아요", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.setAlarmsEnabled(true) }) { Text("켜기") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ringing = planned.isNotEmpty() && !day.alarmSkipped
            Icon(
                if (ringing) Icons.Filled.Alarm else Icons.Filled.AlarmOff,
                contentDescription = null,
                tint = if (ringing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        day.alarmSkipped -> "이 날은 알람 끔"
                        planned.isEmpty() -> "울릴 알람이 없어요"
                        planned.size == 1 -> "${whenText(planned.first(), day.date)}에 울려요"
                        else -> "알람 ${planned.size}개가 울려요"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    when {
                        custom != null -> "이 날만 직접 정한 알람"
                        type != null && type.alarmEnabled -> "${type.name} 근무 알람 설정을 따라요"
                        type != null -> "${type.name} 근무는 알람이 꺼져 있어요"
                        else -> "쉬는 날이에요. 필요하면 직접 정할 수 있어요"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (planned.isNotEmpty()) {
                Switch(checked = !day.alarmSkipped, onCheckedChange = { vm.setAlarmSkipped(day.date, !it) })
            }
        }
        if (planned.size > 1 && !day.alarmSkipped) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 8.dp, start = 36.dp),
            ) {
                for (a in planned) {
                    SuggestionChip(onClick = { editing = true }, label = { Text(whenText(a, day.date)) })
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.EditCalendar, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (custom != null) "이 날 알람 수정" else "이 날 알람 직접 정하기")
            }
            if (type != null && !type.alarmEnabled && custom == null) {
                TextButton(onClick = onOpenShiftTypes) { Text("근무 알람 켜기") }
            }
        }
    }

    if (editing) {
        val start = day.start
        DayAlarmEditorDialog(
            date = day.date,
            initial = custom ?: planned.filter { it.triggerAt.toLocalDate() == day.date }
                .map { DayAlarm(it.triggerAt.toLocalTime()) },
            suggested = start?.minusHours(1) ?: LocalTime.of(8, 0),
            onDismiss = { editing = false },
            onSave = { list ->
                editing = false
                if (settings.hideDayAlarmNotice) vm.setDayAlarms(day.date, list) else pendingSave = list
            },
        )
    }
    pendingSave?.let { list ->
        DayAlarmNoticeDialog(
            date = day.date,
            alarms = list,
            alarmsEnabled = settings.alarmsEnabled,
            onDismiss = { pendingSave = null },
            onConfirm = { dontShowAgain ->
                vm.setDayAlarms(day.date, list)
                if (dontShowAgain) vm.hideDayAlarmNotice()
                pendingSave = null
            },
        )
    }
}

/** "07:00" or "전날 22:30" for an alarm that rings the evening before. */
private fun whenText(alarm: PlannedAlarm, date: LocalDate): String =
    (if (alarm.triggerAt.toLocalDate().isBefore(date)) "전날 " else "") + Fmt.time(alarm.triggerAt.toLocalTime())

@Composable
private fun DayAlarmEditorDialog(
    date: LocalDate,
    initial: List<DayAlarm>,
    suggested: LocalTime,
    onDismiss: () -> Unit,
    onSave: (List<DayAlarm>) -> Unit,
) {
    val alarms = remember { mutableStateListOf<DayAlarm>().apply { addAll(initial.sortedBy { it.time }) } }
    var picking by remember { mutableStateOf<Int?>(null) } // index to change, or -1 for a new alarm
    AlertDialog(
        onDismissRequest = onDismiss,
        // A stray tap outside must not throw away alarms just added; 취소 or 완료 decides.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("${Fmt.dayTitle(date)} 알람") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "이 날은 아래 알람만 울려요. 근무 알람 대신 쓰여요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (alarms.isEmpty()) {
                    Text("알람이 없어요. 저장하면 이 날은 울리지 않아요.", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                alarms.forEachIndexed { i, alarm ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Fmt.time(alarm.time),
                            style = MaterialTheme.typography.headlineSmall.merge(NumberStyle),
                            color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { picking = i }
                                .padding(vertical = 6.dp),
                        )
                        Switch(checked = alarm.enabled, onCheckedChange = { on -> alarms[i] = alarm.copy(enabled = on) })
                        IconButton(onClick = { alarms.removeAt(i) }) { Icon(Icons.Filled.Close, "이 알람 지우기") }
                    }
                }
                OutlinedButton(
                    onClick = { picking = -1 },
                    enabled = alarms.size < ScheduleRepository.MAX_ALARMS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AlarmAdd, null)
                    Spacer(Modifier.width(6.dp))
                    Text("알람 추가")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(alarms.toList()) }) { Text("완료") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
    picking?.let { index ->
        val initialTime = if (index >= 0) alarms[index].time else alarms.maxOfOrNull { it.time }?.plusMinutes(10) ?: suggested
        TimePickerDialog(if (index >= 0) "알람 시각 바꾸기" else "알람 추가", initialTime, onDismiss = { picking = null }) { t ->
            if (alarms.none { it.time == t }) {
                if (index >= 0) alarms[index] = alarms[index].copy(time = t) else alarms.add(DayAlarm(t))
                alarms.sortBy { it.time }
            }
            picking = null
        }
    }
}

@Composable
private fun DayAlarmNoticeDialog(
    date: LocalDate,
    alarms: List<DayAlarm>,
    alarmsEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
) {
    var dontShow by remember { mutableStateOf(false) }
    val active = alarms.filter { it.enabled }.sortedBy { it.time }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Alarm, null) },
        title = { Text("이렇게 울려요") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (active.isEmpty()) "${Fmt.dayTitle(date)}에는 알람이 울리지 않아요."
                    else "${Fmt.dayTitle(date)}에 아래 알람이 울려요. 이 날의 근무 알람 대신 쓰여요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                for (a in active) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Alarm, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(Fmt.time(a.time), style = MaterialTheme.typography.titleMedium.merge(NumberStyle))
                    }
                }
                if (!alarmsEnabled) {
                    Text("지금은 설정에서 알람 사용이 꺼져 있어 울리지 않아요.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { dontShow = !dontShow }
                        .padding(end = 8.dp),
                ) {
                    Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                    Text("다시 표시하지 않기", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(dontShow) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun MemoSection(
    date: LocalDate,
    memos: List<Memo>,
    onQuickAdd: (String) -> Unit,
    onOpenMemo: (Long) -> Unit,
    onDeleteMemo: (Memo) -> Unit,
    onNewMemo: () -> Unit,
) {
    var text by rememberSaveable(date) { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onQuickAdd(text)
            text = ""
        }
    }
    SectionCard(
        title = "메모",
        trailing = {
            TextButton(onClick = onNewMemo) {
                Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("자세히 쓰기")
            }
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("빠른 메모 (예: 인수인계, 준비물)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "메모 추가")
                }
            },
        )
        if (memos.isNotEmpty()) Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (memo in memos) {
                MemoRow(memo = memo, onClick = { onOpenMemo(memo.id) }, onDelete = { onDeleteMemo(memo) })
            }
        }
    }
}

@Composable
private fun MemoRow(memo: Memo, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(memoBackground(memo.colorIndex))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (memo.isChecklist) {
            Icon(Icons.Filled.Checklist, contentDescription = "체크리스트", Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            if (memo.title.isNotBlank()) {
                Text(memo.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val preview = memo.preview
            if (preview.isNotBlank()) {
                Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (memo.isChecklist && memo.itemCount > 0) {
            AssistChip(onClick = onClick, label = { Text("${memo.checkedCount}/${memo.itemCount}") })
        }
        if (memo.pinned) {
            Icon(Icons.Filled.PushPin, contentDescription = "고정됨", Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "메모 삭제", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * What this day adds to pay, straight from the pay engine [PAYX-08]: the shift, any 잔업·특근·대근
 * mark and the holiday all show up here as soon as they change on the calendar.
 */
@Composable
private fun DayPaySection(day: ResolvedDay, snapshot: ScheduleSnapshot, vm: CalendarViewModel, onOpenPay: () -> Unit) {
    val profile by vm.payProfile.collectAsStateWithLifecycle()
    val tier by vm.tier.collectAsStateWithLifecycle()
    val p = profile
    if (p == null) {
        if (day.hasExtraWork) {
            TextButton(onClick = onOpenPay) { Text("급여 계산을 설정하면 이 날 수당이 보여요") }
        }
        return
    }
    val pay = remember(p, snapshot, day.date) { vm.dayPay(p, snapshot, day.date) } ?: return
    if (pay.parts.isEmpty()) return
    val unlocked = FeatureGate.allows(tier, Feature.PAY_ENGINE)
    SectionCard(
        title = if (p.wageType == WageType.MONTHLY) "이 날 더해지는 급여" else "이 날 급여",
        trailing = { TextButton(onClick = onOpenPay) { Text("자세히") } },
    ) {
        for (part in pay.parts) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(part.label, style = MaterialTheme.typography.bodyLarge)
                    Text(part.formula, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (unlocked) "%,d원".format(part.amount) else "●●,●●●원", style = MaterialTheme.typography.titleSmall)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("합계 (세전)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                if (unlocked) "%,d원".format(pay.amount) else "프로 등급에서 보여요",
                style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
