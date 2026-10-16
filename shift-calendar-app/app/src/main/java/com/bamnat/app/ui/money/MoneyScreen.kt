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
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    override fun saveEntry(entry: MoneyEntry) { viewModelScope.launch { money.save(entry) } }
    override fun deleteEntry(entry: MoneyEntry) { viewModelScope.launch { money.delete(entry) } }
    override fun saveJob(job: Job) { viewModelScope.launch { money.save(job) } }
    override fun deleteJob(job: Job) { viewModelScope.launch { money.delete(job) } }
    override fun saveSales(sales: DailySales) { viewModelScope.launch { money.save(sales) } }
}

/** [LEDG-01][WORK-04][TIER-05] The money tab: the month's income, spending and what is left. */
@Composable
fun MoneyScreen(vm: MoneyViewModel, wide: Boolean, onOpenPay: () -> Unit, today: LocalDate = LocalDate.now()) {
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
    val book = bookOrNull ?: MoneyBook.EMPTY
    val style = settings.workStyle
    val pay = remember(style, profile, snapshot, tier, month) { vm.payEstimate(style, profile, snapshot, tier, month) }
    val m = remember(book, month, pay) { book.month(month, pay) }
    val statsOpen = FeatureGate.allows(tier, Feature.MONEY_STATS)
    val prev = remember(book, month, statsOpen, style, profile, snapshot, tier) {
        if (!statsOpen) null else book.month(month.minusMonths(1), vm.payEstimate(style, profile, snapshot, tier, month.minusMonths(1)))
    }
    val trend = remember(book, month, statsOpen) { if (!statsOpen) emptyList() else (5 downTo 0).map { book.month(month.minusMonths(it.toLong())) } }
    val unpaid = remember(book, today) { MoneyCalculator.unpaid(book.jobs, today) }
    val monthEntries = remember(book, month) { book.entries.filter { YearMonth.from(it.date) == month } }

    val summary: @Composable () -> Unit = {
        SummaryCard(m)
        IncomeCard(m, style, hasProfile = profile != null, onOpenPay = onOpenPay)
        if (unpaid.isNotEmpty()) UnpaidCard(unpaid) { editingJob = it }
    }
    val details: @Composable () -> Unit = {
        StatsCard(m, prev, trend, statsOpen)
        RecordsCard(monthEntries) { editing = it }
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
    if (adding) EntryDialog(addDate, null, onSave = { vm.saveEntry(it); adding = false }, onDismiss = { adding = false })
    editing?.let { e ->
        EntryDialog(e.date, e, e.kind, onSave = { vm.saveEntry(it); editing = null },
            onDelete = { vm.deleteEntry(it); editing = null }, onDismiss = { editing = null })
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
private fun StatsCard(m: MonthMoney, prev: MonthMoney?, trend: List<MonthMoney>, open: Boolean) {
    SectionCard(title = "통계") {
        if (!open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("지난 달 비교, 분류별 지출, 월별 흐름은 프로에서 볼 수 있어요. 이번 달 기록과 합계는 무료예요.",
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
private fun RecordsCard(entries: List<MoneyEntry>, onOpen: (MoneyEntry) -> Unit) {
    SectionCard(title = "가계부 기록 ${entries.size}건") {
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
