package com.bamnat.app.ui.setup

import com.bamnat.app.ui.components.labelWidth
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material3.FilledTonalButton
import com.bamnat.app.ui.components.FilterChip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.calendar.MonthGrid
import com.bamnat.app.ui.calendar.PaintTool
import com.bamnat.app.ui.calendar.WEEK_BAR_SPACE
import com.bamnat.app.ui.calendar.WeekdayHeader
import com.bamnat.app.ui.calendar.cellTextGrowth
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.components.contentColorOn
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.PatternPresets
import com.bamnat.core.schedule.ShiftBundles
import com.bamnat.core.setup.SetupDraft
import com.bamnat.core.work.WorkStyle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun OrderStep(vm: SetupViewModel, d: SetupDraft) {
    when {
        d.usesRotation -> RotationOrder(vm, d)
        d.style == WorkStyle.SHIFT -> RosterPaint(vm, d)
        d.style == WorkStyle.HOURLY -> HourlyDays(vm, d)
        else -> {
            StepHeader("어느 요일에 일하세요?", "쉬는 요일은 비워 두세요. 어떤 주만 다르면 달력에서 그날만 바꾸면 돼요.")
            WeekDays(d, onSet = vm::setWeekly)
        }
    }
}

// ---- a repeating order ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RotationOrder(vm: SetupViewModel, d: SetupDraft) {
    StepHeader("근무가 어떤 순서로 돌아가요?", "회사 근무표에서 한 바퀴만 보면 돼요. 오늘이 어디쯤인지는 다음에 물어요.")
    val types = remember(d.types) { d.types.associateBy { it.id } }
    val shared = remember(d.source) { vm.sourceOrder(d) }
    val presets = remember(d.bundle, d.roles, d.types) { vm.presets(d).map { it to PatternPresets.toSteps(it, d.roles) } }
    // [SETUP-14] Kept through folding and unfolding.
    var custom by rememberSaveable {
        mutableStateOf(d.rotation.isNotEmpty() && d.rotation != shared && presets.none { it.second == d.rotation })
    }
    if (shared != null) {
        val source = d.source
        OrderCard(
            title = when {
                source?.kind == com.bamnat.core.setup.SetupSource.Kind.TEMPLATE -> "${source.name} 순서"
                source?.name.isNullOrBlank() -> "받은 설정의 순서"
                else -> "${source?.name} 순서"
            },
            steps = shared,
            types = types,
            selected = !custom && d.rotation == shared,
            onClick = {
                custom = false
                vm.setRotation(shared)
            },
        )
    }
    presets.filter { it.second != shared }.forEach { (p, steps) ->
        OrderCard(
            title = p.name,
            steps = steps,
            types = types,
            selected = !custom && d.rotation == steps,
            onClick = {
                custom = false
                vm.pickPreset(p)
            },
        )
    }
    ChoiceCard(
        title = "직접 만들기",
        subtitle = "근무를 하루씩 눌러 순서를 만들어요",
        icon = Icons.Filled.Edit,
        selected = custom,
        onClick = { custom = true },
    )
    if (custom) RotationEditor(d, types, onChange = vm::setRotation)
}

/** One order to pick: its days as badges, how often it comes round and the week's average. */
@Composable
private fun OrderCard(title: String, steps: List<Long?>, types: Map<Long, ShiftType>, selected: Boolean, onClick: () -> Unit) {
    ChoiceCard(
        title = title,
        subtitle = orderLine(steps, types),
        selected = selected,
        onClick = onClick,
        extra = { BadgeStrip(steps, types, Modifier.padding(top = 6.dp)) },
    )
}

/** "8일마다 반복 · 주 평균 38시간 30분" */
internal fun orderLine(steps: List<Long?>, types: Map<Long, ShiftType>): String =
    "${steps.size}일마다 반복 · 주 평균 ${HoursFormat.korean(ShiftBundles.weeklyMinutes(steps, types))}"

