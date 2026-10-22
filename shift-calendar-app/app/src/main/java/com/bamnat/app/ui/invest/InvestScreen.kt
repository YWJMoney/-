package com.bamnat.app.ui.invest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.NoticeLinks
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.components.WarningBanner
import com.bamnat.app.ui.money.LedgerExport
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.invest.CashKind
import com.bamnat.core.invest.InvestAlarms
import com.bamnat.core.invest.PeriodReturn
import com.bamnat.core.invest.ReturnMethod
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.TradeDraft
import kotlinx.coroutines.launch
import java.time.LocalDateTime

private enum class InvestTab(val label: String) { OVERVIEW("한눈에"), HOLDINGS("종목"), RECORDS("기록"), STATS("통계"), JOURNAL("일지"), ALARMS("알람") }

/**
 * [INV-01..INV-21] The investor calendar's own tab (투자·수익): what everything is worth against what
 * was put in, the holdings, every record, the numbers, the journal and the alarms. Everything is
 * typed by the user and kept on the phone.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InvestScreen(vm: InvestViewModel, wide: Boolean, snackbar: SnackbarHostState) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(InvestTab.OVERVIEW) }
    // Another calendar's screen starts with no sheet of this one's open.
    var sheet by remember(vm.calendarId) { mutableStateOf<InvestSheet?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshToday() }
    InvestMessages(vm, snackbar)

    // [INV-08] A tapped alarm notice opens its trade, filled in.
    val target by NoticeLinks.target.collectAsStateWithLifecycle()
    val payload by NoticeLinks.payload.collectAsStateWithLifecycle()
    val noticeCalendar by NoticeLinks.calendar.collectAsStateWithLifecycle()
    // [MCAL-03] Only the notice's own calendar takes it; another's waits for the switch to that one.
    val mine = (noticeCalendar ?: com.bamnat.app.data.PersonalCalendar.MAIN) == vm.calendarId
    LaunchedEffect(target, state.loaded, mine) {
        if (!mine) return@LaunchedEffect
        when (target) {
            NoticeLinks.Target.INVEST_TRADE -> if (state.loaded) {
                sheet = InvestSheet.TradeEdit(vm.draftFor(payload?.toLongOrNull(), null))
                NoticeLinks.consume(NoticeLinks.Target.INVEST_TRADE)
            }
            NoticeLinks.Target.INVEST -> NoticeLinks.consume(NoticeLinks.Target.INVEST)
            else -> Unit
        }
    }

    // [GUIDE-01][GUIDE-04] A feature opened from the guide or the help search: its tab, or its sheet filled in.
    val focus by com.bamnat.app.ui.guide.FeatureLinks.focus.collectAsStateWithLifecycle()
    LaunchedEffect(focus, state.loaded) {
        val key = focus ?: return@LaunchedEffect
        if (!state.loaded || !key.startsWith("invest.") || !com.bamnat.app.ui.guide.FeatureLinks.take(key)) return@LaunchedEffect
        com.bamnat.app.ui.guide.GuideUse.focus(com.bamnat.app.data.GuideStore.get(context), key)
        when (key) {
            "invest.cash" -> { tab = InvestTab.OVERVIEW; sheet = InvestSheet.CashEdit(CashKind.DEPOSIT) }
            "invest.dividend" -> sheet = InvestSheet.CashEdit(CashKind.DIVIDEND)
            "invest.trade" -> sheet = InvestSheet.TradeEdit(TradeDraft(side = Side.BUY))
            "invest.quotes" -> { tab = InvestTab.HOLDINGS; sheet = InvestSheet.Quotes }
            "invest.goal" -> { tab = InvestTab.OVERVIEW; sheet = InvestSheet.Goal }
            "invest.journal" -> { tab = InvestTab.JOURNAL; sheet = InvestSheet.Journal(state.today) }
            "invest.event" -> { tab = InvestTab.OVERVIEW; sheet = InvestSheet.EventEdit(state.today) }
            "invest.alarms" -> tab = InvestTab.ALARMS
            "invest.stats" -> tab = InvestTab.STATS
            "invest.records" -> tab = InvestTab.RECORDS
            "invest.overview" -> tab = InvestTab.OVERVIEW
        }
    }

    if (!state.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val open: (InvestSheet) -> Unit = { sheet = it }
    Column(Modifier.fillMaxSize()) {
        InvestHeader(vm, state, open) { kind ->
            val (csv, name) = vm.csv(kind)
            scope.launch { LedgerExport.share(context, csv, name) }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 12.dp) {
            InvestTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                InvestTab.OVERVIEW -> OverviewTab(vm, state, open)
                InvestTab.HOLDINGS -> HoldingsTab(state, open)
                InvestTab.RECORDS -> RecordsTab(state, open)
                InvestTab.STATS -> InvestStatsTab(state, wide, vm.calendarId)
                InvestTab.JOURNAL -> JournalTab(state, open)
                InvestTab.ALARMS -> AlarmsTab(vm, state, open)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    sheet?.let { s -> InvestSheetHost(vm, s) { sheet = it } }
}

/** Saved and deleted, with 되돌리기, on the screen's snackbar. */
@Composable
fun InvestMessages(vm: InvestViewModel, snackbar: SnackbarHostState) {
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            launch {
                val r = snackbar.showSnackbar(m.message, actionLabel = m.undo?.let { m.actionLabel }, duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) m.undo?.let { vm.run(it) }
            }
        }
    }
}

