package com.bamnat.app.ui.calendar

import androidx.compose.ui.semantics.selected
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.data.TeamStore
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.CoverFinder
import com.bamnat.core.schedule.TeamRoster
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/*
 * [CREW-01] 모든 조 보기: every team of a 3조 2교대, 4조 2교대, 4조 3교대… rotation, day by day.
 * [CREW-05] 1.21 rebuilt it around what people open it for: what every team works today, the week ahead,
 * and [CREW-04] who can take my shift on a day I want off (대근 찾기). [CREW-02][CREW-03] My team is
 * always my own calendar, whichever place it has in the order, and the order is set in one tap.
 */

class TeamsViewModel(private val container: AppContainer, val calendarId: String) : ViewModel() {
    val snapshot: StateFlow<ScheduleSnapshot?> = container.scopeOf(calendarId).schedule.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val store: TeamStore get() = container.teams
    val rosters = store.rosters
    val rest = store.rest

    fun save(roster: TeamRoster) {
        com.bamnat.app.data.GuideStore.get(container.appContext).used("shift.teams")
        store.set(calendarId, roster)
        redrawWidgets()
    }
    fun reset() {
        store.set(calendarId, null)
        redrawWidgets()
    }

    /** [DAILY-03] 오늘 모든 조 follows the teams as set here. */
    private fun redrawWidgets() {
        container.appScope.launch { com.bamnat.app.widget.WidgetRefresher.refresh(container.appContext) }
    }
    fun setRest(minutes: Int) = store.setRest(calendarId, minutes)
}

/** [CREW-01] The rotation the teams follow on [today]: the one on screen, else the latest. */
internal fun rotationOf(snapshot: ScheduleSnapshot, today: LocalDate): WorkPattern.Rotation? =
    (snapshot.resolver.patternOn(today) as? WorkPattern.Rotation)
        ?: snapshot.patterns.lastOrNull { it is WorkPattern.Rotation } as? WorkPattern.Rotation

/** [CREW-02] The roster on screen: the saved one put back on my calendar, or one guessed from the rotation. */
internal fun rosterFor(saved: TeamRoster?, rotation: WorkPattern.Rotation): TeamRoster =
    (saved ?: TeamRoster.suggest(rotation)).anchored(rotation.steps.size)

private enum class TeamsView(val label: String) { WEEK("2주"), MONTH("한 달") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamsScreen(vm: TeamsViewModel, onBack: () -> Unit, onOpenPattern: () -> Unit, startDay: LocalDate? = null) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val rosters by vm.rosters.collectAsStateWithLifecycle()
    val rests by vm.rest.collectAsStateWithLifecycle()
    val today = com.bamnat.app.ui.calendar.rememberToday() // [CAL-25]
    var month by rememberSaveable { mutableStateOf(YearMonth.from(startDay ?: today)) }
    var view by rememberSaveable { mutableStateOf(TeamsView.WEEK) }
    // [CAL-30] Kept through a fold: a cover being looked for stays open when the phone is unfolded (I 리뷰 P15).
    var editing by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable(stateSaver = DateSaver) { mutableStateOf(startDay) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("모든 조") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            actions = {
                if (snapshot?.let { rotationOf(it, today) } != null) {
                    TextButton(onClick = { editing = true }) {
                        Icon(Icons.Outlined.Tune, null, Modifier.padding(end = 4.dp)); Text("조 설정")
                    }
                }
            },
        )
    }) { padding ->
        val snap = snapshot ?: return@Scaffold
        val rotation = rotationOf(snap, today)
        if (rotation == null) {
            Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("돌아가는 근무 주기가 있어야 조를 나눠 볼 수 있어요", style = MaterialTheme.typography.titleMedium)
                Text("주주야야비휴처럼 순서가 돌아가는 근무(3조 2교대, 4조 2교대 등)는 같은 순서를 조마다 며칠씩 늦게 돌아요. " +
                    "근무 주기를 먼저 넣어 주세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onOpenPattern) { Text("근무 주기 넣기") }
            }
            return@Scaffold
        }
        val saved = rosters[vm.calendarId]
        val roster = remember(saved, rotation) { rosterFor(saved, rotation) }
        val guessed = saved == null
        val restMinutes = rests[vm.calendarId] ?: CoverFinder.DEFAULT_REST_MINUTES
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            if (wide) {
                // Unfolded: today and the cover finder on the left, the table on the right.
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.42f).fillMaxSize().verticalScroll(rememberScrollState())) {
                        TodayTeams(roster, rotation, snap, today, guessed, onEdit = { editing = true })
                        CoverHint(onPick = { picked = today.plusDays(1) })
                        picked?.let { day ->
                            CoverPanel(day, roster, rotation, snap, restMinutes, vm::setRest, onClose = { picked = null })
                        }
                    }
                    Column(Modifier.weight(0.58f).fillMaxSize()) {
                        TableArea(view, { view = it }, month, { month = it }, roster, rotation, snap, today, picked) { picked = if (picked == it) null else it }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TodayTeams(roster, rotation, snap, today, guessed, onEdit = { editing = true })
                    TableArea(view, { view = it }, month, { month = it }, roster, rotation, snap, today, picked,
                        modifier = Modifier.weight(1f)) { picked = if (picked == it) null else it }
                }
                picked?.let { day ->
                    ModalBottomSheet(onDismissRequest = { picked = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            CoverPanel(day, roster, rotation, snap, restMinutes, vm::setRest, onClose = { picked = null })
                        }
                    }
                }
            }
        }
        if (editing) {
            TeamEditor(roster, rotation, snap, today, onSave = { vm.save(it); editing = false }, onReset = { vm.reset(); editing = false },
                onDismiss = { editing = false })
        }
    }
}

