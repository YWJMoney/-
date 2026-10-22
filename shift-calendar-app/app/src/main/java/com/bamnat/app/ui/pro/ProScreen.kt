package com.bamnat.app.ui.pro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.ads.DayPassButton
import com.bamnat.app.appContainer
import com.bamnat.app.billing.BillingState
import com.bamnat.app.billing.Plan
import com.bamnat.app.billing.ProductIds
import com.bamnat.app.company.CompanyException
import com.bamnat.app.review.InAppReview.findActivity
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Gate
import com.bamnat.app.tier.ProState
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.tier.ProReason
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [MON-04] 프로's four pillars (수익화 확정 5-2), each with the [Feature]s it opens. [PROV-08] Since 1.20.0
 * every line is in the app; TierAppTest checks that every 프로 feature is on this screen.
 */
data class Pillar(val title: String, val lines: List<String>, val features: Set<Feature>)

val PRO_PILLARS = listOf(
    Pillar("월급을 증명해요",
        listOf("급여 명세서 사진으로 맞춰 보고 다른 곳 짚기", "회사 임금 규칙 글로 자동 채우기", "기간을 골라 값 한 번에 바꾸기, 연도별 인상 계산",
            "월·연 급여 리포트 PDF·CSV(어떻게 계산했는지까지)", "지난달·작년 같은 달과 비교, 1년 급여·근무 흐름",
            "못 쓴 연차가 돈으로 얼마인지(미사용 연차수당)"),
        setOf(Feature.PAYSLIP_MATCH, Feature.PAY_RULE_IMPORT, Feature.PAY_BULK_EDIT, Feature.PAY_REPORT, Feature.WORK_COMPARE, Feature.LEAVE_PAYOUT)),
    Pillar("근무를 어디서나",
        listOf("휴대폰 캘린더(삼성·구글 캘린더 앱)에 근무 자동 쓰기", "다른 캘린더 앱이 따라오는 구독 주소", "매일 자동 암호 백업(드라이브 같은 고른 파일에)",
            "달력마다 공유 링크 5개까지, 180일·1년", "근무표 PDF(한 달·1년, 인쇄용)"),
        setOf(Feature.DEVICE_CALENDAR, Feature.ICS_FEED, Feature.AUTO_BACKUP, Feature.MORE_LINKS, Feature.ROSTER_PDF)),
    Pillar("절대 안 놓치는 알람",
        listOf("끄기 미션(계산 문제·흔들기), 근무마다 다르게", "끈 뒤 ‘일어났나요?’ 묻고 답이 없으면 한 번 더 울림"),
        setOf(Feature.SURE_ALARM)),
    Pillar("더 넓게, 깔끔하게",
        listOf("돈 통계: 지난달 비교, 분류별 지출, 올해 흐름", "추가 달력 2개째부터", "광고 없음"),
        setOf(Feature.MONEY_STATS, Feature.MORE_CALENDARS, Feature.NO_ADS)),
)

/** [MON-04] 무료 vs 프로, row by row. Everything not listed is free. */
val PRO_COMPARE = listOf(
    Triple("근무표·패턴·빠른 입력·대근·연차", true, true),
    Triple("알람·점점 커지는 소리·알람 점검·위젯·워치", true, true),
    Triple("이번 달 예상 월급(수당·주휴·세금)", true, true),
    Triple("모든 조 보기, 1년 달력, 주 52시간 점검", true, true),
    Triple("가계부 기록·합계·받을 돈", true, true),
    Triple("근무표 링크 1개(90일까지), 캘린더 파일 내보내기", true, true),
    Triple("백업 파일 저장, 앱 잠금, 화면 꾸미기, 문의", true, true),
    Triple("명세서 대조, 규칙 자동 인식, 값 일괄 적용", false, true),
    Triple("급여 리포트 PDF·CSV, 지난달·작년 비교", false, true),
    Triple("휴대폰 캘린더 쓰기, 캘린더 구독 주소", false, true),
    Triple("매일 자동 백업, 링크 5개·1년, 근무표 PDF", false, true),
    Triple("끄기 미션, 일어났는지 확인", false, true),
    Triple("돈 통계(비교·분류·흐름)", false, true),
    Triple("추가 달력 2개째부터", false, true),
    Triple("광고 없음", false, true),
)

/**
 * [MON-05] What a subscription screen must say before anyone pays (구독 화면 규칙): price and how often,
 * when a trial turns paid and how much, cancelling in Play at any time, the 7-day withdrawal and its
 * limit for digital content, and where the terms are. Tested word for word in ProScreenTest.
 */