@Composable
private fun InvestHeader(vm: InvestViewModel, state: InvestState, open: (InvestSheet) -> Unit, onCsv: (CsvKind) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("투자·수익", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
        // [INV-17] Amounts behind dots, for looking at it on the bus.
        IconButton(onClick = { vm.saveSettings { it.copy(hideAmounts = !it.hideAmounts) } }) {
            Icon(if (state.hide) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (state.hide) "금액 보이기" else "금액 가리기")
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "더 보기") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("목표와 수익률") }, onClick = { menu = false; open(InvestSheet.Goal) })
                DropdownMenuItem(text = { Text("내 투자 원칙") }, onClick = { menu = false; open(InvestSheet.Principles) })
                DropdownMenuItem(text = { Text("계좌 추가") }, onClick = { menu = false; open(InvestSheet.AccountEdit()) })
                // [INV-32] Found trades written at once or after 확인, also when none is waiting.
                val auto by vm.alertAuto.collectAsStateWithLifecycle()
                DropdownMenuItem(text = { Text(if (auto) "체결 알림 바로 적기 끄기" else "체결 알림 오면 바로 적기") },
                    onClick = { menu = false; vm.setAlertAuto(!auto) })
                CsvKind.entries.forEach { k ->
                    DropdownMenuItem(text = { Text("${k.label} CSV로 내보내기") }, onClick = { menu = false; onCsv(k) })
                }
            }
        }
    }
    // [INV-15] All accounts together, or one.
    val accounts = state.full.activeAccounts
    if (accounts.size > 1) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = state.account == null, onClick = { vm.setAccount(null) }, label = { Text("전체") })
            accounts.forEach { a -> FilterChip(selected = state.account == a.id, onClick = { vm.setAccount(a.id) }, label = { Text(a.name) }) }
        }
    }
}

