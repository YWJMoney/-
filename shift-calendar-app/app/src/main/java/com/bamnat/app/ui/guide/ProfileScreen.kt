package com.bamnat.app.ui.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.appContainer
import com.bamnat.app.data.GuideStore
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.components.FilterChip
import com.bamnat.app.ui.components.FormSheet
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.guide.FeatureRoute
import com.bamnat.core.guide.Place
import com.bamnat.core.journal.Journal
import com.bamnat.core.profile.Answer
import com.bamnat.core.profile.Fact
import com.bamnat.core.profile.FactGroup
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.FactText
import com.bamnat.core.profile.Missing
import com.bamnat.core.profile.Needs
import com.bamnat.core.profile.Question
import com.bamnat.core.profile.Questions
import com.bamnat.core.profile.Source
import com.bamnat.core.profile.StatusBoard
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** [EASY-08][OBS-03] One calendar's facts as they stand, what the features that are on still wait for, and the board. */
internal data class ProfileView(val facts: Map<FactKey, Fact<*>>, val missing: List<Missing>, val board: StatusBoard, val hasPay: Boolean)

/** [EASY-08] Read again whenever an answer, a switch, the pay or the settings change; [tick] after an edit here. */
@Composable
internal fun rememberProfile(c: AppContainer, calendar: String, tick: Int = 0): ProfileView? {
    val context = LocalContext.current
    val guide by remember { GuideStore.get(context) }.state.collectAsStateWithLifecycle()
    val asks by c.asks.states.collectAsStateWithLifecycle()
    val settings by c.settingsRepository.settings.collectAsStateWithLifecycle()
    val calendars by c.calendars.calendars.collectAsStateWithLifecycle()
    val pay by remember(calendar) { c.scopeOf(calendar).pay.profile }.collectAsStateWithLifecycle()
    val view by produceState<ProfileView?>(null, calendar, guide, asks, settings, calendars, pay, tick) {
        value = runCatching {
            val facts = c.profiles.facts(calendar)
            val style = c.calendars.styleOf(calendar, settings.workStyle)
            val on = FeatureRegistry.of(style).filter { !guide.isOff(it, calendar) }.map { it.id }
            val today = LocalDate.now()
            val missing = Needs.of(facts, on) { key -> daysUntil(key, pay?.period?.payDay, today) }
            ProfileView(facts, missing, StatusBoard.of(facts, missing), pay != null)
        }.getOrNull()
    }
    return view
}

/** The calendar the profile is about: the one on screen, or 내 달력 while the example is (예시의 정보는 내 정보가 아니에요). */
internal fun profileCalendar(active: String) = if (active == PersonalCalendar.EXAMPLE) PersonalCalendar.MAIN else active

/**
 * [EASY-08][OBS-03] The top of 설정: "알고 있는 것 12 · 추정 3 · 모르는 것 2", one tap from 내 정보, and
 * 밤낮이 한 일 under it. A tap on 모르는 것 asks the first of them right there.
 */
@Composable
fun EasyStatusCard(modifier: Modifier = Modifier) {
    val c = LocalContext.current.appContainer
    val active by c.calendars.active.collectAsStateWithLifecycle()
    val calendar = profileCalendar(active)
    var tick by remember { mutableIntStateOf(0) }
    val view = rememberProfile(c, calendar, tick)
    val entries by c.journal.entries.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { c.journal.load() } }
    var asking by remember { mutableStateOf<FactKey?>(null) }
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            HubLine(
                icon = { Icon(Icons.Outlined.Person, null) },
                title = "내 정보",
                summary = view?.board?.line ?: "읽는 중",
                onClick = { FeatureLinks.open(FeatureRoute(Place.PROFILE)) },
            )
            val unknown = view?.board?.unknown.orEmpty()
            if (unknown.isNotEmpty()) {
                TextButton(onClick = { asking = unknown.first() }, modifier = Modifier.padding(start = 52.dp).heightIn(min = 48.dp)) {
                    Text("모르는 것 ${unknown.size}개 · ${unknown.first().label}부터 알려 주기")
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            val today = LocalDate.now()
            val todays = entries.orEmpty().count { it.day(ZoneId.systemDefault()) == today }
            HubLine(
                icon = { Icon(Icons.Outlined.History, null) },
                title = "밤낮이 한 일",
                summary = if (todays > 0) "오늘 ${todays}가지 · 알람·백업·자료 갱신을 이유와 함께" else "알람·백업·자료 갱신처럼 알아서 한 일을 이유와 함께",
                onClick = { FeatureLinks.open(FeatureRoute(Place.JOURNAL)) },
            )
        }
    }
    val key = asking
    val fact = key?.let { view?.facts?.get(it) }
    if (key != null && fact != null && view != null) {
        FactEditSheet(c, calendar, fact, view.hasPay, onSaved = { tick++ }, onDismiss = { asking = null })
    }
}

