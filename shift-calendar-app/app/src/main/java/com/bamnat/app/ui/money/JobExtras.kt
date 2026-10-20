package com.bamnat.app.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.money.DayRate
import com.bamnat.core.money.JobStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [FLD-02] The client (업체·고객) field: names from the calendar's past jobs drop down as the user
 * types, so the same 업체 is written the same way every time and its jobs add up together.
 */
@Composable
fun ClientField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String = "고객·업체") {
    val book = rememberLedger(null)
    val all = remember(book.jobs) { DayRate.clients(book.jobs, limit = 30) }
    var focused by remember { mutableStateOf(false) }
    val shown = remember(all, value) {
        val q = value.trim()
        all.filter { it != q && (q.isEmpty() || it.contains(q, ignoreCase = true)) }.take(5)
    }
    Box(modifier) {
        OutlinedTextField(value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        DropdownMenu(
            expanded = focused && shown.isNotEmpty(),
            onDismissRequest = { focused = false },
            properties = PopupProperties(focusable = false),
        ) {
            shown.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { onChange(c); focused = false }) }
        }
    }
}

/**
 * [FLD-03] A job's step: 가계약 (not yet sure), 확정, 완료 (work done) or 취소. 입금 is not a step the
 * user picks: once the money is ticked as paid the row says 입금 완료.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobStatusRow(status: JobStatus, paid: Boolean, onChange: (JobStatus) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("진행 상태", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (paid && status != JobStatus.CANCELLED) {
                Text("입금 완료", style = MaterialTheme.typography.labelLarge, color = LocalCalendarColors.current.income)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            JobStatus.entries.forEach { s ->
                FilterChip(selected = status == s, onClick = { onChange(s) }, label = { Text(s.label) })
            }
        }
        if (status == JobStatus.CANCELLED) {
            Text("취소한 일감은 번 돈·받을 돈·통계·알림에서 빠져요. 받은 선금은 그대로 남아요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [FLD-09] 입금 약속일: when the client said they would pay; the 받을 돈 알림 follows it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueRow(due: LocalDate?, onChange: (LocalDate?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picking = true }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("입금 약속일 (선택)", style = MaterialTheme.typography.bodyLarge)
            Text(due?.let { "${Fmt.dayTitle(it)} · 다음 날까지 안 들어오면 알려 드려요" } ?: "정하면 그 다음 날 받을 돈을 알려 드려요",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (due != null) TextButton(onClick = { onChange(null) }) { Text("지우기") }
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (due ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("취소") } },
        ) { DatePicker(state, showModeToggle = false) }
    }
}
