package com.bamnat.app.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.DayBook
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MemoAmounts
import com.bamnat.core.money.MoneySource
import com.bamnat.core.work.WorkStyle
import java.time.LocalDate

/** What the day sections may change; the calendar's view model implements it. */
interface MoneyActions {
    fun saveEntry(entry: MoneyEntry)
    fun deleteEntry(entry: MoneyEntry)
    fun saveJob(job: Job)
    fun deleteJob(job: Job)
    fun saveSales(sales: DailySales)
    /** Amounts typed again and again for that kind, one tap away in the editor [LEDG-04]. */
    fun frequentAmounts(kind: MoneyKind): List<Long> = emptyList()
    /** Places the user filed under a category; the editor follows them [LEDG-04]. */
    val categoryRules: Map<String, String> get() = emptyMap()
    /** [LEDG-13] The whole book, for the editor's accounts, categories and smart defaults; null reads the active calendar's. */
    val ledger: MoneyBook? get() = null
}

/** [WORK-02] The day's booked jobs, with what is still owed. */
@Composable
fun JobsSection(date: LocalDate, jobs: List<Job>, actions: MoneyActions) {
    var editing by remember { mutableStateOf<Job?>(null) }
    var adding by remember { mutableStateOf(false) }
    SectionCard(
        title = "일감",
        trailing = { TextButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("일감 추가") } },
    ) {
        if (jobs.isEmpty()) {
            Text("잡힌 일이 없어요. 일감을 적으면 금액이 이번 달 수입에 들어가고, 시작 전에 알려 드려요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        jobs.forEachIndexed { i, job ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            JobRow(job) { editing = job }
        }
    }
    if (adding) JobDialog(date, null, onSave = { actions.saveJob(it); adding = false }, onDismiss = { adding = false })
    editing?.let { job ->
        JobDialog(date, job, onSave = { actions.saveJob(it); editing = null },
            onDelete = { actions.deleteJob(it); editing = null }, onDismiss = { editing = null })
    }
}

@Composable
fun JobRow(job: Job, showDate: Boolean = false, onClick: () -> Unit) {
    val cal = LocalCalendarColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = job.start?.let { s -> Fmt.time(s) + (job.end?.let { "–" + Fmt.time(it) } ?: "") }
            val span = if (job.days > 1) "${Fmt.shortDate(job.date)}~${Fmt.shortDate(job.lastDate)} (${job.days}일)" else null
            val line = listOfNotNull(if (showDate && span == null) Fmt.dayTitle(job.date) else null, span, time,
                job.client.ifBlank { null }, job.place.ifBlank { null })
                .joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.won(job.payable), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            when {
                job.amount == 0L -> Unit
                job.unpaid == 0L -> Text("다 받음", style = MaterialTheme.typography.labelSmall, color = cal.income)
                else -> Text("받을 돈 ${Fmt.won(job.unpaid)}", style = MaterialTheme.typography.labelSmall, color = cal.warning)
            }
        }
    }
}

