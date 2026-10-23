package com.bamnat.app.ui.settings

import com.bamnat.app.ui.guide.guideCalendar
import com.bamnat.app.appContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.VisibilityOff
import com.bamnat.app.company.CompanyText
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import com.bamnat.app.ui.components.FilterChip
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
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
/** [INQ-01] A company way names the workplace, so it stays with the developer; the privacy policy 1-3항 says the same. */
internal const val INQUIRY_PRIVATE_NOTICE =
    "회사 방식은 모두의 문의에 올라가지 않고 개발자만 봐요. 이름, 메일 주소, 전화번호, 기본급·수당 금액은 적지 말아 주세요. " +
        "보낸 문의는 언제든 지울 수 있고, 180일 동안 새 글이 없으면 저절로 지워져요. " +
        "다만 운영자가 고칠 일(개발사항)로 옮겨 적거나 요약한 내용은 문의를 지운 뒤에도 앱 개선 기록으로 남아요."

internal const val INQUIRY_SEND_NOTICE =
    "모두의 문의에 올라가요. 앱을 쓰는 다른 분도 적은 글과 개발자 답을 볼 수 있고, 보낸 분이 누구인지는 보이지 않아요. " +
        "3번의 ‘함께 보내는 정보’는 개발자만 봐요. 이름, 메일 주소, 전화번호, 금액은 적지 말아 주세요. " +
        "보낸 문의는 언제든 지울 수 있고, 180일 동안 새 글이 없으면 저절로 지워져요. " +
        "다만 운영자가 고칠 일(개발사항)로 옮겨 적거나 요약한 내용은 문의를 지운 뒤에도 앱 개선 기록으로 남아요."

private const val LIST = "list"
private const val NEW = "new"
// [ADMIN-02] 모두의 문의, and one public question by "pub:" and its id.
private const val BOARD = "board"
private const val PUB = "pub:"
// [UX-05] The answers to what people ask most, searched before a question is written.
private const val HELP = "help"

