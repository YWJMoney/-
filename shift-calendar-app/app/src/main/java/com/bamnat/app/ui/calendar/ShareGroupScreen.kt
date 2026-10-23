package com.bamnat.app.ui.calendar

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.share.ReceivedCalendarStore
import com.bamnat.app.share.ShareGroup
import com.bamnat.app.share.ShareGroupBoard
import com.bamnat.app.share.ShareLinkException
import com.bamnat.app.share.ShareLinkKey
import com.bamnat.app.share.SharedSchedule
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.FilterChip
import com.bamnat.app.ui.components.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate

/** [SHR-14] 우리 조 모아 보기: start or join a 모임 코드 with one's own link, and see everyone's week side by side. */
class ShareGroupViewModel(private val c: AppContainer, private val today: () -> LocalDate = LocalDate::now) : ViewModel() {
    data class Ui(
        val code: String? = null,
        val members: List<ShareGroupBoard.Member> = emptyList(),
        val busy: Boolean = false,
        val message: String? = null,
    )

    private val _ui = MutableStateFlow(Ui(code = c.shareGroup.code.value))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** The user's own links that can go in, as (key, name) — the calendar's name and its link number. */
    fun ownLinks(): List<Pair<String, String>> = c.shareSync.allLinks().map { key ->
        val cal = c.calendars.get(ShareLinkKey.calendar(key))?.name ?: "내 달력"
        val slot = ShareLinkKey.slot(key)
        key to (if (slot > 1) "$cal 링크 $slot" else cal)
    }

    init { if (_ui.value.code != null) refresh() }

    fun start(ownKey: String) = act {
        val own = c.shareLinksOf(ownKey).link.value ?: throw ShareLinkException("no_link")
        val group = c.shareApi.groupStart(own)
        c.shareGroup.save(group.code, ownKey)
        c.telemetry.event("share_group", "start")
        load(group)
    }

    /** Joins [text]'s code with [ownKey], or only looks when [ownKey] is null. */
    fun join(text: String, ownKey: String?) = act {
        val code = ShareGroup.code(text) ?: throw ShareLinkException("bad_code")
        val own = ownKey?.let { c.shareLinksOf(it).link.value }
        val group = if (own != null) c.shareApi.groupJoin(code, own) else c.shareApi.groupRead(code)
        c.shareGroup.save(group.code, ownKey.takeIf { own != null })
        c.telemetry.event("share_group", if (own != null) "join" else "look")
        load(group)
    }

    fun refresh() = act {
        val code = c.shareGroup.code.value ?: return@act
        try {
            load(c.shareApi.groupRead(code))
        } catch (e: ShareLinkException) {
            if (e.message == "not_found") { c.shareGroup.clear(); _ui.value = Ui(message = "모임 코드가 끝났어요. 모든 링크가 끊기거나 기간이 지났어요.") }
            else throw e
        }
    }

    /** Takes the user's own link out (when it is in) and forgets the code here. */
    fun leave() = act {
        val code = c.shareGroup.code.value ?: return@act
        c.shareGroup.ownKey?.let { c.shareLinksOf(it).link.value }?.let { c.shareApi.groupLeave(code, it) }
        c.shareGroup.clear()
        _ui.value = Ui(message = "모임에서 나왔어요. 내 링크는 그대로 열려 있어요.")
    }

    /** Puts every coworker's link into 받은 달력, so each can be opened on its own too. */
    fun keepAll() {
        var added = 0
        for (m in _ui.value.members) {
            if (m.mine || c.received.get(m.id) != null) continue
            if (c.received.calendars.value.size >= ReceivedCalendarStore.MAX) break
            val raw = shares[m.id] ?: continue
            c.received.put(m.id, raw)
            added++
        }
        _ui.value = _ui.value.copy(message = if (added == 0) "모두 이미 받은 달력에 있어요." else "받은 달력에 ${added}개를 넣었어요.")
    }

    private val shares = HashMap<String, com.bamnat.app.share.ReceivedShare>()

    private suspend fun load(group: ShareGroup) {
        val mine = c.shareSync.allLinks().mapNotNull { c.shareLinksOf(it).link.value?.id }.toSet()
        val members = group.links.mapIndexed { i, id ->
            val kept = c.received.get(id)
            val schedule: SharedSchedule? = kept?.schedule ?: try {
                c.shareApi.read(id).also { shares[id] = it }.let { SharedSchedule.parse(it.schedule) }
            } catch (e: IOException) {
                null
            }
            val title = kept?.title ?: schedule?.title
            ShareGroupBoard.Member(id, if (id in mine) "나" else ShareGroupBoard.name(title, i), id in mine, schedule)
        }.sortedByDescending { it.mine }
        _ui.value = Ui(code = group.code, members = members)
    }

