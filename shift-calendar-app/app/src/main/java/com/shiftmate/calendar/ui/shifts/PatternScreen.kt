package com.shiftmate.calendar.ui.shifts

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiftmate.calendar.ui.Fmt
import com.shiftmate.calendar.ui.components.ConfirmDialog
import com.shiftmate.calendar.ui.components.SectionCard
import com.shiftmate.calendar.ui.components.ShiftBadge
import com.shiftmate.calendar.ui.theme.LocalCalendarColors
import com.shiftmate.core.model.ShiftType
import com.shiftmate.core.model.WorkPattern
import com.shiftmate.core.schedule.PatternPresets
import com.shiftmate.core.schedule.ScheduleResolver
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatternScreen(vm: ShiftsViewModel, onBack: () -> Unit, onOpenShiftTypes: () -> Unit) {
    val typesOrNull by vm.shiftTypes.collectAsStateWithLifecycle()
    val patternState by vm.pattern.collectAsStateWithLifecycle()
    val types = typesOrNull
    val loadedPattern = patternState
    if (types == null || loadedPattern == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val today = remember { LocalDate.now() }
    val existing = loadedPattern.pattern
    // The draft lives in the ViewModel so a trip to the shift type screen does not lose it.
    val draft0 = vm.draft
    LaunchedEffect(Unit) { draft0.initFrom(existing, today) }
    if (!draft0.initialized) return
    var kind by draft0::kind
    val rotation = draft0.rotation
    val weekly = draft0.weekly
    var startDate by draft0::startDate
    var endDate by draft0::endDate
    var selectedStep by remember { mutableStateOf<Int?>(null) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val typesById = remember(types) { types.associateBy { it.id } }
    // Steps pointing at a shift type deleted meanwhile become plain days off.
    val clean: (List<Long?>) -> List<Long?> = { steps -> steps.map { id -> id?.takeIf { it in typesById } } }
    val draft: WorkPattern? = when (kind) {
        PatternKind.ROTATION ->
            if (rotation.isEmpty()) null else WorkPattern.Rotation(startDate, clean(rotation.toList()), endDate)
        PatternKind.WEEKLY -> WorkPattern.Weekly.fromSteps(startDate, clean(weekly.toList()), endDate)
    }
    val valid = draft != null && (endDate == null || !endDate!!.isBefore(startDate))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("근무 패턴") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    TextButton(onClick = { draft?.let { vm.savePattern(it, onBack) } }, enabled = valid) { Text("저장") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = kind == PatternKind.ROTATION,
                    onClick = { kind = PatternKind.ROTATION },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("교대 순환") }
                SegmentedButton(
                    selected = kind == PatternKind.WEEKLY,
                    onClick = { kind = PatternKind.WEEKLY },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("요일 고정") }
            }
            Text(
                if (kind == PatternKind.ROTATION) "주주야야비휴처럼 며칠 단위로 반복되는 교대 근무"
                else "요일마다 같은 근무 (파트타임, 상근직)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (kind == PatternKind.ROTATION) {
                SectionCard(title = "자주 쓰는 패턴") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (preset in PatternPresets.all) {
                            AssistChip(
                                onClick = {
                                    rotation.clear()
                                    rotation.addAll(PatternPresets.toSteps(preset, types))
                                    selectedStep = null
                                },
                                label = { Text("${preset.name} · ${preset.description}") },
                            )
                        }
                    }
                }

                SectionCard(
                    title = "순서 (${rotation.size}일 주기)",
                    trailing = {
                        Row {
                            IconButton(onClick = {
                                val sel = selectedStep
                                if (sel != null && sel in rotation.indices) {
                                    rotation.removeAt(sel)
                                    selectedStep = null
                                } else if (rotation.isNotEmpty()) {
                                    rotation.removeAt(rotation.lastIndex)
                                }
                            }, enabled = rotation.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, if (selectedStep != null) "선택한 칸 지우기" else "마지막 칸 지우기")
                            }
                            IconButton(onClick = { rotation.clear(); selectedStep = null }, enabled = rotation.isNotEmpty()) {
                                Icon(Icons.Filled.DeleteSweep, "모두 지우기")
                            }
                        }
                    },
                ) {
                    if (rotation.isEmpty()) {
                        Text(
                            "아래에서 근무를 순서대로 눌러 하루씩 넣으세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            rotation.forEachIndexed { i, id ->
                                StepCell(
                                    index = i,
                                    type = id?.let { typesById[it] },
                                    selected = selectedStep == i,
                                    onClick = { selectedStep = if (selectedStep == i) null else i },
                                )
                            }
                        }
                        if (selectedStep != null) {
                            Text(
                                "${selectedStep!! + 1}번째 칸을 골랐어요. 아래 근무를 누르면 바뀌어요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val put: (Long?) -> Unit = { id ->
                            val sel = selectedStep
                            if (sel != null && sel in rotation.indices) {
                                rotation[sel] = id
                                selectedStep = null
                            } else if (rotation.size < MAX_STEPS) {
                                rotation.add(id)
                            }
                        }
                        for (t in types) {
                            OutlinedButton(onClick = { put(t.id) }) {
                                ShiftBadge(t, outlined = t.isOff)
                                Spacer(Modifier.width(6.dp))
                                Text(t.name)
                            }
                        }
                        if (types.none { it.isOff }) {
                            OutlinedButton(onClick = { put(null) }) { Text("쉬는 날") }
                        }
                    }
                    TextButton(onClick = onOpenShiftTypes) { Text("근무 유형 추가·수정") }
                }

                if (rotation.isNotEmpty()) {
                    SectionCard(title = "오늘(${Fmt.shortDate(today)})은 몇 번째 날인가요?") {
                        val todayIndex = Math.floorMod(ChronoUnit.DAYS.between(startDate, today), rotation.size.toLong()).toInt()
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rotation.forEachIndexed { i, id ->
                                FilterChip(
                                    selected = !today.isBefore(startDate) && todayIndex == i,
                                    onClick = { startDate = today.minusDays(i.toLong()) },
                                    label = { Text("${i + 1}") },
                                    leadingIcon = { ShiftBadge(id?.let { typesById[it] }, size = 18.dp, text = id?.let { typesById[it]?.label } ?: "휴") },
                                )
                            }
                        }
                        Text(
                            "누르면 시작일이 자동으로 맞춰져요. 순서의 첫날: ${Fmt.fullDate(startDate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            } else {
                SectionCard(title = "요일별 근무") {
                    for (dow in DayOfWeek.entries) {
                        val cal = LocalCalendarColors.current
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                Fmt.dayOfWeekShort(dow),
                                style = MaterialTheme.typography.titleMedium,
                                color = when (dow) {
                                    DayOfWeek.SUNDAY -> cal.sunday
                                    DayOfWeek.SATURDAY -> cal.saturday
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.width(32.dp),
                            )
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = weekly[dow.ordinal] == null,
                                    onClick = { weekly[dow.ordinal] = null },
                                    label = { Text("쉼") },
                                )
                                for (t in types) {
                                    FilterChip(
                                        selected = weekly[dow.ordinal] == t.id,
                                        onClick = { weekly[dow.ordinal] = t.id },
                                        label = { Text(t.name) },
                                        leadingIcon = { ShiftBadge(t, size = 18.dp, outlined = t.isOff) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SectionCard(title = "적용 기간") {
                DateRow("시작일", startDate) { pickStart = true }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("종료일 정하기", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = endDate != null, onCheckedChange = { on ->
                        endDate = if (on) startDate.plusMonths(6) else null
                    })
                }
                endDate?.let { end ->
                    DateRow("종료일", end) { pickEnd = true }
                    if (end.isBefore(startDate)) {
                        Text("종료일이 시작일보다 빨라요", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "직접 바꾼 날짜는 패턴을 바꿔도 그대로 남아요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (draft != null) {
                SectionCard(title = "미리보기 (오늘부터 3주)") {
                    PatternPreview(types = types, pattern = draft, from = today)
                }
            }

            Button(
                onClick = { draft?.let { vm.savePattern(it, onBack) } },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("저장하고 달력에 적용") }
            if (existing != null) {
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("패턴 삭제", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (pickStart || pickEnd) {
        val initial = if (pickStart) startDate else (endDate ?: startDate)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickStart = false; pickEnd = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (pickStart) startDate = d else endDate = d
                    }
                    pickStart = false
                    pickEnd = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { pickStart = false; pickEnd = false }) { Text("취소") } },
        ) {
            DatePicker(state = state)
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "패턴을 삭제할까요?",
            message = "달력의 자동 표시가 사라져요. 직접 바꾼 날짜와 메모는 그대로 남아요.",
            confirmLabel = "삭제",
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                vm.clearPattern(onBack)
            },
        )
    }
}

private const val MAX_STEPS = 62

@Composable
private fun StepCell(index: Int, type: ShiftType?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShiftBadge(type, size = 28.dp, text = type?.label ?: "휴", outlined = type == null || type.isOff)
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DateRow(label: String, date: LocalDate, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Event, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(Fmt.fullDate(date), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.SwapHoriz, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun PatternPreview(types: List<ShiftType>, pattern: WorkPattern, from: LocalDate) {
    val resolver = remember(types, pattern) { ScheduleResolver(types, pattern, emptyList()) }
    val cal = LocalCalendarColors.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 21) {
            val date = from.plusDays(i.toLong())
            val day = resolver.resolve(date)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(36.dp)) {
                Text(
                    Fmt.dayOfWeekShort(date.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (date.dayOfWeek) {
                        DayOfWeek.SUNDAY -> cal.sunday
                        DayOfWeek.SATURDAY -> cal.saturday
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text("${date.dayOfMonth}", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
                val t = day.shiftType
                if (t != null) ShiftBadge(t, size = 22.dp, outlined = t.isOff) else Spacer(Modifier.height(22.dp))
            }
        }
    }
}
