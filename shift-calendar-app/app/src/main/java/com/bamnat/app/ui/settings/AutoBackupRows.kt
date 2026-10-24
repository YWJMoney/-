package com.bamnat.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.backup.AutoBackup
import com.bamnat.app.data.BackupManager
import com.bamnat.app.tier.Feature
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.pro.openPro
import java.time.ZoneId
import com.bamnat.core.time.Today

/** [PROV-03] The row's title, which its notice and settings search point at. */
const val AUTO_BACKUP_ROW = "매일 자동 백업"

/**
 * [PROV-03] 매일 자동 백업 (프로): a password, then a file the user picks (Google Drive works), then a
 * sealed backup there every day. The row says when it last worked and why not when it does not.
 */
@Composable
internal fun AutoBackupRows(vm: SettingsViewModel) {
    val context = LocalContext.current
    val state by vm.autoBackup.collectAsStateWithLifecycle()
    val gate by vm.gate.collectAsStateWithLifecycle()
    val open = gate.allows(Feature.AUTO_BACKUP)
    var asking by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)) { uri ->
        if (uri != null) vm.startAutoBackup(context, uri) else vm.prepareAutoBackup(null)
    }
    val move = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)) { uri ->
        uri?.let(vm::moveAutoBackup)
    }
    SwitchRow(
        title = AUTO_BACKUP_ROW,
        subtitle = (if (open) "" else "프로 · ") + "고른 파일(구글 드라이브도 돼요)에 하루 한 번 암호 건 백업을 덮어쓰고, 다시 읽어 확인해요",
        checked = state.on && open,
    ) { want ->
        when {
            !open -> openPro()
            want -> asking = true
            else -> confirmOff = true
        }
    }
    if (state.on) {
        val line = statusLine(state, open)
        Text(line.first, style = MaterialTheme.typography.bodySmall,
            color = if (line.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::backupNow, enabled = open && !state.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (state.running) "백업하는 중…" else "지금 백업")
            }
            OutlinedButton(onClick = { move.launch(AutoBackup.FILE_NAME) }, enabled = !state.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("위치 바꾸기")
            }
        }
    }
    if (asking) {
        AutoBackupPasswordDialog(onDismiss = { asking = false }) { password ->
            asking = false
            vm.prepareAutoBackup(password)
            create.launch(AutoBackup.FILE_NAME)
        }
    }
    if (confirmOff) {
        ConfirmDialog(
            title = "자동 백업을 끌까요?",
            message = "지금까지 저장한 파일은 그대로 남아요. ‘파일 불러오기’로 암호를 넣어 열 수 있어요. 다시 켜면 암호와 위치를 새로 정해요.",
            confirmLabel = "끄기",
            onDismiss = { confirmOff = false },
            onConfirm = { confirmOff = false; vm.stopAutoBackup() },
        )
    }
}

/** What the row says under the switch, and whether it is a problem. */
internal fun statusLine(s: AutoBackup.State, open: Boolean, zone: ZoneId = Today.zone()): Pair<String, Boolean> {
    val last = s.lastOk?.atZone(zone)?.let { "${Fmt.monthDay(it.toLocalDate())} ${Fmt.time(it.toLocalTime())}" }
    val where = s.name?.let { " · $it" }.orEmpty()
    return when {
        !open -> "프로가 끝나 멈췄어요. 프로를 다시 쓰면 이어서 해요." + (last?.let { " 마지막 백업 $it$where" }.orEmpty()) to false
        s.running -> "백업하는 중…$where" to false
        s.error != null -> s.error + (last?.let { " (마지막 성공 $it)" } ?: "") to true
        last != null -> "마지막 백업 $last$where" to false
        else -> "첫 백업을 기다리는 중$where" to false
    }
}
