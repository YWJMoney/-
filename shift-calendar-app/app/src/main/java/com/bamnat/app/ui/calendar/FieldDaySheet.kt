package com.bamnat.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bamnat.app.appContainer
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.money.AmountField
import com.bamnat.app.ui.money.ClientField
import com.bamnat.app.ui.money.amountDigits
import com.bamnat.app.ui.money.toAmount
import com.bamnat.core.money.DayRate
import com.bamnat.core.money.FieldMode
import com.bamnat.core.money.FieldRules
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyBook
import java.time.LocalDate

/**
 * [FLD-02] The day sheet's 공수 buttons for a day-rate field calendar: 1공, 0.5, 1.5 and 직접. A tap
 * writes the day's job (or changes the one there) with 일당 × 공수 for the last 업체; a second tap on
 * the chosen one takes it off. Before the first 일당 is known, 직접 opens to ask for it.
 */
internal fun LazyListScope.dayRateChips(
    date: LocalDate,
    book: MoneyBook,
    rules: FieldRules,
    onSave: (Job, FieldRules) -> Unit,
    onDelete: (Job) -> Unit,
) {
    val existing = DayRate.on(date, book.jobs)
    for (u in DayRate.UNITS) item {
        var asking by rememberSaveable { mutableStateOf(false) }
        val on = existing?.units == u
        FilterChip(
            selected = on,
            onClick = {
                when {
                    on && existing != null -> onDelete(existing)
                    rules.dayRate <= 0 && existing == null -> asking = true
                    // A day already written keeps its own 일당; a new day takes the remembered one.
                    else -> onSave(DayRate.job(date, u, existing?.let(DayRate::rateOf) ?: rules.dayRate, rules.lastClient, existing), rules)
                }
            },
            label = { Text(DayRate.label(u), maxLines = 1) },
            leadingIcon = if (on) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
        )
        if (asking) DayRateDialog(date, u, rules, existing, onDismiss = { asking = false }) { job, r -> asking = false; onSave(job, r) }
    }
    item {
        var asking by rememberSaveable { mutableStateOf(false) }
        val custom = existing?.units?.takeIf { it !in DayRate.UNITS }
        AssistChip(
            onClick = { asking = true },
            label = { Text(custom?.let { DayRate.label(it) } ?: "직접", maxLines = 1) },
            leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)) },
        )
        if (asking) DayRateDialog(date, existing?.units ?: 1.0, rules, existing, onDismiss = { asking = false }) { job, r -> asking = false; onSave(job, r) }
    }
}

/**
 * [FLD-02] Any 공수 (2, 0.25 …), the 일당 and the 업체 for one day; the 일당 and 업체 are remembered. [INPUT-02] The 공수
 * is tapped (0.5·1·1.5·2) or stepped by 0.25, not typed.
 */
@Composable
internal fun DayRateDialog(date: LocalDate, units: Double, rules: FieldRules, existing: Job?, onDismiss: () -> Unit, onSave: (Job, FieldRules) -> Unit) {
    var picked by rememberSaveable { mutableStateOf(units) }
    val existingRate = existing?.let(DayRate::rateOf)
    var rate by rememberSaveable { mutableStateOf(amountDigits(existingRate ?: rules.dayRate)) }
    var client by rememberSaveable { mutableStateOf(existing?.client ?: rules.lastClient) }
    val u = picked.takeIf { it > 0 && it <= 5 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${Fmt.dayTitle(date)} 공수") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                com.bamnat.app.ui.components.QuickDecimalChips(
                    value = picked, onChange = { picked = it }, name = "공수", quick = listOf(0.5, 1.0, 1.5, 2.0),
                    step = 0.25, range = 0.25..5.0, unit = "공",
                )
                AmountField("일당 (1공)", rate, { rate = it })
                // [FLD-12] 공종별 평균 일당, [FLD-13] 그 일당의 일용직 세금.
                com.bamnat.app.ui.work.TradeWageLink { rate = it.toString() }
                com.bamnat.app.ui.work.dailyTaxLine(rate.toAmount())?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ClientField(client, { client = it.take(30) }, label = "업체")
                if (u != null && rate.toAmount() > 0) {
                    Text("${DayRate.label(u)} × ${Fmt.won(rate.toAmount())} = ${Fmt.won(DayRate.amount(rate.toAmount(), u))}",
                        style = MaterialTheme.typography.titleSmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = u != null, onClick = {
                val r = rules.copy(dayRate = rate.toAmount(), lastClient = client.trim())
                val job = DayRate.job(date, u!!, r.dayRate, r.lastClient, existing).let { if (existing != null) it.copy(client = client.trim()) else it }
                onSave(job, r)
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * [FLD-01] Asked once on a field calendar that has not said how it counts its work: by the day
 * (공수) or by the job. Closing the question keeps 건별형, and it can be changed in 달력 고치기.
 */
@Composable
internal fun FieldModePrompt(onPick: (FieldMode) -> Unit) {
    AlertDialog(
        onDismissRequest = { onPick(FieldMode.PER_JOB) },
        title = { Text("일을 어떻게 세요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("고르는 대로 날짜를 눌렀을 때 나오는 버튼이 달라져요. 나중에 달력 고치기에서 바꿀 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium)
                FieldMode.entries.forEach { m ->
                    OutlinedButton(onClick = { onPick(m) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(m.label, style = MaterialTheme.typography.titleSmall)
                            Text(m.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

/** [FLD-01] 달력 고치기: 일당(공수)형 or 건별형 for a field calendar, the main one too. */
@Composable
internal fun FieldModeSetting(calendar: com.bamnat.app.data.PersonalCalendar) {
    val calendars = androidx.compose.ui.platform.LocalContext.current.appContainer.calendars
    val mode = calendar.field?.mode ?: FieldMode.PER_JOB
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("일 세는 방법", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { calendars.updateField(calendar.id) { it.copy(mode = m) } }, label = { Text(m.label) })
            }
        }
        Text(mode.hint + if (mode == FieldMode.DAY_RATE) ". 날짜를 누르면 1공·0.5·1.5 버튼이 나와요" else ". 날짜를 누르면 일감 적기가 나와요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val rate = calendar.field?.dayRate ?: 0
        if (mode == FieldMode.DAY_RATE && rate > 0) {
            Text("일당 ${Fmt.won(rate)}" + (calendar.field?.lastClient?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
