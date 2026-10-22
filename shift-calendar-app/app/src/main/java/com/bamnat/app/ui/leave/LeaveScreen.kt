package com.bamnat.app.ui.leave

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Gate
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.pro.ProLock
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.app.ui.work.DateField
import com.bamnat.app.ui.work.Hint
import com.bamnat.app.ui.work.NumberField
import com.bamnat.app.ui.work.SwitchLine
import com.bamnat.app.ui.work.ValueLine
import com.bamnat.app.ui.work.toNumber
import com.bamnat.core.leave.EntryKind
import com.bamnat.core.leave.LeaveAccrual
import com.bamnat.core.leave.LeaveBasis
import com.bamnat.core.leave.LeavePayout
import com.bamnat.core.leave.LeavePool
import com.bamnat.core.leave.LeavePromotion
import com.bamnat.core.leave.LeaveSources
import com.bamnat.core.leave.LeaveSummary
import com.bamnat.core.leave.LedgerEntry
import com.bamnat.core.leave.LedgerLine
import com.bamnat.core.leave.LineKind
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * [LEAVE-06] 연차 화면: one calendar's 연차 원장 — what is usable now, what lapses when, every line that changed
 * it, the 사용 촉진 notice's switch [LEAVE-03], 징검다리 for its kind of work [LEAVE-04] and 미사용 연차수당
 * [LEAVE-05]. Leave itself goes on the calendar; this screen adds what the calendar cannot (받은 연차, 이월, 조정).
 */
class LeaveViewModel(private val container: AppContainer, val calendarId: String) : ViewModel() {
    private val scope = container.scopeOf(calendarId)
    private val repo = scope.leave

    val summary: StateFlow<LeaveSummary?> = repo.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profile: StateFlow<PayProfile?> = scope.pay.profile
    val snapshot: StateFlow<ScheduleSnapshot?> = scope.schedule.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val notice: StateFlow<Boolean> = container.settingsRepository.settings.map { it.leaveNotice }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.settingsRepository.current.leaveNotice)
    val style: StateFlow<WorkStyle?> = scope.settings.map { it.workStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), scope.currentSettings().workStyle)
    val gate: StateFlow<Gate> = container.featureGate.state

    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    fun setNotice(on: Boolean) = viewModelScope.launch { container.settingsRepository.update { it.copy(leaveNotice = on) } }

    fun setLowYear(year: Int, low: Boolean) = viewModelScope.launch {
        scope.pay.update { p ->
            val l = p.rules.leave
            p.copy(rules = p.rules.copy(leave = l.copy(lowAttendanceYears = if (low) l.lowAttendanceYears + year else l.lowAttendanceYears - year)))
        }
    }

    fun add(entry: LedgerEntry) = viewModelScope.launch {
        val id = repo.add(entry)
        _messages.emit(UndoMessage("${entry.kind.label} ${LeaveAccrual.days(entry.days)}을 넣었어요") { repo.delete(id) })
    }

    fun delete(entry: LedgerEntry) = viewModelScope.launch {
        repo.delete(entry.id)
        _messages.emit(UndoMessage("${entry.kind.label}을 뺐어요") { repo.add(entry) })
    }

    suspend fun entry(id: Long): LedgerEntry? = repo.all().firstOrNull { it.id == id }

    fun runUndo(m: UndoMessage) { m.undo?.let { u -> container.appScope.launch { u() } } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LeaveScreen(vm: LeaveViewModel, onBack: () -> Unit, onEditRules: () -> Unit) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val gate by vm.gate.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val ui = rememberCoroutineScope()
    var adding by remember { mutableStateOf<EntryKind?>(null) }
    var opened by remember { mutableStateOf<LedgerEntry?>(null) }
    var allLines by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.message, actionLabel = m.undo?.let { m.actionLabel }, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.runUndo(m)
        }
    }
    val s = summary
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("연차") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { NowCard(s, profile) }
            item { RulesCard(profile, onEditRules, vm) }
            item {
                // [LEAVE-03] On by default (S10); turned off here or in 설정 › 알림과 잠.
                SectionCard(title = "쓰라고 알려 주기") {
                    SwitchLine("연차 사용 촉진 알림", notice, vm::setNotice,
                        "연차가 없어지기 6개월 전과 2개월 전(첫해 월 연차는 3개월 전·1개월 전) 아침 9시에, 쓸 예정을 빼고도 남는 날을 알려 드려요.")
                    s?.nextLapse?.let { (date, days) ->
                        val next = LeavePromotion.next(s.lots.map { it.lot }, java.time.LocalDateTime.now())
                        Hint("${Fmt.fullDate(date)}에 ${LeaveAccrual.days(days)}가 없어질 수 있어요." +
                            (next?.let { " 다음 알림은 ${Fmt.shortDate(it.at.toLocalDate())}이에요." } ?: ""))
                    }
                    Hint("회사도 같은 때 서면으로 알려야 해요(근로기준법 제61조). 알림은 연차를 쓰도록 돕는 것뿐이라 켜 두어도 손해가 없어요. 필요 없으면 여기서 끄면 돼요.")
                }
            }
            if (s != null) item { LotsCard(s) }
            item {
                SectionCard(title = "직접 넣기") {
                    Hint("연차·반차는 달력에서 날을 길게 눌러 넣어요. 여기에는 달력에 없는 것만 넣어요.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(EntryKind.GRANT, EntryKind.CARRY, EntryKind.ADJUST).forEach { k ->
                            OutlinedButton(onClick = { adding = k }) { Text(k.label) }
                        }
                    }
                    Hint("받은 연차: 회사가 실제로 준 날이 앱이 센 날과 다를 때, 그해 몫을 바꿔요. 이월: 작년에서 넘어온 날. 조정: 더하거나 빼요(빼려면 −).")
                }
            }
            if (s != null && s.lines.isNotEmpty()) item {
                LinesCard(s, allLines, onAll = { allLines = true }) { id -> ui.launch { opened = vm.entry(id) } }
            }
            item { PayoutCard(s, profile, gate, vm) }
            snapshot?.let { snap -> item { BridgesCard(snap, style, left = s?.afterPlanned) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    adding?.let { kind -> AddDialog(kind, onDismiss = { adding = null }) { vm.add(it); adding = null } }
    opened?.let { e ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text("${e.kind.label} ${LeaveAccrual.days(e.days)}") },
            text = { Text(listOfNotNull(Fmt.fullDate(e.date), e.expires?.let { "${Fmt.fullDate(it)}까지" }, e.note.ifBlank { null }).joinToString("\n")) },
            confirmButton = { TextButton(onClick = { vm.delete(e); opened = null }) { Text("빼기") } },
            dismissButton = { TextButton(onClick = { opened = null }) { Text("닫기") } },
        )
    }
}

