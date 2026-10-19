package com.bamnat.app.ui.company

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.ui.appViewModel

/**
 * [PAYS-09] Whether the invitation to share the company's way may show, on this phone only: "다음에" puts it off
 * for a month and "그만 볼게요" for good, so it never nags.
 */
internal class ShareWayMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("share_way", Context.MODE_PRIVATE)

    fun due(now: Long = System.currentTimeMillis()): Boolean = !prefs.getBoolean("never", false) && now >= prefs.getLong("later", 0)

    fun later(now: Long = System.currentTimeMillis()) = prefs.edit().putLong("later", now + LATER_MILLIS).apply()

    fun never() = prefs.edit().putBoolean("never", true).apply()

    companion object {
        const val LATER_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

/** [PAYS-09] What just happened, which decides how the invitation is worded. */
enum class ShareMoment {
    /** The payslip and the app came to the same month. */
    MATCHED,
    /** The user made an item of the company's own. */
    MADE,
}

/**
 * [PAYS-09] Right after the setup proved itself (명세서와 맞음) or the user made an item of the company's own: an
 * invitation to share the company's way through the paths that already exist, 회사 설정 보내기 and 회사 방식 알려
 * 주기. Its reasons are the true ones: coworkers get the right pay at once, a payslip-checked setup counts double
 * and an adopted one is marked 채택 on 광장, and the reward is said exactly as 회사 설정 says it (a promise while
 * billing is not live). Nothing here is tied to store reviews, no amount goes anywhere, and "다음에"·"그만 볼게요"
 * are remembered. [ways] is the company's own items in words without amounts, filled into 회사 방식 알려 주기 for the
 * user to read and change before anything is sent; items made by hand are not in the setup file yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareWayCard(
    moment: ShareMoment,
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
    ways: String = "",
    /** Some of [ways] are rules made by hand, which the setup file cannot carry yet (plan item 60). */
    notInFile: Boolean = false,
    onClosed: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.appContainer
    val memory = remember { ShareWayMemory(context) }
    var shown by rememberSaveable { mutableStateOf(memory.due()) }
    var asking by remember { mutableStateOf(false) }
    val companyOn = container.companyApi.enabled
    val askOn = container.inquiryApi.enabled
    if (!shown || !companyOn && !askOn) return
    val canShare = onShare != null && companyOn
    if (!canShare && !askOn) return
    // Only when there is a server: the terms come from it, and a phone without one asks nothing.
    val terms = if (companyOn) appViewModel { CompanyNudgeViewModel(it) }.terms.collectAsStateWithLifecycle().value else null
    fun close(remember: () -> Unit) { remember(); shown = false; onClosed() }

    SoftCard(highlighted = true, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Groups, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (moment == ShareMoment.MATCHED) "명세서와 맞았어요. 이 방식을 동료와 나눌까요?" else "우리 회사 방식, 동료에게도 알려 줄까요?",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            Reason("같은 회사 동료는 회사를 고르기만 하면 1분 만에 맞는 급여를 봐요")
            Reason(
                if (moment == ShareMoment.MATCHED) "명세서로 맞춰 본 설정은 2점이라 더 빨리 채택되고, 채택되면 광장에 ‘채택’ 표시가 붙어요"
                else "같은 회사 분들이 맞다고 하면 채택되고, 광장에 ‘채택’ 표시가 붙어 모두가 써요",
            )
            Reason("금액·이름·연락처는 보내지 않아요")
            rewardLine(terms)?.let { RewardPill(it) }
            if (notInFile) {
                Text(
                    "직접 만든 항목은 아직 회사 설정 파일에 담기지 않아요. ‘말로 알려 주기’로 보내 주시면 개발자가 보고 넣어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The way that carries what was just made goes first: items made by hand only travel in words for now.
            val askFirst = askOn && (notInFile || !canShare)
            val share: @Composable (Boolean) -> Unit = { primary ->
                val onClick = { close(memory::later); onShare?.invoke(); Unit }
                if (primary) Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text("회사 설정 보내기") }
                else OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text("회사 설정 보내기") }
            }
            val ask: @Composable (Boolean) -> Unit = { primary ->
                if (primary) Button(onClick = { asking = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("말로 알려 주기") }
                else OutlinedButton(onClick = { asking = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("말로 알려 주기") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (askFirst) {
                    ask(true)
                    if (canShare) share(false)
                } else {
                    if (canShare) share(true)
                    if (askOn) ask(false)
                }
            }
            Row {
                TextButton(onClick = { close(memory::later) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("다음에") }
                TextButton(onClick = { close(memory::never) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("그만 볼게요", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (asking) {
        val store = container.companyStore
        val company = store.applied.value?.company ?: store.draft.value?.company ?: ""
        AskDeveloperDialog(company, onDismiss = { asking = false }, onSent = { asking = false; close(memory::later) }, prefill = ways)
    }
}

@Composable
private fun Reason(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
