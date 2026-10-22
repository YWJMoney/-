package com.bamnat.app.ui.calendar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.data.TeamStore
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.TeamRoster
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/*
 * [CREW-01] 모든 조 보기: every team of a 3조 2교대, 4조 2교대, 4조 3교대… rotation, day by day, for finding
 * who can cover a day or which team is on. The teams are the calendar's own rotation moved by a few
 * days; the user names them and says what each works today when the guess is off.
 */

class TeamsViewModel(private val container: AppContainer, val calendarId: String) : ViewModel() {
    val snapshot: StateFlow<ScheduleSnapshot?> = container.scopeOf(calendarId).schedule.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val store: TeamStore get() = container.teams
    val rosters = store.rosters

    fun save(roster: TeamRoster) {
        com.bamnat.app.data.GuideStore.get(container.appContext).used("shift.teams")
        store.set(calendarId, roster)
    }
    fun reset() = store.set(calendarId, null)
}

/** [CREW-01] The rotation the teams follow on [today]: the one on screen, else the latest. */
internal fun rotationOf(snapshot: ScheduleSnapshot, today: LocalDate): WorkPattern.Rotation? =
    (snapshot.resolver.patternOn(today) as? WorkPattern.Rotation)
        ?: snapshot.patterns.lastOrNull { it is WorkPattern.Rotation } as? WorkPattern.Rotation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamsScreen(vm: TeamsViewModel, onBack: () -> Unit, onOpenPattern: () -> Unit) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val rosters by vm.rosters.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(today)) }
    var editing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("모든 조 보기") },
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
        val roster = rosters[vm.calendarId] ?: remember(rotation) { TeamRoster.suggest(rotation) }
        val guessed = rosters[vm.calendarId] == null
        Column(Modifier.padding(padding).fillMaxSize()) {
            TodayTeams(roster, rotation, snap, today, guessed, onEdit = { editing = true })
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
                Text(Fmt.month(month), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
            }
            TeamTable(month, roster, rotation, snap, today, picked, onPick = { picked = if (picked == it) null else it }, modifier = Modifier.weight(1f))
            picked?.let { day -> CoverFinder(day, roster, rotation, snap, onClose = { picked = null }) }
        }
        if (editing) {
            TeamEditor(roster, rotation, snap, today, onSave = { vm.save(it); editing = false }, onReset = { vm.reset(); editing = false },
                onDismiss = { editing = false })
        }
    }
}

