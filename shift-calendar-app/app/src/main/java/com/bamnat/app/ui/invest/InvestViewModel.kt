package com.bamnat.app.ui.invest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.invest.invest
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.widget.WidgetRefresher
import com.bamnat.core.invest.AccountType
import com.bamnat.core.invest.CashFlow
import com.bamnat.core.invest.CashKind
import com.bamnat.core.invest.GoalProgress
import com.bamnat.core.invest.InvestAccount
import com.bamnat.core.invest.InvestAlarm
import com.bamnat.core.invest.InvestBook
import com.bamnat.core.invest.InvestCsv
import com.bamnat.core.invest.InvestEvent
import com.bamnat.core.invest.InvestGoal
import com.bamnat.core.invest.InvestPlans
import com.bamnat.core.invest.InvestReturns
import com.bamnat.core.invest.InvestSettings
import com.bamnat.core.invest.JournalEntry
import com.bamnat.core.invest.PeriodReturn
import com.bamnat.core.invest.Portfolio
import com.bamnat.core.invest.PortfolioResult
import com.bamnat.core.invest.Quote
import com.bamnat.core.invest.ReturnMethod
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.Trade
import com.bamnat.core.invest.TradeDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** [INV-05] The stretch a return is worked out over. 원금 대비 is always from the start. */
enum class ReturnPeriod(val label: String) { ALL("처음부터"), YEAR("올해"), MONTH("이번 달") }

/**
 * [INV-01..INV-21] One investor calendar as its screens read it: the book, and worked out from it the
 * holdings, each day's result, the goal and (for one account when chosen) the same again.
 */
data class InvestState(
    val loaded: Boolean,
    /** Every account: the lists that are edited. */
    val full: InvestBook,
    val fullResult: PortfolioResult,
    /** [INV-15] The chosen account only, or [full]. */
    val book: InvestBook,
    val result: PortfolioResult,
    /** [INV-01][INV-06] What each day earned, for the cells and the day. */
    val daily: Map<LocalDate, Long>,
    val trades: Map<LocalDate, List<Trade>>,
    val cash: Map<LocalDate, List<CashFlow>>,
    val account: Long?,
    val today: LocalDate,
    val goal: GoalProgress,
) {
    val settings: InvestSettings get() = full.settings
    val hide: Boolean get() = full.settings.hideAmounts

    fun tradesOn(d: LocalDate): List<Trade> = trades[d].orEmpty()
    fun cashOn(d: LocalDate): List<CashFlow> = cash[d].orEmpty()

    /** [INV-15] Every account together: the calendar's cells, month bar and day sheet do not follow the 투자 tab's filter. */
    val unfiltered: InvestState by lazy { if (account == null) this else of(full, null, today, loaded, fullResult) }

    companion object {
        val LOADING = of(InvestBook.EMPTY, null, LocalDate.now(), loaded = false)

        fun of(b: InvestBook, account: Long?, today: LocalDate, loaded: Boolean = true, built: PortfolioResult? = null): InvestState {
            val fullResult = built ?: Portfolio.build(b)
            val acc = account?.takeIf { id -> b.accounts.any { it.id == id } }
            val book = if (acc == null) b else b.copy(
                accounts = b.accounts.filter { it.id == acc },
                trades = b.trades.filter { it.accountId == acc },
                cash = b.cash.filter { it.accountId == acc },
                // Snapshots are of everything together; one account's 시간가중 goes by cost between days.
                snapshots = emptyList(),
            )
            val result = if (acc == null) fullResult else Portfolio.build(book)
            return InvestState(
                loaded = loaded, full = b, fullResult = fullResult, book = book, result = result,
                daily = Portfolio.dailyPnl(result, book.cash),
                trades = book.trades.sortedWith(Portfolio.ORDER).groupBy { it.date },
                cash = book.cash.sortedWith(compareBy({ it.minute ?: -1 }, { it.id })).groupBy { it.date },
                account = acc, today = today,
                goal = InvestGoal.progress(b.settings, fullResult.summary, today),
            )
        }
    }
}

