package com.bamnat.app.ui.shifts

import com.bamnat.core.hours.HoursFormat
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.PatternPresets
import com.bamnat.core.schedule.PatternTimeline
import com.bamnat.core.schedule.ScheduleResolver
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatternScreen(
    vm: ShiftsViewModel,
    onBack: () -> Unit,
    onOpenShiftTypes: () -> Unit,
    startWeekdays: Boolean = false,
    /** [PAYX-13] Part of the first setup: kind of work → cycle → pay. */
    setup: Boolean = false,
    /** Where to go after the first pattern is saved; null goes back. */
    onSaved: (() -> Unit)? = null,
) {
    val typesOrNull by vm.shiftTypes.collectAsStateWithLifecycle()
    val patternState by vm.pattern.collectAsStateWithLifecycle()
    val types = typesOrNull
    val loadedPattern = patternState
    if (types == null || loadedPattern == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val today = com.bamnat.app.ui.calendar.rememberToday() // [CAL-25]
    val existing = loadedPattern.pattern
    // The draft lives in the ViewModel so a trip to the shift type screen does not lose it.
    val draft0 = vm.draft
    LaunchedEffect(Unit) {
        draft0.initFrom(existing, today, if (startWeekdays) types.firstOrNull { !it.isOff && it.hasTimes }?.id else null)
    }
    if (!draft0.initialized) return
    var kind by draft0::kind
    val rotation = draft0.rotation
    val weekly = draft0.weekly
    var startDate by draft0::startDate
    var endDate by draft0::endDate
    var selectedStep by remember { mutableStateOf<Int?>(null) }
    var pickEnd by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var askWhen by remember { mutableStateOf(false) }
    val history by vm.history.collectAsStateWithLifecycle()

    val typesById = remember(types) { types.associateBy { it.id } }
    // Steps pointing at a shift type deleted meanwhile become plain days off.
    val clean: (List<Long?>) -> List<Long?> = { steps -> steps.map { id -> id?.takeIf { it in typesById } } }
    val draft: WorkPattern? = when (kind) {
        PatternKind.ROTATION ->
            if (rotation.isEmpty()) null else WorkPattern.Rotation(startDate, clean(rotation.toList()), endDate)
        PatternKind.WEEKLY -> WorkPattern.Weekly.fromSteps(startDate, clean(weekly.toList()), endDate, draft0.holidaysOff, draft0.altSaturday)
    }
    // [SET-04] An order with no working day would fill the calendar with days off only (ux-review 1 P28).
    val works = when (kind) {
        PatternKind.ROTATION -> rotation.any { id -> id?.let { typesById[it] }?.isOff == false }
        PatternKind.WEEKLY -> weekly.any { id -> id?.let { typesById[it] }?.isOff == false }
    }
    // [SCHED-02] Past days with a 연차, 잔업·특근 or own times that "지금 순서를 고쳐요" would put under another shift.
    val entries by vm.entries.collectAsStateWithLifecycle()
    val moved = remember(existing, draft, entries, today) {
        if (existing != null && draft != null) PatternTimeline.recordedDaysMoved(existing, draft, entries, today) else emptyList()
    }
    val valid = draft != null && works && (endDate == null || !endDate!!.isBefore(today))
    val save: () -> Unit = {
        if (draft != null) {
            // With a pattern already in place, ask whether this is a team change and from when.
            if (existing == null) vm.saveFirstPattern(draft, onSaved ?: onBack)
            else if (moved.isEmpty() && savesWithoutAsking(existing, draft, today)) vm.fixCurrentPattern(draft, onBack)
            else askWhen = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { CalendarTitle(if (setup) "1/2 근무 주기" else "근무 주기", vm) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    TextButton(onClick = save, enabled = valid) { Text(if (setup && existing == null) "다음" else "저장") }
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
            if (setup && existing == null) {
                // [PAYX-13] The first setup runs kind of work → cycle → pay without a detour.
                Text(
                    "근무 주기를 고르고 ‘다음’을 누르면 급여 계산 설정(2/2)으로 이어져요. 급여는 나중에 해도 돼요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = kind == PatternKind.ROTATION,
                    onClick = { kind = PatternKind.ROTATION },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("순서대로 돌아요", maxLines = 1) }
                SegmentedButton(
                    selected = kind == PatternKind.WEEKLY,
                    onClick = { kind = PatternKind.WEEKLY },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("요일마다 같아요", maxLines = 1) }
            }
            Text(
                // [SETUP-09] What each choice means, with an example, instead of 교대 순환·요일 고정.
                if (kind == PatternKind.ROTATION) "교대 근무예요. 주주야야비휴처럼 정한 순서가 며칠마다 다시 돌아요."
                else "월~금 주간처럼 요일마다 근무가 같아요. 상근직·파트타임이 주로 골라요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (kind == PatternKind.ROTATION) {
                SectionCard(
                    title = "순서 (${rotation.size}일 주기)",
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
                        // [SETUP-09] Labelled buttons instead of two bare icons in the corner.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                val sel = selectedStep
                                if (sel != null && sel in rotation.indices) {
                                    rotation.removeAt(sel)
                                    selectedStep = null
                                } else if (rotation.isNotEmpty()) {
                                    rotation.removeAt(rotation.lastIndex)
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.Backspace, null, Modifier.size(18.dp))
                                Text(if (selectedStep != null) " 고른 칸 지우기" else " 마지막 칸 지우기")
                            }
                            TextButton(onClick = { rotation.clear(); selectedStep = null }) {
                                Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp))
                                Text(" 모두 지우기")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (selectedStep != null) "바꿀 근무를 눌러 주세요" else "누를 때마다 하루씩 끝에 붙어요",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
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
                    TextButton(onClick = onOpenShiftTypes) { Text("근무 종류 추가·수정") }
                }

                // Right under the input so each tap shows its effect on the calendar at once.
                if (draft != null) {
                    SectionCard(title = "미리보기") {
                        PatternPreview(types = types, pattern = draft, today = today, weekStart = vm.settings.value.weekStart)
                        // [REST-02][REST-03] The new order read with the 근무표 점검 before it is saved.
                        val (speed, issues) = remember(types, draft) { vm.previewIssues(types, draft, today) }
                        PatternCheck(speed, issues)
                    }
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

                // Presets sit below so the order input and its preview share the first screen.
                SectionCard(title = "자주 쓰는 패턴") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val presets = remember(types) { PatternPresets.ordered(types).map { it to PatternPresets.fits(it, types) } }
                        for ((preset, fits) in presets) {
                            AssistChip(
                                // A preset with a shift the user lacks leaves those days off; said on the chip.
                                onClick = {
                                    rotation.clear()
                                    rotation.addAll(PatternPresets.toSteps(preset, types))
                                    selectedStep = null
                                },
                                label = { Text("${preset.name} · ${preset.description}" + if (fits) "" else " (없는 근무는 쉬는 날)") },
                            )
                        }
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
                    // [SCHED-04] G P18·BC5: a weekday worker's 공휴일 off and 격주 토요일, read by the calendar and alarms.
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("공휴일엔 쉬어요", style = MaterialTheme.typography.bodyLarge)
                            Text("빨간 날과 대체공휴일은 쉬는 날이 되고 알람도 울리지 않아요", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = draft0.holidaysOff, onCheckedChange = { draft0.holidaysOff = it })
                    }
                    if (weekly[DayOfWeek.SATURDAY.ordinal] != null) {
                        val thisSaturday = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("토요일은 격주로 일해요", style = MaterialTheme.typography.bodyLarge)
                                Text("한 주 걸러 토요일이 쉬는 날이 돼요", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = draft0.altSaturday != null, onCheckedChange = { on ->
                                draft0.altSaturday = if (on) thisSaturday else null
                            })
                        }
                        draft0.altSaturday?.let { alt ->
                            val worksThis = Math.floorMod(ChronoUnit.WEEKS.between(alt, thisSaturday), 2L) == 0L
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = worksThis, onClick = { draft0.altSaturday = thisSaturday },
                                    label = { Text("이번 토요일(${Fmt.monthDay(thisSaturday)}) 일해요") })
                                FilterChip(selected = !worksThis, onClick = { draft0.altSaturday = thisSaturday.plusWeeks(1) },
                                    label = { Text("다음 토요일 일해요") })
                            }
                        }
                    }
                }
                if (draft != null) {
                    SectionCard(title = "미리보기") {
                        PatternPreview(types = types, pattern = draft, today = today, weekStart = vm.settings.value.weekStart)
                        // [REST-02][REST-03] The new order read with the 근무표 점검 before it is saved.
                        val (speed, issues) = remember(types, draft) { vm.previewIssues(types, draft, today) }
                        PatternCheck(speed, issues)
                    }
                }
            }

            SectionCard(title = "적용 기간") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("종료일 정하기", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = endDate != null, onCheckedChange = { on ->
                        endDate = if (on) today.plusMonths(6) else null
                    })
                }
                endDate?.let { end ->
                    DateRow("종료일", end) { pickEnd = true }
                    if (end.isBefore(today)) {
                        Text("종료일이 오늘보다 빨라요", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    if (existing == null) {
                        "처음 저장하면 지난 2년도 같은 순서로 채워져요. 직접 바꾼 날짜는 그대로 남아요."
                    } else {
                        "저장할 때 조 변경으로 언제부터 바꿀지 고를 수 있어요. 그 전날까지의 근무와 잔업·특근·메모는 그대로 남아요."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (history.size > 1) {
                SectionCard(title = "교대 주기 기록") {
                    for (p in history.asReversed()) {
                        PatternHistoryRow(p, typesById, today)
                    }
                }
            }

            Button(
                onClick = save,
                enabled = valid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (existing == null) "저장하고 달력에 적용" else "저장 (조 변경 / 수정)") }
            if (existing != null) {
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("패턴 삭제", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (pickEnd) {
        val initial = endDate ?: startDate
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickEnd = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        endDate = d
                    }
                    pickEnd = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { pickEnd = false }) { Text("취소") } },
        ) {
            DatePicker(state = state)
        }
    }

    if (askWhen && draft != null) {
        ChangeFromDialog(
            draft = draft,
            typesById = typesById,
            today = today,
            moved = moved,
            onDismiss = { askWhen = false },
            onChangeFrom = { pattern, from ->
                askWhen = false
                vm.changePatternFrom(pattern, from, onBack)
            },
            onFix = {
                askWhen = false
                vm.fixCurrentPattern(draft, onBack)
            },
        )
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

/** One period in the history, e.g. "2024.10.14 – 2026.11.30 · 주주휴휴야야". */
/**
 * [SET-04] The same order with only "오늘은 몇 번째 날" moved, on a pattern that has no past days yet, is a fix:
 * no "언제부터" question whose first button would undo it (ux-review 1 P9). With past days the same change is a
 * team change (4조 2교대 moving teams), so it is asked and the past keeps the old team.
 */
internal fun savesWithoutAsking(existing: WorkPattern, draft: WorkPattern, today: LocalDate): Boolean =
    existing is WorkPattern.Rotation && draft is WorkPattern.Rotation && existing.steps == draft.steps &&
        existing.endDate == draft.endDate && !existing.startDate.isBefore(today)

@Composable
private fun PatternHistoryRow(pattern: WorkPattern, typesById: Map<Long, ShiftType>, today: LocalDate) {
    val current = pattern.covers(today)
    val order = when (pattern) {
        is WorkPattern.Rotation -> pattern.steps.joinToString("") { id -> id?.let { typesById[it]?.label } ?: "휴" }
        is WorkPattern.Weekly -> "요일 고정"
    }
    val range = "${Fmt.shortFullDate(pattern.startDate)} – " + (pattern.endDate?.let { Fmt.shortFullDate(it) } ?: "계속")
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(order, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (current) {
            Text("지금", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Asked when a pattern already exists: a team change applies the new order from a chosen date and
 * keeps every earlier day; a fix rewrites the period being worked now.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChangeFromDialog(
    draft: WorkPattern,
    typesById: Map<Long, ShiftType>,
    today: LocalDate,
    moved: List<LocalDate>,
    onDismiss: () -> Unit,
    onChangeFrom: (WorkPattern, LocalDate) -> Unit,
    onFix: () -> Unit,
) {
    val nextMonth = today.withDayOfMonth(1).plusMonths(1)
    var from by remember { mutableStateOf(nextMonth) }
    val rotation = draft as? WorkPattern.Rotation
    // Which step of the order falls on the first new day: [SET-04] where the order as edited puts it, so the
    // place just set on this screen is kept unless the user picks another (ux-review 1 P9).
    var firstStep by remember { mutableStateOf(rotation?.stepIndexOn(nextMonth) ?: 0) }
    val start: (LocalDate) -> Unit = { d -> from = d; rotation?.let { firstStep = it.stepIndexOn(d) } }
    var pickDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("언제부터 바꿀까요?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "조가 바뀌었다면 새 순서를 시작할 날을 골라 주세요. 그 전날까지의 근무와 잔업·특근·대근, 메모는 그대로 남아요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, date) in listOf("오늘" to today, "내일" to today.plusDays(1), "다음 달 1일" to nextMonth)) {
                        FilterChip(selected = from == date, onClick = { start(date) }, label = { Text(label) })
                    }
                    FilterChip(
                        selected = from != today && from != today.plusDays(1) && from != nextMonth,
                        onClick = { pickDate = true },
                        label = { Text("날짜 고르기") },
                        leadingIcon = { Icon(Icons.Filled.Event, null, Modifier.size(18.dp)) },
                    )
                }
                Text("새 순서 시작: ${Fmt.fullDate(from)}", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                if (rotation != null) {
                    Text("${Fmt.dayTitle(from)}은 순서의 몇 번째 날인가요?", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rotation.steps.forEachIndexed { i, id ->
                            FilterChip(
                                selected = firstStep == i,
                                onClick = { firstStep = i },
                                label = { Text("${i + 1}") },
                                leadingIcon = {
                                    ShiftBadge(id?.let { typesById[it] }, size = 18.dp, text = id?.let { typesById[it]?.label } ?: "휴")
                                },
                            )
                        }
                    }
                }
                HorizontalDivider()
                // [SCHED-02] The fix rewrites the past of this period; say which recorded days it would move.
                if (moved.isNotEmpty()) {
                    Text(
                        "지금 순서를 고치면 기록이 있는 지난 ${moved.size}일의 근무도 바뀌어요: " +
                            moved.take(4).joinToString { Fmt.monthDay(it) } + (if (moved.size > 4) " 외" else "") +
                            ". 연차·잔업·특근 기록은 남지만 그날 근무 시간이 달라져 급여가 바뀔 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onFix, modifier = Modifier.fillMaxWidth()) {
                    Text(if (moved.isEmpty()) "조 변경이 아니에요. 지금 순서를 고쳐요" else "그래도 지난 날까지 고쳐요")
                }
            }
        },
        confirmButton = {
            // A pattern that ends before the new start would apply to no day at all.
            val endsBefore = draft.endDate?.isBefore(from) == true
            TextButton(enabled = !endsBefore, onClick = {
                val pattern = if (rotation != null) rotation.copy(anchorDate = from.minusDays(firstStep.toLong())) else draft
                onChangeFrom(pattern, from)
            }) { Text(if (endsBefore) "끝나는 날(${draft.endDate!!.monthValue}/${draft.endDate!!.dayOfMonth}) 뒤라 적용할 수 없어요" else "이날부터 적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms -> start(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("취소") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun StepCell(index: Int, type: ShiftType?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            // [VIS-02] 48dp to the finger, though the badge is smaller.
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
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

/**
 * [SCHED-06] I 리뷰 I11: the order shown over a whole month, the same month view the first setup uses
 * ([com.bamnat.app.ui.setup.SetupMonth]), from this month up to a year ahead, so a 격주 토요일 or a 공휴일 off is
 * seen where it falls rather than in a strip of three weeks.
 */
@Composable
private fun PatternPreview(types: List<ShiftType>, pattern: WorkPattern, today: LocalDate, weekStart: DayOfWeek) {
    val thisMonth = java.time.YearMonth.from(today)
    var month by rememberSaveable { mutableStateOf(thisMonth.toString()) }
    val shown = java.time.YearMonth.parse(month)
    com.bamnat.app.ui.setup.SetupMonth(
        month = shown,
        snapshot = remember(types, pattern) { com.bamnat.app.data.ScheduleSnapshot(types, pattern, emptyList()) },
        weekStart = weekStart,
        today = today,
        selected = null,
        onMonth = { month = it.toString() },
        first = thisMonth,
        last = thisMonth.plusMonths(12),
    )
}

/**
 * [REST-02][REST-03] What the 근무표 점검 will say about the order being edited, before it is saved: how fast it turns
 * and the first few signs over two cycles. Only a hint; saving is never stopped.
 */
@Composable
private fun PatternCheck(speed: com.bamnat.core.schedule.RotationSpeed, issues: List<com.bamnat.core.schedule.RestCheck.Issue>) {
    if (speed == com.bamnat.core.schedule.RotationSpeed.NONE) return
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("이 순서: ${speed.label}", style = MaterialTheme.typography.labelLarge)
        if (issues.isEmpty()) {
            Text("근무표 점검에 걸리는 날이 없어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val kinds = issues.groupBy { it.kind }
            kinds.entries.take(4).forEach { (kind, list) ->
                Text("· ${kind.label} ${list.size}번 — ${list.first().text}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 2)
            }
            Text("두 바퀴를 내 근무표 점검 기준으로 본 참고예요. 저장은 그대로 할 수 있어요.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