/**
 * [ADMIN-02][UX-05] 도움말과 문의: answers first (searched with the settings and 모두의 문의), then a
 * question in three steps (what about, what happened, what goes with it), questions sent from this
 * phone with the developer's answers, and a thread to ask more. A build without the company server
 * shows the answers and writes the question as a mail. Nothing here sends more than before: the
 * question's text, its kind and what the form shows it adds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InquiryScreen(vm: InquiryViewModel, onBack: () -> Unit) {
    val list by vm.list.collectAsStateWithLifecycle()
    val offline by vm.offline.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    // [UX-05] What the screen that opened this asked for; 계산이 이상해요 and 문의하기 open on the form.
    var start by remember { mutableStateOf(vm.start) }
    val first = when {
        vm.payCheck != null -> NEW
        start != null -> if (start?.help == true) HELP else NEW
        vm.startOnMine -> LIST
        else -> HELP
    }
    var view by rememberSaveable { mutableStateOf(first) }
    // Until that question is sent, back returns to the screen it came from.
    var fromElsewhere by rememberSaveable { mutableStateOf(first == NEW) }
    var fromPay by rememberSaveable { mutableStateOf(vm.payCheck != null) }
    // A new start begins the form again.
    var formKey by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf(start?.takeIf { it.help }?.text.orEmpty()) }
    var about by rememberSaveable { mutableStateOf(start?.about) }
    var deleting by remember { mutableStateOf<Inquiry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.loadBoard() }
    // Another screen's 문의 while this one is open (it stays on top) is taken here.
    val pending by InquiryLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val s = InquiryLinks.take() ?: return@LaunchedEffect
        start = s
        about = s.about
        message = null
        formKey++
        if (s.help) { query = s.text; view = HELP } else view = NEW
    }
    val open = list?.firstOrNull { it.id == view }
    val publicId = view.removePrefix(PUB).takeIf { view.startsWith(PUB) }
    val home = view == HELP || view == LIST || view == BOARD
    // Back from a public question returns to 모두의 문의, from anything else to where it was opened.
    var lastHome by rememberSaveable { mutableStateOf(if (first == NEW) HELP else first) }
    LaunchedEffect(view) { if (home) lastHome = view }
    LaunchedEffect(open?.id, open?.answers) { open?.let(vm::opened) }
    // A question that is gone (deleted elsewhere) falls back to the list.
    LaunchedEffect(list, view) { if (!home && view != NEW && publicId == null && list != null && open == null) view = LIST }
    val ask: (String, HelpTopic?) -> Unit = { text, topic ->
        start = InquiryStart(about = topic?.tag ?: about, kind = topic?.ask, withCheck = topic?.withCheck == true, text = text)
        message = null
        formKey++
        view = NEW
    }

    // [GUIDE-07] Nothing found: 의견 with the words searched and the kind of work, ready to send.
    val guideContainer = LocalContext.current.appContainer
    val idea: (String) -> Unit = { q ->
        val (_, style) = guideContainer.guideCalendar()
        start = InquiryStart(about = "기능 찾기", kind = InquiryKind.IDEA, text = com.bamnat.app.ui.guide.FeatureSearch.ideaText(q, style))
        message = null
        formKey++
        view = NEW
    }

    val leaves = home || (view == NEW && fromElsewhere)
    val back = { if (leaves) onBack() else { view = if (publicId != null) BOARD else lastHome } }
    BackHandler(enabled = !leaves) { back() }
    // [UX-05] Back clears a help search before it leaves.
    BackHandler(enabled = view == HELP && query.isNotEmpty()) { query = "" }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when {
                        view == NEW -> if (vm.online) "새 문의" else "메일로 문의"
                        open != null -> open.kind.label
                        publicId != null -> "모두의 문의"
                        else -> "도움말과 문의"
                    })
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    if (open != null) IconButton(onClick = { deleting = open }) { Icon(Icons.Outlined.Delete, "이 문의 지우기") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                if (home && vm.online) {
                    PrimaryTabRow(selectedTabIndex = when (view) { HELP -> 0; BOARD -> 1; else -> 2 }) {
                        Tab(selected = view == HELP, onClick = { message = null; view = HELP }, text = { Text("도움말") })
                        Tab(selected = view == BOARD, onClick = { message = null; view = BOARD }, text = { Text("모두의 문의") })
                        Tab(selected = view == LIST, onClick = { message = null; view = LIST }, text = { MineTab(unread) })
                    }
                }
                if (offline && view == LIST) OfflineLine(vm::refresh)
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                when {
                    view == NEW -> key(formKey) {
                        InquiryForm(vm, pay = vm.payCheck.takeIf { fromPay }, start = start,
                            onSent = { sent -> message = null; fromPay = false; fromElsewhere = false; start = null; view = sent.id },
                            // [INQ-05] Waiting to be sent: 내 문의 shows it at the top.
                            onQueued = { fromPay = false; fromElsewhere = false; start = null; message = InquiryViewModel.QUEUED; view = LIST },
                            onMailed = {
                                fromPay = false; start = null
                                if (fromElsewhere) onBack() else { message = "메일 앱에서 보내 주세요. 답은 메일로 와요"; view = HELP }
                            })
                    }
                    view == HELP -> HelpHome(vm, about, query, onQuery = { query = it }, list = list, onAsk = ask, onIdea = idea,
                        onMine = { message = null; view = LIST }, onOpenPublic = { message = null; view = PUB + it.id })
                    view == BOARD -> PublicBoard(vm, onNew = { ask("", null) }, onOpen = { message = null; view = PUB + it.id })
                    publicId != null -> PublicThreadView(vm, publicId, onGone = { message = "이 문의는 지워졌거나 가려졌어요"; view = BOARD })
                    open != null -> InquiryThread(vm, open)
                    else -> InquiryList(vm, list, onNew = { ask("", null) }, onOpen = { message = null; view = it.id })
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

/** [UX-05] 내 문의, with the answers not opened yet counted beside it and read as words. */
@Composable
private fun MineTab(unread: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("내 문의")
        if (unread > 0) {
            Spacer(Modifier.width(6.dp))
            Badge(Modifier.clearAndSetSemantics { contentDescription = "새 답 ${unread}개" }) { Text(if (unread > 9) "9+" else "$unread") }
        }
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
    } else {
        Text(standing(list, vm::unreadIn), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
    Button(onClick = onNew, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("새 문의 쓰기") }
    // [INQ-05] I9: questions waiting for the internet, above the ones sent.
    val outbox by vm.outbox.collectAsStateWithLifecycle()
    if (outbox.isNotEmpty()) OutboxCard(vm, outbox, onEdit = onNew)
    // [UX-05] New answers first, then the questions still waiting.
    vm.ordered(list).forEach { i -> InquiryCard(i, unread = vm.unreadIn(i), onClick = { onOpen(i) }) }
}

/** [INQ-05] 보내기 대기: what waits, why one was refused, and 지금 보내기 · 고치기 · 지우기. */
@Composable
private fun OutboxCard(vm: InquiryViewModel, outbox: List<com.bamnat.app.company.QueuedInquiry>, onEdit: () -> Unit) {
    var sending by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    SoftCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("보내기 대기 ${outbox.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(enabled = !sending, onClick = {
                    sending = true
                    vm.sendQueued { ok -> sending = false; said = if (ok) "보냈어요" else "아직 인터넷에 연결되지 않았어요. 연결되면 저절로 보내요" }
                }) { Text("지금 보내기") }
            }
            Text("인터넷이 연결되면 앱을 닫아 두어도 저절로 보내요. 사진은 함께 가지 않아요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            outbox.forEach { q ->
                HorizontalDivider()
                Text("${q.kind.label} · " + q.text.take(80), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                q.refused?.let { Text("서버가 받지 않았어요: " + CompanyText.problem(it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error) }
                Row {
                    TextButton(onClick = { vm.dropQueued(q.requestId, edit = true); onEdit() }) { Text("고치기") }
                    TextButton(onClick = { vm.dropQueued(q.requestId, edit = false) }) { Text("지우기") }
                }
            }
            said?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** [UX-05] "새 답 1개 · 답을 기다리는 문의 2개": where the user's questions stand, in one line. */
internal fun standing(list: List<Inquiry>, unread: (Inquiry) -> Boolean): String {
    val fresh = list.count(unread)
    val waiting = list.count { it.waiting }
    return listOfNotNull(
        "새 답이 온 문의 ${fresh}개".takeIf { fresh > 0 },
        "답을 기다리는 문의 ${waiting}개".takeIf { waiting > 0 },
    ).joinToString(" · ").ifEmpty { "보낸 문의 ${list.size}개에 모두 답했어요" }
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

/** "[달력] " and the like at the start of a question: the screen it was asked from. */
private val TAG = Regex("^\\[[^\\]]{1,30}]\\s*")

/** What is written beyond the screen's name; empty when nothing is. */
private fun bodyOf(text: String): String = text.replace(TAG, "").trim()

@Composable
private fun InquiryForm(
    vm: InquiryViewModel, pay: String?, start: InquiryStart?, onSent: (Inquiry) -> Unit, onMailed: () -> Unit, onQueued: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by vm.busy.collectAsStateWithLifecycle()
    // [UX-05] Without the company server the question becomes a mail the user sends from their mail app.
    val mail = !vm.online
    // [UX-05] The screen it was opened from starts the text, shown and editable.
    val given = (start?.about?.let { "[$it] " } ?: "") + start?.text.orEmpty()
    // [UX-05] A question started before comes back, unless this was opened with words of its own.
    val draft = remember { vm.draft.value.takeIf { pay == null && start?.text.isNullOrBlank() } }
    var restored by rememberSaveable { mutableStateOf(draft != null) }
    // Opened with words of its own while another question waits: that one can still be brought back.
    val older = remember { vm.draft.value.takeIf { draft == null && it?.text != given } }
    var offerOlder by rememberSaveable { mutableStateOf(older != null) }
    // From 계산이 이상해요 it is a 오류 제보 already, and the pay check goes with it unless unticked.
    var kind by rememberSaveable { mutableStateOf(if (pay != null) InquiryKind.BUG else start?.kind ?: draft?.kind) }
    var withPay by rememberSaveable { mutableStateOf(pay != null) }
    var showPay by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(draft?.text ?: given) }
    var withCheck by rememberSaveable { mutableStateOf(start?.withCheck == true) }
    var showCheck by rememberSaveable { mutableStateOf(false) }
    var check by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var openTip by rememberSaveable { mutableStateOf<String?>(null) }
    // [SRV-06] Up to two photos, made small and stripped on the phone; the developer's switch can pause them.
    var photos by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    val photosOpen = !mail && !context.appContainer.remoteConfig.current.paused(com.bamnat.core.ops.ServerSwitch.INQUIRY_PHOTOS)
    val pickPhotos = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(com.bamnat.app.company.InquiryPhotos.MAX),
    ) { uris ->
        scope.launch {
            val made = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.mapNotNull { com.bamnat.app.company.InquiryPhotos.prepare(context, it) }
            }
            photos = (photos + made).take(com.bamnat.app.company.InquiryPhotos.MAX)
            if (made.size < uris.size) error = "열 수 없는 사진은 빼고 붙였어요"
        }
    }
    val problem = if (mail) text.takeIf { it.length > InquiryClient.TEXT_MAX }?.let { "${InquiryClient.TEXT_MAX}자까지 쓸 수 있어요" } else vm.problem(text)
    // The alarm check helps most when the app misbehaves, so it is offered ticked there.
    LaunchedEffect(kind) { if (kind == InquiryKind.BUG && pay == null && check == null) withCheck = true }
    LaunchedEffect(withCheck, showCheck) { if ((withCheck || showCheck) && check == null) check = vm.alarmCheck(context) }
    // [UX-05] Kept as it is written, so leaving to look something up loses nothing. Only on this phone.
    LaunchedEffect(kind, text) { vm.saveDraft(kind, if (bodyOf(text).isEmpty()) "" else text) }
    val sendByMail: () -> Unit = {
        scope.launch {
            val diag = if (withCheck) check ?: vm.alarmCheck(context) else null
            mailQuestion(context, kind, text, diag, pay.takeIf { withPay })
            vm.clearDraft()
            onMailed()
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (restored) {
            DraftLine("쓰던 문의를 불러왔어요", action = "새로 쓰기") {
                restored = false
                vm.clearDraft()
                text = given
                kind = start?.kind
            }
        }
        if (offerOlder && older != null) {
            DraftLine("쓰던 문의가 하나 더 있어요", action = "불러오기") {
                offerOlder = false
                text = older.text
                if (older.kind != null) kind = older.kind
            }
        }
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
                    Spacer(Modifier.width(8.dp))
                    Text("${text.length}/${InquiryClient.TEXT_MAX}")
                }
            },
        )
        // [UX-05] An answer that fits what is being written, before it is sent.
        val body = bodyOf(text)
        val topics = remember { HelpFaq.topics(vm.shareOn) }
        val tips = remember(body, kind) {
            if (kind == InquiryKind.COMPANY || body.length < 4) emptyList() else HelpFaq.forQuestion(body, topics, limit = 2)
        }
        if (tips.isNotEmpty()) {
            Text("혹시 이런 내용인가요?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            tips.forEach { t -> FaqCard(t, expanded = openTip == t.id, onToggle = { openTip = if (openTip == t.id) null else t.id }) }
        }

        StepTitle(if (mail) "3. 메일에 함께 적는 정보" else "3. 함께 보내는 정보")
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
                if (photosOpen) {
                    Spacer(Modifier.height(4.dp))
                    Text("사진 ${photos.size}/${com.bamnat.app.company.InquiryPhotos.MAX}", style = MaterialTheme.typography.bodyMedium)
                    Text("화면을 찍은 사진이 있으면 더 빨리 알 수 있어요. 위치 같은 사진 정보는 지우고, 작게 줄여서 보내요. " +
                        "문의를 닫고 90일 뒤에 서버에서 지워져요. 다른 사람의 얼굴·이름이나 건강 정보가 보이는 사진은 붙이지 말아 주세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (photos.size < com.bamnat.app.company.InquiryPhotos.MAX) {
                            OutlinedButton(onClick = {
                                pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) { Text("사진 붙이기") }
                        }
                        if (photos.isNotEmpty()) TextButton(onClick = { photos = emptyList() }) { Text("사진 빼기") }
                    }
                }
            }
        }
        Text(
            when {
                mail -> "메일 앱이 열려요. 보내기 전에 한 번 더 보고 고칠 수 있어요. 받는 곳: $REPORT_EMAIL"
                kind?.public == false -> INQUIRY_PRIVATE_NOTICE
                else -> INQUIRY_SEND_NOTICE
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            // [UX-05] A question that cannot go stays written, and can still go by mail.
            OutlinedButton(onClick = sendByMail, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Email, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("메일로 보내기")
            }
        }
        val ready = kind != null && body.isNotEmpty() && problem == null && !busy
        Button(
            onClick = {
                val k = kind ?: return@Button
                error = null
                if (mail) sendByMail()
                else scope.launch {
                    val diag = if (withCheck) check ?: vm.alarmCheck(context) else null
                    vm.send(k, text, diag, pay.takeIf { withPay }, photos.takeIf { photosOpen }.orEmpty()) { sent, why ->
                        if (sent != null) {
                            com.bamnat.app.company.InquiryPhotos.skipped(sent.photosSkipped)?.let {
                                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                            }
                            onSent(sent)
                        } else if (why == InquiryViewModel.QUEUED) onQueued() else { error = why }
                    }
                }
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else {
                Icon(if (mail) Icons.Outlined.Email else Icons.AutoMirrored.Outlined.Send, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(when {
                    kind == null -> "어떤 문의인지 골라 주세요"
                    body.isEmpty() -> "무슨 일인지 적어 주세요"
                    mail -> "메일 앱으로 보내기"
                    else -> "보내기"
                })
            }
        }
        if (!mail) Note("보내기 전까지 쓰던 내용은 이 휴대폰에 남아 있어요.")
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * [UX-05] The question as a mail from the user's own mail app: what they wrote, and what they ticked to
 * go with it, in the same words the form showed. Nothing goes until they send it there.
 */
private fun mailQuestion(context: Context, kind: InquiryKind?, text: String, diag: String?, pay: String?) {
    val extra = buildString {
        if (pay != null) append("\n\n[급여 계산 내용]\n").append(pay)
        if (diag != null) append("\n\n[알람 점검 기록]\n").append(diag)
    }
    writeToUs(context, "[밤낮 문의] " + (kind?.label ?: "문의"), text.trim() + extra)
}

/** [UX-05] One line about a question not sent yet, with what can be done about it. */
@Composable
private fun DraftLine(text: String, action: String, secondary: Pair<String, () -> Unit>? = null, onAction: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EditNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f))
            secondary?.let { (label, act) -> TextButton(onClick = act, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) } }
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
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
    InquiryKind.COMPANY -> "예: OO공장 생산2팀이에요. 휴무일 근무는 8시간에 기본급 10%, 8시간 넘으면 0.5 가산이에요"
    InquiryKind.OTHER -> "예: 야간 근무 날 화면이 어두워져서 좋아요"
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
    InquiryKind.COMPANY -> Icons.Outlined.Apartment
    InquiryKind.OTHER -> Icons.Outlined.ChatBubbleOutline
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
    // [UX-05] More to say is kept until it is sent, like a new question.
    var text by rememberSaveable(i.id) { mutableStateOf(vm.reply(i.id)) }
    var error by remember { mutableStateOf<String?>(null) }
    val problem = vm.problem(text)
    LaunchedEffect(i.id, text) { vm.saveReply(i.id, text) }
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
            ProgressSteps(i)
            // [INQ-05] I8: what came of it is in the update; its lines open from here.
            if (i.stage == "released") TextButton(onClick = { com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.WHATS_NEW) }) {
                Text("새 기능 안내 보기")
            }
            i.messages.forEach { m -> Bubble(m.text, m.fromDeveloper, stampOf(m.at)) }
            if (i.waiting) {
                Text("개발자가 읽고 답하면 여기에 보여요. 보통 하루 안에 답해요. 설정 탭과 설정 첫 화면에도 표시해 드려요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (i.answers > 0) RateAnswer(i, busy, onMore = { more ->
                error = null
                vm.more(i.id, more) { sent, why -> if (sent == null) error = why }
            }) { good -> error = null; vm.rate(i.id, good) { why -> error = why } }
            Spacer(Modifier.height(8.dp))
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
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