/** [SETUP-02] An order typed a day at a time; a day tapped is changed by the next shift tapped. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RotationEditor(d: SetupDraft, types: Map<Long, ShiftType>, onChange: (List<Long?>) -> Unit) {
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    val steps = d.rotation
    val choices: List<Long?> = d.types.map { it.id } + if (d.types.none { it.isOff }) listOf<Long?>(null) else emptyList()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val i = picked?.takeIf { it < steps.size }
            Text(
                when {
                    i != null -> "${i + 1}번째 날을 무엇으로 바꿀까요?"
                    steps.isEmpty() -> "첫날부터 순서대로 눌러 주세요"
                    else -> "이어서 누르거나, 바꿀 날을 눌러 고쳐요"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (steps.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    steps.forEachIndexed { index, id ->
                        StepCell(index, id?.let(types::get), selected = index == i) { picked = if (picked == index) null else index }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { id ->
                    val t = id?.let(types::get)
                    FilledTonalButton(
                        onClick = {
                            if (i != null) {
                                onChange(steps.toMutableList().also { it[i] = id })
                                picked = null
                            } else if (steps.size < SetupViewModel.MAX_STEPS) {
                                onChange(steps + id)
                            }
                        },
                        enabled = i != null || steps.size < SetupViewModel.MAX_STEPS,
                    ) {
                        ShiftBadge(t, size = 20.dp, text = t?.label ?: "휴", outlined = t == null || t.isOff)
                        Spacer(Modifier.width(6.dp))
                        Text(t?.name ?: "휴무")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange(steps.dropLast(1)); picked = null }, enabled = steps.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, null)
                    Spacer(Modifier.width(6.dp))
                    Text("하나 지우기")
                }
                TextButton(onClick = { onChange(emptyList()); picked = null }, enabled = steps.isNotEmpty()) { Text("모두 지우기") }
            }
            if (steps.isNotEmpty()) {
                Text(orderLine(steps, types), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun StepCell(index: Int, type: ShiftType?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            // [VIS-02] 48dp to the finger, though the badge is smaller.
            .sizeIn(minWidth = 44.dp, minHeight = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
            .toggleable(value = selected, role = Role.Button, onValueChange = { onClick() })
            .semantics { contentDescription = "${index + 1}번째 날 ${type?.name ?: "휴무"}" }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShiftBadge(type, size = 26.dp, text = type?.label ?: "휴", outlined = type == null || type.isOff)
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- the same days every week ----

private val WEEK = DayOfWeek.entries

/**
 * [WORK-01] Monday to Sunday. With one shift the days are seven toggles; with several each day picks
 * its shift or none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekDays(d: SetupDraft, onSet: (Int, Long?) -> Unit) {
    val work = d.workTypes
    if (work.size == 1) {
        val t = work.first()
        val color = t.colorArgb.asColor()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WEEK.forEachIndexed { i, day ->
                val on = d.weekly.getOrNull(i) == t.id
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (on) color else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .toggleable(value = on, role = Role.Checkbox, onValueChange = { onSet(i, if (it) t.id else null) })
                        .semantics { contentDescription = "${Fmt.dayOfWeekShort(day)}요일 ${t.name}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        Fmt.dayOfWeekShort(day),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (on) contentColorOn(color) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "${t.name} ${hoursOf(t)} · 일하는 날 ${d.weekly.count { it == t.id }}일",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    WEEK.forEachIndexed { i, day ->
        val current = d.weekly.getOrNull(i)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.dayOfWeekShort(day), style = MaterialTheme.typography.titleMedium, modifier = Modifier.labelWidth(32.dp))
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                work.forEach { t ->
                    FilterChip(
                        selected = current == t.id,
                        onClick = { onSet(i, if (current == t.id) null else t.id) },
                        label = { Text(t.name) },
                        leadingIcon = { ShiftBadge(t, size = 18.dp) },
                    )
                }
                FilterChip(selected = d.isOff(current), onClick = { onSet(i, null) }, label = { Text("쉼") })
            }
        }
    }
}

@Composable
private fun HourlyDays(vm: SetupViewModel, d: SetupDraft) {
    StepHeader("일하는 요일이 정해져 있나요?", "정해져 있으면 달력에 미리 채워 둘게요.")
    ChoiceCard(
        title = "네, 정해져 있어요",
        subtitle = "요일마다 일하는 시간대를 골라요",
        selected = !d.noFixedDays,
        onClick = { vm.setNoFixedDays(false) },
    )
    ChoiceCard(
        title = "아니요, 그때그때 달라요",
        subtitle = "일한 날 달력을 눌러 적으면 돼요",
        icon = Icons.Filled.EventBusy,
        selected = d.noFixedDays,
        onClick = { vm.setNoFixedDays(true) },
    )
    if (!d.noFixedDays) WeekDays(d, onSet = vm::setWeekly)
}

// ---- a roster painted every month ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RosterPaint(vm: SetupViewModel, d: SetupDraft) {
    StepHeader(
        "이번 달 근무표를 칠해 볼까요?",
        "근무를 고르고 날짜를 누르거나 손가락으로 쓸어 칠해요. 다음 달 근무표는 나온 뒤 달력에서 이어 칠하면 돼요.",
    )
    val tool by vm.paintTool.collectAsStateWithLifecycle()
    val month by vm.paintMonth.collectAsStateWithLifecycle()
    val canUndo by vm.canUndoPaint.collectAsStateWithLifecycle()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        d.types.forEach { t ->
            val stamp = PaintTool.Shift(t.id)
            FilterChip(
                selected = tool == stamp,
                onClick = { vm.setPaintTool(stamp) },
                label = { Text(t.name) },
                leadingIcon = { ShiftBadge(t, size = 18.dp, outlined = t.isOff) },
                modifier = Modifier.semantics { contentDescription = "${t.name} 칠하기" },
            )
        }
        if (d.types.none { it.isOff }) {
            FilterChip(selected = tool == PaintTool.Off, onClick = { vm.setPaintTool(PaintTool.Off) }, label = { Text("쉬는 날") })
        }
        FilterChip(
            selected = tool == PaintTool.Clear,
            onClick = { vm.setPaintTool(PaintTool.Clear) },
            label = { Text("지우기") },
            leadingIcon = { Icon(Icons.Filled.LayersClear, null, Modifier.size(18.dp)) },
        )
    }
    val thisMonth = YearMonth.from(vm.today)
    SetupMonth(
        month = month,
        snapshot = remember(d.types, d.painted) { ScheduleSnapshot(d.types, null, d.paintedEntries()) },
        weekStart = vm.weekStart,
        today = vm.today,
        selected = null,
        // This month and the next: a roster further ahead is not out yet.
        onMonth = vm::showPaintMonth,
        first = thisMonth,
        last = thisMonth.plusMonths(1),
        onDayClick = vm::paintTap,
        onPaint = if (tool != PaintTool.Pick) vm::paint else null,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (d.painted.isEmpty()) "아직 칠한 날이 없어요" else "칠한 날 ${d.painted.size}일",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (canUndo) {
            TextButton(onClick = vm::undoPaint) {
                Icon(Icons.AutoMirrored.Filled.Undo, null)
                Spacer(Modifier.width(6.dp))
                Text("되돌리기")
            }
        }
    }
}

/**
 * A month of the draft as the calendar will show it, with ‹ › between [first] and [last]. Painting
 * is on while [onPaint] is given; the long-press menu of the calendar is not offered here.
 */