/** [CAL-30] A date kept as its day number through a configuration change; null stays null. */
internal val DateSaver = androidx.compose.runtime.saveable.Saver<LocalDate?, Long>(
    save = { it?.toEpochDay() ?: Long.MIN_VALUE },
    restore = { if (it == Long.MIN_VALUE) null else LocalDate.ofEpochDay(it) },
)

/**
 * [CAL-45] 모든 조 beside the month on an unfolded screen (I 리뷰 N5 b): the month's table on the calendar's own
 * month, the picked day marked, and 대근 찾기 under it for that day. The full screen stays one tap away.
 */
@Composable
fun TeamsPanel(calendarId: String, snap: ScheduleSnapshot, month: YearMonth, selected: LocalDate, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val vm = com.bamnat.app.ui.appViewModel(key = "teams_$calendarId") { TeamsViewModel(it, calendarId) }
    val rosters by vm.rosters.collectAsStateWithLifecycle()
    val rests by vm.rest.collectAsStateWithLifecycle()
    val today = rememberToday()
    val rotation = rotationOf(snap, today) ?: return
    val roster = remember(rosters[calendarId], rotation) { rosterFor(rosters[calendarId], rotation) }
    var shown by rememberSaveable(month) { mutableStateOf(month) }
    var picked by rememberSaveable(selected, stateSaver = DateSaver) { mutableStateOf<LocalDate?>(null) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("모든 조", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("하루 보기") }
        }
        TableArea(TeamsView.MONTH, {}, shown, { shown = it }, roster, rotation, snap, selected, picked, modifier = Modifier.weight(1f)) {
            picked = if (picked == it) null else it
        }
        picked?.let { day ->
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                CoverPanel(day, roster, rotation, snap, rests[calendarId] ?: CoverFinder.DEFAULT_REST_MINUTES, vm::setRest, onClose = { picked = null })
            }
        }
    }
}

/** [CREW-05] Today: a card a team, in its shift's colour, mine first and marked; tomorrow under it. */
@Composable
private fun TodayTeams(roster: TeamRoster, rotation: WorkPattern.Rotation, snap: ScheduleSnapshot, today: LocalDate, guessed: Boolean, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("오늘 ${Fmt.dayTitle(today)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        val ordered = listOf(roster.mine) + roster.teams.filterNot { it.mine }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ordered.forEach { team ->
                val type = roster.shiftOn(rotation, team, today)?.let { snap.typesById[it] }?.takeIf { !it.isOff }
                val next = roster.shiftOn(rotation, team, today.plusDays(1))?.let { snap.typesById[it] }?.takeIf { !it.isOff }
                TeamCard(team, type, next)
            }
        }
        if (guessed) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("조는 근무 주기에서 짐작했어요. 우리 조가 몇 번째인지, 다른 조가 오늘 무엇인지 맞춰 주세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                    TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("맞추기") }
                }
            }
        }
    }
}

