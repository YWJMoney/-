package com.bamnat.app.ui.money

import com.bamnat.app.appContainer
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobPayments
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.Owed
import com.bamnat.core.money.OwedFilter
import com.bamnat.core.money.OwedSort
import com.bamnat.core.money.OwedSummary
import com.bamnat.core.money.Receivable
import com.bamnat.core.money.ReceivableKind
import com.bamnat.core.money.ReceivablePayment
import com.bamnat.core.money.ReceivableStatus
import com.bamnat.core.money.Receivables
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/** [RECV-01][RECV-02][RECV-05] 받을 돈: everything owed to the user, written down here or left on a job. */
class OwedViewModel(
    private val container: AppContainer,
    /** [MCAL-06] The calendar whose money is owed: the one on the calendar tab. */
    val calendarId: String = com.bamnat.app.data.PersonalCalendar.MAIN,
) : ViewModel() {
    private val scope = container.scopeOf(calendarId)
    private val money = scope.money
    val book: StateFlow<MoneyBook?> = money.book.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val style: StateFlow<WorkStyle?> = scope.settings.map { it.workStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), scope.currentSettings().workStyle)

    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    fun save(r: Receivable) {
        container.appScope.launch {
            val saved = money.save(r)
            if (r.id == 0L) _messages.emit(UndoMessage("${saved.name} ${Fmt.won(saved.amount)}을 받을 돈으로 적었어요") { money.delete(saved) })
        }
    }

    fun delete(r: Receivable) {
        container.appScope.launch {
            val lines = money.linesOf(r)
            money.delete(r)
            _messages.emit(UndoMessage("‘${r.name}’ 기록을 지웠어요") { money.restore(r, lines) })
        }
    }

    fun receive(r: Receivable, amount: Long, date: LocalDate) {
        container.appScope.launch {
            val p = money.receive(r, amount, date) ?: return@launch
            val left = (r.amount - r.received - p.amount).coerceAtLeast(0)
            val text = if (left == 0L) "${r.name} 다 받았어요" else "${Fmt.won(p.amount)} 받았어요 · ${Fmt.won(left)} 남았어요"
            _messages.emit(UndoMessage(text + if (p.entryId != null) " (수입에 적음)" else "") { money.delete(p) })
        }
    }

    /** [RECV-05] A swipe or the sheet's main button: everything left came in today. */
    fun receiveAll(o: Owed, today: LocalDate) {
        when (o) {
            is Owed.Written -> receive(o.r, o.r.left, today)
            is Owed.FromJob -> receiveJob(o.job, JobPayments.all(o.job))
        }
    }

    /** [RECV-05] Ticks a job's 돈 받음 from the list, so it counts as 들어온 돈 without opening the job. */
    fun receiveJob(before: Job, after: Job) {
        container.appScope.launch {
            money.save(after)
            val got = after.received - before.received
            val text = if (after.unpaid == 0L) "‘${after.title}’ 다 받았어요" else "${Fmt.won(got)} 받았어요 · ${Fmt.won(after.unpaid)} 남았어요"
            _messages.emit(UndoMessage("$text (들어온 돈에 더함)") { money.save(before) })
        }
    }

    fun deletePayment(p: ReceivablePayment) {
        container.appScope.launch {
            money.delete(p)
            _messages.emit(UndoMessage("받은 기록을 지웠어요"))
        }
    }

    /** "받지 않기로 했어요": no longer owed, kept in the list of finished ones. */
    fun close(r: Receivable, closed: Boolean) {
        container.appScope.launch {
            money.save(r.copy(closedOn = if (closed) LocalDate.now() else null))
            if (closed) _messages.emit(UndoMessage("받을 돈에서 뺐어요") { money.save(r) })
        }
    }

    fun saveJob(job: Job) { container.appScope.launch { money.save(job) } }

    fun runUndo(m: UndoMessage) { m.undo?.let { u -> container.appScope.launch { u() } } }
}

private enum class Show(val label: String) { OWED("받을 것"), DONE("끝난 것") }

/** What a line is, in one picture: a job, a sale on credit, pay, a loan, anything else. */
private fun Owed.icon(): ImageVector = when (this) {
    is Owed.FromJob -> Icons.Filled.Construction
    is Owed.Written -> when (r.kind) {
        ReceivableKind.SALE -> Icons.Filled.Storefront
        ReceivableKind.WORK -> Icons.Filled.Payments
        ReceivableKind.LOAN -> Icons.Filled.VolunteerActivism
        ReceivableKind.OTHER -> Icons.AutoMirrored.Filled.ReceiptLong
    }
}

