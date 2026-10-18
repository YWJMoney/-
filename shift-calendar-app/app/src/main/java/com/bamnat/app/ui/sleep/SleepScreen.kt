package com.bamnat.app.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.AppSettings
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.sleep.SleepDebt
import com.bamnat.core.sleep.SleepEntry
import com.bamnat.core.sleep.SleepInsights
import com.bamnat.core.sleep.SleepMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SLP-16] The sleep log as something to use: what the two taps are for, the user's own averages
 * after a night shift, before a morning start and on a day off, a few plain findings, the last two
 * weeks as bars, and every record with a way to fix, add or delete one.
 */
class SleepViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings

    data class State(val log: List<SleepEntry>, val report: SleepInsights.Report, val today: LocalDate)

    val state: StateFlow<State?> = combine(
        container.sleepRepository.recent(days = SleepInsights.WINDOW_DAYS + SleepDebt.WINDOW_DAYS),
        container.healthSleep.sleeps,
        container.scheduleRepository.snapshot,
        settings,
    ) { manual, measured, snapshot, s ->
        val log = SleepMerge.merge(manual, measured)
        val today = LocalDate.now()
        State(log, SleepInsights.report(log, snapshot.resolver, today, s.sleepTargetMinutes), today)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val health = container.healthSleep.state

    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    fun refreshHealth() { viewModelScope.launch { container.healthSleep.refresh(settings.value.healthSleep) } }

    fun add(start: LocalDateTime, end: LocalDateTime) {
        container.appScope.launch {
            val id = container.sleepRepository.add(start, end)
            _messages.emit(UndoMessage("${SleepInsights.hours(Duration.between(start, end).toMinutes())} 수면을 적었어요") {
                container.sleepRepository.delete(id)
            })
        }
    }

    fun edit(before: SleepEntry, start: LocalDateTime, end: LocalDateTime?) {
        container.appScope.launch {
            container.sleepRepository.edit(before.id, start, end)
            _messages.emit(UndoMessage("기록을 고쳤어요") { container.sleepRepository.edit(before.id, before.start, before.end) })
        }
    }

    fun delete(entry: SleepEntry) {
        container.appScope.launch {
            container.sleepRepository.delete(entry.id)
            _messages.emit(UndoMessage("기록을 지웠어요") { container.sleepRepository.restore(entry) })
        }
    }

    fun runUndo(m: UndoMessage) { m.undo?.let { u -> container.appScope.launch { u() } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(vm: SleepViewModel, wide: Boolean, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SleepEntry?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.refreshHealth()
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.message, actionLabel = m.undo?.let { m.actionLabel }, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.runUndo(m)
        }
    }
    val target = settings.sleepTargetMinutes

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("수면 기록") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, "기록 추가") }, text = { Text("기록 추가") })
        },
    ) { padding ->
        val s = state ?: return@Scaffold
        val r = s.report
        val summary: @Composable ColumnScope.() -> Unit = {
            if (r.loggedDays < 7) HowItWorks()
            WeekTiles(r, target)
            if (r.findings.isNotEmpty()) Findings(r.findings)
            Groups(r, target)
        }
        val history: @Composable ColumnScope.() -> Unit = {
            Bars(s.log, s.today, target)
            Records(s.log, r, s.today) { e -> if (!e.imported) editing = e }
            healthNote(health)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("수면 기록은 이 휴대폰과 백업 파일에만 남아요. 일정에 맞춘 참고용 안내이고, 진단이 아니에요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { summary(); Spacer(Modifier.height(80.dp)) }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { history(); Spacer(Modifier.height(80.dp)) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) { summary(); history(); Spacer(Modifier.height(80.dp)) }
        }
    }

    if (adding) {
        SleepEditDialog(null, onDismiss = { adding = false }, onDelete = null) { start, end ->
            adding = false
            if (end != null) vm.add(start, end)
        }
    }
    editing?.let { e ->
        SleepEditDialog(e, onDismiss = { editing = null }, onDelete = { vm.delete(e); editing = null }) { start, end ->
            editing = null
            vm.edit(e, start, end)
        }
    }
}

