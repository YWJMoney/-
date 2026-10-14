package com.shiftmate.calendar.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.shiftmate.calendar.data.ScheduleSnapshot
import com.shiftmate.calendar.ui.Fmt
import com.shiftmate.calendar.ui.components.ColorDot
import com.shiftmate.calendar.ui.components.ShiftBadge
import com.shiftmate.calendar.ui.theme.LocalCalendarColors
import com.shiftmate.calendar.ui.theme.NumberStyle
import com.shiftmate.core.hours.HoursFormat
import com.shiftmate.core.hours.WorkHoursCalculator
import com.shiftmate.core.schedule.DaySource
import com.shiftmate.core.schedule.ResolvedDay
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
    modifier: Modifier = Modifier,
) {
    val cal = LocalCalendarColors.current
    val firstCell = remember(month, calculator) { calculator.weekStartOf(month.atDay(1)) }
    Column(modifier.fillMaxWidth()) {
        for (row in 0 until GRID_ROWS) {
            val rowStart = firstCell.plusWeeks(row.toLong())
            val week = remember(calculator, rowStart) { calculator.week(rowStart) }
            val rowShape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(cellHeight)
                    .then(
                        if (week.overLimit) {
                            Modifier.background(cal.warningContainer.copy(alpha = 0.55f), rowShape)
                                .border(1.dp, cal.warning.copy(alpha = 0.35f), rowShape)
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
                        inMonth = date.year == month.year && date.month == month.month,
                        isToday = date == today,
                        isSelected = date == selected,
                        hasMemo = date in memoDates,
                        showHours = showHours,
                        onClick = { onDayClick(date) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: ResolvedDay,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    hasMemo: Boolean,
    showHours: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cal = LocalCalendarColors.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val date = day.date
    val numberColor = when (date.dayOfWeek) {
        DayOfWeek.SUNDAY -> cal.sunday
        DayOfWeek.SATURDAY -> cal.saturday
        else -> scheme.onSurface
    }
    val description = buildString {
        append(Fmt.dayTitle(date))
        day.shiftType?.let { append(", ").append(it.name) }
        if (day.isWorkDay) append(", ").append(HoursFormat.korean(day.totalMinutes))
        if (day.isHolidayWork) append(", 휴일 근무 ").append(HoursFormat.korean(day.holidayWorkMinutes))
        if (hasMemo) append(", 메모 있음")
    }

    Box(
        modifier
            .padding(2.dp)
            .clip(shape)
            .background(if (isSelected) scheme.primaryContainer else Color.Transparent)
            .then(if (isToday) Modifier.border(1.5.dp, scheme.primary, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .alpha(if (inMonth) 1f else 0.38f),
    ) {
        if (day.source == DaySource.OVERRIDE || day.hasCustomTimes) {
            // Marks days the user changed by hand.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.tertiary),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(top = 3.dp, bottom = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                    fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (isToday) scheme.primary else numberColor,
                )
                if (hasMemo) {
                    Spacer(Modifier.width(2.dp))
                    ColorDot(cal.memoDot, size = 5.dp)
                }
            }
            val type = day.shiftType
            if (type != null || day.hasCustomTimes) {
                ShiftBadge(
                    type = type,
                    text = type?.label ?: "근",
                    size = 20.dp,
                    outlined = type?.isOff == true,
                )
            } else {
                Spacer(Modifier.height(20.dp))
            }
            val footer = when {
                day.isHolidayWork -> "휴근 " + HoursFormat.short(day.holidayWorkMinutes)
                day.overtimeMinutes > 0 -> "+" + HoursFormat.short(day.overtimeMinutes)
                showHours && day.scheduledMinutes > 0 -> HoursFormat.short(day.scheduledMinutes)
                else -> ""
            }
            Text(
                footer,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = when {
                    day.isHolidayWork -> cal.holidayWork
                    day.overtimeMinutes > 0 -> scheme.tertiary
                    else -> scheme.onSurfaceVariant
                },
                fontWeight = if (day.isHolidayWork || day.overtimeMinutes > 0) FontWeight.Bold else FontWeight.Normal,
                style = NumberStyle,
            )
        }
    }
}
