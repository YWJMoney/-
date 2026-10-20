package com.bamnat.app.ui.money

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.money.Ledger
import com.bamnat.core.money.LedgerCsv
import com.bamnat.core.money.LedgerFilter
import com.bamnat.core.money.LedgerPeriod
import com.bamnat.core.money.LedgerSort
import com.bamnat.core.money.LedgerTotals
import com.bamnat.core.money.MoneyAccounts
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** [LEDG-07] What the 내역 list shows: a period counted from the month on screen, conditions and an order. */
data class LedgerView(
    val period: LedgerPeriod = LedgerPeriod.MONTH,
    val filter: LedgerFilter = LedgerFilter(),
    val sort: LedgerSort = LedgerSort.NEWEST,
) {
    fun range(month: YearMonth): Pair<LocalDate?, LocalDate?> = period.range(month)
    fun applied(month: YearMonth): LedgerFilter = range(month).let { (from, to) -> filter.copy(from = from, to = to) }
    val narrowed: Boolean get() = filter.count > 0 || filter.query.isNotBlank()
}

/** [SWIPE-01] How far a line must slide, as a share of its width, before letting go acts on it. */
internal const val SWIPE_THRESHOLD = 0.5f

/**
 * [SWIPE-01] Asked before a line slid to the left goes: what it is, so the right one is removed.
 * After 삭제 the money view model offers 되돌리기, which puts the same line back.
 */
