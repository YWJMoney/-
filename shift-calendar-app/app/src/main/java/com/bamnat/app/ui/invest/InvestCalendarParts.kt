package com.bamnat.app.ui.invest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.appViewModel
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.invest.CashKind
import com.bamnat.core.invest.InvestAlarms
import com.bamnat.core.invest.InvestStats
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.TradeDraft
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.math.abs

/** [INV-01] The investor calendar's view of one calendar: shared by its calendar, day and 투자 screens. */
@Composable
fun investViewModel(calendarId: String): InvestViewModel = appViewModel(key = "invest_$calendarId") { InvestViewModel(it, calendarId) }

/**
 * [INV-01][INV-14] What the investor's calendar cells show: each day tinted by what it earned (red a
 * gain, blue a loss, deeper the bigger), the amount under the date, a mark on days with trades and a
 * dot on dates written down to watch.
 */
class InvestCells(
    private val pnl: Map<LocalDate, Long>,
    private val trades: Map<LocalDate, Int>,
    private val events: Set<LocalDate>,
    private val journal: Set<LocalDate>,
    private val hide: Boolean,
) {
    private val scale = pnl.values.maxOfOrNull { abs(it) }?.coerceAtLeast(1) ?: 1

    fun pnl(date: LocalDate): Long? = pnl[date]
    fun trades(date: LocalDate): Int = trades[date] ?: 0
    fun hasEvent(date: LocalDate): Boolean = date in events

    /** "+12만" under the date; null on a day with no result. */
    fun footer(date: LocalDate): String? = pnl[date]?.takeIf { it != 0L }?.let { InvestFmt.signedShort(it, hide) }

    fun tint(date: LocalDate, profit: Color, loss: Color, dark: Boolean): Color? {
        val v = pnl[date]?.takeIf { it != 0L } ?: return null
        val strength = 0.35f + 0.65f * (abs(v).toFloat() / scale)
        return (if (v > 0) profit else loss).copy(alpha = strength * if (dark) 0.30f else 0.18f)
    }

    /** Said by TalkBack after the date. */
    fun describe(date: LocalDate): String? = listOfNotNull(
        trades(date).takeIf { it > 0 }?.let { "매매 ${it}건" },
        pnl[date]?.takeIf { it != 0L }?.let { (if (it > 0) "수익 " else "손실 ") + if (hide) "가림" else Fmt.won(abs(it)) },
        if (date in events) "투자 일정 있음" else null,
        if (date in journal) "일지 있음" else null,
    ).joinToString(", ").ifEmpty { null }

    companion object {
        fun of(filtered: InvestState): InvestCells = filtered.unfiltered.let { state -> InvestCells(
            pnl = state.daily,
            trades = state.trades.mapValues { it.value.size },
            events = state.full.events.map { it.date }.toSet(),
            journal = state.full.journal.filter { !it.isEmpty }.map { it.date }.toSet(),
            hide = state.hide,
        ) }
    }
}

/** [INV-01] A day with trades: a small chip with how many, like a job day's mark. */
@Composable
fun InvestDayMark(cells: InvestCells, date: LocalDate, modifier: Modifier = Modifier) {
    val n = cells.trades(date)
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.height(22.dp).clip(RoundedCornerShape(6.dp)).background(if (n > 0) scheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (n > 0) Text(if (n > 1) "매$n" else "매", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = scheme.onSecondaryContainer, maxLines = 1)
    }
}

/** [INV-14] A dot in the cell's corner for a date written down to watch. */
@Composable
fun InvestEventDot(modifier: Modifier = Modifier) {
    Box(modifier.padding(2.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
}

/** The footer's colour for a day's result. */
@Composable
fun investFooterColor(cells: InvestCells, date: LocalDate): Color = pnlColor(cells.pnl(date) ?: 0L)

/**
 * [INV-01][INV-06] Today on the investor's calendar: what today earned, 총자산 and 수익률, today's trades,
 * and the next alarm, with one tap to write a trade.
 */
@Composable
fun TodayInvestCard(vm: InvestViewModel, today: LocalDate, onTrade: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state = vm.state.collectAsStateWithLifecycle().value.unfiltered
    if (!state.loaded || state.full.isEmpty) return
    val s = state.fullResult.summary
    val pnl = state.daily[today] ?: 0L
    val trades = state.tradesOn(today)
    val hide = state.hide
    val now = LocalDateTime.now()
    val next = state.full.alarms.mapNotNull { a -> InvestAlarms.next(a, now)?.takeIf { it.toLocalDate() == today }?.let { a to it } }.minByOrNull { it.second }
    val events = state.full.events.filter { it.date == today }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onOpen).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("오늘 투자", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (trades.isEmpty() && pnl == 0L) "매매 없음" else InvestFmt.signed(pnl, hide),
                        style = MaterialTheme.typography.titleLarge.merge(NumberStyle), color = pnlColor(pnl), fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("총자산 ${InvestFmt.short(s.totalAssets, hide)}", style = MaterialTheme.typography.bodyMedium.merge(NumberStyle))
                    Text("수익률 ${InvestFmt.percent(s.simpleReturn)}", style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = rateColor(s.simpleReturn))
                }
            }
            if (trades.isNotEmpty()) Text(trades.take(3).joinToString(" · ") { "${it.title} ${it.side.label}" } + if (trades.size > 3) " 외 ${trades.size - 3}건" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            val notes = listOfNotNull(
                next?.let { (a, at) -> "${minuteText(at.hour * 60 + at.minute)} ${a.title}" },
                events.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label },
                if (InvestAlarms.marketClosed(today)) "오늘은 휴장일" else null,
            )
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onTrade) { Text("매매 기록") }
            }
        }
    }
}

