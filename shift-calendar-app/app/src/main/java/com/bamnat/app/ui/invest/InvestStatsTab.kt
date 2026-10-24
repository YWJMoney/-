package com.bamnat.app.ui.invest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.invest.InvestStats
import com.bamnat.core.invest.InvestTaxes
import com.bamnat.core.invest.PeriodStats
import com.bamnat.core.invest.TagPnl
import java.time.DayOfWeek
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * [INV-06][INV-12][INV-13][INV-18] The numbers of a month or a year: what it made, 승률, 손익비, the
 * months as bars, each holding, how trades went by mood and by keeping the rules, the weekdays,
 * holding days, best and worst, and what tax it may come to.
 */
@Composable
internal fun InvestStatsTab(state: InvestState, wide: Boolean, calendarId: String = com.bamnat.app.data.PersonalCalendar.MAIN) {
    var yearly by rememberSaveable { mutableStateOf(false) }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(state.today).toString()) }
    val ym = YearMonth.parse(month)
    val hide = state.hide
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { month = (if (yearly) ym.minusYears(1) else ym.minusMonths(1)).toString() }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, if (yearly) "이전 해" else "이전 달")
        }
        Text(if (yearly) "${ym.year}년" else Fmt.month(ym), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { month = (if (yearly) ym.plusYears(1) else ym.plusMonths(1)).toString() }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, if (yearly) "다음 해" else "다음 달")
        }
        FilterChip(selected = !yearly, onClick = { yearly = false }, label = { Text("월") })
        FilterChip(selected = yearly, onClick = { yearly = true }, label = { Text("연") }, modifier = Modifier.padding(start = 6.dp))
    }
    val stats = remember(state, ym, yearly) {
        if (yearly) InvestStats.year(state.book, state.result, ym.year) else InvestStats.month(state.book, state.result, ym)
    }
    if (stats.isEmpty) {
        Text(if (yearly) "${ym.year}년에 적은 매매가 없어요" else "${ym.monthValue}월에 적은 매매가 없어요", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Figures(stats, hide, if (wide) 4 else 3)
    }
    MonthlyBars(state, ym.year)
    if (!stats.isEmpty) {
        BySymbol(stats, hide)
        // [GUIDE-05] Cards that can be turned off for this calendar.
        com.bamnat.app.ui.guide.FeatureCard("invest.mind", calendarId) { Behaviour(stats, hide) }
        com.bamnat.app.ui.guide.FeatureCard("invest.weekdays", calendarId) { Weekdays(stats, hide) }
    }
    com.bamnat.app.ui.guide.FeatureCard("invest.tax", calendarId) { TaxCard(state, ym.year) }
}

@Composable
private fun Figures(s: PeriodStats, hide: Boolean, columns: Int) {
    val tiles = listOf(
        Triple("손익", InvestFmt.signedShort(s.pnl, hide), "실현 + 배당·이자"),
        Triple("승률", s.winRate?.let { "${"%.0f".format(it * 100)}%" } ?: "–", "매도 ${s.sells}번 중 ${s.wins}번 이익"),
        Triple("매매", "${s.trades}건", "산 돈 ${InvestFmt.short(s.bought, hide)}"),
        Triple("실현손익", InvestFmt.signedShort(s.realized, hide), "판 돈 ${InvestFmt.short(s.sold, hide)}"),
        Triple("배당·이자", InvestFmt.short(s.income, hide), "세후"),
        Triple("수수료·세금", InvestFmt.short(s.fees + s.taxes, hide), "세금 ${InvestFmt.short(s.taxes, hide)}"),
        Triple("손익비", s.payoff?.let { "%.2f".format(it) } ?: "–", "평균 이익 ÷ 평균 손실"),
        Triple("수익 팩터", s.profitFactor?.let { "%.2f".format(it) } ?: "–", "총이익 ÷ 총손실"),
        Triple("평균 보유", s.averageHoldingDays?.let { "%.0f일".format(it) } ?: "–", "판 종목 기준"),
        Triple("연속 수익일", "${s.longestWinStreak}일", "연속 손실 ${s.longestLossStreak}일"),
        Triple("입금·출금", InvestFmt.short(s.deposits - s.withdrawals, hide), "입금 ${InvestFmt.short(s.deposits, hide)}"),
        Triple("일지 쓴 날", "${s.journalDays}일", "수익 ${s.winDays}일 · 손실 ${s.lossDays}일"),
    )
    tiles.chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (l, v, c) -> StatTile(l, v, Modifier.weight(1f), caption = c) }
            repeat(columns - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
        }
    }
    s.best?.let { b ->
        Text("가장 잘한 매도 · ${b.name} ${InvestFmt.signed(b.pnlWon.roundToLong(), hide)} (${Fmt.shortDate(b.date)})",
            style = MaterialTheme.typography.bodyMedium, color = pnlColor(b.pnlWon))
    }
    s.worst?.let { w ->
        Text("가장 아쉬운 매도 · ${w.name} ${InvestFmt.signed(w.pnlWon.roundToLong(), hide)} (${Fmt.shortDate(w.date)})",
            style = MaterialTheme.typography.bodyMedium, color = pnlColor(w.pnlWon))
    }
}

