package com.bamnat.app.ui.money

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.NoticeLinks
import com.bamnat.app.appContainer
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.AutoRecordPrefs
import com.bamnat.app.data.Capture
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.tier.Gate
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.money.AccountType
import com.bamnat.core.money.BudgetLine
import com.bamnat.core.money.Budgets
import com.bamnat.core.money.CaptureFilter
import com.bamnat.core.money.CategoryEdit
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.Job
import com.bamnat.core.money.Ledger
import com.bamnat.core.money.LedgerFilter
import com.bamnat.core.money.LedgerPeriod
import com.bamnat.core.money.MoneyAccount
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneyStats
import com.bamnat.core.money.MonthMoney
import com.bamnat.core.money.OtherPlaces
import com.bamnat.core.money.RepeatFinder
import com.bamnat.core.money.Reports
import com.bamnat.core.money.Upcoming
import com.bamnat.core.money.UpcomingPayments
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

class MoneyViewModel(
    private val container: AppContainer,
    /** [MCAL-06] The calendar whose money this is: the one on the calendar tab. */
    val calendarId: String = com.bamnat.app.data.PersonalCalendar.MAIN,
) : ViewModel(), MoneyActions {
    private val scope = container.scopeOf(calendarId)
    /** [NAV-01] The month shown here, the calendar's and 근무·급여's too. */
    val shownMonth: StateFlow<YearMonth> = container.shownMonth.month
    fun showMonth(month: YearMonth) = container.shownMonth.show(month)
    private val money = scope.money
    val book: StateFlow<MoneyBook?> = money.book.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val snapshot: StateFlow<ScheduleSnapshot?> =
        scope.schedule.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = scope.settings.stateIn(viewModelScope, SharingStarted.Eagerly, scope.currentSettings())
    val payProfile: StateFlow<PayProfile?> = scope.pay.profile
    val gate: StateFlow<Gate> = container.featureGate.state
    private val payCalculator = PayCalculator()

    init {
        // [LEDG-09] Repeating lines set to write themselves are written when the money tab opens.
        container.appScope.launch { runCatching { money.catchUpRecurring(LocalDate.now()) } }
    }

    /** [LEDG-15] The money tab's three pages. */
    enum class Page(val label: String) { SUMMARY("요약"), LIST("내역"), REPORT("분석") }

    private val _page = MutableStateFlow(Page.SUMMARY)
    val page: StateFlow<Page> = _page.asStateFlow()
    fun showPage(p: Page) { _page.value = p }

    private val _view = MutableStateFlow(LedgerView())
    /** [LEDG-07] The 내역 list's period, conditions and order, kept while the user moves between pages. */
    val view: StateFlow<LedgerView> = _view.asStateFlow()
    fun setView(v: LedgerView) { _view.value = v }

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()
    fun select(ids: Set<Long>) { _selection.value = ids }

    /** [LEDG-10] From 분석 to the lines behind a category or a place, in the month on screen. */
    fun showCategory(category: String) {
        _view.value = LedgerView(period = LedgerPeriod.MONTH, filter = LedgerFilter(categories = setOf(category)))
        _page.value = Page.LIST
    }

    fun showPlace(place: String) {
        _view.value = LedgerView(period = LedgerPeriod.MONTH, filter = LedgerFilter(query = place))
        _page.value = Page.LIST
    }

    /** [WORK-04] Wages come from the existing pay engine; null when it is not set up. [MON-01] Free for everyone. */
    fun payEstimate(style: WorkStyle?, profile: PayProfile?, snapshot: ScheduleSnapshot?, month: YearMonth): Long? {
        if (style?.usesPayEngine == false || profile == null || snapshot == null) return null
        return runCatching { payCalculator.calculate(profile, month, snapshot.resolver::resolve).total }.getOrNull()
    }

    /** The month with its wage estimate, off the main thread. */
    suspend fun month(book: MoneyBook, style: WorkStyle?, profile: PayProfile?, snapshot: ScheduleSnapshot?, month: YearMonth): MonthMoney =
        withContext(Dispatchers.Default) { book.month(month, payEstimate(style, profile, snapshot, month)) }

    /**
     * [LEDG-10] The month against the one before, and its year month by month, each with that
     * month's wage estimate. Months still ahead are counted without one.
     */
    suspend fun analysis(book: MoneyBook, style: WorkStyle?, profile: PayProfile?, snapshot: ScheduleSnapshot?,
                         month: YearMonth, today: LocalDate): LedgerAnalysis = withContext(Dispatchers.Default) {
        val now = YearMonth.from(today)
        val pays = HashMap<YearMonth, Long?>()
        val at = { m: YearMonth -> book.month(m, if (m.isAfter(now)) null else pays.getOrPut(m) { payEstimate(style, profile, snapshot, m) }) }
        val current = at(month)
        val year = (1..12).map { at(YearMonth.of(month.year, it)) }
        LedgerAnalysis(current, Reports.month(current, at(month.minusMonths(1)), book, today), Reports.year(month.year, year))
    }

    private val _undo = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val undo: SharedFlow<UndoMessage> = _undo.asSharedFlow()

    override val ledger: MoneyBook? get() = book.value

    override fun saveEntry(entry: MoneyEntry) {
        val before = book.value
        val old = before?.entries?.firstOrNull { it.id == entry.id && entry.id != 0L }
        container.appScope.launch {
            money.save(entry)
            // [LEDG-18] The other lines from this place can follow in one tap.
            val same = old?.let { money.samePlace(entry, it.category) }.orEmpty()
            if (same.isNotEmpty()) {
                _undo.tryEmit(UndoMessage("같은 곳 기록 ${same.size}건도 ‘${entry.category}’(으)로 바꿀까요?", actionLabel = "바꾸기") {
                    recategorize(same, entry.category)
                })
            }
            // [LEDG-06] Said once, when this line is the one that pushes a budget over.
            val crossed = before?.let { Budgets.crossedBy(entry, it.budgets, it.entries, LocalDate.now()) }.orEmpty()
            if (crossed.isNotEmpty()) _undo.tryEmit(UndoMessage(overText(crossed), undo = null))
        }
    }

    private fun overText(crossed: List<BudgetLine>): String {
        val names = crossed.map { if (it.isTotal) "이번 달" else it.category }
        val first = crossed.first()
        return if (crossed.size == 1) "${names.single()} 예산을 ${Fmt.won(-first.left)} 넘었어요" else "${names.joinToString("·")} 예산을 넘었어요"
    }

    override fun deleteEntry(entry: MoneyEntry) = delete(listOf(entry))

    /** [LEDG-07] Removes lines, with one undo for all of them. */
    fun delete(list: List<MoneyEntry>) {
        if (list.isEmpty()) return
        container.appScope.launch {
            money.deleteAll(list)
            val text = if (list.size == 1) "‘${list.single().title}’ 기록을 지웠어요" else "기록 ${list.size}건을 지웠어요"
            _undo.tryEmit(UndoMessage(text) { money.saveAll(list) })
        }
    }

    /** [LEDG-07] A copy of a line on today, as typed by hand. */
    fun duplicate(e: MoneyEntry) {
        container.appScope.launch {
            val saved = money.save(Ledger.duplicate(e, LocalDate.now()))
            _undo.tryEmit(UndoMessage("‘${e.title}’ 기록을 오늘로 복사했어요") { money.delete(saved) })
        }
    }

    /** [LEDG-07] Lines moved to [category]; lines whose kind has no such category stay as they are. */
    fun recategorize(list: List<MoneyEntry>, category: String) {
        val b = book.value ?: return
        val moved = list.filter { category in b.categoriesOf(it.kind) && (it.category != category || it.parts.isNotEmpty()) }
        if (moved.isEmpty()) return
        container.appScope.launch {
            // One at a time, so a place moved to another category is learned [LEDG-04].
            moved.forEach { money.save(Ledger.recategorize(it, category)) }
            _undo.tryEmit(UndoMessage("${moved.size}건을 ‘$category’(으)로 옮겼어요") { money.saveAll(moved) })
        }
    }

    fun message(text: String) { _undo.tryEmit(UndoMessage(text, undo = null)) }

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

    /** [COMN-04] Lines in 확인 대기함 confirmed, so they count. */
    fun confirm(list: List<MoneyEntry>) { container.appScope.launch { money.confirm(list) } }

    /** [LEDG-22][LEDG-24] How 결제 알림으로 적기 was tuned, and repeats turned down. */
    val autoRecord: StateFlow<AutoRecordPrefs.State> = container.autoRecordPrefs.state
    fun setCaptureFilter(filter: CaptureFilter) = container.autoRecordPrefs.setFilter(filter)

    /** [LEDG-21] A rule written or changed on the rules page. */
    fun setRule(merchant: String, category: String) { container.appScope.launch { money.setRule(merchant, category) } }
    fun deleteRule(merchant: String) {
        val old = rules.value[merchant] ?: return
        container.appScope.launch {
            money.deleteRule(merchant)
            _undo.tryEmit(UndoMessage("규칙을 지웠어요") { money.setRule(merchant, old) })
        }
    }

    /** [LEDG-24] A payment seen every month becomes a 반복 지출; 아니에요 is remembered. */
    fun addRepeat(s: RepeatFinder.Suggestion) {
        container.appScope.launch {
            val saved = money.save(s.toFixedCost())
            _undo.tryEmit(UndoMessage("‘${saved.name}’을 반복 지출로 등록했어요") { money.delete(saved) })
        }
    }
    fun notRepeating(s: RepeatFinder.Suggestion) = container.autoRecordPrefs.notRepeating(s.key)
    fun finishCapture(capture: Capture, entry: MoneyEntry) {
        container.appScope.launch { money.save(entry); money.dismissCapture(capture.id) }
    }
    fun dropCapture(capture: Capture) { container.appScope.launch { money.dismissCapture(capture.id) } }
    fun saveFixed(cost: FixedCost) {
        container.appScope.launch {
            money.save(cost)
            // [LEDG-09] One set to write itself catches up at once, so a line due today shows now.
            if (cost.auto) money.catchUpRecurring(LocalDate.now())
        }
    }
    fun deleteFixed(cost: FixedCost) {
        container.appScope.launch {
            money.delete(cost)
            _undo.tryEmit(UndoMessage("‘${cost.name}’ 반복을 지웠어요") { money.save(cost) })
        }
    }
    fun recordFixed(u: Upcoming, today: LocalDate) {
        container.appScope.launch {
            // Paid early is still this period's payment; it is dated today, or the due day when that has passed.
            val saved = money.save(UpcomingPayments.entry(u, if (u.date.isBefore(today)) u.date else today))
            _undo.tryEmit(UndoMessage("‘${u.cost.name}’ ${Fmt.won(u.cost.amount)}을 적었어요") { money.delete(saved) })
        }
    }

    /** [LEDG-08] Saves an account. */
    fun saveAccount(a: MoneyAccount) { container.appScope.launch { money.save(a) } }

    /** [LEDG-08] Removes an account, or puts it away when lines use it; either can be undone. */
    fun removeAccount(a: MoneyAccount) {
        container.appScope.launch {
            val removed = money.remove(a)
            _undo.tryEmit(
                if (removed) UndoMessage("‘${a.name}’을 지웠어요") { money.save(a) }
                else UndoMessage("기록이 있어서 ‘${a.name}’은 숨겼어요") { money.save(a.copy(hidden = false)) },
            )
        }
    }

    /** [LEDG-06] Saves the month's budget and each category's; 0 removes one. */
    fun saveBudgets(values: Map<String, Long>) {
        container.appScope.launch {
            values.forEach { (c, v) -> money.setBudget(c, v) }
            _undo.tryEmit(UndoMessage("예산을 저장했어요", undo = null))
        }
    }

    /** [LEDG-14] Renames first (taking their lines along), then the order and hidden ones. */
    fun saveCategories(kind: MoneyKind, edits: List<CategoryEdit>, renames: List<Pair<String, String>>) {
        container.appScope.launch {
            renames.forEach { (from, to) -> money.renameCategory(kind, from, to) }
            money.saveCategories(kind, edits)
            _undo.tryEmit(UndoMessage("${kind.label} 분류를 저장했어요", undo = null))
        }
    }

    fun runUndo(message: UndoMessage) { message.undo?.let { u -> container.appScope.launch { u() } } }
    override fun saveSales(sales: DailySales) {
        container.appScope.launch {
            money.save(sales)
            // [TYPE-04] Sales on a day closed by itself open it again, from 돈 as from the calendar.
            com.bamnat.app.ui.work.ShopKeeper.salesSaved(container, calendarId, sales.date, sales.sales)
        }
    }
}

