package com.bamnat.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.data.CalendarParts
import com.bamnat.app.data.EveryCalendar
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.work.CombinedView
import com.bamnat.core.work.WorkSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** [COMN-01] A month of every calendar's work: each day's pieces of work and the days where two calendars clash. */
private class CombinedMonth(val spans: Map<LocalDate, List<WorkSpan>>, val clashes: List<Pair<WorkSpan, WorkSpan>>) {
    val clashDays: Set<LocalDate> = clashes.flatMapTo(HashSet()) { (a, b) -> listOf(a.date, b.date) }

    companion object {
        /** The month and the day before it, so a night shift on the last day of the month before still clashes. */
        fun of(parts: List<CalendarParts>, month: YearMonth): CombinedMonth {
            val days = generateSequence(month.atDay(1).minusDays(1)) { it.plusDays(1) }.takeWhile { !it.isAfter(month.atEndOfMonth()) }.toList()
            val all = parts.flatMap { p -> days.flatMap { d -> CombinedView.spansOf(p.cal.id, p.snapshot.resolver.resolve(d), p.book.jobs) } }
            return CombinedMonth(all.groupBy { it.date }, CombinedView.clashes(all))
        }
    }
}

/**
 * [COMN-01] 모든 달력 합쳐 보기, from the calendar switcher once there are two calendars or more. Read
 * only: each day has a thin bar in each calendar's colour that has work that day, and a red dot when
 * two calendars' work overlaps on the clock (a night shift running into the next morning's other job
 * too). A tap on a day lists each calendar's work and time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CombinedScreen(onBack: () -> Unit) {
    val c = LocalContext.current.appContainer
    val calendars by c.calendars.calendars.collectAsStateWithLifecycle()
    val parts by remember(c) { EveryCalendar.parts(c) }.collectAsStateWithLifecycle(null)
    var month by rememberSaveable { mutableStateOf(c.shownMonth.month.value) }
    var picked by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val combined by produceState<CombinedMonth?>(null, parts, month) {
        val p = parts ?: return@produceState
        value = withContext(Dispatchers.Default) { CombinedMonth.of(p, month) }
    }
    val weekStart = c.settingsRepository.current.weekStart

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                title = { Text("모든 달력 합쳐 보기") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
                Text(Fmt.month(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
            }
            Legend(calendars)
            val m = combined
            if (m == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Grid(month, weekStart, m, calendars) { picked = it }
                if (m.clashDays.any { YearMonth.from(it) == month }) {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                        Text("빨간 점: 두 달력의 일하는 시간이 겹치는 날이에요", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            Text("보기만 하는 달력이에요. 근무를 고치려면 그 달력으로 바꿔서 고쳐요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
    }

    val day = picked
    val m = combined
    if (day != null && m != null) {
        ModalBottomSheet(onDismissRequest = { picked = null }) {
            DaySheet(day, m, calendars)
        }
    }
}

@Composable
private fun Legend(calendars: List<PersonalCalendar>) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        calendars.forEach { cal ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(14.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(cal.color.asColor()))
                Text(cal.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Grid(month: YearMonth, weekStart: DayOfWeek, m: CombinedMonth, calendars: List<PersonalCalendar>, onPick: (LocalDate) -> Unit) {
    val cal = LocalCalendarColors.current
    val lead = (month.atDay(1).dayOfWeek.value - weekStart.value + 7) % 7
    val cells = lead + month.lengthOfMonth()
    val rows = (cells + 6) / 7
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth()) {
        (0 until 7).forEach { i ->
            val dow = weekStart.plus(i.toLong())
            Text(Fmt.dayOfWeekShort(dow), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = when (dow) { DayOfWeek.SUNDAY -> cal.sunday; DayOfWeek.SATURDAY -> cal.saturday; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        }
    }
    for (r in 0 until rows) {
        Row(Modifier.fillMaxWidth()) {
            for (col in 0 until 7) {
                val n = r * 7 + col - lead + 1
                if (n < 1 || n > month.lengthOfMonth()) {
                    Spacer(Modifier.weight(1f))
                    continue
                }
                val date = month.atDay(n)
                val spans = m.spans[date].orEmpty()
                val working = calendars.filter { cal -> spans.any { it.calendar == cal.id } }
                val clash = date in m.clashDays
                val said = buildString {
                    append(Fmt.dayTitle(date))
                    if (working.isNotEmpty()) append(", " + working.joinToString { it.name })
                    if (clash) append(", 시간이 겹쳐요")
                }
                Column(
                    Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (date == today) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPick(date) }.semantics { contentDescription = said }.padding(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$n", style = MaterialTheme.typography.labelMedium.merge(NumberStyle))
                        if (clash) Box(Modifier.padding(start = 2.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    }
                    Spacer(Modifier.height(4.dp))
                    working.forEach { w ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 1.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(w.color.asColor()))
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySheet(day: LocalDate, m: CombinedMonth, calendars: List<PersonalCalendar>) {
    val spans = m.spans[day].orEmpty()
    // A clash told on this day: with work of this day or of the day before running into it.
    val clashes = m.clashes.filter { (a, b) -> a.date == day || b.date == day }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Fmt.dayTitle(day), style = MaterialTheme.typography.titleLarge)
        if (clashes.isNotEmpty()) {
            val names = { s: WorkSpan -> calendars.firstOrNull { it.id == s.calendar }?.name.orEmpty() }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp)) {
                Text("시간이 겹쳐요", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                clashes.forEach { (a, b) ->
                    Text("${names(a)} ${a.label} ${timeOf(a)} · ${names(b)} ${b.label} ${timeOf(b)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        if (spans.isEmpty()) {
            Text("이날은 어느 달력에도 일이 없어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        calendars.forEach { cal ->
            val mine = spans.filter { it.calendar == cal.id }
            if (mine.isEmpty()) return@forEach
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 4.dp)) {
                Box(Modifier.padding(top = 6.dp).width(4.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(cal.color.asColor()))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(cal.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    mine.forEach { s ->
                        Text("${s.label} · ${timeOf(s)}", style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** "22:00 – 07:00 (다음날)", or 시간 없음 for work without clock times. */
private fun timeOf(s: WorkSpan): String {
    val from = s.from
    val to = s.to
    if (from == null || to == null) return "시간 없음"
    return Fmt.timeRange(from.toLocalTime(), to.toLocalTime())
}
