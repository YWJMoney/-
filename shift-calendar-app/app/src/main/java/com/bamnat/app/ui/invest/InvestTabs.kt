package com.bamnat.app.ui.invest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.invest.AlarmKind
import com.bamnat.core.invest.CashFlow
import com.bamnat.core.invest.CashKind
import com.bamnat.core.invest.Holding
import com.bamnat.core.invest.InvestAlarms
import com.bamnat.core.invest.InvestPlans
import com.bamnat.core.invest.Market
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.Trade
import com.bamnat.core.invest.TradeDraft
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.math.roundToLong
import com.bamnat.core.time.Today

/** [INV-03] One trade as a line: what, how many at what price, and for a sale what it made. */
@Composable
internal fun TradeLine(t: Trade, state: InvestState, onClick: () -> Unit) {
    val hide = state.hide
    val realized = state.result.realizations.firstOrNull { it.tradeId == t.id }
    val unit = if (t.market == Market.CRYPTO) "개" else "주"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.side.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = if (t.side == Side.BUY) com.bamnat.app.ui.theme.LocalCalendarColors.current.sunday else com.bamnat.app.ui.theme.LocalCalendarColors.current.saturday,
            modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // [INV-31] Coin or share at a glance.
                com.bamnat.core.invest.TradeAlerts.badge(t.market)?.let { MarketBadge(it, t.market) }
                Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(
                    "${InvestFmt.quantity(t.quantity)}$unit × ${InvestFmt.price(t.price, t.market)}",
                    t.minute?.let { minuteText(it) },
                    // [INV-31] A trade from a notification says which app it came from.
                    (t.tags.map { tag -> tag.removePrefix(com.bamnat.core.invest.TradeAlerts.TAG_PREFIX).let { if (it != tag) "$it 알림" else tag } } +
                        t.emotions.map { it.label }).take(3).joinToString(" ").ifBlank { null },
                    state.full.account(t.accountId)?.name?.takeIf { state.full.activeAccounts.size > 1 },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(InvestFmt.won(kotlin.math.abs(t.cashWon).roundToLong(), hide), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle))
            realized?.let { r ->
                Text("${InvestFmt.signed(r.pnlWon.roundToLong(), hide)} ${InvestFmt.percent(r.rate)}", style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
                    color = pnlColor(r.pnlWon))
            }
        }
    }
}

