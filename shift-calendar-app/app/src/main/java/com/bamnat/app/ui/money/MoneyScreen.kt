package com.bamnat.app.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.FeatureGate
import com.bamnat.app.tier.Tier
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyCalculator
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MonthMoney
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneyStats
import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.Upcoming
import com.bamnat.core.money.UpcomingPayments
import com.bamnat.app.data.Capture
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.bamnat.app.ui.UndoMessage
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

class MoneyViewModel(private val container: AppContainer) : ViewModel(), MoneyActions {
    private val money = container.moneyRepository
    val book: StateFlow<MoneyBook?> = money.book.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val snapshot: StateFlow<ScheduleSnapshot?> =
        container.scheduleRepository.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val payProfile: StateFlow<PayProfile?> = container.payStore.profile
    val tier: StateFlow<Tier> = container.featureGate.tier
    private val payCalculator = PayCalculator()

    /** [WORK-04] Wages come from the existing pay engine; null when it is not set up or not unlocked. */
    fun payEstimate(style: WorkStyle?, profile: PayProfile?, snapshot: ScheduleSnapshot?, tier: Tier, month: YearMonth): Long? {
        if (style?.usesPayEngine == false || profile == null || snapshot == null) return null
        if (!FeatureGate.allows(tier, Feature.PAY_ENGINE)) return null
        return runCatching { payCalculator.calculate(profile, month, snapshot.resolver::resolve).total }.getOrNull()
    }

    /** The month, the one before and six months of totals, each with that month's wage estimate. */
    data class Months(val current: MonthMoney, val previous: MonthMoney?, val trend: List<MonthMoney>)

    suspend fun months(book: MoneyBook, style: WorkStyle?, profile: PayProfile?, snapshot: ScheduleSnapshot?, tier: Tier,
                       month: YearMonth, stats: Boolean): Months = withContext(Dispatchers.Default) {
        // The pay engine walks every day of a month; six months of it stay off the main thread.
        val pays = HashMap<YearMonth, Long?>()
        val at = { m: YearMonth -> book.month(m, pays.getOrPut(m) { payEstimate(style, profile, snapshot, tier, m) }) }
        val trend = if (stats) (5 downTo 0).map { at(month.minusMonths(it.toLong())) } else emptyList()
        Months(at(month), if (stats) at(month.minusMonths(1)) else null, trend)
    }

    private val _undo = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val undo: SharedFlow<UndoMessage> = _undo.asSharedFlow()

    override fun saveEntry(entry: MoneyEntry) { container.appScope.launch { money.save(entry) } }
    override fun deleteEntry(entry: MoneyEntry) {
        container.appScope.launch {
            money.delete(entry)
            _undo.tryEmit(UndoMessage("‘${entry.title}’ 기록을 지웠어요") { money.save(entry) })
        }
    }
    override fun saveJob(job: Job) { container.appScope.launch { money.save(job) } }
    override fun deleteJob(job: Job) {
        container.appScope.launch {
            money.delete(job)
            _undo.tryEmit(UndoMessage("‘${job.title}’ 일감을 지웠어요") { money.save(job) })
        }
    }
    val rules: StateFlow<Map<String, String>> = money.rules.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val captures = money.captures.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    override val categoryRules: Map<String, String> get() = rules.value
    override fun frequentAmounts(kind: MoneyKind): List<Long> =
        MoneyStats.frequentAmounts(book.value?.entries.orEmpty(), kind, LocalDate.now())

    fun saveAll(entries: List<MoneyEntry>) {
        container.appScope.launch {
            val saved = entries.map { money.save(it) }
            _undo.tryEmit(UndoMessage("문자 ${saved.size}건을 적었어요") { saved.forEach { money.delete(it) } })
        }
    }
    fun markAllSeen() { container.appScope.launch { money.markAllReviewed() } }
    fun finishCapture(capture: Capture, entry: MoneyEntry) {
        container.appScope.launch { money.save(entry); money.dismissCapture(capture.id) }
    }
    fun dropCapture(capture: Capture) { container.appScope.launch { money.dismissCapture(capture.id) } }
    fun saveFixed(cost: FixedCost) { container.appScope.launch { money.save(cost) } }
    fun deleteFixed(cost: FixedCost) {
        container.appScope.launch {
            money.delete(cost)
            _undo.tryEmit(UndoMessage("‘${cost.name}’ 고정 지출을 지웠어요") { money.save(cost) })
        }
    }
    fun recordFixed(u: Upcoming, today: LocalDate) {
        container.appScope.launch {
            // Paid early is still this month's payment; it is dated today, or the due day when that has passed.
            val saved = money.save(UpcomingPayments.entry(u.cost, if (u.date.isBefore(today)) u.date else today))
            _undo.tryEmit(UndoMessage("‘${u.cost.name}’ ${Fmt.won(u.cost.amount)}을 적었어요") { money.delete(saved) })
        }
    }

