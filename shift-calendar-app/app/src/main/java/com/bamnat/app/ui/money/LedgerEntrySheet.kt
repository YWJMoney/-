package com.bamnat.app.ui.money

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.bamnat.app.ui.components.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.data.db.decodeParts
import com.bamnat.app.data.db.encodeParts
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.money.EntryPart
import com.bamnat.core.money.Ledger
import com.bamnat.core.money.MoneyAccounts
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.PaymentParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/** Most months a card payment is spread over. */
private const val MAX_INSTALLMENTS = 36

/**
 * [LEDG-13] The book of the calendar on screen, for editors opened without one (the calendar's
 * day sheet): its accounts, categories and past lines make the smart defaults.
 */
@Composable
fun rememberLedger(given: MoneyBook?): MoneyBook {
    if (given != null) return given
    val c = LocalContext.current.appContainer
    val active by c.calendars.active.collectAsStateWithLifecycle()
    val book by remember(active) { c.calendars.money(active).book }.collectAsStateWithLifecycle(null)
    return book ?: MoneyBook.EMPTY
}

/**
 * Writes or edits one ledger line [LEDG-01], as a sheet with the amount first. An [initial] with
 * id 0 is a new line filled in ahead (from a notification the app could not finish [LEDG-02]).
 * [LEDG-13] A new line starts on today, the last category and account used for its kind, and a
 * place written before fills its category, amount and account with one tap. [LEDG-08] picks the
 * account (a transfer, both ends; a credit card, its installments) and [LEDG-11] adds a memo,
 * tags and parts for other categories.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EntryDialog(
    date: LocalDate,
    initial: MoneyEntry?,
    defaultKind: MoneyKind = MoneyKind.EXPENSE,
    onSave: (MoneyEntry) -> Unit,
    onDelete: ((MoneyEntry) -> Unit)? = null,
    onDismiss: () -> Unit,
    frequent: (MoneyKind) -> List<Long> = { emptyList() },
    rules: Map<String, String> = emptyMap(),
    book: MoneyBook? = null,
    /** [LEDG-07] Copies a saved line to today. */
    onDuplicate: ((MoneyEntry) -> Unit)? = null,
    /** [LEDG-13] Saves and opens a fresh line, for a pile of receipts. */
    onSaveAndNext: ((MoneyEntry) -> Unit)? = null,
    onEditCategories: (() -> Unit)? = null,
) {
    val ledger = rememberLedger(book)
    val entries = ledger.entries
    val accounts = ledger.activeAccounts
    val isNew = initial == null || initial.id == 0L
    val haptics = LocalHapticFeedback.current
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: defaultKind) }
    val categories = remember(ledger, kind) { ledger.categoriesOf(kind) }
    // A cancellation is stored negative (or, from a kept foreign text, still 0 and marked "결제 취소").
    val cancel = (initial?.amount ?: 0) < 0 || initial?.note?.startsWith("결제 취소") == true
    var digits by rememberSaveable { mutableStateOf(amountDigits(abs(initial?.amount ?: 0))) }
    var category by rememberSaveable {
        mutableStateOf(initial?.category ?: Ledger.lastCategory(entries, kind)?.takeIf { it in categories } ?: categories.first())
    }
    var categoryTouched by rememberSaveable { mutableStateOf(initial != null) }
    var place by rememberSaveable { mutableStateOf(initial?.merchant?.ifBlank { initial.note }.orEmpty()) }
    var day by rememberSaveable { mutableStateOf((initial?.date ?: date).toString()) }
    var account by rememberSaveable { mutableStateOf(initial?.accountId ?: if (isNew) MoneyAccounts.lastUsed(entries, kind, ledger.accounts) else null) }
    var toAccount by rememberSaveable { mutableStateOf(initial?.toAccountId) }
    var accountTouched by rememberSaveable { mutableStateOf(!isNew) }
    // [LEDG-13] Opened from a day, the book may arrive after the sheet: the defaults follow it once, unless already chosen.
    LaunchedEffect(entries.isEmpty(), ledger.accounts.isEmpty()) {
        if (initial != null) return@LaunchedEffect
        if (!accountTouched && account == null) account = MoneyAccounts.lastUsed(entries, kind, ledger.accounts)
        if (!categoryTouched && place.isBlank()) Ledger.lastCategory(entries, kind)?.takeIf { it in categories }?.let { category = it }
    }
    var memo by rememberSaveable { mutableStateOf(initial?.memo.orEmpty()) }
    var tagsText by rememberSaveable { mutableStateOf(initial?.tags.orEmpty().joinToString(" ") { "#$it" }) }
    var partsText by rememberSaveable { mutableStateOf(encodeParts(initial?.parts.orEmpty())) }
    var installments by rememberSaveable { mutableIntStateOf(initial?.installments ?: 1) }
    // [FLD-08] 증빙 and a receipt photo, for field and shop costs.
    var proof by rememberSaveable { mutableStateOf(initial?.proof.orEmpty()) }
    var receipt by rememberSaveable { mutableStateOf(initial?.receipt.orEmpty()) }
    // [LEDG-25] The time a receipt said, for a new line filled from it.
    var readTime by rememberSaveable { mutableStateOf<String?>(null) }
    var scanNote by rememberSaveable { mutableStateOf<String?>(null) }
    val proofs = ledger.style == com.bamnat.core.work.WorkStyle.FIELD || ledger.style == com.bamnat.core.work.WorkStyle.BUSINESS
    var more by rememberSaveable {
        mutableStateOf(initial != null && (initial.memo.isNotBlank() || initial.tags.isNotEmpty() || initial.parts.isNotEmpty()))
    }
    var pickingDate by remember { mutableStateOf(false) }
    var placeFocused by remember { mutableStateOf(false) }
    val onDay = LocalDate.parse(day)
    val amount = digits.toAmount()
    val parts = decodeParts(partsText)
    val partsSum = parts.sumOf { it.amount }
    val selected = ledger.account(account)
    val credit = kind == MoneyKind.EXPENSE && selected?.isCredit == true
    val splitOk = parts.isEmpty() || partsSum <= amount
    val amountFocus = remember { FocusRequester() }
    // A new line starts in the amount with the keypad up: amount first, the rest is optional.
    if (isNew && initial == null) LaunchedEffect(Unit) { runCatching { amountFocus.requestFocus() } }

    fun build(): MoneyEntry {
        // A cancelled payment is stored negative; editing it keeps the sign.
        val signed = if (cancel) -amount else amount
        val base = initial ?: MoneyEntry(date = onDay, kind = kind, amount = signed, category = category)
        return base.copy(
            date = onDay, kind = kind, amount = signed, category = category, merchant = place.trim(),
            note = if (initial?.merchant.isNullOrBlank()) "" else initial!!.note, reviewed = true,
            accountId = account, toAccountId = toAccount.takeIf { kind == MoneyKind.TRANSFER && it != account },
            memo = memo.trim(), tags = Ledger.parseTags(tagsText),
            parts = if (kind == MoneyKind.TRANSFER) emptyList() else parts.filter { it.amount > 0 && it.category != category },
            installments = if (credit) installments else 1,
            proof = if (kind == MoneyKind.EXPENSE) proof else "", receipt = if (kind == MoneyKind.EXPENSE) receipt else "",
            time = readTime?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() } ?: base.time,
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            // Title row: what this is, and the line's own actions while editing.
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isNew) "돈 적기" else "기록 고치기", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (!isNew && onDuplicate != null) {
                    IconButton(onClick = { onDuplicate(initial!!) }) { Icon(Icons.Outlined.ContentCopy, "오늘 날짜로 복사") }
                }
                if (!isNew && onDelete != null) {
                    IconButton(onClick = { onDelete(initial!!) }) { Icon(Icons.Outlined.Delete, "지우기", tint = MaterialTheme.colorScheme.error) }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "닫기") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MoneyKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = kind == k,
                            onClick = {
                                if (kind == k) return@SegmentedButton
                                kind = k
                                val list = ledger.categoriesOf(k)
                                if (category !in list) category = (if (isNew) Ledger.lastCategory(entries, k)?.takeIf { it in list } else null) ?: list.first()
                                if (isNew) account = MoneyAccounts.lastUsed(entries, k, ledger.accounts) ?: account
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, MoneyKind.entries.size),
                        ) { Text(k.label) }
                    }
                }
                // [LEDG-25] A new spending line can be filled from a receipt photo, read on the phone.
                if (isNew && kind == MoneyKind.EXPENSE) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ReceiptScanChip(
                            onRead = { d, name ->
                                receipt = name
                                d.amount?.let { digits = amountDigits(it) }
                                d.date?.let { day = it.toString() }
                                readTime = d.time?.toString()
                                if (d.merchant.isNotBlank()) place = d.merchant
                            },
                            onMessage = { scanNote = it },
                        )
                        scanNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
                AmountField(
                    if (cancel) "돌려받은 금액" else "금액", digits, { digits = it }, Modifier.focusRequester(amountFocus),
                    textStyle = MaterialTheme.typography.headlineSmall.merge(NumberStyle),
                )
                val quick = remember(kind) { frequent(kind) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    quick.forEach { a -> FilterChip(selected = amount == a, onClick = { digits = amountDigits(a) }, label = { Text(Fmt.won(a)) }) }
                    // Cash is counted out in notes: add a note at a time.
                    listOf(10_000L, 50_000L).forEach { step ->
                        AssistChip(onClick = { digits = amountDigits((amount + step).coerceAtMost(99_999_999_999L)) },
                            label = { Text("+${wonBrief(step)}") })
                    }
                }
                DateRow(onDay, onPick = { day = it.toString() }, onOpen = { pickingDate = true })
                OutlinedTextField(
                    value = place,
                    onValueChange = { v ->
                        place = v.take(40)
                        // A place seen before (or a known brand) fills its category until the user picks one.
                        if (!categoryTouched && kind == MoneyKind.EXPENSE) {
                            val guess = PaymentParser.guessCategory(place.trim(), rules)
                            if (guess != MoneyCategories.OTHER && guess in categories) category = guess
                        }
                    },
                    singleLine = true,
                    label = { Text(if (kind == MoneyKind.INCOME) "어디서 받은 돈 (선택)" else "어디서·무엇 (선택)") },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { placeFocused = it.isFocused },
                )
                val suggestions = remember(entries, place, kind) { Ledger.suggest(entries, place, kind, limit = 4) }
                if (suggestions.isNotEmpty() && (placeFocused || (isNew && place.isBlank() && amount == 0L))) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { s ->
                            SuggestionChip(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    place = s.merchant
                                    if (s.category in categories) { category = s.category; categoryTouched = true }
                                    if (amount == 0L) digits = amountDigits(s.amount)
                                    s.accountId?.takeIf { id -> accounts.any { it.id == id } }?.let { account = it; accountTouched = true }
                                },
                                icon = { Icon(Icons.Outlined.History, null, Modifier.size(16.dp)) },
                                label = { Text("${s.merchant} · ${wonBrief(s.amount)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                FieldTitle("분류")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = category == c, onClick = { category = c; categoryTouched = true },
                            label = { Text(c) },
                            leadingIcon = { Icon(categoryIcon(c), null, Modifier.size(18.dp), tint = if (category == c) categoryColor(c) else MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                    if (onEditCategories != null) {
                        AssistChip(onClick = onEditCategories, label = { Text("분류 편집") },
                            leadingIcon = { Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp)) })
                    }
                }
                if (accounts.isNotEmpty()) {
                    FieldTitle(if (kind == MoneyKind.TRANSFER) "어디서" else if (kind == MoneyKind.INCOME) "들어온 곳" else "결제수단")
                    AccountChips(accounts, account) { account = it; accountTouched = true }
                    if (kind == MoneyKind.TRANSFER) {
                        FieldTitle("어디로")
                        AccountChips(accounts.filter { it.id != account }, toAccount) { toAccount = it }
                    }
                }
                if (credit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("할부", style = MaterialTheme.typography.bodyLarge)
                            if (installments > 1 && amount > 0) {
                                Text("한 달에 약 ${Fmt.won(amount / installments)}씩 카드값에 들어가요", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(enabled = installments > 1, onClick = { installments-- }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                        Text(if (installments <= 1) "일시불" else "${installments}개월", style = MaterialTheme.typography.titleMedium)
                        TextButton(enabled = installments < MAX_INSTALLMENTS, onClick = { installments = if (installments <= 1) 2 else installments + 1 }) {
                            Text("+", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                if (kind == MoneyKind.EXPENSE && (proofs || proof.isNotBlank() || receipt.isNotBlank())) {
                    ProofSection(proof, { proof = it }, receipt, { receipt = it })
                }
                if (!initial?.note.isNullOrBlank() && !initial?.merchant.isNullOrBlank()) {
                    Text(initial!!.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    kind == MoneyKind.TRANSFER -> Hint("내 돈을 옮긴 거라 들어온 돈·나간 돈에는 넣지 않아요. 카드값도 여기에 적어요.")
                    kind == MoneyKind.INCOME && category == MoneyCategories.SALARY -> Hint("실제로 받은 급여를 적으면 그달은 예상 급여 대신 이 금액으로 계산해요.")
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { more = !more }.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("메모·태그·나눠 적기", style = MaterialTheme.typography.titleSmall)
                        val summary = listOfNotNull(
                            memo.trim().takeIf { it.isNotEmpty() }?.let { "메모" },
                            Ledger.parseTags(tagsText).takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                            parts.takeIf { it.isNotEmpty() }?.let { "${it.size + 1}개 분류로 나눔" },
                        ).joinToString(" · ")
                        if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(if (more) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (more) "접기" else "펼치기")
                }
                AnimatedVisibility(more) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = memo, onValueChange = { memo = it.take(200) }, label = { Text("메모") },
                            modifier = Modifier.fillMaxWidth(), maxLines = 3)
                        TagsField(tagsText, Ledger.tags(entries).take(8)) { tagsText = it }
                        if (kind != MoneyKind.TRANSFER) {
                            SplitEditor(category, categories, amount, parts) { partsText = encodeParts(it) }
                        }
                    }
                }
                Spacer(Modifier.padding(bottom = 4.dp))
            }
            // The buttons stay in reach above the keyboard.
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val ready = amount > 0 && splitOk
                if (isNew && onSaveAndNext != null) {
                    OutlinedButton(enabled = ready, onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onSaveAndNext(build())
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("저장하고 하나 더") }
                }
                Button(enabled = ready, onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSave(build())
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("저장") }
            }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = onDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    pickingDate = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("취소") } },
        ) { DatePicker(state, showModeToggle = false) }
    }
}