/**
 * [INQ-02] Where the question stands, in the steps every question goes through: 접수 → 확인 → 답변, and for a
 * fix or an idea 개발 목록 → 만드는 중 → 반영 (with the version), so the asker sees it was not lost.
 */
@Composable
private fun ProgressSteps(i: Inquiry) {
    val steps = inquirySteps(i)
    val at = steps.indexOfFirst { it.first == i.stage }.coerceAtLeast(0)
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "진행 상황: ${steps[at].second}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { n, (_, name) ->
            val done = n <= at
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(if (done) scheme.primary else scheme.surfaceVariant, RoundedCornerShape(2.dp)))
                Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (n == at) scheme.primary else if (done) scheme.onSurface else scheme.onSurfaceVariant,
                    fontWeight = if (n == at) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
            }
            if (n < steps.lastIndex) Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * [INQ-02][INQ-05] I8: the steps of a question. A bug or an idea can become work, so it shows all six,
 * 접수 → 확인 중 → 답변 → 개발 목록 → 만드는 중 → 반영 (with the version); other questions end at the answer.
 */
internal fun inquirySteps(i: Inquiry): List<Pair<String, String>> {
    val answer = listOf("received" to "접수", "checking" to "확인 중", "answered" to "답변")
    val dev = listOf("planned" to "개발 목록", "building" to "만드는 중", "released" to (i.version?.let { "$it 반영" } ?: "반영"))
    return if (i.kind == InquiryKind.BUG || i.kind == InquiryKind.IDEA || i.stage in setOf("planned", "building", "released")) answer + dev else answer
}

