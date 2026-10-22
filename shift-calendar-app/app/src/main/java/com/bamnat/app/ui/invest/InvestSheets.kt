package com.bamnat.app.ui.invest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.money.AmountField
import com.bamnat.app.ui.money.amountDigits
import com.bamnat.app.ui.money.toAmount
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.invest.AccountType
import com.bamnat.core.invest.AlarmKind
import com.bamnat.core.invest.CashFlow
import com.bamnat.core.invest.CashKind
import com.bamnat.core.invest.Emotion
import com.bamnat.core.invest.EPS
import com.bamnat.core.invest.InvestAccount
import com.bamnat.core.invest.InvestAlarm
import com.bamnat.core.invest.InvestEvent
import com.bamnat.core.invest.InvestFees
import com.bamnat.core.invest.InvestTaxes
import com.bamnat.core.invest.Market
import com.bamnat.core.invest.Quote
import com.bamnat.core.invest.REASON_TAGS
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.Trade
import com.bamnat.core.invest.TradeDraft
import com.bamnat.core.invest.holdingKey
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToLong

/** [INV-01] Which of the investor's sheets is open. */
sealed interface InvestSheet {
    data class TradeEdit(val draft: TradeDraft? = null, val editing: Trade? = null) : InvestSheet
    data class CashEdit(val kind: CashKind = CashKind.DEPOSIT, val date: LocalDate? = null, val editing: CashFlow? = null) : InvestSheet
    data class AccountEdit(val editing: InvestAccount? = null) : InvestSheet
    data object Quotes : InvestSheet
    data class AlarmEdit(val kind: AlarmKind = AlarmKind.PLAN, val editing: InvestAlarm? = null) : InvestSheet
    data class EventEdit(val date: LocalDate? = null, val editing: InvestEvent? = null) : InvestSheet
    data object Goal : InvestSheet
    data object Principles : InvestSheet
    data class Journal(val date: LocalDate) : InvestSheet
}

/** Opens [sheet] over whichever screen asked: the 투자 tab, the calendar or the day. */
@Composable
fun InvestSheetHost(vm: InvestViewModel, sheet: InvestSheet, onSheet: (InvestSheet?) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val close = { onSheet(null) }
    // A new sheet (a trade copied from the one open) starts from its own values.
    androidx.compose.runtime.key(sheet) { when (sheet) {
        is InvestSheet.TradeEdit -> TradeSheet(vm, state, sheet.draft, sheet.editing, onDismiss = close,
            onCopy = { t -> onSheet(InvestSheet.TradeEdit(TradeDraft(t.symbol, t.name, t.market, t.side, t.quantity, t.price, t.accountId, date = state.today), null)) })
        is InvestSheet.CashEdit -> CashSheet(vm, state, sheet.kind, sheet.date, sheet.editing, onDismiss = close)
        is InvestSheet.AccountEdit -> AccountSheet(vm, sheet.editing, onDismiss = close)
        InvestSheet.Quotes -> QuoteSheet(vm, state, onDismiss = close)
        is InvestSheet.AlarmEdit -> AlarmSheet(vm, state, sheet.kind, sheet.editing, onDismiss = close)
        is InvestSheet.EventEdit -> EventSheet(vm, sheet.date ?: state.today, sheet.editing, onDismiss = close)
        InvestSheet.Goal -> GoalSheet(vm, state, onDismiss = close)
        InvestSheet.Principles -> PrinciplesSheet(vm, state, onDismiss = close)
        is InvestSheet.Journal -> JournalSheet(vm, state, sheet.date, onDismiss = close)
    } }
}

