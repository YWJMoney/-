package com.shiftmate.calendar.ui.shifts

import com.shiftmate.calendar.data.ScheduleRepository
import com.shiftmate.core.model.AlarmSpec
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AlarmAdd
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiftmate.calendar.alarm.Notifications
import com.shiftmate.calendar.ui.Fmt
import com.shiftmate.calendar.ui.components.ConfirmDialog
import com.shiftmate.calendar.ui.components.MinutesStepper
import com.shiftmate.calendar.ui.components.SectionCard
import com.shiftmate.calendar.ui.components.ShiftBadge
import com.shiftmate.calendar.ui.components.SoundPickerRow
import com.shiftmate.calendar.ui.components.TimePickerDialog
import com.shiftmate.calendar.ui.components.asColor
import com.shiftmate.calendar.ui.theme.ShiftPalette
import com.shiftmate.core.hours.HoursFormat
import com.shiftmate.core.model.ShiftType
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftTypesScreen(vm: ShiftsViewModel, onBack: () -> Unit) {
    val types by vm.shiftTypes.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ShiftType?>(null) }
    var deleting by remember { mutableStateOf<ShiftType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("근무 유형") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editing = ShiftType(
                        id = 0, name = "", label = "", colorArgb = ShiftPalette[(types?.size ?: 0) % ShiftPalette.size],
                        start = LocalTime.of(9, 0), end = LocalTime.of(18, 0), breakMinutes = 60,
                        alarmEnabled = true, alarms = listOf(AlarmSpec(60)),
                    )
                },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("근무 추가") },
            )
        },
    ) { padding ->
        val list = types
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "달력에 표시될 근무 종류예요. 시간, 색, 알람을 근무마다 따로 정할 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            itemsIndexed(list, key = { _, t -> t.id }) { index, type ->
                ShiftTypeRow(
                    type = type,
                    canMoveUp = index > 0,
                    canMoveDown = index < list.lastIndex,
                    onClick = { editing = type },
                    onMoveUp = { vm.move(type.id, -1) },
                    onMoveDown = { vm.move(type.id, 1) },
                )
            }
        }
    }

    editing?.let { type ->
        ShiftTypeEditorDialog(
            initial = type,
            onDismiss = { editing = null },
            onSave = {
                vm.save(it)
                editing = null
            },
            onDelete = if (type.id != 0L && (types?.size ?: 0) > 1) {
                {
                    deleting = type
                    editing = null
                }
            } else {
                null
            },
        )
    }
    deleting?.let { type ->
        ConfirmDialog(
            title = "‘${type.name}’ 근무를 삭제할까요?",
            message = "패턴에서 이 근무가 들어간 칸은 쉬는 날이 되고, 이 근무로 바꿔 둔 날은 패턴대로 돌아가요.",
            confirmLabel = "삭제",
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = {
                vm.delete(type.id)
                deleting = null
            },
        )
    }
}

@Composable
private fun ShiftTypeRow(
    type: ShiftType,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ShiftBadge(type, size = 32.dp, outlined = type.isOff)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(type.name, style = MaterialTheme.typography.titleMedium)
                val start = type.start
                val end = type.end
                Text(
                    if (type.isOff || start == null || end == null) "쉬는 날 (근무시간 0)"
                    else "${Fmt.timeRange(start, end)} · 휴게 ${type.breakMinutes}분 · ${HoursFormat.korean(type.workMinutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!type.isOff) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(
                            if (type.alarmEnabled) Icons.Filled.Alarm else Icons.Filled.AlarmOff,
                            null,
                            Modifier.size(14.dp),
                            tint = if (type.alarmEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            alarmSummary(type),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Filled.KeyboardArrowUp, "위로") }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Filled.KeyboardArrowDown, "아래로") }
            }
        }
    }
}

private val OffsetChoices = listOf(0, 10, 30, 60, 90, 120, 180)
private val DurationChoices = listOf(4, 6, 8, 9, 10, 12, 24)

