package com.bamnat.app.ui.company

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.bamnat.app.ui.components.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.company.Activity
import com.bamnat.app.company.Plaza
import com.bamnat.app.company.PlazaJob
import com.bamnat.app.company.Rate
import com.bamnat.app.company.RateGroup
import com.bamnat.core.money.TradeWages
import com.bamnat.app.company.RuleVersion
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.bamnat.core.time.Today

private fun won(v: Long): String = "%,d원".format(v)

private val dateFormat = DateTimeFormatter.ofPattern("yyyy년 M월 d일")

/** [PLZ-08] Who stands behind a rule set: how many sent it, checked it on a pay slip and use it, and the names they chose. */
@Composable
fun WhoLine(senders: Int, checked: Int, uses: Int, by: List<String>, checkedLong: Boolean = false) {
    val parts = listOfNotNull(
        senders.takeIf { it > 0 }?.let { "보낸 분 ${it}명" },
        checked.takeIf { it > 0 }?.let { "명세서 확인 ${it}명" },
        uses.takeIf { it > 0 }?.let { "쓰는 사람 ${it}명" },
    )
    if (parts.isEmpty() && by.isEmpty() && !checkedLong) return
    Column {
        // [COMM-11] N7: checked on pay slips from different networks in two different months.
        if (checkedLong) StatusChip("두 달 이상 확인됨", Tone.GOOD)
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (by.isNotEmpty()) Text("올린 분: ${by.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * [PLZ-12][PLZ-05] Which plaza, which 시·도 and in what order: chips the thumb reaches, one row each,
 * so no menu has to be found first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlazaFilterBar(
    filter: CompanyBoardViewModel.Filter,
    plazas: List<PlazaJob>,
    onChange: (CompanyBoardViewModel.Filter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter.job.isEmpty(), onClick = { onChange(filter.copy(job = "")) }, label = { Text("모든 광장") })
            Plaza.JOBS.forEach { (key, name) ->
                val n = plazas.firstOrNull { it.key == key }?.let { it.adopted + it.voting } ?: 0
                FilterChip(selected = filter.job == key, onClick = { onChange(filter.copy(job = if (filter.job == key) "" else key)) },
                    label = { Text(if (n > 0) "$name $n" else name) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            var open by remember { mutableStateOf(false) }
            Box {
                FilterChip(selected = filter.region.isNotEmpty(), onClick = { open = true }, label = { Text(if (filter.region.isEmpty()) "전국" else Plaza.region(filter.region)) })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("전국") }, onClick = { open = false; onChange(filter.copy(region = "")) })
                    Plaza.REGIONS.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onChange(filter.copy(region = key)) }) }
                }
            }
            listOf("recent" to "최근", "trust" to "믿을 만한", "used" to "많이 쓰는").forEach { (key, label) ->
                FilterChip(selected = filter.sort == key, onClick = { onChange(filter.copy(sort = key)) }, label = { Text(label) })
            }
        }
    }
}

