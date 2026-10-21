package com.bamnat.app.ui.stats

import com.bamnat.app.ui.guide.FeatureCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.share.StatementImage
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.money.AmountField
import com.bamnat.app.ui.money.amountDigits
import com.bamnat.app.ui.money.toAmount
import com.bamnat.app.ui.money.wonBrief
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.money.DayRate
import com.bamnat.core.money.FieldYear
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobStatement
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.work.SalesSplit
import com.bamnat.core.work.SalesTarget
import com.bamnat.core.work.ShopMoney
import com.bamnat.core.work.VatEstimate
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * [FLD-02][FLD-07][FLD-10] Field work's extra cards on 일감·수입: the month's 공수 and what they
 * come to, 출역일 this month and this year with the 퇴직공제 count, and the year's earnings, 3.3%
 * held back and costs for the 5월 종합소득세 신고.
 */
@Composable
fun FieldStatsCards(book: MoneyBook, month: YearMonth, today: LocalDate = LocalDate.now(), calendarId: String = com.bamnat.app.data.PersonalCalendar.MAIN) {
    val units = remember(book, month) { DayRate.month(month, book.jobs) }
    if (units.days > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("${month.monthValue}월 공수", DayRate.label(units.units), Modifier.weight(1f), caption = "${units.days}일")
            StatTile("일당 합계", Fmt.won(units.amount), Modifier.weight(1f),
                caption = if (units.units > 0) "1공 평균 ${wonBrief((units.amount / units.units).toLong())}" else null)
        }
    }
    val year = month.year
    val monthDays = remember(book, month) { FieldYear.workedDays(book.jobs, month.atDay(1), month.atEndOfMonth()) }
    val yearEnd = if (year == today.year) today else LocalDate.of(year, 12, 31)
    val yearDays = remember(book, year, yearEnd) { FieldYear.workedDays(book.jobs, LocalDate.of(year, 1, 1), yearEnd) }
    // [GUIDE-05] 출역일 and the year's totals go off together, for this calendar.
    FeatureCard("field.year", calendarId) { FieldYearCards(book, month, today, monthDays, yearDays) }
}

