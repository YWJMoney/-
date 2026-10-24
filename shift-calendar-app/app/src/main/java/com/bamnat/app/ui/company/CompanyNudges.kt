package com.bamnat.app.ui.company

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.company.AppliedSetup
import com.bamnat.app.company.CompanyDraft
import kotlinx.coroutines.launch

/** Where to correct a setup the user said was wrong [COMM-03]. */
enum class FixPlace { PAY, SHIFT_TYPES, PATTERN, NONE }

/** What was wrong, as the user says it, and what the server is told: wrong, old or name. */
private enum class Wrong(val label: String, val why: String, val place: FixPlace) {
    PAY("급여 계산", "wrong", FixPlace.PAY),
    TIMES("근무 시간", "wrong", FixPlace.SHIFT_TYPES),
    ORDER("근무 순서", "wrong", FixPlace.PATTERN),
    OLD("예전 정보예요", "old", FixPlace.PAY),
    NAME("회사·부서가 달라요", "name", FixPlace.NONE),
}

/** [EXTRA-06][COMM-04] What the developer gets with a way the app cannot set yet; the privacy policy says the same. */
internal const val ASK_NOTICE =
    "앱 안 문의로 개발자에게만 가고, 모두의 문의에는 올라가지 않아요. 회사 이름과 적은 내용, 앱·기기 정보가 가요. " +
        "기본급·이름·연락처는 적지 마세요. 답은 설정 > 도움말과 문의 > 내 문의에서 볼 수 있어요."

/**
 * [COMM-02][COMM-03] The cards around a company setup, on the pay screen and in settings: the
 * invitation to share a hand-made or corrected setup, else 맞았어요·달라요 on the one in use.
 * [onShare] opens 내 설정 나누기, [onFix] the screen where the setup is corrected.
 */
@Composable
fun CompanyNudgeCards(vm: CompanyNudgeViewModel, onShare: () -> Unit, onFix: (FixPlace) -> Unit) {
    if (!vm.enabled) return
    val invite by vm.invite.collectAsStateWithLifecycle()
    val askVote by vm.askVote.collectAsStateWithLifecycle()
    val terms by vm.terms.collectAsStateWithLifecycle()
    var thanked by rememberSaveable { mutableStateOf<String?>(null) }
    // The sheets outlive the card that opened them: once 달라요 is sent the card turns into the invitation.
    var sheet by remember { mutableStateOf<Pair<AppliedSetup, Boolean>?>(null) }
    val draft = invite
    val asked = askVote
    when {
        draft != null -> InviteCard(draft, rewardShort(terms), onShare = onShare, onLater = vm::closeInvite)
        asked != null -> VoteCard(asked, onRight = { sheet = asked to true }, onWrong = { sheet = asked to false }, onLater = vm::later)
        thanked != null -> SoftCard(highlighted = true) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(thanked.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { thanked = null }) { Icon(Icons.Filled.Close, "닫기") }
            }
        }
    }
    when (val open = sheet) {
        null -> Unit
        else -> if (open.second) {
            RightSheet(open.first, onDismiss = { sheet = null }) { verified, done ->
                vm.vote(true, verified) { ok ->
                    done(ok)
                    if (ok) {
                        sheet = null
                        thanked = "고마워요! ${open.first.company} 분들에게 큰 힘이 돼요 💪"
                    }
                }
            }
        } else {
            WrongSheet(vm, open.first, onDismiss = { sheet = null }, onShare = { sheet = null; onShare() }, onFix = { sheet = null; onFix(it) })
        }
    }
}

@Composable
private fun InviteCard(draft: CompanyDraft, reward: String?, onShare: () -> Unit, onLater: () -> Unit) {
    SoftCard(highlighted = true) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (draft.fix) "고친 설정, 알려 주실래요? 🙏" else "${draft.company}의 첫 번째 길잡이가 되어 주실래요? 🧭",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(top = 8.dp),
                )
                IconButton(onClick = onLater) { Icon(Icons.Filled.Close, "나중에") }
            }
            Text(
                if (draft.fix) "맞게 고친 설정을 보내 주시면 ${draft.company} 설정이 바로잡혀요. 금액은 보내지 않아요."
                else "직접 맞춘 설정을 금액 없이 나눠 주시면, 같은 회사 분들은 1분이면 설정이 끝나요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            ) {
                reward?.let { RewardPill(it, Modifier.weight(1f, fill = false).padding(end = 8.dp)) }
                Button(onClick = onShare) { Text(if (draft.fix) "보내기" else "나눠 볼게요") }
            }
        }
    }
}

@Composable
private fun VoteCard(a: AppliedSetup, onRight: () -> Unit, onWrong: () -> Unit, onLater: () -> Unit) {
    SoftCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${a.company}${a.dept.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""} 설정, 맞았나요?",
                style = MaterialTheme.typography.titleMedium)
            Text("같은 회사 분들이 알려 준 설정이에요. 첫 명세서와 맞춰 보고 알려 주시면 더 정확해져요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onRight) { Text("맞았어요 👍") }
                OutlinedButton(onClick = onWrong) { Text("달라요") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onLater) { Text("나중에") }
            }
        }
    }
}

