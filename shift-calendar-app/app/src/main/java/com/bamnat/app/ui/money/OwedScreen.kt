package com.bamnat.app.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.Owed
import com.bamnat.core.money.Receivable
import com.bamnat.core.money.ReceivableKind
import com.bamnat.core.money.ReceivablePayment
import com.bamnat.core.money.ReceivableStatus
import com.bamnat.core.money.Receivables
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** [RECV-01][RECV-02] 받을 돈: everything owed to the user, written down here or left on a job. */
class OwedViewModel(private val container: AppContainer) : ViewModel() {
    private val money = container.moneyRepository
    val book: StateFlow<MoneyBook?> = money.book.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OwedScreen(vm: OwedViewModel, wide: Boolean, onBack: () -> Unit, today: LocalDate = LocalDate.now()) {
    val bookOrNull by vm.book.collectAsStateWithLifecycle()
    val book = bookOrNull ?: MoneyBook.EMPTY
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Receivable?>(null) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingJob by remember { mutableStateOf<Job?>(null) }
    var show by rememberSaveable { mutableStateOf(Show.OWED) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.message, actionLabel = m.undo?.let { m.actionLabel }, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.runUndo(m)
        }
    }
    val owed = remember(book, today) { book.owed(today) }
    val summary = remember(book, today) { Receivables.summary(book.receivables, book.jobs, today) }
    val done = remember(book) { book.receivables.filter { it.left == 0L }.sortedByDescending { it.lastPaid ?: it.closedOn ?: it.date } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("받을 돈") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, "받을 돈 적기") }, text = { Text("받을 돈 적기") })
        },
    ) { padding ->
        val top: @Composable ColumnScope.() -> Unit = {
            Totals(summary)
            if (summary.byWho.size > 1 || summary.aging.drop(1).any { it > 0 }) Breakdown(summary)
        }
        val list: @Composable ColumnScope.() -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Show.entries.forEach { s ->
                    FilterChip(selected = show == s, onClick = { show = s },
                        label = { Text(if (s == Show.OWED) "${s.label} ${owed.size}" else "${s.label} ${done.size}") })
                }
            }
            when (show) {
                Show.OWED -> if (owed.isEmpty()) Empty() else SectionCard(title = null) {
                    owed.forEachIndexed { i, o ->
                        if (i > 0) HorizontalDivider()
                        OwedRow(o, today) {
                            when (o) {
                                is Owed.Written -> openId = o.r.id
                                is Owed.FromJob -> editingJob = o.job
                            }
                        }
                    }
                }
                Show.DONE -> if (done.isEmpty()) {
                    Text("다 받았거나 받지 않기로 한 것이 여기에 모여요.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else SectionCard(title = null) {
                    done.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider()
                        OwedRow(Owed.Written(r), today) { openId = r.id }
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { top(); Spacer(Modifier.height(80.dp)) }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { list(); Spacer(Modifier.height(80.dp)) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) { top(); list(); Spacer(Modifier.height(80.dp)) }
        }
    }

    val names = remember(book) { (book.receivables.map { it.who } + book.jobs.map { it.client }).map { it.trim() }.filter { it.isNotBlank() }.distinct() }
    if (adding) ReceivableDialog(null, names, today, onDismiss = { adding = false }) { vm.save(it); adding = false }
    editing?.let { r -> ReceivableDialog(r, names, today, onDismiss = { editing = null }) { vm.save(it); editing = null } }
    val opened = openId?.let { id -> book.receivables.firstOrNull { it.id == id } }
    // Deleted meanwhile (or by its own button): the sheet closes. Waits for the book to load first.
    LaunchedEffect(opened == null, bookOrNull == null) { if (opened == null && bookOrNull != null) openId = null }
    opened?.let { r ->
        ReceivableSheet(r, today,
            onDismiss = { openId = null },
            onReceive = { amount, date -> vm.receive(r, amount, date) },
            onDeletePayment = vm::deletePayment,
            onEdit = { editing = r; openId = null },
            onClose = { vm.close(r, it); if (it) openId = null },
            onDelete = { vm.delete(r); openId = null })
    }
    editingJob?.let { j ->
        JobDialog(j.date, j, onSave = { vm.saveJob(it); editingJob = null }, onDismiss = { editingJob = null })
    }
}

