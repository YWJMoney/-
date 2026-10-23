package com.bamnat.app.ui.company

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.company.BoardItem
import com.bamnat.app.company.TalkPost
import kotlinx.coroutines.Dispatchers
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [COMM-07] 회사 규칙 광장: the rule sets coworkers shared, from every company. Ones still being voted on
 * come first, so anyone who knows the company can say 맞았어요 or 달라요 and talk it over; adopted ones
 * are there for everyone to use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyBoardScreen(
    vm: CompanyBoardViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: () -> Unit,
    /** [PLZ-14] 임금표. */
    onRates: (() -> Unit)? = null,
) {
    val adopted by vm.adopted.collectAsStateWithLifecycle()
    val voting by vm.voting.collectAsStateWithLifecycle()
    val chosen by vm.chosen.collectAsStateWithLifecycle()
    val terms by vm.terms.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val plazas by vm.plazas.collectAsStateWithLifecycle()
    // [STAB-01] Read on the key's own frame, or half-built Korean letters double or drop.
    val query by vm.query.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val keyboard = LocalSoftwareKeyboardController.current
    val tab = if (adopted) chosen else voting
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } ?: false } }
    LaunchedEffect(nearEnd, adopted) { if (nearEnd) vm.more() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (filter.job.isEmpty()) "규칙 광장" else "${com.bamnat.app.company.Plaza.job(filter.job)} 광장") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    // [PLZ-14][PLZ-08] Plain buttons, not a menu to find first.
                    if (onRates != null) TextButton(onClick = onRates) { Text("임금표") }
                    TextButton(onClick = vm.activity::show) { Text("내 활동") }
                },
            )
        },
        floatingActionButton = {
            // [SETUP-10] Shrinks to its icon once the list moves, so it does not sit on a card's numbers.
            val wide by remember { derivedStateOf { list.firstVisibleItemIndex == 0 } }
            ExtendedFloatingActionButton(
                onClick = onShare, expanded = wide, text = { Text("우리 회사 규칙 올리기") },
                icon = { Icon(Icons.Filled.Add, if (wide) null else "우리 회사 규칙 올리기") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = list,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    PrimaryTabRow(selectedTabIndex = if (adopted) 1 else 0) {
                        Tab(selected = !adopted, onClick = { vm.pick(false) }, text = { Text("투표 중") })
                        Tab(selected = adopted, onClick = { vm.pick(true) }, text = { Text("채택됨") })
                    }
                }
                item {
                    // [COMM-07] Any company's rules by name, adopted or still being voted on.
                    OutlinedTextField(
                        value = query,
                        onValueChange = { vm.query.value = it.take(40) },
                        placeholder = { Text("회사 이름으로 찾기") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, "지우기") }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                item { PlazaFilterBar(filter, plazas, vm::setFilter) }
                item {
                    Text(
                        if (adopted) "같은 회사 분들이 맞다고 했거나 개발자가 확인한 규칙이에요. 누구나 그대로 가져다 쓸 수 있어요. 금액은 각자 넣어요."
                        else "같은 회사 분들이 올린 규칙이에요. 다니는 회사라면 맞는지 알려 주세요. 맞았어요가 ${terms?.agree ?: 75}% 이상이고 " +
                            "${terms?.threshold ?: 3}점이 모이면 채택돼서 모두가 쓸 수 있어요. 개발자가 확인해 채택하기도 해요.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(tab.items, key = { "${it.companyId}/${it.setup}" }) { item ->
                    BoardCard(item, onOpen = { onOpen(item.companyId) }, onTalk = { vm.talk.show(item.companyId, item.setup, item.title()) })
                }
                item {
                    when {
                        tab.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                        tab.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                            Text(tab.error, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = vm::refresh, modifier = Modifier.padding(top = 8.dp)) { Text("다시 불러오기") }
                        }
                        tab.items.isEmpty() -> Text(
                            when {
                                query.isNotBlank() && adopted -> "‘${query.trim()}’의 채택된 규칙은 아직 없어요. 투표 중 탭도 봐 주세요."
                                query.isNotBlank() -> "‘${query.trim()}’의 투표 중인 규칙이 없어요. 우리 회사 규칙을 처음 올려 주세요!"
                                adopted -> "아직 채택된 규칙이 없어요."
                                else -> "지금 투표 중인 규칙이 없어요. 우리 회사 규칙을 처음 올려 주세요!"
                            },
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp),
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
    TalkSheet(vm.talk)
    ActivitySheet(vm.activity)
}

private fun BoardItem.title() = "$company · ${dept.ifEmpty { "회사 전체" }}"

@Composable
private fun BoardCard(item: BoardItem, onOpen: () -> Unit, onTalk: () -> Unit) {
    SoftCard(Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.company, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(item.dept.ifEmpty { "회사 전체" }, item.cycle, if (item.pay) "급여 계산 포함" else "근무표만").filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.adopted) StatusChip("채택${item.version?.let { " v$it" } ?: ""}", Tone.GOOD) else StatusChip("투표 중", Tone.WAIT)
            }
            // [PLZ-12] Its plaza and region, and whether it is a whole job's rule rather than one place's.
            val place = listOfNotNull(
                com.bamnat.app.company.Plaza.job(item.job).ifEmpty { null },
                item.region.takeIf { it.isNotEmpty() }?.let(com.bamnat.app.company.Plaza::region),
                when (item.kind) { "topic" -> "직군 공통"; "hospital" -> "병원"; else -> null },
            )
            if (place.isNotEmpty()) Text(place.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (item.gist.isNotEmpty()) Text(item.gist, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TrustLine(item.trust, item.agree, item.persons, item.bad, item.adopted)
            WhoLine(0, 0, item.uses, item.by)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ago(item.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onTalk) {
                    Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (item.posts > 0) "토론 ${item.posts}" else "토론하기")
                }
            }
        }
    }
}

/**
 * [COMM-07] 신뢰도 as a bar, with who stands behind it. Adopted rules show how many agree; ones being
 * voted on show how close they are.
 */
@Composable
fun TrustLine(trust: Int, agree: Int, persons: Int, bad: Int, adopted: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("신뢰도", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { (if (adopted) agree.coerceAtLeast(trust) else trust) / 100f },
                modifier = Modifier.weight(1f).semantics { contentDescription = "신뢰도 ${if (adopted) agree else trust}" },
                drawStopIndicator = {},
            )
            Spacer(Modifier.width(8.dp))
            Text("${if (adopted) agree.coerceAtLeast(trust) else trust}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            // [SETUP-10] 맞았어요 once, its share beside it: "맞았어요 2명 (67%) · 달라요 1명".
            when {
                persons > 0 -> "맞았어요 ${persons}명 (${agree}%)" + if (bad > 0) " · 달라요 ${bad}명" else ""
                bad > 0 -> "달라요 ${bad}명"
                else -> "아직 확인한 분이 없어요"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 맞았어요 · 달라요 on one setup, for anyone who knows the company; 달라요 asks what is off. */
@Composable
fun VoteButtons(mine: Boolean?, onVote: (Boolean, String?) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val okText = if (mine == true) "맞았어요 ✓" else "맞았어요"
        val badText = if (mine == false) "달라요 ✓" else "달라요"
        if (mine == true) FilledTonalButton(onClick = { onVote(true, null) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ThumbUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(okText) }
        else OutlinedButton(onClick = { onVote(true, null) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ThumbUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(okText) }
        if (mine == false) FilledTonalButton(onClick = { asking = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ThumbDown, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(badText) }
        else OutlinedButton(onClick = { asking = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ThumbDown, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(badText) }
    }
    if (asking) {
        var why by remember { mutableStateOf("wrong") }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("어디가 달라요?") },
            text = {
                Column {
                    listOf("wrong" to "근무 시간이나 계산 방식이 달라요", "old" to "예전 정보예요", "name" to "회사나 부서 이름이 달라요").forEach { (key, text) ->
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { why = key }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = why == key, onClick = { why = key })
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text("무엇이 다른지 토론에 적어 주시면 같은 회사 분들께 큰 도움이 돼요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { asking = false; onVote(false, why) }) { Text("달라요 보내기") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("취소") } },
        )
    }
}

/**
 * [COMM-06] The talk on one rule set, in a sheet: each writer is only 참여자 N of this talk, with what
 * they said about it. Writing asks for no name, and the server refuses contacts and amounts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkSheet(holder: TalkHolder) {
    val open by holder.open.collectAsStateWithLifecycle()
    val o = open ?: return
    var text by rememberSaveable(o.setup) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember(o.setup) { mutableStateOf<String?>(null) }
    var flagging by remember { mutableStateOf<TalkPost?>(null) }
    var replyTo by remember(o.setup) { mutableStateOf<TalkPost?>(null) }
    var kind by rememberSaveable(o.setup) { mutableStateOf("talk") }
    var change by remember(o.setup) { mutableStateOf(listOf(blankChange())) }
    var withSetup by rememberSaveable(o.setup) { mutableStateOf(false) }
    var hiding by remember { mutableStateOf<TalkPost?>(null) }
    val rules = rememberPlazaRules()
    HideWriterDialog(hiding, onDismiss = { hiding = null }, onHide = holder::hide)
    ModalBottomSheet(onDismissRequest = holder::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).imePadding()) {
            Text("토론", style = MaterialTheme.typography.titleLarge)
            Text(o.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 8.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("다른 분께는 ‘참여자 번호’로만 보여요. 이름, 연락처, 금액은 적지 마세요. 욕설·비방·광고는 신고로 가려져요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val talk = o.talk
                when {
                    talk == null && o.error != null -> Text(o.error, style = MaterialTheme.typography.bodyMedium)
                    talk == null -> CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                    talk.posts.isEmpty() -> Text("아직 글이 없어요. 이 규칙이 맞는지, 다른 점이 있는지 처음 적어 주세요.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
                    else -> {
                        // [PLZ-05] Answers sit under the post they answer.
                        val tops = talk.posts.filter { it.parent == null || talk.posts.none { t -> t.id == it.parent } }
                        tops.forEach { p ->
                            PostRow(p, onDelete = { holder.delete(p.id) }, onFlag = { flagging = p }, onHide = { hiding = p },
                                onVote = { ok -> holder.vote(p.id, ok) { e -> error = e } }, onReply = if (talk.past) null else ({ replyTo = p }))
                            talk.posts.filter { it.parent == p.id }.forEach { r ->
                                Row {
                                    Spacer(Modifier.width(20.dp))
                                    Column(Modifier.weight(1f)) {
                                        PostRow(r, onDelete = { holder.delete(r.id) }, onFlag = { flagging = r }, onHide = { hiding = r },
                                            onVote = { ok -> holder.vote(r.id, ok) { e -> error = e } }, onReply = if (talk.past) null else ({ replyTo = p }))
                                    }
                                }
                            }
                        }
                    }
                }
                // [COMM-09] Said plainly, so a quiet talk is not mistaken for an empty one.
                o.talk?.hiddenByYou?.takeIf { it > 0 }?.let { n ->
                    Text("가린 분의 글 ${n}개는 보이지 않아요. 내 활동 > 가린 사람에서 다시 볼 수 있어요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val muted = o.talk?.mutedUntil
            if (o.talk?.past == true) {
                Text("지금은 새 버전이 쓰이고 있어요. 이 토론은 지난 버전의 기록이라 읽기만 할 수 있어요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            } else if (muted != null) {
                Text("운영 원칙을 지키지 않은 글이 있어 ${day(muted)}까지 글을 쓸 수 없어요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            } else if (holder.writePaused) {
                // [SRV-04] The developer paused writing; reading, 맞아요 and 신고 still work.
                Text(com.bamnat.core.ops.ServerSwitch.pausedText(com.bamnat.core.ops.ServerSwitch.PLAZA_WRITE), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            } else {
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                // [PLZ-09] What kind of post: a word, a question, or a proposal to change the rule.
                if (replyTo == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        listOf("talk" to "의견", "question" to "질문", "proposal" to "고치자는 제안").forEach { (k, label) ->
                            com.bamnat.app.ui.components.FilterChip(selected = kind == k, onClick = { kind = k; error = null }, label = { Text(label) })
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("${replyTo?.let { it.nick ?: "참여자 ${it.who}" }}에게 답글", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { replyTo = null }) { Text("답글 취소") }
                    }
                }
                if (kind == "proposal" && replyTo == null) {
                    ProposalEditor(change, onChange = { change = it; error = null }, withSetup = withSetup, onWithSetup = { withSetup = it })
                }
                // [COMM-11] I4: how many more posts today, so the limit is no surprise.
                o.talk?.leftPosts?.let { n ->
                    Text(if (n > 0) "오늘 ${n}번 더 쓸 수 있어요" else "오늘은 더 쓸 수 없어요. 내일 다시 써 주세요",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (n > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(500); error = null },
                        placeholder = { Text(when {
                            replyTo != null -> "답글을 적어 주세요"
                            kind == "question" -> "궁금한 점을 적어 주세요"
                            kind == "proposal" -> "왜 바꿔야 하는지 한두 줄로 적어 주세요"
                            else -> "이 규칙에 대해 적어 주세요"
                        }) },
                        maxLines = 4,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val proposing = kind == "proposal" && replyTo == null
                            val lines = change.filter { it.want.isNotBlank() }
                            if (proposing && lines.isEmpty()) {
                                error = "무엇을 어떻게 바꾸자는지 한 줄 이상 적어 주세요"
                            } else rules.ensure {
                                busy = true
                                holder.send(text.trim(), parent = replyTo?.id, kind = if (replyTo != null) "talk" else kind, change = if (proposing) lines else emptyList(),
                                    withSetup = proposing && withSetup) { e ->
                                    busy = false; error = e
                                    if (e == null) { text = ""; replyTo = null; if (proposing) { change = listOf(blankChange()); kind = "talk" } }
                                }
                            }
                        },
                        enabled = !busy && text.trim().length >= 2,
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Filled.Send, "보내기")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    flagging?.let { p ->
        var reason by remember { mutableStateOf("abuse") }
        AlertDialog(
            onDismissRequest = { flagging = null },
            title = { Text("이 글을 신고할까요?") },
            text = {
                Column {
                    listOf("abuse" to "욕설·비방", "personal" to "개인 정보가 있어요", "spam" to "광고·도배", "false" to "일부러 틀린 정보").forEach { (key, label) ->
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { reason = key }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reason == key, onClick = { reason = key })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text("서로 다른 세 분이 신고하면 개발자가 확인할 때까지 가려져요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { flagging = null; holder.flag(p.id, reason) { e -> error = e ?: "신고했어요. 고마워요" } }) { Text("신고하기") } },
            dismissButton = { TextButton(onClick = { flagging = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun PostRow(p: TalkPost, onDelete: () -> Unit, onFlag: () -> Unit, onHide: () -> Unit = {}, onVote: (Boolean) -> Unit = {}, onReply: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .padding(start = 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val name = p.nick ?: "참여자 ${p.who}"
            Text(if (p.mine) "나 ($name)" else name, style = MaterialTheme.typography.labelLarge)
            when (p.kind) {
                "question" -> { Spacer(Modifier.width(4.dp)); StatusChip("질문", Tone.QUIET) }
                "proposal" -> { Spacer(Modifier.width(4.dp)); StatusChip("제안", Tone.WAIT) }
            }
            Spacer(Modifier.width(6.dp))
            when (p.stance) {
                "sender" -> StatusChip("올린 분", Tone.QUIET)
                "ok" -> StatusChip("맞았어요", Tone.GOOD)
                "bad" -> StatusChip("달라요", Tone.BAD)
            }
            if (p.verified) {
                Spacer(Modifier.width(4.dp))
                StatusChip("명세서 확인", Tone.WAIT)
            }
            Spacer(Modifier.weight(1f))
            Text(ago(p.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, "더 보기", Modifier.size(18.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (p.mine) DropdownMenuItem(text = { Text("지우기") }, onClick = { menu = false; onDelete() })
                    else DropdownMenuItem(text = { Text("신고하기") }, onClick = { menu = false; onFlag() })
                    // [COMM-09] Play's UGC rule: users can block one another.
                    if (!p.mine && p.author != null) DropdownMenuItem(text = { Text("이 사람 글 안 보기") }, onClick = { menu = false; onHide() })
                }
            }
        }
        // [COMM-11] I2: one 개인 정보 신고 marks the post 확인 중, dimmed, until an admin looks.
        if (p.review && p.hidden == null) {
            Text("확인 중 · 개인 정보가 있다는 신고가 있어 운영자가 보고 있어요", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 2.dp))
        }
        Text(p.body, style = MaterialTheme.typography.bodyMedium,
            color = if (p.hidden != null || p.review) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        p.hidden?.let {
            Text("다른 분께는 가려졌어요: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp))
            // [COMM-11] G2: the writer can ask for another look; the question names the post.
            if (p.mine) TextButton(onClick = { com.bamnat.app.ui.settings.InquiryLinks.ask(appealStart(p.id)) },
                contentPadding = PaddingValues(horizontal = 0.dp)) { Text("이의 제기") }
        }
        // [PLZ-09] A proposal's lines: what, now → as it should be, why.
        if (p.change.isNotEmpty()) {
            SoftCard(Modifier.padding(top = 4.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    p.change.forEach { c ->
                        Text("${com.bamnat.app.company.Plaza.CHANGE_FIELDS[c.field] ?: c.field}: ${if (c.now.isNotBlank()) "${c.now} → " else ""}${c.want}",
                            style = MaterialTheme.typography.bodyMedium)
                        if (c.why.isNotBlank()) Text("왜: ${c.why}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    p.proposal?.let { r ->
                        Text(if (r.adopted) "이 제안대로 규칙이 바뀌었어요" else "이 제안에 맞아요 ${r.persons}명 · 모이면 규칙이 바뀌어요",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (p.hidden == null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (p.mine) {
                    Text("맞아요 ${p.agree} · 달라요 ${p.disagree}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    AgreeRow(p.agree, p.disagree, p.myVote, onVote)
                }
                Spacer(Modifier.weight(1f))
                onReply?.let { TextButton(onClick = it) { Text("답글") } }
            }
        }
    }
}

private fun blankChange() = com.bamnat.app.company.ChangeLine("shift", "", "", "")

/** [COMM-11] G2 이의 제기: 문의하기 started with the post's id at the front, so the admins find it at once. */
internal fun appealStart(postId: String) = com.bamnat.app.ui.settings.InquiryStart(
    about = "이의 $postId", kind = com.bamnat.app.company.InquiryKind.OTHER,
    text = "가려진 제 글을 다시 봐 주세요. 규칙에 맞는 이유: ",
)

/**
 * [PLZ-09] A proposal written so anyone can read it at a glance: up to six lines of what, how it is now,
 * how it should be and why. With [withSetup] the app's own setup (no amounts) goes with it as the rule
 * people vote on; 맞아요 from enough people then makes it the next version.
 */
@Composable
private fun ProposalEditor(
    change: List<com.bamnat.app.company.ChangeLine>,
    onChange: (List<com.bamnat.app.company.ChangeLine>) -> Unit,
    withSetup: Boolean,
    onWithSetup: (Boolean) -> Unit,
) {
    // Kept short so the talk above stays in sight; the lines scroll on their own.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
        change.forEachIndexed { i, c ->
            SoftCard {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    var open by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            TextButton(onClick = { open = true }) { Text("바꿀 것: ${com.bamnat.app.company.Plaza.CHANGE_FIELDS[c.field]} ▾") }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                com.bamnat.app.company.Plaza.CHANGE_FIELDS.forEach { (k, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChange(change.toMutableList().also { it[i] = c.copy(field = k) }) })
                                }
                            }
                        }
                        if (change.size > 1) TextButton(onClick = { onChange(change.filterIndexed { j, _ -> j != i }) }) { Text("빼기") }
                    }
                    OutlinedTextField(c.now, { v -> onChange(change.toMutableList().also { it[i] = c.copy(now = v.take(80)) }) }, singleLine = true,
                        label = { Text("지금 규칙 (선택)") }, placeholder = { Text("예: 주간 07:00~19:00") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(c.want, { v -> onChange(change.toMutableList().also { it[i] = c.copy(want = v.take(80)) }) }, singleLine = true,
                        label = { Text("이렇게 바꿔요") }, placeholder = { Text("예: 주간 07:00~18:00") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(c.why, { v -> onChange(change.toMutableList().also { it[i] = c.copy(why = v.take(160)) }) },
                        label = { Text("왜 (선택)") }, placeholder = { Text("예: 10월부터 한 시간 줄었어요") }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (change.size < 6) TextButton(onClick = { onChange(change + blankChange()) }) { Text("한 줄 더") }
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onWithSetup(!withSetup) }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = withSetup, onCheckedChange = onWithSetup)
            Text("내 앱 설정(금액 빼고)을 고친 규칙으로 함께 보내요. 맞아요가 모이면 이 설정이 다음 버전이 돼요.", style = MaterialTheme.typography.bodySmall)
        }
        Text("금액 대신 ‘기본급의 10%’, ‘1.5배’처럼 비율로 적어 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("M월 d일")

private fun day(at: Instant): String = dayFormat.format(at.atZone(ZoneId.systemDefault()))

/** 방금, 5분 전, 3시간 전, 2일 전, then the date. */
internal fun ago(at: Instant, now: Instant = Instant.now()): String {
    val d = Duration.between(at, now)
    return when {
        d.isNegative || d.toMinutes() < 1 -> "방금"
        d.toHours() < 1 -> "${d.toMinutes()}분 전"
        d.toDays() < 1 -> "${d.toHours()}시간 전"
        d.toDays() < 7 -> "${d.toDays()}일 전"
        else -> day(at)
    }
}
