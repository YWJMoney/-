package com.bamnat.app.ui.calendar

import com.bamnat.core.holiday.KoreanLunar
import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.Leave
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
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
import com.bamnat.core.money.DayMoney
import com.bamnat.core.money.MoneyBook
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
    /** [EXTRA-01] Adds the piece, or takes it off when the day has it. */
    data class Extra(val choice: ExtraChoice) : QuickAction
    /** 연차·반차·반반차, or null to remove it [FEAT-10]. */
    data class SetLeave(val leave: Leave?) : QuickAction
    /** [DAY-03] This day's alarm off (true) or back on. */
    data class Alarm(val skipped: Boolean) : QuickAction
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
    showLunar: Boolean = false,
    cellHeight: Dp,
    onDayClick: (LocalDate) -> Unit,
    onQuickAction: (LocalDate, QuickAction) -> Unit,
    modifier: Modifier = Modifier,
    /** [WORK-02][WORK-03] Field and shop work show the day's income in place of hours. */
    money: MoneyBook = MoneyBook.EMPTY,
    showMoney: Boolean = false,
    /** [FEAT-17] Memo-first: the start of each day's memo in place of the hours. */
    memoTexts: Map<LocalDate, String> = emptyMap(),
) {
    val cal = LocalCalendarColors.current
    val dayMoney = remember(money, month, showMoney) { if (showMoney) money.days(month) else emptyMap() }
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
                        money = dayMoney[date],
                        memoText = memoTexts[date],
                        showHours = showHours,
                        showLunar = showLunar,
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
    showLunar: Boolean = false,
    money: DayMoney? = null,
    memoText: String? = null,
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
    // A 대근 on a day off tints the cell with the covered shift [EXTRA-01].
    val tint = (type?.takeIf { !it.isOff && day.leave?.isFullDay != true } ?: day.coveredShift)
        ?.colorArgb?.asColor()?.copy(alpha = if (isDarkSurface()) 0.22f else 0.13f)
    // [VIS-01] What the cell shows, read out in the same order.
    val description = buildString {
        append(Fmt.dayTitle(date))
        if (isToday) append(", 오늘")
        holidayName?.let { append(", ").append(it) }
        append(", ").append(if (day.isWorkDay) type?.name ?: "근무" else type?.name ?: "쉬는 날")
        if (day.isWorkDay) append(" ").append(HoursFormat.korean(day.scheduledMinutes))
        day.leave?.let { append(", ").append(it.label) }
        for (e in day.extras) {
            append(", ").append(ExtraText.name(e))
            if (e.entry.hasTimes || e.covered != null) append(" ").append(HoursFormat.korean(e.minutes))
        }
        if (day.source == DaySource.OVERRIDE || day.hasCustomTimes) append(", 직접 바꿈")
        if (day.alarmSkipped) append(", 알람 끔")
        if (hasMemo) append(", 메모 있음")
        money?.let { m ->
            if (m.jobs > 0) append(", 일감 ${m.jobs}건")
            if (m.income != 0L) append(", 수입 ").append(Fmt.won(m.income))
            if (m.unpaid > 0) append(", 받을 돈 ").append(Fmt.won(m.unpaid))
        }
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
                val leave = day.leave
                // [EXTRA-01] The kinds of extra work on the day: the first top right, a second top left.
                val kinds = day.extras.map { it.kind }.distinctBy { it.id }
                val covered = day.coveredShift
                // On a day off with a 대근 the covered shift is the badge, so the cell reads "야간, 대근".
                val kindIsBadge = !day.isWorkDay && covered == null && kinds.isNotEmpty() && day.leave == null
                val marks = if (kindIsBadge) kinds.drop(1) else kinds
                val topEnd = marks.firstOrNull()
                when {
                    topEnd != null -> Box(Modifier.align(Alignment.TopEnd)) { ExtraKindMark(topEnd, size = 13.dp) }
                    leave != null && !leave.isFullDay -> Box(Modifier.align(Alignment.TopEnd)) { LeaveMark(leave, size = 13.dp) }
                    day.source == DaySource.OVERRIDE || day.hasCustomTimes -> Box(
                        // Marks days the user changed by hand.
                        Modifier.align(Alignment.TopEnd).padding(2.dp).size(5.dp).clip(CircleShape).background(cal.changed),
                    )
                }
                // [DAY-03] A day whose alarm was turned off says so, so a missed alarm is never a surprise.
                if (day.alarmSkipped) {
                    Icon(Icons.Filled.AlarmOff, contentDescription = null, tint = cal.alarmOff,
                        modifier = Modifier.align(Alignment.TopStart).size(12.dp))
                } else {
                    marks.getOrNull(1)?.let { second ->
                        Box(Modifier.align(Alignment.TopStart)) { ExtraKindMark(second, size = 13.dp) }
                    }
                }
            }
            val fullLeave = day.leave?.takeIf { it.isFullDay }
            val covered = day.coveredShift
            val badgeKind = day.extras.firstOrNull()?.kind?.takeIf { !day.isWorkDay && covered == null && day.leave == null }
            if (fullLeave != null) {
                // A whole day of leave shows the leave instead of the shift it replaced [FEAT-10].
                LeaveMark(fullLeave, size = 22.dp)
            } else if (covered != null && day.isWorkDay && type != null) {
                // [FEAT-02][EXTRA-01] The day's shift and the shift covered on top, labels only so both fit a folded cell.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ShiftBadge(type = type, size = 18.dp, outlined = type.isOff, showIcon = false)
                    ShiftBadge(type = covered, size = 18.dp, showIcon = false)
                }
            } else if (covered != null) {
                // [EXTRA-01] A 대근 on a day off: the covered shift's badge.
                ShiftBadge(type = covered, size = 22.dp)
            } else if (badgeKind != null) {
                // [EXTRA-01] Other extra work on a day off (특근 09–18): the kind itself is the day's mark.
                ExtraKindMark(badgeKind, size = 22.dp)
            } else if (type != null || day.hasCustomTimes) {
                ShiftBadge(type = type, text = type?.label ?: "근", size = 22.dp, outlined = type?.isOff == true)
            } else if (money != null && money.jobs > 0) {
                // [WORK-02] A booked job; amber while money is still owed.
                JobBadge(count = money.jobs, owed = money.unpaid > 0)
            } else {
                Spacer(Modifier.height(22.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                val extraMinutes = day.extraMinutes
                val moneyText = money?.income?.takeIf { it != 0L }?.let { Fmt.wonShort(it) }
                val footer = when {
                    memoText != null -> memoText
                    moneyText != null -> moneyText
                    extraMinutes > 0 -> "+" + HoursFormat.short(extraMinutes)
                    // [FEAT-05] Lunar date takes the place of the hours when turned on.
                    showLunar -> remember(date) { KoreanLunar.fromSolar(date).short }
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
                        memoText != null -> cal.memoDot
                        moneyText != null -> if (money!!.income < 0) cal.expense else cal.income
                        extraMinutes > 0 -> cal.holidayWork
                        else -> scheme.onSurfaceVariant
                    },
                    fontWeight = if (extraMinutes > 0 || moneyText != null) FontWeight.Bold else FontWeight.Normal,
                    style = NumberStyle,
                )
                if (hasMemo && memoText == null) {
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
            val choices = remember(snapshot, day) { ExtraText.choices(snapshot.kinds, snapshot.shiftTypes, day) }
            if (choices.isNotEmpty()) HorizontalDivider()
            for (c in choices) {
                val i = ExtraText.indexOf(day, c)
                DropdownMenuItem(
                    text = { Text(if (i >= 0) "${ExtraText.name(day.extras[i])} 빼기" else c.label) },
                    leadingIcon = { ExtraKindMark(c.kind, size = 20.dp) },
                    trailingIcon = { if (i >= 0) Icon(Icons.Filled.Check, null) },
                    onClick = { menu = false; onQuickAction(QuickAction.Extra(c)) },
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
            if (day.isWorkDay || day.coveredShift != null || day.alarmSkipped) {
                DropdownMenuItem(
                    text = { Text(if (day.alarmSkipped) "이 날 알람 켜기" else "이 날 알람 끄기") },
                    leadingIcon = { Icon(if (day.alarmSkipped) Icons.Filled.Alarm else Icons.Filled.AlarmOff, null) },
                    onClick = { menu = false; onQuickAction(QuickAction.Alarm(!day.alarmSkipped)) },
                )
            }
            DropdownMenuItem(
                text = { Text("자세히 열기") },
                leadingIcon = { Icon(Icons.Filled.OpenInFull, null) },
                onClick = { menu = false; onQuickAction(QuickAction.Open) },
            )
        }
    }
}

@Composable
private fun JobBadge(count: Int, owed: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (owed) LocalCalendarColors.current.warningContainer else scheme.secondaryContainer
    Box(
        Modifier.height(22.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 1) "일$count" else "일", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (owed) LocalCalendarColors.current.warning else scheme.onSecondaryContainer, maxLines = 1)
    }
}