/** [RECV-05] The kinds an empty list offers first, by how the user works. */
private fun startKinds(style: WorkStyle?): List<ReceivableKind> = when (style) {
    WorkStyle.BUSINESS -> listOf(ReceivableKind.SALE, ReceivableKind.LOAN, ReceivableKind.OTHER)
    WorkStyle.FIELD -> listOf(ReceivableKind.WORK, ReceivableKind.SALE, ReceivableKind.LOAN)
    else -> listOf(ReceivableKind.WORK, ReceivableKind.LOAN, ReceivableKind.OTHER)
}

/** Shares [text] through the messenger the user picks; nothing is sent by the app itself. */
private fun shareText(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    runCatching { context.startActivity(Intent.createChooser(send, "받을 돈 알림 보내기")) }
}

/** A number that rolls to its new value instead of jumping, so a payment is felt. */
@Composable
private fun rolling(target: Long): Long {
    var shown by remember { mutableLongStateOf(0L) }
    LaunchedEffect(target) {
        val from = shown
        val a = Animatable(0f)
        a.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) { shown = from + ((target - from) * value.toDouble()).roundToLong() }
        shown = target
    }
    return shown
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwedScreen(vm: OwedViewModel, wide: Boolean, onBack: () -> Unit, today: LocalDate = LocalDate.now()) {
    val bookOrNull by vm.book.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val book = bookOrNull ?: MoneyBook.EMPTY
    val context = LocalContext.current
    var adding by rememberSaveable { mutableStateOf<ReceivableKind?>(null) }
    var editing by remember { mutableStateOf<Receivable?>(null) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openJobId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingJob by remember { mutableStateOf<Job?>(null) }
    var show by rememberSaveable { mutableStateOf(Show.OWED) }
    var filter by rememberSaveable { mutableStateOf(OwedFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(OwedSort.URGENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.message, actionLabel = m.undo?.let { m.actionLabel }, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.runUndo(m)
        }
    }
    val owed = remember(book, today) { book.owed(today) }
    val summary = remember(book, today) { Receivables.summary(book.receivables, book.jobs, today) }
    val counts = remember(owed, today) { Receivables.counts(owed, today) }
    val done = remember(book) { book.receivables.filter { it.left == 0L }.sortedByDescending { it.lastPaid ?: it.closedOn ?: it.date } }
    val shown = remember(owed, today, filter, query, sort, person) {
        Receivables.narrow(person?.let { Receivables.of(owed, it) } ?: owed, today, filter, query, sort)
    }
    val doneShown = remember(done, query) { done.filter { query.isBlank() || "${it.who} ${it.name} ${it.note}".contains(query.trim(), ignoreCase = true) } }
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    val actions = RowActions(
        open = { o -> when (o) { is Owed.Written -> openId = o.r.id; is Owed.FromJob -> openJobId = o.job.id } },
        receiveAll = { o -> vm.receiveAll(o, today) },
        remind = { o -> shareText(context, Receivables.reminder(o, today)) },
    )
    val pickFilter = { f: OwedFilter -> show = Show.OWED; filter = if (filter == f) OwedFilter.ALL else f }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("받을 돈") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, if (searching) "찾기 닫기" else "찾기")
                    }
                    SortMenu(sort) { sort = it }
                    // [LEDG-27] Every 받을 돈 written down, as a CSV file through the share sheet.
                    if (book.receivables.isNotEmpty()) {
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        IconButton(onClick = {
                            scope.launch {
                                runCatching {
                                    LedgerExport.share(context, com.bamnat.core.money.WorkCsv.receivables(book.receivables, today),
                                        com.bamnat.core.money.WorkCsv.fileName("receivables", null, null))
                                }.onFailure { snackbar.showSnackbar("공유하지 못했어요") }
                            }
                        }) { Icon(Icons.Outlined.FileDownload, "받을 돈 CSV로 내보내기") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = startKinds(style).first() }, expanded = atTop || wide,
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("받을 돈 적기") },
                modifier = Modifier.semantics { contentDescription = "받을 돈 적기" })
        },
    ) { padding ->
        val top: @Composable ColumnScope.() -> Unit = {
            // [MCAL-06] Whose money is owed, once there are several calendars.
            com.bamnat.app.ui.calendar.CalendarScopeChips(androidx.compose.ui.platform.LocalContext.current.appContainer)
            Totals(summary, filter, onFilter = pickFilter, onShowDone = { show = Show.DONE })
            if (summary.byWho.size > 1 || summary.aging.drop(1).any { it > 0 }) {
                Breakdown(summary, person, startOpen = wide) { who -> show = Show.OWED; person = if (person == who) null else who }
            }
        }
        val list: LazyListScope.() -> Unit = {
            item(key = "controls") {
                Controls(
                    show = show, onShow = { show = it }, owedCount = owed.size, doneCount = done.size,
                    filter = filter, counts = counts, onFilter = pickFilter,
                    searching = searching, query = query, onQuery = { query = it },
                    person = person, onClearPerson = { person = null },
                )
            }
            when (show) {
                Show.OWED -> when {
                    owed.isEmpty() -> item(key = "empty") { Empty(style) { adding = it } }
                    shown.isEmpty() -> item(key = "none") {
                        NoMatch { filter = OwedFilter.ALL; query = ""; person = null }
                    }
                    else -> {
                        item(key = "count") {
                            Text("${shown.size}건 · ${Fmt.won(shown.sumOf { it.left })}" +
                                if (shown.size == owed.size) "" else " (전체 ${owed.size}건)",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        items(shown, key = { it.key() }) { o ->
                            SwipeRow(o, today, actions, Modifier.animateItem())
                        }
                        item(key = "hint") { SwipeHint() }
                    }
                }
                Show.DONE -> if (doneShown.isEmpty()) item(key = "done-empty") {
                    Text(if (done.isEmpty()) "다 받았거나 받지 않기로 한 것이 여기에 모여요." else "찾는 기록이 없어요.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp))
                } else items(doneShown, key = { "done-${it.id}" }) { r ->
                    OwedCard(Owed.Written(r), today, Modifier.animateItem()) { openId = r.id }
                }
            }
            item(key = "end") { Spacer(Modifier.height(88.dp)) }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { top(); Spacer(Modifier.height(80.dp)) }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState, contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = list)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "top") { Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 4.dp)) { top() } }
                list()
            }
        }
    }

    val names = remember(book) { (book.receivables.map { it.who } + book.jobs.map { it.client }).map { it.trim() }.filter { it.isNotBlank() }.distinct() }
    adding?.let { k -> ReceivableDialog(null, names, today, kindDefault = k, onDismiss = { adding = null }) { vm.save(it); adding = null } }
    editing?.let { r -> ReceivableDialog(r, names, today, onDismiss = { editing = null }) { vm.save(it); editing = null } }
    val opened = openId?.let { id -> book.receivables.firstOrNull { it.id == id } }
    // Deleted meanwhile (or by its own button): the sheet closes. Waits for the book to load first.
    LaunchedEffect(opened == null, bookOrNull == null) { if (opened == null && bookOrNull != null) openId = null }
    opened?.let { r ->
        ReceivableSheet(r, today,
            onDismiss = { openId = null },
            onReceive = { amount, date -> vm.receive(r, amount, date) },
            onDeletePayment = vm::deletePayment,
            onRemind = { shareText(context, Receivables.reminder(Owed.Written(r), today)) },
            onEdit = { editing = r; openId = null },
            onClose = { vm.close(r, it); if (it) openId = null },
            onDelete = { vm.delete(r); openId = null })
    }
    val openedJob = openJobId?.let { id -> book.jobs.firstOrNull { it.id == id } }
    LaunchedEffect(openedJob == null, bookOrNull == null) { if (openedJob == null && bookOrNull != null) openJobId = null }
    openedJob?.let { j ->
        JobOwedSheet(j, today,
            onDismiss = { openJobId = null },
            onReceive = { after -> vm.receiveJob(j, after); if (after.unpaid == 0L) openJobId = null },
            onRemind = { shareText(context, Receivables.reminder(Owed.FromJob(j), today)) },
            onEdit = { editingJob = j; openJobId = null })
    }
    editingJob?.let { j ->
        JobDialog(j.date, j, onSave = { vm.saveJob(it); editingJob = null }, onDismiss = { editingJob = null })
    }
}