@Composable
private fun TeamCard(team: TeamRoster.Team, type: ShiftType?, next: ShiftType?) {
    val tint = type?.colorArgb?.asColor() ?: MaterialTheme.colorScheme.outline
    val desc = "${team.name}${if (team.mine) ", 내 조" else ""}, 오늘 ${type?.name ?: "쉬는 날"}, 내일 ${next?.name ?: "쉬는 날"}"
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (type == null) MaterialTheme.colorScheme.surfaceContainerLow else tint.copy(alpha = 0.14f),
        modifier = Modifier
            .widthIn(min = 92.dp)
            .then(if (team.mine) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = desc },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(team.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (team.mine) {
                    Spacer(Modifier.width(4.dp))
                    Text("내 조", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            ShiftBadge(type, text = if (type == null) "휴" else null, outlined = type == null, size = 36.dp)
            Text(type?.let { t -> if (t.hasTimes) "${t.name} ${Fmt.timeRange(t.start!!, t.end!!)}" else t.name } ?: "쉬는 날",
                style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text("내일 ${next?.name ?: "휴"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [CREW-04] On the unfolded screen, the way into 대근 찾기 before a day is picked. */
@Composable
private fun CoverHint(onPick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("휴가·대근 찾기", style = MaterialTheme.typography.titleSmall)
            Text("쉬고 싶은 날을 표에서 누르면 그날 내 근무를 대신 들어올 수 있는 조를 알려 드려요. 전날 야간을 한 조처럼 쉴 시간이 모자란 조는 이유와 함께 빼요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onPick, modifier = Modifier.heightIn(min = 48.dp)) { Text("내일로 찾아보기") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TableArea(
    view: TeamsView, onView: (TeamsView) -> Unit, month: YearMonth, onMonth: (YearMonth) -> Unit,
    roster: TeamRoster, rotation: WorkPattern.Rotation, snap: ScheduleSnapshot, today: LocalDate, picked: LocalDate?,
    modifier: Modifier = Modifier, onPick: (LocalDate) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                TeamsView.entries.forEachIndexed { i, v ->
                    SegmentedButton(selected = view == v, onClick = { onView(v) }, shape = SegmentedButtonDefaults.itemShape(i, TeamsView.entries.size)) { Text(v.label) }
                }
            }
        }
        val days = when (view) {
            TeamsView.WEEK -> (0L until 14L).map { today.plusDays(it) }
            TeamsView.MONTH -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
                    Text(Fmt.month(month), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onMonth(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
                }
                (1..month.lengthOfMonth()).map { month.atDay(it) }
            }
        }
        Text("날짜를 누르면 그날 대근할 수 있는 조를 찾아요", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        TeamTable(days, roster, rotation, snap, today, picked, onPick, scrollToToday = view == TeamsView.MONTH && YearMonth.from(today) == month,
            modifier = Modifier.weight(1f))
    }
}

/** The days a row each, a team to a column; my team's column is tinted, today is outlined. */
@Composable
private fun TeamTable(
    days: List<LocalDate>, roster: TeamRoster, rotation: WorkPattern.Rotation, snap: ScheduleSnapshot, today: LocalDate,
    picked: LocalDate?, onPick: (LocalDate) -> Unit, scrollToToday: Boolean, modifier: Modifier = Modifier,
) {
    val cal = LocalCalendarColors.current
    val list = rememberLazyListState()
    LaunchedEffect(days.firstOrNull(), scrollToToday) { if (scrollToToday) list.scrollToItem((today.dayOfMonth - 3).coerceAtLeast(0)) }
    val mineTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Columns share the width when they fit, and scroll sideways only with many teams on a narrow screen.
        val column = ((maxWidth - DATE_COLUMN - 16.dp) / roster.teams.size).coerceIn(52.dp, 96.dp)
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Row(Modifier.padding(horizontal = 8.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("날짜", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(DATE_COLUMN), textAlign = TextAlign.Center)
                roster.teams.forEach { t ->
                    Text(t.name + if (t.mine) " ★" else "", style = MaterialTheme.typography.labelMedium, fontWeight = if (t.mine) FontWeight.Bold else FontWeight.Normal,
                        color = if (t.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(column))
                }
            }
            HorizontalDivider()
            LazyColumn(state = list, modifier = Modifier.width(DATE_COLUMN + column * roster.teams.size + 16.dp)) {
                itemsIndexed(days, key = { _, d -> d.toEpochDay() }) { i, day ->
                    val holiday = KoreanHolidays.nameOf(day)
                    val red = holiday != null || day.dayOfWeek == DayOfWeek.SUNDAY
                    val shifts = roster.teams.map { t -> roster.shiftOn(rotation, t, day)?.let { snap.typesById[it] }?.takeIf { !it.isOff } }
                    val off = roster.teams.filterIndexed { j, _ -> shifts[j] == null }.joinToString(", ") { it.name }
                    if (i > 0 && day.dayOfMonth == 1) {
                        Text(Fmt.month(YearMonth.from(day)), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when (day) {
                                    picked -> MaterialTheme.colorScheme.secondaryContainer
                                    today -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else -> if (day.dayOfWeek == DayOfWeek.SATURDAY || red) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent
                                },
                            )
                            .clickable(onClickLabel = "대근 찾기") { onPick(day) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 4.dp)
                            .semantics { contentDescription = "${Fmt.dayTitle(day)}${holiday?.let { ", $it" } ?: ""}, 쉬는 조 ${off.ifEmpty { "없음" }}" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.width(DATE_COLUMN), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${day.dayOfMonth}", style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                                    color = if (red) cal.sunday else MaterialTheme.colorScheme.onSurface)
                                Text(" " + Fmt.dayOfWeekShort(day.dayOfWeek), style = MaterialTheme.typography.bodySmall,
                                    color = if (red) cal.sunday else if (day.dayOfWeek == DayOfWeek.SATURDAY) cal.saturday else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (day == today) Text("오늘", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            else holiday?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cal.sunday, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        roster.teams.forEachIndexed { j, t ->
                            Box(Modifier.width(column).heightIn(min = 40.dp).background(if (t.mine) mineTint else Color.Transparent),
                                contentAlignment = Alignment.Center) {
                                val type = shifts[j]
                                ShiftBadge(type, text = if (type == null) "휴" else null, outlined = type == null, size = 28.dp)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
            }
        }
    }
}

/**
 * [CREW-04] A picked day: my shift, then every other team, those who can come in first with why, and
 * a 대근 request naming them, to copy or send.
 */
@Composable
private fun CoverPanel(
    day: LocalDate, roster: TeamRoster, rotation: WorkPattern.Rotation, snap: ScheduleSnapshot,
    restMinutes: Int, onRest: (Int) -> Unit, onClose: () -> Unit,
) {
    val context = LocalContext.current
    val myDay = remember(snap, day) { snap.resolver.resolve(day) }
    val result = remember(roster, rotation, snap, day, restMinutes) {
        CoverFinder.find(roster, rotation, snap.typesById, day, myDay.shiftType?.id, restMinutes)
    }
    val label = "${Fmt.monthDay(day)}(${Fmt.dayOfWeekShort(day.dayOfWeek)})"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label 대근 찾기", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onClose) { Text("닫기") }
        }
        val mine = result.myShift
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShiftBadge(mine, text = if (mine == null) "휴" else null, outlined = mine == null, size = 32.dp)
                Text(
                    if (mine == null) "이 날은 쉬는 날이에요. 다른 조가 그날 무엇을 하는지만 보여 드려요."
                    else "내 근무: ${mine.name}${if (mine.hasTimes) " " + Fmt.timeRange(mine.start!!, mine.end!!) else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (mine != null) {
            val can = result.candidates
            Text(
                if (can.isEmpty()) "쉴 시간까지 맞는 조가 없어요. 아래 이유를 보고 조끼리 바꾸는 방법을 찾아보세요."
                else "${can.joinToString(", ") { it.team.name }} — 대근을 부탁할 수 있어요",
                style = MaterialTheme.typography.titleSmall,
                color = if (can.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        result.options.forEach { o -> OptionRow(o, mine != null) }
        // [SCHED-07] I 리뷰 N1: or swap, both ways: a team takes my day, I take one of theirs back.
        if (mine != null) {
            val swaps = remember(roster, rotation, snap, day, restMinutes) {
                com.bamnat.core.schedule.SwapFinder.find(roster, rotation, snap.typesById, day, myDay.shiftType?.id, restMinutes)
            }
            var picked by rememberSaveable(day) { mutableStateOf<Int?>(null) }
            Text("맞바꾸기", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            if (swaps.isEmpty()) {
                Text("2주 안에 쉴 시간까지 맞게 맞바꿀 날이 없어요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            swaps.forEachIndexed { i, sw ->
                val on = picked == i
                Surface(
                    onClick = { picked = if (on) null else i },
                    shape = MaterialTheme.shapes.medium,
                    color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().semantics { selected = on },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ShiftBadge(sw.theirShift, size = 28.dp)
                        Column(Modifier.weight(1f)) {
                            Text("${sw.team.name} · ${Fmt.monthDay(sw.takeDate)}(${Fmt.dayOfWeekShort(sw.takeDate.dayOfWeek)}) ${sw.theirShift.name}을 내가",
                                style = MaterialTheme.typography.bodyMedium)
                            Text(sw.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            picked?.let { swaps.getOrNull(it) }?.let { sw ->
                val swapText = sw.request { d -> "${Fmt.monthDay(d)}(${Fmt.dayOfWeekShort(d.dayOfWeek)})" }
                Text(swapText, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { copy(context, swapText) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.padding(end = 6.dp)); Text("맞바꾸기 글 복사")
                    }
                    OutlinedButton(onClick = { send(context, swapText) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Share, null, Modifier.padding(end = 6.dp)); Text("보내기")
                    }
                }
            }
        }
        if (mine != null) {
            RestRule(restMinutes, onRest)
            val text = result.request(label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { copy(context, text) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.padding(end = 6.dp)); Text("부탁 글 복사")
                }
                OutlinedButton(onClick = { send(context, text) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Share, null, Modifier.padding(end = 6.dp)); Text("보내기")
                }
            }
        }
    }
}

@Composable
private fun OptionRow(o: CoverFinder.Option, judged: Boolean) {
    val good = o.canCover
    val (icon, color) = when (o.verdict) {
        CoverFinder.Verdict.CAN -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        CoverFinder.Verdict.ALSO_WORKS -> Icons.Outlined.Schedule to MaterialTheme.colorScheme.tertiary
        CoverFinder.Verdict.UNKNOWN -> Icons.Outlined.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
        CoverFinder.Verdict.SHORT_REST -> Icons.Outlined.EventBusy to MaterialTheme.colorScheme.error
        CoverFinder.Verdict.BUSY -> Icons.Outlined.Block to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (good && judged) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (judged) Icon(icon, null, tint = color, modifier = Modifier.size(20.dp).padding(end = 2.dp))
                Text(" ${o.team.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (judged) Text(o.verdict.label, style = MaterialTheme.typography.labelMedium, color = color)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DayMini("어제", o.yesterday)
                DayMini("그날", o.today)
                DayMini("다음 날", o.tomorrow)
            }
            if (judged && o.reason.isNotEmpty()) {
                Text(o.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DayMini(label: String, type: ShiftType?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ShiftBadge(type, text = if (type == null) "휴" else null, outlined = type == null, size = 22.dp)
    }
}

/** [CREW-04] The rest a team needs around my shift, 8~16 hours (기본 11시간, 근로기준법 제59조 제2항). */
@Composable
private fun RestRule(minutes: Int, onRest: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("쉬는 시간 기준 ${minutes / 60}시간", style = MaterialTheme.typography.bodyMedium)
            Text("앞뒤 근무와 이만큼 떨어져야 들어올 수 있다고 봐요. 기본 11시간(근로기준법 제59조 제2항의 연속 휴식)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onRest(minutes - 60) }, enabled = minutes > CoverFinder.MIN_REST_SETTING) { Icon(Icons.Filled.Remove, "한 시간 줄이기") }
        IconButton(onClick = { onRest(minutes + 60) }, enabled = minutes < CoverFinder.MAX_REST_SETTING) { Icon(Icons.Filled.Add, "한 시간 늘리기") }
    }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("대근", text))
    android.widget.Toast.makeText(context, "복사했어요. 단톡방에 붙여 넣으세요", android.widget.Toast.LENGTH_SHORT).show()
}

private fun send(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(intent, "대근 부탁 보내기")) }
}

/**
 * [CREW-01][CREW-02][CREW-03] The teams in order: which place is mine (one tap, the others move with it),
 * names, the order on screen, and what each other team works today. My team's today is my calendar's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamEditor(
    start: TeamRoster, rotation: WorkPattern.Rotation, snap: ScheduleSnapshot, today: LocalDate,
    onSave: (TeamRoster) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit,
) {
    var roster by remember { mutableStateOf(start) }
    val steps = rotation.steps
    val size = steps.size
    fun stepLabel(i: Int): String {
        val type = steps[i]?.let { snap.typesById[it] }
        val label = type?.label ?: "휴"
        // The same shift twice in a row reads 주1·주2, so a team on its second day is told apart.
        val same = (0 until size).filter { steps[it] == steps[i] }
        return if (same.size > 1) "$label${same.indexOf(i) + 1}" else label
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("조 설정") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("우리 조는 몇 번째인가요?", style = MaterialTheme.typography.titleSmall)
                        Text("고르면 그 조가 내 근무 주기를 그대로 따르고, 다른 조는 순서대로 저절로 맞춰져요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            roster.teams.indices.forEach { i ->
                                FilterChip(selected = roster.mineIndex == i, onClick = { roster = roster.inOrder(rotation, i) },
                                    label = { Text("${i + 1}번째 (${roster.teams[i].name})") })
                            }
                        }
                    }
                }
                item { HorizontalDivider() }
                items(roster.teams.size) { index ->
                    val team = roster.teams[index]
                    val todayStep = Math.floorMod(rotation.stepIndexOn(today) - team.lag, size)
                    Column(Modifier.fillMaxWidth().border(1.dp, if (team.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(22.dp))
                            OutlinedTextField(
                                value = team.name, singleLine = true, modifier = Modifier.weight(1f),
                                onValueChange = { v -> roster = roster.copy(teams = roster.teams.mapIndexed { i, t -> if (i == index) t.copy(name = v.take(12)) else t }) },
                                label = { Text(if (team.mine) "조 이름 (내 조)" else "조 이름") },
                            )
                            Column {
                                IconButton(onClick = { roster = roster.moved(index, index - 1) }, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, "${team.name} 위로") }
                                IconButton(onClick = { roster = roster.moved(index, index + 1) }, enabled = index < roster.teams.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "${team.name} 아래로") }
                            }
                        }
                        if (team.mine) {
                            Text("오늘 ${stepLabel(todayStep)} — 내 달력 그대로예요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("오늘 이 조는", style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (0 until size).forEach { i ->
                                    FilterChip(selected = todayStep == i, label = { Text(stepLabel(i)) }, onClick = {
                                        roster = roster.withToday(rotation, team.name, i, today)
                                    })
                                }
                            }
                            TextButton(onClick = { roster = roster.withMine(index, size) }) { Text("이 조가 내 조예요") }
                        }
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (roster.teams.size < TeamRoster.MAX_TEAMS) TextButton(onClick = {
                            val names = roster.teams.map { it.name }.toSet()
                            val name = ('A'..'H').map { "${it}조" }.firstOrNull { it !in names } ?: "새 조"
                            roster = roster.copy(teams = roster.teams + TeamRoster.Team(name, 0))
                        }) { Text("조 더하기") }
                        if (roster.teams.size > 2) TextButton(onClick = {
                            val dropped = roster.teams.dropLast(1)
                            roster = (if (dropped.none { it.mine }) roster.copy(teams = dropped.mapIndexed { i, t -> t.copy(mine = i == 0) }) else roster.copy(teams = dropped)).anchored(size)
                        }) { Text("마지막 조 빼기") }
                        TextButton(onClick = onReset) { Text("처음대로") }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = roster.teams.all { it.name.isNotBlank() } && roster.teams.map { it.name }.toSet().size == roster.teams.size,
                onClick = { onSave(roster.anchored(size)) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private val DATE_COLUMN = 64.dp