/** [INQ-03] After an answer: was it helpful? 아쉬워요 shows the admins it needs another look; [INQ-05] I10 asks one line more. */
@Composable
private fun RateAnswer(i: Inquiry, busy: Boolean, onMore: (String) -> Unit = {}, onRate: (Boolean?) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(when (i.rating) {
            true -> "도움이 됐다고 알려 주셨어요. 고마워요"
            false -> "아쉬운 점을 아래에 더 적어 주시면 다시 살펴볼게요"
            null -> "답이 도움이 됐나요?"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = i.rating == true, enabled = !busy, onClick = { onRate(if (i.rating == true) null else true) },
                label = { Text("도움이 됐어요") })
            FilterChip(selected = i.rating == false, enabled = !busy, onClick = { onRate(if (i.rating == false) null else false) },
                label = { Text("아쉬워요") })
        }
        // [INQ-05] I10: what was missing, in a line, right after 아쉬워요; it goes as a message on the question.
        val asked = i.messages.lastOrNull()?.let { !it.fromDeveloper && it.text.startsWith(MISSED) } == true
        if (i.rating == false && !asked) {
            var line by rememberSaveable(i.id) { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(line, { line = it.take(200) }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("무엇이 아쉬웠나요? (한 줄)") })
                TextButton(enabled = line.isNotBlank() && !busy, onClick = { onMore("$MISSED ${line.trim()}"); line = "" }) { Text("보내기") }
            }
        }
    }
}