@Composable
private fun NowCard(s: LeaveSummary?, profile: PayProfile?) {
    SectionCard(title = "지금 쓸 수 있는 연차") {
        if (s == null) return@SectionCard
        Text(LeaveAccrual.days(s.available), style = MaterialTheme.typography.headlineMedium.merge(NumberStyle),
            color = if (s.available < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (s.planned > 0) ValueLine("달력에 넣은 쓸 예정", LeaveAccrual.days(s.planned))
        if (s.planned > 0) ValueLine("예정대로 쓰고 남는 날", LeaveAccrual.days(s.afterPlanned), strong = true)
        ValueLine("지금까지 생긴 연차 / 쓴 연차", "${LeaveAccrual.days(s.arisen)} / ${LeaveAccrual.days(s.used)}")
        if (s.comp != 0.0 || s.lots.any { it.lot.pool == LeavePool.COMP }) ValueLine("보상휴가", LeaveAccrual.days(s.comp))
        if (s.available < 0) Hint("생긴 것보다 많이 썼어요(미리 쓴 연차). 다음에 생기는 연차에서 먼저 빠져요.")
        LeaveSources.noAuto(profile)?.let { Hint(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesCard(profile: PayProfile?, onEditRules: () -> Unit, vm: LeaveViewModel) {
    val policy = profile?.rules?.leave ?: return
    val hire = profile.hireDate
    SectionCard(title = "세는 방법", trailing = { TextButton(onClick = onEditRules) { Text("바꾸기") } }) {
        Text(
            when {
                !policy.auto -> "직접 넣은 날만 세요"
                hire == null -> "입사일을 넣으면 법대로 세어요"
                policy.basis == LeaveBasis.HIRE -> "${Fmt.fullDate(hire)} 입사 · ${policy.basis.label}"
                else -> "${Fmt.fullDate(hire)} 입사 · ${policy.basis.label} (${policy.fiscalStartMonth}월 1일 시작)"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Hint("1년을 다 채운 다음 날 15일이 생기고 2년마다 1일씩 늘어 25일까지예요. 1년이 안 되면 한 달 개근마다 1일(최대 11일)이에요. " +
            "쓰지 않은 연차는 1년이 지나면 없어져요(근로기준법 제60조).")
        if (policy.auto && hire != null) {
            // ② 80% 미만 출근한 해: 15일 대신 개근한 달마다 1일, which the user adds by hand.
            val years = (java.time.temporal.ChronoUnit.YEARS.between(hire, LocalDate.now()).toInt()).coerceIn(0, 40)
            if (years >= 1) {
                Text("출근율 80%가 안 된 해", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..years).forEach { y ->
                        val low = y in policy.lowAttendanceYears
                        FilterChip(selected = low, onClick = { vm.setLowYear(y, !low) }, label = { Text("${y}년차") })
                    }
                }
                Hint("고른 해 다음에는 15일이 생기지 않아요. 그해 개근한 달만큼 ‘조정’으로 넣어 주세요.")
            }
        }
    }
}

@Composable
private fun LotsCard(s: LeaveSummary) {
    val open = s.open.filter { it.left > 1e-9 || it.lot.on == s.today }
    if (open.isEmpty()) return
    SectionCard(title = "없어지는 날 순서") {
        open.forEach { st ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(st.lot.kind.label + if (st.lot.note.isNotBlank()) " · ${st.lot.note}" else "", style = MaterialTheme.typography.bodyMedium)
                    Text(st.lot.expires?.let { "${Fmt.fullDate(it)}까지" } ?: "기한 없음",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${LeaveAccrual.days(st.left)} 남음", style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
            }
        }
        Hint("쓴 날은 먼저 없어지는 연차에서 빼요.")
    }
}

@Composable
private fun LinesCard(s: LeaveSummary, all: Boolean, onAll: () -> Unit, onOpen: (Long) -> Unit) {
    // Newest first; lines far ahead (lapses a year out) after the planned ones.
    val lines = s.lines.asReversed()
    val shown = if (all) lines else lines.filter { !it.date.isBefore(s.today.minusMonths(6)) && !it.date.isAfter(s.today.plusMonths(6)) }.take(20)
    SectionCard(title = "연차 원장") {
        shown.forEachIndexed { i, l ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp))
            LineRow(l, onOpen)
        }
        if (!all && shown.size < lines.size) TextButton(onClick = onAll) { Text("모두 보기 (${lines.size}줄)") }
    }
}

@Composable
private fun LineRow(l: LedgerLine, onOpen: (Long) -> Unit) {
    val color = when (l.kind) {
        LineKind.ARISE -> MaterialTheme.colorScheme.primary
        LineKind.LAPSE -> MaterialTheme.colorScheme.error
        LineKind.PLANNED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color.Unspecified
    }
    Row(
        Modifier.fillMaxWidth().then(l.entryId?.let { id -> Modifier.clickable(role = Role.Button, onClickLabel = "고치기") { onOpen(id) } } ?: Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${Fmt.shortDate(l.date)} · ${l.title}" + if (l.kind == LineKind.PLANNED) " (예정)" else "", style = MaterialTheme.typography.bodyMedium)
            val sub = listOfNotNull(l.note.ifBlank { null }, l.expires?.let { "${Fmt.shortDate(it)}까지" }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text((if (l.days > 0) "+" else "") + LeaveAccrual.days(l.days), style = MaterialTheme.typography.titleSmall.merge(NumberStyle), color = color)
            if (l.pool == LeavePool.ANNUAL) Text("남은 ${LeaveAccrual.days(l.balance)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [LEAVE-05] 미사용 연차수당 (프로): what the days that would lapse are worth, and a 정산 row once paid. */
@Composable
private fun PayoutCard(s: LeaveSummary?, profile: PayProfile?, gate: Gate, vm: LeaveViewModel) {
    if (s == null || profile == null) return
    val lapse = s.nextLapse
    val days = lapse?.second ?: s.afterPlanned.coerceAtLeast(0.0)
    val month = YearMonth.from(lapse?.first ?: s.today)
    val estimate = remember(days, profile, month) { LeavePayout.estimate(days, profile, month) }
    ProLock(Feature.LEAVE_PAYOUT, gate) {
        SectionCard(title = "못 쓴 연차, 돈으로는") {
            ValueLine(lapse?.let { "${Fmt.shortDate(it.first)}에 없어질 ${LeaveAccrual.days(days)}" } ?: "남은 ${LeaveAccrual.days(days)}",
                "약 ${Fmt.won(estimate.amount)}", strong = true)
            Hint("${estimate.formula}로 셌어요. 회사가 쓰라고 제대로 알렸는데도(근로기준법 제61조) 안 쓴 날은 돈으로 받지 못할 수 있어요. " +
                "받는 때는 연차를 쓸 수 있는 기간이 끝난 뒤 첫 월급날이에요(시행령 제33조).")
            if (days > 0) OutlinedButton(onClick = {
                vm.add(LedgerEntry(kind = EntryKind.PAYOUT, date = lapse?.first ?: s.today, days = days,
                    note = "미사용 연차수당 약 ${Fmt.won(estimate.amount)}"))
            }) { Text("돈으로 받았어요 (${LeaveAccrual.days(days)} 정산)") }
            Hint("그만둘 때는 다른 도구 › 그만두는 날 정리에서 남은 연차를 함께 셈해요.")
        }
    }
}

/** [LEAVE-04] 징검다리 for the calendar's kind of work: 공휴일 for a 일근 calendar, the roster's own days off otherwise. */
@Composable
private fun BridgesCard(snap: ScheduleSnapshot, style: WorkStyle?, left: Double?) {
    val today = remember { LocalDate.now() }
    // [LIFE-02] The ones the 연차 left can cover come first; the rest stay, marked, since more may arise before then.
    val bridges = remember(snap, style, today, left) { LeaveBridges.of(snap, style, today).sortedByDescending { LeaveBridges.fits(it, left) } }
    if (bridges.isEmpty()) return
    SectionCard(title = "연차로 길게 쉬기") {
        bridges.take(8).forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${Fmt.shortDate(b.from)}~${Fmt.shortDate(b.to)}" + if (b.holidays.isNotEmpty()) " · ${b.holidays.joinToString("·")}" else "",
                        style = MaterialTheme.typography.bodyLarge.merge(NumberStyle))
                    Text("연차: " + b.leave.joinToString(", ") { "${Fmt.shortDate(it)}(${Fmt.dayOfWeekShort(it.dayOfWeek)})" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LeaveBridges.shortNote(b, left)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = LocalCalendarColors.current.warning)
                    }
                }
                Text(b.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Hint(LeaveBridges.caption(style))
    }
}

/** 받은 연차, 이월 or 조정, with the days, the date it arises and until when. */
@Composable
private fun AddDialog(kind: EntryKind, onDismiss: () -> Unit, onAdd: (LedgerEntry) -> Unit) {
    val today = remember { LocalDate.now() }
    var days by rememberSaveable { mutableStateOf("") }
    var minus by rememberSaveable { mutableStateOf(false) }
    var date by remember { mutableStateOf(if (kind == EntryKind.ADJUST) today else today.withDayOfYear(1)) }
    var until by remember { mutableStateOf<LocalDate?>(today.withDayOfYear(1).plusYears(1).minusDays(1)) }
    var note by rememberSaveable { mutableStateOf("") }
    val value = days.toNumber()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("일수", days, { days = it }, suffix = "일", decimal = true, maxLength = 5)
                if (kind == EntryKind.ADJUST) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !minus, onClick = { minus = false }, label = { Text("더하기") })
                        FilterChip(selected = minus, onClick = { minus = true }, label = { Text("빼기") })
                    }
                }
                DateField(if (kind == EntryKind.GRANT) "그해 시작일" else "생긴 날", date, { if (it != null) date = it }, clearable = false)
                DateField("쓸 수 있는 마지막 날", until, { until = it })
                OutlinedTextField(note, { note = it.take(60) }, label = { Text("메모 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (kind == EntryKind.GRANT) Hint("앱이 같은 해에 센 연차 대신 이 날수를 써요.")
            }
        },
        confirmButton = {
            TextButton(enabled = value > 0, onClick = {
                onAdd(LedgerEntry(kind = kind, date = date, days = if (minus) -value else value, expires = until?.takeIf { !it.isBefore(date) },
                    yearStart = date.takeIf { kind == EntryKind.GRANT }, note = note.trim()))
            }) { Text("넣기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