    private fun act(block: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                block()
            } catch (e: ShareLinkException) {
                _ui.value = _ui.value.copy(message = words(e.message))
            } catch (e: IOException) {
                _ui.value = _ui.value.copy(message = "인터넷에 연결되지 않았어요. 연결을 확인하고 다시 해 주세요.")
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    val week: LocalDate get() = today()

    companion object {
        fun words(code: String?): String = when (code) {
            "bad_code" -> "코드는 영문과 숫자 6자예요. 받은 그대로 넣어 주세요."
            "not_found" -> "그런 모임 코드가 없거나 끝났어요. 코드를 다시 확인해 주세요."
            "full" -> "이 모임은 링크 12개가 다 찼어요."
            "forbidden" -> "이 휴대폰이 만든 링크가 아니라 넣을 수 없어요."
            "rate_limited" -> "잠깐 사이에 너무 여러 번 했어요. 한 시간쯤 뒤에 다시 해 주세요."
            "no_link" -> "먼저 근무표 공유에서 내 링크를 만들어 주세요."
            "unsupported" -> "이 판에서는 모임 코드를 쓸 수 없어요."
            else -> "모임을 불러오지 못했어요 ($code). 잠시 뒤 다시 해 주세요."
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareGroupScreen(vm: ShareGroupViewModel, onBack: () -> Unit, onShare: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                title = { Text("우리 조 모아 보기") },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("동료마다 자기 근무표 링크를 만들고 같은 6자 코드에 넣으면, 한 주 근무를 나란히 봐요. 서버에는 링크 목록만 있고 이름·전화번호는 없어요. 모임은 가장 늦게 끝나는 링크와 함께 끝나요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val code = ui.code
            if (code == null) Join(vm, ui.busy, onShare) else Board(vm, ui, code)
            if (ui.busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun Join(vm: ShareGroupViewModel, busy: Boolean, onShare: () -> Unit) {
    val own = remember { vm.ownLinks() }
    var pick by rememberSaveable { mutableStateOf(own.firstOrNull()?.first) }
    var text by rememberSaveable { mutableStateOf("") }
    SectionCard(title = "넣을 내 링크") {
        if (own.isEmpty()) {
            Text("아직 내 링크가 없어요. 링크 없이 코드로 들어가 보기만 할 수도 있어요.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("근무표 공유에서 링크 만들기") }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                own.forEach { (key, name) -> FilterChip(selected = pick == key, onClick = { pick = key }, label = { Text(name) }) }
                FilterChip(selected = pick == null, onClick = { pick = null }, label = { Text("보기만") })
            }
        }
    }
    SectionCard(title = "새 모임 만들기") {
        Text("코드를 만들어 조원에게 보내요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = !busy && pick != null, onClick = { pick?.let(vm::start) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("모임 코드 만들기")
        }
    }
    SectionCard(title = "받은 코드로 들어가기") {
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(12) }, singleLine = true, label = { Text("모임 코드 6자") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(enabled = !busy && ShareGroup.code(text) != null, onClick = { vm.join(text, pick) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (pick == null) "보기만 하기" else "내 링크 넣고 들어가기") }
    }
}

@Composable
private fun Board(vm: ShareGroupViewModel, ui: ShareGroupViewModel.Ui, code: String) {
    val context = LocalContext.current
    var leaving by remember { mutableStateOf(false) }
    SectionCard(title = "모임 코드") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ShareGroup.invite(code))
                runCatching { context.startActivity(Intent.createChooser(send, "모임 코드 보내기")) }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("코드 보내기") }
        }
        Text("${ui.members.size}명 · 링크가 끊기거나 끝나면 저절로 빠져요", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val from = vm.week
    val rows = ShareGroupBoard.rows(ui.members, from)
    SectionCard(title = "이번 주 나란히") {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row {
                    Spacer(Modifier.width(NAME_WIDTH))
                    (0 until 7).forEach { i ->
                        val d = from.plusDays(i.toLong())
                        Text("${d.monthValue}/${d.dayOfMonth}\n${com.bamnat.app.ui.Fmt.dayOfWeekShort(d.dayOfWeek)}",
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(CELL_WIDTH).padding(4.dp))
                    }
                }
                HorizontalDivider()
                rows.forEach { r ->
                    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (r.mine) FontWeight.Bold else null,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(NAME_WIDTH).padding(end = 4.dp))
                        r.cells.forEach { cell ->
                            Text(cell ?: "—", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (cell == null || cell == "쉬는 날") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(CELL_WIDTH).padding(4.dp))
                        }
                    }
                }
            }
        }
        val together = ShareGroupBoard.restTogether(ui.members, from)
        Text(if (together.isEmpty()) "이번 주에는 모두 같이 쉬는 날이 없어요"
            else "모두 쉬는 날: " + together.joinToString(", ") { "${it.monthValue}/${it.dayOfMonth}" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !ui.busy, onClick = vm::refresh, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("새로 고침") }
        OutlinedButton(enabled = !ui.busy && ui.members.any { !it.mine }, onClick = vm::keepAll,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("받은 달력에 넣기") }
    }
    Spacer(Modifier.height(4.dp))
    TextButton(enabled = !ui.busy, onClick = { leaving = true }, modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)) {
        Text("모임에서 나가기", color = MaterialTheme.colorScheme.error)
    }
    if (leaving) ConfirmDialog(
        title = "모임에서 나갈까요?",
        message = "내 링크가 이 모임에서 빠지고, 이 휴대폰은 코드를 잊어요. 내 링크는 끊기지 않아요.",
        confirmLabel = "나가기",
        destructive = true,
        onDismiss = { leaving = false },
        onConfirm = { leaving = false; vm.leave() },
    )
}

private val NAME_WIDTH = 72.dp
private val CELL_WIDTH = 64.dp
