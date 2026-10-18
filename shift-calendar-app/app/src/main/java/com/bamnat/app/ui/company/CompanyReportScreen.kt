package com.bamnat.app.ui.company

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.company.ReportStatus
import com.bamnat.app.company.SentReport
import com.bamnat.app.data.CompanySummary
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import java.time.ZoneId

/** [COMM-02] What the report screen and the privacy policy say about what goes to the company server. */
internal const val COMPANY_SEND_NOTICE =
    "보내면 위 내용이 회사 찾기에 올라가요. 함께 가는 건 이 휴대폰에서 만든 무작위 번호뿐이라 누가 보냈는지 알 수 없어요. " +
        "‘내가 보낸 정보’에서 언제든 거두거나 모두 지울 수 있어요."

/**
 * [COMM-02] 내 설정 나누기: the user's setup without amounts goes to coworkers. What goes and what
 * does not is read from the very file that is sent, and how it becomes adopted is said before it goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyReportScreen(vm: CompanyReportViewModel, onBack: () -> Unit, onDone: () -> Unit, onOpenSent: () -> Unit) {
    val sending by vm.sending.collectAsStateWithLifecycle()
    val terms by vm.terms.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val fix = vm.start?.fix == true
    var company by rememberSaveable { mutableStateOf(vm.start?.company.orEmpty()) }
    var dept by rememberSaveable { mutableStateOf(vm.start?.dept.orEmpty()) }
    val payslipMonth = vm.payslipMonth
    var verified by rememberSaveable { mutableStateOf(payslipMonth != null) }
    var note by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (fix) "맞는 설정 알려 주기" else "내 설정 나누기") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(
                targetState = sending is CompanyReportViewModel.Sending.Sent,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "report-sent",
            ) { sent ->
                val s = sending
                if (sent && s is CompanyReportViewModel.Sending.Sent) {
                    SentPanel(s.status, onDone = onDone, onOpenSent = onOpenSent)
                } else {
                    Column(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        HeroIcon(Icons.Outlined.VolunteerActivism)
                        Text(if (fix) "정답을 알려 주셔서 고마워요" else "같은 회사 분들의 길잡이가 되어 주세요",
                            style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (fix) "고친 설정을 보내 주시면, 같은 회사 분들이 확인한 뒤 회사 찾기의 설정이 바로잡혀요."
                            else "내 근무표와 급여 계산 방식을 금액 없이 보내요. 같은 회사 분들이 확인하면 회사 찾기에 올라가요.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = company, onValueChange = { company = it.take(40) },
                            label = { Text("회사 이름") }, singleLine = true,
                            supportingText = { Text("띄어쓰기나 (주)는 달라도 같은 회사로 찾아요") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = dept, onValueChange = { dept = it.take(20) },
                            label = { Text("부서·조 (선택)") }, singleLine = true,
                            placeholder = { Text("예: 생산 2팀") },
                            supportingText = { Text("비워 두면 회사 전체 설정이 돼요") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        WhatGoes(preview)
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                .clickable(role = Role.Checkbox) { verified = !verified }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = verified, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("급여 명세서와 맞춰 봤어요", style = MaterialTheme.typography.bodyLarge)
                                Text((payslipMonth?.let { "${it.monthValue}월 명세서로 맞춰 본 기록이 있어요. " } ?: "") + "명세서로 확인한 설정은 2점이라 더 빨리 채택돼요",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedTextField(
                            value = note, onValueChange = { note = it.take(500) },
                            label = { Text("동료에게 한마디 (선택)") },
                            placeholder = { Text("예: 야간 가산은 22시부터예요") },
                            supportingText = { Text("금액·전화번호·메일 주소는 적지 말아 주세요") },
                            minLines = 2, modifier = Modifier.fillMaxWidth(),
                        )
                        HowAdopted(terms?.threshold ?: 3, rewardLine(terms))
                        Text(COMPANY_SEND_NOTICE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        (s as? CompanyReportViewModel.Sending.Failed)?.let { f ->
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                                Text(companyProblem(f.code, f.field), color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                            }
                        }
                        Button(
                            onClick = { vm.send(company, dept, verified, note) },
                            enabled = s != CompanyReportViewModel.Sending.Busy && company.trim().length >= 2,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            if (s == CompanyReportViewModel.Sending.Busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text(if (fix) "맞는 설정 보내기" else "보내기")
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** What goes, from the file that is sent, and what never does. */
@Composable
private fun WhatGoes(preview: CompanySummary?) {
    SoftCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("보내는 것", style = MaterialTheme.typography.titleSmall)
            if (preview == null) {
                Text("읽는 중…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (preview.shiftTypes.isNotEmpty()) Goes("근무 ${preview.shiftTypes.joinToString(" · ")}" + (preview.cycleDays?.let { " (${it}일 순서)" } ?: ""))
                if (preview.extraKinds.isNotEmpty()) Goes("연장·휴무일 근무 ${preview.extraKinds.joinToString(" · ")}")
                if (preview.hasRules) Goes("연장·야간·휴일 가산과 회사 규칙")
                if (preview.items.isNotEmpty()) Goes("수당 이름 ${preview.items.joinToString(" · ")} (금액 빼고)")
                if (!preview.hasRules) Goes("급여 계산 방식은 아직 없어요. 근무표만 가요")
            }
            Spacer(Modifier.height(6.dp))
            Text("보내지 않는 것", style = MaterialTheme.typography.titleSmall)
            NotGoes("기본급과 수당 금액")
            NotGoes("입사일·연차, 이름·연락처")
            NotGoes("날짜별 근무와 메모")
        }
    }
}