/** 맞았어요: checked against a payslip counts double [COMM-03]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RightSheet(a: AppliedSetup, onDismiss: () -> Unit, onVote: (Boolean, (Boolean) -> Unit) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = busySheetState(busy)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("좋아요! 무엇과 맞춰 보셨어요?", style = MaterialTheme.typography.titleLarge)
            Text("${a.company} 분들이 이 설정을 믿고 쓸 수 있게 점수로 쌓여요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val send = { verified: Boolean ->
                busy = true
                failed = false
                onVote(verified) { ok -> busy = false; failed = !ok }
            }
            ChoiceRow("급여 명세서와 맞춰 봤어요", "2점 · 가장 믿을 수 있어요", enabled = !busy) { send(true) }
            ChoiceRow("근무표와 대충 맞아요", "1점", enabled = !busy) { send(false) }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp).align(Alignment.CenterHorizontally), strokeWidth = 2.dp)
            if (failed) Text(companyProblem("offline"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 달라요, then the plea [COMM-03]: whoever noticed it is wrong is the closest to the right answer,
 * so they are asked, warmly and with a wink, to correct it and send it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WrongSheet(vm: CompanyNudgeViewModel, a: AppliedSetup, onDismiss: () -> Unit, onShare: () -> Unit, onFix: (FixPlace) -> Unit) {
    val terms by vm.terms.collectAsStateWithLifecycle()
    var wrong by remember { mutableStateOf<Wrong?>(null) }
    var told by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = busySheetState(busy)) {
        AnimatedContent(told, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "wrong-plea") { plea ->
            Column(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!plea) {
                    Text("어디가 달랐나요?", style = MaterialTheme.typography.titleLarge)
                    Text("하나만 골라 주세요. 같은 회사 분들에게 ‘조심’ 표시로 보여요.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Wrong.entries.forEach { w -> FilterChip(selected = wrong == w, onClick = { wrong = w }, label = { Text(w.label) }) }
                    }
                    if (failed) Text(companyProblem("offline"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = {
                            val w = wrong ?: return@Button
                            busy = true
                            failed = false
                            vm.vote(false, why = w.why) { ok ->
                                busy = false
                                if (ok) { vm.startFix(); told = true } else failed = true
                            }
                        },
                        enabled = wrong != null && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("알려 주기") }
                } else {
                    val w = wrong ?: Wrong.PAY
                    Text("👀", style = MaterialTheme.typography.displaySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("제보 고마워요! 그런데… 혹시 정답도 아세요?", style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text(
                        when (w) {
                            Wrong.NAME -> "회사나 부서가 다르다면, 내 회사 이름으로 설정을 나눠 주세요. 같은 회사 분들이 헤매지 않고 찾아올 수 있게요."
                            Wrong.OLD -> "회사 방식이 바뀐 걸 알아챈 분이 정답에 제일 가까워요. 지금 방식으로 고쳐서 알려 주시면 다음 동료는 헤매지 않아요."
                            else -> "‘다르다’는 걸 알아챈 분이 정답에 제일 가까워요. 맞게 고친 설정을 보내 주시면 다음 동료는 헤매지 않아요."
                        },
                        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Text("지금 ${a.company} 설정을 바로잡을 수 있는 사람, 바로 당신이에요 😉", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    rewardLine(terms)?.let { RewardPill(it, Modifier.align(Alignment.CenterHorizontally)) }
                    if (w.place != FixPlace.NONE) {
                        Button(onClick = { onFix(w.place) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(when (w.place) {
                                FixPlace.SHIFT_TYPES -> "근무 시간 고치러 가기"
                                FixPlace.PATTERN -> "근무 순서 고치러 가기"
                                else -> "급여 설정 고치러 가기"
                            })
                        }
                        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("이미 고쳤어요, 바로 보낼게요") }
                    } else {
                        Button(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("내 회사로 나누기") }
                    }
                    TextButton(onClick = { asking = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("앱으로는 맞출 수 없는 방식이에요")
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("다음에 할게요") }
                }
            }
        }
    }
    if (asking) {
        AskDeveloperDialog(a.company, onDismiss = { asking = false }, onSent = { asking = false; onDismiss() })
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    SoftCard(Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * The invitation right after a pay setup made by hand under a company's name [COMM-02]: once,
 * when the work is fresh in mind. "나중에" leaves the card on the pay screen and in settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteSheet(vm: CompanyNudgeViewModel, onShare: () -> Unit, onDismiss: () -> Unit) {
    val draft by vm.invite.collectAsStateWithLifecycle()
    val terms by vm.terms.collectAsStateWithLifecycle()
    val d = draft ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🧭", style = MaterialTheme.typography.displaySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("설정 끝! ${d.company}의 첫 번째 길잡이가 되어 주실래요?", style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("방금 맞춘 근무표와 계산 방식을 금액 없이 나눠 주시면, 같은 회사 분들은 회사 이름만 찾으면 1분이면 끝나요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            HowAdopted(terms?.threshold ?: 3, rewardLine(terms))
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("나눠 볼게요") }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("나중에") }
        }
    }
}

/**
 * [EXTRA-06][COMM-04] A company way the app cannot set yet, in words and an example, straight to
 * the developer as a question in the app [INQ-01]. The answer comes back in 설정 > 문의하기. [setup] is
 * the settings that go with it, shown before sending.
 */