@Composable
private fun FieldTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() })
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** ‹ the day › with 오늘 and 어제 one tap away; the date itself opens a calendar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateRow(onDay: LocalDate, onPick: (LocalDate) -> Unit, onOpen: () -> Unit) {
    val today = LocalDate.now()
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onPick(onDay.minusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "전날") }
        TextButton(onClick = onOpen, modifier = Modifier.weight(1f).semantics { contentDescription = "${Fmt.dayTitle(onDay)}, 날짜 고르기" }) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(Fmt.dayTitle(onDay) + if (onDay.year != today.year) " ${onDay.year}" else "", style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center, maxLines = 1)
        }
        IconButton(onClick = { onPick(onDay.plusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음날") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = onDay == today, onClick = { onPick(today) }, label = { Text("오늘") })
        FilterChip(selected = onDay == today.minusDays(1), onClick = { onPick(today.minusDays(1)) }, label = { Text("어제") })
        FilterChip(selected = onDay == today.minusDays(2), onClick = { onPick(today.minusDays(2)) }, label = { Text("그저께") })
    }
}

/** [LEDG-08] The accounts to pick one from; a second tap on the picked one clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountChips(accounts: List<com.bamnat.core.money.MoneyAccount>, selected: Long?, onPick: (Long?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text("안 고름") })
        accounts.forEach { a ->
            FilterChip(
                selected = selected == a.id, onClick = { onPick(if (selected == a.id) null else a.id) },
                label = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(accountIcon(a.type), null, Modifier.size(18.dp), tint = if (selected == a.id) accountColor(a) else MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}

/** [LEDG-11] Tags typed with or without '#', the ones used most a tap away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsField(text: String, recent: List<String>, onChange: (String) -> Unit) {
    val now = Ledger.parseTags(text)
    OutlinedTextField(
        value = text, onValueChange = { onChange(it.take(80)) }, singleLine = true,
        label = { Text("태그") }, placeholder = { Text("#여행 #회식") }, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("${Ledger.MAX_TAGS}개까지, 띄어 쓰면 따로 붙어요") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
    if (recent.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            recent.forEach { t ->
                val on = t in now
                FilterChip(selected = on, onClick = {
                    val next = if (on) now - t else (now + t).take(Ledger.MAX_TAGS)
                    onChange(next.joinToString(" ") { "#$it" })
                }, label = { Text("#$t") })
            }
        }
    }
}

/**
 * [LEDG-11] One payment over several categories: each part names its category and amount, and
 * what is left stays with the line's own category.
 */
@Composable
private fun SplitEditor(main: String, categories: List<String>, amount: Long, parts: List<EntryPart>, onChange: (List<EntryPart>) -> Unit) {
    val cal = LocalCalendarColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("나눠 적기", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            com.bamnat.app.ui.components.AddButton("분류 더하기", onClick = {
                val next = categories.firstOrNull { c -> c != main && parts.none { it.category == c } }
                if (next != null) onChange(parts + EntryPart(next, 0))
            })
        }
        if (parts.isEmpty()) {
            Hint("장보며 생활용품도 샀다면 한 결제를 여러 분류로 나눠 적을 수 있어요.")
            return@Column
        }
        parts.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryPicker(p.category, categories.filter { it != main }, Modifier.weight(1f)) { c ->
                    onChange(parts.mapIndexed { k, q -> if (k == i) q.copy(category = c) else q })
                }
                AmountField("금액", amountDigits(p.amount), { d -> onChange(parts.mapIndexed { k, q -> if (k == i) q.copy(amount = d.toAmount()) else q }) },
                    Modifier.weight(1.3f))
                IconButton(onClick = { onChange(parts.filterIndexed { k, _ -> k != i }) }) { Icon(Icons.Filled.Close, "${p.category} 나눔 빼기") }
            }
        }
        val left = amount - parts.sumOf { it.amount }
        Text(
            if (left < 0) "나눈 금액이 전체보다 ${Fmt.won(-left)} 많아요" else "나머지 ${Fmt.won(left)}은 $main",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            color = if (left < 0) cal.warning else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A category as a button that opens the list to pick another. */
@Composable
fun CategoryPicker(value: String, options: List<String>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(categoryIcon(value), null, Modifier.size(18.dp), tint = categoryColor(value))
            Spacer(Modifier.width(6.dp))
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { c ->
                DropdownMenuItem(text = { Text(c) }, leadingIcon = { Icon(categoryIcon(c), null, tint = categoryColor(c)) },
                    onClick = { onPick(c); open = false })
            }
        }
    }
}