/**
 * [PLZ-10] 내 월급으로 시험: this month worked out with my own setup and with the shared rules, side by
 * side, line by line. All on the phone; then 맞아요 or 달라요 with what my pay slip says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrialSheet(trial: Trial?, onClose: () -> Unit, onVote: (Boolean) -> Unit) {
    val t = trial ?: return
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("내 월급으로 시험", style = MaterialTheme.typography.titleLarge)
            Text("${t.month.monthValue}월 내 근무를 지금 내 설정과 이 규칙으로 각각 계산했어요. 기본급과 수당 금액은 내 것 그대로예요. 계산은 휴대폰 안에서만 해요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                t.loading -> CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                t.note != null -> Text(t.note, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp))
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TotalBox("지금 내 설정", t.now, Modifier.weight(1f))
                        TotalBox("이 규칙이면", t.then, Modifier.weight(1f))
                    }
                    val d = t.diff
                    Text(
                        when {
                            d == 0L -> "이 달은 똑같아요."
                            d > 0 -> "이 규칙이 ${won(d)} 더 많아요."
                            else -> "이 규칙이 ${won(-d)} 적어요."
                        },
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    )
                    val changed = t.lines.filter { it.now != it.then }
                    if (changed.isNotEmpty()) {
                        Text("달라지는 항목", style = MaterialTheme.typography.labelLarge)
                        changed.take(8).forEach { l ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(l.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text("${won(l.now)} → ${won(l.then)}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("명세서와 비교해 보고, 이 규칙이 우리 회사와 맞는지 알려 주세요.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onVote(true); onClose() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.ThumbUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("맞아요")
                        }
                        OutlinedButton(onClick = { onVote(false); onClose() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.ThumbDown, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("달라요")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalBox(label: String, amount: Long, modifier: Modifier) {
    SoftCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(won(amount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** [PLZ-04] How a rule grew: every version, how it came in and what changed from the one before. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(state: HistoryState?, onClose: () -> Unit) {
    val h = state ?: return
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("바뀐 기록", style = MaterialTheme.typography.titleLarge)
            Text("${h.title} · 채택된 버전은 지워지지 않고 여기에 남아요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                h.error != null -> Text(h.error)
                h.versions == null -> CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                else -> h.versions.forEach { VersionRow(it) }
            }
        }
    }
}

@Composable
private fun VersionRow(v: RuleVersion) {
    SoftCard(highlighted = v.state == "active") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("v${v.version}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when (v.state) {
                    "active" -> StatusChip("쓰는 중", Tone.GOOD)
                    "withdrawn" -> StatusChip("내려감", Tone.BAD)
                    else -> StatusChip("이전 버전", Tone.QUIET)
                }
            }
            Text(
                "${dateFormat.format(v.adoptedAt.atZone(Today.zone()))} · " + when (v.source) {
                    "amend" -> "고치자는 제안에 ${v.senders}명이 맞아요"
                    "auto" -> "${v.senders}명이 같은 규칙을 보내 채택"
                    else -> "개발자가 확인해 채택"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            v.removed.forEach { Text("− $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            v.added.forEach { Text("+ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (v.version == 1 && v.added.isEmpty()) Text("처음 채택된 규칙이에요", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** [PLZ-08] 내 활동: the name I am shown by, and what I sent, proposed and earned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitySheet(holder: ActivityHolder) {
    val open by holder.open.collectAsStateWithLifecycle()
    val o = open ?: return
    ModalBottomSheet(onDismissRequest = holder::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("내 활동", style = MaterialTheme.typography.titleLarge)
            val a = o.activity
            when {
                o.error != null -> Text(o.error)
                a == null -> CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                else -> ActivityBody(a, holder)
            }
        }
    }
}

@Composable
private fun ActivityBody(a: Activity, holder: ActivityHolder) {
    var nick by rememberSaveable { mutableStateOf(a.nick.orEmpty()) }
    var msg by remember { mutableStateOf<String?>(null) }
    Text("광장 별명", style = MaterialTheme.typography.titleSmall)
    Text("고르면 토론과 규칙의 ‘올린 분’에 참여자 번호 대신 보여요. 실명이나 연락처는 쓰지 마세요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = nick, onValueChange = { nick = it.take(12); msg = null }, singleLine = true, placeholder = { Text("예: 야간요정") },
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Button(onClick = { holder.setNick(nick) { e -> msg = e ?: if (nick.isBlank()) "별명을 지웠어요" else "별명을 정했어요" } }) { Text("저장") }
    }
    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    HorizontalDivider()
    PlazaPhoneSettings()
    HorizontalDivider()
    Text("보낸 규칙 ${a.reports.size}", style = MaterialTheme.typography.titleSmall)
    if (a.reports.isEmpty()) Text("아직 없어요", style = MaterialTheme.typography.bodySmall)
    a.reports.forEach { r ->
        Text("${r.company} · ${r.dept.ifEmpty { "회사 전체" }} — " + when (r.status) {
            "adopted" -> "채택${r.version?.let { " v$it" } ?: ""}, 쓰는 사람 ${r.uses}명"
            "pending" -> "투표 중 (맞아요 ${r.persons}명${if (r.bad > 0) " · 달라요 ${r.bad}명" else ""})"
            "rejected" -> "반려"
            "withdrawn" -> "내려감"
            else -> r.status
        }, style = MaterialTheme.typography.bodyMedium)
    }
    if (a.proposals.isNotEmpty()) {
        Text("고치자는 제안 ${a.proposals.size}", style = MaterialTheme.typography.titleSmall)
        a.proposals.forEach { p -> Text("${p.company} — 맞아요 ${p.agree} · 달라요 ${p.disagree}", style = MaterialTheme.typography.bodyMedium) }
    }
    if (a.rates.isNotEmpty()) {
        Text("올린 시세 ${a.rates.size}", style = MaterialTheme.typography.titleSmall)
        a.rates.forEach { r ->
            Text("${r.trade} ${Plaza.unit(r.unit)} ${won(r.amount)} — ${if (r.status == "adopted") "채택" else "맞아요 ${r.agree} · 달라요 ${r.disagree}"}",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (a.rewards.isNotEmpty()) {
        Text("받은 보상", style = MaterialTheme.typography.titleSmall)
        a.rewards.forEach { w ->
            Text("프로 ${w.months}개월 · ${when (w.why) { "rate" -> "시세 채택"; "rule" -> "규칙 채택"; else -> "제안 반영" }} · ${if (w.status == "paid") "받음" else "지급 대기"}",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * [PLZ-14][PLZ-15] 임금표: what a trade is paid in a region per day, hour or month, from what people shared.
 * The adopted ones make 광장 시세 (low, middle, high); the rest wait for 맞아요 from people who know.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RatesScreen(vm: RatesViewModel, onBack: () -> Unit) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val rules = rememberPlazaRules()
    val c = androidx.compose.ui.platform.LocalContext.current.appContainer
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("임금표") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                // [SRV-04] Paused by the developer: said here, not after the form is filled.
                if (c.remoteConfig.current.paused(com.bamnat.core.ops.ServerSwitch.PLAZA_WRITE)) {
                    notice = com.bamnat.core.ops.ServerSwitch.pausedText(com.bamnat.core.ops.ServerSwitch.PLAZA_WRITE)
                } else rules.ensure { adding = true }
            }, text = { Text("시세 알려 주기") }, icon = { Icon(Icons.Filled.Add, null) })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Plaza.JOBS.forEach { (key, name) ->
                            FilterChip(selected = filter.job == key, onClick = { vm.setFilter(filter.copy(job = key)) }, label = { Text(name) })
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            FilterChip(selected = filter.region.isNotEmpty(), onClick = { open = true }, label = { Text(if (filter.region.isEmpty()) "전국" else Plaza.region(filter.region)) })
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                DropdownMenuItem(text = { Text("전국") }, onClick = { open = false; vm.setFilter(filter.copy(region = "")) })
                                Plaza.REGIONS.forEach { (k, n) -> DropdownMenuItem(text = { Text(n) }, onClick = { open = false; vm.setFilter(filter.copy(region = k)) }) }
                            }
                        }
                        FilterChip(selected = filter.unit.isEmpty(), onClick = { vm.setFilter(filter.copy(unit = "")) }, label = { Text("전부") })
                        Plaza.RATE_UNITS.forEach { (k, u) ->
                            FilterChip(selected = filter.unit == k, onClick = { vm.setFilter(filter.copy(unit = k)) }, label = { Text(u.name) })
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("같은 일을 하는 분들이 알려 준 시세예요. 서로 다른 분들이 맞아요라고 하면 광장 시세가 돼요. 이름·회사·명세서는 받지 않아요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                notice?.let { n -> item { Text(n, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) } }
                val b = state.board
                if (b != null && b.groups.isNotEmpty()) {
                    item { Text("광장 시세", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                    items(b.groups, key = { "${it.region}|${it.trade}|${it.unit}|${it.year}" }) { g -> GroupCard(g, onApply = { vm.applyToMyPay(g) { notice = it } }) }
                }
                if (b != null) {
                    item { Text("알려 준 시세", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                    if (b.entries.isEmpty()) item { Text("아직 없어요. 아는 시세를 처음 알려 주세요!", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp)) }
                    items(b.entries, key = { it.id }) { r ->
                        RateCard(r, onVote = { ok -> vm.vote(r, ok) }, onTakeBack = { vm.takeBack(r) { kept -> notice = if (kept) "채택된 시세는 남고, 나와의 연결만 지웠어요" else "되돌렸어요" } })
                    }
                }
                item {
                    when {
                        state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                        state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                            Text(state.error.orEmpty())
                            OutlinedButton(onClick = vm::load, modifier = Modifier.padding(top = 8.dp)) { Text("다시 불러오기") }
                        }
                    }
                }
            }
        }
    }
    if (adding) AddRateDialog(filter, onDismiss = { adding = false }, onSend = { trade, unit, amount, note, done ->
        vm.add(trade, unit, amount, note) { e -> done(e); if (e == null) { adding = false; notice = "보냈어요. 서로 다른 분들이 맞아요라고 하면 광장 시세가 돼요" } }
    })
}

@Composable
private fun GroupCard(g: RateGroup, onApply: () -> Unit) {
    SoftCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${g.trade} ${Plaza.unit(g.unit)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip("${Plaza.region(g.region)} · ${g.year}년", Tone.QUIET)
            }
            Text(won(g.mid), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = "가운데 값 ${won(g.mid)}" })
            Text(if (g.n > 1) "채택 ${g.n}건 · ${won(g.low)} ~ ${won(g.high)}" else "채택 1건", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            GovernmentWageLine(g)
            // [PLZ-11] The plaza's rate as my own pay, from this month.
            OutlinedButton(onClick = onApply) { Text("내 급여에 넣기") }
        }
    }
}

/**
 * [PAYR-06] Next to what people shared, the government-published 전국 평균 for the same trade (대한건설협회
 * 시중노임단가, 1일 8시간), grey and only for 일당: a reference point, not a check on anyone's pay. The table is
 * the signed `wages.json` ([TradeWages]), so a new half-year reaches phones without an update (순번 272).
 */