class InvestViewModel(private val container: AppContainer, val calendarId: String) : ViewModel() {
    private val repo = container.invest(calendarId)
    private val today = MutableStateFlow(LocalDate.now())
    private val _account = MutableStateFlow<Long?>(null)
    private val _period = MutableStateFlow(ReturnPeriod.ALL)
    val period: StateFlow<ReturnPeriod> = _period.asStateFlow()

    val state: StateFlow<InvestState> = combine(repo.book, _account, today) { b, acc, t -> InvestState.of(b, acc, t) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, InvestState.LOADING)

    /** [INV-05] The three returns over the chosen stretch; 원금 대비 always over everything. */
    val returns: StateFlow<List<PeriodReturn>> = combine(state, _period) { s, p ->
        if (!s.loaded) return@combine emptyList()
        val first = InvestReturns.firstDay(s.book) ?: s.today
        val from = when (p) {
            ReturnPeriod.ALL -> first
            ReturnPeriod.YEAR -> maxOf(first, s.today.withDayOfYear(1))
            ReturnPeriod.MONTH -> maxOf(first, s.today.withDayOfMonth(1))
        }
        // [INV-05] Rows dated ahead count from their day, not as today's gain.
        val live = InvestReturns.liveSummary(s.book, s.today, s.result)
        ReturnMethod.entries.map { InvestReturns.of(it, s.book, from, s.today, s.today, live) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** [INV-32] Trades found in notifications, waiting for 확인. */
    val alerts: StateFlow<List<com.bamnat.core.invest.TradeAlerts.Pending>> = repo.pendingAlerts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val alertAuto: StateFlow<Boolean> = repo.alertAuto.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    /** Saved, deleted: said once, with 되돌리기 where it can be undone. */
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    init {
        // [INV-21] Each day the prices are complete, what everything is worth is kept for 시간가중.
        viewModelScope.launch {
            state.filter { it.loaded }
                .map { s -> InvestReturns.snapshotFor(s.today, InvestReturns.liveSummary(s.full, s.today, s.fullResult), s.fullResult.held.isNotEmpty()) to s.full.snapshots }
                .distinctUntilChanged()
                .collect { (snap, kept) ->
                    if (snap != null && kept.none { it == snap }) repo.saveSnapshot(snap)
                }
        }
    }

    fun refreshToday() {
        today.value = LocalDate.now()
    }

    fun setAccount(id: Long?) {
        _account.value = id
    }

    fun setPeriod(p: ReturnPeriod) {
        _period.value = p
    }

    /** Runs a snackbar's 되돌리기 in this screen's scope. */
    fun run(action: suspend () -> Unit) {
        viewModelScope.launch { action(); changed() }
    }

    private suspend fun changed() {
        // [INV-16] The home-screen line follows the main calendar's records.
        if (calendarId == com.bamnat.app.data.PersonalCalendar.MAIN) runCatching { WidgetRefresher.refresh(container.appContext) }
    }

    private fun say(message: String, undo: (suspend () -> Unit)? = null) {
        _messages.tryEmit(UndoMessage(message, undo = undo))
    }

    /** [INV-03][INV-08] A new trade's sheet, filled from the day, the last account and, from a notice, the alarm. */
    fun draftFor(alarmId: Long?, date: LocalDate?): TradeDraft {
        val s = state.value
        val alarm = alarmId?.let { id -> s.full.alarms.firstOrNull { it.id == id } }
        val base = alarm?.let { InvestPlans.draft(it, s.full, s.today) } ?: TradeDraft(accountId = s.account ?: s.settings.lastAccountId)
        return base.copy(date = date ?: base.date ?: s.today)
    }

    /**
     * [INV-02][INV-03] Where a line is saved: a new one goes to an open account (made when none is),
     * while a changed one stays in its own account, closed or not, as long as that account exists.
     */
    private suspend fun homeAccount(accountId: Long, editing: Boolean): Long {
        val account = state.value.full.account(accountId)
        return when {
            accountId == 0L || account == null -> repo.ensureAccount()
            editing -> accountId
            account.hidden -> repo.ensureAccount()
            else -> accountId
        }
    }

    // [INV-03][INV-20] Trades: saved, copied, changed and deleted with 되돌리기.
    fun saveTrade(t: Trade, before: Trade?) = viewModelScope.launch {
        val account = homeAccount(t.accountId, before != null)
        val id = repo.saveTrade(t.copy(accountId = account))
        changed()
        val what = "${t.title} ${t.side.label}"
        if (before == null) say("$what 적었어요") { repo.deleteTrade(id) }
        else say("$what 고쳤어요") { repo.saveTrade(before) }
    }

    // [INV-32] A found trade: written as it came, opened to fix first, or let go (with 되돌리기).
    fun confirmAlert(p: com.bamnat.core.invest.TradeAlerts.Pending) = viewModelScope.launch {
        val t = repo.confirmAlert(p)
        changed()
        say("${t.title} ${t.side.label} 적었어요") { repo.deleteTrade(t.id); repo.addAlert(p) }
    }

    fun dropAlert(p: com.bamnat.core.invest.TradeAlerts.Pending) = viewModelScope.launch {
        repo.dropAlert(p.at)
        say("${p.title} ${p.side.label} 알림을 넘겼어요") { repo.addAlert(p) }
    }

    /** The sheet saved the trade a found one opened; it no longer waits. */
    fun alertSaved(at: Long) = viewModelScope.launch { repo.dropAlert(at) }

    fun setAlertAuto(on: Boolean) = viewModelScope.launch {
        repo.setAlertAuto(on)
        say(if (on) "체결 알림이 오면 바로 적어요. 적은 뒤에 고칠 수 있어요" else "체결 알림은 확인한 뒤에 적어요")
    }

    fun deleteTrade(t: Trade) = viewModelScope.launch {
        repo.deleteTrade(t.id)
        changed()
        say("${t.title} ${t.side.label} 기록을 지웠어요") { repo.saveTrade(t) }
    }

    // [INV-02][INV-09] 입금·출금, 배당·이자, 기타 비용.
    fun saveCash(c: CashFlow, before: CashFlow?) = viewModelScope.launch {
        // [INV-05] 원금 as it will be, from the rows before this save (the state may or may not hold it yet).
        val principal = state.value.full.cash.sumOf { it.principalWon } - (before?.principalWon ?: 0) + c.principalWon
        val account = homeAccount(c.accountId, before != null)
        val id = repo.saveCash(c.copy(accountId = account))
        changed()
        val msg = when (c.kind) {
            CashKind.DEPOSIT, CashKind.WITHDRAW -> "${c.kind.label} 적었어요 · 원금 ${InvestFmt.won(principal, state.value.hide)}"
            else -> "${c.kind.label} 적었어요"
        }
        if (before == null) say(msg) { repo.deleteCash(id) } else say("${c.kind.label} 고쳤어요") { repo.saveCash(before) }
    }

    fun deleteCash(c: CashFlow) = viewModelScope.launch {
        repo.deleteCash(c.id)
        changed()
        say("${c.kind.label} 기록을 지웠어요") { repo.saveCash(c) }
    }

    // [INV-02] Accounts.
    fun saveAccount(a: InvestAccount) = viewModelScope.launch {
        val sort = if (a.id == 0L) (state.value.full.accounts.maxOfOrNull { it.sortOrder } ?: 0) + 1 else a.sortOrder
        val id = repo.saveAccount(a.copy(sortOrder = sort, name = a.name.trim().ifEmpty { a.type.label }))
        if (a.id == 0L) repo.saveSettings { it.copy(lastAccountId = id) }
    }

    fun deleteAccount(a: InvestAccount) = viewModelScope.launch {
        repo.deleteAccount(a.id)
        if (_account.value == a.id) _account.value = null
        say("‘${a.name}’ 계좌를 정리했어요") { repo.saveAccount(a) }
    }

    /** [INV-04][INV-19] Prices typed at once, with the dollar's rate. */
    fun saveQuotes(quotes: List<Quote>, removed: List<String>, usdKrw: Double?) = viewModelScope.launch {
        repo.saveQuotes(quotes, removed)
        if (usdKrw != null && usdKrw > 0) repo.saveSettings { it.copy(usdKrw = usdKrw) }
        changed()
        say("현재가를 바꿨어요. 평가손익과 수익률도 다시 계산했어요")
    }

    // [INV-07] The day's journal.
    fun saveJournal(j: JournalEntry) = viewModelScope.launch {
        repo.saveJournal(j)
        say(if (j.isEmpty) "일지를 비웠어요" else "${j.date.monthValue}월 ${j.date.dayOfMonth}일 일지를 적었어요")
    }

    // [INV-14] Dates to watch.
    fun saveEvent(e: InvestEvent) = viewModelScope.launch { repo.saveEvent(e) }

    fun deleteEvent(e: InvestEvent) = viewModelScope.launch {
        repo.deleteEvent(e.id)
        say("‘${e.label}’ 일정을 지웠어요") { repo.saveEvent(e) }
    }

    // [INV-08] Alarms and 정기 매수.
    fun saveAlarm(a: InvestAlarm) = viewModelScope.launch {
        repo.saveAlarm(a)
        say(if (a.enabled) "‘${a.title}’ ${com.bamnat.core.invest.InvestAlarms.describe(a)}에 알려 드려요" else "‘${a.title}’ 알람을 껐어요")
    }

    fun toggleAlarm(a: InvestAlarm, on: Boolean) = viewModelScope.launch { repo.saveAlarm(a.copy(enabled = on)) }

    fun deleteAlarm(a: InvestAlarm) = viewModelScope.launch {
        repo.deleteAlarm(a.id)
        say("‘${a.title}’ 알람을 지웠어요") { repo.saveAlarm(a) }
    }

    // [INV-05][INV-11][INV-17] The calendar's own choices.
    fun saveSettings(change: (InvestSettings) -> InvestSettings) = viewModelScope.launch {
        repo.saveSettings(change)
        changed()
    }

    /** [INV-02] The first account, named after its kind, so a deposit can be written at once. */
    fun addFirstAccount(type: AccountType = AccountType.GENERAL) = saveAccount(InvestAccount(name = type.label, type = type))

    /** [INV-10] The CSV of one kind of record, over the chosen account. */
    fun csv(kind: CsvKind): Pair<String, String> {
        val s = state.value
        return when (kind) {
            CsvKind.TRADES -> InvestCsv.trades(s.book, s.result) to InvestCsv.fileName("trades", null, null)
            CsvKind.CASH -> InvestCsv.cash(s.book) to InvestCsv.fileName("cash", null, null)
            CsvKind.HOLDINGS -> InvestCsv.holdings(s.result) to InvestCsv.fileName("holdings", null, null)
            CsvKind.JOURNAL -> InvestCsv.journal(s.full) to InvestCsv.fileName("journal", null, null)
        }
    }

    /** [INV-08] The plan's next trade, when the price is known: written in one tap from the list. */
    fun recordPlanNow(a: InvestAlarm): Boolean {
        val s = state.value
        val d = InvestPlans.draft(a, s.full, s.today)
        if (!InvestPlans.canSaveAtOnce(d)) return false
        val qty = d.quantity ?: return false
        val price = d.price ?: return false
        val account = d.accountId?.takeIf { s.full.account(it)?.hidden == false } ?: 0L
        val fees = com.bamnat.core.invest.InvestFees.suggest(d.market, d.side, qty, price, s.full.account(account))
        saveTrade(Trade(accountId = account, date = s.today, symbol = d.symbol, name = d.name, market = d.market, side = d.side,
            quantity = qty, price = price, fxRate = if (d.market.foreign) s.settings.usdKrw else 1.0, fee = fees.fee, tax = fees.tax,
            tags = listOf(if (a.kind == com.bamnat.core.invest.AlarmKind.PLAN) "정기 적립" else a.kind.label), alarmId = a.id), null)
        return true
    }

    /** Holdings the user had, for the trade sheet's one-tap picks: held first, then the rest. */
    fun knownHoldings(): List<com.bamnat.core.invest.Holding> = state.value.fullResult.byKey

    /** Whether a sell of [qty] is more than is held there, to warn before saving. */
    fun heldIn(accountId: Long, key: String): Double =
        state.value.fullResult.holdings.firstOrNull { it.accountId == accountId && it.key == key }?.quantity ?: 0.0
}

enum class CsvKind(val label: String) { TRADES("매매 기록"), CASH("입출금·배당"), HOLDINGS("보유 종목"), JOURNAL("투자일지") }