fun proDisclosures(plans: List<Plan>, today: LocalDate): List<String> {
    val month = plans.firstOrNull { !it.yearly }
    val year = plans.firstOrNull { it.yearly }
    val prices = listOfNotNull(month?.priceWords, year?.priceWords).joinToString(", ")
    val trialEnd = today.plusMonths(1)
    return listOfNotNull(
        "결제는 Google Play 계정으로 해요. " + (if (prices.isNotEmpty()) "$prices, " else "") + "월 요금제는 매달, 연 요금제는 매년 같은 날 자동으로 결제돼요.",
        if (plans.any { it.trial }) "1개월 무료 체험은 처음 구독하는 분께 한 번이에요. 오늘 시작하면 ${trialEnd.monthValue}월 ${trialEnd.dayOfMonth}일부터 " +
            "고른 요금제의 금액이 결제돼요. 끝나기 7일 전에 계속 쓸지 따로 여쭤봐요." else null,
        "해지는 언제든 Play 스토어 › 결제 및 정기 결제에서 해요. 해지해도 이미 낸 기간이 끝날 때까지 프로를 써요.",
        "결제한 날부터 7일 안에는 청약철회(환불)를 요청할 수 있어요. 이미 쓰기 시작한 디지털 콘텐츠는 법에 따라 철회가 제한될 수 있어요(전자상거래법 제17조 제2항). " +
            "Google Play 환불 요청이나 설정 › 문의하기로 접수해요.",
        "구독하면 이용약관과 개인정보처리방침에 동의하는 거예요.",
    )
}

const val TERMS_URL = "https://ywjmoney.github.io/-/terms/"
const val PRIVACY_URL = "https://ywjmoney.github.io/-/privacy/"

