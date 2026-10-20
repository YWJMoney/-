package com.bamnat.app.ui.work

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.Fmt
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.work.NthWeekday
import com.bamnat.core.work.ShopDays
import com.bamnat.core.work.ShopRules
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * [TYPE-03][TYPE-04] What a shop calendar needs instead of a shift order: the 영업 day kind with the
 * shop's hours, a weekly order of open days and 정기휴무, the monthly and holiday days off written
 * onto the calendar, and open days that passed with no sales turned into 임시휴무 by themselves.
 */
object ShopKeeper {
    /** The shop's day kinds: 영업 with its hours, and 단축영업 for a short day. */
    const val OPEN_NAME = "영업"
    const val SHORT_NAME = "단축영업"

    fun openType(types: List<ShiftType>): ShiftType? = types.firstOrNull { !it.isOff && it.name == OPEN_NAME }

    /** One change to shop days at a time, so a save and the catch-up on opening the app do not undo each other. */
    private val lock = Mutex()

    /** Saves [rules] for calendar [id]: its day kinds, weekly order and the days off the rules add or take back. */
    suspend fun apply(c: AppContainer, id: String, rules: ShopRules, today: LocalDate = LocalDate.now()) = lock.withLock {
        val schedule = c.calendars.repository(id)
        val types = schedule.shiftTypes.first()
        val open = openType(types)
        val openId = schedule.saveShiftType(
            (open ?: ShiftType(0, OPEN_NAME, "영", 0xFF27AE60, null, null, sortOrder = -2, icon = "store"))
                .copy(start = rules.open, end = rules.close, breakMinutes = open?.breakMinutes ?: 0),
        )
        if (types.none { it.name == SHORT_NAME }) {
            val shortEnd = rules.open.plusHours(maxOf(1L, java.time.Duration.between(rules.open, rules.close).toHours() / 2))
            schedule.saveShiftType(ShiftType(0, SHORT_NAME, "단", 0xFFE0A100, rules.open, shortEnd, sortOrder = -1, icon = "store"))
        }
        val weekly = WorkPattern.Weekly(today, DayOfWeek.entries.associateWith { d -> if (d in rules.closedWeekdays) null else openId })
        // From today on only: the weeks already lived keep the days they had, and their sales stats with them.
        if (schedule.patterns.first().isEmpty()) schedule.setFirstPattern(weekly, today) else schedule.changePatternFrom(weekly, today)
        // The monthly days off and holidays a weekly order cannot hold go onto the days themselves; the
        // ones the old rules put there and the new ones do not are opened again.
        val before = c.calendars.get(id)?.shop
        val to = today.plusDays(ShopDays.AHEAD_DAYS)
        val now = ShopDays.extraClosed(rules, today, to, KoreanHolidays::isHoliday).toSet()
        val was = before?.let { ShopDays.extraClosed(it, today, to, KoreanHolidays::isHoliday) }.orEmpty().toSet()
        val snapshot = schedule.snapshot.first()
        val byDate = snapshot.entries.associateBy { it.date }
        val close = now.filter { (byDate[it]?.override ?: ShiftOverride.None) == ShiftOverride.None }
        // Only days the old rules closed open again; a day the user closed by hand stays closed.
        val reopen = (was - now).filter { byDate[it]?.override == ShiftOverride.Off }
        if (close.isNotEmpty()) schedule.updateDays(close) { it.copy(override = ShiftOverride.Off) }
        if (reopen.isNotEmpty()) schedule.updateDays(reopen) { it.copy(override = ShiftOverride.None) }
        c.calendars.setShop(id, rules.copy(
            checkedUntil = before?.checkedUntil ?: today.minusDays(1),
            autoClosedDays = before?.autoClosedDays.orEmpty(),
        ))
    }

    /**
     * [TYPE-04] Open days that passed with nothing sold become 임시휴무, once each, in every shop
     * calendar. Run when the app comes to the front.
     */
    suspend fun catchUp(c: AppContainer, today: LocalDate = LocalDate.now()) = lock.withLock {
        val mainStyle = c.settingsRepository.current.workStyle
        for (cal in c.calendars.calendars.value) {
            val rules = cal.shop ?: continue
            if (c.calendars.styleOf(cal.id, mainStyle) != WorkStyle.BUSINESS) continue
            val schedule = c.calendars.repository(cal.id)
            val snapshot = schedule.snapshot.first()
            val byDate = snapshot.entries.associateBy { it.date }
            val book = c.calendars.money(cal.id).book.first()
            val sold = book.sales.filter { it.sales > 0 }.map { it.date }.toSet() + book.entries.filter { it.kind == com.bamnat.core.money.MoneyKind.INCOME }.map { it.date }
            // Only days that open by the weekly order: a day the user set to 단축영업 or anything else stays theirs.
            val closed = ShopDays.autoClosed(rules, today, { d ->
                (byDate[d]?.override ?: ShiftOverride.None) == ShiftOverride.None && snapshot.resolver.resolve(d).isWorkDay
            }) { it in sold }
            if (closed.isNotEmpty()) schedule.updateDays(closed) { it.copy(override = ShiftOverride.Off) }
            val keepFrom = today.minusDays(ShopDays.LOOKBACK_DAYS * 2)
            c.calendars.updateShop(cal.id) { r ->
                r.copy(checkedUntil = today.minusDays(1), autoClosedDays = (r.autoClosedDays + closed).filterTo(mutableSetOf()) { !it.isBefore(keepFrom) })
            }
        }
    }