@Composable
fun AskDeveloperDialog(
    company: String,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
    setup: String? = null,
    /** [PAYS-09] Words to start from, e.g. the company's own items without amounts; the user reads and changes them first. */
    prefill: String = "",
) {
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(company) }
    var how by rememberSaveable { mutableStateOf(prefill.take(800)) }
    // [INPUT-03] The rules put together by taps; sent as words before what the user writes.
    var built by remember { mutableStateOf(emptyList<String>()) }
    var example by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (sent) "보냈어요! 💌" else "회사 방식 알려 주기") },
        text = {
            if (sent) {
                Text("개발자가 직접 읽고 답을 드려요. 답은 설정 > 도움말과 문의 > 내 문의에서 볼 수 있어요.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("회사 계산 방식을 골라 담고, 담기지 않는 것만 말로 적어 주세요. 개발자가 직접 읽고 앱에 넣을 수 있는지 답을 드려요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("회사 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    com.bamnat.app.ui.components.RuleBuilder(built, { built = it })
                    OutlinedTextField(how, { how = it.take(800) }, label = { Text(if (built.isEmpty()) "회사는 어떻게 계산하나요?" else "더 적을 말 (선택)") },
                        placeholder = { Text("예: 명절 근무는 따로 정해요") },
                        minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(example, { example = it.take(400) }, label = { Text("예시 (선택)") },
                        placeholder = { Text("예: 근무 8시간 뒤 야간 4시간 → 기본급의 10%") },
                        minLines = 2, modifier = Modifier.fillMaxWidth())
                    if (setup != null) {
                        TextButton(onClick = { showSetup = !showSetup }) {
                            Text(if (showSetup) "함께 보내는 설정 접기" else "함께 보내는 설정 보기 (금액 없이)")
                        }
                        if (showSetup) {
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text(setup, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(10.dp))
                            }
                        }
                    }
                    Text(ASK_NOTICE, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (sent) {
                Button(onClick = onSent) { Text("확인") }
            } else {
                Button(onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        error = container.askDeveloper(name, com.bamnat.app.ui.components.ruleWords(built, how), example, setup)
                        busy = false
                        if (error == null) sent = true
                    }
                }, enabled = !busy && (how.isNotBlank() || built.isNotEmpty()) && name.trim().length >= 2) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("개발자에게 보내기")
                }
            }
        },
        dismissButton = { if (!sent) TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } },
    )
}

/** [COMM-01][COMM-02] The company part of settings when the server is there: what is in use and the ways in. */
@Composable
fun ColumnScope.CompanySettingsRows(
    vm: CompanyNudgeViewModel,
    onFind: () -> Unit,
    onShare: () -> Unit,
    onSent: () -> Unit,
    onFix: (FixPlace) -> Unit,
    onBoard: () -> Unit = {},
) {
    val applied by vm.applied.collectAsStateWithLifecycle()
    applied?.let { a ->
        SoftCard(highlighted = true, modifier = Modifier.padding(vertical = 4.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("지금 쓰는 회사 설정", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.company + " · " + a.dept.ifEmpty { "회사 전체" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (a.adopted) StatusChip("채택${a.version?.let { " v$it" } ?: ""}", Tone.GOOD) else StatusChip("확인 중", Tone.WAIT)
                }
                when (a.vote) {
                    true -> Text("‘맞았어요’라고 알려 주셨어요. 고마워요!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    false -> Text("‘달라요’라고 알려 주셨어요. 고친 설정을 나눠 주시면 바로잡혀요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    null -> Unit
                }
            }
        }
    }
    CompanyNudgeCards(vm, onShare = onShare, onFix = onFix)
    com.bamnat.app.ui.settings.NavRow("회사 찾기", "같은 회사 분들이 맞춘 근무표와 계산 방식을 가져와요", onFind)
    // [COMM-07]
    com.bamnat.app.ui.settings.NavRow("회사 규칙 광장", "여러 회사가 올린 근무표·급여 규칙을 보고, 맞는지 투표하고 토론해요", onBoard)
    com.bamnat.app.ui.settings.NavRow("내 설정 나누기", "금액 없이 보내요. 채택되면 보상을 드려요", onShare)
    com.bamnat.app.ui.settings.NavRow("내가 보낸 정보", "보낸 설정이 어떻게 됐는지, 개발자의 답, 거두기", onSent)
}

/**
 * A sheet that cannot be swiped away while [busy]: a swipe while sending would leave its scrim catching
 * taps until the answer comes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun busySheetState(busy: Boolean): SheetState {
    val sending by rememberUpdatedState(busy)
    return rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !sending })
}