/**
 * [MON-04][MON-05][MON-08][MON-09] 설정 › 프로: why this phone has 프로 now, what 프로 adds next to
 * the free app, the plans with everything said before paying, a day by a rewarded ad, reward passes
 * with their recovery code, and the admin link. Before the 정식 출시 switch it says everything is free.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.appContainer
    val gate by c.featureGate.state.collectAsStateWithLifecycle()
    val pro by c.proStore.state.collectAsStateWithLifecycle()
    val config by c.remoteConfig.config.collectAsStateWithLifecycle()
    val plans by c.billing.plans.collectAsStateWithLifecycle()
    val billingState by c.billing.state.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()
    // [STAT-04] The first step of the 프로 funnel; only counted once 결제 is open.
    LaunchedEffect(Unit) { if (config.billingLive) c.telemetry.event(com.bamnat.core.stats.UsageEvent.PAYWALL_VIEW.key) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("프로") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusCard(gate, pro, zone)
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

                SectionCard(title = "프로가 더해 주는 것") {
                    PRO_PILLARS.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text(p.title, style = MaterialTheme.typography.titleSmall)
                        p.lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }

                SectionCard(title = "무료와 프로") {
                    Row(Modifier.fillMaxWidth()) {
                        Text("", Modifier.weight(1f))
                        Text("무료", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 48.dp))
                        Text("프로", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 48.dp))
                    }
                    PRO_COMPARE.forEach { (what, free, paid) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(what, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Mark(free)
                            Mark(paid)
                        }
                    }
                    Text("여기 없는 기능은 모두 무료예요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (!gate.live || gate.preview) {
                    SectionCard(title = "결제") {
                        Text(
                            if (gate.preview) "미리 보기라 결제는 되지 않아요."
                            else "지금은 정식 출시 전이라 모든 기능이 무료예요. 출시하는 날 앱에서 알려 드리고, 테스트에 참여한 분께는 프로 ${config.thanksMonths.coerceAtLeast(1)}개월을 드려요.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    PlansCard(c, gate, pro, plans, billingState, onMessage = { message = it })
                }

                DayPassButton(gate)

                if (c.tierApi.enabled) {
                    PassesCard(c, pro, gate, zone, onMessage = { message = it })
                    AdminCard(c, pro, onMessage = { message = it })
                }
            }
        }
    }
}

@Composable
private fun Mark(on: Boolean) {
    Box(Modifier.widthIn(min = 48.dp)) {
        Icon(if (on) Icons.Filled.Check else Icons.Filled.Remove, if (on) "있음" else "없음",
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun StatusCard(gate: Gate, pro: ProState, zone: ZoneId) {
    SectionCard(title = "지금") {
        Text(gate.access.reason.words, style = MaterialTheme.typography.titleMedium)
        gate.access.until?.let { until ->
            val day = lastDay(until, zone)
            val words = when (gate.access.reason) {
                ProReason.TRIAL -> "체험은 ${dateWords(day)}에 끝나요"
                ProReason.SUBSCRIPTION -> if (pro.sub?.autoRenew == true) "다음 결제일 ${dateWords(day)}" else "${dateWords(day)}까지 써요(해지했어요)"
                ProReason.GRACE -> "${dateWords(day)}까지 결제 수단을 바꾸면 그대로 써요"
                ProReason.DAY_PASS -> "오늘 밤 12시까지예요"
                else -> "${dateWords(day)}까지예요"
            }
            Text(words, style = MaterialTheme.typography.bodyMedium)
        }
        pro.passes?.waitingMonths?.takeIf { it > 0 }?.let {
            Text("받은 보상 이용권 ${it}개월은 정식 출시 날부터 시작돼요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PlansCard(c: AppContainer, gate: Gate, pro: ProState, plans: List<Plan>, state: BillingState, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subscribed = pro.sub?.play()?.entitled(Instant.now()) == true
    SectionCard(title = if (subscribed) "구독" else "프로 시작하기") {
        when {
            subscribed -> Text("구독 중이에요. 요금제 바꾸기와 해지는 Play 스토어에서 해요.", style = MaterialTheme.typography.bodyMedium)
            plans.isEmpty() -> Text(
                if (state == BillingState.UNAVAILABLE) "이 휴대폰에서 Play 결제를 쓸 수 없어요. Play 스토어에 로그인했는지 확인해 주세요."
                else "Play에서 요금제를 불러오는 중이에요. 인터넷을 켜고 잠시 뒤 다시 열어 주세요.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> plans.forEach { plan ->
                // [MON-05] Each plan its own button; none is picked for the user.
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(plan.priceWords + if (plan.yearly) " (1년마다)" else " (매달)", style = MaterialTheme.typography.titleSmall)
                    if (plan.trial) Text("첫 1개월 무료, 그 뒤 ${plan.priceWords}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        val activity = context.findActivity()
                        if (activity == null || !c.billing.buy(activity, plan)) onMessage("Play 결제 화면을 열지 못했어요. 잠시 뒤 다시 해 주세요.")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (plan.trial) "1개월 무료로 시작 (${if (plan.yearly) "연" else "월"} 요금제)" else "${plan.priceWords}로 구독")
                    }
                }
            }
        }
        proDisclosures(plans, LocalDate.now()).forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open(context, ProductIds.manageUrl(context.packageName)) }) { Text("Play에서 구독 관리·해지") }
            OutlinedButton(onClick = {
                scope.launch {
                    c.billing.refresh()
                    c.featureGate.refresh()
                    onMessage(if (c.proStore.current.sub != null) "구독을 다시 찾았어요" else "이 Google 계정에서 찾은 구독이 없어요")
                }
            }) { Text("구매 복원") }
            TextButton(onClick = { open(context, TERMS_URL) }) { Text("이용약관") }
            TextButton(onClick = { open(context, PRIVACY_URL) }) { Text("개인정보처리방침") }
        }
    }
}

/** [MON-08] Reward passes and the recovery code that moves them to a new phone. */
@Composable
private fun PassesCard(c: AppContainer, pro: ProState, gate: Gate, zone: ZoneId, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entering by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    val passes = pro.passes
    SectionCard(title = "보상 이용권") {
        Text("회사 근태·급여 방식이 채택되거나 제안이 반영되면 프로 이용권을 드려요. 받을수록 기간이 이어져요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        passes?.items?.forEach { item ->
            Text("• 프로 ${item.months}개월 (${if (item.why == "rule") "회사 방식 채택" else "제안 반영"}, ${dateWords(item.at.atZone(zone).toLocalDate())})",
                style = MaterialTheme.typography.bodyMedium)
        }
        passes?.until?.let { Text("이용권은 ${dateWords(it.atZone(zone).toLocalDate())}까지예요.", style = MaterialTheme.typography.bodyMedium) }
        if (passes?.deferred?.let { it > 0 } == true) Text("구독 중이라 ${passes.deferred}번은 Play 다음 결제일을 미뤄 드렸어요.", style = MaterialTheme.typography.bodySmall)
        pro.code?.let { saved ->
            Text("복구 코드", style = MaterialTheme.typography.labelLarge)
            Text(saved, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            Text("휴대폰을 바꾸면 새 휴대폰에서 이 코드를 넣거나 백업을 불러오면 이용권이 옮겨 가요. 한 번 옮기면 이 휴대폰의 이용권은 끝나요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (passes?.items?.isNotEmpty() == true) {
                OutlinedButton(onClick = {
                    scope.launch {
                        runServer(onMessage) {
                            val made = c.tierApi.newCode(c.companyStore.device)
                            c.proStore.setCode(made)
                            copy(context, made)
                            onMessage("복구 코드를 만들어 복사했어요. 백업 파일에도 들어가요.")
                        }
                    }
                }) { Text(if (pro.code == null) "복구 코드 만들기" else "새 복구 코드") }
            }
            TextButton(onClick = { entering = !entering }) { Text("다른 휴대폰의 복구 코드 넣기") }
        }
        if (entering) {
            OutlinedTextField(code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(24) },
                label = { Text("복구 코드") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                scope.launch {
                    runServer(onMessage) {
                        c.proStore.setPasses(c.tierApi.claim(c.companyStore.device, code))
                        c.featureGate.refresh()
                        entering = false
                        code = ""
                        onMessage("이용권을 이 휴대폰으로 옮겼어요.")
                    }
                }
            }, enabled = code.count { it.isLetterOrDigit() } >= 16) { Text("옮겨 오기") }
        }
    }
}