@Composable
internal fun DeleteLineDialog(e: MoneyEntry, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val what = listOfNotNull(Fmt.shortDate(e.date), e.title.ifBlank { e.category }, signedWon(e)).joinToString(" · ")
    com.bamnat.app.ui.components.ConfirmDialog(
        title = "삭제할까요?",
        message = "$what\n\n삭제한 뒤에도 바로 나오는 ‘되돌리기’로 다시 살릴 수 있어요.",
        confirmLabel = "삭제",
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** What the list can do to its lines; the money view model does it, with undo. */
interface LedgerListActions {
    fun open(e: MoneyEntry)
    fun delete(list: List<MoneyEntry>)
    fun duplicate(e: MoneyEntry)
    fun recategorize(list: List<MoneyEntry>, category: String)
    fun message(text: String)
}

/**
 * [LEDG-07] Every line, found by words and conditions over any period, in days with their own
 * totals. A line slides right to copy it to today and left to remove it (asked first [SWIPE-01], with undo); a long press
 * picks several to move to a category or remove together. Screen readers get the same actions.
 * [LEDG-12] What is shown can leave as a CSV file. On a wide screen the conditions stay open beside
 * the list.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LedgerListPane(
    book: MoneyBook,
    month: YearMonth,
    view: LedgerView,
    onView: (LedgerView) -> Unit,
    selection: Set<Long>,
    onSelection: (Set<Long>) -> Unit,
    actions: LedgerListActions,
    wide: Boolean,
    onAdd: () -> Unit,
) {
    val filter = remember(view, month) { view.applied(month) }
    val shown = remember(book, filter, view.sort) { Ledger.search(book.entries, filter, view.sort, book.accounts) }
    val totals = remember(shown, filter) { Ledger.totals(shown, filter) }
    val byDate = view.sort == LedgerSort.NEWEST || view.sort == LedgerSort.OLDEST
    val days = remember(shown, byDate) { if (byDate) Ledger.days(shown) else emptyList() }
    var filtering by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()
    val picked = remember(shown, selection) { shown.filter { it.id in selection } }
    BackHandler(enabled = selecting) { onSelection(emptySet()) }
    // Lines picked and then filtered away are let go.
    LaunchedEffect(shown) { if (selecting) onSelection(selection.filter { id -> shown.any { it.id == id } }.toSet()) }

    val list: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            if (selecting) {
                SelectionBar(picked.size, all = picked.size == shown.size,
                    onClose = { onSelection(emptySet()) },
                    onAll = { onSelection(if (picked.size == shown.size) emptySet() else shown.map { it.id }.toSet()) },
                    onMove = { moving = true },
                    onDelete = { actions.delete(picked); onSelection(emptySet()) })
            }
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FabClearance + 8.dp)) {
                if (!wide) {
                    item(key = "tools") {
                        Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchRow(view, onView, onFilter = { filtering = true }, onExport = { exporting = true })
                            PeriodChips(view, month, onView)
                            ActiveConditions(book, view, onView)
                            if (shown.isNotEmpty()) TotalsStrip(totals, view.filter.categories.isNotEmpty())
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        when {
                            book.entries.isEmpty() -> LedgerEmpty(Icons.AutoMirrored.Outlined.ReceiptLong, "아직 적은 돈이 없어요",
                                "‘돈 적기’를 누르거나 위 ⋮ > 자동 기록을 켜면 여기에 쌓여요.", action = "돈 적기", onAction = onAdd)
                            view.narrowed -> LedgerEmpty(Icons.Outlined.SearchOff, "조건에 맞는 기록이 없어요",
                                "기간을 넓히거나 조건을 지워 보세요.", action = "조건 지우기", onAction = { onView(view.copy(filter = LedgerFilter())) })
                            else -> LedgerEmpty(Icons.AutoMirrored.Outlined.ReceiptLong, "이 기간에 적은 돈이 없어요",
                                "다른 달로 넘기거나 기간을 ‘전체’로 바꿔 보세요.", action = "전체 기간 보기", onAction = { onView(view.copy(period = LedgerPeriod.ALL)) })
                        }
                    }
                }
                if (byDate) {
                    days.forEach { d ->
                        stickyHeader(key = "d${d.date}") { DayHeader(d.date, d.expense, d.income) }
                        items(d.entries, key = { it.id }) { e ->
                            LedgerRow(e, book, selecting, e.id in selection, actions,
                                onToggle = { onSelection(if (e.id in selection) selection - e.id else selection + e.id) },
                                modifier = Modifier.animateItem())
                        }
                    }
                } else {
                    items(shown, key = { it.id }) { e ->
                        LedgerRow(e, book, selecting, e.id in selection, actions, showDate = true,
                            onToggle = { onSelection(if (e.id in selection) selection - e.id else selection + e.id) },
                            modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            list(Modifier.weight(1.4f).fillMaxSize())
            Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchRow(view, onView, onFilter = null, onExport = { exporting = true })
                PeriodChips(view, month, onView)
                if (shown.isNotEmpty()) TotalsStrip(totals, view.filter.categories.isNotEmpty())
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("거르기", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                            if (view.filter.count > 0) TextButton(onClick = { onView(view.copy(filter = LedgerFilter(query = view.filter.query))) }) { Text("모두 지우기") }
                        }
                        FilterControls(book, view.filter) { onView(view.copy(filter = it)) }
                    }
                }
                Spacer(Modifier.height(FabClearance))
            }
        }
    } else {
        list(Modifier.fillMaxSize())
    }

    if (filtering) {
        FilterSheet(book, view, month, onApply = { onView(it); filtering = false }, onDismiss = { filtering = false })
    }
    if (exporting) {
        ExportDialog(shown, book, view.range(month), actions::message) { exporting = false }
    }
    if (moving && picked.isNotEmpty()) {
        MoveDialog(book, picked, onPick = { c -> actions.recategorize(picked, c); onSelection(emptySet()); moving = false }, onDismiss = { moving = false })
    }
}

/** The search box with the conditions, order and export beside it. */
@Composable
private fun SearchRow(view: LedgerView, onView: (LedgerView) -> Unit, onFilter: (() -> Unit)?, onExport: () -> Unit) {
    var sorting by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = view.filter.query,
            onValueChange = { onView(view.copy(filter = view.filter.copy(query = it.take(40)))) },
            singleLine = true,
            placeholder = { Text("기록 찾기", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingText = null,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (view.filter.query.isNotEmpty()) IconButton(onClick = { onView(view.copy(filter = view.filter.copy(query = ""))) }) {
                    Icon(Icons.Filled.Close, "찾는 말 지우기")
                }
            },
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).semantics { contentDescription = "가게, 메모, #태그, 금액으로 기록 찾기" },
        )
        if (onFilter != null) {
            IconButton(onClick = onFilter) {
                BadgedBox(badge = { if (view.filter.count > 0) Badge { Text("${view.filter.count}") } }) {
                    Icon(Icons.Outlined.FilterList, if (view.filter.count > 0) "거르기, ${view.filter.count}개 켜짐" else "거르기")
                }
            }
        }
        Box {
            IconButton(onClick = { sorting = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "정렬, ${view.sort.label}") }
            DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                LedgerSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        leadingIcon = { if (s == view.sort) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                        onClick = { onView(view.copy(sort = s)); sorting = false },
                    )
                }
            }
        }
        IconButton(onClick = onExport) { Icon(Icons.Outlined.FileDownload, "CSV로 내보내기") }
    }
}

