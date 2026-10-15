package com.bamnat.app.ui.calendar

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.share.ShareLinkClient
import com.bamnat.app.share.ShareLinkException
import com.bamnat.app.share.ShareLinkPayload
import com.bamnat.app.share.ShareLinkState
import com.bamnat.app.share.ShareLinkStore
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [MKT-09] Picture or link. A link shows about three months from the month on screen, read only,
 * and expires by itself; the phone keeps one link and can refresh it with the latest schedule or cut it.
 */
@Composable
fun ShareDialog(
    snapshot: ScheduleSnapshot,
    month: YearMonth,
    onImage: () -> Unit,
    onDismiss: () -> Unit,
    client: ShareLinkClient = remember { ShareLinkClient() },
    store: ShareLinkStore = LocalContext.current.let { c -> remember { ShareLinkStore(c) } },
    preview: ShareLinkState? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(preview ?: store.load()) }
    var title by remember { mutableStateOf(link?.title.orEmpty()) }
    var ttl by remember { mutableIntStateOf(30) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(action: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { action() } catch (e: Exception) { error = messageFor(e) } finally { busy = false }
        }
    }
    val payload = { ShareLinkPayload.build(snapshot, month.atDay(1), title, ttl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("근무표 공유") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onImage(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("이 달 이미지로 보내기") }
                Text("링크로 보내기", style = MaterialTheme.typography.titleSmall)
                Text("받는 사람은 앱 없이 브라우저로 ${month.monthValue}월부터 3개월 근무표를 봐요. 메모와 급여는 들어가지 않고, 정한 기간이 지나면 링크가 저절로 지워져요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = title, onValueChange = { if (it.length <= 30) title = it }, singleLine = true,
                    label = { Text("이름 (선택, 예: 민지)") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShareLinkPayload.TTL_DAYS.forEach { d -> FilterChip(selected = ttl == d, onClick = { ttl = d }, label = { Text("${d}일") }) }
                }
                val current = link
                if (current == null) {
                    Button(enabled = !busy, onClick = {
                        run { client.create(payload()).also { store.save(it); link = it; send(context, it) } }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "만드는 중…" else "링크 만들어 보내기") }
                } else {
                    Text("만든 링크: ${current.url}\n${expiryText(current)}까지 열려요.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy, onClick = { send(context, current) }, modifier = Modifier.fillMaxWidth()) { Text("이 링크 다시 보내기") }
                    OutlinedButton(enabled = !busy, onClick = {
                        run { client.update(current, payload()).also { store.save(it); link = it } }
                    }, modifier = Modifier.fillMaxWidth()) { Text("지금 근무표로 고치기") }
                    TextButton(enabled = !busy, onClick = {
                        run { client.delete(current); store.clear(); link = null }
                    }, modifier = Modifier.fillMaxWidth()) { Text("링크 끊기", color = MaterialTheme.colorScheme.error) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

private fun expiryText(s: ShareLinkState): String =
    s.expiresAt.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("M월 d일"))

private fun messageFor(e: Exception): String = when (e.message) {
    "rate_limited" -> "잠시 뒤 다시 해 주세요. 한 시간에 만들 수 있는 링크 수를 넘었어요."
    "not_found", "forbidden" -> "이 링크는 이미 지워졌어요. 링크 끊기를 누른 뒤 새로 만들어 주세요."
    else -> if (e is ShareLinkException) "링크를 만들지 못했어요 (${e.message}). 다시 해 주세요."
    else if (e is java.io.IOException) "인터넷에 연결되지 않았어요. 연결을 확인하고 다시 해 주세요." else "링크를 만들지 못했어요. 다시 해 주세요."
}

private fun send(context: Context, s: ShareLinkState) {
    val text = (if (s.title.isNotBlank()) "${s.title}의 근무표" else "근무표") + "\n${s.url}"
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(send, "근무표 링크 보내기"))
}
