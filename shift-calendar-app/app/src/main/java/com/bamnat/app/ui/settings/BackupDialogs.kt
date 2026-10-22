package com.bamnat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** [FEAT-08] A backup file that needs its password before it can be read. */
data class LockedBackup(val text: String, val working: Boolean = false, val error: String? = null)

const val MIN_BACKUP_PASSWORD = 4

/** [FEAT-08] Optional password for a backup, typed twice. Null means no password. */
@Composable
fun ExportPasswordDialog(onDismiss: () -> Unit, onExport: (CharArray?) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val mismatch = second.isNotEmpty() && first != second
    val ok = first.length >= MIN_BACKUP_PASSWORD && first == second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("백업에 암호를 걸까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "암호를 걸면 파일을 다른 사람이 열어 볼 수 없어요. 암호는 어디에도 저장하지 않아서 잊으면 이 백업을 열 수 없어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PasswordField(first, { first = it }, "암호 (${MIN_BACKUP_PASSWORD}자 이상)")
                PasswordField(second, { second = it }, "암호 한 번 더", error = if (mismatch) "두 암호가 달라요" else null)
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(first.toCharArray()) }, enabled = ok) { Text("암호 걸고 저장") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("취소") }
                TextButton(onClick = { onExport(null) }) { Text("암호 없이") }
            }
        },
    )
}

/**
 * [PROV-03] The password 자동 백업 seals every file with, typed twice; it is not optional there, since
 * the file may sit in Google Drive. Picking where to save comes next.
 */
@Composable
fun AutoBackupPasswordDialog(onDismiss: () -> Unit, onSet: (CharArray) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val mismatch = second.isNotEmpty() && first != second
    val ok = first.length >= MIN_BACKUP_PASSWORD && first == second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자동 백업 암호") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "매일 저장하는 백업 파일에 걸 암호예요. 휴대폰을 바꾸거나 앱을 다시 설치해 그 파일을 불러올 때 넣어요. " +
                        "암호는 어디에도 저장하지 않아서 잊으면 열 수 없어요.\n다음 화면에서 저장할 곳을 골라요. 구글 드라이브에 두면 휴대폰을 잃어버려도 꺼낼 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PasswordField(first, { first = it }, "암호 (${MIN_BACKUP_PASSWORD}자 이상)")
                PasswordField(second, { second = it }, "암호 한 번 더", error = if (mismatch) "두 암호가 달라요" else null)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(first.toCharArray()) }, enabled = ok) { Text("저장할 곳 고르기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** [FEAT-08] Asks for the password of a sealed backup; a wrong one stays here with a message. */
@Composable
fun UnlockBackupDialog(locked: LockedBackup, onUnlock: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!locked.working) onDismiss() },
        title = { Text("암호가 걸린 백업이에요") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("백업을 저장할 때 정한 암호를 넣어 주세요.", style = MaterialTheme.typography.bodyMedium)
                PasswordField(password, { password = it }, "암호", error = locked.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onUnlock(password.toCharArray()) }, enabled = password.isNotEmpty() && !locked.working) {
                if (locked.working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("열기")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !locked.working) { Text("취소") } },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(64)) },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