@Composable
private fun PeriodChips(view: LedgerView, month: YearMonth, onView: (LedgerView) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LedgerPeriod.entries.forEach { p ->
            val label = when (p) {
                LedgerPeriod.MONTH -> Fmt.monthShort(month)
                LedgerPeriod.YEAR -> "${month.year}년"
                else -> p.label
            }
            FilterChip(selected = view.period == p, onClick = { onView(view.copy(period = p)) }, label = { Text(label) })
        }
    }
}

/** The conditions on, each a chip that takes itself off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveConditions(book: MoneyBook, view: LedgerView, onView: (LedgerView) -> Unit) {
    val f = view.filter
    if (f.count == 0) return
    fun set(next: LedgerFilter) = onView(view.copy(filter = next))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        f.kinds.forEach { k -> Removable(k.label) { set(f.copy(kinds = f.kinds - k)) } }
        f.categories.forEach { c -> Removable(c) { set(f.copy(categories = f.categories - c)) } }
        f.accounts.forEach { id -> Removable(if (id == LedgerFilter.NO_ACCOUNT) "결제수단 없음" else book.account(id)?.label ?: "결제수단") { set(f.copy(accounts = f.accounts - id)) } }
        f.tags.forEach { t -> Removable("#$t") { set(f.copy(tags = f.tags - t)) } }
        if (f.minAmount != null || f.maxAmount != null) {
            Removable(amountRange(f.minAmount, f.maxAmount)) { set(f.copy(minAmount = null, maxAmount = null)) }
        }
    }
}

private fun amountRange(min: Long?, max: Long?): String = when {
    min != null && max != null -> "${wonBrief(min)}~${wonBrief(max)}원"
    min != null -> "${wonBrief(min)}원 이상"
    else -> "${wonBrief(max ?: 0)}원 이하"
}

@Composable
private fun Removable(label: String, onRemove: () -> Unit) {
    InputChip(selected = true, onClick = onRemove, label = { Text(label, maxLines = 1) },
        trailingIcon = { Icon(Icons.Filled.Close, "$label 조건 빼기", Modifier.size(18.dp)) })
}

/** [LEDG-07] What the lines shown add up to; with categories picked, only their parts. */
@Composable
private fun TotalsStrip(t: LedgerTotals, partsOnly: Boolean) {
    val cal = LocalCalendarColors.current
    val out = (if (t.expense > 0) "-" else "") + Fmt.won(t.expense)
    val ins = (if (t.income > 0) "+" else "") + Fmt.won(t.income)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        if (LocalDensity.current.fontScale < 1.3f) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${t.count}건", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.weight(1f)) {
                    Text("나간 돈", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FitText(out, MaterialTheme.typography.titleSmall, cal.expense)
                }
                Column(Modifier.weight(1f)) {
                    Text("들어온 돈", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FitText(ins, MaterialTheme.typography.titleSmall, cal.income)
                }
            }
        } else {
            // [LEDG-15] At very large text each total gets the whole width, so no amount is cut.
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("${t.count}건", style = MaterialTheme.typography.labelLarge)
                listOf(Triple("나간 돈", out, cal.expense), Triple("들어온 돈", ins, cal.income)).forEach { (label, amount, color) ->
                    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp))
                        FitText(amount, MaterialTheme.typography.titleSmall.copy(textAlign = TextAlign.End), color, Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (partsOnly) {
        Text("나눠 적은 결제는 고른 분류의 몫만 더했어요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayHeader(date: LocalDate, expense: Long, income: Long) {
    val cal = LocalCalendarColors.current
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Fmt.dayTitle(date) + if (date.year != LocalDate.now().year) " ${date.year}" else "", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (expense != 0L) Text("-" + Fmt.won(expense), style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = cal.expense)
        if (income != 0L) {
            Spacer(Modifier.width(8.dp))
            Text("+" + Fmt.won(income), style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = cal.income)
        }
    }
}

/** The bar shown while lines are picked: how many, and what to do with them. */
@Composable
private fun SelectionBar(count: Int, all: Boolean, onClose: () -> Unit, onAll: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "고르기 끝내기") }
            Text("${count}건 골랐어요", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            IconButton(onClick = onAll) { Icon(Icons.Outlined.SelectAll, if (all) "모두 풀기" else "모두 고르기") }
            IconButton(onClick = onMove, enabled = count > 0) { Icon(Icons.AutoMirrored.Outlined.Label, "분류 바꾸기") }
            IconButton(onClick = onDelete, enabled = count > 0) { Icon(Icons.Outlined.Delete, "고른 기록 지우기") }
        }
    }
}

