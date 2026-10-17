package com.bamnat.app.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobReminders
import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.PaymentParser
import java.time.LocalDate
import java.time.LocalTime

/** Most a single line may hold: 100억원, far above any household amount, and safe from overflow. */
private const val MAX_DIGITS = 11

/** Longest job one booking covers; longer work is several bookings (one per stage). */
private const val MAX_JOB_DAYS = 60

/** Whole won typed as digits and shown with thousands separators [LEDG-01]. */
@Composable
fun AmountField(
    label: String,
    digits: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = digits,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).trimStart('0').take(MAX_DIGITS)) },
        label = { Text(label) },
        suffix = { Text("원") },
        singleLine = true,
        visualTransformation = ThousandsTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        modifier = modifier.fillMaxWidth(),
    )
}

fun amountDigits(amount: Long): String = if (amount <= 0) "" else amount.toString()
fun String.toAmount(): Long = toLongOrNull() ?: 0L

internal object ThousandsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val d = text.text
        val n = d.length
        val out = StringBuilder()
        for (k in 0 until n) {
            if (k > 0 && (n - k) % 3 == 0) out.append(',')
            out.append(d[k])
        }
        val formatted = out.toString()
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                offset + (1 until offset.coerceIn(0, n)).count { k -> (n - k) % 3 == 0 }

            override fun transformedToOriginal(offset: Int): Int =
                formatted.take(offset.coerceIn(0, formatted.length)).count(Char::isDigit)
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { c -> FilterChip(selected = selected == c, onClick = { onSelect(c) }, label = { Text(c) }) }
    }
}

/**
 * Writes or edits one ledger line [LEDG-01]. An [initial] with id 0 is a new line filled in ahead
 * (from a notification the app could not finish [LEDG-02]). [frequent] amounts are one tap away and
 * a known place picks its category by itself [LEDG-04].
 */
