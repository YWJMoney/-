package com.bamnat.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.company.Inquiry
import com.bamnat.app.company.InquiryClient
import com.bamnat.app.company.InquiryFlag
import com.bamnat.app.company.InquiryKind
import com.bamnat.app.company.PublicInquiry
import com.bamnat.app.company.PublicThread
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.company.HeroIcon
import com.bamnat.app.ui.company.SoftCard
import com.bamnat.app.ui.company.StatusChip
import com.bamnat.app.ui.company.Tone
import com.bamnat.app.ui.components.ConfirmDialog
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** [ADMIN-02] What the form and the privacy policy say about a question before it goes. */
internal const val INQUIRY_SEND_NOTICE =
    "모두의 문의에 올라가요. 앱을 쓰는 다른 분도 적은 글과 개발자 답을 볼 수 있고, 보낸 분이 누구인지는 보이지 않아요. " +
        "3번의 ‘함께 보내는 정보’는 개발자만 봐요. 이름, 메일 주소, 전화번호, 금액은 적지 말아 주세요. " +
        "보낸 문의는 언제든 지울 수 있고, 180일 동안 새 글이 없으면 저절로 지워져요."

private const val LIST = "list"
private const val NEW = "new"
// [ADMIN-02] 모두의 문의, and one public question by "pub:" and its id.
private const val BOARD = "board"
private const val PUB = "pub:"

/**
 * [ADMIN-02] 설정 > 문의하기: questions sent from this phone with the developer's answers, a new
 * question in three steps (what about, what happened, what goes with it) and a thread to ask more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InquiryScreen(vm: InquiryViewModel, onBack: () -> Unit) {
    val list by vm.list.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    // The list, the form, or one question by its id. 계산이 이상해요 opens straight on the form [ADMIN-02].
    var view by rememberSaveable { mutableStateOf(if (vm.payCheck != null) NEW else if (vm.startOnBoard) BOARD else LIST) }
    // Until that question is sent, back returns to the pay screen it came from.
    var fromPay by rememberSaveable { mutableStateOf(vm.payCheck != null) }
    var deleting by remember { mutableStateOf<Inquiry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.loadBoard() }
    val open = list?.firstOrNull { it.id == view }
    val publicId = view.removePrefix(PUB).takeIf { view.startsWith(PUB) }
    val home = view == LIST || view == BOARD
    // Back from a public question returns to 모두의 문의, from anything else to 내 문의.
    var lastHome by rememberSaveable { mutableStateOf(if (view == BOARD) BOARD else LIST) }
    LaunchedEffect(view) { if (home) lastHome = view }
    LaunchedEffect(open?.id, open?.answers) { open?.let(vm::opened) }
    // A question that is gone (deleted elsewhere) falls back to the list.
    LaunchedEffect(list, view) { if (!home && view != NEW && publicId == null && list != null && open == null) view = LIST }

    val leaves = home || (view == NEW && fromPay)
    val back = { if (leaves) onBack() else { view = if (publicId != null) BOARD else lastHome } }
    BackHandler(enabled = !leaves) { back() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (view == NEW) "새 문의" else if (open != null) open.kind.label else if (publicId != null) "모두의 문의" else "문의하기") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    if (open != null) IconButton(onClick = { deleting = open }) { Icon(Icons.Outlined.Delete, "이 문의 지우기") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                if (home) {
                    PrimaryTabRow(selectedTabIndex = if (view == BOARD) 0 else 1) {
                        Tab(selected = view == BOARD, onClick = { message = null; view = BOARD }, text = { Text("모두의 문의") })
                        Tab(selected = view == LIST, onClick = { message = null; view = LIST }, text = { Text("내 문의") })
                    }
                }
                if (offline && view == LIST) OfflineLine(vm::refresh)
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                when {
                    view == NEW -> InquiryForm(vm, pay = vm.payCheck.takeIf { fromPay },
                        onSent = { sent -> message = null; fromPay = false; view = sent.id })
                    view == BOARD -> PublicBoard(vm, onNew = { message = null; view = NEW }, onOpen = { message = null; view = PUB + it.id })
                    publicId != null -> PublicThreadView(vm, publicId, onGone = { message = "이 문의는 지워졌거나 가려졌어요"; view = BOARD })
                    open != null -> InquiryThread(vm, open)
                    else -> InquiryList(vm, list, onNew = { message = null; view = NEW }, onOpen = { message = null; view = it.id })
                }
            }
        }
    }

    deleting?.let { i ->
        ConfirmDialog(
            title = "이 문의를 지울까요?",
            message = "보낸 글과 개발자 답이 서버에서 모두 지워져요.",
            confirmLabel = "지우기",
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                vm.delete(i.id) { ok ->
                    message = if (ok) "지웠어요" else "인터넷에 연결한 뒤 다시 해 주세요"
                    if (ok) view = LIST
                }
            },
        )
    }
}

@Composable
private fun OfflineLine(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("인터넷에 연결되지 않아 새 답을 확인하지 못했어요", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("다시") }
    }
}

@Composable
private fun InquiryList(vm: InquiryViewModel, list: List<Inquiry>?, onNew: () -> Unit, onOpen: (Inquiry) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            InquiryItems(vm, list, onNew, onOpen)
            MailNotice()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InquiryItems(vm: InquiryViewModel, list: List<Inquiry>, onNew: () -> Unit, onOpen: (Inquiry) -> Unit) {
    if (list.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroIcon(Icons.Outlined.QuestionAnswer)
            Text("궁금한 점이나 이상한 점을 알려 주세요", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text("개발자가 직접 읽고 답해요. 답은 이 화면에 오고, 모두의 문의에도 함께 올라가요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
    Button(onClick = onNew, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("새 문의 쓰기") }
    list.forEach { i -> InquiryCard(i, unread = vm.unreadIn(i), onClick = { onOpen(i) }) }
}

@Composable
private fun InquiryCard(i: Inquiry, unread: Boolean, onClick: () -> Unit) {
    SoftCard(Modifier.clickable(onClick = onClick), highlighted = unread) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(i.kind.label + " · " + stampOf(i.updatedAt), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                i.label(unread).let { (text, tone) -> StatusChip(text, tone) }
            }
            Text(i.messages.firstOrNull()?.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            i.messages.lastOrNull { it.fromDeveloper }?.let {
                Text("개발자: " + it.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Where a question stands, in a few words and a tone. */
