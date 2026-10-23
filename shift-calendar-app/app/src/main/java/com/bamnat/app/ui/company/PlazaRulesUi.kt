package com.bamnat.app.ui.company

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.company.TalkPost
import com.bamnat.core.plaza.HideList
import com.bamnat.core.plaza.PlazaRules

/**
 * [COMM-08] Google Play's rule for posts people write (UGC): the user agrees to the plaza's rules
 * before the first post, reply, proposal or 임금표 entry. Asked once per rules version.
 */
@Stable
class PlazaRulesGate internal constructor(private val agreed: () -> Boolean, private val agree: () -> Unit) {
    internal var waiting by mutableStateOf<(() -> Unit)?>(null)

    /** Runs [then] now when the rules are agreed, or after the user agrees to them. */
    fun ensure(then: () -> Unit) {
        if (agreed()) then() else waiting = then
    }

    internal fun accept() {
        agree()
        waiting?.invoke()
        waiting = null
    }
}

@Composable
fun rememberPlazaRules(): PlazaRulesGate {
    val c = LocalContext.current.appContainer
    val gate = remember {
        PlazaRulesGate(
            agreed = { PlazaRules.agreed(c.settingsRepository.current.plazaRulesAgreed) },
            agree = {
                c.settingsRepository.update { it.copy(plazaRulesAgreed = PlazaRules.VERSION) }
                c.telemetry.event("plaza_rules_agree", "v${PlazaRules.VERSION}")
            },
        )
    }
    PlazaRulesDialog(gate)
    return gate
}

@Composable
private fun PlazaRulesDialog(gate: PlazaRulesGate) {
    if (gate.waiting == null) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { gate.waiting = null },
        title = { Text("광장 운영 규칙") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // [COMM-11] I6: after a change, what changed is marked, and the rest is the same as agreed.
                val changed = PlazaRules.changedSince(context.appContainer.settingsRepository.current.plazaRulesAgreed)
                Text(if (changed.isEmpty()) "처음 글을 쓰기 전에 한 번만 봐 주세요. 모두가 편하게 쓰는 광장을 위한 약속이에요."
                    else "운영 규칙이 바뀌었어요. 바뀐 줄(${changed.size}개)을 보고 다시 동의해 주세요.",
                    style = MaterialTheme.typography.bodyMedium)
                PlazaRules.LINES.forEachIndexed { i, line ->
                    if (i in changed) Text("${i + 1}. $line (바뀜)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    else Text("${i + 1}. $line", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PlazaRules.URL))) }
                }) { Text("운영 규칙 전체 보기") }
            }
        },
        confirmButton = { TextButton(onClick = gate::accept) { Text("동의하고 쓰기") } },
        dismissButton = { TextButton(onClick = { gate.waiting = null }) { Text("나중에") } },
    )
}

/** [COMM-09] Asks before hiding a writer; the list can be undone in 내 활동 > 가린 사람. */
@Composable
fun HideWriterDialog(post: TalkPost?, onDismiss: () -> Unit, onHide: (TalkPost) -> Unit) {
    val p = post ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이 사람 글을 안 볼까요?") },
        text = {
            Text("${p.nick ?: "참여자 ${p.who}"}님의 글과 답글이 모든 토론에서 이 휴대폰에만 보이지 않아요. 상대에게는 알리지 않고, " +
                "서버에도 목록을 남기지 않아요. 내 활동 > 가린 사람에서 언제든 다시 볼 수 있어요.", style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onHide(p) }) { Text("안 보기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * [COMM-09][COMM-10] 내 활동's part about this phone: 광장 소식 알림 (on unless turned off) and the writers
 * the user hid, each with 다시 보기.
 */
@Composable
fun PlazaPhoneSettings() {
    val c = LocalContext.current.appContainer
    val settings by c.settingsRepository.settings.collectAsStateWithLifecycle()
    val hidden = HideList.decode(settings.plazaHidden)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = settings.plazaNews, role = Role.Switch) { on -> c.settingsRepository.update { it.copy(plazaNews = on) } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("밤낮 소식 알림", style = MaterialTheme.typography.titleSmall)
                Text("쓰는 규칙이 바뀌거나 보낸 규칙·제안·시세가 채택되거나 신고한 글에 결과가 나오면, 문의 답과 함께 하루에 한 번 모아서 알려 줘요. " +
                    "켜 두어도 광고는 오지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = settings.plazaNews, onCheckedChange = null)
        }
        Text("가린 사람 ${hidden.writers.size}", style = MaterialTheme.typography.titleSmall)
        if (hidden.writers.isEmpty()) {
            Text("토론 글의 ⋮ > 이 사람 글 안 보기로 가린 분이 여기 모여요. 이 휴대폰에만 남아요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        hidden.writers.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(w.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { c.settingsRepository.update { s -> s.copy(plazaHidden = HideList.decode(s.plazaHidden).show(w.mark).encode()) } }) {
                    Text("다시 보기")
                }
            }
        }
    }
}
