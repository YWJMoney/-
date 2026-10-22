package com.bamnat.app.ui.pro

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.billing.Plan
import com.bamnat.app.billing.ProductIds
import com.bamnat.app.billing.rememberPlans
import com.bamnat.app.tier.SubRecord
import com.bamnat.app.tier.trialEnd
import com.bamnat.core.tier.TrialConsent
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** [MON-06] What the user is asked, word for word, and what is kept as the record of their answer. */
fun trialConsentText(end: java.time.LocalDate, price: String): String =
    "1개월 무료 체험이 ${end.monthValue}월 ${end.dayOfMonth}일에 끝나고, 그날부터 ${price}씩 자동으로 결제돼요. 계속 쓸까요?\n\n" +
        "해지하려면 Play 스토어 › 결제 및 정기 결제에서 해요. 해지해도 ${end.monthValue}월 ${end.dayOfMonth}일까지는 프로를 써요."

/** The price of the trial's plan as Play shows it, or plain words when Play has not said yet. */
fun trialPrice(sub: SubRecord, plans: List<Plan>): String =
    (plans.firstOrNull { it.basePlan == sub.basePlan } ?: plans.firstOrNull { !it.yearly })?.priceWords
        ?: if (sub.basePlan == ProductIds.YEARLY) "연 요금" else "월 요금"

/**
 * [MON-06] 전자상거래법 제13조 6항: from 7 days before a trial turns paid, the app asks on its own,
 * apart from the purchase, whether to go on. "계속 쓸게요" is kept on the phone and the server as the
 * consent; "해지할래요" opens Play's subscription page. Closed without an answer, it asks again the
 * next time the app opens, and on the last day as well.
 */
@Composable
fun TrialConsentPrompt(c: AppContainer) {
    val pro by c.proStore.state.collectAsStateWithLifecycle()
    var closed by rememberSaveable { mutableStateOf(false) }
    val sub = pro.sub ?: return
    val zone = ZoneId.systemDefault()
    val end = sub.trialEnd(zone)
    val stage = TrialConsent.stage(sub.play(), end, pro.consentAt, Instant.now())
    if (closed || (stage != TrialConsent.Stage.ASK && stage != TrialConsent.Stage.LAST_DAY)) return
    // [PERF-01] Asked only when the question shows, so Play's billing client is not built at every start.
    val plans = rememberPlans()
    val context = LocalContext.current
    val day = end.atZone(zone).toLocalDate()
    val price = trialPrice(sub, plans)
    val text = trialConsentText(day, price)
    val answer: (String) -> Unit = { a ->
        val now = Instant.now()
        c.proStore.answer(a, now)
        c.appScope.launch {
            runCatching { c.tierApi.takeIf { it.enabled }?.consent(c.companyStore.device, a, ProductIds.PRO, price, end, text) }
        }
        closed = true
    }
    AlertDialog(
        onDismissRequest = { closed = true },
        title = { Text(if (stage == TrialConsent.Stage.LAST_DAY) "내일 체험이 끝나요" else "무료 체험이 곧 끝나요") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Text("답은 이 휴대폰과 밤낮 서버에 기록해 둬요(전자상거래법).", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { answer("keep") }) { Text("계속 쓸게요") } },
        dismissButton = {
            TextButton(onClick = {
                answer("cancel")
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProductIds.manageUrl(context.packageName)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("해지할래요") }
        },
    )
}

/** [MON-10] What a user from before the 정식 출시 is told, once, about the months of 프로 they were given. */
fun launchThanksText(until: java.time.LocalDate): String =
    "정식 출시 전부터 밤낮을 써 주셔서 고마워요. 그래서 ${until.year}년 ${until.monthValue}월 ${until.dayOfMonth}일까지 프로를 무료로 열어 뒀어요. " +
        "결제 정보는 받지 않고, 그날이 지나면 저절로 무료로 돌아가요."

/** [MON-10] Shown once after the 정식 출시 switch, only to phones that had the app before it. */
@Composable
fun LaunchThanks(c: AppContainer) {
    val pro by c.proStore.state.collectAsStateWithLifecycle()
    val config by c.remoteConfig.config.collectAsStateWithLifecycle()
    if (!config.billingLive || pro.thanksSeen) return
    val zone = ZoneId.systemDefault()
    val until = com.bamnat.core.tier.ProAccess.thanksUntil(c.installedAt, config.launchedAt, config.thanksMonths, zone) ?: return
    if (!Instant.now().isBefore(until)) return
    AlertDialog(
        onDismissRequest = { c.proStore.thanksSeen() },
        title = { Text("고마워요") },
        text = { Text(launchThanksText(lastDay(until, zone)), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { c.proStore.thanksSeen() }) { Text("확인") } },
        dismissButton = { TextButton(onClick = { c.proStore.thanksSeen(); openPro() }) { Text("프로 둘러보기") } },
    )
}