@Composable
private fun GovernmentWageLine(g: RateGroup) {
    if (g.unit != "day") return
    val t = TradeWages.of(g.trade) ?: return
    Text(
        "정부 공표 전국 평균 ${won(t.daily)} · ${TradeWages.SOURCE_SHORT} 시중노임단가 (8시간)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
    )
}

@Composable
private fun RateCard(r: Rate, onVote: (Boolean) -> Unit, onTakeBack: () -> Unit) {
    SoftCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.trade} ${Plaza.unit(r.unit)} ${won(r.amount)}", style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(Plaza.region(r.region), "${r.year}년", r.nick, ago(r.at)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (r.status) {
                    "adopted" -> StatusChip("채택", Tone.GOOD)
                    "review" -> StatusChip("확인 중", Tone.WAIT)
                    else -> StatusChip("투표 중", Tone.WAIT)
                }
            }
            if (r.note.isNotEmpty()) Text(r.note, style = MaterialTheme.typography.bodyMedium)
            // [COMM-11] I5: why the sender's own entry waits.
            if (r.mine && r.waitingWhy == "far") Text("다른 분들이 알려 준 시세와 차이가 커서 운영자가 한 번 보고 있어요. 맞으면 곧 투표에 올라가요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.mine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("내가 알려 준 시세 · 맞아요 ${r.agree} · 달라요 ${r.disagree}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onTakeBack) { Text("되돌리기") }
                }
            } else {
                AgreeRow(r.agree, r.disagree, r.myVote, onVote)
            }
        }
    }
}