@Composable
private fun Totals(s: com.bamnat.core.money.OwedSummary) {
    SectionCard(title = "아직 못 받은 돈") {
        Text(Fmt.won(s.total), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(if (s.count == 0) "받을 돈이 없어요" else "${s.count}건", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("약속일 지남", Fmt.won(s.overdue), Modifier.weight(1f), caption = "${s.overdueCount}건", warning = s.overdue > 0)
            StatTile("3일 안에 받을 날", Fmt.won(s.dueSoon), Modifier.weight(1f))
            StatTile("이번 달 받은 돈", Fmt.won(s.receivedThisMonth), Modifier.weight(1f))
        }
    }
}

/** Who owes most, and how long it has been. */
@Composable
private fun Breakdown(s: com.bamnat.core.money.OwedSummary) {
    SectionCard(title = "누가, 얼마나 오래") {
        val max = (s.byWho.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
        s.byWho.take(5).forEach { (who, amount) -> MeterRow(who, Fmt.won(amount), amount.toFloat() / max, MaterialTheme.colorScheme.primary) }
        if (s.byWho.size > 5) Text("그 밖에 ${s.byWho.size - 5}명", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val ageMax = (s.aging.maxOrNull() ?: 1).coerceAtLeast(1)
        val warn = LocalCalendarColors.current.warning
        s.aging.forEachIndexed { i, v ->
            MeterRow(Receivables.AGING_LABELS[i], Fmt.won(v), v.toFloat() / ageMax, if (i >= 2) warn else MaterialTheme.colorScheme.tertiary)
        }
        Text("약속일이 있으면 약속일부터, 없으면 생긴 날부터 세요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun MeterRow(label: String, value: String, fraction: Float, color: Color) {
    Column(Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) {}) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

@Composable
private fun Empty() {
    SectionCard(title = "받을 돈을 적어 두세요") {
        Text("외상 매출, 밀린 일당, 빌려준 돈처럼 아직 못 받은 돈을 적어 두면 약속일에 알려 주고, 나눠 받은 것도 남은 금액으로 계산해요. " +
            "돈 탭에서 적은 일감의 남은 잔금도 여기에 함께 보여요.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusChip(status: ReceivableStatus) {
    val cal = LocalCalendarColors.current
    val (bg, fg) = when (status) {
        ReceivableStatus.OVERDUE -> cal.warningContainer to cal.warning
        ReceivableStatus.DUE_SOON -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ReceivableStatus.PAID -> MaterialTheme.colorScheme.secondaryContainer to cal.income
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(status.label, style = MaterialTheme.typography.labelSmall, color = fg, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
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

@Composable
private fun OwedRow(o: Owed, today: LocalDate, onClick: () -> Unit) {
    val status = o.status(today)
    val r = (o as? Owed.Written)?.r
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(o.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(o.who.trim().takeIf { it.isNotBlank() && it != o.name }, if (o is Owed.FromJob) "일감 잔금" else r?.kind?.label,
                        if (r == null || r.left > 0) dueText(o, today) else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.won(if (r != null && r.left == 0L) r.amount else o.left), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                StatusChip(status)
            }
        }
        if (r != null && r.received > 0 && r.left > 0) {
            Spacer(Modifier.height(6.dp))
            MeterRow("${Fmt.won(r.received)} 받음", "남은 ${Fmt.won(r.left)}", r.received.toFloat() / r.amount.coerceAtLeast(1), LocalCalendarColors.current.income)
        }
    }
}

/** One receivable: what came back, a button for what comes now, and the rest of its actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceivableSheet(
    r: Receivable,
    today: LocalDate,
    onDismiss: () -> Unit,
    onReceive: (Long, LocalDate) -> Unit,
    onDeletePayment: (ReceivablePayment) -> Unit,
    onEdit: () -> Unit,
    onClose: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var amount by rememberSaveable(r.id, r.left) { mutableStateOf(amountDigits(r.left)) }
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(r.who.takeIf { it.isNotBlank() && it != r.name }, r.kind.label).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${Fmt.shortFullDate(r.date)}부터" + (r.due?.let { " · 약속일 ${Fmt.shortDate(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(r.status(today))
                }
                Text("전체 ${Fmt.won(r.amount)} · 받은 돈 ${Fmt.won(r.received)} · 남은 돈 ${Fmt.won(r.left)}",
                    style = MaterialTheme.typography.titleSmall)
                if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodyMedium)
                if (r.payments.isNotEmpty()) {
                    Text("받은 기록", style = MaterialTheme.typography.labelLarge)
                    r.payments.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${Fmt.shortDate(p.date)}  ${Fmt.won(p.amount)}" + if (p.entryId != null) " · 수입에 적음" else "",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onDeletePayment(p) }) { Text("취소") }
                        }
                    }
                }
                if (r.left > 0) {
                    HorizontalDivider()
                    Text("돈이 들어왔나요?", style = MaterialTheme.typography.labelLarge)
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
                        if (r.countAsIncome) "받은 돈은 돈 탭 수입에도 적어요." else "수입에는 적지 않아요 (${r.kind.hint}).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onReceive(amount.toAmount(), LocalDate.parse(date)); amount = "" },
                        enabled = amount.toAmount() > 0, modifier = Modifier.fillMaxWidth()) {
                        val a = Receivables.clampPayment(r, amount.toAmount())
                        Text(if (a > 0) "${Fmt.won(a)} 받았어요" else "받았어요")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("고치기") }
                    if (r.closedOn == null && r.left > 0) OutlinedButton(onClick = { onClose(true) }, modifier = Modifier.weight(1f)) { Text("받지 않기로") }
                    if (r.closedOn != null) OutlinedButton(onClick = { onClose(false) }, modifier = Modifier.weight(1f)) { Text("다시 받을 돈으로") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = { TextButton(onClick = { confirmDelete = true }) { Text("지우기", color = MaterialTheme.colorScheme.error) } },
    )
    if (pickDate) PickDate(LocalDate.parse(date), onDismiss = { pickDate = false }) { date = it.toString(); pickDate = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 기록을 지울까요?") },
            text = { Text("받은 기록과 그때 수입에 적은 줄도 함께 지워요. 바로 되돌릴 수 있어요.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("지우기", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

/** [RECV-01] Writing down what someone owes: who, how much, what kind, and when they said they would pay. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceivableDialog(
    initial: Receivable?,
    names: List<String>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Receivable) -> Unit,
) {
    var who by rememberSaveable { mutableStateOf(initial?.who.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(amountDigits(initial?.amount ?: 0)) }
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: ReceivableKind.SALE) }
    var income by rememberSaveable { mutableStateOf(initial?.countAsIncome ?: ReceivableKind.SALE.incomeByDefault) }
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
