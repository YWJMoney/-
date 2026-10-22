package com.bamnat.app.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Tier
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.theme.LedgerColors
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MonthMoney
import com.bamnat.core.money.MonthReport
import com.bamnat.core.money.MoneyStats
import com.bamnat.core.money.Reports
import com.bamnat.core.money.YearReport
import com.bamnat.core.work.WorkStyle
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** [LEDG-10] What the 분석 tab draws: the month looked at closely and its year. */
data class LedgerAnalysis(val month: MonthMoney, val report: MonthReport, val year: YearReport)

/**
 * [LEDG-10][TIER-05] The month and the year in pictures: where the money went against last month,
 * how fast it went, money in against money out, the year month by month, and the accounts, tags,
 * days and payments behind it. Totals stay free; the rest is marked 프로 and opens to everyone
 * until billing ships.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerReportsPane(
    analysis: LedgerAnalysis?,
    book: MoneyBook,
    budget: Long?,
    style: WorkStyle?,
    gate: com.bamnat.app.tier.Gate,
    wide: Boolean,
    today: LocalDate,
    onCategory: (String) -> Unit,
    onOpen: (MoneyEntry) -> Unit,
    onPlace: (String) -> Unit,
    month: YearMonth,
) {
    if (analysis == null) return
    val places = remember(book, month) { MoneyStats.places(book.entries, month) }
    val r = analysis.report
    val m = analysis.month
    val first: @Composable () -> Unit = {
        OverviewCard(m, r, today)
        // [MON-04] Locked, the user's own analysis shows blurred under the lock.
        com.bamnat.app.ui.pro.ProLock(Feature.MONEY_STATS, gate) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (r.categories.isNotEmpty()) CategoriesCard(r, onCategory)
                PaceCard(r, budget, today)
                InOutCard(m, style)
            }
        }
    }
    val second: @Composable () -> Unit = {
        if (gate.allows(Feature.MONEY_STATS)) {
            YearCard(analysis.year, r.month)
            if (r.byAccount.size > 1 || r.byAccount.any { it.first != null }) AccountsShareCard(r, book)
            if (places.isNotEmpty()) PlacesCard(places, onPlace)
            if (r.byTag.isNotEmpty()) TagsCard(r)
            if (r.spent > 0) WeekdayCard(r)
            if (r.biggest.isNotEmpty()) BiggestCard(r, onOpen)
        } else {
            com.bamnat.app.ui.pro.ProLock(Feature.MONEY_STATS, gate) { YearCard(analysis.year, r.month) }
        }
        if (!gate.live) ProNote()
    }
    if (!r.hasData && analysis.year.activeMonths == 0) {
        LedgerEmpty(Icons.Outlined.Insights, "분석할 기록이 아직 없어요", "돈을 적으면 어디에 얼마 썼는지, 지난달보다 어떤지 그림으로 보여 드려요.")
        return
    }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) { first(); Spacer(Modifier.height(FabClearance)) }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) { second(); Spacer(Modifier.height(FabClearance)) }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) { first(); second(); Spacer(Modifier.height(FabClearance)) }
    }
}

/** [LEDG-10] The month's three numbers, each against the month before. */
@Composable
private fun OverviewCard(m: MonthMoney, r: MonthReport, today: LocalDate) {
    SectionCard(title = "${m.month.monthValue}월 한눈에") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("들어온 돈", Fmt.won(m.income), Modifier.weight(1f), caption = changeText(m.income, r.previousIncome, "많이", "적게")?.let { "지난달보다 $it" })
            StatTile("나간 돈", Fmt.won(m.spent), Modifier.weight(1f), caption = changeText(m.spent, r.previousSpent)?.let { "지난달보다 $it" })
        }
        Spacer(Modifier.height(8.dp))
        val saved = if (m.income > 0) (m.balance * 100 / m.income).toInt() else null
        StatTile("남은 돈", Fmt.won(m.balance), Modifier.fillMaxWidth(), warning = m.balance < 0,
            caption = saved?.let { if (it >= 0) "들어온 돈의 $it%를 남겼어요" else "들어온 돈보다 더 썼어요" })
        if (r.daysCounted > 0) {
            val perDay = r.spent / r.daysCounted
            Text("하루 평균 ${Fmt.won(perDay)} · 무지출 ${r.noSpendDays}일" + if (YearMonth.from(today) == m.month) " (오늘까지)" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** [TIER-05] Before the 정식 출시 switch, the one line that says what will be pro. */
@Composable
private fun ProNote() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        ProPill()
        Spacer(Modifier.width(8.dp))
        Text(
            "지난달 비교·분류별·올해 흐름 분석은 정식 출시 뒤 ${Tier.PRO.label} 기능이 돼요. 지금은 모두 무료예요. 기록과 합계는 계속 무료예요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** [LEDG-10] Where the money went, as a ring and a list; a tap shows that category's lines. */
@Composable
private fun CategoriesCard(r: MonthReport, onCategory: (String) -> Unit) {
    val cal = LocalCalendarColors.current
    val shown = r.categories.take(7)
    val rest = r.categories.drop(7)
    val other = LedgerColors.other()
    val slices = shown.map { Slice(it.category, it.amount, categoryColor(it.category)) } +
        listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let { l -> Slice("그 밖에", l.sumOf { it.amount }, other) })
    SectionCard(title = "분류별 지출", trailing = { ProPill() }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Donut(slices, Fmt.won(r.spent), "나간 돈")
        }
        Spacer(Modifier.height(12.dp))
        val top = r.categories.maxOf { it.amount }.coerceAtLeast(1)
        r.categories.forEach { c ->
            val diff = c.diff
            ShareRow(
                title = c.category, amount = c.amount, fraction = c.amount.toFloat() / top, color = categoryColor(c.category),
                leading = { CategoryAvatar(c.category, size = 36.dp) },
                caption = "${(c.share * 100).toInt()}%",
                trailingNote = when {
                    c.previous == 0L -> "새로"
                    diff > 0 -> "▲${wonBrief(diff)}"
                    diff < 0 -> "▼${wonBrief(-diff)}"
                    else -> "같음"
                },
                trailingColor = when { diff > 0 && c.previous > 0 -> cal.expense; diff < 0 -> cal.income; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                onClick = if (c.category == Reports.JOB_COSTS) null else ({ onCategory(c.category) }),
            )
        }
        Text("▲▼는 지난달과 비교예요. 분류를 누르면 그 기록만 보여요.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/** [LEDG-10] Spending added up day by day, against last month and the budget. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaceCard(r: MonthReport, budget: Long?, today: LocalDate) {
    if (r.spent <= 0 && r.previousSpent <= 0) return
    val cal = LocalCalendarColors.current
    val current = YearMonth.from(today) == r.month
    val diff = r.spent - r.previousSameDay
    val line = when {
        !current -> changeText(r.spent, r.previousSpent)?.let { "지난달보다 $it 썼어요" } ?: "${Fmt.won(r.spent)} 썼어요"
        r.previousCumulative.isEmpty() || r.previousSpent == 0L -> "오늘까지 ${Fmt.won(r.spent)} 썼어요"
        diff > 0 -> "지난달 같은 날보다 ${Fmt.won(diff)} 더 썼어요"
        diff < 0 -> "지난달 같은 날보다 ${Fmt.won(-diff)} 덜 썼어요"
        else -> "지난달 같은 날과 똑같이 썼어요"
    }
    SectionCard(title = "쓰는 속도", trailing = { ProPill() }) {
        Text(line, style = MaterialTheme.typography.titleSmall, color = if (current && diff > 0) cal.expense else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        PaceChart(r.cumulative, r.previousCumulative, r.month.lengthOfMonth(), budget, "$line. 이번 달 ${Fmt.won(r.spent)}, 지난달 ${Fmt.won(r.previousSpent)}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
            LegendKey(cal.expense, "이번 달")
            LegendKey(MaterialTheme.colorScheme.onSurfaceVariant, "지난달", dashed = true)
            if (budget != null && budget > 0) LegendKey(cal.warning, "예산 ${wonBrief(budget)}", dashed = true)
        }
    }
}

/** [LEDG-10][WORK-04] Money in by where it came from (pay, jobs, the shop, the rest) against money out. */
@Composable
private fun InOutCard(m: MonthMoney, style: WorkStyle?) {
    if (m.income == 0L && m.spent == 0L) return
    val cal = LocalCalendarColors.current
    val top = maxOf(m.income, m.spent).coerceAtLeast(1)
    val sources = buildList {
        if (m.payCounted > 0) add((if (m.payRecorded > 0) "급여 (받은 금액)" else "급여 (예상)") to m.payCounted)
        if (m.jobReceived > 0 || style?.usesJobs == true) add("일감 받은 돈" to m.jobReceived)
        if (m.salesNet != 0L || style?.usesSales == true) add("가게 순수익" to m.salesNet)
        if (m.otherIncome > 0) add("그 밖의 수입" to m.otherIncome)
    }
    SectionCard(title = "들어온 돈과 나간 돈", trailing = { ProPill() }) {
        ShareRow("들어온 돈", m.income, m.income.toFloat() / top, cal.income)
        sources.forEach { (label, v) ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp)) {
                Text("· $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(Fmt.won(v), style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ShareRow("나간 돈", m.spent, m.spent.toFloat() / top, cal.expense)
        if (m.jobCosts > 0) {
            Text("일감 경비 ${Fmt.won(m.jobCosts)}이 나간 돈에 들어 있어요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("남은 돈", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(Fmt.won(m.balance), style = MaterialTheme.typography.titleSmall.merge(NumberStyle), fontWeight = FontWeight.Bold,
                color = if (m.balance < 0) cal.expense else cal.income)
        }
    }
}

/** [LEDG-10] The year month by month; a tap on a month says its numbers. */
@Composable
private fun YearCard(y: YearReport, shown: YearMonth) {
    var picked by rememberSaveable(y.year) { mutableIntStateOf((shown.monthValue - 1).coerceIn(0, 11)) }
    val months = y.months
    if (y.activeMonths == 0) return
    SectionCard(title = "${y.year}년 흐름", trailing = { ProPill() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("올해 들어온 돈", Fmt.won(y.income), Modifier.weight(1f), caption = "한 달 평균 ${wonBrief(y.averageIncome)}")
            StatTile("올해 나간 돈", Fmt.won(y.spent), Modifier.weight(1f), caption = "한 달 평균 ${wonBrief(y.averageSpent)}")
        }
        Spacer(Modifier.height(10.dp))
        PairBars(
            labels = months.map { "${it.month.monthValue}" },
            income = months.map { it.income }, spent = months.map { it.spent },
            selected = picked, onSelect = { picked = it },
            descriptions = months.map { "${it.month.monthValue}월 들어온 돈 ${Fmt.won(it.income)}, 나간 돈 ${Fmt.won(it.spent)}" },
        )
        months.getOrNull(picked)?.let { pm ->
            val cal = LocalCalendarColors.current
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${pm.month.monthValue}월", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("+${wonBrief(pm.income)}", style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = cal.income)
                Spacer(Modifier.width(10.dp))
                Text("-${wonBrief(pm.spent)}", style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = cal.expense)
                Spacer(Modifier.width(10.dp))
                Text("= ${wonBrief(pm.balance)}", style = MaterialTheme.typography.labelLarge.merge(NumberStyle), fontWeight = FontWeight.Bold)
            }
        }
        y.savingRate?.let { rate ->
            Text(if (rate >= 0) "올해 들어온 돈의 $rate%를 남겼어요." else "올해는 들어온 돈보다 ${Fmt.won(-y.balance)} 더 썼어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun AccountsShareCard(r: MonthReport, book: MoneyBook) {
    val top = r.byAccount.maxOf { it.second }.coerceAtLeast(1)
    SectionCard(title = "결제수단별 지출", trailing = { ProPill() }) {
        r.byAccount.forEach { (id, v) ->
            val a = book.account(id)
            ShareRow(a?.label ?: "고르지 않음", v, v.toFloat() / top, a?.let { accountColor(it) } ?: LedgerColors.other(),
                leading = { if (a != null) IconAvatar(accountIcon(a.type), accountColor(a), size = 32.dp) else CategoryAvatar("기타", size = 32.dp) },
                caption = "${(v * 100 / r.spent.coerceAtLeast(1))}%")
        }
    }
}

/** [LEDG-04] Where the user pays most often this month; a tap shows those lines in 내역. */
@Composable
private fun PlacesCard(places: List<MoneyStats.Place>, onPlace: (String) -> Unit) {
    val top = places.maxOf { it.amount }.coerceAtLeast(1)
    SectionCard(title = "자주 쓴 곳", trailing = { ProPill() }) {
        places.forEach { p ->
            ShareRow(p.merchant, p.amount, p.amount.toFloat() / top, categoryColor(p.category),
                leading = { CategoryAvatar(p.category, size = 32.dp) },
                caption = "${p.count}번 · ${p.category}",
                onClick = { onPlace(p.merchant) })
        }
    }
}

@Composable
private fun TagsCard(r: MonthReport) {
    val top = r.byTag.maxOf { it.second }.coerceAtLeast(1)
    SectionCard(title = "태그별 지출", trailing = { ProPill() }) {
        r.byTag.take(8).forEachIndexed { i, (t, v) -> ShareRow("#$t", v, v.toFloat() / top, LedgerColors.at(i + 3)) }
    }
}

@Composable
private fun WeekdayCard(r: MonthReport) {
    val most = r.byWeekday.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }
    SectionCard(title = "요일별 지출", trailing = { ProPill() }) {
        most?.let {
            Text("${Reports.WEEKDAYS[it.index]}요일에 가장 많이 썼어요", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
        }
        ValueBars(Reports.WEEKDAYS, r.byWeekday, LocalCalendarColors.current.expense, most?.index)
    }
}

/** [LEDG-10] The month's five biggest payments; a tap opens the line. */
@Composable
private fun BiggestCard(r: MonthReport, onOpen: (MoneyEntry) -> Unit) {
    SectionCard(title = "가장 큰 지출", trailing = { ProPill() }) {
        r.biggest.forEachIndexed { i, e ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpen(e) }.padding(vertical = 4.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "${i + 1}위 ${e.title} ${Fmt.won(e.amount)}, ${Fmt.dayTitle(e.date)}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
                CategoryAvatar(e.category, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Fmt.shortDate(e.date)} · ${e.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Fmt.won(abs(e.amount)), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
