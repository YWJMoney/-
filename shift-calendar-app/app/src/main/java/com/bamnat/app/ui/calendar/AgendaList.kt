package com.bamnat.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.work.AgendaRow
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.MonthAgenda
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * [CAL-08] The month as a list: each week with its total, each day that has something on it, and
 * the empty days between folded into one quiet line. Reads top to bottom with TalkBack and a big
 * font, where a grid of 42 small cells cannot. A tap picks the day; the calendar's pager turns the month.
 */
@Composable
internal fun AgendaList(
    month: YearMonth,
    focus: CalendarFocus,
    snapshot: ScheduleSnapshot,
    money: MoneyBook,
    memoCounts: Map<LocalDate, Int>,
    weekStart: DayOfWeek,
    today: LocalDate,
    selected: LocalDate,
    onDay: (LocalDate) -> Unit,
    onQuickAction: (LocalDate, QuickAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = month
    val rows = remember(m, focus, snapshot, money, memoCounts, weekStart) {
        MonthAgenda.of(m, focus, snapshot.resolver, money.jobs, money.sales, memoCounts, weekStart)
    }
    Column(modifier.fillMaxWidth()) {
        if (rows.none { it is AgendaRow.Day }) {
            Text(
                when (focus) {
                    CalendarFocus.SHIFTS -> "${m.monthValue}월에 적힌 근무가 없어요"
                    CalendarFocus.JOBS -> "${m.monthValue}월에 잡힌 일감이 없어요"
                    CalendarFocus.SALES -> "${m.monthValue}월에 적은 매출이 없어요"
                    CalendarFocus.INVEST -> "${m.monthValue}월 매매는 ‘투자·수익’ 탭의 기록에서 날짜별로 볼 수 있어요"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                textAlign = TextAlign.Center,
            )
        }
        for (row in rows) {
            when (row) {
                is AgendaRow.Week -> WeekRow(row, focus)
                is AgendaRow.Gap -> GapRow(row, focus)
                is AgendaRow.Day -> DayRow(row, focus, today, row.date == selected, onDay, onQuickAction)
            }
        }
    }
}

@Composable
private fun WeekRow(w: AgendaRow.Week, focus: CalendarFocus) {
    val total = when (focus) {
        CalendarFocus.SHIFTS -> w.minutes.takeIf { it > 0 }?.let { HoursFormat.korean(it) }
        CalendarFocus.JOBS -> if (w.jobs > 0) "일감 ${w.jobs}건 · ${Fmt.wonShort(w.jobEarned)}" else null
        CalendarFocus.SALES -> w.sales.takeIf { it > 0 }?.let { "매출 ${Fmt.wonShort(it)}" }
        CalendarFocus.INVEST -> null
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (w.start == w.end) Fmt.monthDay(w.start) else "${Fmt.monthDay(w.start)} – ${if (w.start.month == w.end.month) "${w.end.dayOfMonth}일" else Fmt.monthDay(w.end)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            total?.let {
                Text(it, style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun GapRow(g: AgendaRow.Gap, focus: CalendarFocus) {
    val range = if (g.days == 1) Fmt.monthDay(g.from) else "${g.from.dayOfMonth}일 – ${g.to.dayOfMonth}일"
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(8.dp))
        Text("$range · ${focus.gap(g.days)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayRow(
    row: AgendaRow.Day,
    focus: CalendarFocus,
    today: LocalDate,
    isSelected: Boolean,
    onDay: (LocalDate) -> Unit,
    onQuickAction: (LocalDate, QuickAction) -> Unit,
) {
    val cal = LocalCalendarColors.current
    val scheme = MaterialTheme.colorScheme
    val day = row.day
    val date = row.date
    val type = day.shiftType
    val isToday = date == today
    val red = row.holiday != null || date.dayOfWeek == DayOfWeek.SUNDAY
    val dateColor = when {
        isToday -> scheme.onPrimary
        red -> cal.sunday
        date.dayOfWeek == DayOfWeek.SATURDAY -> cal.saturday
        else -> scheme.onSurface
    }
    val showShift = focus == CalendarFocus.SHIFTS || day.isWorkDay || (type != null && !type.isOff) || day.leave != null
    val lines = buildList {
        if (showShift) add(shiftLine(day))
        day.extras.forEach { add(extraLine(it)) }
        row.jobs.forEach { j ->
            add((j.start?.let { Fmt.time(it) + " " } ?: "") + j.title +
                (if (j.days > 1) " · ${j.days}일 중 ${(date.toEpochDay() - j.date.toEpochDay()).toInt() + 1}일째" else "") +
                (if (j.date == date && j.unpaid > 0) " · 받을 돈 ${Fmt.wonShort(j.unpaid)}" else ""))
        }
        row.sales?.takeIf { it.sales != 0L || it.costs != 0L }?.let { s ->
            add("매출 ${Fmt.won(s.sales)}" + if (s.costs != 0L) " · 남은 돈 ${Fmt.wonShort(s.net)}" else "")
        }
    }
    val description = buildString {
        append(Fmt.dayTitle(date))
        if (isToday) append(", 오늘")
        row.holiday?.let { append(", ").append(it) }
        lines.forEach { append(", ").append(it) }
        if (day.alarmSkipped) append(", 알람 꺼짐")
        if (row.memos > 0) append(", 메모 ${row.memos}개")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (isSelected) scheme.primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "이 날 보기",
                onClick = { onDay(date) },
                onLongClickLabel = "자세히 열기",
                onLongClick = { onQuickAction(date, QuickAction.Open) },
            )
            .clearAndSetSemantics {
                contentDescription = description
                selected = isSelected
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The date: the number, the weekday under it; today in the primary circle as on the grid.
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (isToday) scheme.primary else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
                    fontWeight = FontWeight.SemiBold, color = dateColor)
            }
            Text(Fmt.dayOfWeekShort(date.dayOfWeek), style = MaterialTheme.typography.labelSmall,
                color = if (red) cal.sunday else scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            val fullLeave = day.leave?.takeIf { it.isFullDay }
            when {
                fullLeave != null -> LeaveMark(fullLeave, size = 22.dp)
                showShift && (type != null || day.hasCustomTimes) ->
                    ShiftBadge(type, text = type?.label ?: "근", size = 22.dp, outlined = type?.isOff == true, showIcon = false)
                row.jobs.isNotEmpty() -> JobBadge(row.jobs.size, owed = row.jobs.any { it.date == date && it.unpaid > 0 })
                row.sales != null -> Icon(Icons.Outlined.Storefront, null, tint = cal.income, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            row.holiday?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = cal.sunday) }
            if (lines.isEmpty() && row.holiday == null && row.memos == 0) {
                Text(focus.emptyDay, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            lines.forEachIndexed { i, l ->
                Text(l, style = if (i == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    color = if (i == 0) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (day.alarmSkipped) Icon(Icons.Filled.AlarmOff, null, tint = cal.alarmOff, modifier = Modifier.size(16.dp))
            if (row.memos > 0) {
                Icon(Icons.AutoMirrored.Outlined.StickyNote2, null, tint = cal.memoDot, modifier = Modifier.size(16.dp))
                if (row.memos > 1) Text("${row.memos}", style = MaterialTheme.typography.labelSmall, color = cal.memoDot)
            }
        }
    }
}