@Composable
internal fun SetupMonth(
    month: YearMonth,
    snapshot: ScheduleSnapshot,
    weekStart: DayOfWeek,
    today: LocalDate,
    selected: LocalDate?,
    onMonth: (YearMonth) -> Unit,
    first: YearMonth,
    last: YearMonth,
    onDayClick: (LocalDate) -> Unit = {},
    onPaint: ((List<LocalDate>) -> Unit)? = null,
) {
    val calculator = remember(snapshot) { WorkHoursCalculator(snapshot.resolver) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMonth(month.minusMonths(1)) }, enabled = month > first) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달")
                }
                Text(Fmt.month(month), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = month < last) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달")
                }
            }
            WeekdayHeader(weekStart, Modifier.padding(start = WEEK_BAR_SPACE))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Same cell as 내 달력, so a holiday name under the shift has its line (a 60dp cell cut it off on a folded phone).
                val cellHeight = (maxWidth / 7 * 1.3f).coerceIn(66.dp, 100.dp) + cellTextGrowth()
                MonthGrid(
                    month = month,
                    snapshot = snapshot,
                    calculator = calculator,
                    weekStart = weekStart,
                    selected = selected ?: LocalDate.MIN,
                    today = today,
                    memoDates = emptySet(),
                    showHours = false,
                    cellHeight = cellHeight,
                    onDayClick = onDayClick,
                    onQuickAction = { _, _ -> },
                    onPaint = onPaint,
                    quickMenu = false,
                    modifier = Modifier.heightIn(min = cellHeight * 5),
                )
            }
        }
    }
}