private fun alarmSummary(type: ShiftType): String {
    val active = type.activeAlarms
    return when {
        !type.alarmEnabled || active.isEmpty() -> "알람 꺼짐"
        active.size == 1 -> "알람 ${Fmt.offset(active.first().offsetMinutes)}"
        else -> "알람 ${active.size}개 · " + active.joinToString(", ") { Fmt.offset(it.offsetMinutes) }
    }
}

@Composable
private fun AlarmSpecRow(alarm: AlarmSpec, ringsAt: LocalTime, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            if (alarm.enabled) Icons.Filled.Alarm else Icons.Filled.AlarmOff,
            null,
            tint = if (alarm.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("근무 ${Fmt.offset(alarm.offsetMinutes)}", style = MaterialTheme.typography.bodyLarge)
            Text("${Fmt.time(ringsAt)}에 울려요", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "이 알람 지우기") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OffsetPickerDialog(existing: Set<Int>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(OffsetChoices.firstOrNull { it !in existing } ?: 45) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("알람 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("근무 시작 몇 분 전에 울릴까요?", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in OffsetChoices) {
                        FilterChip(
                            selected = minutes == m,
                            onClick = { minutes = m },
                            enabled = m !in existing,
                            label = { Text(Fmt.offset(m)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("직접 입력", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    MinutesStepper(minutes = minutes, onChange = { minutes = it }, step = 5, max = 24 * 60,
                        dialogTitle = "몇 분 전에 울릴까요?")
                }
                if (minutes in existing) {
                    Text("이미 있는 알람이에요", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(minutes) }, enabled = minutes !in existing) { Text("추가") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShiftTypeEditorDialog(
    initial: ShiftType,
    onDismiss: () -> Unit,
    onSave: (ShiftType) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var addingAlarm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (initial.id == 0L) "근무 추가" else "근무 수정") },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "닫기") } },
                        actions = {
                            TextButton(onClick = { onSave(draft) }, enabled = draft.name.isNotBlank()) { Text("저장") }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShiftBadge(draft.copy(label = draft.label.ifBlank { draft.name.take(1).ifBlank { "?" } }), size = 44.dp,
                            outlined = draft.isOff)
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { v -> draft = draft.copy(name = v.take(10)) },
                            label = { Text("이름 (예: 주간, 야간, 오픈)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = draft.label,
                            onValueChange = { v -> draft = draft.copy(label = v.take(2)) },
                            label = { Text("표시") },
                            singleLine = true,
                            modifier = Modifier.width(76.dp),
                        )
                    }

                    SectionCard(title = "색상") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in ShiftPalette) {
                                val selected = c == draft.colorArgb
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(c.asColor())
                                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        .clickable { draft = draft.copy(colorArgb = c) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) Icon(Icons.Filled.Check, "선택됨", tint = androidx.compose.ui.graphics.Color.White)
                                }
                            }
                        }
                    }

                    SectionCard(title = null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("쉬는 날 유형", style = MaterialTheme.typography.bodyLarge)
                                Text("휴무, 비번처럼 근무시간에 들어가지 않는 날", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = draft.isOff, onCheckedChange = { off ->
                                draft = if (off) {
                                    draft.copy(isOff = true, alarmEnabled = false)
                                } else {
                                    draft.copy(
                                        isOff = false,
                                        start = draft.start ?: LocalTime.of(9, 0),
                                        end = draft.end ?: LocalTime.of(18, 0),
                                    )
                                }
                            })
                        }
                    }

                    if (!draft.isOff) {
                        val start = draft.start ?: LocalTime.of(9, 0)
                        val end = draft.end ?: LocalTime.of(18, 0)
                        val timed = draft.copy(start = start, end = end)
                        SectionCard(title = "근무 시간") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TimeButton("시작", start, Modifier.weight(1f)) { pickStart = true }
                                TimeButton(
                                    if (timed.durationMinutes >= 24 * 60 || !end.isAfter(start)) "종료 (다음날)" else "종료",
                                    end,
                                    Modifier.weight(1f),
                                ) { pickEnd = true }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("근무 길이", style = MaterialTheme.typography.bodyLarge)
                                    Text("시작부터 끝까지", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                MinutesStepper(
                                    minutes = timed.durationMinutes,
                                    onChange = { draft = timed.withDuration(it) },
                                    step = 30,
                                    max = 24 * 60,
                                    dialogTitle = "근무 길이",
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (h in DurationChoices) {
                                    FilterChip(
                                        selected = timed.durationMinutes == h * 60,
                                        onClick = { draft = timed.withDuration(h * 60) },
                                        label = { Text("${h}시간") },
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("휴게 시간", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                MinutesStepper(
                                    minutes = draft.breakMinutes,
                                    onChange = { draft = draft.copy(breakMinutes = it) },
                                    step = 10,
                                    max = 12 * 60,
                                    dialogTitle = "휴게 시간",
                                )
                            }
                            Text(
                                "실제 근무 ${HoursFormat.korean(timed.workMinutes)}" +
                                    if (!end.isAfter(start)) " · 다음날 ${Fmt.time(end)}에 끝나요" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        SectionCard(title = "알람") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("근무 전 알람", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "여러 개를 추가할 수 있어요",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = draft.alarmEnabled, onCheckedChange = { on ->
                                    draft = draft.copy(
                                        alarmEnabled = on,
                                        alarms = if (on && draft.alarms.isEmpty()) listOf(AlarmSpec(60)) else draft.alarms,
                                    )
                                    if (on && Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(context)) {
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                })
                            }
                            if (draft.alarmEnabled) {
                                Spacer(Modifier.height(4.dp))
                                val sorted = draft.alarms.sortedByDescending { it.offsetMinutes }
                                for (alarm in sorted) {
                                    AlarmSpecRow(
                                        alarm = alarm,
                                        ringsAt = start.minusMinutes(alarm.offsetMinutes.toLong()),
                                        onToggle = { on ->
                                            draft = draft.copy(alarms = draft.alarms.map { if (it == alarm) it.copy(enabled = on) else it })
                                        },
                                        onRemove = { draft = draft.copy(alarms = draft.alarms - alarm) },
                                    )
                                }
                                if (sorted.isEmpty()) {
                                    Text("알람이 없어요. 아래에서 추가하세요.", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(
                                    onClick = { addingAlarm = true },
                                    enabled = draft.alarms.size < ScheduleRepository.MAX_ALARMS,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                ) {
                                    Icon(Icons.Filled.AlarmAdd, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("알람 추가")
                                }
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                SoundPickerRow(
                                    sound = draft.alarmSoundUri,
                                    defaultLabel = "기본 알람음 (설정에서 변경)",
                                    onPicked = { draft = draft.copy(alarmSoundUri = it) },
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { onSave(draft) },
                        enabled = draft.name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("저장") }
                    if (onDelete != null) {
                        TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                            Text("이 근무 유형 삭제", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (pickStart) {
        TimePickerDialog("시작 시각", draft.start ?: LocalTime.of(9, 0), onDismiss = { pickStart = false }) {
            draft = draft.copy(start = it)
            pickStart = false
        }
    }
    if (pickEnd) {
        TimePickerDialog("종료 시각", draft.end ?: LocalTime.of(18, 0), onDismiss = { pickEnd = false }) {
            draft = draft.copy(end = it)
            pickEnd = false
        }
    }
    if (addingAlarm) {
        OffsetPickerDialog(
            existing = draft.alarms.map { it.offsetMinutes }.toSet(),
            onDismiss = { addingAlarm = false },
            onPick = { m ->
                if (draft.alarms.none { it.offsetMinutes == m }) draft = draft.copy(alarms = draft.alarms + AlarmSpec(m))
                addingAlarm = false
            },
        )
    }
}

@Composable
private fun TimeButton(label: String, time: LocalTime, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Fmt.time(time), style = MaterialTheme.typography.headlineSmall)
    }
}
