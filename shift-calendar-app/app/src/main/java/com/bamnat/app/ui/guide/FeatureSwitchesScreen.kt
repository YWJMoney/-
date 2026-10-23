package com.bamnat.app.ui.guide

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.data.CommutePrefs
import com.bamnat.app.data.GuideStore
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.settings.InquiryLinks
import com.bamnat.app.ui.settings.InquiryStart
import com.bamnat.core.guide.AppFeature
import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.guide.Off
import com.bamnat.core.work.WorkStyle
import java.time.LocalDate

/**
 * [GUIDE-05] 설정 › 기능 켜고 끄기: every feature that can be off, for the calendar picked at the top, in
 * groups — the guide, this calendar's cards, what the app does by itself, and the widget. A switch that
 * was there before (오늘의 흐름, 매출 없으면 휴무, 출퇴근 자동 기록, 결제 알림 읽기, 잠금 화면 오늘 근무)
 * is that same switch; one that needs a permission or a place opens where it is turned on.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeatureSwitchesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.appContainer
    val store = remember { GuideStore.get(context) }
    val guide by store.state.collectAsStateWithLifecycle()
    val settings by c.settingsRepository.settings.collectAsStateWithLifecycle()
    val calendars by c.calendars.calendars.collectAsStateWithLifecycle()
    val active by c.calendars.active.collectAsStateWithLifecycle()
    val commute by CommutePrefs.get(context).state.collectAsStateWithLifecycle()
    // 결제 알림 읽기 is allowed in the phone's settings: read again on coming back.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val style = c.calendars.styleOf(active, settings.workStyle)
    val name = calendars.firstOrNull { it.id == active }?.name.orEmpty()
    // [SET-05] The list is long: narrowed by a word and by on or off (I 리뷰 I17).
    var query by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableIntStateOf(SHOW_ALL) }
    val all = FeatureRegistry.of(style).filter { it.canTurnOff }
    // Read again whenever a switch changes, here or where it lives.
    val states = remember(all, guide, settings, calendars, commute, resumed, active) {
        all.associate { it.id to FeatureSwitches.isOn(c, it, active) }
    }
    val isOn: (AppFeature) -> Boolean = { f -> states[f.id] ?: true }
    val words = query.trim()
    val features = all.filter { f ->
        (words.isEmpty() || words in f.name || words in f.what || f.keywords.any { words in it }) &&
            when (show) { SHOW_ON -> isOn(f); SHOW_OFF -> !isOn(f); else -> true }
    }
    var message by remember { mutableStateOf<String?>(null) }
    // [EASY-09] A feature that is on but waits for a fact says which, and a tap asks it here.
    val profileOf = profileCalendar(active)
    var asked by remember { mutableIntStateOf(0) }
    val profile = rememberProfile(c, profileOf, asked)
    var asking by remember { mutableStateOf<com.bamnat.core.profile.FactKey?>(null) }
    val waits: (AppFeature) -> String? = { f -> if (!isOn(f)) null else profile?.facts?.let { com.bamnat.core.profile.FactText.startsWith(it, f.id) } }
    val ask: (AppFeature) -> Unit = { f -> asking = profile?.facts?.let { com.bamnat.core.profile.FactText.waiting(it, f.id).firstOrNull() } }
    val toggle: (AppFeature, Boolean) -> Unit = { f, on ->
        message = if (FeatureSwitches.set(c, f, active, on)) null else "‘${f.name}’은 켜는 곳에서 켜요. 그 화면을 열었어요"
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("기능 켜고 끄기") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("쓰지 않는 기능은 꺼 두면 화면에서 사라져요. 언제든 여기서 다시 켤 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // [ONBOARD-04][SIMPLE-02] The recommended features and their permissions, on one screen.
                SectionCard(title = "추천 기능 한 번에 켜기") {
                    Text("켜 둬도 괜찮은 알림과 필요한 허용을 한 화면에서 보고 바꿔요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { com.bamnat.app.ui.settings.SettingsLinks.go(com.bamnat.app.ui.settings.SettingsGo.RECOMMENDED) }) {
                        Text("추천 기능 보기")
                    }
                }
                if (calendars.size > 1) {
                    Text("설정할 달력", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        calendars.filter { it.ready }.forEach { cal ->
                            FilterChip(selected = cal.id == active, onClick = { c.calendars.setActive(cal.id) }, label = { Text(cal.name) })
                        }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it.take(20) }, singleLine = true,
                    label = { Text("기능 이름으로 찾기") }, modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(SHOW_ALL to "모두 ${all.size}", SHOW_ON to "켜진 것 ${all.count(isOn)}", SHOW_OFF to "꺼진 것 ${all.count { !isOn(it) }}")
                        .forEach { (k, label) -> FilterChip(selected = show == k, onClick = { show = k }, label = { Text(label) }) }
                }
                if (features.isEmpty()) {
                    Text("맞는 기능이 없어요. 찾는 말을 바꾸거나 ‘모두’를 골라 보세요.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                val guideRows = features.filter { it.id.startsWith("guide.") }
                if (guideRows.isNotEmpty()) SectionCard(title = "안내") {
                    guideRows.forEach { f -> SwitchLine(f, isOn(f), waits(f), { ask(f) }) { toggle(f, it) } }
                    TextButton(onClick = { store.restartChecklist(active, style, LocalDate.now()); message = "달력 맨 위에 ‘이 달력으로 할 수 있는 것’을 다시 보여 드려요" }) {
                        Text("‘$name’ 안내 카드 다시 보기")
                    }
                }
                val cards = features.filter { it.off == Off.CALENDAR }
                if (cards.isNotEmpty()) {
                    SectionCard(title = "‘$name’ 카드 (${(style ?: WorkStyle.SHIFT).label})") {
                        Text("이 달력에서만 꺼져요. 다른 달력은 그대로예요.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        cards.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider()
                            SwitchLine(f, isOn(f), waits(f), { ask(f) }) { toggle(f, it) }
                        }
                    }
                }
                val linked = features.filter { it.off == Off.ELSEWHERE }
                if (linked.isNotEmpty()) {
                    SectionCard(title = "알아서 하는 일 · 알림") {
                        Text("원래 있던 스위치와 같아요. 여기서 바꾸면 그곳도 바뀌어요.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        linked.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider()
                            SwitchLine(f, isOn(f), waits(f), { ask(f) }) { toggle(f, it) }
                        }
                    }
                }
                val app = features.filter { it.off == Off.APP && !it.id.startsWith("guide.") }
                if (app.isNotEmpty()) {
                    SectionCard(title = "위젯") { app.forEach { f -> SwitchLine(f, isOn(f), waits(f), { ask(f) }) { toggle(f, it) } } }
                }
                Text("다른 일하는 방식의 달력을 고르면 그 달력의 기능이 보여요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // [GUIDE-07] Not here: the help search finds it, and a 의견 goes from there.
                TextButton(onClick = { InquiryLinks.ask(InquiryStart(about = "기능 켜고 끄기", help = true)) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("찾는 기능이 없나요? · 기능 찾기와 의견 보내기")
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    val key = asking
    val fact = key?.let { profile?.facts?.get(it) }
    if (key != null && fact != null && profile != null) {
        FactEditSheet(c, profileOf, fact, profile.hasPay, onSaved = { asked++ }, onDismiss = { asking = null })
    }
}

private const val SHOW_ALL = 0
private const val SHOW_ON = 1
private const val SHOW_OFF = 2

/** One feature: name, what it does, where it is, and its switch. */
@Composable
private fun SwitchLine(feature: AppFeature, on: Boolean, waits: String?, onAsk: () -> Unit, onChange: (Boolean) -> Unit) {
    Column {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(feature.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { heading() })
            Text(feature.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("어디: ${feature.where}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = null)
    }
    if (waits != null) TextButton(onClick = onAsk, modifier = Modifier.heightIn(min = 48.dp)) { Text(waits) }
    }
}