@Composable
private fun FieldYearCards(book: MoneyBook, month: YearMonth, today: LocalDate, monthDays: Int, yearDays: Int) {
    val year = month.year
    SectionCard(title = "출역일") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("${month.monthValue}월", "${monthDays}일", Modifier.weight(1f))
            StatTile("${year}년", "${yearDays}일", Modifier.weight(1f), caption = if (year == today.year) "오늘까지" else null)
        }
        Text(
            "퇴직공제 대상일 ${yearDays}일 (참고용) · 건설근로자 퇴직공제는 공제 가입 현장에서 일한 날만큼 쌓이고, 252일이 넘으면 받을 수 있어요. 정확한 날 수는 건설근로자공제회에서 확인해요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    val totals = remember(book, year) { FieldYear.totals(year, book.jobs, book.entries) }
    if (totals.jobs > 0 || totals.expenses > 0) {
        SectionCard(title = "${year}년 합계") {
            YearLine("번 돈 (3.3% 떼고)", Fmt.won(totals.earned))
            YearLine("떼인 3.3%", Fmt.won(totals.withheld))
            YearLine("경비", Fmt.won(totals.expenses))
            Text("5월 종합소득세 신고 때 쓰세요. 떼인 3.3%는 미리 낸 세금이라, 경비를 챙기면 돌려받을 수 있어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun YearLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
    }
}

/**
 * [FLD-05] 거래내역서 보내기: one client's month, shown as it will be sent, with the sender's name
 * to put on it, then shared as an image.
 */
@Composable
fun StatementDialog(client: String, month: YearMonth, jobs: List<Job>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val statement = remember(client, month, jobs) { JobStatement.of(client, month, jobs) }
    var sender by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("거래내역서 보내기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${month.year}년 ${month.monthValue}월 · ${statement.client}", style = MaterialTheme.typography.titleSmall)
                statement.lines.take(6).forEach { l ->
                    Row {
                        Text("${Fmt.shortDate(l.date)} ${l.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(Fmt.won(l.amount), style = MaterialTheme.typography.bodySmall.merge(NumberStyle))
                    }
                }
                if (statement.lines.size > 6) Text("그 밖에 ${statement.lines.size - 6}건", style = MaterialTheme.typography.bodySmall)
                Text("합계 ${Fmt.won(statement.amount)}" + (if (statement.withheld > 0) " · 3.3% ${Fmt.won(statement.withheld)}" else "") +
                    " · 남은 돈 ${Fmt.won(statement.unpaid)}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = sender, onValueChange = { sender = it.take(30) }, singleLine = true,
                    label = { Text("보내는 사람 (선택)") }, modifier = Modifier.fillMaxWidth())
                Text("사진으로 만들어 메신저로 보낼 수 있어요. 이 휴대폰에서만 만들어져요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = statement.lines.isNotEmpty(), onClick = {
                scope.launch { runCatching { StatementImage.share(context, statement, sender.trim()) } }
                onDismiss()
            }) { Text("보내기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * [SHOP-01][SHOP-04][SHOP-05] A shop's extra cards on 매출·영업: the month's sales by how they were
 * paid, the month's target and what each open day left must sell, and a rough 부가세 for the half year.
 */
@Composable
fun ShopStatsCards(calendarId: String, book: MoneyBook, month: YearMonth, isOpen: (LocalDate) -> Boolean, today: LocalDate = LocalDate.now()) {
    val c = LocalContext.current.appContainer
    val calendars by c.calendars.calendars.collectAsStateWithLifecycle()
    val rules = calendars.firstOrNull { it.id == calendarId }?.shopMoney ?: ShopMoney()
    val split = remember(book, month) { SalesSplit.of(month, book.sales) }
    // [GUIDE-05] Each card can be turned off for its calendar.
    if (split.total > split.unsplit) FeatureCard("shop.split", calendarId) {
        SectionCard(title = "결제 수단별") {
            split.parts.forEach { (name, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${v * 100 / split.total}% · ", style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.won(v), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
                }
            }
        }
    }
    FeatureCard("shop.target", calendarId) {
        TargetCard(rules, book, month, today, isOpen) { t -> c.calendars.updateShopMoney(calendarId) { it.copy(monthlyTarget = t) } }
    }
    FeatureCard("shop.vat", calendarId) {
        VatCard(rules, book, if (YearMonth.from(today) == month) today else month.atDay(1)) { simple ->
            c.calendars.updateShopMoney(calendarId) { it.copy(simpleTax = simple) }
        }
    }
}

/** [SHOP-05] 목표 매출: achieved %, what is left and the daily target over the open days left. */
@Composable
private fun TargetCard(rules: ShopMoney, book: MoneyBook, month: YearMonth, today: LocalDate, isOpen: (LocalDate) -> Boolean, onTarget: (Long) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val progress = remember(rules.monthlyTarget, book.sales, month, today) { SalesTarget.of(rules.monthlyTarget, month, book.sales, today, isOpen) }
    SectionCard(title = "목표 매출", trailing = { TextButton(onClick = { editing = true }) { Text(if (rules.monthlyTarget > 0) "바꾸기" else "정하기") } }) {
        if (progress == null) {
            Text("한 달 목표를 정하면 남은 영업일에 하루 얼마를 팔면 되는지 알려 드려요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("${Fmt.won(progress.achieved)} / ${Fmt.won(progress.target)} · ${progress.percent}%", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { (progress.percent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text(
                when {
                    progress.reached -> "목표를 넘었어요!"
                    progress.openDaysLeft == 0 -> "남은 돈 ${Fmt.won(progress.remaining)} · 남은 영업일이 없어요"
                    else -> "남은 돈 ${Fmt.won(progress.remaining)} · 남은 영업일 ${progress.openDaysLeft}일 · 하루 목표 ${Fmt.won(progress.perDay)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (progress.reached) LocalCalendarColors.current.income else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (editing) {
        var digits by rememberSaveable { mutableStateOf(amountDigits(rules.monthlyTarget)) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("한 달 목표 매출") },
            text = { AmountField("목표 매출", digits, { digits = it }) },
            confirmButton = { TextButton(onClick = { onTarget(digits.toAmount()); editing = false }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("취소") } },
        )
    }
}

/** [SHOP-04] 부가세 예상: the half year's sales and costs and a rough VAT, 일반 or 간이. */
@Composable
private fun VatCard(rules: ShopMoney, book: MoneyBook, date: LocalDate, onSimple: (Boolean) -> Unit) {
    val half = remember(book, date, rules) { VatEstimate.of(date, book.sales, book.entries, rules) }
    SectionCard(title = "부가세 예상 (${half.year}년 ${when (half.half) { 1 -> "1~6월"; 2 -> "7~12월"; else -> "1~12월" }})") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !rules.simpleTax, onClick = { onSimple(false) }, label = { Text("일반과세") })
            FilterChip(selected = rules.simpleTax, onClick = { onSimple(true) }, label = { Text("간이과세") })
        }
        YearLine("매출", Fmt.won(half.sales))
        YearLine("경비", Fmt.won(half.expenses))
        if (!rules.simpleTax) YearLine("세금계산서·카드·현금영수증 경비", Fmt.won(half.provenExpenses))
        YearLine("부가세 대략", Fmt.won(half.vat))
        Text(
            (if (rules.simpleTax) "간이과세: 1년 매출 × 업종 부가가치율 ${rules.valueAddedPercent}% × 10%로 셌어요. " +
                (if (half.exempt) "1년 매출이 4,800만 원 미만이면 낼 부가세가 없어요(납부 면제). " else "")
            else "일반과세: (매출 − 증빙 있는 경비) × 10/110로 셌어요. 경비에 증빙을 고르면 빠져요. ") +
                "대략·참고용이에요. 신고 마감 ${half.due.monthValue}월 ${half.due.dayOfMonth}일.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