@Composable
private fun HubLine(icon: @Composable () -> Unit, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * [EASY-08] 설정 › 내 정보: every fact of the calendar on one screen, by group, each fixed where it is tapped.
 * A group with nothing to look at is folded. [EASY-09] 모두 처음부터 at the end, taken back for 7 days.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val c = LocalContext.current.appContainer
    val active by c.calendars.active.collectAsStateWithLifecycle()
    val calendars by c.calendars.calendars.collectAsStateWithLifecycle()
    val calendar = profileCalendar(active)
    var tick by remember { mutableIntStateOf(0) }
    val view = rememberProfile(c, calendar, tick)
    var editing by remember { mutableStateOf<FactKey?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resetAt by produceState<java.time.Instant?>(null, calendar, tick) { value = c.profiles.startedOver(calendar) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("내 정보") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("알려 주신 정보는 여기 한곳에 모여요. 누르면 그 자리에서 고치고, 고친 값은 쓰는 기능에 바로 적용돼요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val mine = calendars.filter { it.ready && !it.isExample }
                if (mine.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        mine.forEach { cal -> FilterChip(selected = cal.id == calendar, onClick = { c.calendars.setActive(cal.id) }, label = { Text(cal.name) }) }
                    }
                }
                if (active == PersonalCalendar.EXAMPLE) {
                    Text("지금은 예시 달력을 보고 있어요. 아래는 내 달력의 정보예요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (view == null) return@Column
                BoardCard(view.board) { editing = it }
                resetAt?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("처음부터 다시 묻고 있어요", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { c.profiles.undoStartOver(calendar, c.asks); tick++ } }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("되돌리기")
                        }
                    }
                }
                val missingKeys = view.missing.map { it.key }.toSet()
                FactGroup.entries.forEach { group ->
                    val facts = view.facts.values.filter { it.key.group == group }
                    if (facts.isEmpty()) return@forEach
                    val attention = facts.count { it.key in missingKeys || it.estimated }
                    var open by rememberSaveable(group, calendar) { mutableStateOf(attention > 0) }
                    SectionCard(title = null) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open }
                                .semantics { heading(); stateDescription = if (open) "펼침" else "접힘" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(group.label, style = MaterialTheme.typography.titleMedium)
                                Text(if (attention > 0) "${facts.size}개 중 ${attention}개 확인해 주세요" else "${facts.size}개 · 모두 알아요",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                        }
                        if (open) facts.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider()
                            FactRow(f, missing = f.key in missingKeys) { editing = f.key }
                        }
                    }
                }
                SectionCard(title = "더 자세히") {
                    LinkLine("알람 점검 · 서버 연결 점검", FeatureRoute(Place.DIAGNOSTICS))
                    LinkLine("급여 ‘이렇게 계산해요’", FeatureRoute(Place.PAY))
                    LinkLine(com.bamnat.app.ui.settings.SUMMARY_ROW, FeatureRoute(Place.SETTINGS_PRIVACY, com.bamnat.app.ui.settings.SUMMARY_ROW))
                    LinkLine("밤낮이 한 일", FeatureRoute(Place.JOURNAL))
                }
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("모두 처음부터") }
                Text("넣어 둔 정보를 질문 카드로 하나씩 다시 물어요. 답하기 전까지는 지금 값을 그대로 쓰고, 근무·급여·돈 기록은 지우지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    val key = editing
    val fact = key?.let { view?.facts?.get(it) }
    if (key != null && fact != null && view != null) {
        FactEditSheet(c, calendar, fact, view.hasPay, onSaved = { tick++ }, onDismiss = { editing = null })
    }
    if (confirmReset) {
        FormSheet(
            onDismissRequest = { confirmReset = false },
            title = { Text("모두 처음부터 할까요?") },
            text = {
                Text("넣어 둔 정보와 ‘나중에’로 미룬 질문을 질문 카드로 하루 한두 개씩 처음부터 다시 물어요. 답하기 전까지는 지금 값을 그대로 써요. " +
                    "근무·급여·돈 기록은 지우지 않고, 7일 안에 되돌릴 수 있어요.")
            },
            confirmButton = {
                Button(onClick = {
                    confirmReset = false
                    scope.launch { c.profiles.startOver(calendar, c.asks); tick++ }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("처음부터") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("그만두기") } },
        )
    }
}

/** [OBS-03] The board: the three counts, and each fact still waited for as a chip that asks it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardCard(board: StatusBoard, onAsk: (FactKey) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(board.line, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (board.unknown.isEmpty()) {
                Text("켜 둔 기능에 필요한 정보는 모두 있어요.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("모르는 것을 누르면 바로 물어요.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    board.unknown.forEach { k -> FilterChip(selected = false, onClick = { onAsk(k) }, label = { Text(k.label) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FactRow(fact: Fact<*>, missing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = "고치기", onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(fact.key.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (missing && fact.known) "${FactText.of(fact)} · 한 번 확인해 주세요" else FactText.of(fact),
                style = MaterialTheme.typography.bodyMedium,
                color = if (missing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fact.known && fact.source != Source.TYPED) {
                Text(fact.origin(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (fact.estimated) Text("추정", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(end = 8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkLine(label: String, route: FeatureRoute) {
    TextButton(onClick = { FeatureLinks.open(route) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

/**
 * [EASY-08] The question of [key] for fixing it from 내 정보. The same as the card's ([Questions.of]), except the
 * alarm once it is set: one answer would put every shift's alarms to one time, so its own screen opens.
 */
internal fun editQuestion(key: FactKey, hasPay: Boolean, known: Boolean): Question =
    if (key == FactKey.ALARM_LEAD && known) Question(key, "근무마다 알람을 따로 맞췄을 수 있어서 알람 화면에서 고쳐요", Answer.Open("alarm.moveAll", "알람 시간 고치기"))
    else Questions.of(key, hasPay)

/**
 * [EASY-08] Fixing one fact where it was tapped: what it is now and where it came from, what a change reaches,
 * and the same chips, number or date as the question card. 맞아요 makes a guess the user's own.
 */
@Composable
fun FactEditSheet(c: AppContainer, calendar: String, fact: Fact<*>, hasPay: Boolean, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val question = editQuestion(fact.key, hasPay, fact.known)
    var failed by remember { mutableStateOf(false) }
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text(fact.key.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(question.text, style = MaterialTheme.typography.bodyLarge)
                Text(if (fact.known) "지금: ${FactText.of(fact)} · ${fact.origin()}" else "지금: ${FactText.NOT_KNOWN}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(FactText.applies(fact.key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                AnswerInput(
                    question,
                    onAnswer = { value ->
                        scope.launch {
                            if (c.answers.answer(calendar, fact.key, value)) { onSaved(); onDismiss() } else failed = true
                        }
                    },
                    onOpen = { feature -> onDismiss(); FeatureRegistry.get(feature)?.let(FeatureLinks::open) },
                    current = FactText.answerOf(fact),
                )
                if (failed) Text("저장하지 못했어요. 그 기능 화면에서 고쳐 주세요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                if (fact.estimated) {
                    TextButton(onClick = {
                        scope.launch { c.profiles.mark(calendar, fact.key, Source.TYPED, why = "확인함"); onSaved(); onDismiss() }
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("맞아요, 이대로") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** [OBS-02] The calendar's line under the question card: "오늘 밤낮이 한 일 3개 · 보기". Nothing on a day of only the usual. */
@Composable
fun JournalHomeLine(modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    val c = LocalContext.current.appContainer
    val entries by c.journal.entries.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { c.journal.load() } }
    val line = remember(entries, today) { entries?.let { Journal.todayLine(it, ZoneId.systemDefault(), today) } } ?: return
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "밤낮이 한 일 보기") { FeatureLinks.open(FeatureRoute(Place.JOURNAL)) }
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("보기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