/** [INV-06] Twelve months of a year as red and blue bars around zero. */
@Composable
private fun MonthlyBars(state: InvestState, year: Int) {
    val months = remember(state, year) { InvestStats.monthly(state.book, state.result, year) }
    if (months.all { it.second == 0L }) return
    val cal = LocalCalendarColors.current
    val axis = MaterialTheme.colorScheme.outlineVariant
    val max = months.maxOf { abs(it.second) }.coerceAtLeast(1)
    SectionCard(title = "${year}년 월별 손익") {
        Canvas(Modifier.fillMaxWidth().height(110.dp).semantics {
            contentDescription = months.filter { it.second != 0L }.joinToString(", ") { "${it.first.monthValue}월 ${InvestFmt.signedShort(it.second, state.hide)}" }
        }) {
            val w = size.width / 12
            val mid = size.height / 2
            drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.5f)
            months.forEachIndexed { i, (_, v) ->
                if (v == 0L) return@forEachIndexed
                val h = (abs(v).toFloat() / max) * (mid - 4)
                val top = if (v > 0) mid - h else mid
                drawRect(if (v > 0) cal.sunday else cal.saturday, Offset(i * w + w * 0.2f, top), Size(w * 0.6f, h))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            (1..12).forEach { m ->
                Text("$m", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** [INV-06] 종목별 손익: what each holding made by selling and paying out. */
@Composable
private fun BySymbol(s: PeriodStats, hide: Boolean) {
    val rows = s.bySymbol.filter { it.total != 0L || it.trades > 0 }.take(12)
    if (rows.isEmpty()) return
    SectionCard(title = "종목별 손익") {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.bodyLarge)
                    Text("매매 ${r.trades}건" + (if (r.sells > 0) " · 매도 ${r.sells}번 중 ${r.wins}번 이익" else "") +
                        (if (r.dividends > 0) " · 배당 ${InvestFmt.short(r.dividends, hide)}" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(InvestFmt.signed(r.total, hide), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = pnlColor(r.total))
            }
        }
    }
}

/** [INV-13] How trades went by the mood they were made in, and by keeping the rules. */
@Composable
private fun Behaviour(s: PeriodStats, hide: Boolean) {
    if (s.byEmotion.isEmpty() && s.byRules.isEmpty()) return
    SectionCard(title = "마음과 원칙에 따라") {
        (s.byRules + s.byEmotion).forEach { t -> TagRow(t, hide) }
        val kept = s.byRules.firstOrNull { it.label == "원칙 지킴" }
        val broke = s.byRules.firstOrNull { it.label == "원칙 어김" }
        if (kept != null && broke != null) {
            val diff = kept.pnl - broke.pnl
            Text(if (diff > 0) "원칙을 지킨 매매가 ${InvestFmt.short(diff, hide)} 더 벌었어요" else "이번엔 원칙을 어긴 매매가 더 나았어요. 원칙을 다시 볼 때일지도요",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TagRow(t: TagPnl, hide: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
        Text("${t.sells}번 · 승률 ${t.winRate?.let { "%.0f%%".format(it * 100) } ?: "–"}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(InvestFmt.signed(t.pnl, hide), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = pnlColor(t.pnl))
    }
}

/** [INV-18] 요일별 손익. */
@Composable
private fun Weekdays(s: PeriodStats, hide: Boolean) {
    val days = DayOfWeek.entries.filter { it.value <= 5 || (s.byWeekday[it] ?: 0L) != 0L }
    if (days.all { (s.byWeekday[it] ?: 0L) == 0L }) return
    SectionCard(title = "요일별 손익") {
        Row(Modifier.fillMaxWidth()) {
            days.forEach { d ->
                val v = s.byWeekday[d] ?: 0L
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.dayOfWeekShort(d), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (v == 0L) "–" else InvestFmt.signedShort(v, hide), style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = pnlColor(v))
                }
            }
        }
    }
}

/**
 * [INV-12] What this year's trading may come to in tax, by the 2026 rules: 해외 주식 22% over 250만,
 * 코인 from 2027, 배당·이자 15.4% and 금융소득종합과세 at 2,000만, ISA's 비과세 한도, 연금 계좌 deferred.
 */
@Composable
private fun TaxCard(state: InvestState, year: Int) {
    val t = remember(state, year) { InvestTaxes.estimate(state.full, state.fullResult, year) }
    val hide = state.hide
    val lines = buildList {
        if (t.domesticGain != 0L || t.tradeTax > 0) add("국내 주식 손익 ${InvestFmt.signed(t.domesticGain, hide)} · 거래세 ${InvestFmt.won(t.tradeTax, hide)} (대주주가 아니면 양도세 없음)")
        if (t.foreignGain != 0L) add("해외 주식 손익 ${InvestFmt.signed(t.foreignGain, hide)} → 예상 양도세 ${InvestFmt.won(t.foreignTax, hide)}" +
            if (t.foreignDeductionLeft > 0) " (250만 원 공제 ${InvestFmt.short(t.foreignDeductionLeft, hide)} 남음)" else "")
        if (t.cryptoGain != 0L) add("코인 손익 ${InvestFmt.signed(t.cryptoGain, hide)} · " +
            if (t.cryptoTaxed) "예상 세금 ${InvestFmt.won(t.cryptoTax, hide)}" else "${InvestTaxes.CRYPTO_FROM_YEAR}년부터 과세 (그 기준이면 ${InvestFmt.won(t.cryptoTax, hide)})")
        if (t.financialIncome > 0) add("배당·이자 ${InvestFmt.won(t.financialIncome, hide)} · 떼인 세금 ${InvestFmt.won(t.dividendTax, hide)}")
        if (t.hasIsa) add("ISA 순이익 ${InvestFmt.signed(t.isaGain, hide)} / 비과세 ${InvestFmt.short(t.isaLimit, hide)}" +
            if (t.isaTaxOver > 0) " · 넘는 만큼 9.9% ${InvestFmt.won(t.isaTaxOver, hide)}" else "")
        if (t.deferredGain != 0L) add("연금저축·IRP 손익 ${InvestFmt.signed(t.deferredGain, hide)} · 연금으로 받을 때 과세")
    }
    if (lines.isEmpty()) return
    SectionCard(title = "${year}년 세금 미리 보기") {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
        if (t.financialOver) Text("금융소득이 2,000만 원을 넘었어요. 다른 소득과 합쳐 종합과세될 수 있어요.", style = MaterialTheme.typography.bodySmall,
            color = LocalCalendarColors.current.warning)
        else if (t.financialNear) Text("금융소득이 2,000만 원에 가까워요. 넘으면 종합과세 대상이에요.", style = MaterialTheme.typography.bodySmall,
            color = LocalCalendarColors.current.warning)
        // [INV-35] Why this gain and the 보유 tab's can differ.
        if (t.foreignGain != 0L) Text(InvestTaxes.AVERAGE_VS_FIFO, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("참고용 계산이에요. 실제 세금은 증권사 자료와 홈택스로 확인해 주세요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
