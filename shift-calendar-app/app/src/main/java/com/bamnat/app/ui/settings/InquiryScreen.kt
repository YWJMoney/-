package com.bamnat.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.Button
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
import com.bamnat.app.company.InquiryKind
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
    "개발자에게만 가요. 이름이나 연락처 없이 이 휴대폰에서 만든 무작위 번호로 답을 받아요. " +
        "메일 주소, 전화번호, 금액은 적지 말아 주세요. 보낸 문의는 언제든 지울 수 있고, 180일 동안 새 글이 없으면 저절로 지워져요."

private const val LIST = "list"
private const val NEW = "new"

/**
 * [ADMIN-02] 설정 > 문의하기: questions sent from this phone with the developer's answers, a new
 * question in three steps (what about, what happened, what goes with it) and a thread to ask more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InquiryScreen(vm: InquiryViewModel, onBack: () -> Unit) {
    val list by vm.list.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    // The list, the form, or one question by its id.
    var view by rememberSaveable { mutableStateOf(LIST) }
    var deleting by remember { mutableStateOf<Inquiry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    val open = list?.firstOrNull { it.id == view }
    LaunchedEffect(open?.id, open?.answers) { open?.let(vm::opened) }
    // A question that is gone (deleted elsewhere) falls back to the list.
    LaunchedEffect(list, view) { if (view != LIST && view != NEW && list != null && open == null) view = LIST }

    val back = { if (view == LIST) onBack() else { view = LIST } }
    BackHandler(enabled = view != LIST) { view = LIST }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (view == NEW) "새 문의" else if (open != null) open.kind.label else "문의하기") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    if (open != null) IconButton(onClick = { deleting = open }) { Icon(Icons.Outlined.Delete, "이 문의 지우기") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                if (offline && view != NEW) OfflineLine(vm::refresh)
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                when {
                    view == NEW -> InquiryForm(vm, onSent = { sent -> message = null; view = sent.id })
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
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            InquiryItems(vm, list, onNew, onOpen)
            TextButton(onClick = { writeToUs(context) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("메일로 보내고 싶어요")
            }
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
            Text("개발자가 직접 읽고 답해요. 답은 이 화면에 와요.", style = MaterialTheme.typography.bodyMedium,
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
private fun InquiryForm(vm: InquiryViewModel, onSent: (Inquiry) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf<InquiryKind?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var withCheck by rememberSaveable { mutableStateOf(false) }
    var showCheck by rememberSaveable { mutableStateOf(false) }
    var check by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = vm.problem(text)
    // The alarm check helps most when the app misbehaves, so it is offered ticked there.
    LaunchedEffect(kind) { if (kind == InquiryKind.BUG && check == null) withCheck = true }
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
            placeholder = { Text(placeholder(kind)) },
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
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().toggleable(value = withCheck, role = Role.Checkbox, onValueChange = { withCheck = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = withCheck, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("알람 점검 기록도 보내기", style = MaterialTheme.typography.bodyLarge)
                        Text("다음 알람 시각과 근무 이름, 권한 상태, 최근 알람 기록이 들어 있어요",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { showCheck = !showCheck }) {
                    Text(if (showCheck) "기록 접기" else "보낼 기록 보기")
                    Icon(if (showCheck) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
                if (showCheck) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(check ?: "불러오는 중…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()).padding(10.dp))
                    }
                }
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
                    vm.send(k, text, diag) { sent, why -> if (sent != null) onSent(sent) else { error = why } }
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
