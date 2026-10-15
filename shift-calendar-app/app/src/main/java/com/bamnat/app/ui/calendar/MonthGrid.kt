package com.bamnat.app.ui.calendar

import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.Leave
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.components.isDarkSurface
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.model.ShiftOverride
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ColorDot
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.schedule.DaySource
import com.bamnat.core.schedule.ResolvedDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

const val GRID_ROWS = 6

@Composable
fun WeekdayHeader(weekStart: DayOfWeek, modifier: Modifier = Modifier) {
    val cal = LocalCalendarColors.current
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        for (i in 0 until 7) {
            val dow = weekStart.plus(i.toLong())
            Text(
                Fmt.dayOfWeekShort(dow),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = when (dow) {
                    DayOfWeek.SUNDAY -> cal.sunday
                    DayOfWeek.SATURDAY -> cal.saturday
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** Shortcuts offered on a long press, so a day can be set without opening it. */
sealed interface QuickAction {
    data class Shift(val override: ShiftOverride) : QuickAction
    data class Extra(val kind: ExtraWork, val minutes: Int) : QuickAction
    data object ClearExtra : QuickAction
    /** 연차·반차·반반차, or null to remove it [FEAT-10]. */
    data class SetLeave(val leave: Leave?) : QuickAction
    data object Open : QuickAction
}

@Composable
fun MonthGrid(
    month: YearMonth,
    snapshot: ScheduleSnapshot,
    calculator: WorkHoursCalculator,
    selected: LocalDate,
    today: LocalDate,
    memoDates: Set<LocalDate>,
    showHours: Boolean,
    cellHeight: Dp,
    onDayClick: (LocalDate) -> Unit,
    onQuickAction: (LocalDate, QuickAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cal = LocalCalendarColors.current
    val firstCell = remember(month, calculator) { calculator.weekStartOf(month.atDay(1)) }
    Column(modifier.fillMaxWidth()) {
        for (row in 0 until GRID_ROWS) {
            val rowStart = firstCell.plusWeeks(row.toLong())
            val week = remember(calculator, rowStart) { calculator.week(rowStart) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(cellHeight)
                    .then(
                        if (week.overLimit) {
                            Modifier.background(cal.warningContainer.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        },
                    ),
            ) {
                for (col in 0 until 7) {
                    val date = rowStart.plusDays(col.toLong())
                    val day = remember(snapshot, date) { snapshot.resolver.resolve(date) }
                    DayCell(
                        day = day,
                        snapshot = snapshot,
                        inMonth = date.year == month.year && date.month == month.month,
                        isToday = date == today,
                        isSelected = date == selected,
                        hasMemo = date in memoDates,
                        showHours = showHours,
                        onClick = { onDayClick(date) },
                        onQuickAction = { onQuickAction(date, it) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    day: ResolvedDay,
    snapshot: ScheduleSnapshot,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    hasMemo: Boolean,
    showHours: Boolean,
    onClick: () -> Unit,
    onQuickAction: (QuickAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cal = LocalCalendarColors.current
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val date = day.date
    val type = day.shiftType
    val holidayName = KoreanHolidays.nameOf(date)
    val numberColor = when {
        // Public holidays are red like Sundays [FEAT-05].
        holidayName != null -> cal.sunday
        else -> when (date.dayOfWeek) {
        DayOfWeek.SUNDAY -> cal.sunday
        DayOfWeek.SATURDAY -> cal.saturday
        else -> scheme.onSurface
        }
    }
    // Working days are tinted with their shift's color so the month reads at a glance.
    val tint = type?.takeIf { !it.isOff && day.leave?.isFullDay != true }?.colorArgb?.asColor()?.copy(alpha = if (isDarkSurface()) 0.22f else 0.13f)
    val description = buildString {
        append(Fmt.dayTitle(date))
        holidayName?.let { append(", ").append(it) }
        type?.let { append(", ").append(it.name) }
        if (day.isWorkDay) append(", ").append(HoursFormat.korean(day.totalMinutes))
        day.extraWork?.let { append(", ").append(it.label) }
        day.leave?.let { append(", ").append(it.label) }
        if (day.isHolidayWork) append(", 휴일 근무 ").append(HoursFormat.korean(day.holidayWorkMinutes))
        if (hasMemo) append(", 메모 있음")
    }

    Box(
        modifier
            .padding(1.5.dp)
            .clip(shape)
            .background(tint ?: Color.Transparent)
            .then(
                when {
                    isSelected -> Modifier.border(2.dp, scheme.primary, shape)
                    else -> Modifier
                },
            )
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                },
                onLongClickLabel = "빠른 설정",
            )
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .alpha(if (inMonth) 1f else 0.35f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.fillMaxWidth().height(20.dp)) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (isToday) scheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                        fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isToday) scheme.onPrimary else numberColor,
                        fontSize = 13.sp,
                    )
                }
                val extra = day.extraWork
                val leave = day.leave
                when {
                    extra != null -> Box(Modifier.align(Alignment.TopEnd)) { ExtraWorkMark(extra, size = 13.dp) }
                    leave != null && !leave.isFullDay -> Box(Modifier.align(Alignment.TopEnd)) { LeaveMark(leave, size = 13.dp) }
                    day.source == DaySource.OVERRIDE || day.hasCustomTimes -> Box(
                        // Marks days the user changed by hand.
                        Modifier.align(Alignment.TopEnd).padding(2.dp).size(5.dp).clip(CircleShape).background(scheme.tertiary),
                    )
                }
            }
            val fullLeave = day.leave?.takeIf { it.isFullDay }
            if (fullLeave != null) {
                // A whole day of leave shows the leave instead of the shift it replaced [FEAT-10].
                LeaveMark(fullLeave, size = 22.dp)
            } else if (type != null || day.hasCustomTimes) {
                ShiftBadge(type = type, text = type?.label ?: "근", size = 22.dp, outlined = type?.isOff == true)
            } else {
                Spacer(Modifier.height(22.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                val extraMinutes = day.extraMinutes
                val footer = when {
                    extraMinutes > 0 -> "+" + HoursFormat.short(extraMinutes)
                    showHours && day.scheduledMinutes > 0 -> HoursFormat.short(day.scheduledMinutes)
                    else -> ""
                }
                Text(
                    footer,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    color = if (extraMinutes > 0) cal.holidayWork else scheme.onSurfaceVariant,
                    fontWeight = if (extraMinutes > 0) FontWeight.Bold else FontWeight.Normal,
                    style = NumberStyle,
                )
                if (hasMemo) {
                    if (footer.isNotEmpty()) Spacer(Modifier.width(2.dp))
                    ColorDot(cal.memoDot, size = 5.dp)
                }
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                Fmt.dayTitle(date),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            for (t in snapshot.shiftTypes) {
                DropdownMenuItem(
                    text = { Text(t.name) },
                    leadingIcon = { ShiftBadge(t, size = 20.dp, outlined = t.isOff) },
                    trailingIcon = { if (type?.id == t.id) Icon(Icons.Filled.Check, null) },
                    onClick = { menu = false; onQuickAction(QuickAction.Shift(ShiftOverride.Shift(t.id))) },
                )
            }
            if (day.source == DaySource.OVERRIDE) {
                DropdownMenuItem(
                    text = { Text("패턴대로") },
                    leadingIcon = { Icon(Icons.Filled.Autorenew, null) },
                    onClick = { menu = false; onQuickAction(QuickAction.Shift(ShiftOverride.None)) },
                )
            }
            HorizontalDivider()
            for (k in ExtraWork.entries) {
                val minutes = if (day.isWorkDay) 2 * 60 else 8 * 60
                DropdownMenuItem(
                    text = { Text(if (day.extraWork == k) "${k.label} 해제" else "${k.label} ${minutes / 60}시간") },
                    leadingIcon = { ExtraWorkMark(k, size = 20.dp) },
                    onClick = {
                        menu = false
                        onQuickAction(if (day.extraWork == k) QuickAction.ClearExtra else QuickAction.Extra(k, minutes))
                    },
                )
            }
            val shift = type?.takeIf { it.hasTimes }
            if (shift != null || day.leave != null) {
                HorizontalDivider()
                for (q in listOf(4, 2, 1)) {
                    val l = Leave(LeaveKind.ANNUAL, q)
                    DropdownMenuItem(
                        text = { Text(if (day.leave == l) "${l.label} 해제" else l.label) },
                        leadingIcon = { LeaveMark(l, size = 20.dp) },
                        onClick = { menu = false; onQuickAction(QuickAction.SetLeave(if (day.leave == l) null else l)) },
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("자세히 열기") },
                leadingIcon = { Icon(Icons.Filled.OpenInFull, null) },
                onClick = { menu = false; onQuickAction(QuickAction.Open) },
            )
        }
    }
}