@OptIn(ExperimentalLayoutApi::class)
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
) {
    val isNew = initial == null || initial.id == 0L
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: defaultKind) }
    // A cancellation is stored negative (or, from a kept foreign text, still 0 and marked "결제 취소").
    val cancel = (initial?.amount ?: 0) < 0 || initial?.note?.startsWith("결제 취소") == true
    var digits by rememberSaveable { mutableStateOf(amountDigits(kotlin.math.abs(initial?.amount ?: 0))) }
    var category by rememberSaveable { mutableStateOf(initial?.category ?: MoneyCategories.of(defaultKind).first()) }
    var categoryTouched by rememberSaveable { mutableStateOf(initial != null) }
    var place by rememberSaveable { mutableStateOf(initial?.merchant?.ifBlank { initial.note }.orEmpty()) }
    var day by rememberSaveable { mutableStateOf((initial?.date ?: date).toString()) }
    val onDay = LocalDate.parse(day)
    val amount = digits.toAmount()
    val amountFocus = remember { FocusRequester() }
    // A new line starts in the amount with the keypad up: amount first, the rest is optional.
    if (isNew) LaunchedEffect(Unit) { runCatching { amountFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "돈 적기" else "돈 기록 고치기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Spending is often written down a day or two later, so the date moves by a tap.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { day = onDay.minusDays(1).toString() }) { Text("‹ 전날") }
                    Text(Fmt.dayTitle(onDay), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { day = onDay.plusDays(1).toString() }) { Text("다음날 ›") }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MoneyKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = kind == k,
                            onClick = {
                                kind = k
                                if (category !in MoneyCategories.of(k)) category = MoneyCategories.of(k).first()
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, MoneyKind.entries.size),
                        ) { Text(k.label) }
                    }
                }
                AmountField("금액", digits, { digits = it }, Modifier.focusRequester(amountFocus))
                val quick = remember(kind) { frequent(kind) }
                if (quick.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        quick.forEach { a ->
                            FilterChip(selected = amount == a, onClick = { digits = amountDigits(a) }, label = { Text(Fmt.won(a)) })
                        }
                    }
                }
                OutlinedTextField(
                    value = place,
                    onValueChange = { v ->
                        place = v.take(40)
                        // A place seen before (or a known brand) fills its category until the user picks one.
                        if (!categoryTouched && kind == MoneyKind.EXPENSE) {
                            val guess = PaymentParser.guessCategory(place.trim(), rules)
                            if (guess != "기타" && guess in MoneyCategories.expense) category = guess
                        }
                    },
                    singleLine = true,
                    label = { Text("어디서·무엇 (선택)") }, modifier = Modifier.fillMaxWidth(),
                )
                CategoryChips(MoneyCategories.of(kind), category) { category = it; categoryTouched = true }
                if (!initial?.note.isNullOrBlank() && !initial?.merchant.isNullOrBlank()) {
                    Text(initial!!.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (kind == MoneyKind.TRANSFER) {
                    Text("내 계좌끼리 옮긴 돈이라 수입·지출 합계에는 넣지 않아요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (kind == MoneyKind.INCOME && category == MoneyCategories.SALARY) {
                    Text("실제로 받은 급여를 적으면 그달은 예상 급여 대신 이 금액으로 계산해요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = amount > 0, onClick = {
                // A cancelled payment is stored negative; editing it keeps the sign.
                val signed = if (cancel) -amount else amount
                val base = initial ?: MoneyEntry(date = onDay, kind = kind, amount = signed, category = category)
                onSave(base.copy(date = onDay, kind = kind, amount = signed, category = category, merchant = place.trim(),
                    note = if (initial?.merchant.isNullOrBlank()) "" else initial!!.note, reviewed = true))
            }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (!isNew && onDelete != null) {
                    TextButton(onClick = { onDelete(initial!!) }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

/** Books or edits a job for field and freelance work [WORK-02]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobDialog(
    date: LocalDate,
    initial: Job?,
    onSave: (Job) -> Unit,
    onDelete: ((Job) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var client by rememberSaveable { mutableStateOf(initial?.client.orEmpty()) }
    var place by rememberSaveable { mutableStateOf(initial?.place.orEmpty()) }
    var start by rememberSaveable { mutableStateOf(initial?.start?.toString()) }
    var end by rememberSaveable { mutableStateOf(initial?.end?.toString()) }
    var amount by rememberSaveable { mutableStateOf(amountDigits(initial?.amount ?: 0)) }
    var deposit by rememberSaveable { mutableStateOf(amountDigits(initial?.deposit ?: 0)) }
    var depositPaid by rememberSaveable { mutableStateOf(initial?.depositPaid ?: false) }
    var balancePaid by rememberSaveable { mutableStateOf(initial?.balancePaid ?: false) }
    var remind by rememberSaveable { mutableStateOf(initial?.remindMinutes) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var days by rememberSaveable { mutableStateOf(initial?.days ?: 1) }
    var costs by rememberSaveable { mutableStateOf(amountDigits(initial?.costs ?: 0)) }
    var withholding by rememberSaveable { mutableStateOf(initial?.withholding ?: false) }
    var picking by remember { mutableStateOf<String?>(null) }
    val startTime = start?.let(LocalTime::parse)
    val endTime = end?.let(LocalTime::parse)
    val hasDeposit = deposit.toAmount() > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "${Fmt.dayTitle(date)} 일감" else "일감 고치기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(40) }, singleLine = true,
                    label = { Text("일 내용 (예: 욕실 타일)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = client, onValueChange = { client = it.take(30) }, singleLine = true,
                        label = { Text("고객") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = place, onValueChange = { place = it.take(40) }, singleLine = true,
                        label = { Text("장소") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeChip("시작", startTime, Modifier.weight(1f)) { picking = "start" }
                    TimeChip("끝", endTime, Modifier.weight(1f)) { picking = "end" }
                }
                // [WORK-02] Work that runs several days (욕실 3일, 주방 5일) is booked once.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("작업 기간", style = MaterialTheme.typography.bodyLarge)
                        val start = initial?.date ?: date
                        Text(if (days == 1) "하루" else "${Fmt.shortDate(start)} ~ ${Fmt.shortDate(start.plusDays(days - 1L))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = days > 1, onClick = { days-- }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text("${days}일", style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = days < MAX_JOB_DAYS, onClick = { days++ }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
                AmountField("받을 금액 (전체)", amount, { amount = it })
                SwitchRow("3.3% 떼고 받아요 (사업소득)", withholding) { withholding = it }
                if (withholding && amount.toAmount() > 0) {
                    val w = com.bamnat.core.money.Withholding.of(amount.toAmount())
                    Text("세금 ${Fmt.won(w)}을 떼고 ${Fmt.won(amount.toAmount() - w)}이 들어와요. 5월 종합소득세 신고 때 정산돼요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AmountField("선금 (계약금)", deposit, { deposit = it })
                if (hasDeposit) SwitchRow("선금 받음", depositPaid) { depositPaid = it }
                SwitchRow(if (hasDeposit) "잔금 받음" else "돈 받음", balancePaid) { balancePaid = it }
                Text("시작 전 알림", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JobReminders.CHOICES.forEach { m ->
                        FilterChip(selected = remind == m, enabled = startTime != null || m == null, onClick = { remind = m },
                            label = { Text(JobReminders.label(m)) })
                    }
                }
                if (startTime == null) {
                    Text("시작 시간을 정하면 알림을 받을 수 있어요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AmountField("자재비·경비 (선택)", costs, { costs = it })
                if (costs.toAmount() > 0 && amount.toAmount() > 0) {
                    val payable = amount.toAmount() - if (withholding) com.bamnat.core.money.Withholding.of(amount.toAmount()) else 0
                    Text("이 일로 남는 돈 ${Fmt.won(payable - costs.toAmount())}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) },
                    label = { Text("메모 (선택)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() || amount.toAmount() > 0, onClick = {
                onSave(
                    Job(
                        id = initial?.id ?: 0, date = initial?.date ?: date, title = title.trim().ifEmpty { "일감" },
                        client = client.trim(), place = place.trim(), start = startTime, end = endTime,
                        amount = amount.toAmount(), deposit = deposit.toAmount(), depositPaid = hasDeposit && depositPaid,
                        balancePaid = balancePaid, remindMinutes = remind.takeIf { startTime != null }, note = note.trim(),
                        endDate = (initial?.date ?: date).plusDays(days - 1L).takeIf { days > 1 },
                        costs = costs.toAmount(), withholding = withholding,
                    ),
                )
            }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (initial != null && onDelete != null) {
                    TextButton(onClick = { onDelete(initial) }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )

    picking?.let { which ->
        TimePickerDialog(
            title = if (which == "start") "시작 시간" else "끝나는 시간",
            initial = (if (which == "start") startTime else endTime ?: startTime?.plusHours(8)) ?: LocalTime.of(9, 0),
            onDismiss = { picking = null },
            onConfirm = { t ->
                if (which == "start") {
                    // A first start time turns on the usual reminder an hour before.
                    if (start == null && remind == null) remind = 60
                    start = t.toString()
                } else {
                    end = t.toString()
                }
                picking = null
            },
        )
    }
}

@Composable
private fun TimeChip(label: String, time: LocalTime?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time?.let { Fmt.time(it) } ?: "정하지 않음", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A shop's sales and costs for one day [WORK-03]. */
@Composable
fun SalesDialog(date: LocalDate, initial: DailySales?, onSave: (DailySales) -> Unit, onDismiss: () -> Unit) {
    var sales by rememberSaveable { mutableStateOf(amountDigits(initial?.sales ?: 0)) }
    var costs by rememberSaveable { mutableStateOf(amountDigits(initial?.costs ?: 0)) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    val net = sales.toAmount() - costs.toAmount()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${Fmt.dayTitle(date)} 매출") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AmountField("매출", sales, { sales = it })
                AmountField("경비 (재료비·인건비 등)", costs, { costs = it })
                OutlinedTextField(value = note, onValueChange = { note = it.take(100) }, singleLine = true,
                    label = { Text("메모 (선택)") }, modifier = Modifier.fillMaxWidth())
                Text("순수익 ${Fmt.won(net)}", style = MaterialTheme.typography.titleMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(DailySales(date, sales.toAmount(), costs.toAmount(), note.trim())) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