    fun runUndo(message: UndoMessage) { message.undo?.let { u -> container.appScope.launch { u() } } }
    override fun saveSales(sales: DailySales) { container.appScope.launch { money.save(sales) } }
}

/** [LEDG-01][WORK-04][TIER-05] The money tab: the month's income, spending and what is left. */
@Composable
fun MoneyScreen(
    vm: MoneyViewModel,
    wide: Boolean,
    onOpenPay: () -> Unit,
    snackbar: SnackbarHostState? = null,
    today: LocalDate = LocalDate.now(),
) {
    val bookOrNull by vm.book.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val profile by vm.payProfile.collectAsStateWithLifecycle()
    val tier by vm.tier.collectAsStateWithLifecycle()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val month = remember(monthText) { YearMonth.parse(monthText) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MoneyEntry?>(null) }
    var editingJob by remember { mutableStateOf<Job?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf<Capture?>(null) }
    var editingFixed by remember { mutableStateOf<FixedCost?>(null) }
    var addingFixed by remember { mutableStateOf(false) }
    var place by rememberSaveable { mutableStateOf<String?>(null) }
    val rules by vm.rules.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    val book = bookOrNull ?: MoneyBook.EMPTY
    val style = settings.workStyle
    val statsOpen = FeatureGate.allows(tier, Feature.MONEY_STATS)
    // Records show at once; the wage estimate joins when the pay engine has run.
    val months by produceState(MoneyViewModel.Months(book.month(month), null, emptyList()), book, style, profile, snapshot, tier, month, statsOpen) {
        value = vm.months(book, style, profile, snapshot, tier, month, statsOpen)
    }
    val m = months.current
    val prev = months.previous
    val trend = months.trend
    LaunchedEffect(snackbar) {
        val host = snackbar ?: return@LaunchedEffect
        vm.undo.collect { msg ->
            if (host.showSnackbar(msg.message, actionLabel = "되돌리기", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                vm.runUndo(msg)
            }
        }
    }
    val unpaid = remember(book, today) { MoneyCalculator.unpaid(book.jobs, today) }
    val monthEntries = remember(book, month) { book.entries.filter { YearMonth.from(it.date) == month } }
    val shownEntries = remember(monthEntries, place) {
        place?.let { p -> monthEntries.filter { it.merchant.ifBlank { it.note }.trim() == p } } ?: monthEntries
    }
    val unseen = remember(book) { book.entries.filter { !it.reviewed } }
    val upcoming = remember(book, today) { UpcomingPayments.within(book.fixed, book.entries, today) }
    val leftFixed = remember(book, today) { UpcomingPayments.leftThisMonth(book.fixed, book.entries, today) }
    val places = remember(book, month, statsOpen) { if (statsOpen) MoneyStats.places(book.entries, month) else emptyList() }

    val summary: @Composable () -> Unit = {
        NewRecordsCard(unseen, captures, onOpen = { editing = it }, onAllSeen = vm::markAllSeen,
            onFinish = { finishing = it }, onDrop = vm::dropCapture)
        SummaryCard(m)
        UpcomingCard(upcoming, book.fixed, leftFixed, onRecord = { vm.recordFixed(it, today) },
            onEdit = { c -> if (c == null) addingFixed = true else editingFixed = c })
        IncomeCard(m, style, hasProfile = profile != null, onOpenPay = onOpenPay)
        if (unpaid.isNotEmpty()) UnpaidCard(unpaid) { editingJob = it }
        // [LEDG-02] Next to the totals, so typing less is found before scrolling through records.
        AutoRecordCard(onPaste = { pasting = true })
    }
    val details: @Composable () -> Unit = {
        StatsCard(m, prev, trend, statsOpen) { PlacesSection(places, place) { place = it } }
        RecordsCard(shownEntries, place, onClearPlace = { place = null }) { editing = it }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("돈 관리", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달")
                }
                Text(Fmt.month(month), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { monthText = month.plusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달")
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) { summary(); Spacer(Modifier.height(80.dp)) }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) { details(); Spacer(Modifier.height(80.dp)) }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { summary(); details(); Spacer(Modifier.height(80.dp)) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("돈 적기") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    val addDate = if (YearMonth.from(today) == month) today else month.atEndOfMonth()
    if (adding) {
        EntryDialog(addDate, null, onSave = { vm.saveEntry(it); adding = false }, onDismiss = { adding = false },
            frequent = vm::frequentAmounts, rules = rules)
    }
    editing?.let { e ->
        EntryDialog(e.date, e, e.kind, onSave = { vm.saveEntry(it); editing = null },
            onDelete = { vm.deleteEntry(it); editing = null }, onDismiss = { editing = null },
            frequent = vm::frequentAmounts, rules = rules)
    }
    finishing?.let { c ->
        val draft = remember(c) { c.draft(today) }
        EntryDialog(draft.date, draft, draft.kind, onSave = { vm.finishCapture(c, it); finishing = null },
            onDismiss = { finishing = null }, frequent = vm::frequentAmounts, rules = rules)
    }
    if (pasting) PasteDialog(book.entries, rules, onSave = { vm.saveAll(it); pasting = false }, onDismiss = { pasting = false }, today = today)
    if (addingFixed || editingFixed != null) {
        FixedCostDialog(editingFixed, onSave = { vm.saveFixed(it); addingFixed = false; editingFixed = null },
            onDelete = { vm.deleteFixed(it); editingFixed = null },
            onDismiss = { addingFixed = false; editingFixed = null })
    }
    editingJob?.let { j ->
        JobDialog(j.date, j, onSave = { vm.saveJob(it); editingJob = null },
            onDelete = { vm.deleteJob(it); editingJob = null }, onDismiss = { editingJob = null })
    }
}

@Composable
private fun SummaryCard(m: MonthMoney) {
    val cal = LocalCalendarColors.current
    SectionCard(title = "${m.month.monthValue}월 남은 돈") {
        Text(Fmt.won(m.balance), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
            color = if (m.balance < 0) cal.expense else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("들어온 돈", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("+" + Fmt.won(m.income), style = MaterialTheme.typography.titleMedium, color = cal.income, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("나간 돈", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("-" + Fmt.won(m.expenses), style = MaterialTheme.typography.titleMedium, color = cal.expense, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun IncomeCard(m: MonthMoney, style: WorkStyle?, hasProfile: Boolean, onOpenPay: () -> Unit) {
    SectionCard(title = "수입 내역") {
        if (style?.usesPayEngine != false) {
            val label = when {
                m.payRecorded > 0 -> "급여 (받은 금액)"
                m.payEstimate != null -> "급여 (예상)"
                else -> "급여"
            }
            if (m.payRecorded > 0 || m.payEstimate != null) {
                Line(label, Fmt.won(m.payCounted))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (hasProfile) "급여 계산을 열면 예상 급여가 들어가요" else "시급·월급을 정하면 근무표로 급여를 계산해요",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenPay) { Text(if (hasProfile) "열기" else "정하기") }
                }
            }
        }
        if (style?.usesJobs == true || m.jobCount > 0) {
            Line("일감 ${m.jobCount}건", Fmt.won(m.jobEarned))
            if (m.jobWithheld > 0) Line("  3.3% 세금 뗀 뒤 금액 (뗀 세금 ${Fmt.won(m.jobWithheld)})", "", muted = true)
            if (m.jobCosts > 0) Line("  자재비·경비", "-" + Fmt.won(m.jobCosts), muted = true)
            if (m.jobEarned > 0) Line("  그중 받은 돈", Fmt.won(m.jobReceived), muted = true)
        }
        if (style?.usesSales == true || m.salesDays > 0) {
            Line("가게 순수익 (${m.salesDays}일)", Fmt.won(m.salesNet))
            if (m.sales > 0) Line("  매출 ${Fmt.won(m.sales)} · 경비 ${Fmt.won(m.salesCosts)}", "", muted = true)
        }
        if (m.otherIncome > 0) Line("기타 수입", Fmt.won(m.otherIncome))
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Line("합계", Fmt.won(m.income), strong = true)
    }
}

@Composable
private fun Line(label: String, value: String, muted: Boolean = false, strong: Boolean = false) {
    val color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = if (muted) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge, color = color,
            modifier = Modifier.weight(1f))
        Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, color = color,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
    }
}

/** [WORK-02] Work done but not fully paid; the real worry of field work. */
@Composable
private fun UnpaidCard(jobs: List<Job>, onOpen: (Job) -> Unit) {
    SectionCard(title = "받을 돈 ${Fmt.won(jobs.sumOf { it.unpaid })}") {
        jobs.take(8).forEachIndexed { i, j ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            JobRow(j, showDate = true) { onOpen(j) }
        }
        if (jobs.size > 8) {
            Text("그 밖에 ${jobs.size - 8}건", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [TIER-05] Comparisons and breakdowns; open to everyone until billing ships. */
@Composable
private fun StatsCard(m: MonthMoney, prev: MonthMoney?, trend: List<MonthMoney>, open: Boolean, extra: @Composable () -> Unit = {}) {
    SectionCard(title = "통계") {
        if (!open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("지난 달 비교, 분류별 지출, 자주 쓴 곳, 월별 흐름은 프로에서 볼 수 있어요. 기록과 합계는 지난 달도 모두 무료예요.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            return@SectionCard
        }
        if (prev != null && prev.expenses > 0) {
            val diff = m.expenses - prev.expenses
            val pct = (abs(diff) * 100.0 / prev.expenses).roundToInt()
            Text(
                when {
                    diff > 0 -> "지난 달보다 ${Fmt.won(diff)} (${pct}%) 더 썼어요"
                    diff < 0 -> "지난 달보다 ${Fmt.won(-diff)} (${pct}%) 덜 썼어요"
                    else -> "지난 달과 똑같이 썼어요"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (m.expenseByCategory.isEmpty()) {
            Text("이번 달 지출 기록이 아직 없어요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("분류별 지출", style = MaterialTheme.typography.labelLarge)
            val max = m.expenseByCategory.first().amount.coerceAtLeast(1)
            m.expenseByCategory.take(8).forEach { c ->
                Bar("${c.category} · ${c.count}건", Fmt.won(c.amount), c.amount.toFloat() / max, LocalCalendarColors.current.expense)
            }
        }
        extra()
        // Months before the first record are left out rather than shown as empty rows.
        val shown = trend.dropWhile { it.income == 0L && it.expenses == 0L }
        if (shown.size > 1) {
            Spacer(Modifier.height(12.dp))
            Text("월별 흐름", style = MaterialTheme.typography.labelLarge)
            val max = shown.maxOf { maxOf(it.income, it.expenses) }.coerceAtLeast(1)
            shown.forEach { t -> TrendRow(t, max) }
        }
    }
}

@Composable
private fun TrendRow(t: MonthMoney, max: Long) {
    val cal = LocalCalendarColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Fmt.monthShort(t.month), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Track(t.income.toFloat() / max, cal.income)
            Track(t.expenses.toFloat() / max, cal.expense)
        }
        Column(Modifier.padding(start = 10.dp), horizontalAlignment = Alignment.End) {
            Text("+" + Fmt.wonShort(t.income), style = MaterialTheme.typography.labelSmall, color = cal.income)
            Text("-" + Fmt.wonShort(t.expenses), style = MaterialTheme.typography.labelSmall, color = cal.expense)
        }
    }
}

@Composable
private fun Track(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
private fun Bar(label: String, value: String, fraction: Float, color: Color) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
        Track(fraction, color)
    }
}

@Composable
private fun RecordsCard(entries: List<MoneyEntry>, place: String?, onClearPlace: () -> Unit, onOpen: (MoneyEntry) -> Unit) {
    SectionCard(title = "가계부 기록 ${entries.size}건") {
        if (place != null) {
            androidx.compose.material3.InputChip(selected = true, onClick = onClearPlace, label = { Text("$place 만 보기") },
                trailingIcon = { Icon(Icons.Filled.Close, "모두 보기") })
        }
        if (entries.isEmpty()) {
            Text("아래 ‘돈 적기’나 달력의 날짜에서 지출과 수입을 적어요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        var lastDate: LocalDate? = null
        entries.forEach { e ->
            if (e.date != lastDate) {
                lastDate = e.date
                Text(Fmt.dayTitle(e.date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp))
            }
            EntryRow(e) { onOpen(e) }
        }
    }
}