/**
 * [LEDG-01][WORK-04][TIER-05][LEDG-15] The money tab: 요약 (the month, today's budget, what is
 * coming, accounts), 내역 (every line, found and changed in bulk) and 분석 (where it went and how
 * the year goes). Folded it is one column; unfolded each page uses two.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val gate by vm.gate.collectAsStateWithLifecycle()
    // [NAV-01] The same month as the calendar and 근무·급여.
    val month by vm.shownMonth.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<MoneyKind?>(null) }
    var addRound by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<MoneyEntry?>(null) }
    var editingJob by remember { mutableStateOf<Job?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf<Capture?>(null) }
    var editingFixed by remember { mutableStateOf<FixedCost?>(null) }
    var addingFixed by remember { mutableStateOf(false) }
    var budgeting by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<MoneyAccount?>(null) }
    var addingAccount by remember { mutableStateOf<AccountType?>(null) }
    var addingAnyAccount by remember { mutableStateOf(false) }
    var editingCategories by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var autoRecording by rememberSaveable { mutableStateOf(false) }
    var editingRules by rememberSaveable { mutableStateOf(false) }
    var filtering by rememberSaveable { mutableStateOf(false) }
    // [LEDG-16] 돈 적기 shrinks to its icon while the page scrolls down and comes back on the way up.
    var fabExpanded by rememberSaveable { mutableStateOf(true) }
    val fabScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -1f) fabExpanded = false else if (available.y > 1f) fabExpanded = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(page) { fabExpanded = true }
    // [LEDG-16] Settings' 결제 알림 읽기 lands here with 자동 기록 open.
    val noticeTarget by NoticeLinks.target.collectAsStateWithLifecycle()
    LaunchedEffect(noticeTarget) {
        if (noticeTarget != NoticeLinks.Target.AUTO_RECORD) return@LaunchedEffect
        NoticeLinks.consume(NoticeLinks.Target.AUTO_RECORD)
        vm.showPage(MoneyViewModel.Page.SUMMARY)
        autoRecording = true
    }
    val rules by vm.rules.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    val autoRecord by vm.autoRecord.collectAsStateWithLifecycle()
    val book = bookOrNull ?: MoneyBook.EMPTY
    val style = settings.workStyle
    // Records show at once; the wage estimate joins when the pay engine has run.
    val m by produceState(book.month(month), book, style, profile, snapshot, month) {
        value = vm.month(book, style, profile, snapshot, month)
    }
    val analysis by produceState<LedgerAnalysis?>(null, book, style, profile, snapshot, month, page == MoneyViewModel.Page.REPORT) {
        if (page == MoneyViewModel.Page.REPORT) value = vm.analysis(book, style, profile, snapshot, month, today)
    }
    LaunchedEffect(snackbar) {
        val host = snackbar ?: return@LaunchedEffect
        vm.undo.collect { msg ->
            val action = msg.actionLabel.takeIf { msg.undo != null }
            if (host.showSnackbar(msg.message, actionLabel = action, duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
                == SnackbarResult.ActionPerformed) {
                vm.runUndo(msg)
            }
        }
    }
    val owed = remember(book, today) { book.owed(today) }
    // [COMN-04] With 확인하고 적기 on, the unconfirmed lines are in book.pending, out of every total.
    val unseen = remember(book) { book.pending + book.entries.filter { !it.reviewed } }
    val otherPlaces = remember(book, today) { OtherPlaces.groups(book.entries, today) }
    val repeats = remember(book, today, autoRecord.notRepeating) {
        RepeatFinder.find(book.entries, book.fixed, today, autoRecord.notRepeating)
    }
    val upcoming = remember(book, today) { UpcomingPayments.within(book.fixed, book.entries, today, accounts = book.accounts) }
    val leftFixed = remember(book, today) { UpcomingPayments.leftThisMonth(book.fixed, book.entries, today) }
    val budget = remember(book, month, today, leftFixed) { Budgets.status(book.budgets, book.entries, month, today, leftFixed) }
    val recent = remember(book, month) { book.entries.filter { YearMonth.from(it.date) == month }.take(5) }
    val listActions = remember(vm) {
        object : LedgerListActions {
            override fun open(e: MoneyEntry) { editing = e }
            override fun delete(list: List<MoneyEntry>) = vm.delete(list)
            override fun duplicate(e: MoneyEntry) = vm.duplicate(e)
            override fun recategorize(list: List<MoneyEntry>, category: String) = vm.recategorize(list, category)
            override fun message(text: String) = vm.message(text)
        }
    }

    val left: @Composable () -> Unit = {
        if (!wide) NewRecordsCard(unseen, captures, onOpen = { editing = it }, onAllSeen = vm::markAllSeen,
            onFinish = { finishing = it }, onDrop = vm::dropCapture,
            waiting = autoRecord.confirmFirst, onConfirm = vm::confirm, onDelete = vm::delete)
        HeroCard(m, budget, today, onBudget = { budgeting = true })
        // [FLD-06][SHOP-02] A deposit that may be a job's money; card deposits still to come.
        WorkMoneyCards(vm.calendarId, book, style, today)
        // [COMN-02] With two calendars or more, all of them added up.
        AllCalendarsCard(month)
        BudgetCard(budget, today, onEdit = { budgeting = true })
        // [GUIDE-05] Suggestions that can be turned off for the calendar.
        com.bamnat.app.ui.guide.FeatureCard("money.otherPlaces", vm.calendarId) { OtherPlacesCard(otherPlaces, book.categoriesOf(MoneyKind.EXPENSE), onPick = { g, c -> vm.recategorize(g.entries, c) }) }
        UpcomingCard(upcoming, book.fixed, leftFixed, onRecord = { vm.recordFixed(it, today) },
            onEdit = { c -> if (c == null) addingFixed = true else editingFixed = c })
        com.bamnat.app.ui.guide.FeatureCard("money.repeat", vm.calendarId) { RepeatSuggestCard(repeats, onAdd = vm::addRepeat, onNo = vm::notRepeating) }
        AccountsCard(book, today, onAdd = { t -> if (t == null) addingAnyAccount = true else addingAccount = t }, onEdit = { editingAccount = it })
    }
    val right: @Composable () -> Unit = {
        if (wide) NewRecordsCard(unseen, captures, onOpen = { editing = it }, onAllSeen = vm::markAllSeen,
            onFinish = { finishing = it }, onDrop = vm::dropCapture,
            waiting = autoRecord.confirmFirst, onConfirm = vm::confirm, onDelete = vm::delete)
        RecentCard(recent, month, onOpen = { editing = it }, onAll = { vm.setView(LedgerView()); vm.showPage(MoneyViewModel.Page.LIST) })
        IncomeCard(m, style, hasProfile = profile != null, onOpenPay = onOpenPay)
        // [RECV-02] Money owed: receivables and unpaid jobs together, the whole list one tap away.
        OwedCard(owed, today, onOpenAll = { com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.OWED) }) { o ->
            when (o) {
                is com.bamnat.core.money.Owed.FromJob -> editingJob = o.job
                is com.bamnat.core.money.Owed.Written -> com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.OWED)
            }
        }
        // [LEDG-02] Ways to type less.
        AutoRecordCard(onPaste = { pasting = true })
    }

    Box(Modifier.fillMaxSize().nestedScroll(fabScroll)) {
        Column(Modifier.fillMaxSize()) {
            // At very large text the tab has no room for the 프로 mark (the cards inside still carry it),
            // and the title gives its room to the month: the 돈 tab below already says where this is.
            val roomy = LocalDensity.current.fontScale < 1.3f
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (roomy) {
                    FitText("돈 관리", MaterialTheme.typography.headlineSmall, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f).semantics { heading() })
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = { vm.showMonth(month.minusMonths(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달")
                }
                Text(Fmt.month(month), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                IconButton(onClick = { vm.showMonth(month.plusMonths(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달")
                }
                // [LEDG-16] What is set once and changed rarely, always in the same place.
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "돈 탭 메뉴") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("자동 기록") }, onClick = { menuOpen = false; autoRecording = true })
                        DropdownMenuItem(text = { Text("결제 문자 붙여넣기") }, onClick = { menuOpen = false; pasting = true })
                        DropdownMenuItem(text = { Text("분류 편집") }, onClick = { menuOpen = false; editingCategories = true })
                    }
                }
            }
            // [MCAL-06] Whose money this is, once there are several calendars.
            com.bamnat.app.ui.calendar.CalendarScopeChips(LocalContext.current.appContainer, Modifier.padding(horizontal = 16.dp))
            PrimaryTabRow(selectedTabIndex = page.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
                MoneyViewModel.Page.entries.forEach { p ->
                    Tab(
                        selected = page == p, onClick = { vm.showPage(p); if (p != MoneyViewModel.Page.LIST) vm.select(emptySet()) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.label, maxLines = 1)
                                if (p == MoneyViewModel.Page.REPORT && roomy) { Spacer(Modifier.width(6.dp)); ProPill() }
                            }
                        },
                    )
                }
            }
            when (page) {
                MoneyViewModel.Page.SUMMARY -> if (wide) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) { left(); Spacer(Modifier.height(FabClearance)) }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) { right(); Spacer(Modifier.height(FabClearance)) }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) { left(); right(); Spacer(Modifier.height(FabClearance)) }
                }
                MoneyViewModel.Page.LIST -> LedgerListPane(book, month, view, vm::setView, selection, vm::select, listActions, wide,
                    onAdd = { adding = MoneyKind.EXPENSE })
                MoneyViewModel.Page.REPORT -> LedgerReportsPane(analysis, book, budget.total?.budget, style, gate, wide, today,
                    onCategory = vm::showCategory, onOpen = { editing = it }, onPlace = vm::showPlace, month = month)
            }
        }
        AnimatedVisibility(selection.isEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(
                onClick = { adding = MoneyKind.EXPENSE },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("돈 적기") },
                expanded = fabExpanded,
                modifier = Modifier.semantics { contentDescription = "돈 적기" },
            )
        }
    }

    val addDate = if (YearMonth.from(today) == month) today else month.atEndOfMonth()
    adding?.let { k ->
        // A new round for 저장하고 하나 더, so the sheet starts empty again.
        key(addRound) {
            EntryDialog(addDate, null, k, onSave = { vm.saveEntry(it); adding = null }, onDismiss = { adding = null },
                frequent = vm::frequentAmounts, rules = rules, book = book,
                onSaveAndNext = { vm.saveEntry(it); addRound++ }, onEditCategories = { editingCategories = true })
        }
    }
    editing?.let { e ->
        EntryDialog(e.date, e, e.kind, onSave = { vm.saveEntry(it); editing = null },
            onDelete = { vm.delete(listOf(it)); editing = null }, onDismiss = { editing = null },
            frequent = vm::frequentAmounts, rules = rules, book = book,
            onDuplicate = { vm.duplicate(it); editing = null }, onEditCategories = { editingCategories = true })
    }
    finishing?.let { c ->
        val draft = remember(c) { c.draft() }
        EntryDialog(draft.date, draft, draft.kind, onSave = { vm.finishCapture(c, it); finishing = null },
            onDismiss = { finishing = null }, frequent = vm::frequentAmounts, rules = rules, book = book)
    }
    if (autoRecording) AutoRecordDialog(onPaste = { pasting = true }, onDismiss = { autoRecording = false },
        onRules = { autoRecording = false; editingRules = true }, onFilter = { autoRecording = false; filtering = true })
    if (editingRules) CategoryRulesDialog(rules, book.categoriesOf(MoneyKind.EXPENSE), onSave = vm::setRule, onDelete = vm::deleteRule,
        onDismiss = { editingRules = false })
    if (filtering) CaptureFilterDialog(autoRecord, onChange = vm::setCaptureFilter, onDismiss = { filtering = false })
    if (pasting) PasteDialog(book.entries, rules, onSave = { vm.saveAll(it); pasting = false }, onDismiss = { pasting = false }, today = today)
    if (addingFixed || editingFixed != null) {
        FixedCostDialog(editingFixed, onSave = { vm.saveFixed(it); addingFixed = false; editingFixed = null },
            onDelete = { vm.deleteFixed(it); editingFixed = null },
            onDismiss = { addingFixed = false; editingFixed = null }, book = book, today = today)
    }
    editingJob?.let { j ->
        JobDialog(j.date, j, onSave = { vm.saveJob(it); editingJob = null },
            onDelete = { vm.deleteJob(it); editingJob = null }, onDismiss = { editingJob = null })
    }
    if (budgeting) BudgetEditor(book, month, onSave = { vm.saveBudgets(it); budgeting = false }, onDismiss = { budgeting = false })
    if (editingAccount != null || addingAccount != null || addingAnyAccount) {
        AccountEditor(editingAccount, addingAccount, book,
            onSave = { vm.saveAccount(it); editingAccount = null; addingAccount = null; addingAnyAccount = false },
            onRemove = { vm.removeAccount(it); editingAccount = null },
            onDismiss = { editingAccount = null; addingAccount = null; addingAnyAccount = false })
    }
    if (editingCategories) {
        CategoryEditor(book, onSave = { k, edits, renames -> vm.saveCategories(k, edits, renames); editingCategories = false },
            onDismiss = { editingCategories = false }, startKind = editing?.kind ?: adding ?: MoneyKind.EXPENSE)
    }
}

/** [LEDG-07] The month's latest lines, with the whole list a tap away. */
@Composable
private fun RecentCard(recent: List<MoneyEntry>, month: YearMonth, onOpen: (MoneyEntry) -> Unit, onAll: () -> Unit) {
    SectionCard(title = "${month.monthValue}월 최근 기록", trailing = { TextButton(onClick = onAll) { Text("내역 모두 보기") } }) {
        if (recent.isEmpty()) {
            Text("아래 ‘돈 적기’나 달력의 날짜에서 지출과 수입을 적어요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recent.forEach { e -> EntryRow(e, showDate = true) { onOpen(e) } }
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
            // [RECV-04] Only what was paid counts; the rest of the work is shown, not added.
            Line("일감 ${m.jobCount}건 받은 돈", Fmt.won(m.jobReceived))
            if (m.jobEarned > 0) Line("  일한 금액 ${Fmt.won(m.jobEarned)} · 아직 안 받은 돈 ${Fmt.won(m.jobOwed)}", "", muted = true)
            if (m.jobWithheld > 0) Line("  3.3% 세금 뗀 뒤 금액 (뗀 세금 ${Fmt.won(m.jobWithheld)})", "", muted = true)
            if (m.jobCosts > 0) Line("  자재비·경비는 나간 돈에 넣었어요", Fmt.won(m.jobCosts), muted = true)
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

/** [WORK-02][RECV-02] Work done but not fully paid, and money written down as owed; the real worry of field work and shops. */
@Composable
private fun OwedCard(owed: List<com.bamnat.core.money.Owed>, today: LocalDate, onOpenAll: () -> Unit, onOpen: (com.bamnat.core.money.Owed) -> Unit) {
    val cal = com.bamnat.app.ui.theme.LocalCalendarColors.current
    val overdue = owed.filter { it.status(today) == com.bamnat.core.money.ReceivableStatus.OVERDUE }
    SectionCard(
        title = if (owed.isEmpty()) "받을 돈 (미수금)" else "받을 돈 ${Fmt.won(owed.sumOf { it.left })}",
        trailing = { TextButton(onClick = onOpenAll) { Text(if (owed.isEmpty()) "적기" else "모두 보기") } },
    ) {
        if (owed.isEmpty()) {
            Text("외상, 밀린 일당, 빌려준 돈처럼 아직 못 받은 돈을 적어 두면 약속일에 알려 줘요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        if (overdue.isNotEmpty()) {
            Text("약속일 지난 돈 ${overdue.size}건 · ${Fmt.won(overdue.sumOf { it.left })}", style = MaterialTheme.typography.bodyMedium,
                color = cal.warning, modifier = Modifier.padding(bottom = 4.dp))
        }
        owed.take(4).forEachIndexed { i, o ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            when (o) {
                is com.bamnat.core.money.Owed.FromJob -> JobRow(o.job, showDate = true) { onOpen(o) }
                is com.bamnat.core.money.Owed.Written -> Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpen(o) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.name, style = MaterialTheme.typography.bodyLarge)
                        Text(listOfNotNull(o.who.takeIf { it.isNotBlank() && it != o.name }, o.status(today).label,
                            o.due?.let { "약속 ${Fmt.shortDate(it)}" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Fmt.won(o.left), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                        color = if (o.status(today) == com.bamnat.core.money.ReceivableStatus.OVERDUE) cal.warning else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (owed.size > 4) {
            Text("그 밖에 ${owed.size - 4}건", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