/** [INQ-05] How the one line after 아쉬워요 starts, so the admins see what it is. */
internal const val MISSED = "[아쉬운 점]"

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
            // [COMM-11] What this phone's 이 사람 글 안 보기 left out, and where to undo it.
            if (p.hiddenByYou > 0) Text("가린 사람의 문의 ${p.hiddenByYou}개는 빼고 보여요. 회사 광장 › 내 활동에서 되돌릴 수 있어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        // [COMM-11] G1 이 사람 글 안 보기, as in the plaza: the mark stays on this phone.
        var hiding by remember { mutableStateOf(false) }
        if (!t.mine && t.author != null) TextButton(onClick = { hiding = true }) {
            Icon(Icons.Outlined.VisibilityOff, null, modifier = Modifier.size(18.dp)); Text(" 이 사람 글 안 보기")
        }
        if (hiding) ConfirmDialog(
            title = "이 사람의 문의를 가릴까요?",
            message = "모두의 문의와 회사 광장에서 이 사람의 글이 이 휴대폰에서만 안 보여요. 회사 광장 › 내 활동의 ‘가린 사람’에서 되돌릴 수 있어요.",
            confirmLabel = "가리기",
            onDismiss = { hiding = false },
            onConfirm = { hiding = false; t.author?.let(vm::hideWriter); onGone() },
        )
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

/**
 * [UX-05] 도움말: one search over the answers, the settings and 모두의 문의, the answers about the screen
 * it was opened from first, then the rest. A question is always one tap away at the end, with the
 * words searched already in it, so not finding an answer is never a dead end.
 */
@Composable
private fun HelpHome(
    vm: InquiryViewModel,
    about: String?,
    query: String,
    onQuery: (String) -> Unit,
    list: List<Inquiry>?,
    onAsk: (String, HelpTopic?) -> Unit,
    onMine: () -> Unit,
    onOpenPublic: (PublicInquiry) -> Unit,
    onIdea: (String) -> Unit = {},
) {
    val topics = remember { HelpFaq.topics(vm.shareOn) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val toggle: (HelpTopic) -> Unit = { t -> expanded = if (expanded == t.id) null else t.id }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { onQuery(it.take(60)) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "기능과 도움말 찾기" },
            // [GUIDE-07] Features, answers and settings by name or other words.
            placeholder = { Text("무엇을 찾으세요? (예: 주휴, 백업, 알람)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "검색어 지우기") } }) else null,
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )
        draft?.let { d ->
            DraftLine("쓰던 문의가 있어요: " + d.text.lineSequence().first().take(24), action = "이어 쓰기",
                secondary = "지우기" to { vm.clearDraft() }) { onAsk("", null) }
        }
        if (query.isBlank()) {
            val here = remember(about, topics) { HelpFaq.about(about, topics).take(4) }
            if (here.isNotEmpty()) {
                SectionLabel("‘${about.orEmpty().substringAfterLast("> ").trim()}’ 도움말")
                here.forEach { t -> FaqCard(t, expanded == t.id, { toggle(t) }, onAsk = { onAsk("", t) }) }
            }
            SectionLabel("자주 묻는 질문")
            (topics - here.toSet()).forEach { t -> FaqCard(t, expanded == t.id, { toggle(t) }, onAsk = { onAsk("", t) }) }
        } else {
            val found = remember(query, topics) { HelpFaq.search(query, topics) }
            val settings = remember(query) {
                SettingsIndex.search(query, SettingsIndex.entries(board = false, share = vm.shareOn), limit = 3)
                    .filter { (it.place as? SettingPlace.Screen)?.go != SettingsGo.INQUIRY }
            }
            val posts = remember(query, board) { boardMatches(query, board?.items.orEmpty()) }
            // [GUIDE-07] The features first: where each is, what it does, and whether it is off on this calendar.
            val guideContainer = LocalContext.current.appContainer
            val features = remember(query) {
                val (_, style) = guideContainer.guideCalendar()
                com.bamnat.app.ui.guide.FeatureSearch.search(query, style)
            }
            val total = found.size + settings.size + posts.size + features.size
            Text(if (total == 0) "‘$query’에 맞는 답을 찾지 못했어요" else "찾은 답 ${total}개",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
            if (features.isNotEmpty()) {
                SectionLabel("기능에서 찾기")
                features.forEach { f -> com.bamnat.app.ui.guide.FeatureHelpRow(f) }
            }
            if (found.isNotEmpty() && features.isNotEmpty()) SectionLabel("자주 묻는 질문에서")
            found.forEach { t -> FaqCard(t, expanded == t.id, { toggle(t) }, onAsk = { onAsk(query, t) }) }
            if (settings.isNotEmpty()) {
                SectionLabel("설정에서 찾기")
                settings.forEach { e -> HelpSettingRow(e) }
            }
            if (posts.isNotEmpty()) {
                SectionLabel("모두의 문의에서")
                posts.forEach { p -> PublicCard(p) { onOpenPublic(p) } }
            }
        }
        AskCard(vm, query, list, onAsk = { onAsk(query, null) }, onMine = onMine, onIdea = { onIdea(query) })
        if (vm.online) MailNotice()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp).semantics { heading() })
}

/**
 * [UX-05] One answer: the question, and when opened, the answer, a button to where it is done, and a
 * question about it if that did not help.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FaqCard(topic: HelpTopic, expanded: Boolean, onToggle: () -> Unit, onAsk: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (expanded) scheme.surfaceContainer else scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "답 접기" else "답 보기", onClick = onToggle)
                    .semantics { stateDescription = if (expanded) "펼침" else "접힘" }
                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(topic.question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = scheme.onSurfaceVariant)
            }
            if (expanded) {
                Column(Modifier.padding(start = 48.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(topic.answer, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        topic.action?.let { (label, action) ->
                            FilledTonalButton(onClick = { action.run() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                        }
                        if (onAsk != null) {
                            TextButton(onClick = onAsk, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (topic.withCheck) "점검 기록과 함께 문의" else "그래도 안 되면 문의")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** [UX-05] A setting the help search found, with where it lives; it opens there. */
@Composable
private fun HelpSettingRow(e: SettingEntry) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button) {
                when (val p = e.place) {
                    is SettingPlace.Row -> SettingsLinks.open(p.page, p.row)
                    is SettingPlace.Screen -> SettingsLinks.go(p.go)
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge)
            Text(if (e.where == "설정") "설정" else "설정 › ${e.where}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** [UX-05] Questions others asked that hold the words searched, as far as 모두의 문의 is loaded. */
internal fun boardMatches(query: String, items: List<PublicInquiry>, limit: Int = 3): List<PublicInquiry> =
    // Only the question's own words count: every question under 알람 is not about a late alarm.
    SearchText.best(items, limit) { SearchText.score(query, it.preview, emptyList()) }

/**
 * [UX-05] The end of the help page: ask the developer, with where the user's questions stand. A build
 * without the server asks by mail.
 */
@Composable
private fun AskCard(vm: InquiryViewModel, query: String, list: List<Inquiry>?, onAsk: () -> Unit, onMine: () -> Unit, onIdea: () -> Unit = {}) {
    SoftCard(Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // [GUIDE-07] Not found: the words go to the developer as 건의·제안, with the kind of work.
            if (query.isNotBlank()) {
                Text("못 찾았나요?", style = MaterialTheme.typography.titleMedium)
                Text("찾던 말과 지금 달력의 일하는 방식을 담아 의견으로 보내요. 있는 기능이면 어디 있는지 답해 드리고, 없으면 만들 때 참고해요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onIdea, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Outlined.QuestionAnswer, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("못 찾았어요 · 의견 보내기")
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            Text(if (query.isBlank()) "찾는 답이 없나요?" else "‘$query’ 그대로 물어볼까요?", style = MaterialTheme.typography.titleMedium)
            Text(
                if (vm.online) "개발자가 직접 읽고 답해요. 답이 오면 설정 탭에 표시돼요."
                else "메일로 물어보면 개발자가 직접 읽고 메일로 답해요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAsk, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(if (vm.online) Icons.Outlined.QuestionAnswer else Icons.Outlined.Email, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (vm.online) "새 문의 쓰기" else "메일로 문의하기")
            }
            if (vm.online && !list.isNullOrEmpty()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
                        .clickable(role = Role.Button, onClick = onMine).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("내 문의: " + standing(list, vm::unreadIn), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