/** [CAL-06][INV-06] The month on the investor's calendar in three figures: what it earned, 승률, 수익률. */
@Composable
fun InvestMonthBar(vm: InvestViewModel, month: YearMonth, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state = vm.state.collectAsStateWithLifecycle().value.unfiltered
    if (!state.loaded) return
    val stats = remember(state, month) { InvestStats.month(state.book, state.result, month) }
    val hide = state.hide
    val s = state.result.summary
    val figures = listOf(
        Triple("${month.monthValue}월 손익", InvestFmt.signedShort(stats.pnl, hide), pnlColor(stats.pnl)),
        Triple("승률", stats.winRate?.let { "${"%.0f".format(it * 100)}%" } ?: "–", MaterialTheme.colorScheme.onSurface),
        Triple("총 수익률", InvestFmt.percent(s.simpleReturn), rateColor(s.simpleReturn)),
    )
    val notes = listOf("매매 ${stats.trades}건", "매도 ${stats.sells}번", "원금 ${InvestFmt.short(s.principal, hide)}")
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(role = Role.Button, onClickLabel = "투자·수익 탭 열기", onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = figures.zip(notes).joinToString(", ") { (f, n) -> "${f.first} ${f.second} $n" }
            },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            figures.zip(notes).forEach { (f, n) ->
                Column(Modifier.weight(1f)) {
                    Text(f.first, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(f.second, style = MaterialTheme.typography.titleMedium.merge(NumberStyle), color = f.third, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(n, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** [INV-01] The day's 매매 line in the day sheet's heading. */
fun investDaySummary(filtered: InvestState, date: LocalDate): String {
    val state = filtered.unfiltered
    val trades = state.tradesOn(date)
    val pnl = state.daily[date]
    return listOfNotNull(
        trades.takeIf { it.isNotEmpty() }?.let { "매매 ${it.size}건" },
        pnl?.takeIf { it != 0L }?.let { InvestFmt.signed(it, state.hide) },
        state.cashOn(date).takeIf { it.isNotEmpty() }?.let { c -> c.joinToString(" ") { it.kind.label } },
        if (state.full.journalOn(date)?.isEmpty == false) "일지" else null,
    ).joinToString(" · ").ifEmpty { "기록 없음" }
}

/**
 * [INV-01][INV-03][INV-07] A day on the investor's calendar: its trades, deposits and dividends, the
 * journal and dates, each a tap from being written or changed.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun InvestDayContent(vm: InvestViewModel, date: LocalDate, onSheet: (InvestSheet) -> Unit) {
    val state = vm.state.collectAsStateWithLifecycle().value.unfiltered
    if (!state.loaded) return
    Column(Modifier.fillMaxWidth()) {
        state.tradesOn(date).forEach { t -> TradeLine(t, state) { onSheet(InvestSheet.TradeEdit(editing = t)) } }
        state.cashOn(date).forEach { c -> CashLine(c, state) { onSheet(InvestSheet.CashEdit(editing = c)) } }
        state.full.events.filter { it.date == date }.forEach { e ->
            Text("${e.kind.label} · ${e.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth().clickable { onSheet(InvestSheet.EventEdit(editing = e)) }.padding(vertical = 6.dp))
        }
        state.full.journalOn(date)?.takeIf { !it.isEmpty }?.let { j ->
            Text("일지 · " + listOfNotNull(j.grade?.label, j.review.ifBlank { j.market }.take(60).ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onSheet(InvestSheet.Journal(date)) }.padding(vertical = 6.dp))
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { onSheet(InvestSheet.TradeEdit(vm.draftFor(null, date).copy(side = Side.BUY))) }, label = { Text("매수") })
            AssistChip(onClick = { onSheet(InvestSheet.TradeEdit(vm.draftFor(null, date).copy(side = Side.SELL))) }, label = { Text("매도") })
            AssistChip(onClick = { onSheet(InvestSheet.CashEdit(CashKind.DEPOSIT, date)) }, label = { Text("입금·출금") })
            AssistChip(onClick = { onSheet(InvestSheet.CashEdit(CashKind.DIVIDEND, date)) }, label = { Text("배당") })
            AssistChip(onClick = { onSheet(InvestSheet.Journal(date)) }, label = { Text("일지") })
            AssistChip(onClick = { onSheet(InvestSheet.EventEdit(date)) }, label = { Text("일정") })
        }
    }
}

/** [INV-01] The day sheet's 매매 group: the day's records and its own sheets. */
@Composable
fun InvestDayGroupBody(calendarId: String, date: LocalDate) {
    val vm = investViewModel(calendarId)
    var sheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<InvestSheet?>(null) }
    InvestDayContent(vm, date) { sheet = it }
    sheet?.let { s -> InvestSheetHost(vm, s) { sheet = it } }
}

/** [INV-01] The summary line for the day sheet's 매매 group. */
@Composable
fun rememberInvestDaySummary(calendarId: String, date: LocalDate): String {
    val vm = investViewModel(calendarId)
    val state = vm.state.collectAsStateWithLifecycle().value.unfiltered
    return remember(state, date) { investDaySummary(state, date) }
}

/** [INV-03] The day's quick chip: a trade sheet for that day, from anywhere on the calendar. */
@Composable
fun InvestQuickTrade(calendarId: String, date: LocalDate, onDismiss: () -> Unit) {
    val vm = investViewModel(calendarId)
    val draft = remember(vm, date) { vm.draftFor(null, date) }
    InvestSheetHost(vm, InvestSheet.TradeEdit(TradeDraft(accountId = draft.accountId, date = date))) { s -> if (s == null) onDismiss() }
}
