package com.bamnat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.calendar.ExtraWorkMark
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.model.ExtraWork

/** Hours offered for a tap; the day itself can still be set to any time. */
private val HOUR_CHOICES = listOf(60, 120, 180, 240, 480, 720)

/**
 * [PAYX-09] 잔업·특근·대근 the way this person's company says it: a name of its own (조출, 철야,
 * 대체근무 …), whether the kind is used at all, and the hours one tap fills in. Pay rules for each
 * kind stay in 급여 계산 → 회사 규칙.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtraKindsDialog(initial: ExtraKinds, onDismiss: () -> Unit, onSave: (ExtraKinds) -> Unit) {
    var kinds by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("잔업 · 특근 · 대근") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("회사마다 부르는 이름과 쓰는 종류가 달라요. 여기서 바꾸면 달력과 급여 내역에 그대로 보여요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ExtraWork.entries.forEachIndexed { i, k ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    val used = k !in kinds.hidden
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        ExtraWorkMark(k, size = 20.dp)
                        Text("  ${k.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (used) "사용" else "안 씀", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Switch(checked = used, onCheckedChange = { on ->
                            // At least one kind stays, so a day can always be marked.
                            val hidden = if (on) kinds.hidden - k else kinds.hidden + k
                            if (hidden.size < ExtraWork.entries.size) kinds = kinds.copy(hidden = hidden)
                        }, modifier = Modifier.padding(start = 8.dp))
                    }
                    if (used) {
                        OutlinedTextField(
                            value = kinds.names[k] ?: "",
                            onValueChange = { v -> kinds = kinds.copy(names = if (v.isBlank()) kinds.names - k else kinds.names + (k to v.take(8))) },
                            label = { Text("회사에서 부르는 이름") },
                            placeholder = { Text(k.label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("한 번 누르면 넣을 시간 (근무일)", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val now = kinds.minutesFor(k, workDay = true)
                            HOUR_CHOICES.forEach { m ->
                                FilterChip(selected = now == m, onClick = {
                                    kinds = kinds.copy(minutes = if (m == ExtraKinds.DEFAULT_WORK_DAY_MINUTES) kinds.minutes - k else kinds.minutes + (k to m))
                                }, label = { Text(HoursFormat.short(m)) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(kinds) }, enabled = kinds != initial) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