@Composable
private fun OverviewTab(vm: InvestViewModel, state: InvestState, open: (InvestSheet) -> Unit) {
    val s = state.result.summary
    val hide = state.hide
    if (state.full.isEmpty) {
        SectionCard(title = "원금부터 적어 보세요") {
            Text("계좌에 넣은 돈(입금)을 적으면 원금이 정해지고, 매매를 적을 때마다 수익률·현금·목표가 바로 바뀌어요.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { open(InvestSheet.CashEdit(CashKind.DEPOSIT)) }) { Text("입금 적기") }
                TextButton(onClick = { open(InvestSheet.AccountEdit()) }) { Text("계좌부터 만들기") }
            }
        }
    }
    FoundTradesCard(vm, state, open)
    SummaryCard(vm, state, open)
    QuickActions(open)
    if (s.missingQuotes.isNotEmpty()) WarningBanner(
        title = "현재가를 적지 않은 종목이 ${s.missingQuotes.size}개 있어요",
        message = s.missingQuotes.take(3).joinToString(", ") + "은(는) 산 가격으로 셌어요. 평가손익을 보려면 현재가를 적어 주세요.",
        action = "현재가 적기", onAction = { open(InvestSheet.Quotes) },
    )
    if (s.cashShort) WarningBanner(
        title = "현금이 ${InvestFmt.won(-s.cash, hide)} 모자라요",
        message = "산 돈보다 넣은 돈이 적게 적혀 있어요. 입금을 적으면 원금과 수익률이 맞아요.",
        action = "입금 적기", onAction = { open(InvestSheet.CashEdit(CashKind.DEPOSIT)) },
    )
    // [GUIDE-05] Cards that can be turned off for this calendar.
    com.bamnat.app.ui.guide.FeatureCard("invest.goal", vm.calendarId) { GoalCard(state, open) }
    TodayCard(state, open)
    if (state.account == null && state.full.activeAccounts.isNotEmpty()) AccountsCard(state, open)
    com.bamnat.app.ui.guide.FeatureCard("invest.history", vm.calendarId) { HistoryCard(state) }
    com.bamnat.app.ui.guide.FeatureCard("invest.events", vm.calendarId) { UpcomingCard(state, open) }
}