/** Today: what each team works, mine first, and who is off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayTeams(roster: TeamRoster, rotation: WorkPattern.Rotation, snap: ScheduleSnapshot, today: LocalDate, guessed: Boolean, onEdit: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("오늘 ${Fmt.monthDay(today)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                roster.teams.forEach { team ->
                    val type = roster.shiftOn(rotation, team, today)?.let { snap.typesById[it] }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(team.name + if (team.mine) "(내 조)" else "", style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (team.mine) FontWeight.Bold else FontWeight.Normal)
                        Spacer(Modifier.width(4.dp))
                        ShiftBadge(type, text = if (type == null) "휴" else null, outlined = type == null)
                    }
                }
            }
            if (guessed) {
                Text("조는 근무 주기에서 짐작했어요. 다른 조 근무가 다르면 ‘조 설정’에서 오늘 그 조의 근무를 골라 맞춰 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("조 이름·순서 맞추기") }
            }
        }
    }
}

/** The month a day to a row, a team to a column; my team's column is tinted. */
@Composable
private fun TeamTable(
    month: YearMonth, roster: TeamRoster, rotation: WorkPattern.Rotation, snap: ScheduleSnapshot, today: LocalDate,
    picked: LocalDate?, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier,
) {
    val cal = LocalCalendarColors.current
    val days = remember(month) { (1..month.lengthOfMonth()).map { month.atDay(it) } }
    val list = rememberLazyListState()
    LaunchedEffect(month) { if (YearMonth.from(today) == month) list.scrollToItem((today.dayOfMonth - 3).coerceAtLeast(0)) }
    val mineTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val scroll = rememberScrollState()
    Column(modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Row(Modifier.padding(horizontal = 8.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("날짜", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(DATE_COLUMN), textAlign = TextAlign.Center)
            roster.teams.forEach { t ->
                Text(t.name, style = MaterialTheme.typography.labelMedium, fontWeight = if (t.mine) FontWeight.Bold else FontWeight.Normal,
                    color = if (t.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center, modifier = Modifier.width(TEAM_COLUMN))
            }
        }
        HorizontalDivider()
        LazyColumn(state = list, modifier = Modifier.width(DATE_COLUMN + TEAM_COLUMN * roster.teams.size + 16.dp)) {
            items(days, key = { it.toEpochDay() }) { day ->
                val holiday = KoreanHolidays.nameOf(day) != null
                val red = holiday || day.dayOfWeek == DayOfWeek.SUNDAY
                val shifts = roster.teams.map { t -> roster.shiftOn(rotation, t, day)?.let { snap.typesById[it] } }
                val off = roster.teams.filterIndexed { i, _ -> shifts[i] == null }.joinToString(", ") { it.name }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (day == picked) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPick(day) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 8.dp)
                        .semantics { contentDescription = "${Fmt.monthDay(day)}, 쉬는 조 ${off.ifEmpty { "없음" }}" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.width(DATE_COLUMN), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text("${day.dayOfMonth}", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                            color = if (red) cal.sunday else MaterialTheme.colorScheme.onSurface)
                        Text(" " + Fmt.dayOfWeekShort(day.dayOfWeek), style = MaterialTheme.typography.bodySmall,
                            color = if (red) cal.sunday else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    roster.teams.forEachIndexed { i, t ->
                        Box(Modifier.width(TEAM_COLUMN).heightIn(min = 40.dp).background(if (t.mine) mineTint else androidx.compose.ui.graphics.Color.Transparent),
                            contentAlignment = Alignment.Center) {
                            val type = shifts[i]
                            ShiftBadge(type, text = if (type == null) "휴" else null, outlined = type == null, size = 26.dp)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
}

/** [CREW-01] A picked day: who works what and who is off, with a 대근 request ready to send. */
@Composable
private fun CoverFinder(day: LocalDate, roster: TeamRoster, rotation: WorkPattern.Rotation, snap: ScheduleSnapshot, onClose: () -> Unit) {
    val context = LocalContext.current
    val mine = roster.shiftOn(rotation, roster.mine, day)?.let { snap.typesById[it] }
    val off = roster.offOn(rotation, day).filterNot { it.mine }
    val text = buildString {
        append("${Fmt.monthDay(day)}(${Fmt.dayOfWeekShort(day.dayOfWeek)}) ")
        append(if (mine != null) "${mine.name} 대근 구해요" else "근무 바꿔 주실 분 구해요")
        if (off.isNotEmpty()) append(" · 이 날 쉬는 조: ${off.joinToString(", ") { it.name }}")
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${Fmt.monthDay(day)} 쉬는 조", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("닫기") }
            }
            Text(if (off.isEmpty()) "이 날은 모든 조가 근무해요" else off.joinToString(", ") { it.name } + " — 대근을 부탁할 수 있는 조예요",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { copy(context, text) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.padding(end = 6.dp)); Text("대근 부탁 글 복사")
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("대근", text))
    android.widget.Toast.makeText(context, "복사했어요. 단톡방에 붙여 넣으세요", android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * [CREW-01] Names, which team is mine, and what each team works today: people know "B조 오늘 야간 첫날",
 * not how many days behind me it runs. Two to eight teams.
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
                    Text("조마다 오늘 근무를 골라 주세요. 나머지 날은 근무 주기 순서대로 채워져요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(roster.teams.size) { index ->
                    val team = roster.teams[index]
                    val todayStep = Math.floorMod(rotation.stepIndexOn(today) - team.lag, size)
                    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = team.name, singleLine = true, modifier = Modifier.weight(1f),
                                onValueChange = { v -> roster = roster.copy(teams = roster.teams.mapIndexed { i, t -> if (i == index) t.copy(name = v.take(12)) else t }) },
                                label = { Text("조 이름") },
                            )
                            Row(Modifier.clickable { roster = roster.copy(teams = roster.teams.mapIndexed { i, t -> t.copy(mine = i == index) }) }
                                .heightIn(min = 48.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = team.mine, onClick = null)
                                Text("내 조", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("오늘 이 조는", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (0 until size).forEach { i ->
                                FilterChip(selected = todayStep == i, label = { Text(stepLabel(i)) }, onClick = {
                                    roster = roster.withToday(rotation, team.name, i, today)
                                })
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (roster.teams.size < TeamRoster.MAX_TEAMS) TextButton(onClick = {
                            val names = roster.teams.map { it.name }.toSet()
                            val name = ('A'..'H').map { "${it}조" }.firstOrNull { it !in names } ?: "새 조"
                            roster = roster.copy(teams = roster.teams + TeamRoster.Team(name, 0))
                        }) { Text("조 더하기") }
                        if (roster.teams.size > 2) TextButton(onClick = {
                            val dropped = roster.teams.dropLast(1)
                            roster = roster.copy(teams = if (dropped.none { it.mine }) dropped.mapIndexed { i, t -> t.copy(mine = i == 0) } else dropped)
                        }) { Text("마지막 조 빼기") }
                        TextButton(onClick = onReset) { Text("처음대로") }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = roster.teams.all { it.name.isNotBlank() } && roster.teams.map { it.name }.toSet().size == roster.teams.size,
                onClick = { onSave(roster) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private val DATE_COLUMN = 64.dp
private val TEAM_COLUMN = 64.dp