/** The investor's sheets share one frame: title and actions on top, the form, one button at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InvestSheetFrame(
    title: String,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)?,
    saveLabel: String = "저장",
    canSave: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                actions()
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "닫기") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                Spacer(Modifier.height(4.dp))
            }
            if (onSave != null) {
                Button(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) {
                    Text(saveLabel)
                }
            }
        }
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** [INV-02] The account a record goes into, as one-tap chips; with none yet the first is made on saving. */
@Composable
internal fun AccountPicker(accounts: List<InvestAccount>, selected: Long, onPick: (Long) -> Unit) {
    if (accounts.isEmpty()) {
        Text("저장하면 ‘일반 계좌’를 만들어 넣어요. 계좌는 나중에 바꿀 수 있어요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (accounts.size == 1) return
    FieldLabel("계좌")
    ChoiceChips(accounts, { it.id == selected }, { it.name }, { onPick(it.id) })
}

@Composable
private fun DateRow(date: LocalDate, minuteText: String?, onDate: (LocalDate) -> Unit, onMinute: ((String) -> Unit)?) {
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picking = true }) {
            Icon(Icons.Outlined.CalendarMonth, null)
            Spacer(Modifier.width(6.dp))
            Text(if (date == LocalDate.now()) "오늘 · ${Fmt.dayTitle(date)}" else Fmt.dayTitle(date))
        }
        if (onMinute != null && minuteText != null) {
            OutlinedTextField(
                value = minuteText, onValueChange = { v -> onMinute(v.filter { it.isDigit() || it == ':' }.take(5)) },
                label = { Text("시각") }, placeholder = { Text("09:05") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (picking) InvestDatePicker(date, onDate) { picking = false }
}

/**
 * [INV-03][INV-04][INV-08][INV-13][INV-20] One buy or sell: the holding (tapped from the ones already
 * held), quantity and price, the fee and 거래세 worked out as it is typed, then why and how it felt.
 */
@Composable
internal fun TradeSheet(
    vm: InvestViewModel,
    state: InvestState,
    draft: TradeDraft?,
    editing: Trade?,
    onDismiss: () -> Unit,
    onCopy: (Trade) -> Unit,
) {
    val book = state.full
    val accounts = book.activeAccounts
    val start = editing
    val d = draft ?: TradeDraft()
    var side by rememberSaveable { mutableStateOf(start?.side ?: d.side) }
    // [INV-03] A new trade starts in the draft's account, else the chosen one, else the last used (open ones only).
    var accountId by rememberSaveable {
        mutableStateOf(start?.accountId ?: listOf(d.accountId, state.account, book.settings.lastAccountId)
            .firstOrNull { id -> id != null && accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id ?: 0L)
    }
    val account = book.account(accountId)
    var name by rememberSaveable { mutableStateOf(start?.name ?: d.name) }
    var symbol by rememberSaveable { mutableStateOf(start?.symbol ?: d.symbol) }
    var market by rememberSaveable { mutableStateOf(start?.market ?: d.market.takeIf { draft != null && (d.symbol.isNotBlank() || d.name.isNotBlank()) } ?: account?.type?.market ?: Market.KR_STOCK) }
    var qty by rememberSaveable { mutableStateOf(InvestFmt.input(start?.quantity ?: d.quantity)) }
    var price by rememberSaveable { mutableStateOf(InvestFmt.input(start?.price ?: d.price)) }
    var date by rememberSaveable { mutableStateOf((start?.date ?: d.date ?: state.today).toString()) }
    var minute by rememberSaveable {
        mutableStateOf(minuteText(start?.minute ?: if (start == null && (d.date ?: state.today) == LocalDate.now()) LocalTime.now().let { it.hour * 60 + it.minute } else null))
    }
    var fx by rememberSaveable { mutableStateOf(InvestFmt.input(start?.fxRate?.takeIf { start.market.foreign } ?: book.settings.usdKrw)) }
    // Fees follow what is typed until the user writes their own.
    var feeText by rememberSaveable { mutableStateOf(start?.fee?.let { InvestFmt.input(it) } ?: "") }
    var taxText by rememberSaveable { mutableStateOf(start?.tax?.let { InvestFmt.input(it) } ?: "") }
    var costsTyped by rememberSaveable { mutableStateOf(start != null) }
    val tags = remember { mutableStateListOf<String>().apply { addAll(start?.tags ?: d.tags) } }
    val emotions = remember { mutableStateListOf<Emotion>().apply { addAll(start?.emotions.orEmpty()) } }
    var rules by rememberSaveable { mutableStateOf(start?.followedRules) }
    var memo by rememberSaveable { mutableStateOf(start?.memo ?: "") }
    var deleting by remember { mutableStateOf(false) }

    val q = qty.toDecimal() ?: 0.0
    val p = price.toDecimal() ?: 0.0
    val suggestion = InvestFees.suggest(market, side, q, p, account)
    val fee = if (costsTyped) feeText.toDecimal() ?: 0.0 else suggestion.fee
    val tax = if (costsTyped) taxText.toDecimal() ?: 0.0 else suggestion.tax
    val rate = if (market.foreign) fx.toDecimal() ?: book.settings.usdKrw else 1.0
    val key = holdingKey(symbol, name)
    val held = if (key.isEmpty()) 0.0 else vm.heldIn(accountId, key) + (if (start != null && start.key == key && start.accountId == accountId) {
        if (start.side == Side.BUY) -start.quantity else start.quantity
    } else 0.0)
    val holding = state.fullResult.holdings.firstOrNull { it.accountId == accountId && it.key == key }
    val canSave = key.isNotEmpty() && q > 0 && p > 0
    val hide = state.hide

    fun save() {
        val day = runCatching { LocalDate.parse(date) }.getOrDefault(state.today)
        vm.saveTrade(Trade(
            id = start?.id ?: 0, accountId = accountId, date = day, minute = parseMinute(minute), symbol = symbol.trim(), name = name.trim(),
            market = market, side = side, quantity = q, price = p, fxRate = rate, fee = fee, tax = tax,
            reason = start?.reason ?: "", emotions = emotions.toSet(), tags = tags.toList(), followedRules = rules, memo = memo.trim(),
            alarmId = start?.alarmId ?: d.alarmId, createdAt = start?.createdAt ?: 0,
        ), start)
        if (start == null) d.alertAt?.let { vm.alertSaved(it) }
        onDismiss()
    }

    InvestSheetFrame(
        title = if (start == null) "매매 기록" else "매매 고치기",
        onDismiss = onDismiss,
        onSave = ::save,
        saveLabel = if (start == null) "${side.label} 저장" else "고친 내용 저장",
        canSave = canSave,
        actions = {
            if (start != null) {
                IconButton(onClick = { onCopy(start) }) { Icon(Icons.Outlined.ContentCopy, "오늘 날짜로 복사") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "지우기", tint = MaterialTheme.colorScheme.error) }
            }
        },
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Side.entries.forEachIndexed { i, s ->
                SegmentedButton(selected = side == s, onClick = { side = s }, shape = SegmentedButtonDefaults.itemShape(i, Side.entries.size)) {
                    Text(s.label)
                }
            }
        }
        AccountPicker(accounts, accountId) { id ->
            accountId = id
            if (key.isEmpty()) book.account(id)?.type?.market?.let { market = it }
        }
        // One tap on a holding already there fills its name, code and market; a sell also its quantity.
        val known = remember(state.fullResult) { vm.knownHoldings().take(10) }
        if (known.isNotEmpty()) {
            FieldLabel(if (side == Side.SELL) "갖고 있는 종목" else "전에 산 종목")
            val picks = if (side == Side.SELL) known.filter { it.held } else known
            ChoiceChips(picks, { it.key == key }, { it.title }, { h ->
                name = h.name; symbol = h.symbol; market = h.market
                if (side == Side.SELL && qty.isEmpty()) {
                    val inAccount = state.fullResult.holdings.firstOrNull { it.accountId == accountId && it.key == h.key }?.quantity ?: h.quantity
                    if (inAccount > EPS) qty = InvestFmt.input(inAccount)
                }
                if (price.isEmpty()) book.quote(h.key)?.let { price = InvestFmt.input(it.price) }
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("종목명") }, placeholder = { Text("삼성전자") }, singleLine = true,
                modifier = Modifier.weight(1.4f))
            OutlinedTextField(symbol, { symbol = it.take(20) }, label = { Text("코드 (선택)") }, placeholder = { Text("005930") }, singleLine = true,
                modifier = Modifier.weight(1f))
        }
        ChoiceChips(Market.entries, { it == market }, { it.label }, { market = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecimalField("수량", qty, { qty = it }, Modifier.weight(1f), suffix = if (market == Market.CRYPTO) "개" else "주")
            DecimalField(if (market.foreign) "단가 (달러)" else "단가", price, { price = it }, Modifier.weight(1.3f), suffix = if (market.foreign) "$" else "원")
        }
        if (side == Side.SELL && held > EPS) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("이 계좌에 ${InvestFmt.quantity(held)}${if (market == Market.CRYPTO) "개" else "주"} 있어요", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { qty = InvestFmt.input(held) }) { Text("전부") }
                TextButton(onClick = { qty = InvestFmt.input(if (market.fractional) held / 2 else kotlin.math.floor(held / 2)) }) { Text("절반") }
            }
        }
        if (side == Side.SELL && key.isNotEmpty() && q > held + EPS) {
            Text("갖고 있는 수량보다 많아요. 넘는 만큼은 손익 없이 계산해요. 산 기록을 먼저 적어 주세요.",
                style = MaterialTheme.typography.bodySmall, color = LocalCalendarColors.current.warning)
        }
        if (market.foreign) DecimalField("환율 (1달러)", fx, { fx = it }, Modifier.fillMaxWidth(), suffix = "원",
            supporting = "실제 체결 환율을 적으면 원화 손익이 더 정확해요")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecimalField("수수료", if (costsTyped) feeText else InvestFmt.input(suggestion.fee), { feeText = it; taxText = if (costsTyped) taxText else InvestFmt.input(suggestion.tax); costsTyped = true },
                Modifier.weight(1f), suffix = if (market.foreign) "$" else "원")
            DecimalField("세금", if (costsTyped) taxText else InvestFmt.input(suggestion.tax), { taxText = it; feeText = if (costsTyped) feeText else InvestFmt.input(suggestion.fee); costsTyped = true },
                Modifier.weight(1f), suffix = if (market.foreign) "$" else "원")
        }
        Text(
            if (costsTyped) "직접 적은 수수료·세금으로 저장해요" else
                "수수료 ${"%.3f".format(account?.feeRate ?: market.defaultFeeRate).trimEnd('0').trimEnd('.')}%" +
                    (if (side == Side.SELL && market.sellTaxRate > 0) " · 거래세 ${market.sellTaxRate}%" else "") + "로 자동 계산했어요. 고치면 그 값으로 저장해요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // What this trade comes to, and for a sell what it made against 평균단가.
        if (q > 0 && p > 0) {
            val grossWon = q * p * rate
            val costsWon = (fee + tax) * rate
            val total = if (side == Side.BUY) grossWon + costsWon else grossWon - costsWon
            val lines = buildList {
                add((if (side == Side.BUY) "내는 돈 " else "받는 돈 ") + InvestFmt.won(total.roundToLong(), hide))
                if (side == Side.SELL && holding != null && holding.quantity > EPS) {
                    val sold = minOf(q, holding.quantity)
                    val cost = holding.costWon * sold / holding.quantity
                    val pnl = (total * sold / q - cost).roundToLong()
                    add("예상 실현손익 ${InvestFmt.signed(pnl, hide)} (${InvestFmt.percent(if (cost > 0) pnl / cost else null)})")
                }
                if (side == Side.BUY) {
                    val cash = state.fullResult.accounts.firstOrNull { it.accountId == accountId }?.cash ?: 0L
                    val after = cash - total.roundToLong() + (if (start != null && start.side == Side.BUY) (-start.cashWon).roundToLong() else 0L)
                    if (after < 0) add("현금이 ${InvestFmt.won(-after, hide)} 모자라요. 입금도 적어 두면 원금과 수익률이 맞아요")
                    else add("남는 현금 ${InvestFmt.won(after, hide)}")
                }
            }
            Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        DateRow(runCatching { LocalDate.parse(date) }.getOrDefault(state.today), minute, { date = it.toString() }, { minute = it })
        HorizontalDivider()
        FieldLabel("이유")
        ChoiceChips(REASON_TAGS, { it in tags }, { it }, { t -> if (t in tags) tags.remove(t) else tags.add(t) })
        FieldLabel("그때 마음")
        ChoiceChips(Emotion.entries, { it in emotions }, { it.label }, { e -> if (e in emotions) emotions.remove(e) else emotions.add(e) })
        FieldLabel("내 원칙")
        ChoiceChips(listOf(true, false), { rules == it }, { if (it) "지켰어요" else "어겼어요" }, { v -> rules = if (rules == v) null else v })
        OutlinedTextField(memo, { memo = it.take(500) }, label = { Text("메모") }, placeholder = { Text("왜 샀는지, 언제 팔지") },
            modifier = Modifier.fillMaxWidth(), minLines = 2)
    }
    if (deleting && start != null) ConfirmDialog(
        title = "이 매매를 지울까요?", message = "${start.title} ${start.side.label} ${InvestFmt.quantity(start.quantity)}주 기록이 없어지고 손익도 다시 계산돼요. 바로 되돌릴 수 있어요.",
        confirmLabel = "지우기", destructive = true, onDismiss = { deleting = false },
        onConfirm = { deleting = false; vm.deleteTrade(start); onDismiss() },
    )
}

/**
 * [INV-02][INV-05][INV-09] 입금·출금, 배당·이자, 기타 비용. While a deposit or withdrawal is typed the
 * sheet shows what it does to 원금, 수익률, cash and the goal, before it is saved.
 */
@Composable
internal fun CashSheet(vm: InvestViewModel, state: InvestState, kind0: CashKind, date0: LocalDate?, editing: CashFlow?, onDismiss: () -> Unit) {
    val book = state.full
    val accounts = book.activeAccounts
    var kind by rememberSaveable { mutableStateOf(editing?.kind ?: kind0) }
    var accountId by rememberSaveable {
        mutableStateOf(editing?.accountId ?: (state.account ?: book.settings.lastAccountId)?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id ?: 0L)
    }
    var amount by rememberSaveable { mutableStateOf(amountDigits(editing?.amount ?: 0)) }
    var taxText by rememberSaveable { mutableStateOf(amountDigits(editing?.tax ?: 0)) }
    var taxTyped by rememberSaveable { mutableStateOf(editing != null) }
    var symbol by rememberSaveable { mutableStateOf(editing?.symbol ?: "") }
    var memo by rememberSaveable { mutableStateOf(editing?.memo ?: "") }
    var date by rememberSaveable { mutableStateOf((editing?.date ?: date0 ?: state.today).toString()) }
    var deleting by remember { mutableStateOf(false) }
    val a = amount.toAmount()
    val hide = state.hide
    val holdingMarket = state.fullResult.byKey.firstOrNull { it.key == holdingKey(symbol, "") }?.market ?: Market.KR_STOCK
    val suggestedTax = if (kind == CashKind.DIVIDEND) InvestTaxes.dividendTax(a, holdingMarket)
        else if (kind == CashKind.INTEREST) (a * InvestTaxes.DIVIDEND_RATE).toLong() else 0L
    val tax = if (!kind.isIncome) 0L else if (taxTyped) taxText.toAmount() else suggestedTax

    fun save() {
        val day = runCatching { LocalDate.parse(date) }.getOrDefault(state.today)
        vm.saveCash(CashFlow(id = editing?.id ?: 0, accountId = accountId, date = day, minute = editing?.minute, kind = kind, amount = a,
            tax = tax.coerceIn(0, a), symbol = if (kind == CashKind.DIVIDEND) symbol.trim() else "", memo = memo.trim(), createdAt = editing?.createdAt ?: 0), editing)
        onDismiss()
    }

    InvestSheetFrame(
        title = if (editing == null) "${kind.label} 적기" else "${kind.label} 고치기",
        onDismiss = onDismiss, onSave = ::save, canSave = a > 0,
        actions = {
            if (editing != null) IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "지우기", tint = MaterialTheme.colorScheme.error) }
        },
    ) {
        ChoiceChips(CashKind.entries, { it == kind }, { it.label }, { kind = it })
        AccountPicker(accounts, accountId) { accountId = it }
        AmountField(label = if (kind.isIncome) "받은 금액 (세전)" else "금액", digits = amount, onChange = { amount = it }, modifier = Modifier.fillMaxWidth())
        // Quick amounts for the usual round deposits.
        if (kind == CashKind.DEPOSIT || kind == CashKind.WITHDRAW) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(100_000L, 500_000L, 1_000_000L, 5_000_000L).forEach { step ->
                    OutlinedButton(onClick = { amount = (a + step).toString() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text("+" + Fmt.wonShort(step))
                    }
                }
            }
        }
        if (kind == CashKind.DIVIDEND) {
            val held = state.fullResult.byKey.filter { it.held || it.dividendsWon > 0 }.take(10)
            if (held.isNotEmpty()) ChoiceChips(held, { it.symbol.equals(symbol, true) || it.key == holdingKey(symbol, "") }, { it.title }, { symbol = it.symbol.ifBlank { it.name } })
            OutlinedTextField(symbol, { symbol = it.take(20) }, label = { Text("종목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (kind.isIncome) {
            AmountField(label = "떼인 세금", digits = if (taxTyped) taxText else amountDigits(suggestedTax), onChange = { taxText = it; taxTyped = true }, modifier = Modifier.fillMaxWidth())
            Text(
                if (taxTyped) "실제로 떼인 세금으로 저장해요"
                else if (kind == CashKind.DIVIDEND && holdingMarket.foreign) "미국 배당은 현지에서 15%를 떼요. 실수령 ${InvestFmt.won(a - tax, hide)}"
                else "배당·이자소득세 15.4%로 계산했어요. 실수령 ${InvestFmt.won(a - tax, hide)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // [INV-05] Principal typed: 원금, 수익률, cash and the goal move at once.
        if (a > 0) {
            val typed = CashFlow(accountId = accountId, date = state.today, kind = kind, amount = a, tax = tax.coerceIn(0, a))
            CashPreview(state, typed.cashWon - (editing?.cashWon ?: 0), typed.principalWon - (editing?.principalWon ?: 0))
        }
        DateRow(runCatching { LocalDate.parse(date) }.getOrDefault(state.today), null, { date = it.toString() }, null)
        OutlinedTextField(memo, { memo = it.take(200) }, label = { Text("메모") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (deleting && editing != null) ConfirmDialog(
        title = "이 기록을 지울까요?", message = "${editing.kind.label} ${InvestFmt.won(editing.amount, hide)} 기록이 없어지고 원금과 수익률도 다시 계산돼요.",
        confirmLabel = "지우기", destructive = true, onDismiss = { deleting = false },
        onConfirm = { deleting = false; vm.deleteCash(editing); onDismiss() },
    )
}

/** [INV-05][INV-11] 원금 1,000만 → 1,100만 · 수익률 +5.00% → +4.55% · 현금 · 목표. */
@Composable
private fun CashPreview(state: InvestState, cashDelta: Long, principalDelta: Long) {
    val s = state.fullResult.summary
    val hide = state.hide
    val newPrincipal = s.principal + principalDelta
    val newAssets = s.totalAssets + cashDelta
    val newRate = if (newPrincipal > 0) (newAssets - newPrincipal).toDouble() / newPrincipal else null
    val lines = buildList {
        if (principalDelta != 0L) add("원금 ${InvestFmt.short(s.principal, hide)} → ${InvestFmt.short(newPrincipal, hide)}")
        add("수익률 ${InvestFmt.percent(s.simpleReturn)} → ${InvestFmt.percent(newRate)}")
        add("현금 ${InvestFmt.short(s.cash, hide)} → ${InvestFmt.short(s.cash + (newAssets - s.totalAssets), hide)}")
        state.settings.goalAmount?.let { g -> add("목표 ${InvestFmt.short(g, hide)}까지 ${"%.0f".format(newAssets * 100.0 / g)}%") }
    }
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("저장하면 바로 바뀌어요", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            if (newPrincipal < 0) Text("뺀 돈이 넣은 돈보다 많아요. 처음 넣은 돈도 입금으로 적어 주세요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/** [INV-02][INV-03] An account: its name, kind (which decides taxes and the market), broker and its own fee. */
@Composable
internal fun AccountSheet(vm: InvestViewModel, editing: InvestAccount?, onDismiss: () -> Unit) {
    var type by rememberSaveable { mutableStateOf(editing?.type ?: AccountType.GENERAL) }
    var name by rememberSaveable { mutableStateOf(editing?.name ?: "") }
    var broker by rememberSaveable { mutableStateOf(editing?.broker ?: "") }
    var fee by rememberSaveable { mutableStateOf(editing?.feeRate?.let { InvestFmt.input(it) } ?: "") }
    var deleting by remember { mutableStateOf(false) }
    InvestSheetFrame(
        title = if (editing == null) "계좌 추가" else "계좌 고치기",
        onDismiss = onDismiss,
        onSave = {
            vm.saveAccount((editing ?: InvestAccount(name = "")).copy(name = name.ifBlank { type.label }, type = type, broker = broker.trim(), feeRate = fee.toDecimal()))
            onDismiss()
        },
        actions = {
            if (editing != null) IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "계좌 정리", tint = MaterialTheme.colorScheme.error) }
        },
    ) {
        ChoiceChips(AccountType.entries, { it == type }, { it.label }, { type = it })
        OutlinedTextField(name, { name = it.take(20) }, label = { Text("이름") }, placeholder = { Text(type.label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(broker, { broker = it.take(20) }, label = { Text("증권사·거래소 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DecimalField("수수료율 (선택)", fee, { fee = it }, Modifier.fillMaxWidth(), suffix = "%",
            supporting = "비워 두면 ${type.market.label} 보통 수수료 ${type.market.defaultFeeRate}%로 계산해요", imeAction = ImeAction.Done)
        Text(
            when {
                type == AccountType.ISA -> "ISA는 순이익 200만 원(서민형 400만 원)까지 세금이 없고, 넘는 만큼 9.9%만 내요."
                type.taxDeferred -> "연금저축·IRP는 이익에 바로 세금이 붙지 않고, 연금으로 받을 때 내요."
                type == AccountType.CRYPTO -> "코인 이익은 2027년부터 250만 원을 넘는 만큼 22% 세금이 붙을 예정이에요."
                type == AccountType.OVERSEAS -> "해외 주식은 1년 이익에서 250만 원을 뺀 만큼 22%를 다음 해 5월에 신고해요."
                else -> "국내 주식은 팔 때 거래세 0.20%가 붙고, 대주주가 아니면 양도세는 없어요. ETF는 거래세가 없어요."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (deleting && editing != null) ConfirmDialog(
        title = "‘${editing.name}’ 계좌를 정리할까요?", message = "기록이 있으면 목록에서만 숨기고 합계에는 그대로 남겨요. 기록이 없으면 지워요.",
        confirmLabel = "정리", destructive = true, onDismiss = { deleting = false },
        onConfirm = { deleting = false; vm.deleteAccount(editing); onDismiss() },
    )
}

/**
 * [INV-04][INV-19] 현재가 for every holding at once, typed from the broker's app, and the dollar's rate.
 * Nothing is fetched: the prices are the user's own, dated today.
 */
@Composable
internal fun QuoteSheet(vm: InvestViewModel, state: InvestState, onDismiss: () -> Unit) {
    val held = state.fullResult.held
    val typed = remember { mutableStateMapOf<String, String>().apply { held.forEach { put(it.key, InvestFmt.input(it.quote?.price)) } } }
    val foreign = held.any { it.market.foreign }
    var usd by rememberSaveable { mutableStateOf(InvestFmt.input(state.settings.usdKrw)) }
    InvestSheetFrame(
        title = "현재가 적기",
        onDismiss = onDismiss,
        onSave = {
            val quotes = held.mapNotNull { h -> typed[h.key]?.toDecimal()?.takeIf { it > 0 }?.let { Quote(h.key, it, state.today, h.title, h.market) } }
            // [INV-04] Blank or 0 takes the price away, as a price of nothing is no price.
            val removed = held.filter { h -> h.quote != null && (typed[h.key]?.toDecimal() ?: 0.0) <= 0 }.map { it.key }
            vm.saveQuotes(quotes, removed, if (foreign) usd.toDecimal() else null)
            onDismiss()
        },
        canSave = held.isNotEmpty(),
    ) {
        Text("증권사 앱에서 본 가격을 적어 주세요. 평가손익·수익률·총자산이 바로 다시 계산돼요. 가격을 받아 오지는 않아요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (held.isEmpty()) Text("갖고 있는 종목이 아직 없어요. 매수를 먼저 적어 주세요.", style = MaterialTheme.typography.bodyMedium)
        if (foreign) DecimalField("환율 (1달러)", usd, { usd = it }, Modifier.fillMaxWidth(), suffix = "원", supporting = "모든 해외 종목의 원화 평가에 써요")
        held.forEach { h ->
            val avg = h.averagePrice
            DecimalField(
                label = h.title, text = typed[h.key].orEmpty(), onChange = { typed[h.key] = it }, modifier = Modifier.fillMaxWidth(),
                suffix = if (h.market.foreign) "$" else "원",
                supporting = "평균단가 ${InvestFmt.price(avg, h.market)} · ${InvestFmt.quantity(h.quantity)}${if (h.market == Market.CRYPTO) "개" else "주"}" +
                    (h.quote?.let { " · ${it.date.monthValue}/${it.date.dayOfMonth} 적음" } ?: ""),
            )
        }
    }
}