/** [INV-05] 총자산, 원금, 손익 and 수익률 by the chosen method, with what the method means. */
@Composable
private fun SummaryCard(vm: InvestViewModel, state: InvestState, open: (InvestSheet) -> Unit) {
    val s = state.result.summary
    val hide = state.hide
    val returns by vm.returns.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val method = state.settings.returnMethod
    val chosen: PeriodReturn? = returns.firstOrNull { it.method == method }
    SectionCard(title = state.account?.let { id -> state.full.account(id)?.name } ?: "내 투자") {
        Text("총자산", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(InvestFmt.won(s.totalAssets, hide), style = MaterialTheme.typography.headlineMedium.merge(NumberStyle), fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { contentDescription = "총자산 ${InvestFmt.won(s.totalAssets, hide)}" })
        val rate = chosen?.rate
        Text(
            "${InvestFmt.signed(if (method == ReturnMethod.SIMPLE) s.pnl else chosen?.pnl ?: s.pnl, hide)} (${InvestFmt.percent(rate)})",
            style = MaterialTheme.typography.titleMedium.merge(NumberStyle), color = rateColor(rate), fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("원금", InvestFmt.short(s.principal, hide), Modifier.weight(1f), caption = "입금 − 출금",
                onClick = { open(InvestSheet.CashEdit(CashKind.DEPOSIT)) })
            StatTile("현금", InvestFmt.short(s.cash, hide), Modifier.weight(1f), warning = s.cashShort)
            StatTile("평가손익", InvestFmt.signedShort(s.unrealized, hide), Modifier.weight(1f),
                caption = if (s.missingQuotes.isEmpty()) "현재가 기준" else "일부 매입가")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("실현손익", InvestFmt.signedShort(s.realized, hide), Modifier.weight(1f))
            StatTile("배당·이자", InvestFmt.short(s.income, hide), Modifier.weight(1f), caption = "세후")
            StatTile("수수료·세금", InvestFmt.short(s.fees + s.taxes, hide), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text("수익률 계산", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReturnMethod.entries.forEach { m ->
                val r = returns.firstOrNull { it.method == m }
                FilterChip(selected = m == method, onClick = { vm.saveSettings { it.copy(returnMethod = m) } },
                    label = { Text("${m.label} ${InvestFmt.percent(r?.rate)}") })
            }
        }
        if (method != ReturnMethod.SIMPLE) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReturnPeriod.entries.forEach { p -> FilterChip(selected = p == period, onClick = { vm.setPeriod(p) }, label = { Text(p.label) }) }
        }
        Text(method.explain + if (method == ReturnMethod.SIMPLE) " 기간과 상관없이 처음부터 계산해요." else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.account != null && method == ReturnMethod.TIME_WEIGHTED) Text("계좌 하나만 볼 때는 날마다의 평가액이 없어 산 가격으로 이어 계산해요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One tap to the records an investor writes most. */
@Composable
private fun QuickActions(open: (InvestSheet) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { open(InvestSheet.TradeEdit(TradeDraft(side = Side.BUY))) }) { Text("매수") }
        FilledTonalButton(onClick = { open(InvestSheet.TradeEdit(TradeDraft(side = Side.SELL))) }) { Text("매도") }
        AssistChip(onClick = { open(InvestSheet.CashEdit(CashKind.DEPOSIT)) }, label = { Text("입금") })
        AssistChip(onClick = { open(InvestSheet.CashEdit(CashKind.WITHDRAW)) }, label = { Text("출금") })
        AssistChip(onClick = { open(InvestSheet.CashEdit(CashKind.DIVIDEND)) }, label = { Text("배당") })
        AssistChip(onClick = { open(InvestSheet.Quotes) }, label = { Text("현재가") })
    }
}

/** [INV-11] How near the goal is, and when it may be reached. */
@Composable
private fun GoalCard(state: InvestState, open: (InvestSheet) -> Unit) {
    val g = state.goal
    val hide = state.hide
    if (!g.hasGoal) {
        TextButton(onClick = { open(InvestSheet.Goal) }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("목표 정하기 (금액·수익률)") }
        return
    }
    SectionCard(title = "목표", trailing = { TextButton(onClick = { open(InvestSheet.Goal) }) { Text("고치기") } }) {
        g.amountRatio?.let { r ->
            Text("${InvestFmt.short(state.settings.goalAmount ?: 0, hide)} 중 ${"%.0f".format(r * 100)}%", style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { r.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        g.returnRatio?.let { r ->
            Text("수익률 ${state.settings.goalReturnPercent?.let { InvestFmt.quantity(it) }}% 목표의 ${"%.0f".format(r.coerceAtLeast(0.0))}%", style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { (r / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        val notes = listOfNotNull(
            g.amountLeft?.takeIf { it > 0 }?.let { "남은 ${InvestFmt.short(it, hide)}" },
            g.monthsLeft?.let { if (it == 0) "목표에 닿았어요!" else "매달 넣으면 약 ${it}개월" },
            g.perMonthToDate?.let { "기한까지 매달 ${InvestFmt.short(it, hide)}" },
        )
        if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [INV-06][INV-07] Today: what it earned, its trades and the journal. */
@Composable
private fun TodayCard(state: InvestState, open: (InvestSheet) -> Unit) {
    val today = state.today
    val pnl = state.daily[today] ?: 0L
    val trades = state.tradesOn(today)
    val journal = state.full.journalOn(today)
    SectionCard(title = "오늘 · ${Fmt.dayTitle(today)}", trailing = {
        TextButton(onClick = { open(InvestSheet.Journal(today)) }) { Text(if (journal == null) "일지 쓰기" else "일지 보기") }
    }) {
        Text(if (trades.isEmpty() && pnl == 0L) "오늘은 아직 매매가 없어요" else "오늘 손익 ${InvestFmt.signed(pnl, state.hide)} · 매매 ${trades.size}건",
            style = MaterialTheme.typography.bodyLarge, color = pnlColor(pnl))
        trades.take(4).forEach { t -> TradeLine(t, state, onClick = { open(InvestSheet.TradeEdit(editing = t)) }) }
        if (InvestAlarms.marketClosed(today)) Text("오늘은 국내 증시가 쉬는 날이에요", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [INV-15] Each account's own 원금, 총자산 and 수익률. */
@Composable
private fun AccountsCard(state: InvestState, open: (InvestSheet) -> Unit) {
    val hide = state.hide
    SectionCard(title = "계좌별", trailing = { TextButton(onClick = { open(InvestSheet.AccountEdit()) }) { Text("계좌 추가") } }) {
        state.fullResult.accounts.filter { it.account?.hidden != true }.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clickable { a.account?.let { open(InvestSheet.AccountEdit(it)) } }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.account?.name ?: "계좌", style = MaterialTheme.typography.bodyLarge)
                    Text("원금 ${InvestFmt.short(a.principal, hide)} · 현금 ${InvestFmt.short(a.cash, hide)}" + (a.account?.type?.let { " · ${it.label}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(InvestFmt.short(a.totalAssets, hide), style = MaterialTheme.typography.bodyLarge.merge(NumberStyle))
                    Text(InvestFmt.percent(a.rate), style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = rateColor(a.rate))
                }
            }
        }
    }
}

/** [INV-21] 총자산 against 원금 over the days a value was kept. */
@Composable
private fun HistoryCard(state: InvestState) {
    val snaps = state.full.snapshots.sortedBy { it.date }.takeLast(120)
    if (state.account != null || snaps.size < 2) return
    val profit = LocalCalendarColorsSunday()
    val line = MaterialTheme.colorScheme.outline
    SectionCard(title = "총자산 흐름") {
        val max = snaps.maxOf { maxOf(it.value, it.principal) }.coerceAtLeast(1)
        val min = snaps.minOf { minOf(it.value, it.principal) }.coerceAtMost(max - 1)
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics {
            contentDescription = "${Fmt.shortDate(snaps.first().date)}부터 ${Fmt.shortDate(snaps.last().date)}까지 총자산 ${InvestFmt.short(snaps.first().value, state.hide)}에서 ${InvestFmt.short(snaps.last().value, state.hide)}"
        }) {
            fun pt(i: Int, v: Long) = Offset(size.width * i / (snaps.size - 1), size.height * (1f - (v - min).toFloat() / (max - min)))
            for (i in 1 until snaps.size) {
                drawLine(line, pt(i - 1, snaps[i - 1].principal), pt(i, snaps[i].principal), strokeWidth = 2f)
                drawLine(profit, pt(i - 1, snaps[i - 1].value), pt(i, snaps[i].value), strokeWidth = 4f)
            }
            drawCircle(profit, 6f, pt(snaps.size - 1, snaps.last().value), style = Stroke(3f))
        }
        Text("굵은 선은 총자산, 가는 선은 원금이에요. 현재가를 모두 적은 날의 값이 남아요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LocalCalendarColorsSunday() = com.bamnat.app.ui.theme.LocalCalendarColors.current.sunday

/** [INV-08][INV-14] The next alarm and the dates coming up this week. */
@Composable
private fun UpcomingCard(state: InvestState, open: (InvestSheet) -> Unit) {
    val now = LocalDateTime.now()
    val next = state.full.alarms.mapNotNull { a -> InvestAlarms.next(a, now)?.let { a to it } }.minByOrNull { it.second }
    val events = state.full.events.filter { !it.date.isBefore(state.today) && it.date.isBefore(state.today.plusDays(8)) }.sortedBy { it.date }
    if (next == null && events.isEmpty()) return
    SectionCard(title = "다가오는 일") {
        next?.let { (a, at) ->
            Text("${a.title} · ${Fmt.dayTitle(at.toLocalDate())} ${minuteText(at.hour * 60 + at.minute)}", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable { open(InvestSheet.AlarmEdit(editing = a)) }.padding(vertical = 4.dp))
        }
        events.forEach { e ->
            Text("${Fmt.shortDate(e.date)} ${e.kind.label} · ${e.label}", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable { open(InvestSheet.EventEdit(editing = e)) }.padding(vertical = 4.dp))
        }
    }
}