private fun Owed.key(): String = when (this) { is Owed.Written -> "r-${r.id}"; is Owed.FromJob -> "j-${job.id}" }

private class RowActions(val open: (Owed) -> Unit, val receiveAll: (Owed) -> Unit, val remind: (Owed) -> Unit)

@Composable
private fun SortMenu(sort: OwedSort, onSort: (OwedSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "정렬: ${sort.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            OwedSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    onClick = { onSort(s); open = false },
                    trailingIcon = { if (s == sort) Icon(Icons.Filled.Check, "고름") },
                )
            }
        }
    }
}

/** The headline: what is still out, how much of it has come back, and tiles that narrow the list. */
@Composable
private fun Totals(s: OwedSummary, filter: OwedFilter, onFilter: (OwedFilter) -> Unit, onShowDone: () -> Unit) {
    val cal = LocalCalendarColors.current
    SectionCard(title = "아직 못 받은 돈") {
        Text(Fmt.won(rolling(s.total)), style = MaterialTheme.typography.displaySmall.merge(NumberStyle), fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { contentDescription = "아직 못 받은 돈 ${Fmt.won(s.total)}" })
        Text(if (s.count == 0) "받을 돈이 없어요" else "${s.count}건" + if (s.overdueCount > 0) " · 약속일 지난 것 ${s.overdueCount}건" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (s.overdueCount > 0) cal.warning else MaterialTheme.colorScheme.onSurfaceVariant)
        // How much of this month's money has come back against what is still out.
        val whole = s.receivedThisMonth + s.total
        if (whole > 0) {
            val fraction by animateFloatAsState(s.receivedThisMonth.toFloat() / whole, tween(700, easing = FastOutSlowInEasing), label = "collected")
            Spacer(Modifier.height(10.dp))
            Bar(fraction, cal.income, height = 8.dp)
            Row(Modifier.padding(top = 4.dp)) {
                Text("이번 달 ${Fmt.won(s.receivedThisMonth)} 받음", style = MaterialTheme.typography.labelMedium, color = cal.income,
                    modifier = Modifier.weight(1f))
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium.merge(NumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Nothing owed and nothing back yet: three 0원 tiles would only be noise.
        if (s.count == 0 && s.receivedThisMonth == 0L) return@SectionCard
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("약속일 지남", Fmt.won(s.overdue), Modifier.weight(1f).selectedRing(filter == OwedFilter.OVERDUE),
                caption = "${s.overdueCount}건 · 눌러서 보기", warning = s.overdue > 0, onClick = { onFilter(OwedFilter.OVERDUE) })
            StatTile("3일 안에 받을 날", Fmt.won(s.dueSoon), Modifier.weight(1f).selectedRing(filter == OwedFilter.DUE_SOON),
                caption = "눌러서 보기", onClick = { onFilter(OwedFilter.DUE_SOON) })
            StatTile("이번 달 받은 돈", Fmt.won(s.receivedThisMonth), Modifier.weight(1f), caption = "끝난 것 보기", onClick = onShowDone)
        }
    }
}

@Composable
private fun Modifier.selectedRing(selected: Boolean): Modifier =
    if (!selected) this else this.then(Modifier.background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium).padding(2.dp))

/** Who owes most, and how long it has been; a name narrows the list to that person. */
@Composable
private fun Breakdown(s: OwedSummary, person: String?, startOpen: Boolean, onPerson: (String) -> Unit) {
    // [RECV-05] Folded on a phone so the list is near the top; two panes have room for all of it.
    var open by rememberSaveable { mutableStateOf(startOpen) }
    SectionCard(title = "누가, 얼마나 오래", trailing = { TextButton(onClick = { open = !open }) { Text(if (open) "접기" else "펼치기") } }) {
        val max = (s.byWho.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
        s.byWho.take(if (open) 5 else 3).forEach { (who, amount) ->
            MeterRow(who, Fmt.won(amount), amount.toFloat() / max,
                if (person == who) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                selected = person == who, onClick = { onPerson(who) })
        }
        val hidden = s.byWho.size - if (open) 5 else 3
        if (hidden > 0) Text("그 밖에 ${hidden}명", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("이름을 누르면 그 사람 것만 보여요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { Column {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val ageMax = (s.aging.maxOrNull() ?: 1).coerceAtLeast(1)
        val warn = LocalCalendarColors.current.warning
        s.aging.forEachIndexed { i, v ->
            MeterRow(Receivables.AGING_LABELS[i], Fmt.won(v), v.toFloat() / ageMax, if (i >= 2) warn else MaterialTheme.colorScheme.tertiary)
        }
        Text("약속일이 있으면 약속일부터, 없으면 생긴 날부터 세요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        } }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, height: androidx.compose.ui.unit.Dp = 6.dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(color))
    }
}

@Composable
private fun MeterRow(label: String, value: String, fraction: Float, color: Color, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val animated by animateFloatAsState(fraction, tween(600, easing = FastOutSlowInEasing), label = "meter")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).heightIn(min = 48.dp) else Modifier)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .padding(horizontal = if (onClick != null) 6.dp else 0.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.Center,
    ) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(value, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(3.dp))
        Bar(animated, color)
    }
}

/** Tabs, chips, the search field and a person picked from the breakdown, over the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(
    show: Show, onShow: (Show) -> Unit, owedCount: Int, doneCount: Int,
    filter: OwedFilter, counts: Map<OwedFilter, Int>, onFilter: (OwedFilter) -> Unit,
    searching: Boolean, query: String, onQuery: (String) -> Unit,
    person: String?, onClearPerson: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Show.entries.forEachIndexed { i, s ->
                SegmentedButton(selected = show == s, onClick = { onShow(s) }, shape = SegmentedButtonDefaults.itemShape(i, Show.entries.size)) {
                    Text("${s.label} ${if (s == Show.OWED) owedCount else doneCount}")
                }
            }
        }
        AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            OutlinedTextField(
                value = query, onValueChange = { onQuery(it.take(30)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "지우기") } },
                placeholder = { Text("이름, 무슨 돈, 메모로 찾기") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (show == Show.OWED && owedCount > 0) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // A chip with nothing behind it is left out, unless it is the one picked.
                OwedFilter.entries.filter { it == OwedFilter.ALL || it == filter || (counts[it] ?: 0) > 0 }.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { onFilter(f) },
                        leadingIcon = if (filter == f) { { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) } } else null,
                        label = { Text("${f.label} ${counts[f] ?: 0}") })
                }
            }
        }
        if (person != null && show == Show.OWED) {
            InputChip(selected = true, onClick = onClearPerson, label = { Text(person) },
                leadingIcon = { Icon(Icons.Filled.Person, null, Modifier.size(16.dp)) },
                trailingIcon = { Icon(Icons.Filled.Close, "$person 보기 끄기", Modifier.size(16.dp)) })
        }
    }
}

@Composable
private fun SwipeHint() {
    Text("오른쪽으로 밀면 다 받음, 왼쪽으로 밀면 알림 문자를 보내요.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

/** [RECV-05] Ways in for each kind of user, so the first line takes one tap to start. */
@Composable
private fun Empty(style: WorkStyle?, onAdd: (ReceivableKind) -> Unit) {
    SectionCard(title = null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Payments, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("받을 돈을 적어 두세요", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(when (style) {
                WorkStyle.BUSINESS -> "외상 매출이나 거래처 미수금을 적어 두면 약속일에 알려 주고, 나눠 받은 것도 남은 금액으로 계산해요."
                WorkStyle.FIELD -> "달력에 적은 일감 중 돈 받음을 켜지 않은 것은 여기에 저절로 모여요. 그 밖의 돈도 적어 둘 수 있어요."
                else -> "밀린 일당이나 빌려준 돈처럼 아직 못 받은 돈을 적어 두면 약속일에 알려 줘요."
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            startKinds(style).forEachIndexed { i, k ->
                val label = "${k.label} 적기"
                if (i == 0) Button(onClick = { onAdd(k) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                else OutlinedButton(onClick = { onAdd(k) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(label) }
            }
        }
    }
}

@Composable
private fun NoMatch(onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("조건에 맞는 받을 돈이 없어요", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onClear) { Text("조건 모두 끄기") }
    }
}

@Composable
private fun StatusChip(status: ReceivableStatus) {
    val (bg, fg) = statusColors(status)
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(status.label, style = MaterialTheme.typography.labelSmall, color = fg, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun statusColors(status: ReceivableStatus): Pair<Color, Color> {
    val cal = LocalCalendarColors.current
    return when (status) {
        ReceivableStatus.OVERDUE -> cal.warningContainer to cal.warning
        ReceivableStatus.DUE_SOON -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ReceivableStatus.PAID -> cal.income.copy(alpha = 0.16f) to cal.income
        ReceivableStatus.PARTLY -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** "D-3", "오늘", "5일 지남", or how long ago it began when no day was promised. */
private fun dueText(o: Owed, today: LocalDate): String {
    val due = o.due
    if (due != null) {
        val d = ChronoUnit.DAYS.between(today, due)
        return when {
            d > 0 -> "${Fmt.shortDate(due)}까지 · D-$d"
            d == 0L -> "오늘 받기로 함"
            else -> "${Fmt.shortDate(due)} 약속 · ${-d}일 지남"
        }
    }
    val days = ChronoUnit.DAYS.between(o.since, today)
    return "${Fmt.shortDate(o.since)}부터" + if (days > 0) " · ${days}일째" else ""
}

/** How much of a line has come back, for the bar under it. */
private fun Owed.paidPart(): Pair<Long, Long> = when (this) {
    is Owed.Written -> r.received to r.amount
    is Owed.FromJob -> job.received to job.payable
}

/**
 * [RECV-05] A line that slides: right for 다 받음, left for a reminder to send. Both snap back, and
 * the same two actions are offered to screen readers, so nothing depends on the gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(o: Owed, today: LocalDate, actions: RowActions, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * SWIPE_THRESHOLD }) // [SWIPE-01] not by a slanted scroll
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> { haptics.performHapticFeedback(HapticFeedbackType.Confirm); actions.receiveAll(o); state.reset() }
            SwipeToDismissBoxValue.EndToStart -> { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); actions.remind(o); state.reset() }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }
    val cal = LocalCalendarColors.current
    SwipeToDismissBox(
        state = state,
        modifier = modifier.clip(MaterialTheme.shapes.large),
        backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val armed = state.targetValue != SwipeToDismissBoxValue.Settled
            val bg by animateColorAsState(
                when {
                    state.dismissDirection == SwipeToDismissBoxValue.Settled -> Color.Transparent
                    toRight -> if (armed) cal.income else cal.income.copy(alpha = 0.35f)
                    else -> if (armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                }, label = "swipe")
            Row(Modifier.fillMaxSize().background(bg).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End) {
                val fg = if (armed) Color.White else MaterialTheme.colorScheme.onSurface
                if (toRight) {
                    Icon(Icons.Filled.Check, null, tint = fg)
                    Spacer(Modifier.width(8.dp))
                    Text("${Fmt.won(o.left)} 다 받음", color = fg, style = MaterialTheme.typography.labelLarge)
                } else if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Text("알림 문자", color = fg, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = fg)
                }
            }
        },
    ) {
        OwedCard(o, today, Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction("${Fmt.won(o.left)} 다 받음") { actions.receiveAll(o); true },
                CustomAccessibilityAction("알림 문자 보내기") { actions.remind(o); true },
            )
        }) { actions.open(o) }
    }
}

/** One line as a card: what kind it is, who, how late, how much is left, and how much came back. */
@Composable
private fun OwedCard(o: Owed, today: LocalDate, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val status = o.status(today)
    val r = (o as? Owed.Written)?.r
    val finished = r != null && r.left == 0L
    val (tone, onTone) = statusColors(status)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (status == ReceivableStatus.OVERDUE) LocalCalendarColors.current.warning.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(tone), contentAlignment = Alignment.Center) {
                    Icon(o.icon(), null, tint = onTone, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(o.who.trim().takeIf { it.isNotBlank() && it != o.name }, if (o is Owed.FromJob) "일감 잔금" else r?.kind?.label,
                            if (!finished) dueText(o, today) else (r?.lastPaid ?: r?.closedOn)?.let { Fmt.shortDate(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.won(if (finished) r!!.amount else o.left), style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    StatusChip(status)
                }
            }
            val (got, of) = o.paidPart()
            if (got > 0 && !finished) {
                Spacer(Modifier.height(10.dp))
                Bar(got.toFloat() / of.coerceAtLeast(1), LocalCalendarColors.current.income)
                Text("${Fmt.won(got)} 받음 · 전체 ${Fmt.won(of)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** The top of both sheets: the picture, the name, who and what kind, and the state. */
@Composable
private fun SheetHeader(o: Owed, today: LocalDate, sub: String) {
    val status = o.status(today)
    val (tone, onTone) = statusColors(status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(tone), contentAlignment = Alignment.Center) {
            Icon(o.icon(), null, tint = onTone)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(o.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusChip(status)
    }
}

/** Left, received and the bar between them, large: the one thing the sheet is about. */
@Composable
private fun LeftFigure(left: Long, got: Long, of: Long) {
    val cal = LocalCalendarColors.current
    Column {
        Text("남은 돈", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Fmt.won(rolling(left)), style = MaterialTheme.typography.headlineMedium.merge(NumberStyle), fontWeight = FontWeight.Bold,
            color = if (left == 0L) cal.income else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        val fraction by animateFloatAsState(got.toFloat() / of.coerceAtLeast(1), tween(600, easing = FastOutSlowInEasing), label = "sheet")
        Bar(fraction, cal.income, height = 8.dp)
        Row(Modifier.padding(top = 4.dp)) {
            Text("${Fmt.won(got)} 받음", style = MaterialTheme.typography.labelMedium, color = cal.income, modifier = Modifier.weight(1f))
            Text("전체 ${Fmt.won(of)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One receivable: what came back, a button for what comes now, and the rest of its actions. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReceivableSheet(
    r: Receivable,
    today: LocalDate,
    onDismiss: () -> Unit,
    onReceive: (Long, LocalDate) -> Unit,
    onDeletePayment: (ReceivablePayment) -> Unit,
    onRemind: () -> Unit,
    onEdit: () -> Unit,
    onClose: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var amount by rememberSaveable(r.id, r.left) { mutableStateOf(amountDigits(r.left)) }
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val o = Owed.Written(r)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SheetHeader(o, today, listOfNotNull(r.who.takeIf { it.isNotBlank() && it != r.name }, r.kind.label).joinToString(" · "))
            LeftFigure(r.left, r.received, r.amount)
            Text("${Fmt.shortFullDate(r.date)}에 생김 · " + if (r.left > 0 && r.due != null) dueText(o, today)
                else r.due?.let { "약속일 ${Fmt.shortDate(it)}" } ?: "약속일 없음",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.note.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Text(r.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            if (r.left > 0) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("돈이 들어왔나요?", style = MaterialTheme.typography.titleSmall)
                        AmountField("받은 금액", amount, { amount = it }, imeAction = ImeAction.Done)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = amount.toAmount() == r.left, onClick = { amount = amountDigits(r.left) }, label = { Text("남은 돈 전부") })
                            if (r.left >= 20_000) FilterChip(selected = amount.toAmount() == r.left / 2, onClick = { amount = amountDigits(r.left / 2) },
                                label = { Text("절반") })
                            FilterChip(selected = date == today.toString(), onClick = { date = today.toString() }, label = { Text("오늘") })
                            FilterChip(selected = date != today.toString(), onClick = { pickDate = true },
                                label = { Text(if (date == today.toString()) "다른 날" else Fmt.shortDate(LocalDate.parse(date))) })
                        }
                        Text(
                            if (r.countAsIncome) "받은 돈은 돈 탭 들어온 돈에도 적어요." else "들어온 돈에는 적지 않아요 (${r.kind.hint}).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val a = Receivables.clampPayment(r, amount.toAmount())
                        Button(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onReceive(amount.toAmount(), LocalDate.parse(date)); amount = ""
                        }, enabled = a > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Icon(Icons.Filled.Check, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (a > 0) "${Fmt.won(a)} 받았어요" else "받았어요")
                        }
                    }
                }
            }
            if (r.payments.isNotEmpty()) {
                Text("받은 기록 ${r.payments.size}번", style = MaterialTheme.typography.titleSmall)
                Column {
                    r.payments.sortedByDescending { it.date }.forEachIndexed { i, p ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            // A dot on a line: the payments read as a timeline, newest on top.
                            Column(Modifier.width(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(LocalCalendarColors.current.income))
                                if (i < r.payments.size - 1) Box(Modifier.width(2.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(Fmt.won(p.amount), style = MaterialTheme.typography.bodyLarge.merge(NumberStyle), fontWeight = FontWeight.Medium)
                                Text(Fmt.dayTitle(p.date) + if (p.entryId != null) " · 들어온 돈에 적음" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onDeletePayment(p) }) { Text("취소") }
                        }
                    }
                }
            }
            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.left > 0) FilledTonalButton(onClick = onRemind) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("알림 문자")
                }
                OutlinedButton(onClick = onEdit) { Text("고치기") }
                if (r.closedOn == null && r.left > 0) OutlinedButton(onClick = { onClose(true) }) { Text("받지 않기로") }
                if (r.closedOn != null) OutlinedButton(onClick = { onClose(false) }) { Text("다시 받을 돈으로") }
                TextButton(onClick = { confirmDelete = true }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (pickDate) PickDate(LocalDate.parse(date), onDismiss = { pickDate = false }) { date = it.toString(); pickDate = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 기록을 지울까요?") },
            text = { Text("받은 기록과 그때 들어온 돈에 적은 줄도 함께 지워요. 바로 되돌릴 수 있어요.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("지우기", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

/** [RECV-05] A job still owed: tick its deposit or all of it as paid here, or open the job itself. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun JobOwedSheet(job: Job, today: LocalDate, onDismiss: () -> Unit, onReceive: (Job) -> Unit, onRemind: () -> Unit, onEdit: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val o = Owed.FromJob(job)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val span = if (job.days > 1) "${Fmt.shortDate(job.date)}~${Fmt.shortDate(job.lastDate)} (${job.days}일)" else Fmt.dayTitle(job.date)
            SheetHeader(o, today, listOfNotNull(job.client.ifBlank { null }, span, job.place.ifBlank { null }).joinToString(" · "))
            LeftFigure(job.unpaid, job.received, job.payable)
            if (job.deposit > 0) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PartLine("선금", job.deposit.coerceAtMost(job.payable), job.depositPaid)
                        PartLine("잔금", job.balance, job.balancePaid)
                    }
                }
            }
            if (job.withheld > 0) Text("3.3% 뗀 뒤 받을 금액이에요 (뗀 세금 ${Fmt.won(job.withheld)}).", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("받았다고 하면 그 일감의 돈 받음이 켜지고, 돈 탭 들어온 돈에 더해져요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            JobPayments.deposit(job)?.let { after ->
                FilledTonalButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onReceive(after) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("선금 ${Fmt.won(job.deposit.coerceAtMost(job.payable))} 받았어요") }
            }
            Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onReceive(JobPayments.all(job)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Filled.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("남은 ${Fmt.won(job.unpaid)} 다 받았어요")
            }
            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRemind) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("알림 문자")
                }
                OutlinedButton(onClick = onEdit) { Text("일감 고치기") }
            }
        }
    }
}

@Composable
private fun PartLine(label: String, amount: Long, paid: Boolean) {
    val cal = LocalCalendarColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(Fmt.won(amount), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle))
        Spacer(Modifier.width(8.dp))
        Text(if (paid) "받음" else "안 받음", style = MaterialTheme.typography.labelMedium,
            color = if (paid) cal.income else cal.warning)
    }
}

/** [RECV-01] Writing down what someone owes: who, how much, what kind, and when they said they would pay. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceivableDialog(
    initial: Receivable?,
    names: List<String>,
    today: LocalDate,
    /** [RECV-05] The kind a new one starts as: what the user tapped, or what fits their work. */
    kindDefault: ReceivableKind = ReceivableKind.SALE,
    onDismiss: () -> Unit,
    onSave: (Receivable) -> Unit,
) {
    var who by rememberSaveable { mutableStateOf(initial?.who.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(amountDigits(initial?.amount ?: 0)) }
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: kindDefault) }
    var income by rememberSaveable { mutableStateOf(initial?.countAsIncome ?: kindDefault.incomeByDefault) }
    var touchedIncome by rememberSaveable { mutableStateOf(initial != null) }
    var date by rememberSaveable { mutableStateOf((initial?.date ?: today).toString()) }
    var due by rememberSaveable { mutableStateOf(initial?.due?.toString()) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var picking by remember { mutableStateOf<String?>(null) }
    val start = LocalDate.parse(date)
    val dueDate = due?.let(LocalDate::parse)
    // Less than what already came back would make money appear; the lowest is what was received.
    val minAmount = initial?.received ?: 0
    val valid = amount.toAmount() > 0 && amount.toAmount() >= minAmount && (who.isNotBlank() || title.isNotBlank()) &&
        (dueDate == null || !dueDate.isBefore(start))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "받을 돈 적기" else "받을 돈 고치기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = who, onValueChange = { who = it.take(30) }, singleLine = true,
                    label = { Text("누가 (손님·거래처·사람)") }, modifier = Modifier.fillMaxWidth())
                val suggestions = names.filter { it != who && (who.isBlank() || it.contains(who.trim())) }.take(6)
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { n -> FilterChip(selected = false, onClick = { who = n }, label = { Text(n) }) }
                    }
                }
                AmountField("얼마", amount, { amount = it })
                if (amount.toAmount() in 1 until minAmount) {
                    Text("이미 ${Fmt.won(minAmount)} 받았어요. 그보다 적게 할 수 없어요.", style = MaterialTheme.typography.bodySmall,
                        color = LocalCalendarColors.current.warning)
                }
                OutlinedTextField(value = title, onValueChange = { title = it.take(40) }, singleLine = true,
                    label = { Text("무슨 돈 (예: 10월 납품, 3일치 일당)") }, modifier = Modifier.fillMaxWidth())
                Text("어떤 돈인가요", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReceivableKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = {
                            kind = k
                            if (!touchedIncome) income = k.incomeByDefault
                        }, label = { Text(k.label) })
                    }
                }
                Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("언제 받기로 했나요", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = dueDate == null, onClick = { due = null }, label = { Text("정하지 않음") })
                    listOf(7L to "1주 뒤", 14L to "2주 뒤").forEach { (d, label) ->
                        val day = start.plusDays(d)
                        FilterChip(selected = dueDate == day, onClick = { due = day.toString() }, label = { Text(label) })
                    }
                    val monthEnd = start.withDayOfMonth(start.lengthOfMonth()).let { if (it == start) it.plusMonths(1).withDayOfMonth(it.plusMonths(1).lengthOfMonth()) else it }
                    FilterChip(selected = dueDate == monthEnd, onClick = { due = monthEnd.toString() }, label = { Text("말일") })
                    val other = dueDate != null && dueDate != start.plusDays(7) && dueDate != start.plusDays(14) && dueDate != monthEnd
                    FilterChip(selected = other, onClick = { picking = "due" }, label = { Text(if (other) Fmt.shortDate(dueDate!!) else "날짜 고르기") })
                }
                if (dueDate != null && dueDate.isBefore(start)) {
                    Text("약속일이 생긴 날보다 앞이에요.", style = MaterialTheme.typography.bodySmall, color = LocalCalendarColors.current.warning)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("생긴 날", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = "date" }) { Text(if (start == today) "오늘" else Fmt.shortFullDate(start)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("받으면 수입으로 적기", style = MaterialTheme.typography.bodyLarge)
                        Text(if (kind == ReceivableKind.SALE) "그날 매출에 이미 넣었다면 꺼 두세요. 두 번 세지 않게요."
                            else if (kind == ReceivableKind.LOAN) "빌려준 돈은 돌려받아도 수입이 아니에요."
                            else "받은 날 돈 탭에 수입 한 줄이 생겨요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = income, onCheckedChange = { income = it; touchedIncome = true })
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, label = { Text("메모 (선택)") },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave((initial ?: Receivable(who = "", amount = 0, date = start)).copy(
                    who = who.trim(), title = title.trim(), amount = amount.toAmount(), date = start, due = dueDate,
                    kind = kind, note = note.trim(), countAsIncome = income,
                ))
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
    when (picking) {
        "date" -> PickDate(start, onDismiss = { picking = null }) { date = it.toString(); picking = null }
        "due" -> PickDate(dueDate ?: start.plusDays(7), onDismiss = { picking = null }) { due = it.toString(); picking = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDate(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) { DatePicker(state = state) }
}