/** [MON-09] An invited admin's phone, tied with the 6-digit code from the developer app. */
@Composable
private fun AdminCard(c: AppContainer, pro: ProState, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    val admin = pro.passes?.admin
    SectionCard(title = "관리자 연결") {
        when {
            admin?.approved == true -> Text("${admin.name} 관리자 휴대폰이에요. 프로를 무료로 써요.", style = MaterialTheme.typography.bodyMedium)
            admin != null -> Text("${admin.name} 관리자 휴대폰으로 승인을 기다리고 있어요.", style = MaterialTheme.typography.bodyMedium)
            else -> Text("밤낮 관리자라면 관리자 앱 › 내 정보 › ‘내 휴대폰 밤낮 연결’의 6자리 코드를 넣어 주세요. 최고 관리자가 승인하면 프로를 무료로 써요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (admin == null) {
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6자리 코드") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                scope.launch {
                    runServer(onMessage) {
                        val state = c.tierApi.link(c.companyStore.device, code)
                        c.syncPasses(force = true)
                        c.featureGate.refresh()
                        code = ""
                        onMessage(if (state.approved) "연결했어요. 프로를 무료로 써요." else "연결했어요. 최고 관리자가 승인하면 프로가 열려요.")
                    }
                }
            }, enabled = code.length == 6) { Text("연결하기") }
        } else {
            TextButton(onClick = {
                scope.launch {
                    runServer(onMessage) {
                        c.tierApi.unlink(c.companyStore.device)
                        c.syncPasses(force = true)
                        c.featureGate.refresh()
                        onMessage("관리자 연결을 끊었어요.")
                    }
                }
            }) { Text("연결 끊기") }
        }
    }
}

private suspend fun runServer(onMessage: (String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: CompanyException) {
        onMessage(serverProblem(e.code))
    } catch (e: Exception) {
        onMessage(serverProblem("bad_response"))
    }
}

/** What to tell the user about a refusal from the company server here. */
fun serverProblem(code: String): String = when (code) {
    "code" -> "코드가 맞지 않거나 시간이 지났어요. 새 코드로 다시 해 주세요."
    "too_many" -> "이 관리자에게는 휴대폰을 2대까지 연결할 수 있어요. 관리자 앱에서 하나를 끊어 주세요."
    "rate_limited" -> "잠시 뒤 다시 해 주세요."
    "offline" -> "인터넷에 연결되지 않았어요."
    else -> "지금은 처리하지 못했어요. 잠시 뒤 다시 해 주세요."
}

fun dateWords(d: LocalDate): String = "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"

/** The last day an end time still counts: an end at midnight belongs to the day before. */
fun lastDay(end: Instant, zone: ZoneId): LocalDate {
    val at = end.atZone(zone)
    return if (at.toLocalTime() == java.time.LocalTime.MIDNIGHT) at.toLocalDate().minusDays(1) else at.toLocalDate()
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copy(context: Context, text: String) {
    runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("복구 코드", text)) }
}
