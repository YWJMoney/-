package com.bamnat.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.model.Attendance
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.work.HourlyWork
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [HRLY-02][HRLY-03] What an 알바 calendar's cells need: the pay setup for each day's pay and the weekday
 * the 주휴 week starts on, for the badge on each row.
 */
@Immutable
class HourlyCells(val profile: PayProfile?, val holidayWeekStart: DayOfWeek) {
    /** "5.2만" for a day that earns something. */
    fun payText(day: ResolvedDay): String? = HourlyWork.dayPay(day, profile).takeIf { it > 0 }?.let(HourlyWork::brief)
}

/** [HRLY-03] The day's hours in place of the shift code: "18–23", in the shift's colour. */
@Composable
internal fun HourlyTimeMark(day: ResolvedDay, range: String) {
    val color = day.shiftType?.takeIf { !it.isOff }?.colorArgb?.asColor() ?: MaterialTheme.colorScheme.primary
    Box(Modifier.size(width = 48.dp, height = 22.dp), contentAlignment = Alignment.Center) {
        Text(range, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1,
            overflow = TextOverflow.Clip, style = NumberStyle)
    }
}

/** [HRLY-01] 지각·조퇴 (주황) and 결근 (빨강) in a cell's corner. */
@Composable
internal fun AttendanceMark(a: Attendance) {
    val bg = if (a == Attendance.ABSENT) MaterialTheme.colorScheme.error else LocalCalendarColors.current.warning
    Box(Modifier.size(13.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(a.label.take(1), fontSize = 8.sp, lineHeight = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** [HRLY-02] On the last day of a week that earned 주휴 (15시간, 결근 없음). */
@Composable
internal fun HolidayWeekCheck(modifier: Modifier = Modifier) {
    Text("✓", fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = LocalCalendarColors.current.income, modifier = modifier)
}

/**
 * [HRLY-01][HRLY-08] An 알바 day's chips: 출근·퇴근 today (the time now, to 5 minutes, kept in the day's
 * change history), 지각·조퇴·결근 (a second tap takes it off), and 대타, which adds the 대근 of a shift.
 */
internal fun LazyListScope.hourlyChips(
    day: ResolvedDay,
    today: LocalDate,
    snapshot: ScheduleSnapshot,
    vm: CalendarViewModel,
    now: LocalDateTime = LocalDateTime.now(),
) {
    val works = day.shiftType?.isOff == false
    val clockIn = day.date == today && works && day.entry?.attendance != Attendance.ABSENT
    // [HRLY-08] A shift past midnight is clocked out on the day it started, from either day's sheet.
    val out = clockOutDay(day, today, now) { snapshot.resolver.resolve(today.minusDays(1)) }
    if (clockIn) {
        item {
            AssistChip(
                onClick = {
                    val now = HourlyWork.clock(LocalTime.now())
                    vm.changeDay(day.date, "출근 ${Fmt.time(now)} 적었어요", feature = "hourly.clock") { e ->
                        e.copy(customStart = now, customEnd = e.customEnd ?: day.shiftType?.end)
                    }
                },
                label = { Text("출근", maxLines = 1) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, null, Modifier.size(18.dp)) },
            )
        }
    }
    if (out != null) {
        item {
            AssistChip(
                onClick = { vm.clockOut(out, today, LocalTime.now()) },
                label = { Text(clockOutLabel(day, out), maxLines = 1) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp)) },
            )
        }
    }
    for (a in Attendance.entries) item {
        val on = day.entry?.attendance == a
        FilterChip(
            selected = on,
            onClick = {
                vm.changeDay(day.date, if (on) "${a.label} 표시를 지웠어요" else "${Fmt.monthDay(day.date)} ${a.label}${if (a == Attendance.EARLY) "로" else "으로"} 적었어요", feature = "hourly.attendance") { e ->
                    e.copy(attendance = if (on) null else a)
                }
            },
            label = { Text(a.label, maxLines = 1) },
            leadingIcon = if (on) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
        )
    }
    item { CoverChip(day, snapshot, vm) }
}

/** [HRLY-08] "어제 퇴근" on today's sheet for yesterday's late shift, else "퇴근". */
internal fun clockOutLabel(day: ResolvedDay, out: ResolvedDay): String = if (out.date != day.date) "어제 퇴근" else "퇴근"

/** [HRLY-01][HRLY-08] 퇴근 at [at] (to 5 minutes) on [out], the day [clockOutDay] picked. */
internal fun CalendarViewModel.clockOut(out: ResolvedDay, today: LocalDate, at: LocalTime) {
    val now = HourlyWork.clock(at)
    val said = (if (out.date != today) "${Fmt.monthDay(out.date)} " else "") + "퇴근 ${Fmt.time(now)} 적었어요"
    changeDay(out.date, said, feature = "hourly.clock") { e ->
        e.copy(customEnd = now, customStart = e.customStart ?: out.start ?: out.shiftType?.start)
    }
}

/** [HRLY-08] Hours past a shift's planned end that its 퇴근 still goes on it after midnight. */
internal const val CLOCK_OUT_SLACK_HOURS = 4L

/** [HRLY-08] Whether a 퇴근 at [now] still goes on [day], the day before: its shift ran on past midnight, or soon before. */
internal fun clocksOutLate(day: ResolvedDay, now: LocalDateTime): Boolean {
    val to = day.shiftTo ?: return false
    return day.date == now.toLocalDate().minusDays(1) && day.shiftType?.isOff == false &&
        day.entry?.attendance != Attendance.ABSENT && now.isBefore(to.plusHours(CLOCK_OUT_SLACK_HOURS))
}

/**
 * [HRLY-08] The day a 퇴근 on [day]'s sheet is written to, or null for no 퇴근 chip: on today's sheet
 * yesterday's late shift while today's has not started, else today's; on yesterday's sheet, its late shift.
 */
internal fun clockOutDay(day: ResolvedDay, today: LocalDate, now: LocalDateTime, yesterday: () -> ResolvedDay): ResolvedDay? {
    val works = day.shiftType?.isOff == false && day.entry?.attendance != Attendance.ABSENT
    return when (day.date) {
        today -> yesterday().takeIf { y -> clocksOutLate(y, now) && (!works || day.shiftFrom?.let(now::isBefore) != false) }
            ?: day.takeIf { works }
        today.minusDays(1) -> day.takeIf { clocksOutLate(it, now) }
        else -> null
    }
}

/** [HRLY-01] 대타: the 대근 of a shift for this day; one shift adds at once, more open a list, a second tap takes it off. */
@Composable
private fun CoverChip(day: ResolvedDay, snapshot: ScheduleSnapshot, vm: CalendarViewModel) {
    var menu by remember { mutableStateOf(false) }
    val i = day.extras.indexOfFirst { it.kind.timing is ExtraTiming.AsShift }
    val choices = remember(snapshot, day) {
        ExtraText.choices(snapshot.kinds, snapshot.shiftTypes, day).filter { it.kind.timing is ExtraTiming.AsShift }
    }
    Box {
        FilterChip(
            selected = i >= 0,
            enabled = i >= 0 || choices.isNotEmpty(),
            onClick = {
                when {
                    i >= 0 -> vm.removeExtra(day.date, i, ExtraText.name(day.extras[i]))
                    choices.size == 1 -> vm.addExtra(day.date, choices[0].entry, choices[0].name)
                    else -> menu = true
                }
            },
            label = { Text(if (i >= 0) "대타 · ${ExtraText.name(day.extras[i])}" else "대타", maxLines = 1) },
            leadingIcon = if (i >= 0) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (c in choices) DropdownMenuItem(
                text = { Text(c.label) },
                leadingIcon = { ExtraKindMark(c.kind, size = 20.dp) },
                onClick = { menu = false; vm.addExtra(day.date, c.entry, c.name) },
            )
        }
    }
}
