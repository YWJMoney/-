package com.bamnat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.CompanySummary

/** [COMM-00] What goes in the company file, and whether allowance amounts do. */
@Composable
fun CompanyShareDialog(hasPay: Boolean, onDismiss: () -> Unit, onShare: (String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var amounts by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("회사 설정 보내기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("회사·부서 이름 (선택)") }, singleLine = true)
                Text("들어가는 것: 근무 종류와 시간, 근무 주기, 잔업·특근 이름" + if (hasPay) ", 회사 급여 규칙, 수당 이름" else "",
                    style = MaterialTheme.typography.bodySmall)
                Text("안 들어가는 것: 기본급, 입사일, 연차 일수, 알람, 달력에 적은 내용과 메모",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasPay) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("수당 금액도 넣기", style = MaterialTheme.typography.bodyLarge)
                            Text("모두 같은 금액을 받을 때만 켜세요", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = amounts, onCheckedChange = { amounts = it })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onShare(name, amounts) }) { Text("보내기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** [COMM-00] What a received company file holds, before it changes anything. */
@Composable
fun CompanyImportDialog(summary: CompanySummary, hasPay: Boolean, onDismiss: () -> Unit, onApply: (Boolean) -> Unit) {
    var rotation by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (summary.company.isNotBlank()) "${summary.company} 설정을 넣을까요?" else "회사 설정을 넣을까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("근무 종류 ${summary.shiftTypes.size}개: ${summary.shiftTypes.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                Text("같은 이름은 시간만 맞추고, 알람과 색은 그대로 둬요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                summary.cycleDays?.let { days ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("${days}일 근무 주기도 넣기", style = MaterialTheme.typography.bodyLarge)
                            Text("오늘부터 바뀌고 지난 날은 그대로예요", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = rotation, onCheckedChange = { rotation = it })
                    }
                }
                if (summary.hasRules) {
                    Text(
                        if (hasPay) "회사 급여 규칙" + (if (summary.items.isNotEmpty()) "과 수당 ${summary.items.size}개" else "") +
                            "를 넣어요. 기본급과 연차, 이미 있는 수당 금액은 그대로예요." +
                            (if (!summary.withAmounts && summary.items.isNotEmpty()) " 새 수당 금액은 직접 넣어 주세요." else "")
                        else "급여 규칙은 급여 설정을 마친 뒤에 넣을 수 있어요. 근무 종류와 주기만 먼저 넣어요.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(rotation) }) { Text("넣기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