/** [SLP-16] What the taps are for, said where the user first wonders. */
@Composable
private fun HowItWorks() {
    SectionCard(title = "수면 기록은 이렇게 쓰여요") {
        listOf(
            "잠들 때 ‘잠들었어요’, 깼을 때 ‘깼어요’를 누르면 돼요. 달력 위 오늘의 흐름 카드, 오늘 날짜, 자는 중 알림에 버튼이 있어요.",
            "깜빡했으면 여기서 ‘기록 추가’로 나중에 적어도 돼요. 워치 기록은 설정 > 수면에서 헬스 커넥트를 켜면 함께 세요.",
            "기록이 쌓이면 야간 뒤·아침 출근 전·쉬는 날 평균을 따로 보여 주고, 퇴근길 카드가 내 평균으로 알려 줘요.",
        ).forEachIndexed { i, line ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(20.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun WeekTiles(r: SleepInsights.Report, target: Int) {
    val w = r.week
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("최근 7일 평균", if (w.days == 0) "—" else SleepInsights.hours(w.averageMinutes), Modifier.weight(1f),
            caption = "목표 ${SleepInsights.hours(target.toLong())}")
        StatTile("목표보다 부족", if (w.days == 0) "—" else if (w.shortMinutes == 0L) "없음" else SleepInsights.hours(w.shortMinutes),
            Modifier.weight(1f), caption = "기록한 ${w.days}일 합계", warning = w.shortMinutes >= 4 * 60)
        StatTile("기록한 날", "${r.loggedDays}일", Modifier.weight(1f), caption = "최근 ${SleepInsights.WINDOW_DAYS}일")
    }
}

@Composable
private fun Findings(findings: List<SleepInsights.Finding>) {
    val cal = LocalCalendarColors.current
    SectionCard(title = "내 기록에서 보이는 것") {
        findings.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val (icon, tint) = when (f.tone) {
                SleepInsights.Tone.WARN -> Icons.Filled.WarningAmber to cal.warning
                SleepInsights.Tone.GOOD -> Icons.Filled.CheckCircle to cal.income
                SleepInsights.Tone.NOTE -> Icons.Filled.Info to MaterialTheme.colorScheme.primary
            }
            Row {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(f.title, style = MaterialTheme.typography.titleSmall)
                    Text(f.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Average per kind of day against the target, as bars that read at any font size. */
@Composable
private fun Groups(r: SleepInsights.Report, target: Int) {
    if (r.groups.isEmpty()) return
    val cal = LocalCalendarColors.current
    SectionCard(title = "근무별 평균") {
        val max = maxOf(target.toLong(), r.groups.maxOf { it.averageMinutes }).coerceAtLeast(1)
        r.groups.forEach { g ->
            Column(Modifier.padding(vertical = 6.dp).semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(g.context.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(SleepInsights.hours(g.averageMinutes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (target - g.averageMinutes >= SleepInsights.SHORT_MINUTES) cal.warning else MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxWidth(g.averageMinutes.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary))
                }
                Text(
                    "${g.count}번 · 보통 ${g.averageBedtime?.let(SleepInsights::clock) ?: "--:--"}에 잠들어요" +
                        if (!g.enough) " · 기록이 더 있으면 정확해져요" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (r.naps > 0) {
            Text("짧은 낮잠 ${r.naps}번은 평균에서 뺐어요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The last 14 days by the day the user woke up, with the target as a dashed line. */
@Composable
private fun Bars(log: List<SleepEntry>, today: LocalDate, target: Int) {
    val days = (13 downTo 0).map { today.minusDays(it.toLong()) }
    val byDay = log.filter { (it.minutes ?: 0) in 1..SleepDebt.MAX_SLEEP_MINUTES }.groupBy { it.wakeDate }
        .mapValues { (_, l) -> l.sumOf { it.minutes!! } }
    val values = days.map { byDay[it] ?: 0L }
    val max = maxOf(values.maxOrNull() ?: 0, target.toLong() + 60).coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val short = LocalCalendarColors.current.warning
    val line = MaterialTheme.colorScheme.outline
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    val described = days.zip(values).filter { it.second > 0 }
        .joinToString(", ") { (d, v) -> "${Fmt.shortDate(d)} ${SleepInsights.hours(v)}" }
    SectionCard(title = "최근 2주") {
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "최근 2주 수면: " + described.ifEmpty { "기록 없음" } }) {
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (days.size - 1)) / days.size
            values.forEachIndexed { i, v ->
                val h = if (v == 0L) 4.dp.toPx() else size.height * v / max
                drawRoundRect(
                    color = if (v == 0L) empty else if (v < target - SleepInsights.SHORT_MINUTES) short else primary,
                    topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h), cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            val y = size.height - size.height * target / max
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(Fmt.shortDate(days.first()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text("점선 목표 ${SleepInsights.hours(target.toLong())}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("오늘", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Records(log: List<SleepEntry>, r: SleepInsights.Report, today: LocalDate, onOpen: (SleepEntry) -> Unit) {
    val contexts = r.nights.associate { it.entry.id to it.context }
    val shown = log.filter { e -> (e.wakeDate ?: e.start.toLocalDate()) >= today.minusDays(SleepInsights.WINDOW_DAYS - 1) }
    SectionCard(title = "기록 ${shown.size}개") {
        if (shown.isEmpty()) {
            Text("아직 기록이 없어요. 오늘 잠들 때 ‘잠들었어요’를 눌러 보세요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        shown.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !e.imported) { onOpen(e) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (e.imported) Icons.Filled.Watch else Icons.Filled.Bedtime, contentDescription = if (e.imported) "워치 기록" else null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(Fmt.dayTitle(e.wakeDate ?: e.start.toLocalDate()), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            "${hm(e.start)}–${e.end?.let(::hm) ?: "자는 중"}",
                            contexts[e.id]?.label,
                            if (e.imported) "워치·헬스 커넥트" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(e.minutes?.let { if (it > SleepDebt.MAX_SLEEP_MINUTES) "통계 제외" else SleepInsights.hours(it) } ?: "",
                    style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

private fun hm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

private fun healthNote(state: com.bamnat.app.sleep.HealthSleep.State): String? = when (state) {
    com.bamnat.app.sleep.HealthSleep.State.READY -> "헬스 커넥트 기록을 함께 보여 줘요. 직접 적은 기록과 겹치면 직접 기록을 써요."
    com.bamnat.app.sleep.HealthSleep.State.NEEDS_PERMISSION -> "헬스 커넥트 수면 읽기 허용이 꺼져 있어요. 설정 > 수면에서 다시 켤 수 있어요."
    else -> null
}

/**
 * [SLP-16] A sleep written or corrected by hand: the night it ended on, when it began and ended.
 * The end may be the next day of the start; an open sleep ([initial] with no end) may stay open.
 */
@Composable
private fun SleepEditDialog(
    initial: SleepEntry?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (LocalDateTime, LocalDateTime?) -> Unit,
) {
    val now = remember { LocalDateTime.now().withSecond(0).withNano(0) }
    var wake by rememberSaveable { mutableStateOf((initial?.end ?: initial?.start ?: now).toLocalDate().toString()) }
    var start by rememberSaveable { mutableStateOf((initial?.start?.toLocalTime() ?: LocalTime.of(23, 0)).toString()) }
    var end by rememberSaveable { mutableStateOf((initial?.end?.toLocalTime() ?: LocalTime.of(7, 0)).toString()) }
    var open by rememberSaveable { mutableStateOf(initial != null && initial.end == null) }
    var picking by remember { mutableStateOf<String?>(null) }
    val wakeDate = LocalDate.parse(wake)
    val endTime = LocalTime.parse(end)
    val startTime = LocalTime.parse(start)
    // A start later in the day than the end began the evening before.
    val endAt = wakeDate.atTime(endTime)
    val startAt = if (startTime.isAfter(endTime) || startTime == endTime) wakeDate.minusDays(1).atTime(startTime) else wakeDate.atTime(startTime)
    val minutes = Duration.between(startAt, endAt).toMinutes()
    val valid = open || (minutes in 1..SleepDebt.MAX_SLEEP_MINUTES && !endAt.isAfter(now.plusMinutes(1)))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "수면 기록 추가" else "수면 기록 고치기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("깬 날", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val today = now.toLocalDate()
                    listOf(today.minusDays(2), today.minusDays(1), today).forEach { d ->
                        FilterChip(selected = wakeDate == d, onClick = { wake = d.toString() },
                            label = { Text(when (d) { today -> "오늘"; today.minusDays(1) -> "어제"; else -> Fmt.shortDate(d) }) })
                    }
                    if (wakeDate.isBefore(now.toLocalDate().minusDays(2))) FilterChip(selected = true, onClick = {}, label = { Text(Fmt.shortDate(wakeDate)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.weight(1f)) {
                        Text("잠든 시각 ${start}")
                    }
                    OutlinedButton(onClick = { picking = "end" }, enabled = !open, modifier = Modifier.weight(1f)) {
                        Text(if (open) "자는 중" else "깬 시각 ${end}")
                    }
                }
                Text(
                    when {
                        open -> "아직 자는 중으로 둬요."
                        !valid && minutes > SleepDebt.MAX_SLEEP_MINUTES -> "16시간이 넘어요. 시각을 다시 확인해 주세요."
                        !valid && endAt.isAfter(now) -> "아직 오지 않은 시각이에요."
                        else -> "${Fmt.shortDate(startAt.toLocalDate())} ${hm(startAt)}부터 ${SleepInsights.hours(minutes)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else LocalCalendarColors.current.warning,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                if (open) onSave(wakeDate.atTime(startTime).let { if (it.isAfter(now)) it.minusDays(1) else it }, null)
                else onSave(startAt, endAt)
            }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("지우기", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
    when (picking) {
        "start" -> TimePickerDialog("잠든 시각", startTime, onDismiss = { picking = null }) { start = it.toString(); picking = null }
        "end" -> TimePickerDialog("깬 시각", endTime, onDismiss = { picking = null }) { end = it.toString(); open = false; picking = null }
    }
}