private fun Inquiry.label(unread: Boolean): Pair<String, Tone> = when {
    unread -> "새 답이 왔어요" to Tone.GOOD
    status == "answered" -> "답했어요" to Tone.GOOD
    status == "closed" -> "끝났어요" to Tone.QUIET
    else -> "답을 기다려요" to Tone.WAIT
}

private val stamp = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

/** "10월 18일 21:05" in this phone's time zone. */
private fun stampOf(at: Instant): String = at.atZone(ZoneId.systemDefault()).format(stamp)

@Composable
private fun InquiryForm(vm: InquiryViewModel, pay: String?, onSent: (Inquiry) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by vm.busy.collectAsStateWithLifecycle()
    // From 계산이 이상해요 it is a 오류 제보 already, and the pay check goes with it unless unticked.
    var kind by rememberSaveable { mutableStateOf(if (pay != null) InquiryKind.BUG else null) }
    var withPay by rememberSaveable { mutableStateOf(pay != null) }
    var showPay by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var withCheck by rememberSaveable { mutableStateOf(false) }
    var showCheck by rememberSaveable { mutableStateOf(false) }
    var check by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = vm.problem(text)
    // The alarm check helps most when the app misbehaves, so it is offered ticked there.
    LaunchedEffect(kind) { if (kind == InquiryKind.BUG && pay == null && check == null) withCheck = true }
    LaunchedEffect(withCheck, showCheck) { if ((withCheck || showCheck) && check == null) check = vm.alarmCheck(context) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepTitle("1. 어떤 문의인가요?")
        InquiryKind.entries.forEach { k ->
            KindOption(k, selected = kind == k) { kind = k }
        }

        StepTitle("2. 무슨 일인지 적어 주세요")
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(InquiryClient.TEXT_MAX + 50) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            minLines = 5,
            placeholder = { Text(if (pay != null) PAY_PLACEHOLDER else placeholder(kind)) },
            isError = problem != null,
            supportingText = {
                Row {
                    Text(problem ?: "언제, 어느 화면에서였는지 적으면 더 빨리 답할 수 있어요", modifier = Modifier.weight(1f))
                    Text("${text.length}/${InquiryClient.TEXT_MAX}")
                }
            },
        )

        StepTitle("3. 함께 보내는 정보")
        SoftCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoLine("앱", vm.info.app)
                InfoLine("기기", vm.info.model)
                InfoLine("Android", vm.info.android)
                if (pay != null) {
                    Spacer(Modifier.height(4.dp))
                    CheckRow("급여 계산 내용도 보내기", "이 달의 규칙과 항목별 시간·가산율이에요. 금액은 들어가지 않아요",
                        checked = withPay, onChange = { withPay = it })
                    TextButton(onClick = { showPay = !showPay }) {
                        Text(if (showPay) "내용 접기" else "보낼 내용 보기")
                        Icon(if (showPay) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                    if (showPay) Shown(pay)
                }
                Spacer(Modifier.height(4.dp))
                CheckRow("알람 점검 기록도 보내기", "다음 알람 시각과 근무 이름, 권한 상태, 최근 알람 기록이 들어 있어요",
                    checked = withCheck, onChange = { withCheck = it })
                TextButton(onClick = { showCheck = !showCheck }) {
                    Text(if (showCheck) "기록 접기" else "보낼 기록 보기")
                    Icon(if (showCheck) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
                if (showCheck) Shown(check ?: "불러오는 중…")
            }
        }
        Text(INQUIRY_SEND_NOTICE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        val ready = kind != null && text.isNotBlank() && problem == null && !busy
        Button(
            onClick = {
                val k = kind ?: return@Button
                error = null
                scope.launch {
                    val diag = if (withCheck) check ?: vm.alarmCheck(context) else null
                    vm.send(k, text, diag, pay.takeIf { withPay }) { sent, why -> if (sent != null) onSent(sent) else { error = why } }
                }
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else {
                Icon(Icons.AutoMirrored.Outlined.Send, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (kind == null) "어떤 문의인지 골라 주세요" else "보내기")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private const val PAY_PLACEHOLDER = "예: 9월 야간수당이 명세서보다 3시간만큼 적게 나와요. 금액 대신 시간이나 비율로 적어 주세요"

/** A ticked line of what goes with the question. */
@Composable
private fun CheckRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Exactly what will be sent, before it is. */
@Composable
private fun Shown(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()).padding(10.dp))
    }
}

private fun placeholder(kind: InquiryKind?): String = when (kind) {
    InquiryKind.BUG -> "예: 어제 야간 출근 알람이 5분 늦게 울렸어요. 잠금 화면에는 아무것도 안 떴어요"
    InquiryKind.IDEA -> "예: 달력이 일요일부터 시작하면 좋겠어요"
    else -> "예: 대근을 4시간만 하면 급여에 어떻게 들어가나요?"
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
}

@Composable
private fun KindOption(kind: InquiryKind, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.primaryContainer.copy(alpha = 0.45f) else scheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kind.icon(), null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

private fun InquiryKind.icon(): ImageVector = when (this) {
    InquiryKind.QUESTION -> Icons.Outlined.QuestionAnswer
    InquiryKind.BUG -> Icons.Outlined.BugReport
    InquiryKind.IDEA -> Icons.Outlined.Lightbulb
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 72.dp).padding(end = 8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InquiryThread(vm: InquiryViewModel, i: Inquiry) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var text by rememberSaveable(i.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = vm.problem(text)
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(Fmt.shortFullDate(i.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()) + "에 보냄",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                i.label(unread = false).let { (t, tone) -> StatusChip(t, tone) }
            }
            when {
                i.hidden != null -> Text("모두의 문의에서 가려졌어요: ${i.hidden}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                i.public -> Text("모두의 문의에 올라가 있어요" + if (i.same > 0) " · 나도 그래요 ${i.same}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            i.messages.forEach { m -> Bubble(m.text, m.fromDeveloper, stampOf(m.at)) }
            if (i.waiting) {
                Text("개발자가 읽고 답하면 여기에 보여요. 설정 첫 화면에도 알려 드려요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(InquiryClient.TEXT_MAX + 50) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (i.answers > 0) "더 묻거나 답에 대해 적기" else "덧붙일 내용 적기") },
                        isError = problem != null,
                        supportingText = if (problem != null) { { Text(problem) } } else null,
                        maxLines = 5,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            error = null
                            vm.more(i.id, text) { sent, why -> if (sent != null) { text = "" } else { error = why } }
                        },
                        enabled = text.isNotBlank() && problem == null && !busy,
                    ) { Icon(Icons.AutoMirrored.Outlined.Send, "보내기") }
                }
            }
        }
    }
}

/** [ADMIN-02] Only the address, for those who want mail; questions themselves go through the app. */
@Composable
private fun MailNotice() {
    Text("메일 문의는 $REPORT_EMAIL 로 받아요. 답이 늦을 수 있어 앱 안 문의를 먼저 써 주세요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

/**
 * [ADMIN-02] 모두의 문의: every question sent as public, with how many said 나도 그래요 and the
 * developer's answers, so a problem someone already asked about is found before asking again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PublicBoard(vm: InquiryViewModel, onNew: () -> Unit, onOpen: (PublicInquiry) -> Unit) {
    val page by vm.board.collectAsStateWithLifecycle()
    val offline by vm.boardOffline.collectAsStateWithLifecycle()
    val topic by vm.topic.collectAsStateWithLifecycle()
    val bySame by vm.bySame.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        if (offline) OfflineLine { vm.loadBoard() }
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("새 문의 쓰기") }
        val p = page
        if (p == null) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            if (p.counts.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = topic == null, onClick = { vm.showTopic(null) }, label = { Text("전체") })
                    p.topics.filter { (key, _) -> (p.counts[key] ?: 0) > 0 || key == topic }.forEach { (key, name) ->
                        FilterChip(selected = topic == key, onClick = { vm.showTopic(key) }, label = { Text("$name ${p.counts[key] ?: 0}") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !bySame, onClick = { vm.sortBySame(false) }, label = { Text("최근") })
                    FilterChip(selected = bySame, onClick = { vm.sortBySame(true) }, label = { Text("나도 그래요 많은 순") })
                }
            }
            if (p.items.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroIcon(Icons.Outlined.QuestionAnswer)
                    Text(if (topic == null) "아직 올라온 문의가 없어요" else "이 주제의 문의가 없어요",
                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text("궁금한 점이나 이상한 점을 처음으로 알려 주세요. 개발자가 직접 답해요.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            p.items.forEach { i -> PublicCard(i) { onOpen(i) } }
            if (p.next != null) {
                OutlinedButton(onClick = { vm.loadBoard(more = true) }, modifier = Modifier.fillMaxWidth()) { Text("더 보기") }
            }
            MailNotice()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PublicCard(i: PublicInquiry, onClick: () -> Unit) {
    SoftCard(Modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i.kind.label} · ${i.topicName}" + if (i.mine) " · 내 문의" else "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (i.answers > 0) StatusChip("답 ${i.answers}", Tone.GOOD) else StatusChip("답을 기다려요", Tone.WAIT)
            }
            Text(i.preview, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("나도 그래요 ${i.same} · " + stampOf(i.updatedAt), style = MaterialTheme.typography.bodySmall,
                color = if (i.sameByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One public question: the conversation, 나도 그래요 and 신고. Writing more is for its writer, in 내 문의. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PublicThreadView(vm: InquiryViewModel, id: String, onGone: () -> Unit) {
    val loaded by vm.thread.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }
    var flagging by remember { mutableStateOf(false) }
    LaunchedEffect(id) { vm.openThread(id, failed = onGone) }
    val t = loaded?.takeIf { it.id == id }
    if (t == null) {
        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("${t.kind.label} · ${t.topicName} · " + Fmt.shortFullDate(t.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (t.messages.any { it.fromDeveloper }) StatusChip("답했어요", Tone.GOOD) else StatusChip("답을 기다려요", Tone.WAIT)
        }
        t.hidden?.let {
            Text("모두의 문의에서 가려졌어요: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        t.messages.forEach { m -> Bubble(m.text, m.fromDeveloper, stampOf(m.at)) }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (t.mine) {
            Text("내가 보낸 문의예요. 더 쓰거나 지우기는 ‘내 문의’에서 할 수 있어요. 나도 그래요 ${t.same}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.sameByMe) {
                    Button(onClick = { vm.same(false) { note = it } }) {
                        Icon(Icons.Filled.ThumbUp, null, modifier = Modifier.size(18.dp)); Text("  나도 그래요 ${t.same}")
                    }
                } else {
                    OutlinedButton(onClick = { vm.same(true) { note = it ?: "같은 일을 겪은 분이 많을수록 개발자가 먼저 봐요" } }) {
                        Icon(Icons.Outlined.ThumbUp, null, modifier = Modifier.size(18.dp)); Text("  나도 그래요 ${t.same}")
                    }
                }
                TextButton(onClick = { flagging = true }, enabled = !t.flaggedByMe) {
                    Icon(Icons.Outlined.Flag, null, modifier = Modifier.size(18.dp)); Text(if (t.flaggedByMe) " 신고했어요" else " 신고")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (flagging) {
        FlagDialog(onDismiss = { flagging = false }) { reason ->
            flagging = false
            vm.flag(reason) { why -> note = why ?: "신고했어요. 개발자가 확인해요" }
        }
    }
}

@Composable
private fun FlagDialog(onDismiss: () -> Unit, onFlag: (InquiryFlag) -> Unit) {
    var reason by remember { mutableStateOf(InquiryFlag.ABUSE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이 문의를 신고할까요?") },
        text = {
            Column {
                InquiryFlag.entries.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = reason == f, role = Role.RadioButton, onClick = { reason = f })
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == f, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(f.label)
                    }
                }
                Text("신고가 모이면 개발자가 확인할 때까지 가려져요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onFlag(reason) }) { Text("신고") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun Bubble(text: String, fromDeveloper: Boolean, at: String) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (fromDeveloper) Alignment.Start else Alignment.End) {
        Text(if (fromDeveloper) "개발자 · $at" else at, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        // A bubble is as wide as its text, up to most of the line, so who said what reads at a glance.
        Box(Modifier.fillMaxWidth(0.88f), contentAlignment = if (fromDeveloper) Alignment.CenterStart else Alignment.CenterEnd) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = if (fromDeveloper) scheme.primaryContainer else scheme.surfaceContainerHigh,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge,
                    color = if (fromDeveloper) scheme.onPrimaryContainer else scheme.onSurface, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