    /**
     * [TYPE-04] Sales written on a day that was closed by itself open it again; a day the user or the
     * rules closed stays closed. Called wherever sales are saved, for the calendar they were saved in.
     */
    suspend fun salesSaved(c: AppContainer, calendarId: String, date: LocalDate, sales: Long) = lock.withLock {
        if (sales <= 0) return@withLock
        val rules = c.calendars.get(calendarId)?.shop ?: return@withLock
        if (date !in rules.autoClosedDays) return@withLock
        val schedule = c.calendars.repository(calendarId)
        val entry = schedule.snapshot.first().entries.firstOrNull { it.date == date }
        if (entry?.override == ShiftOverride.Off) schedule.updateDay(date) { it.copy(override = ShiftOverride.None) }
        c.calendars.updateShop(calendarId) { it.copy(autoClosedDays = it.autoClosedDays - date) }
    }
}

class ShopDaysViewModel(private val c: AppContainer, val calendarId: String) : ViewModel() {
    private val _rules = MutableStateFlow(c.calendars.get(calendarId)?.shop ?: ShopRules())
    val rules: StateFlow<ShopRules> = _rules.asStateFlow()
    val name: String get() = c.calendars.get(calendarId)?.name ?: PersonalCalendar.MAIN_NAME
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun edit(transform: (ShopRules) -> ShopRules) { _rules.value = transform(_rules.value) }

    fun save(onDone: (String) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            ShopKeeper.apply(c, calendarId, _rules.value)
            c.telemetry.event("shop_days")
            _busy.value = false
            onDone("영업일을 저장했어요. ${ShopDays.describe(_rules.value)}")
        }
    }
}

/** [TYPE-03] 영업일·정기휴무: the shop's hours and the days it is always closed. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShopDaysScreen(vm: ShopDaysViewModel, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Boolean?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("영업일·정기휴무") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "‘${vm.name}’ 달력의 영업일이에요. 정한 대로 달력이 채워지고, 쉬는 날은 휴무로 보여요. 하루만 다르면 달력에서 그날을 눌러 바꿔요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("영업시간", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("문 여는 시간 ${Fmt.time(rules.open)}")
                            }
                            OutlinedButton(onClick = { picking = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("닫는 시간 ${Fmt.time(rules.close)}")
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("매주 쉬는 요일 (정기휴무)", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DayOfWeek.entries.forEach { d ->
                                val on = d in rules.closedWeekdays
                                FilterChip(
                                    selected = on,
                                    onClick = { vm.edit { r -> r.copy(closedWeekdays = if (on) r.closedWeekdays - d else r.closedWeekdays + d) } },
                                    label = { Text(NthWeekday.DAY_NAMES[d.ordinal].take(1)) },
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("매달 쉬는 날", style = MaterialTheme.typography.titleMedium)
                        Text("둘째·넷째 월요일처럼 달마다 정해진 휴무예요.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MonthlyPicker(rules) { vm.edit { r -> r.copy(monthly = it) } }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        SwitchLine("공휴일은 쉬어요", "설·추석·대체공휴일까지 달력에 휴무로 넣어요", rules.holidaysClosed) { on ->
                            vm.edit { it.copy(holidaysClosed = on) }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SwitchLine(
                            "매출 없는 영업일은 휴무로",
                            "문 여는 날인데 매출을 적지 않고 지나가면 다음 날 휴무(임시휴무)로 바꿔요. 나중에 매출을 적으면 다시 영업일이 돼요.",
                            rules.autoClosed,
                        ) { on -> vm.edit { it.copy(autoClosed = on) } }
                    }
                }
                Text(ShopDays.describe(rules), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Button(onClick = { vm.save(onSaved) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (busy) "저장하는 중…" else "저장")
                }
            }
        }
    }
    picking?.let { opening ->
        com.bamnat.app.ui.components.TimePickerDialog(
            title = if (opening) "문 여는 시간" else "닫는 시간",
            initial = if (opening) rules.open else rules.close,
            onDismiss = { picking = null },
            presets = listOf(LocalTime.of(7, 0), LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0), LocalTime.of(17, 0), LocalTime.of(21, 0), LocalTime.of(22, 0), LocalTime.of(0, 0)),
            onConfirm = { t: LocalTime ->
                vm.edit { if (opening) it.copy(open = t) else it.copy(close = t) }
                picking = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthlyPicker(rules: ShopRules, onChange: (List<NthWeekday>) -> Unit) {
    var day by remember { mutableStateOf(rules.monthly.firstOrNull()?.day ?: DayOfWeek.MONDAY) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.entries.forEach { d ->
            FilterChip(selected = d == day, onClick = { day = d }, label = { Text(NthWeekday.DAY_NAMES[d.ordinal].take(1)) })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf(1, 2, 3, 4, 5, -1)).forEach { n ->
            val item = NthWeekday(n, day)
            val on = item in rules.monthly
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) rules.monthly - item else rules.monthly + item) },
                label = { Text(item.label.substringBefore(' ')) },
            )
        }
    }
    if (rules.monthly.isNotEmpty()) {
        Text("고른 날: " + rules.monthly.joinToString(", ") { it.label }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SwitchLine(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = onChange)
    }
}
