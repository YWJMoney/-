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
import androidx.compose.ui.platform.LocalContext
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
import com.bamnat.app.ui.components.AddButton
import com.bamnat.app.ui.components.FilterChip
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.money.LedgerExport
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
import com.bamnat.core.leave.LeaveExport
import com.bamnat.core.leave.LeavePlanner
import com.bamnat.core.leave.LotKind
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

    val promotion: StateFlow<List<LeavePromotion.Record>> = repo.promotion.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addNotice(r: LeavePromotion.Record) = viewModelScope.launch {
        val id = repo.addNotice(r)
        _messages.emit(UndoMessage("${Fmt.shortDate(r.date)} 기록을 넣었어요") { repo.deleteNotice(id) })
    }

    fun deleteNotice(r: LeavePromotion.Record) = viewModelScope.launch {
        repo.deleteNotice(r.id)
        _messages.emit(UndoMessage("기록을 뺐어요") { repo.addNotice(r) })
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
    val promotion by vm.promotion.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
            if (s != null) item { PromotionCard(s, promotion, vm) }
            if (s != null) item { LotsCard(s) }
            item {
                SectionCard(title = "직접 넣기") {
                    Hint("연차·반차는 달력에서 날을 길게 눌러 넣어요. 여기에는 달력에 없는 것만 넣어요.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(EntryKind.GRANT, EntryKind.CARRY, EntryKind.ADJUST).forEach { k ->
                            AddButton(k.label, onClick = { adding = k })
                        }
                    }
                    Hint("받은 연차: 회사가 실제로 준 날이 앱이 센 날과 다를 때, 그해 몫을 바꿔요. 이월: 작년에서 넘어온 날. 조정: 더하거나 빼요(빼려면 −).")
                }
            }
            if (s != null && s.lines.isNotEmpty()) item {
                LinesCard(s, allLines, onAll = { allLines = true },
                    onExport = { ui.launch { LedgerExport.share(context, LeaveExport.csv(s), LeaveExport.fileName(s.today)) } },
                    onPdf = { ui.launch { com.bamnat.app.report.ReportShare.pdf(context, LeaveExport.doc(s), LeaveExport.pdfName(s.today)) } },
                ) { id -> ui.launch { opened = vm.entry(id) } }
            }
            item { PayoutCard(s, profile, gate, vm, promotion, snapshot) }
            if (s != null) snapshot?.let { snap -> item { PlanCard(s, snap, style, profile) } }
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
        if (!profile.fiveOrMoreEmployees) {
            Text("5인 미만 사업장은 법으로 정한 연차가 없어요(근로기준법 시행령 제7조 별표 1). 회사가 주는 날이 있으면 ‘받은 연차’로 넣어 주세요.",
                style = MaterialTheme.typography.bodyMedium, color = LocalCalendarColors.current.warning)
        }
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
private fun LinesCard(s: LeaveSummary, all: Boolean, onAll: () -> Unit, onExport: () -> Unit, onPdf: () -> Unit, onOpen: (Long) -> Unit) {
    // Newest first; lines far ahead (lapses a year out) after the planned ones.
    val lines = s.lines.asReversed()
    val shown = if (all) lines else lines.filter { !it.date.isBefore(s.today.minusMonths(6)) && !it.date.isAfter(s.today.plusMonths(6)) }.take(20)
    SectionCard(title = "연차 원장", trailing = {
        Row { TextButton(onClick = onPdf) { Text("PDF") }; TextButton(onClick = onExport) { Text("CSV") } }
    }) {
        shown.forEachIndexed { i, l ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp))
            LineRow(l, onOpen)
        }
        if (!all && shown.size < lines.size) TextButton(onClick = onAll) { Text("모두 보기 (${lines.size}줄)") }
        Hint("PDF는 인쇄하거나 보여 주기 좋고, CSV는 모든 줄을 표 파일로 만들어 회사나 노무사에게 보낼 수 있게 해요. 파일은 휴대폰 밖으로 저절로 나가지 않아요.")
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
private fun PayoutCard(
    s: LeaveSummary?, profile: PayProfile?, gate: Gate, vm: LeaveViewModel,
    records: List<LeavePromotion.Record>, snap: ScheduleSnapshot?,
) {
    if (s == null || profile == null) return
    val lapse = s.nextLapse
    val days = lapse?.second ?: s.afterPlanned.coerceAtLeast(0.0)
    val month = YearMonth.from(lapse?.first ?: s.today)
    // [LEAVE-09] A 단시간 근로자's day is the roster's usual day, not 8시간.
    val dayMinutes = remember(snap, month) { snap?.let { LeavePayout.scheduledDay(it.resolver::resolve, month) } }
    val estimate = remember(days, profile, month, dayMinutes) { LeavePayout.estimate(days, profile, month, dayMinutes) }
    val check = lapse?.let { (date, _) -> LeavePromotion.check(date, firstYear = isFirstYear(s, date), records = records) }
    // [LEAVE-08] 회계연도 기준 on leaving: the days the hire date would have given more are paid too.
    val shortfall = remember(profile) {
        val hire = profile.hireDate
        val leaving = profile.leaveDate
        if (hire != null && leaving != null) LeaveAccrual.fiscalShortfall(hire, profile.rules.leave, leaving) else 0.0
    }
    ProLock(Feature.LEAVE_PAYOUT, gate) {
        SectionCard(title = "못 쓴 연차, 돈으로는") {
            ValueLine(lapse?.let { "${Fmt.shortDate(it.first)}에 없어질 ${LeaveAccrual.days(days)}" } ?: "남은 ${LeaveAccrual.days(days)}",
                "약 ${Fmt.won(estimate.amount)}", strong = true)
            Hint("${estimate.formula}로 셌어요. 회사가 쓰라고 제대로 알렸는데도(근로기준법 제61조) 안 쓴 날은 돈으로 받지 못할 수 있어요. " +
                "받는 때는 연차를 쓸 수 있는 기간이 끝난 뒤 첫 월급날이에요(시행령 제33조).")
            if (dayMinutes != null && dayMinutes < 8 * 60) Hint("근무표의 하루가 ${dayMinutes / 60.0}시간이라 1일 통상임금도 그만큼으로 셌어요(단시간 근로자).")
            check?.let {
                Text(it.text, style = MaterialTheme.typography.bodySmall,
                    color = if (it.done) MaterialTheme.colorScheme.onSurfaceVariant else LocalCalendarColors.current.warning)
            }
            if (shortfall > 1e-9) {
                val more = LeavePayout.estimate(shortfall, profile, YearMonth.from(profile.leaveDate ?: s.today), dayMinutes)
                ValueLine("회계연도 기준이라 덜 받은 ${LeaveAccrual.days(shortfall)}", "약 ${Fmt.won(more.amount)}")
                Hint("그만둘 때 입사일 기준으로 다시 세어 더 많으면 그 차이를 연차수당으로 받아요(근로기준과-5802, 2009.12.31).")
            }
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

/** Whether the lapse on [date] is the 1년 미만 월 연차 (제61조 제2항, 3개월·1개월 전). */
private fun isFirstYear(s: LeaveSummary, date: LocalDate): Boolean =
    s.lots.any { it.lot.kind == LotKind.MONTHLY && it.lot.expires == date }

/**
 * [LEAVE-11] 사용 촉진 기록: what the company did (and the worker answered) before the next lapse, step by step with the
 * date, so the 미사용 수당 card can say whether the 촉진 counts (제61조).
 */
@Composable
private fun PromotionCard(s: LeaveSummary, records: List<LeavePromotion.Record>, vm: LeaveViewModel) {
    val (lapse, _) = s.nextLapse ?: return
    var step by remember { mutableStateOf<LeavePromotion.Step?>(null) }
    val mine = records.filter { it.lapse == lapse }
    val check = LeavePromotion.check(lapse, isFirstYear(s, lapse), records)
    SectionCard(title = "회사의 사용 촉진 기록") {
        Text("${Fmt.fullDate(lapse)}에 없어지는 연차", style = MaterialTheme.typography.bodyLarge)
        mine.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "빼기") { vm.deleteNotice(r) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.step.label, style = MaterialTheme.typography.bodyMedium)
                    if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Fmt.shortDate(r.date), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
            }
        }
        LeavePromotion.Step.entries.filter { st -> mine.none { it.step == st } }.forEach { st ->
            AddButton(st.label.substringBefore(" ("), onClick = { step = st })
        }
        Text(check.text, style = MaterialTheme.typography.bodySmall,
            color = if (check.done) MaterialTheme.colorScheme.onSurfaceVariant else LocalCalendarColors.current.warning)
        Hint("회사가 보낸 문서·메일의 날짜를 적어 두면, 나중에 연차수당을 받을 수 있는지 따질 때 도움이 돼요. 기록은 이 휴대폰에만 있어요.")
    }
    step?.let { st ->
        var date by remember { mutableStateOf(s.today) }
        var note by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { step = null },
            title = { Text(st.label.substringBefore(" (")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Hint(st.label)
                    DateField("날짜", date, { if (it != null) date = it }, clearable = false)
                    OutlinedTextField(note, { note = it.take(60) }, label = { Text("메모 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.addNotice(LeavePromotion.Record(lapse = lapse, step = st, date = date, note = note.trim())); step = null }) { Text("넣기") }
            },
            dismissButton = { TextButton(onClick = { step = null }) { Text("취소") } },
        )
    }
}

/** [LEAVE-13] 없어질 연차를 어디에 쓰면 가장 길게 쉬는지: the planner's picks before the lapse. */
@Composable
private fun PlanCard(s: LeaveSummary, snap: ScheduleSnapshot, style: WorkStyle?, profile: PayProfile?) {
    val plan = remember(s, snap, style) { LeavePlanner.plan(s, LeaveBridges.of(snap, style, s.today)) } ?: return
    SectionCard(title = "없어지기 전에 쓰는 법") {
        Text("${Fmt.fullDate(plan.lapse)}에 없어질 연차 ${LeaveAccrual.days(plan.lapsing)}", style = MaterialTheme.typography.bodyLarge)
        if (plan.picks.isEmpty()) {
            Hint("그 전에 공휴일·쉬는 날과 이어지는 날이 없어요. 원하는 날에 나눠 쓰면 돼요.")
        } else {
            plan.picks.forEach { b ->
                ValueLine("${Fmt.shortDate(b.from)}~${Fmt.shortDate(b.to)} · 연차 ${b.leave.size}일", b.text)
            }
            ValueLine("연차 ${plan.days}일로 쉬는 날", "${plan.restDays}일", strong = true)
            if (plan.leftOver > 1e-9) Hint("나머지 ${LeaveAccrual.days(plan.leftOver)}: 원하는 날에 쓰면 돼요.")
        }
        Hint(LeavePlanner.payNote(profile != null && profile.wageType != com.bamnat.core.pay.WageType.MONTHLY))
    }
}