@Composable
private fun Goes(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NotGoes(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Block, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How a setup becomes adopted, with the reward, said before anyone sends [COMM-03]. */
@Composable
internal fun HowAdopted(threshold: Int, reward: String?) {
    SoftCard {
        Column(Modifier.padding(16.dp)) {
            Text("이렇게 채택돼요", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
            HowStep(1, "회사 찾기에 ‘확인 중’으로 올라가요", "같은 회사 분들은 바로 써 볼 수 있어요")
            HowStep(2, "같은 회사 분들이 확인해요", "같은 설정을 보내거나 ‘맞았어요’를 누를 때마다 점수가 쌓여요")
            HowStep(3, "${threshold}점이 되면 채택돼요", "두 사람 이상이 맞다고 해야 하고, 개발자가 한 번 더 볼 때도 있어요")
            reward?.let { RewardPill(it, Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun SentPanel(status: ReportStatus, onDone: () -> Unit, onOpenSent: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val (label, tone) = status.label()
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        androidx.compose.animation.AnimatedVisibility(shown, enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn()) {
            Text("🎉", style = MaterialTheme.typography.displayMedium)
        }
        Text(if (status.again) "이미 보낸 설정이에요" else "보냈어요! 고마워요", style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center)
        Text(
            if (status.again) "같은 휴대폰에서 같은 설정은 한 번만 세요. 지금 상태를 보여 드릴게요."
            else "${status.company}${status.dept.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""} 분들이 다음부터 훨씬 쉽게 설정해요.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        SoftCard(highlighted = true) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(label, tone)
                Text(status.detail(), style = MaterialTheme.typography.bodyMedium)
                if (status.status == "pending" && !status.review) PointsBar(status.points, status.threshold)
                if (status.rewardMonths > 0 && status.status == "pending") {
                    RewardPill(rewardText(status.rewardMonths))
                }
            }
        }
        Text("같은 회사 동료에게 밤낮을 알려 주면 더 빨리 채택돼요", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("확인") }
        OutlinedButton(onClick = onOpenSent, modifier = Modifier.fillMaxWidth()) { Text("내가 보낸 정보 보기") }
    }
}

/**
 * [COMM-02] 내가 보낸 정보: where each report stands, the answers to ways the app cannot set yet,
 * taking one back, and deleting everything this phone sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanySentScreen(vm: CompanyReportViewModel, onBack: () -> Unit, onShare: () -> Unit) {
    val sent by vm.sent.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    var takingBack by remember { mutableStateOf<SentReport?>(null) }
    var forgetting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("내가 보낸 정보") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (offline) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("인터넷에 연결되지 않아 마지막으로 본 상태예요", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::refresh) { Text("다시") }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                if (sent.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroIcon(Icons.Outlined.Inbox)
                        Text("아직 보낸 정보가 없어요", style = MaterialTheme.typography.titleMedium)
                        Text("내 설정을 나누면 같은 회사 분들이 1분 만에 설정해요.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Button(onClick = onShare) { Text("내 설정 나누기") }
                    }
                }
                sent.forEach { r ->
                    SentCard(r, statuses[r.id], statuses.containsKey(r.id), onTakeBack = { takingBack = r },
                        // Already gone from the server: only the line here is left to drop.
                        onDrop = { vm.takeBack(r.id) {} })
                }
                if (sent.isNotEmpty()) {
                    TextButton(onClick = { forgetting = true }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
                        Text("이 휴대폰이 보낸 정보 모두 지우기", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    takingBack?.let { r ->
        ConfirmDialog(
            title = "이 정보를 거둘까요?",
            message = if (r.ask) "개발자에게 보낸 내용이 지워져요." else "회사 찾기에서 빠지고, 받기로 한 보상도 없어져요. 같은 회사 분들이 쓰던 설정은 그대로예요.",
            confirmLabel = "거두기",
            destructive = true,
            onDismiss = { takingBack = null },
            onConfirm = {
                takingBack = null
                vm.takeBack(r.id) { ok -> message = if (ok) "거뒀어요" else "인터넷에 연결한 뒤 다시 해 주세요" }
            },
        )
    }
    if (forgetting) {
        ConfirmDialog(
            title = "보낸 정보를 모두 지울까요?",
            message = "보낸 설정, 맞았어요·달라요, 토론 글과 신고, 받기로 한 보상까지 서버에서 모두 지워요. 이 휴대폰에서 쓰는 설정은 그대로예요.",
            confirmLabel = "모두 지우기",
            destructive = true,
            onDismiss = { forgetting = false },
            onConfirm = {
                forgetting = false
                vm.forgetAll { ok -> message = if (ok) "모두 지웠어요" else "인터넷에 연결한 뒤 다시 해 주세요" }
            },
        )
    }
}

@Composable
private fun SentCard(r: SentReport, status: ReportStatus?, looked: Boolean, onTakeBack: () -> Unit, onDrop: () -> Unit) {
    SoftCard(highlighted = status?.status == "adopted" || status?.status == "answered") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.company + (r.dept.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleMedium)
                    Text(
                        (if (r.ask) "앱에 없는 방식 · " else "") + Fmt.shortFullDate(r.at.atZone(ZoneId.systemDefault()).toLocalDate()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    status != null -> status.label().let { (text, tone) -> StatusChip(text, tone) }
                    looked -> StatusChip("서버에 없어요", Tone.QUIET)
                }
            }
            when {
                status != null -> {
                    Text(status.detail(), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (status.status == "answered") FontWeight.Medium else FontWeight.Normal)
                    if (status.status == "pending" && !status.review) PointsBar(status.points, status.threshold)
                }
                looked -> Text("거뒀거나 모두 지운 정보예요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status != null && status.status !in setOf("withdrawn", "replaced")) {
                TextButton(onClick = onTakeBack, modifier = Modifier.align(Alignment.End)) { Text("거두기") }
            } else if (status == null && looked) {
                TextButton(onClick = onDrop, modifier = Modifier.align(Alignment.End)) { Text("목록에서 빼기") }
            }
        }
    }
}
