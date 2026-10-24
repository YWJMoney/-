package com.bamnat.app.ui.company

import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.company.AppliedSetup
import com.bamnat.app.company.CompanyHit
import com.bamnat.app.company.CompanyPage
import com.bamnat.app.company.SharedSetup
import com.bamnat.app.data.CompanyShare
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ShiftBadge
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.bamnat.core.time.Today

/**
 * [COMM-01] 회사 찾기, the first step of setting up work when a company server is there: a coworker's
 * setup in a few taps, or the way on to set it up by hand under the company's name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyFindScreen(
    vm: CompanyFindViewModel,
    setup: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onByHand: (String) -> Unit,
    /** Screenshots: no keyboard over the results. */
    focus: Boolean = true,
    /** The user's own setup is already in, so a company that is not here leads to sharing it. */
    ready: Boolean = false,
    /** [COMM-07] Every company's rules, to look through without a name. */
    onBoard: (() -> Unit)? = null,
) {
    // [STAB-01] Read at once, on the same frame as the key: a text box one frame behind the keyboard
    // doubles or drops half-built Korean letters and moves the cursor.
    val query by vm.query.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val result by vm.result.collectAsStateWithLifecycle()
    val terms by vm.terms.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (focus) LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val byHand = { name: String ->
        keyboard?.hide()
        vm.byHand(name)
        onByHand(name.trim())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (setup) "" else "회사 찾기") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = { if (setup) TextButton(onClick = { byHand("") }) { Text("건너뛰기") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HeroIcon(Icons.Outlined.Apartment)
                Text(if (setup) "다니는 회사를 찾아볼까요?" else "회사 이름으로 찾아요", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "같은 회사 분들이 맞춰 둔 근무표와 급여 계산 방식을 그대로 가져와요. 금액은 각자 넣어요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it.take(40) },
                    placeholder = { Text("회사 이름 (예: 예시정밀)") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, "지우기") }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                AnimatedContent(
                    targetState = result,
                    contentKey = { it::class },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "company-search",
                ) { r ->
                    when (r) {
                        CompanyFindViewModel.Result.Idle -> HowItWorks()
                        CompanyFindViewModel.Result.Searching -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("찾고 있어요…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is CompanyFindViewModel.Result.Found -> if (r.hits.isEmpty()) {
                            NotFound(r.query, rewardLine(terms), ready) { byHand(r.query) }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                r.hits.forEach { hit -> CompanyRow(hit) { keyboard?.hide(); onOpen(hit.id) } }
                                TextButton(onClick = { byHand(r.query) }) {
                                    Text(if (ready) "여기 없어요. ‘${r.query}’로 내 설정을 나눌게요" else "여기 없어요. ‘${r.query}’로 직접 설정할게요")
                                }
                            }
                        }
                        is CompanyFindViewModel.Result.Failed -> Offline(r.code, onRetry = vm::retry) { byHand(r.query) }
                    }
                }
                if (onBoard != null) {
                    OutlinedButton(onClick = { keyboard?.hide(); onBoard() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Forum, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("회사 규칙 광장에서 모든 회사 규칙 보기")
                    }
                }
                if (setup && result !is CompanyFindViewModel.Result.Found) {
                    TextButton(onClick = { byHand(query) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("회사 없이 직접 설정할게요")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun HowItWorks() {
    SoftCard {
        Column(Modifier.padding(16.dp)) {
            Text("이렇게 끝나요", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
            HowStep(1, "회사 찾기", "띄어쓰기나 (주)는 빼고 적어도 찾아요")
            HowStep(2, "오늘 근무 고르기", "같은 회사라도 조마다 순서가 달라서, 오늘이 어떤 근무인지만 골라요")
            HowStep(3, "내 기본급 넣기", "근무 시간, 연장·휴무일 근무, 가산 방식은 이미 들어 있어요")
        }
    }
}

@Composable
private fun CompanyRow(hit: CompanyHit, onClick: () -> Unit) {
    SoftCard(Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(hit.name.trim().removePrefix("(주)").trim().take(1), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(hit.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        hit.alias?.let { "다른 이름 $it" },
                        if (hit.adopted > 0) "채택된 설정 ${hit.adopted}개" else null,
                        if (hit.pending > 0) "확인 중 ${hit.pending}개" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun NotFound(query: String, reward: String?, ready: Boolean, onByHand: () -> Unit) {
    SoftCard(highlighted = true) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("아직 ‘$query’ 설정이 없어요", style = MaterialTheme.typography.titleMedium)
            Text(
                if (ready) "지금 쓰는 설정을 금액 없이 나눠 주시면, 같은 회사 분들은 다음부터 1분이면 끝나요. 처음 길을 내는 분이 되어 주세요!"
                else "직접 맞춘 뒤 나눠 주시면, 같은 회사 분들은 다음부터 1분이면 끝나요. 처음 길을 내는 분이 되어 주세요!",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            reward?.let { RewardPill(it) }
            Button(onClick = onByHand, modifier = Modifier.fillMaxWidth()) { Text(if (ready) "‘$query’로 내 설정 나누기" else "‘$query’ 설정 직접 하기") }
        }
    }
}

@Composable
private fun Offline(code: String, onRetry: () -> Unit, onByHand: () -> Unit) {
    SoftCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(if (code == "offline") "인터넷에 연결되지 않아 찾지 못했어요" else companyProblem(code),
                    style = MaterialTheme.typography.titleSmall)
            }
            Text("연결한 뒤 다시 찾거나, 지금은 직접 설정해도 돼요. 나중에 설정 > 회사 찾기에서 다시 찾을 수 있어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetry) { Text("다시 찾기") }
                TextButton(onClick = onByHand) { Text("직접 설정하기") }
            }
        }
    }
}

/**
 * [COMM-01] A company's setups, each in plain words with how many coworkers stand behind it, and
 * "이 설정 쓰기" from today's place in its rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyDetailScreen(
    vm: CompanyDetailViewModel,
    setup: Boolean,
    onBack: () -> Unit,
    onApplied: (CompanyShare.Applied, String) -> Unit,
    onByHand: (String) -> Unit,
    /** Screenshots only: open the rotation sheet for this setup. */
    openPhasesFor: String? = null,
    /** The user's own setup is already in: "우리 부서 설정이 없나요?" leads to sharing it. */
    ready: Boolean = false,
    /** [SETUP-01] In the first setup a setup is only chosen here; it is saved with all the other answers. */
    onPick: ((CompanyPage, SharedSetup) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val applied by vm.applied.collectAsStateWithLifecycle()
    val hasPattern by vm.hasPattern.collectAsStateWithLifecycle()
    val votes by vm.votes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf<Pair<SharedSetup, Phases>?>(null) }
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val page = (state as? CompanyDetailViewModel.State.Loaded)?.page

    fun use(p: CompanyPage, s: SharedSetup, step: Int?) {
        applying = true
        vm.apply(p, s, step, done = { r -> applying = false; choosing = null; onApplied(r, p.name) }, failed = { applying = false; error = it })
    }
    fun start(p: CompanyPage, s: SharedSetup) {
        if (onPick != null) return onPick(p, s)
        scope.launch {
            val phases = vm.phases(s)
            if (phases != null && phases.options.size > 1) choosing = s to phases else use(p, s, if (phases != null) 0 else null)
        }
    }
    if (openPhasesFor != null && page != null) {
        LaunchedEffect(page) { page.setups.firstOrNull { it.id == openPhasesFor }?.let { start(page, it) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (val s = state) {
                CompanyDetailViewModel.State.Loading -> CircularProgressIndicator(Modifier.padding(top = 64.dp))
                is CompanyDetailViewModel.State.Failed -> Column(
                    Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(companyProblem(s.code), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = vm::load) { Text("다시 불러오기") }
                }
                is CompanyDetailViewModel.State.Loaded -> Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val p = s.page
                    if (p.aliases.isNotEmpty()) {
                        Text("다른 이름: ${p.aliases.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("같은 회사 분들이 알려 준 정보예요. 회사가 낸 공식 정보는 아니어서, 첫 명세서와 한 번 맞춰 봐 주세요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (p.setups.isEmpty()) {
                        Text("아직 볼 수 있는 설정이 없어요.", style = MaterialTheme.typography.bodyLarge)
                    }
                    p.setups.forEach { su ->
                        SetupCard(
                            su, applied?.takeIf { it.companyId == p.id && it.setup == su.id }, onUse = { start(p, su) },
                            // [COMM-06][COMM-07] Anyone who knows the company can vote and talk it over, using it or not.
                            vote = votes[su.id] ?: applied?.takeIf { it.companyId == p.id && it.setup == su.id }?.vote,
                            onVote = { ok, why -> vm.vote(p, su, ok, why) { e -> error = e } },
                            onTalk = { vm.talk.show(p.id, su.id, "${p.name} · ${su.dept.ifEmpty { "회사 전체" }}") },
                            // [PLZ-10][PLZ-04] My own month with these rules, and how the rule got here.
                            onTry = if (su.hasPay) ({ vm.tryPay(su) }) else null,
                            onHistory = if (su.adopted && (su.version ?: 1) > 1) ({ vm.showHistory(p, su) }) else null,
                        )
                    }
                    SoftCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("우리 부서 설정이 없나요?", style = MaterialTheme.typography.titleSmall)
                            Text(if (ready) "지금 쓰는 설정을 나눠 주시면 같은 부서 분들이 다음부터 바로 써요." else "직접 맞춘 뒤 나눠 주시면 같은 부서 분들이 다음부터 바로 써요.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { vm.byHand(p); onByHand(p.name) }) { Text(if (ready) "우리 부서 설정 나누기" else "직접 설정하고 나누기") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            error?.let { msg ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).navigationBarsPadding()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = { error = null }) { Text("닫기") }
                    }
                }
            }
        }
    }

    TalkSheet(vm.talk)
    TrialSheet(vm.trial.collectAsStateWithLifecycle().value, onClose = vm::closeTrial,
        onVote = { ok -> page?.setups?.firstOrNull { it.id == vm.trial.value?.setup }?.let { su -> vm.vote(page, su, ok, if (ok) null else "wrong") { e -> error = e } } })
    HistorySheet(vm.history.collectAsStateWithLifecycle().value, onClose = vm::closeHistory)
    val pick = choosing
    if (pick != null && page != null) {
        PhaseSheet(
            phases = pick.second,
            replacing = hasPattern == true,
            busy = applying,
            onDismiss = { if (!applying) choosing = null },
            onPick = { step -> use(page, pick.first, step) },
        )
    }
}

@Composable
private fun SetupCard(
    s: SharedSetup,
    applied: AppliedSetup?,
    onUse: () -> Unit,
    vote: Boolean? = null,
    onVote: ((Boolean, String?) -> Unit)? = null,
    onTalk: (() -> Unit)? = null,
    onTry: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
) {
    var more by rememberSaveable(s.id) { mutableStateOf(false) }
    val main = s.lines.filter { (label, _) -> label == "근무" || label.startsWith("순서") || label == "연장·휴무일 근무" }
    val pay = s.lines - main.toSet()
    SoftCard(highlighted = applied != null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.dept.ifEmpty { "회사 전체" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (s.adopted) StatusChip("채택${s.version?.let { " v$it" } ?: ""}", Tone.GOOD) else StatusChip("확인 중", Tone.WAIT)
            }
            TrustLine(s.trust, s.agree, s.persons, s.bad, s.adopted)
            if (!s.adopted) PointsBar(s.points, s.threshold)
            // [PLZ-08] Who stands behind it, in the names they chose.
            WhoLine(s.senders, s.checked, s.uses, s.by, s.checkedLong)
            main.forEach { (label, text) -> SetupLine(label, text) }
            if (pay.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { more = !more }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (more) "급여 계산 방식 접기" else "급여 계산 방식 보기 (${pay.size}가지)", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Icon(if (more) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
                }
                AnimatedVisibility(more) { Column { pay.forEach { (label, text) -> SetupLine(label, text) } } }
            } else if (!s.hasPay) {
                Text("급여 계산 방식은 없어요. 근무표만 가져와요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (applied != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("지금 이 설정을 쓰고 있어요", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (s.adopted) Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) { Text(if (applied != null) "다시 넣기" else "이 설정 쓰기") }
            else FilledTonalButton(onClick = onUse, modifier = Modifier.fillMaxWidth()) { Text("확인 중이지만 써 보기") }
            if (onVote != null) {
                Text("다니는 회사라면 이 규칙이 맞는지 알려 주세요", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                VoteButtons(vote, onVote)
            }
            if (onTry != null || onHistory != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    onTry?.let { OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) { Text("내 월급으로 시험") } }
                    onHistory?.let { OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) { Text("바뀐 기록") } }
                }
            }
            if (onTalk != null) {
                TextButton(onClick = onTalk, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (s.posts > 0) "토론 ${s.posts}개 보기 · 고치자고 제안하기" else "토론 열기 · 고치자고 제안하기")
                }
            }
        }
    }
}

/**
 * "오늘은 어떤 근무예요?": the rotation as rows of the coming week, one per place today could be in
 * it. Coworkers on other teams share the order on other days, so only the user can say [COMM-01].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhaseSheet(phases: Phases, replacing: Boolean, busy: Boolean, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var picked by remember(phases) { mutableStateOf(phases.current ?: phases.sent) }
    val today = remember { Today.date() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("오늘은 어떤 근무예요?", style = MaterialTheme.typography.titleLarge)
            // [SETUP-02] A coworker on another 조 shares the order on other days: the sender's row is only a start.
            val sender = phases.team?.let { "보낸 분($it)" } ?: "보낸 분"
            Text(
                "${Fmt.dayTitle(today)} · ${phases.cycle}일 순서예요. " + when {
                    phases.current != null -> "지금 달력과 같은 줄을 골라 두었어요."
                    phases.sent != null -> "${sender}의 오늘 줄을 골라 두었어요. 조가 다르면 내 근무와 같은 줄로 바꿔 주세요."
                    else -> "다음 일주일이 내 근무와 같은 줄을 골라 주세요."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 8.dp, top = 4.dp)) {
                (0 until 7).forEach { d ->
                    val day = today.plusDays(d.toLong())
                    Text(if (d == 0) "오늘" else Fmt.dayOfWeekShort(day.dayOfWeek), style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (d == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (d == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            phases.options.forEach { o ->
                val selected = picked == o.step
                val scheme = MaterialTheme.colorScheme
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(if (selected) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerLow)
                        .border(if (selected) 2.dp else 0.dp, if (selected) scheme.primary else scheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                        .selectable(selected = selected, role = Role.RadioButton) { picked = o.step }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 10.dp))
                    o.days.forEach { t ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            ShiftBadge(t, size = 30.dp, text = t?.label ?: "휴", outlined = t == null || t.isOff)
                        }
                    }
                }
            }
            if (replacing) {
                Text("오늘부터 이 순서로 바뀌어요. 지난 날짜와 직접 바꾼 날은 그대로예요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { picked?.let(onPick) }, enabled = picked != null && !busy, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("이 순서로 쓸게요")
            }
        }
    }
}
