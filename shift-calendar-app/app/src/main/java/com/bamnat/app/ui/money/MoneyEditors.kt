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
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    textStyle: androidx.compose.ui.text.TextStyle? = null,
    /** [SHOP-03] 0 is an answer here (센 현금 0원), not an empty field. */
    allowZero: Boolean = false,
) {
    OutlinedTextField(
        value = digits,
        onValueChange = { v -> onChange(amountInput(v, allowZero)) },
        label = { Text(label) },
        textStyle = textStyle ?: androidx.compose.material3.LocalTextStyle.current,
        suffix = { Text("원") },
        singleLine = true,
        visualTransformation = ThousandsTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        modifier = modifier.fillMaxWidth(),
    )
}

/** What an amount field keeps of what was typed: digits without leading zeros, a lone 0 only when [allowZero]. */
internal fun amountInput(typed: String, allowZero: Boolean = false): String {
    val d = typed.filter(Char::isDigit)
    return d.trimStart('0').ifEmpty { if (allowZero && d.isNotEmpty()) "0" else "" }.take(MAX_DIGITS)
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
    var days by rememberSaveable { mutableIntStateOf(initial?.days ?: 1) }
    var costs by rememberSaveable { mutableStateOf(amountDigits(initial?.costs ?: 0)) }
    var withholding by rememberSaveable { mutableStateOf(initial?.withholding ?: false) }
    // [FLD-03] 가계약·확정·완료·취소; [FLD-09] the day the money is promised.
    var status by rememberSaveable { mutableStateOf(initial?.status ?: com.bamnat.core.money.JobStatus.CONFIRMED) }
    var due by rememberSaveable { mutableStateOf(initial?.due?.toString()) }
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
                    // [FLD-02] Names from past jobs one tap away.
                    ClientField(client, { client = it.take(30) }, Modifier.weight(1f))
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
                JobStatusRow(status, paid = balancePaid && (!hasDeposit || depositPaid) && amount.toAmount() > 0) { status = it }
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
                DueRow(due?.let(LocalDate::parse)) { due = it?.toString() }
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
                        status = status, units = initial?.units, due = due?.let(LocalDate::parse),
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

/** A shop's sales and costs for one day [WORK-03]; [SHOP-01] now the 하루 마감 sheet. */
@Composable
fun SalesDialog(date: LocalDate, initial: DailySales?, onSave: (DailySales) -> Unit, onDismiss: () -> Unit) =
    DayCloseDialog(date, initial, onSave, onDismiss)
