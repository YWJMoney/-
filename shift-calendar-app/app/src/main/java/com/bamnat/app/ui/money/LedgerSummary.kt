package com.bamnat.app.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.money.AccountType
import com.bamnat.core.money.BudgetLevel
import com.bamnat.core.money.BudgetLine
import com.bamnat.core.money.BudgetStatus
import com.bamnat.core.money.CardCycle
import com.bamnat.core.money.CategoryEdit
import com.bamnat.core.money.MoneyAccount
import com.bamnat.core.money.MoneyAccounts
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyBudget
import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MonthMoney
import java.time.LocalDate
import java.time.YearMonth

/**
 * [LEDG-01][LEDG-06] The month at a glance: what is left, money in and out, and with a budget,
 * what may still be spent today (the month's budget less what is spent and the repeating costs
 * still to leave, over the days left) and where the month is heading.
 */
@Composable
fun HeroCard(m: MonthMoney, status: BudgetStatus, today: LocalDate, onBudget: () -> Unit) {
    val cal = LocalCalendarColors.current
    val current = YearMonth.from(today) == m.month
    SectionCard(title = "${m.month.monthValue}월 남은 돈") {
        FitText(Fmt.won(m.balance), MaterialTheme.typography.displaySmall, if (m.balance < 0) cal.expense else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("들어온 돈", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FitText("+" + Fmt.won(m.income), MaterialTheme.typography.titleMedium, cal.income, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("나간 돈", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FitText("-" + Fmt.won(m.spent), MaterialTheme.typography.titleMedium, cal.expense, fontWeight = FontWeight.Bold)
            }
        }
        // [RECV-04] Work written down but not paid yet stays out of 들어온 돈 and shows here instead.
        if (m.jobOwed > 0) {
            Text("아직 안 받은 일감 ${Fmt.won(m.jobOwed)}은 받을 돈에 있어요. 돈 받음을 켜면 들어온 돈에 더해져요.",
                style = MaterialTheme.typography.bodySmall, color = cal.warning, modifier = Modifier.padding(top = 8.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        val total = status.total
        if (total == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("예산을 정하면 하루에 쓸 수 있는 돈을 알려 줘요.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onBudget) { Text("예산 정하기") }
            }
            return@SectionCard
        }
        val left = status.todayLeft
        if (current && left != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("오늘 쓸 수 있는 돈", style = MaterialTheme.typography.labelLarge)
                    Text("하루 ${Fmt.won(status.perDay ?: 0)} · 남은 ${status.daysLeft}일", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Fmt.won(left.coerceAtLeast(0)), style = MaterialTheme.typography.headlineSmall.merge(NumberStyle), fontWeight = FontWeight.Bold,
                    color = if (left <= 0) cal.warning else MaterialTheme.colorScheme.primary)
            }
            if (left < 0) {
                Text("오늘 몫보다 ${Fmt.won(-left)} 더 썼어요. 내일부터 조금씩 줄여요.", style = MaterialTheme.typography.bodySmall,
                    color = cal.warning, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
        BudgetLineView(total, status.elapsed.takeIf { current }, onClick = onBudget, whole = true)
        if (current && status.fixedLeft > 0) {
            Text("이번 달 남은 반복 지출 ${Fmt.won(status.fixedLeft)}은 미리 빼고 계산했어요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** One budget: spent against it, a mark for how far the month is, and a plain word on where it stands. */
@Composable
private fun BudgetLineView(b: BudgetLine, elapsed: Float?, onClick: () -> Unit, whole: Boolean = false) {
    val cal = LocalCalendarColors.current
    val color = when (b.level) { BudgetLevel.OVER -> cal.warning; BudgetLevel.NEAR -> cal.changed; BudgetLevel.OK -> MaterialTheme.colorScheme.primary }
    val pct = (b.fraction * 100).toInt()
    val status = when {
        b.level == BudgetLevel.OVER -> "${Fmt.won(-b.left)} 넘었어요"
        b.headedOver -> "이대로면 약 ${wonBrief(b.forecast - b.budget)}원 넘을 것 같아요"
        b.level == BudgetLevel.NEAR -> "${pct}% 썼어요 · ${Fmt.won(b.left)} 남았어요"
        else -> "${Fmt.won(b.left)} 남았어요"
    }
    val name = if (whole) "이번 달 예산" else b.category
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$name ${Fmt.won(b.budget)} 중 ${Fmt.won(b.spent)} 씀, $status" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!whole) {
                CategoryAvatar(b.category, size = 28.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Fmt.won(b.spent)} / ${Fmt.won(b.budget)}", style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        LedgerTrack(b.fraction, color, marker = elapsed, height = if (whole) 10.dp else 8.dp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (b.level == BudgetLevel.OVER || b.headedOver) {
                Icon(Icons.Filled.WarningAmber, null, tint = if (b.level == BudgetLevel.OVER) cal.warning else cal.changed, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(status, style = MaterialTheme.typography.labelMedium,
                color = when { b.level == BudgetLevel.OVER -> cal.warning; b.headedOver || b.level == BudgetLevel.NEAR -> cal.changed
                    else -> MaterialTheme.colorScheme.onSurfaceVariant })
        }
    }
}

/** [LEDG-06] Each category with a budget, the ones over or closest to it first. */
@Composable
fun BudgetCard(status: BudgetStatus, today: LocalDate, onEdit: () -> Unit) {
    if (status.categories.isEmpty()) return
    val current = YearMonth.from(today) == status.month
    SectionCard(title = "분류별 예산", trailing = { TextButton(onClick = onEdit) { Text("고치기") } }) {
        if (status.over.isNotEmpty()) {
            Text("${status.over.joinToString(", ") { it.category }} 예산을 넘었어요", style = MaterialTheme.typography.bodySmall,
                color = LocalCalendarColors.current.warning, modifier = Modifier.padding(bottom = 4.dp))
        }
        status.categories.forEach { b -> BudgetLineView(b, status.elapsed.takeIf { current }, onClick = onEdit) }
    }
}

/**
 * [LEDG-06] The month's budget and each category's. Every category shows what went there last
 * month, and one tap fills the empty ones with the last three months' average.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetEditor(book: MoneyBook, month: YearMonth, onSave: (Map<String, Long>) -> Unit, onDismiss: () -> Unit) {
    val categories = remember(book) { book.categoriesOf(MoneyKind.EXPENSE) }
    val values = remember {
        mutableStateMapOf<String, String>().apply { book.budgets.forEach { put(it.category, amountDigits(it.amount)) } }
    }
    val lastMonth = remember(book, month) { book.month(month.minusMonths(1)).expenseByCategory.associate { it.category to it.amount } }
    val average = remember(book, month) {
        val months = (1..3).map { book.month(month.minusMonths(it.toLong())) }
        val counted = months.count { it.spent > 0 }.coerceAtLeast(1)
        months.flatMap { it.expenseByCategory }.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amount } / counted }
    }
    val sum = categories.sumOf { values[it]?.toAmount() ?: 0 }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            SheetTitle("예산 정하기", onDismiss)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("매달 같은 예산으로 계산해요. 비워 두면 예산이 없어요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountField("한 달 전체 예산", values[MoneyBudget.TOTAL].orEmpty(), { values[MoneyBudget.TOTAL] = it })
                if (values[MoneyBudget.TOTAL].isNullOrEmpty() && sum > 0) {
                    Text("전체를 비우면 분류별 예산의 합 ${Fmt.won(sum)}이 한 달 예산이 돼요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("분류별 예산", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                    if (average.isNotEmpty()) {
                        TextButton(onClick = {
                            // Rounded up to 1만원: a budget is a round number people remember.
                            for ((c, a) in average) if (c in categories && a > 0 && values[c].isNullOrEmpty()) {
                                values[c] = amountDigits((a + 9_999) / 10_000 * 10_000)
                            }
                        }) { Text("지난 3달 평균으로 채우기") }
                    }
                }
                categories.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryAvatar(c, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(lastMonth[c]?.let { "지난달 ${Fmt.won(it)}" } ?: "지난달 없음", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        AmountField("예산", values[c].orEmpty(), { values[c] = it },
                            Modifier.widthIn(max = 168.dp).semantics { contentDescription = "$c 예산" })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider()
            Button(
                onClick = { onSave((listOf(MoneyBudget.TOTAL) + categories + book.budgets.map { it.category }).distinct().associateWith { values[it]?.toAmount() ?: 0 }) },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp).heightIn(min = 48.dp),
            ) { Text("저장") }
        }
    }
}

@Composable
private fun SheetTitle(title: String, onDismiss: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "닫기") }
    }
}

/**
 * [LEDG-08] Cash, accounts and cards: what is in each account, and what each credit card will take
 * on its next 결제일 from what was spent on it (installments spread over their months).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsCard(book: MoneyBook, today: LocalDate, onAdd: (AccountType?) -> Unit, onEdit: (MoneyAccount) -> Unit) {
    val accounts = book.activeAccounts
    val cal = LocalCalendarColors.current
    val balances = remember(book) { MoneyAccounts.balances(book.accounts, book.entries) }
    SectionCard(
        title = "결제수단·계좌",
        trailing = { TextButton(onClick = { onAdd(null) }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("추가") } },
    ) {
        if (accounts.isEmpty()) {
            Text("현금·통장·카드를 적어 두면 어디서 얼마 나갔는지, 이번 달 카드값이 얼마 나올지 알려 줘요. 카드 결제 알림도 끝 4자리로 알아서 이어 줘요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf(AccountType.CASH, AccountType.BANK, AccountType.CREDIT, AccountType.CHECK).forEach { t ->
                    AssistChip(onClick = { onAdd(t) }, label = { Text(t.label) }, leadingIcon = { Icon(accountIcon(t), null, Modifier.size(18.dp)) })
                }
            }
            return@SectionCard
        }
        accounts.forEach { a ->
            val (value, caption) = when {
                a.isCredit && a.payDay != null -> {
                    val bill = CardCycle.next(a, today, book.entries, book.accounts)
                    Fmt.won(bill.amount) to "${Fmt.shortDate(bill.payDate)} 결제 · ${Fmt.shortDate(bill.from)}~${Fmt.shortDate(bill.to)} 쓴 돈"
                }
                a.isCredit -> null to "결제일을 정하면 카드값을 계산해요"
                a.type == AccountType.CHECK -> null to (book.account(a.payFromId)?.let { "${it.name}에서 바로 나가요" } ?: "연결한 통장을 고르면 잔액에 반영해요")
                else -> Fmt.won(balances[a.id] ?: a.opening) to (if (a.last4.isNotBlank()) "끝 ${a.last4}" else a.type.label)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).clickable { onEdit(a) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAvatar(accountIcon(a.type), accountColor(a), size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (value != null) {
                    val negative = !a.isCredit && (balances[a.id] ?: 0) < 0
                    Text(value, style = MaterialTheme.typography.titleSmall.merge(NumberStyle), fontWeight = FontWeight.Bold,
                        color = if (negative) cal.expense else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        val kept = accounts.filter { it.keepsBalance }
        if (kept.size > 1) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row {
                Text("통장·현금 합계", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(Fmt.won(kept.sumOf { balances[it.id] ?: it.opening }), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * [LEDG-08] Adds or edits an account. A card names its last four digits (payment texts find it by
 * them) and the account it is paid from; a credit card its 결제일 and, if not the usual whole
 * month before, its 마감일. An account that holds money starts from what is in it now.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AccountEditor(
    initial: MoneyAccount?,
    type: AccountType?,
    book: MoneyBook,
    onSave: (MoneyAccount) -> Unit,
    onRemove: (MoneyAccount) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(initial?.type ?: type ?: AccountType.CREDIT) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var last4 by rememberSaveable { mutableStateOf(initial?.last4.orEmpty()) }
    var payDay by rememberSaveable { mutableIntStateOf(initial?.payDay ?: 14) }
    var ownClose by rememberSaveable { mutableStateOf(initial?.closeDay != null) }
    var closeDay by rememberSaveable { mutableIntStateOf(initial?.closeDay ?: 31) }
    var payFrom by rememberSaveable { mutableStateOf(initial?.payFromId) }
    var opening by rememberSaveable { mutableStateOf(amountDigits(initial?.opening ?: 0)) }
    var remind by rememberSaveable { mutableStateOf(initial?.remind ?: true) }
    val banks = book.activeAccounts.filter { it.keepsBalance && it.id != initial?.id }
    val card = kind == AccountType.CREDIT || kind == AccountType.CHECK
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            SheetTitle(if (initial == null) "결제수단 추가" else "결제수단 고치기", onDismiss) {
                if (initial != null) IconButton(onClick = { onRemove(initial) }) { Icon(Icons.Outlined.Delete, "지우기", tint = MaterialTheme.colorScheme.error) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AccountType.entries.forEach { t ->
                        FilterChip(selected = kind == t, onClick = { kind = t }, label = { Text(t.label) },
                            leadingIcon = { Icon(accountIcon(t), null, Modifier.size(18.dp)) })
                    }
                }
                Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = name, onValueChange = { name = it.take(MoneyAccount.MAX_NAME) }, singleLine = true,
                    label = { Text(when (kind) { AccountType.CREDIT, AccountType.CHECK -> "카드 이름 (예: 신한 Deep)"; AccountType.BANK -> "통장 이름 (예: 월급 통장)"
                        AccountType.CASH -> "이름 (예: 지갑)"; AccountType.PAY -> "이름 (예: 카카오페이)"; AccountType.OTHER -> "이름" }) },
                    modifier = Modifier.fillMaxWidth())
                if (card) {
                    OutlinedTextField(value = last4, onValueChange = { v -> last4 = v.filter(Char::isDigit).take(4) }, singleLine = true,
                        label = { Text("카드 끝 4자리 (선택)") }, supportingText = { Text("이 카드의 결제 알림은 이 달력에만 알아서 적어요") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                }
                if (kind == AccountType.CREDIT) {
                    DayStepper("결제일", payDay) { payDay = it }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { ownClose = !ownClose }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("쓴 기간 직접 정하기", style = MaterialTheme.typography.bodyLarge)
                            Text(if (!ownClose) "보통은 지난달 1일부터 말일까지 쓴 돈이 나가요" else "카드사 앱에서 '이용 기간'을 확인해 마지막 날을 넣어요",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = ownClose, onCheckedChange = { ownClose = it })
                    }
                    if (ownClose) DayStepper("쓴 기간 마지막 날", closeDay) { closeDay = it }
                    val preview = MoneyAccount(name = name, type = kind, payDay = payDay, closeDay = if (ownClose) closeDay else null)
                    val (from, to) = CardCycle.period(preview, YearMonth.now().plusMonths(1))
                    Text("${YearMonth.now().plusMonths(1).monthValue}월 ${if (payDay >= 31) "말일" else "${payDay}일"} 결제: ${Fmt.shortDate(from)}~${Fmt.shortDate(to)}에 쓴 돈",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (card && banks.isNotEmpty()) {
                    Text("돈이 나가는 통장", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = payFrom == null, onClick = { payFrom = null }, label = { Text("안 고름") })
                        banks.forEach { b -> FilterChip(selected = payFrom == b.id, onClick = { payFrom = b.id }, label = { Text(b.label) }) }
                    }
                }
                if (!card) {
                    AmountField(if (initial == null) "지금 들어 있는 돈" else "처음 들어 있던 돈", opening, { opening = it })
                    Text("이후 이 계좌로 적은 수입·지출·이체로 잔액을 계산해요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (kind == AccountType.CREDIT) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { remind = !remind }, verticalAlignment = Alignment.CenterVertically) {
                        Text("카드값 3일 전·당일 아침 알림", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = remind, onCheckedChange = { remind = it })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider()
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave((initial ?: MoneyAccount(name = name, type = kind)).copy(
                        name = name.trim(), type = kind, last4 = if (card) last4 else "",
                        payDay = payDay.takeIf { kind == AccountType.CREDIT }, closeDay = closeDay.takeIf { kind == AccountType.CREDIT && ownClose },
                        payFromId = payFrom.takeIf { card }, opening = if (card) 0 else opening.toAmount(), remind = remind,
                    ))
                },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp).heightIn(min = 48.dp),
            ) { Text("저장") }
        }
    }
}

@Composable
private fun DayStepper(label: String, day: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(if (day <= 1) 31 else day - 1) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(if (day >= 31) "말일" else "${day}일", style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 48.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange(if (day >= 31) 1 else day + 1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/** One category in the editor: its name now, the name it had, and whether it is hidden. */
private data class EditRow(val name: String, val was: String?, val hidden: Boolean)

/**
 * [LEDG-14] The user's categories for each kind: moved up or down, hidden (kept for old lines,
 * not offered), renamed (with every line, budget and repeating line under it) or added. 기타
 * always stays last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryEditor(
    book: MoneyBook,
    onSave: (MoneyKind, List<CategoryEdit>, List<Pair<String, String>>) -> Unit,
    onDismiss: () -> Unit,
    startKind: MoneyKind = MoneyKind.EXPENSE,
) {
    var kind by rememberSaveable { mutableStateOf(startKind) }
    val rows = remember(kind) {
        val mine = book.categories.filter { it.kind == kind }.sortedBy { it.position }
        val shown = book.categoriesOf(kind).filter { it != MoneyCategories.OTHER }
        val hidden = mine.filter { it.hidden }.map { it.name }
        mutableStateListOf<EditRow>().apply {
            shown.forEach { add(EditRow(it, it, false)) }
            hidden.forEach { add(EditRow(it, it, true)) }
        }
    }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            SheetTitle("분류 편집", onDismiss)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    MoneyKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, MoneyKind.entries.size)) { Text(k.label) }
                    }
                }
                rows.forEachIndexed { i, r ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) {
                            customActions = listOfNotNull(
                                CustomAccessibilityAction("위로") { if (i > 0) { rows.add(i - 1, rows.removeAt(i)) }; true }.takeIf { i > 0 },
                                CustomAccessibilityAction("아래로") { if (i < rows.lastIndex) { rows.add(i + 1, rows.removeAt(i)) }; true }.takeIf { i < rows.lastIndex },
                            )
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryAvatar(r.name, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(r.name + if (r.hidden) " (숨김)" else "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            color = if (r.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { renaming = i }) { Icon(Icons.Outlined.Edit, "${r.name} 이름 바꾸기") }
                        IconButton(onClick = { rows[i] = r.copy(hidden = !r.hidden) }) {
                            Icon(if (r.hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (r.hidden) "${r.name} 다시 보이기" else "${r.name} 숨기기")
                        }
                        IconButton(enabled = i > 0, onClick = { rows.add(i - 1, rows.removeAt(i)) }) { Icon(Icons.Filled.KeyboardArrowUp, "${r.name} 위로") }
                        IconButton(enabled = i < rows.lastIndex, onClick = { rows.add(i + 1, rows.removeAt(i)) }) { Icon(Icons.Filled.KeyboardArrowDown, "${r.name} 아래로") }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryAvatar(MoneyCategories.OTHER, size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("${MoneyCategories.OTHER} (늘 마지막)", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(value = adding, onValueChange = { adding = it.take(MoneyCategories.MAX_NAME) }, singleLine = true,
                        label = { Text("새 분류") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    val clean = MoneyCategories.clean(adding)
                    TextButton(enabled = clean.isNotEmpty() && rows.none { it.name == clean } && clean != MoneyCategories.OTHER, onClick = {
                        rows.add(EditRow(clean, null, false))
                        adding = ""
                    }) { Text("더하기") }
                }
                Text("이름을 바꾸면 그 분류의 기록·예산·반복도 함께 바뀌어요. 숨긴 분류의 기록은 그대로 있어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            HorizontalDivider()
            Button(
                onClick = {
                    val edits = rows.mapIndexed { i, r -> CategoryEdit(kind, r.name, i, r.hidden) }
                    val renames = rows.mapNotNull { r -> r.was?.takeIf { it != r.name }?.let { it to r.name } }
                    onSave(kind, edits, renames)
                },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp).heightIn(min = 48.dp),
            ) { Text("${kind.label} 분류 저장") }
        }
    }
    renaming?.let { i ->
        val r = rows.getOrNull(i) ?: return@let
        var text by remember(i) { mutableStateOf(r.name) }
        val clean = MoneyCategories.clean(text)
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("이름 바꾸기") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(MoneyCategories.MAX_NAME) }, singleLine = true,
                    label = { Text("분류 이름") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = clean.isNotEmpty() && clean != MoneyCategories.OTHER && rows.none { it !== r && it.name == clean }, onClick = {
                    rows[i] = r.copy(name = clean)
                    renaming = null
                }) { Text("바꾸기") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("취소") } },
        )
    }
}