/** [INV-31] 코인, 주식, ETF or 해외주식 as a small label. */
@Composable
internal fun MarketBadge(text: String, market: Market) {
    val coin = market == Market.CRYPTO
    androidx.compose.material3.Surface(
        color = if (coin) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (coin) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** [INV-02][INV-09] One deposit, withdrawal, dividend or cost as a line. */
@Composable
internal fun CashLine(c: CashFlow, state: InvestState, onClick: () -> Unit) {
    val hide = state.hide
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(c.kind.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp),
            color = if (c.kind == CashKind.WITHDRAW || c.kind == CashKind.FEE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            maxLines = 1)
        Column(Modifier.weight(1f)) {
            Text(listOf(c.symbol, c.memo).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { state.full.account(c.accountId)?.name ?: c.kind.label },
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.tax > 0) Text("세금 ${InvestFmt.won(c.tax, hide)} · 실수령 ${InvestFmt.won(c.amount - c.tax, hide)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text((if (c.cashWon >= 0) "+" else "−") + InvestFmt.won(kotlin.math.abs(c.cashWon), hide), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle))
    }
}

/** [INV-04] What is held: 평균단가, 현재가, 평가손익 and its share, and what was sold out. */
@Composable
internal fun HoldingsTab(state: InvestState, open: (InvestSheet) -> Unit) {
    val hide = state.hide
    val held = state.result.held
    val total = held.sumOf { it.valueWon ?: it.costWon }.coerceAtLeast(1.0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("갖고 있는 종목 ${held.size}개", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = { open(InvestSheet.Quotes) }, enabled = held.isNotEmpty()) { Text("현재가 적기") }
    }
    if (held.isEmpty()) Text("매수를 적으면 평균단가와 보유 수량을 알아서 계산해요.", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    held.forEach { h -> HoldingCard(h, state, (h.valueWon ?: h.costWon) / total, open) }
    val sold = state.result.byKey.filter { !it.held }
    if (sold.isNotEmpty()) SectionCard(title = "다 판 종목") {
        sold.forEach { h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(h.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(InvestFmt.signed((h.realizedWon + h.dividendsWon).roundToLong(), hide), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                    color = pnlColor(h.realizedWon + h.dividendsWon))
            }
        }
    }
    Text("평균단가는 산 금액에 수수료까지 더해 이동평균으로 계산해요. 증권사 앱과 조금 다를 수 있어요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // [INV-35] The tax preview matches sales oldest first.
    if (held.any { it.market.foreign }) Text(com.bamnat.core.invest.InvestTaxes.AVERAGE_VS_FIFO,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HoldingCard(h: Holding, state: InvestState, weight: Double, open: (InvestSheet) -> Unit) {
    val hide = state.hide
    var expanded by rememberSaveable(h.key) { mutableStateOf(false) }
    val unit = if (h.market == Market.CRYPTO) "개" else "주"
    val fx = state.settings.usdKrw
    SectionCard(title = null) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.title, style = MaterialTheme.typography.titleSmall)
                Text("${InvestFmt.quantity(h.quantity)}$unit · 평균 ${InvestFmt.price(h.averagePrice, h.market)} · 비중 ${"%.0f".format(weight * 100)}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(InvestFmt.won((h.valueWon ?: h.costWon).roundToLong(), hide), style = MaterialTheme.typography.bodyLarge.merge(NumberStyle))
                val u = h.unrealizedWon
                Text(if (u == null) "현재가 없음" else "${InvestFmt.signed(u.roundToLong(), hide)} ${InvestFmt.percent(h.unrealizedRate)}",
                    style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = if (u == null) MaterialTheme.colorScheme.onSurfaceVariant else pnlColor(u))
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            val lines = listOfNotNull(
                h.quote?.let { "현재가 ${InvestFmt.priceWithWon(it.price, h.market, fx)} (${Fmt.shortDate(it.date)} 적음)" },
                "매입금액 ${InvestFmt.won(h.costWon.roundToLong(), hide)}",
                h.realizedWon.takeIf { kotlin.math.abs(it) > 0.5 }?.let { "실현손익 ${InvestFmt.signed(it.roundToLong(), hide)}" },
                h.dividendsWon.takeIf { it > 0 }?.let { "받은 배당 ${InvestFmt.won(it, hide)}" },
                "합계 손익 ${InvestFmt.signed(h.totalPnlWon.roundToLong(), hide)}",
                h.openedOn?.let { "보유 ${java.time.temporal.ChronoUnit.DAYS.between(it, state.today)}일째 (${Fmt.shortFullDate(it)}부터)" },
            )
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                AssistChip(onClick = { open(InvestSheet.TradeEdit(TradeDraft(h.symbol, h.name, h.market, Side.BUY, price = h.quote?.price))) }, label = { Text("더 사기") })
                AssistChip(onClick = { open(InvestSheet.TradeEdit(TradeDraft(h.symbol, h.name, h.market, Side.SELL, h.quantity, h.quote?.price))) }, label = { Text("팔기") })
                AssistChip(onClick = { open(InvestSheet.CashEdit(CashKind.DIVIDEND)) }, label = { Text("배당") })
                AssistChip(onClick = { open(InvestSheet.AlarmEdit(AlarmKind.TARGET)) }, label = { Text("목표가 알람") })
            }
        }
    }
}

/** [INV-03][INV-10][INV-20] Every record of a month, newest first; a tap opens it to change, copy or delete. */
@Composable
internal fun RecordsTab(state: InvestState, open: (InvestSheet) -> Unit) {
    var month by rememberSaveable { mutableStateOf(YearMonth.from(state.today).toString()) }
    val ym = YearMonth.parse(month)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { month = ym.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
        Text(Fmt.month(ym), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { month = ym.plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
    }
    val days = (state.trades.keys + state.cash.keys).filter { YearMonth.from(it) == ym }.sortedDescending()
    if (days.isEmpty()) Text("${ym.monthValue}월에 적은 기록이 없어요", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    days.forEach { d -> DayRecords(d, state, open) }
}

@Composable
internal fun DayRecords(d: LocalDate, state: InvestState, open: (InvestSheet) -> Unit) {
    val pnl = state.daily[d]
    SectionCard(title = Fmt.dayTitle(d), trailing = pnl?.let { p ->
        { Text(InvestFmt.signed(p, state.hide), color = pnlColor(p), style = MaterialTheme.typography.titleSmall.merge(NumberStyle)) }
    }) {
        state.tradesOn(d).forEach { t -> TradeLine(t, state) { open(InvestSheet.TradeEdit(editing = t)) } }
        state.cashOn(d).forEach { c -> CashLine(c, state) { open(InvestSheet.CashEdit(editing = c)) } }
        state.full.journalOn(d)?.takeIf { !it.isEmpty }?.let { j ->
            HorizontalDivider()
            Text("일지 · " + listOfNotNull(j.grade?.label, j.review.ifBlank { j.market }.take(40).ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { open(InvestSheet.Journal(d)) }.padding(vertical = 6.dp))
        }
    }
}

/** [INV-07] The journal: today first, then the month's entries, and how often the rules were kept. */
@Composable
internal fun JournalTab(state: InvestState, open: (InvestSheet) -> Unit) {
    val today = state.today
    val month = YearMonth.from(today)
    val entries = state.full.journal.filter { !it.isEmpty }.sortedByDescending { it.date }
    val principles = state.settings.principles
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { open(InvestSheet.Journal(today)) }) {
            Text(if (state.full.journalOn(today) == null) "오늘 일지 쓰기" else "오늘 일지 고치기")
        }
        OutlinedButton(onClick = { open(InvestSheet.Principles) }) { Text("원칙 고치기") }
    }
    val thisMonth = entries.filter { YearMonth.from(it.date) == month }
    if (thisMonth.isNotEmpty() && principles.isNotEmpty()) {
        SectionCard(title = "${month.monthValue}월 원칙 지킨 비율") {
            principles.forEach { p ->
                val kept = thisMonth.count { p in it.checked }
                Text("$p · ${thisMonth.size}일 중 ${kept}일", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.LinearProgressIndicator(progress = { kept.toFloat() / thisMonth.size },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
    }
    if (entries.isEmpty()) Text("매매가 없는 날에도 시장 메모와 복기를 적어 두면, 나중에 같은 실수를 덜 해요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    entries.take(60).forEach { j ->
        val pnl = state.daily[j.date]
        SectionCard(title = Fmt.dayTitle(j.date), trailing = {
            Text(listOfNotNull(j.grade?.label, pnl?.let { InvestFmt.signedShort(it, state.hide) }).joinToString(" · "),
                color = pnl?.let { pnlColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }) {
            Column(Modifier.fillMaxWidth().clickable { open(InvestSheet.Journal(j.date)) }) {
                if (j.market.isNotBlank()) Text("시장 · ${j.market}", style = MaterialTheme.typography.bodyMedium)
                if (j.review.isNotBlank()) Text("복기 · ${j.review}", style = MaterialTheme.typography.bodyMedium)
                if (principles.isNotEmpty()) Text("원칙 ${principles.count { it in j.checked }}/${principles.size} 지킴",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * [INV-08][INV-14] The alarms (장 시작·마감, 정기 매수 with how the plan is going, 목표가, the user's own)
 * and the dates written down to watch.
 */
@Composable
internal fun AlarmsTab(vm: InvestViewModel, state: InvestState, open: (InvestSheet) -> Unit) {
    val now = Today.dateTime()
    Text("알림을 누르면 내용이 채워진 매매 기록 창이 열려요. 소리로 울릴지는 알람마다 고를 수 있어요.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth()) {
        listOf(AlarmKind.MARKET_OPEN, AlarmKind.MARKET_CLOSE, AlarmKind.PLAN).forEach { k ->
            com.bamnat.app.ui.components.AddButton(k.label, onClick = { open(InvestSheet.AlarmEdit(k)) })
        }
    }
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth()) {
        listOf(AlarmKind.TARGET, AlarmKind.CUSTOM).forEach { k ->
            com.bamnat.app.ui.components.AddButton(k.label, onClick = { open(InvestSheet.AlarmEdit(k)) })
        }
    }
    val alarms = state.full.alarms.sortedWith(compareBy({ !it.enabled }, { it.minute }))
    if (alarms.isEmpty()) Text("아직 투자 알람이 없어요. 매달 25일 정기 매수나 장 마감 10분 전 알람부터 만들어 보세요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    alarms.forEach { a ->
        SectionCard(title = null) {
            Row(Modifier.fillMaxWidth().clickable { open(InvestSheet.AlarmEdit(editing = a)) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.title, style = MaterialTheme.typography.titleSmall)
                    Text(InvestAlarms.describe(a) + (if (a.ring) " · 소리" else "") +
                        (InvestAlarms.next(a, now)?.let { " · 다음 ${Fmt.shortDate(it.toLocalDate())}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = a.enabled, onCheckedChange = { vm.toggleAlarm(a, it) },
                    modifier = Modifier.semantics { contentDescription = "${a.title} 알람 ${if (a.enabled) "켜짐" else "꺼짐"}" })
            }
            if (a.kind == AlarmKind.PLAN) {
                val p = InvestPlans.progress(a, state.full.trades, state.today)
                val draft = InvestPlans.draft(a, state.full, state.today)
                Text(
                    listOfNotNull(
                        a.amount?.let { "한 번에 ${InvestFmt.short(it, state.hide)}" } ?: a.quantity?.let { "한 번에 ${InvestFmt.quantity(it)}${a.market.unit}" },
                        "지금까지 ${p.times}번 · ${InvestFmt.short(p.spent, state.hide)}",
                        if (p.doneThisPeriod) p.doneLabel else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!p.doneThisPeriod) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (InvestPlans.canSaveAtOnce(draft)) TextButton(onClick = { vm.recordPlanNow(a) }) {
                        Text("오늘 ${InvestFmt.quantity(draft.quantity ?: 0.0)}${draft.market.unit} ${if (draft.side == Side.SELL) "팔았어요" else "샀어요"}")
                    }
                    TextButton(onClick = { open(InvestSheet.TradeEdit(vm.draftFor(a.id, null))) }) { Text("기록 창 열기") }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("투자 일정", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        com.bamnat.app.ui.components.AddButton("일정 적기", onClick = { open(InvestSheet.EventEdit()) })
    }
    val events = state.full.events.filter { !it.date.isBefore(state.today.minusDays(7)) }.sortedBy { it.date }
    if (events.isEmpty()) Text("배당락일, 실적 발표, 청약 날짜를 적어 두면 달력에 점이 찍히고 그날 아침에 알려 드려요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    events.forEach { e ->
        Row(Modifier.fillMaxWidth().clickable { open(InvestSheet.EventEdit(editing = e)) }.padding(vertical = 8.dp)) {
            Text(Fmt.shortDate(e.date), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), modifier = Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Text("${e.kind.label} · ${e.label}", style = MaterialTheme.typography.bodyLarge)
                if (e.memo.isNotBlank()) Text(e.memo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (e.remind) Text("알림", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