/** [WORK-03] The shop's day: sales, costs and what is left. */
@Composable
fun SalesSection(date: LocalDate, sales: DailySales?, actions: MoneyActions) {
    var editing by remember { mutableStateOf(false) }
    SectionCard(
        title = "매출",
        trailing = { TextButton(onClick = { editing = true }) { Text(if (sales == null) "매출 적기" else "고치기") } },
    ) {
        if (sales == null) {
            Text("이 날 매출과 경비를 적으면 순수익이 그달 수입에 들어가요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Figure("매출", Fmt.won(sales.sales), Modifier.weight(1f))
                Figure("경비", Fmt.won(sales.costs), Modifier.weight(1f))
                Figure("순수익", Fmt.won(sales.net), Modifier.weight(1f), strong = true)
            }
            if (sales.note.isNotBlank()) {
                Text(sales.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    if (editing) SalesDialog(date, sales, onSave = { actions.saveSales(it); editing = false }, onDismiss = { editing = false })
}

/** A label over an amount. [LEDG-15] In a narrow pane the amount shrinks to fit instead of being cut ("1,240,0"). */
@Composable
fun Figure(label: String, value: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val style = (if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge).merge(NumberStyle)
        BasicText(
            value,
            style = style.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize),
        )
    }
}

/** [LEDG-01] The day's ledger lines and a quick way to add one. */
@Composable
fun DayLedgerSection(date: LocalDate, style: WorkStyle?, entries: List<MoneyEntry>, actions: MoneyActions, memoText: String = "") {
    var adding by remember { mutableStateOf<MoneyKind?>(null) }
    var fromMemo by remember { mutableStateOf<MoneyEntry?>(null) }
    val memoAmounts = remember(memoText, entries) { MemoAmounts.pending(memoText, entries) }
    var addingJob by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MoneyEntry?>(null) }
    SectionCard(title = "가계부") {
        entries.forEach { e -> EntryRow(e) { editing = e } }
        // [WORK-05] "+50,000" or "수입 5만원" in the day's memo is offered as a line; nothing is added until saved.
        memoAmounts.forEach { f ->
            SuggestionChip(
                onClick = {
                    fromMemo = MoneyEntry(date = date, kind = f.kind, amount = f.amount,
                        category = if (f.kind == MoneyKind.INCOME) "부수입" else "기타", merchant = f.line, source = MoneySource.MEMO)
                },
                label = {
                    val to = if (f.kind == MoneyKind.INCOME) "수입으로" else "지출로"
                    Text("메모의 ${Fmt.won(f.amount)} $to 적기", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                icon = { Icon(Icons.Filled.Add, null) },
            )
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { adding = MoneyKind.EXPENSE }, label = { Text("지출 적기") },
                leadingIcon = { Icon(Icons.Filled.Add, null) })
            AssistChip(onClick = { adding = MoneyKind.INCOME }, label = { Text("수입 적기") },
                leadingIcon = { Icon(Icons.Filled.Add, null) })
            // [WORK-02] A shift worker's weekend 부업 is a job too.
            if (style?.usesJobs != true) {
                AssistChip(onClick = { addingJob = true }, label = { Text("부업 일감") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
            }
        }
        if (entries.isEmpty() && style?.usesPayEngine == true) {
            Text("급여일에 실제 받은 급여를 수입(급여)으로 적으면 예상 대신 그 금액으로 계산해요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (addingJob) JobDialog(date, null, onSave = { actions.saveJob(it); addingJob = false }, onDismiss = { addingJob = false })
    adding?.let { k ->
        EntryDialog(date, null, k, onSave = { actions.saveEntry(it); adding = null }, onDismiss = { adding = null },
            frequent = actions::frequentAmounts, rules = actions.categoryRules, book = actions.ledger)
    }
    fromMemo?.let { e ->
        EntryDialog(date, e, e.kind, onSave = { actions.saveEntry(it); fromMemo = null }, onDismiss = { fromMemo = null },
            frequent = actions::frequentAmounts, rules = actions.categoryRules, book = actions.ledger)
    }
    editing?.let { e ->
        EntryDialog(date, e, e.kind, onSave = { actions.saveEntry(it); editing = null },
            onDelete = { actions.deleteEntry(it); editing = null }, onDismiss = { editing = null },
            frequent = actions::frequentAmounts, rules = actions.categoryRules, book = actions.ledger)
    }
}

/**
 * One ledger line: the category's badge, what and where, and the signed amount [LEDG-01].
 * [LEDG-11] A split line, installments, tags and a memo show in the second line.
 */
@Composable
fun EntryRow(e: MoneyEntry, showDate: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (e.kind == MoneyKind.TRANSFER) IconAvatar(Icons.Filled.SwapHoriz, MaterialTheme.colorScheme.onSurfaceVariant, size = 36.dp)
        else CategoryAvatar(e.category, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val source = when (e.source) {
                MoneySource.NOTIFICATION -> "알림에서"
                MoneySource.PASTE -> "문자에서"
                MoneySource.MEMO -> "메모에서"
                MoneySource.FIXED -> "반복"
                MoneySource.MANUAL -> null
            }
            Text(
                listOfNotNull(
                    if (showDate) Fmt.shortDate(e.date) else null, e.time?.let(Fmt::time),
                    if (e.isSplit) "${e.shares().size}개 분류" else e.category,
                    if (e.kind == MoneyKind.TRANSFER) "이체" else null,
                    if (e.installments > 1) "${e.installments}개월 할부" else null, source,
                    e.note.takeIf { it.isNotBlank() && e.merchant.isNotBlank() },
                    e.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(signedWon(e), style = MaterialTheme.typography.titleSmall.merge(NumberStyle), fontWeight = FontWeight.Bold, color = amountColor(e))
    }
}

/** Sections of a calendar day for the user's kind of work [WORK-01]. */
@Composable
fun DayMoneySections(date: LocalDate, style: WorkStyle?, day: DayBook, actions: MoneyActions, memoText: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (style?.usesJobs == true || day.jobs.isNotEmpty()) JobsSection(date, day.jobs, actions)
        if (style?.usesSales == true || day.sales != null) SalesSection(date, day.sales, actions)
        DayLedgerSection(date, style, day.entries, actions, memoText)
    }
}