/**
 * [LEDG-07] One line in the list. It slides right to be copied to today and left to be removed; a
 * long press starts picking. Each action is offered to screen readers by name as well.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LedgerRow(
    e: MoneyEntry,
    book: MoneyBook,
    selecting: Boolean,
    selected: Boolean,
    actions: LedgerListActions,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    // [SWIPE-01] A slide to the left never removes the line by itself: it springs back and asks first,
    // and it has to go half the row's width, so a slanted scroll cannot set it off.
    var asking by remember { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                asking = true
                false
            } else true
        },
        positionalThreshold = { it * SWIPE_THRESHOLD },
    )
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> { haptics.performHapticFeedback(HapticFeedbackType.Confirm); actions.duplicate(e); state.reset() }
            SwipeToDismissBoxValue.EndToStart -> state.reset()
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }
    if (asking) {
        DeleteLineDialog(e, onDismiss = { asking = false }, onConfirm = { asking = false; actions.delete(listOf(e)) })
    }
    val cal = LocalCalendarColors.current
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        gesturesEnabled = !selecting,
        backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val armed = state.targetValue != SwipeToDismissBoxValue.Settled
            val tone = if (toRight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            val bg by animateColorAsState(
                when {
                    state.dismissDirection == SwipeToDismissBoxValue.Settled -> Color.Transparent
                    armed -> tone
                    else -> tone.copy(alpha = 0.3f)
                }, label = "swipe")
            Row(Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End) {
                val fg = if (armed) (if (toRight) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError) else MaterialTheme.colorScheme.onSurface
                if (toRight) {
                    Icon(Icons.Outlined.ContentCopy, null, tint = fg)
                    Spacer(Modifier.width(8.dp))
                    Text("오늘로 복사", color = fg, style = MaterialTheme.typography.labelLarge)
                } else if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Text("지우기", color = fg, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Delete, null, tint = fg)
                }
            }
        },
    ) {
        val account = MoneyAccounts.of(e, book.accounts)?.let(book::account)
        val shares = e.shares()
        val caption = listOfNotNull(
            if (showDate) Fmt.shortDate(e.date) else null,
            e.time?.let(Fmt::time),
            if (shares.size > 1) "${e.category} 외 ${shares.size - 1}" else e.category.takeIf { it != e.title },
            account?.let { a -> if (e.kind == MoneyKind.TRANSFER) "${a.name} → ${book.account(e.toAccountId)?.name ?: "?"}" else a.name },
            when (e.source) {
                MoneySource.NOTIFICATION -> "알림"; MoneySource.PASTE -> "문자"; MoneySource.MEMO -> "메모"; MoneySource.FIXED -> "반복"; MoneySource.MANUAL -> null
            },
        ).joinToString(" · ")
        val said = listOf(e.title, signedWon(e).replace("-", "마이너스 "), caption).joinToString(", ")
        Surface(
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selecting) onToggle() else actions.open(e) },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onToggle() },
                    onLongClickLabel = "고르기",
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = said
                    if (selecting) this.selected = selected
                    customActions = listOf(
                        CustomAccessibilityAction("오늘로 복사") { actions.duplicate(e); true },
                        CustomAccessibilityAction("지우기") { asking = true; true },
                        CustomAccessibilityAction(if (selected) "고르기 풀기" else "고르기") { onToggle(); true },
                    )
                },
        ) {
            Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    Checkbox(checked = selected, onCheckedChange = { onToggle() }, modifier = Modifier.size(40.dp))
                } else {
                    Box {
                        CategoryAvatar(e.category)
                        if (!e.reviewed) Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).align(Alignment.TopEnd))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (caption.isNotEmpty()) {
                        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (e.tags.isNotEmpty() || e.memo.isNotBlank()) {
                        Text((e.tags.map { "#$it" } + listOfNotNull(e.memo.takeIf { it.isNotBlank() })).joinToString(" "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(signedWon(e), style = MaterialTheme.typography.titleSmall.merge(NumberStyle), fontWeight = FontWeight.Bold, color = amountColor(e), maxLines = 1)
                    when {
                        e.installments > 1 -> Text("${e.installments}개월 할부", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        e.kind == MoneyKind.TRANSFER -> Text("이체", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        e.amount < 0 -> Text("취소", style = MaterialTheme.typography.labelSmall, color = cal.income)
                    }
                }
            }
        }
    }
}

/** [LEDG-07] The conditions, as chips: kinds, categories, accounts, tags and an amount range. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterControls(book: MoneyBook, f: LedgerFilter, onChange: (LedgerFilter) -> Unit) {
    // Only categories that have lines (and any already picked), in the user's order: an empty one finds nothing.
    val categories = remember(book, f.kinds, f.categories) {
        val kinds = f.kinds.ifEmpty { MoneyKind.entries.toSet() }
        val used = book.entries.filter { it.kind in kinds }.flatMap { e -> e.shares().map { it.first } }.toSet() + f.categories
        val order = kinds.flatMap { book.categoriesOf(it) }.distinct()
        (order.filter { it in used } + used.filter { it !in order }.sorted())
            .distinct().let { list -> list.filter { it != MoneyCategories.OTHER } + list.filter { it == MoneyCategories.OTHER } }
    }
    val tags = remember(book) { Ledger.tags(book.entries).take(16) }
    FilterTitle("구분")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MoneyKind.entries.forEach { k ->
            FilterChip(selected = k in f.kinds, onClick = { onChange(f.copy(kinds = f.kinds.toggle(k))) }, label = { Text(k.label) })
        }
    }
    if (categories.isNotEmpty()) FilterTitle("분류")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        categories.forEach { c ->
            FilterChip(selected = c in f.categories, onClick = { onChange(f.copy(categories = f.categories.toggle(c))) }, label = { Text(c) },
                leadingIcon = { Icon(categoryIcon(c), null, Modifier.size(18.dp)) })
        }
    }
    if (book.accounts.isNotEmpty()) {
        FilterTitle("결제수단")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            book.accounts.sortedWith(compareBy({ it.hidden }, { it.order })).forEach { a ->
                FilterChip(selected = a.id in f.accounts, onClick = { onChange(f.copy(accounts = f.accounts.toggle(a.id))) }, label = { Text(a.label) },
                    leadingIcon = { Icon(accountIcon(a.type), null, Modifier.size(18.dp)) })
            }
            FilterChip(selected = LedgerFilter.NO_ACCOUNT in f.accounts, onClick = { onChange(f.copy(accounts = f.accounts.toggle(LedgerFilter.NO_ACCOUNT))) },
                label = { Text("안 고른 기록") })
        }
    }
    FilterTitle("금액")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AmountField("최소", amountDigits(f.minAmount ?: 0), { onChange(f.copy(minAmount = it.toAmount().takeIf { v -> v > 0 })) }, Modifier.weight(1f))
        Text("~")
        AmountField("최대", amountDigits(f.maxAmount ?: 0), { onChange(f.copy(maxAmount = it.toAmount().takeIf { v -> v > 0 })) }, Modifier.weight(1f))
    }
    if (tags.isNotEmpty()) {
        FilterTitle("태그")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { t -> FilterChip(selected = t in f.tags, onClick = { onChange(f.copy(tags = f.tags.toggle(t))) }, label = { Text("#$t") }) }
        }
    }
}

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v

@Composable
private fun FilterTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp).semantics { heading() })
}

/** [LEDG-07] The conditions in a sheet, with how many lines they would leave before they are applied. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(book: MoneyBook, view: LedgerView, month: YearMonth, onApply: (LedgerView) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(view.filter) }
    val count = remember(book, f, view.period, month) { Ledger.search(book.entries, view.copy(filter = f).applied(month), view.sort, book.accounts).size }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("거르기", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(enabled = f.count > 0, onClick = { f = LedgerFilter(query = f.query) }) { Text("모두 지우기") }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "닫기") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterControls(book, f) { f = it }
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider()
            Button(onClick = { onApply(view.copy(filter = f)) },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp).heightIn(min = 48.dp)) {
                Text("${count}건 보기")
            }
        }
    }
}

/** [LEDG-07] Moves the picked lines to one category; lines of another kind are left as they are. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoveDialog(book: MoneyBook, picked: List<MoneyEntry>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val kind = picked.groupingBy { it.kind }.eachCount().maxBy { it.value }.key
    val same = picked.count { it.kind == kind }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("분류 바꾸기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (same == picked.size) "${kind.label} ${same}건을 어느 분류로 옮길까요?"
                    else "${kind.label} ${same}건만 옮겨요. 다른 구분의 기록은 그대로예요.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    book.categoriesOf(kind).forEach { c ->
                        FilterChip(selected = false, onClick = { onPick(c) }, label = { Text(c) },
                            leadingIcon = { Icon(categoryIcon(c), null, Modifier.size(18.dp), tint = categoryColor(c)) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * [LEDG-12] What is shown, as a CSV file for a spreadsheet: shared through the phone's share sheet
 * or saved where the user picks. It is made on the phone and goes nowhere else.
 */
