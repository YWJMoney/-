package com.bamnat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.data.RecordsTrash
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog

/** [UNDO-01] What a row of 최근 지운 기록 says under its title. */
internal fun RecordsTrash.Entry.caption(now: Long): String {
    val at = AlarmScheduler.millisToLocal(at)
    return listOfNotNull("${Fmt.monthDay(at.toLocalDate())} ${Fmt.time(at.toLocalTime())}", detail.ifBlank { null }, "${daysLeft(now)}일 남음")
        .joinToString(" · ")
}

/**
 * [UNDO-01][UNDO-02][UNDO-03] 설정 › 데이터 › 최근 지운 기록: each change that cleared or removed
 * something in the last 7 days, newest first, to put back. Putting back asks first, saying what it does.
 */
@Composable
internal fun RecordsTrashDialog(entries: List<RecordsTrash.Entry>, onRestore: (RecordsTrash.Entry) -> Unit, onDismiss: () -> Unit) {
    var asking by remember { mutableStateOf<RecordsTrash.Entry?>(null) }
    val now = System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("최근 지운 기록") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "근무 형태를 바꾸거나 기록·달력을 지운 것을 7일 동안 되돌릴 수 있어요. 7일이 지나면 저절로 없어져요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (entries.isEmpty()) {
                    Text("최근 7일 동안 지운 기록이 없어요", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                }
                entries.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("‘${e.name}’ ${e.kind.label}", style = MaterialTheme.typography.titleSmall)
                            Text(e.caption(now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { asking = e }) { Text("되돌리기") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    asking?.let { e ->
        ConfirmDialog(
            title = "‘${e.name}’ 되돌릴까요?",
            message = when (e.kind) {
                RecordsTrash.Kind.STYLE -> "근무 형태를 바꾸기 전으로 돌려요. 그 뒤에 적은 기록은 그대로예요."
                RecordsTrash.Kind.DELETED -> "지운 달력을 근무·돈 기록·급여 설정과 함께 다시 살려요. 공유 링크는 다시 만들어야 해요."
                else -> "지우기 전의 근무 기록·근무 순서·급여 설정·일감·매출·받을 돈과 메모로 돌아가요. 지운 뒤에 새로 적은 근무 기록과 일감·매출은 없어져요."
            },
            confirmLabel = "되돌리기",
            onDismiss = { asking = null },
            onConfirm = { asking = null; onRestore(e) },
        )
    }
}
