package com.shiftmate.calendar.ui.calendar

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiftmate.calendar.data.ScheduleSnapshot
import com.shiftmate.calendar.ui.Fmt
import com.shiftmate.calendar.ui.components.MinutesStepper
import com.shiftmate.calendar.ui.components.SectionCard
import com.shiftmate.calendar.ui.components.ShiftBadge
import com.shiftmate.calendar.ui.components.TimePickerDialog
import com.shiftmate.calendar.ui.components.memoBackground
import com.shiftmate.calendar.ui.theme.LocalCalendarColors
import com.shiftmate.calendar.ui.theme.NumberStyle
import com.shiftmate.core.hours.HoursFormat
import com.shiftmate.core.memo.Memo
import com.shiftmate.core.model.ShiftOverride
import com.shiftmate.core.schedule.DaySource
import com.shiftmate.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.LocalTime

private const val DEFAULT_HOLIDAY_WORK_MINUTES = 8 * 60

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
) {
    val day = remember(snapshot, date) { snapshot.resolver.resolve(date) }
    val memos by remember(date) { vm.memosOn(date) }.collectAsStateWithLifecycle(emptyList())

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DayHeader(day = day, isToday = date == today)

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
                    label = { Text(if (snapshot.pattern != null) "패턴대로" else "지정 안 함") },
                    leadingIcon = { Icon(Icons.Filled.Autorenew, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
            if (day.source == DaySource.OVERRIDE) {
                val patternType = snapshot.pattern?.shiftTypeIdOn(date)?.let { snapshot.typesById[it] }
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

        ExtraWorkSection(day = day, vm = vm)

        if (day.isWorkDay && day.shiftType != null) {
            AlarmSection(day = day, vm = vm, onOpenShiftTypes = onOpenShiftTypes)
        }

        MemoSection(
            date = date,
            memos = memos,
            onQuickAdd = { vm.addQuickMemo(date, it) },
            onOpenMemo = onOpenMemo,
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
private fun DayHeader(day: ResolvedDay, isToday: Boolean) {
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
        val type = day.shiftType
        val start = day.start
        val end = day.end
        val summary = when {
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
        if (day.overtimeMinutes > 0 || day.holidayWorkMinutes > 0) {
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
    val showOvertime = day.isWorkDay || day.overtimeMinutes > 0
    val showHoliday = day.isOff || day.holidayWorkMinutes > 0
    if (!showOvertime && !showHoliday) return
    val cal = LocalCalendarColors.current

    SectionCard(title = "추가 근무") {
        if (showOvertime) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("연장 근무", style = MaterialTheme.typography.bodyLarge)
                    Text("정해진 시간보다 더 일한 시간", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MinutesStepper(
                    minutes = day.overtimeMinutes,
                    onChange = { vm.setOvertime(day.date, it) },
                    dialogTitle = "연장 근무 시간",
                )
            }
        }
        if (showOvertime && showHoliday) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
        }
        if (showHoliday) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("휴일 근무", style = MaterialTheme.typography.bodyLarge,
                        color = if (day.isHolidayWork) cal.holidayWork else MaterialTheme.colorScheme.onSurface)
                    Text("쉬는 날 일한 시간도 주·월·3개월 합계에 들어가요",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = day.isHolidayWork,
                    onCheckedChange = { on -> vm.setHolidayWork(day.date, if (on) DEFAULT_HOLIDAY_WORK_MINUTES else 0) },
                )
            }
            if (day.isHolidayWork) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("일한 시간", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    MinutesStepper(
                        minutes = day.holidayWorkMinutes,
                        onChange = { vm.setHolidayWork(day.date, it) },
                        dialogTitle = "휴일 근무 시간",
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (h in listOf(2, 4, 6, 8, 10, 12)) {
                        SuggestionChip(
                            onClick = { vm.setHolidayWork(day.date, h * 60) },
                            label = { Text("${h}시간") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmSection(day: ResolvedDay, vm: CalendarViewModel, onOpenShiftTypes: () -> Unit) {
    val type = day.shiftType ?: return
    val start = day.start ?: return
    SectionCard(title = "알람") {
        if (type.alarmEnabled) {
            val trigger = day.date.atTime(start).minusMinutes(type.alarmOffsetMinutes.toLong())
            val dayPrefix = when {
                trigger.toLocalDate().isBefore(day.date) -> "전날 "
                else -> ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (day.alarmSkipped) Icons.Filled.AlarmOff else Icons.Filled.Alarm,
                    contentDescription = null,
                    tint = if (day.alarmSkipped) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (day.alarmSkipped) "이 날은 알람 끔" else "$dayPrefix${Fmt.time(trigger.toLocalTime())}에 울려요",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "근무 ${Fmt.offset(type.alarmOffsetMinutes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = !day.alarmSkipped, onCheckedChange = { vm.setAlarmSkipped(day.date, !it) })
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AlarmOff, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(12.dp))
                Text(
                    "${type.name} 근무는 알람이 꺼져 있어요",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenShiftTypes) { Text("켜기") }
            }
        }
    }
}

@Composable
private fun MemoSection(
    date: LocalDate,
    memos: List<Memo>,
    onQuickAdd: (String) -> Unit,
    onOpenMemo: (Long) -> Unit,
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
                MemoRow(memo = memo, onClick = { onOpenMemo(memo.id) })
            }
        }
    }
}

@Composable
private fun MemoRow(memo: Memo, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(memoBackground(memo.colorIndex))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
    }
}