@Composable
private fun ExportDialog(shown: List<MoneyEntry>, book: MoneyBook, range: Pair<LocalDate?, LocalDate?>, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = remember(range) { LedgerCsv.fileName(range.first, range.second) }
    // Oldest first, as a spreadsheet reads.
    val csv = remember(shown, book) { LedgerCsv.write(shown.sortedWith(compareBy<MoneyEntry> { it.date }.thenBy { it.time }.thenBy { it.id }), book.accounts) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LedgerExport.MIME)) { uri ->
        if (uri == null) onDismiss() else scope.launch {
            onMessage(if (LedgerExport.save(context, uri, csv)) "파일로 저장했어요" else "파일을 저장하지 못했어요")
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.FileDownload, null) },
        title = { Text("CSV로 내보내기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("지금 보이는 기록 ${shown.size}건을 엑셀·구글 시트에서 여는 파일로 만들어요.", style = MaterialTheme.typography.bodyMedium)
                Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("파일은 이 휴대폰에서 만들어지고, 보낼 곳은 직접 골라요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // [SHOP-07] Daily sales and jobs as their own files.
                WorkCsvButtons(book, range, onMessage, onDismiss)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = shown.isNotEmpty(), onClick = { runCatching { save.launch(name) }.onFailure { onMessage("파일을 저장할 곳을 열지 못했어요"); onDismiss() } }) {
                    Text("파일로 저장")
                }
                FilledTonalButton(enabled = shown.isNotEmpty(), onClick = {
                    scope.launch {
                        runCatching { LedgerExport.share(context, csv, name) }.onFailure { onMessage("공유하지 못했어요") }
                        onDismiss()
                    }
                }) { Text("공유하기") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
