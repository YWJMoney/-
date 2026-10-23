package com.bamnat.app.ui.shifts

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bamnat.app.ui.components.vector
import com.bamnat.core.model.ShiftIcon
import android.widget.Toast
import com.bamnat.app.data.ScheduleRepository
import com.bamnat.core.model.AlarmSpec
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import com.bamnat.core.schedule.LegalBreak
import com.bamnat.core.schedule.ShiftTypeSet
import com.bamnat.core.schedule.ShiftTypeSets
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.ui.components.rememberNotificationAsk
import com.bamnat.app.ui.settings.WarningLine
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.MinutesStepper
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.SoundPickerRow
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.theme.ShiftPalette
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.ShiftType
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftTypesScreen(vm: ShiftsViewModel, onBack: () -> Unit, editId: Long = 0L) {
    val types by vm.shiftTypes.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ShiftType?>(null) }
    // [DAILY-08] Opened for one shift type: its editor first, once.
    var editOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(types, editId) {
        if (editId == 0L || editOpened) return@LaunchedEffect
        types?.firstOrNull { it.id == editId }?.let { editing = it; editOpened = true }
    }
    var deleting by remember { mutableStateOf<ShiftType?>(null) }
    var addingSet by remember { mutableStateOf<ShiftTypeSet?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { CalendarTitle("근무 종류와 시간", vm) },
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
                icon = { Icon(Icons.Filled.Add, "근무 추가") },
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
                // [SETUP-07] What can be done here, said once: tap to change, arrows to reorder.
                Text(
                    "달력에 보이는 근무예요. 근무를 누르면 시간과 알람을 고치고, 오른쪽 손잡이를 끌거나 화살표로 달력에 보이는 순서를 바꿔요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item {
                // Ready-made sets for common workplaces [MKT-03].
                Text("한 번에 추가", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    items(ShiftTypeSets.all, key = { it.name }) { set ->
                        val missing = ShiftTypeSets.missing(set, list)
                        AssistChip(
                            onClick = { addingSet = set },
                            enabled = missing.isNotEmpty(),
                            label = { Text(set.name) },
                            leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            itemsIndexed(list, key = { _, t -> t.id }) { index, type ->
                ShiftTypeRow(
                    type = type,
                    canMoveUp = index > 0,
                    canMoveDown = index < list.lastIndex,
                    onClick = { editing = type },
                    onMoveUp = { vm.move(type.id, -1) },
                    onMoveDown = { vm.move(type.id, 1) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    editing?.let { type ->
        ShiftTypeEditorDialog(
            initial = type,
            showPremium = vm.isShiftCalendar,
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
    addingSet?.let { set ->
        val missing = ShiftTypeSets.missing(set, types.orEmpty())
        ConfirmDialog(
            title = "‘${set.name}’ 근무를 추가할까요?",
            message = set.description + "\n\n추가: " + missing.joinToString { it.name } +
                (if (missing.size < set.types.size) "\n이미 있는 이름은 건너뛰어요." else "") +
                "\n추가한 뒤 시간과 알람은 근무마다 바꿀 수 있어요.",
            confirmLabel = "추가",
            onDismiss = { addingSet = null },
            onConfirm = {
                vm.addSet(set)
                addingSet = null
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
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ShiftBadge(type, size = 32.dp, outlined = type.isOff)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(type.name, style = MaterialTheme.typography.titleMedium)
                    // [SHFT-01] Night work and the kind's own premium, at a glance.
                    ShiftPremiumMarks(type)
                }
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
            // [DAILY-08] Drag the handle up or down: one place for each row's height passed.
            val step = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
            val up by androidx.compose.runtime.rememberUpdatedState(onMoveUp)
            val down by androidx.compose.runtime.rememberUpdatedState(onMoveDown)
            val canUp by androidx.compose.runtime.rememberUpdatedState(canMoveUp)
            val canDown by androidx.compose.runtime.rememberUpdatedState(canMoveDown)
            Icon(
                Icons.Filled.DragHandle, null,
                Modifier.size(40.dp).padding(8.dp).pointerInput(Unit) {
                    var moved = 0f
                    detectVerticalDragGestures(onDragStart = { moved = 0f }) { change, dy ->
                        change.consume()
                        moved += dy
                        if (moved > step && canDown) { down(); moved -= step }
                        else if (moved < -step && canUp) { up(); moved += step }
                    }
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // [SETUP-07] Straight arrows that move, not the chevrons that read as "open"; TalkBack names the shift.
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Filled.ArrowUpward, "${type.name} 위로 옮기기", Modifier.size(20.dp)) }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Filled.ArrowDownward, "${type.name} 아래로 옮기기", Modifier.size(20.dp)) }
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
private fun AlarmSpecRow(
    alarm: AlarmSpec,
    ringsAt: LocalTime,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            if (alarm.enabled) Icons.Filled.Alarm else Icons.Filled.AlarmOff,
            null,
            tint = if (alarm.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(12.dp))
        // [UX-02] Tapping the alarm changes when it rings, instead of deleting and adding it again.
        Column(
            Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = "알람 시각 바꾸기", onClick = onEdit)
                .padding(vertical = 4.dp),
        ) {
            Text("근무 ${Fmt.offset(alarm.offsetMinutes)}", style = MaterialTheme.typography.bodyLarge)
            Text("${Fmt.time(ringsAt)}에 울려요 · 눌러서 바꾸기", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "이 알람 지우기") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OffsetPickerDialog(
    existing: Set<Int>,
    start: LocalTime,
    onDismiss: () -> Unit,
    /** The alarm being changed; null when adding a new one. */
    initial: Int? = null,
    onPick: (Int) -> Unit,
) {
    var minutes by remember { mutableIntStateOf(initial ?: OffsetChoices.firstOrNull { it !in existing } ?: 45) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "알람 시각 바꾸기" else "알람 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("근무 시작 몇 분 전에 울릴까요? 1분 단위로 정할 수 있어요.", style = MaterialTheme.typography.bodyMedium)
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
                    // [UX-01] To the minute: tap for 1분, hold to run, or tap the value to type it.
                    MinutesStepper(minutes = minutes, onChange = { minutes = it }, step = 1, max = 24 * 60,
                        dialogTitle = "몇 분 전에 울릴까요?")
                }
                if (minutes in existing) {
                    Text("이미 있는 알람이에요", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${Fmt.time(start.minusMinutes(minutes.toLong()))}에 울려요", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(minutes) }, enabled = minutes !in existing) { Text(if (initial != null) "바꾸기" else "추가") }
        },
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
    /** [SETUP-01] The first setup asks for alarms in a step of their own. */
    showAlarms: Boolean = true,
    /** [SHFT-01] A shift calendar sets each kind's own premium here. */
    showPremium: Boolean = false,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var pickBreak by remember { mutableStateOf(false) }
    var addingAlarm by remember { mutableStateOf(false) }
    var editingAlarm by remember { mutableStateOf<AlarmSpec?>(null) }
    var styling by remember(initial) { mutableStateOf(false) }
    val context = LocalContext.current
    // [SLP-15] Turning an alarm on is when the notification permission is explained and asked for.
    val askNotifications = rememberNotificationAsk()
    var canPost by remember { mutableStateOf(Notifications.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canPost = Notifications.canPost(context) }

    // Leaving with changes keeps them: alarms added or times picked are never lost to a back press.
    val close: () -> Unit = {
        if (draft != initial && draft.name.isNotBlank()) {
            onSave(draft)
            Toast.makeText(context, "저장했어요", Toast.LENGTH_SHORT).show()
        } else {
            onDismiss()
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (initial.id == 0L || initial.name.isBlank()) "근무 추가" else "근무 수정") },
                        navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "닫기") } },
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

                    SectionCard(title = null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                // [SETUP-08] Said as the user would: is this a day without work?
                                Text("일하지 않는 날이에요", style = MaterialTheme.typography.bodyLarge)
                                Text("휴무·비번처럼 근무가 없는 날이면 켜요", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
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
                            // 순번 285: 근로기준법 제54조 asks for 30 minutes from 4 hours and an hour from 8; say so, never block.
                            val legalBreak = LegalBreak.minutesFor(timed.durationMinutes)
                            if (draft.breakMinutes < legalBreak) {
                                Text(
                                    "${HoursFormat.korean(timed.durationMinutes)} 근무는 휴게가 ${legalBreak}분 이상이어야 해요(근로기준법 제54조). " +
                                        "휴게 없이 모두 일한 시간으로 쳐 주는 회사라면 그대로 두세요.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (draft.breakMinutes > 0) {
                                // [PAY-01] Where the break falls decides how much of the shift is night work.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("휴게 시작", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            if (draft.breakStart == null) "모르면 비워 두세요. 야간 가산은 추정해요"
                                            else "야간(22~06시) 가산을 정확히 계산해요",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (draft.breakStart != null) {
                                        TextButton(onClick = { draft = draft.copy(breakStart = null) }) { Text("비우기") }
                                    }
                                    FilledTonalButton(onClick = { pickBreak = true }) {
                                        Text(draft.breakStart?.let { Fmt.time(it) } ?: "선택")
                                    }
                                }
                            }
                            Text(
                                "실제 근무 ${HoursFormat.korean(timed.workMinutes)}" +
                                    if (!end.isAfter(start)) " · 다음날 ${Fmt.time(end)}에 끝나요" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // [SHFT-01] This kind of day's own premium, a line of its own in 급여 계산.
                        if (showPremium) ShiftPremiumSection(timed, onChange = { draft = draft.copy(premiumPercent = it) })

                        if (showAlarms) SectionCard(title = "알람") {
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
                                    if (on && !canPost) askNotifications()
                                })
                            }
                            if (draft.alarmEnabled && !canPost) {
                                WarningLine("알림이 꺼져 있어 잠금 화면에서 알람을 끌 수 없어요", "허용", askNotifications)
                            }
                            if (draft.alarmEnabled) {
                                Spacer(Modifier.height(4.dp))
                                val sorted = draft.alarms.sortedByDescending { it.offsetMinutes }
                                for (alarm in sorted) {
                                    AlarmSpecRow(
                                        alarm = alarm,
                                        ringsAt = start.minusMinutes(alarm.offsetMinutes.toLong()),
                                        onEdit = { editingAlarm = alarm },
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
                                    defaultLabel = "기본 알람음 (설정 › 알람 › 소리)",
                                    onPicked = { draft = draft.copy(alarmSoundUri = it) },
                                )
                            }
                        }
                    }

                    // [SETUP-08] Time first: it is what the calendar and the pay need. Colour and icon already have a
                    // value, so they wait folded under one row instead of filling the first screen.
                    SectionCard(title = null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable(onClickLabel = if (styling) "색과 아이콘 접기" else "색과 아이콘 바꾸기") { styling = !styling }
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("색과 아이콘 (선택)", style = MaterialTheme.typography.bodyLarge)
                                Text("지금 모양 그대로 써도 돼요", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ShiftBadge(draft.copy(label = draft.label.ifBlank { draft.name.take(1).ifBlank { "?" } }), size = 32.dp,
                                outlined = draft.isOff)
                            Spacer(Modifier.width(8.dp))
                            Icon(if (styling) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                        }
                        AnimatedVisibility(styling) {
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("색상", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    ShiftPalette.forEachIndexed { i, c ->
                                        val selected = c == draft.colorArgb
                                        // A 48 dp target around a 36 dp swatch.
                                        Box(
                                            Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .clickable(onClickLabel = "이 색 고르기") { draft = draft.copy(colorArgb = c) }
                                                .semantics { contentDescription = "색 ${i + 1}"; this.selected = selected },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(c.asColor())
                                                    .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (selected) Icon(Icons.Filled.Check, null, tint = com.bamnat.app.ui.components.contentColorOn(c.asColor())) // [VIS-03] a tick on 노랑 too
                                            }
                                        }
                                    }
                                }
                                Text("아이콘", style = MaterialTheme.typography.labelLarge)
                                // [FEAT-01] An icon next to the label on the calendar; none keeps cells plain.
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val tint = draft.colorArgb.asColor()
                                    IconChoice(selected = draft.icon == null, label = "없음", onClick = { draft = draft.copy(icon = null) }) {
                                        Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    for (icon in ShiftIcon.entries) {
                                        IconChoice(selected = draft.icon == icon.key, label = icon.label, onClick = { draft = draft.copy(icon = icon.key) }) {
                                            Icon(icon.vector, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { onSave(draft) },
                        enabled = draft.name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("저장") }
                    if (onDelete != null) {
                        TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                            Text("이 근무 종류 삭제", color = MaterialTheme.colorScheme.error)
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
    if (pickBreak) {
        val suggested = (draft.start ?: LocalTime.of(9, 0)).plusMinutes((draft.durationMinutes / 2).toLong()).withMinute(0)
        TimePickerDialog("휴게 시작 시각", draft.breakStart ?: suggested, onDismiss = { pickBreak = false }) {
            draft = draft.copy(breakStart = it)
            pickBreak = false
        }
    }
    if (addingAlarm) {
        OffsetPickerDialog(
            existing = draft.alarms.map { it.offsetMinutes }.toSet(),
            start = draft.start ?: LocalTime.of(9, 0),
            onDismiss = { addingAlarm = false },
            onPick = { m ->
                if (draft.alarms.none { it.offsetMinutes == m }) draft = draft.copy(alarms = draft.alarms + AlarmSpec(m))
                addingAlarm = false
            },
        )
    }
    editingAlarm?.let { old ->
        OffsetPickerDialog(
            existing = draft.alarms.map { it.offsetMinutes }.toSet() - old.offsetMinutes,
            start = draft.start ?: LocalTime.of(9, 0),
            initial = old.offsetMinutes,
            onDismiss = { editingAlarm = null },
            onPick = { m ->
                // A changed alarm rings again, even if it was switched off before.
                draft = draft.copy(alarms = draft.alarms.map { if (it == old) AlarmSpec(m, enabled = true) else it })
                editingAlarm = null
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

/** One tile of the icon picker [FEAT-01]. */
@Composable
private fun IconChoice(selected: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .background(if (selected) com.bamnat.app.ui.components.Selection.container else MaterialTheme.colorScheme.surfaceContainer) // [VIS-03]
            .border(if (selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, shape)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** [MCAL-01] A screen's title, with the added calendar it edits under it. */
@Composable
internal fun CalendarTitle(title: String, vm: ShiftsViewModel) {
    val name by vm.calendarName.collectAsStateWithLifecycle()
    Column {
        Text(title)
        name?.let { Text("$it 달력", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
