package com.bamnat.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.components.contentColorOn
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.work.Heat
import com.bamnat.core.work.ShiftHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import com.bamnat.core.work.ShiftMonth
import java.time.temporal.TemporalAdjusters

/**
 * [CAL-22] 1년 달력: the twelve months of a year in small, each day in its shift's colour, with the year's
 * count of each shift and leave on top, for planning a holiday or a long stretch. A month opens the calendar.
 */
class YearViewModel(private val container: AppContainer, val calendarId: String) : ViewModel() {
    val snapshot: StateFlow<ScheduleSnapshot?> = container.scopeOf(calendarId).schedule.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val weekStart: DayOfWeek get() = container.settingsRepository.settings.value.weekStart

    fun openMonth(month: YearMonth) = container.shownMonth.show(month)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun YearScreen(vm: YearViewModel, onBack: () -> Unit) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val today = com.bamnat.app.ui.calendar.rememberToday() // [CAL-25]
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    // [CAL-44] 근무 shows the shifts' colours; 건강 the same days as a heat map of nights and short rests.
    var heatMap by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("1년 달력") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { padding ->
        val snap = snapshot ?: return@Scaffold
        // [CAL-41] A year of days is worked out off the main thread (I 리뷰 P17): with much leave and extra
        // work it took more than a frame on opening.
        val loaded by produceState<Pair<Map<LocalDate, ResolvedDay>, Map<LocalDate, Heat>>?>(null, snap, year) {
            value = withContext(Dispatchers.Default) {
                val from = LocalDate.of(year, 1, 1)
                val to = LocalDate.of(year, 12, 31)
                snap.resolver.resolveRange(from, to).associateBy { it.date } to ShiftHealth.heat(from, to, snap.resolver::resolve)
            }
        }
        val (days, heat) = loaded ?: (emptyMap<LocalDate, ResolvedDay>() to emptyMap())
        Column(Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 해") }
                Text("${year}년", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 해") }
            }
            YearCounts(snap, days.values)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !heatMap, onClick = { heatMap = false }, label = { Text("근무") })
                FilterChip(selected = heatMap, onClick = { heatMap = true }, label = { Text("야간·휴식") })
            }
            if (heatMap) HeatLegend(heat)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
                val columns = when {
                    maxWidth >= 840.dp -> 4
                    maxWidth >= 560.dp -> 3
                    else -> 2
                }
                // Rows of months, not a FlowRow: a flow row measures its items short and the weeks run into each other.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    (1..12).chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { m ->
                                val month = YearMonth.of(year, m)
                                MiniMonth(month, days, heat.takeIf { heatMap }, vm.weekStart, today, Modifier.weight(1f).align(Alignment.Top)) {
                                    vm.openMonth(month)
                                    onBack()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** [CAL-22] How many of each shift the year holds, and the leave taken, in the badges the calendar uses. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YearCounts(snap: ScheduleSnapshot, days: Collection<ResolvedDay>) {
    // [STAT-07] The month's count, a year long: a day taken off whole with leave is 휴가, not its code.
    val counts = ShiftMonth.counts(days, snap.shiftTypes).associate { it.type.id to it.days }
    // [CAL-26] 반차 is half a day and 반반차 a quarter, as the leave ledger counts them (I 리뷰 P5).
    val leave = ShiftMonth.leaveDays(days)
    if (counts.isEmpty()) {
        Text("이 해에는 넣은 근무가 없어요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center)
        return
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            snap.shiftTypes.filter { (counts[it.id] ?: 0) > 0 }.forEach { type ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShiftBadge(type)
                    Text(" ${counts[type.id]}일", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (leave > 0) Text("휴가 ${leaveDays(leave)}일", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MiniMonth(month: YearMonth, days: Map<LocalDate, ResolvedDay>, heat: Map<LocalDate, Heat>?, weekStart: DayOfWeek, today: LocalDate, modifier: Modifier, onOpen: () -> Unit) {
    val cal = LocalCalendarColors.current
    val scheme = MaterialTheme.colorScheme
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(weekStart))
    val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(first, month.atEndOfMonth()) / 7) + 1).toInt()
    val work = (1..month.lengthOfMonth()).count { days[month.atDay(it)]?.isWorkDay == true }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surface,
        modifier = modifier.border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.medium).clickable(onClickLabel = "${month.monthValue}월 달력 열기", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "${month.monthValue}월, 근무 ${work}일" },
    ) {
        Column(Modifier.padding(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${month.monthValue}월", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = if (YearMonth.from(today) == month) scheme.primary else scheme.onSurface)
                Text("  근무 ${work}일", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            repeat(weeks) { w ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { d ->
                        val date = first.plusDays((w * 7 + d).toLong())
                        val inMonth = YearMonth.from(date) == month
                        val day = days[date].takeIf { inMonth }
                        val type = day?.shiftType
                        val fill = when {
                            !inMonth -> Color.Transparent
                            heat != null -> heatColor(heat[date] ?: Heat.REST)
                            day?.leave != null -> scheme.tertiaryContainer
                            type != null && day.isWorkDay -> type.colorArgb.asColor().copy(alpha = 0.85f)
                            else -> Color.Transparent
                        }
                        val sunday = date.dayOfWeek == DayOfWeek.SUNDAY || KoreanHolidays.isHoliday(date)
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).padding(1.dp)
                                .background(fill, RoundedCornerShape(3.dp))
                                .then(if (date == today) Modifier.border(1.5.dp, scheme.primary, RoundedCornerShape(3.dp)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (inMonth) Text(
                                "${date.dayOfMonth}", fontSize = 9.sp, lineHeight = 9.sp, maxLines = 1,
                                color = when {
                                    // [CAL-42] Dark letters on 오후's orange or a light shift colour (I 리뷰 P23).
                                    fill != Color.Transparent -> contentColorOn(fill.compositeOver(scheme.surface))
                                    sunday -> cal.sunday
                                    else -> scheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** [CAL-26] 3, 2.5 or 0.75: whole days without ".0". */
internal fun leaveDays(days: Double): String =
    if (days % 1.0 == 0.0) days.toLong().toString() else java.math.BigDecimal(days).stripTrailingZeros().toPlainString()

/** [CAL-44] Rest is left blank; work, nights, long runs and short rests darken step by step. */
@Composable
private fun heatColor(h: Heat): Color {
    val cal = LocalCalendarColors.current
    val scheme = MaterialTheme.colorScheme
    return when (h) {
        Heat.REST -> Color.Transparent
        Heat.WORK -> scheme.surfaceContainerHighest
        Heat.NIGHT -> scheme.primary.copy(alpha = 0.55f)
        Heat.NIGHT_RUN -> scheme.primary
        Heat.SHORT_REST -> cal.warning
    }
}

/** [CAL-44] What the heat map's colours mean, with the year's count of each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeatLegend(heat: Map<LocalDate, Heat>) {
    val counts = heat.values.groupingBy { it }.eachCount()
    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (h in listOf(Heat.WORK, Heat.NIGHT, Heat.NIGHT_RUN, Heat.SHORT_REST)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 4.dp).size(14.dp).background(heatColor(h), RoundedCornerShape(3.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp)))
                Text("${h.label} ${counts[h] ?: 0}일", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