/** [PLZ-01] 맞아요 · 달라요 with their counts; the one I picked is filled, and picking it again takes it back. */
@Composable
fun AgreeRow(agree: Int, disagree: Int, mine: Boolean?, onVote: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mine == true, onClick = { onVote(true) }, label = { Text("맞아요 $agree") },
            leadingIcon = { Icon(if (mine == true) Icons.Filled.Check else Icons.Outlined.ThumbUp, null, Modifier.size(16.dp)) })
        FilterChip(selected = mine == false, onClick = { onVote(false) }, label = { Text("달라요 $disagree") },
            leadingIcon = { Icon(if (mine == false) Icons.Filled.Check else Icons.Outlined.ThumbDown, null, Modifier.size(16.dp)) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRateDialog(filter: RatesViewModel.Filter, onDismiss: () -> Unit, onSend: (String, String, Long, String, (String?) -> Unit) -> Unit) {
    var trade by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(filter.unit.ifEmpty { if (filter.job == "hourly") "hour" else "day" }) }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("시세 알려 주기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${Plaza.job(filter.job)} · ${Plaza.region(filter.region)}", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(trade, { trade = it.take(20); error = null }, label = { Text("직종") }, placeholder = { Text("예: 형틀목수, 편의점") }, singleLine = true)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Plaza.RATE_UNITS.entries.forEachIndexed { i, (k, u) ->
                        SegmentedButton(selected = unit == k, onClick = { unit = k }, shape = SegmentedButtonDefaults.itemShape(i, Plaza.RATE_UNITS.size)) { Text(u.name) }
                    }
                }
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit).take(9); error = null }, label = { Text("${Plaza.unit(unit)} (원)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { amount.toLongOrNull()?.let { Text(won(it)) } })
                OutlinedTextField(note, { note = it.take(100); error = null }, label = { Text("한마디 (선택)") }, placeholder = { Text("예: 식대 별도, 숙련 3년") })
                Text("이름·회사·전화번호는 적지 마세요. 들은 시세여도 괜찮아요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                onSend(trade, unit, amount.toLongOrNull() ?: 0, note) { e -> busy = false; error = e }
            }) { Text("보내기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
