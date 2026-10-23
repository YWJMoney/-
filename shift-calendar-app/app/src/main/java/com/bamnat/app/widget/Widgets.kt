package com.bamnat.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.color.ColorProvider as DayNight
import com.bamnat.app.MainActivity
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.calendar.clockOutDay
import com.bamnat.app.ui.components.contentColorOn
import com.bamnat.app.ui.theme.DarkCalendarColors
import com.bamnat.app.ui.theme.LightCalendarColors
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.schedule.CalendarGrid
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.sleep.TodayFlow
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.WorkStyle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/*
 * Home screen widgets [FEAT-03, REL-03]: today and tomorrow, this week, and the month.
 * They are redrawn whenever the schedule changes, at midnight, and after time or time zone
 * changes (see WidgetRefresher), so the date never sticks.
 */

class TodayWidget : GlanceAppWidget() {
    // [SLP-14] Laid out for its real size, so a short widget keeps the line of 오늘의 흐름 over 내일.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context)
        provideContent { GlanceTheme { WidgetFrame { TodayContent(data) } } }
    }
}

class WeekWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context)
        provideContent { GlanceTheme { WidgetFrame { WeekContent(data) } } }
    }
}

class MonthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context)
        provideContent { GlanceTheme { WidgetFrame { MonthContent(data) } } }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class WeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthWidget()
}

@Composable
private fun WidgetFrame(content: @Composable () -> Unit) {
    Box(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) { content() }
}

/** [CAL-11] What an empty widget asks for, in the words of the user's kind of work. */
private fun emptyText(data: WidgetData): String = when {
    data.focus == CalendarFocus.JOBS -> "일감을 적으면 여기에 오늘 일이 보여요"
    data.focus == CalendarFocus.SALES -> "매출을 적으면 여기에 오늘 매출이 보여요"
    data.focus == CalendarFocus.INVEST -> "입금과 매매를 적으면 여기에 오늘 손익과 수익률이 보여요"
    data.style == WorkStyle.DAY -> "일하는 요일을 정하면 여기에 오늘 근무가 보여요"
    data.style == WorkStyle.HOURLY -> "일한 날을 칠하면 여기에 오늘 근무가 보여요"
    else -> "근무 주기를 정하면 여기에 오늘 근무가 보여요"
}

@Composable
private fun TodayContent(data: WidgetData) {
    Column(GlanceModifier.fillMaxSize()) {
        if (data.isEmpty) {
            Text(emptyText(data), style = small(GlanceTheme.colors.onSurfaceVariant))
            return@Column
        }
        // [CAL-11] Field work: today's and tomorrow's jobs; a shop: today's sales and the month's.
        when (data.focus) {
            CalendarFocus.JOBS -> { JobsToday(data); return@Column }
            CalendarFocus.SALES -> { SalesToday(data); return@Column }
            CalendarFocus.INVEST -> { InvestToday(data); return@Column }
            CalendarFocus.SHIFTS -> Unit
        }
        val todayDay = data.day(data.today)
        val alarmToday = data.nextAlarm?.takeIf { it.triggerAt.toLocalDate() == data.today }
        // [DAILY-08] Today's alarm sits on the date line, so a short widget never cuts it off.
        DayLine("오늘", todayDay, big = true, alarm = alarmToday?.let { Fmt.time(it.triggerAt.toLocalTime()) }, memo = data.memos[data.today])
        // [COMN-05] A part-timer's week and 주휴, a day worker's 연차 and 야근.
        WorkStyleLine(data)
        // [DAYW-03] A day worker leaves work from the home screen.
        if (LeaveNowAction.offered(data.style, todayDay)) {
            Spacer(GlanceModifier.height(4.dp))
            LeaveNowWidgetButton()
        }
        // [DAILY-11] An 알바 clocks in and out from the home screen.
        val now = java.time.LocalDateTime.now()
        HourlyClockAction.offered(data.style, todayDay, now) { data.day(data.today.minusDays(1)) }?.let { out ->
            Spacer(GlanceModifier.height(4.dp))
            HourlyClockButton(out, yesterday = out && clockOutDay(todayDay, data.today, now) { data.day(data.today.minusDays(1)) }?.date != data.today)
        }
        val line = data.flow?.line
        if (line != null) {
            Spacer(GlanceModifier.height(4.dp))
            val warn = data.flow.state == TodayFlow.State.ALARM_OFF || data.flow.state == TodayFlow.State.FORGOT_WAKE
            Text(
                "${flowMark(data.flow)} $line",
                style = TextStyle(color = if (warn) GlanceTheme.colors.error else GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        // Room for 내일 too, unless the widget is short and has the flow's line to show instead.
        if (line == null || LocalSize.current.height >= 150.dp) {
            Spacer(GlanceModifier.height(8.dp))
            val alarmTomorrow = data.nextAlarm?.takeIf { it.triggerAt.toLocalDate() == data.today.plusDays(1) }
            DayLine("내일", data.day(data.today.plusDays(1)), big = false, alarm = alarmTomorrow?.let { Fmt.time(it.triggerAt.toLocalTime()) },
                memo = data.memos[data.today.plusDays(1)])
        }
        // An alarm further off keeps its own line.
        data.nextAlarm?.takeIf { it.triggerAt.toLocalDate().isAfter(data.today.plusDays(1)) }?.let { alarm ->
            Spacer(GlanceModifier.height(6.dp))
            val at = alarm.triggerAt
            Text("⏰ ${Fmt.shortDate(at.toLocalDate())} ${Fmt.time(at.toLocalTime())}", style = small(GlanceTheme.colors.primary), maxLines = 1)
        }
    }
}

/** [SLP-14] A mark for the kind of line: sleep, nap, coffee, an alarm to check, waking. */
private fun flowMark(card: TodayFlow.Card): String = when (card.state) {
    TodayFlow.State.ALARM_OFF, TodayFlow.State.FORGOT_WAKE -> "⚠"
    TodayFlow.State.NAP -> "💤"
    TodayFlow.State.SLEEPING -> "🌙"
    TodayFlow.State.AWAKE -> "☀"
    TodayFlow.State.ON_SHIFT -> if (card.line?.startsWith("커피") == true) "☕" else "🛏"
    else -> if (card.line?.startsWith("낮잠") == true) "💤" else "🛏"
}

/** [CAL-11] "오늘 10/9(금) · 한글날", red on a public holiday as on the calendar. */
@Composable
private fun DateLabel(title: String, date: LocalDate) {
    val holiday = KoreanHolidays.nameOf(date)
    Text(
        "$title ${date.monthValue}/${date.dayOfMonth}(${Fmt.dayOfWeekShort(date.dayOfWeek)})" + (holiday?.let { " · $it" } ?: ""),
        style = small(if (holiday != null) WidgetRed else GlanceTheme.colors.onSurfaceVariant),
        maxLines = 1,
    )
}

/** [CAL-11] The day's name on a widget: a whole day of leave, a 대근 on a day off, or the shift. */
private fun dayName(day: ResolvedDay): String {
    val leave = day.leave?.takeIf { it.isFullDay }
    val covered = day.coveredShift
    return when {
        leave != null -> leave.label
        covered != null && !day.isWorkDay -> "${covered.name} 대근"
        else -> day.shiftType?.name ?: "일정 없음"
    }
}

@Composable
private fun JobsToday(data: WidgetData) {
    for ((title, date) in listOf("오늘" to data.today, "내일" to data.today.plusDays(1))) {
        val jobs = data.jobsOn(date)
        DateLabel(title, date)
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MarkChip(if (jobs.isEmpty()) "–" else if (jobs.size > 1) "일${jobs.size}" else "일", if (date == data.today) 28 else 22, jobs.isNotEmpty())
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(
                    jobs.firstOrNull()?.title ?: "잡힌 일감 없음",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (date == data.today) 18.sp else 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                jobs.firstOrNull()?.let { j ->
                    val time = j.start?.let { s -> Fmt.time(s) + (j.end?.let { "–" + Fmt.time(it) } ?: "") }
                    val more = if (jobs.size > 1) "외 ${jobs.size - 1}건" else null
                    val run = if (j.days > 1) "${j.days}일 중 ${(date.toEpochDay() - j.date.toEpochDay()).toInt() + 1}일째" else null
                    listOfNotNull(time, run, more).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = small(GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                    }
                }
            }
        }
        if (date == data.today) Spacer(GlanceModifier.height(8.dp))
    }
    if (data.jobsOn(data.today).isEmpty() && data.jobsOn(data.today.plusDays(1)).isEmpty()) {
        data.nextJobAfter(data.today.plusDays(1))?.let { j ->
            Spacer(GlanceModifier.height(6.dp))
            Text("다음 일감 ${Fmt.shortDate(j.date)} ${j.title}", style = small(GlanceTheme.colors.primary), maxLines = 1)
        }
    }
    // [COMN-05] The week's days or 공수, and what is still owed.
    if (data.style == WorkStyle.FIELD) FieldWeekLine(data)
}

/** [CAL-11] A shop's own day and month; the amounts are the user's, on their own screen, as in the calendar's cells. */
@Composable
private fun SalesToday(data: WidgetData) {
    val today = data.sales[data.today]
    DateLabel("오늘", data.today)
    Text(
        today?.let { "매출 " + Fmt.won(it.sales) } ?: "아직 안 적었어요",
        style = TextStyle(color = if (today != null) WidgetGreen else GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
    Spacer(GlanceModifier.height(8.dp))
    val month = YearMonth.from(data.today)
    Text("${month.monthValue}월 매출", style = small(GlanceTheme.colors.onSurfaceVariant))
    Text(Fmt.wonShort(data.monthSales(month)), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
}

/** [INV-16] The investor's today: what today earned and the total return; no amounts while they are hidden. */
@Composable
private fun InvestToday(data: WidgetData) {
    val line = data.invest ?: return
    DateLabel("오늘", data.today)
    val v = line.todayPnl
    Text(
        when {
            line.hide -> if (line.todayTrades > 0) "매매 ${line.todayTrades}건" else "매매 없음"
            v == 0L && line.todayTrades == 0 -> "매매 없음"
            else -> "손익 " + (if (v > 0) "+" else if (v < 0) "−" else "") + Fmt.won(kotlin.math.abs(v))
        },
        style = TextStyle(color = when {
            line.hide || v == 0L -> GlanceTheme.colors.onSurface
            v > 0 -> WidgetRed
            else -> WidgetBlue
        }, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
    Spacer(GlanceModifier.height(8.dp))
    Text("총 수익률", style = small(GlanceTheme.colors.onSurfaceVariant))
    val r = line.totalReturn
    Text(
        r?.let { (if (it > 0) "+" else if (it < 0) "−" else "") + "%.2f%%".format(kotlin.math.abs(it * 100)) } ?: "원금을 적어 주세요",
        style = TextStyle(color = when {
            r == null || r == 0.0 -> GlanceTheme.colors.onSurface
            r > 0 -> WidgetRed
            else -> WidgetBlue
        }, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
}

/**
 * A day on the today widget: its date (with the alarm's time), its shift or leave, and [DAILY-01] its memo.
 * [DAILY-06] Tapping it opens that day in the app; [DAILY-07] an alarm turned off for the day says so.
 */
@Composable
private fun DayLine(title: String, day: ResolvedDay, big: Boolean, alarm: String? = null, memo: String? = null) {
    val context = androidx.glance.LocalContext.current
    Column(GlanceModifier.fillMaxWidth().clickable(openDay(context, day.date))) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DateLabel(title, day.date)
            when {
                day.alarmSkipped && (day.isWorkDay || day.coveredShift != null) ->
                    Text(" · 🔕 알람 끔", style = small(GlanceTheme.colors.error), maxLines = 1)
                alarm != null -> Text(" · ⏰ $alarm", style = small(GlanceTheme.colors.primary), maxLines = 1)
            }
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ShiftChip(day, if (big) 28 else 22)
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(
                    dayName(day),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = if (big) 18.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                val start = day.start
                val end = day.end
                val leave = day.leave?.takeIf { !it.isFullDay }
                if (day.isWorkDay && start != null && end != null) {
                    Text(Fmt.timeRange(start, end) + (leave?.let { " · ${it.label}" } ?: ""), style = small(GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                }
            }
        }
        memo?.let { MemoLine(it) }
    }
}

@Composable
private fun WeekContent(data: WidgetData) {
    // "이번 주" starts on the user's first day of the week, like the calendar.
    val days = CalendarGrid.week(CalendarGrid.weekStartOf(data.today, data.weekStart))
    Column(GlanceModifier.fillMaxSize()) {
        Text(
            when (data.focus) {
                CalendarFocus.SHIFTS -> "이번 주 근무"
                CalendarFocus.JOBS -> "이번 주 일감"
                CalendarFocus.SALES -> "이번 주 매출"
                CalendarFocus.INVEST -> "이번 주 손익"
            },
            style = small(GlanceTheme.colors.onSurfaceVariant),
        )
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            for (date in days) {
                val day = data.day(date)
                // [DAILY-06] Each day opens itself.
                Column(GlanceModifier.defaultWeight().clickable(openDay(androidx.glance.LocalContext.current, date)), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (date == data.today) "오늘" else Fmt.dayOfWeekShort(date.dayOfWeek),
                        style = small(weekdayColor(date.dayOfWeek, date == data.today, KoreanHolidays.isHoliday(date))).copy(textAlign = TextAlign.Center),
                    )
                    Text(
                        "${date.dayOfMonth}",
                        style = TextStyle(
                            // [CAL-11] A public holiday is red as on the calendar.
                            color = if (KoreanHolidays.isHoliday(date) && date != data.today) WidgetRed else GlanceTheme.colors.onSurface,
                            fontSize = 15.sp,
                            fontWeight = if (date == data.today) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        ),
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    WeekMark(data, day, 26)
                    // [DAILY-07] The day's alarm is off.
                    if (day.alarmSkipped && (day.isWorkDay || day.coveredShift != null)) {
                        Text("🔕", style = TextStyle(fontSize = 9.sp, textAlign = TextAlign.Center), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthContent(data: WidgetData) {
    val month = YearMonth.from(data.today)
    val weeks = CalendarGrid.monthWeeks(month, data.weekStart)
    Column(GlanceModifier.fillMaxSize()) {
        Text(
            Fmt.month(month),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(4.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val dow = data.weekStart.plus(i.toLong())
                Text(
                    Fmt.dayOfWeekShort(dow),
                    style = small(weekdayColor(dow, false)).copy(textAlign = TextAlign.Center),
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
        for (week in weeks) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                for (date in week) {
                    MonthCell(data, date, inMonth = YearMonth.from(date) == month, modifier = GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

/** [CAL-11] A week day's mark in the kind of work: its shift, its jobs, or its sales. */
@Composable
private fun WeekMark(data: WidgetData, day: ResolvedDay, sizeDp: Int) {
    when (data.focus) {
        CalendarFocus.SHIFTS -> ShiftChip(day, sizeDp)
        CalendarFocus.JOBS -> {
            val n = data.jobsOn(day.date).size
            if (n == 0 && (day.shiftType != null || day.leave != null)) ShiftChip(day, sizeDp)
            else MarkChip(if (n == 0) "–" else if (n > 1) "일$n" else "일", sizeDp, n > 0)
        }
        CalendarFocus.SALES -> {
            val s = data.sales[day.date]
            Text(
                s?.let { Fmt.wonShort(it.sales) } ?: "–",
                style = TextStyle(color = if (s != null) WidgetGreen else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
        // [INV-16] A week day's result, red or blue; only a mark while amounts are hidden.
        CalendarFocus.INVEST -> {
            val line = data.invest
            val v = line?.days?.get(day.date)
            Text(
                when {
                    v == null || v == 0L -> "–"
                    line.hide -> "●"
                    else -> (if (v > 0) "+" else "−") + Fmt.wonShort(kotlin.math.abs(v))
                },
                style = TextStyle(color = when {
                    v == null || v == 0L -> GlanceTheme.colors.onSurfaceVariant
                    v > 0 -> WidgetRed
                    else -> WidgetBlue
                }, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}

/** [CAL-11] A rounded square with a short word, like a shift chip, for a job day. */
@Composable
private fun MarkChip(text: String, sizeDp: Int, on: Boolean) {
    Box(
        GlanceModifier.height(sizeDp.dp).padding(horizontal = 4.dp)
            .background(if (on) GlanceTheme.colors.secondaryContainer else ColorProvider(Color(0x22888888)))
            .cornerRadius((sizeDp / 3).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(color = if (on) GlanceTheme.colors.onSecondaryContainer else GlanceTheme.colors.onSurfaceVariant,
            fontSize = (sizeDp * 0.42f).sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

@Composable
private fun MonthCell(data: WidgetData, date: LocalDate, inMonth: Boolean, modifier: GlanceModifier) {
    val day = data.day(date)
    val leave = day.leave?.takeIf { it.isFullDay }
    // [CAL-11] A whole day of leave shows the leave, and a 대근 on a day off the shift covered.
    val type = if (leave != null) null else day.shiftType?.takeIf { !it.isOff || day.coveredShift == null } ?: day.coveredShift
    val isToday = date == data.today
    val red = KoreanHolidays.isHoliday(date) || date.dayOfWeek == DayOfWeek.SUNDAY
    Column(modifier.padding(1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${date.dayOfMonth}",
            style = TextStyle(
                color = when {
                    isToday -> GlanceTheme.colors.primary
                    !inMonth -> GlanceTheme.colors.outline
                    red -> WidgetRed
                    else -> GlanceTheme.colors.onSurface
                },
                fontSize = 11.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
        val jobs = if (inMonth && data.focus == CalendarFocus.JOBS) data.jobsOn(date).size else 0
        val sold = inMonth && data.focus == CalendarFocus.SALES && data.sales[date] != null
        if (leave != null && inMonth) {
            Text(leave.short, style = TextStyle(color = GlanceTheme.colors.onTertiaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center), maxLines = 1,
                modifier = GlanceModifier.fillMaxWidth().background(GlanceTheme.colors.tertiaryContainer).cornerRadius(4.dp))
        } else if (jobs > 0 && (type == null || type.isOff)) {
            Text(if (jobs > 1) "일$jobs" else "일", style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 1,
                modifier = GlanceModifier.fillMaxWidth().background(GlanceTheme.colors.secondaryContainer).cornerRadius(4.dp))
        } else if (sold && type == null) {
            Text("●", style = TextStyle(color = WidgetGreen, fontSize = 9.sp, textAlign = TextAlign.Center), maxLines = 1)
        } else if (type != null && inMonth) {
            val color = Color(type.colorArgb.toInt())
            Text(
                type.label,
                style = TextStyle(
                    color = ColorProvider(if (type.isOff) color else onColor(color)),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(if (type.isOff) Color.Transparent else color)
                    .cornerRadius(4.dp),
            )
        }
    }
}

/** A rounded square in the shift's color with its label, or [REST_GLYPH] for an empty day. */
@Composable
private fun ShiftChip(day: ResolvedDay, sizeDp: Int) {
    // [CAL-11] A whole day of leave shows the leave; a 대근 on a day off shows the shift worked.
    day.leave?.takeIf { it.isFullDay }?.let { l ->
        Box(
            GlanceModifier.size(sizeDp.dp).background(GlanceTheme.colors.tertiaryContainer).cornerRadius((sizeDp / 3).dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(l.short, style = TextStyle(color = GlanceTheme.colors.onTertiaryContainer, fontSize = (sizeDp * 0.45f).sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
        return
    }
    ShiftSquare(day.shiftType?.takeIf { !(it.isOff && day.coveredShift != null) } ?: day.coveredShift, sizeDp)
}

/**
 * [WIDG-04] One square for a shift on every widget (I 리뷰 1-5④): filled in the shift's colour, an off type
 * as its coloured letter on grey, and a day with no shift as "휴", so the team widget and the week rows agree.
 */
@Composable
internal fun ShiftSquare(type: ShiftType?, sizeDp: Int) {
    val color = type?.let { Color(it.colorArgb.toInt()) }
    Box(
        GlanceModifier
            .size(sizeDp.dp)
            .background(if (color == null || type.isOff) Color(0x22888888) else color)
            .cornerRadius((sizeDp / 3).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            type?.label ?: REST_GLYPH,
            style = TextStyle(
                color = when {
                    color == null -> GlanceTheme.colors.onSurfaceVariant
                    type.isOff -> ColorProvider(color)
                    else -> ColorProvider(onColor(color))
                },
                fontSize = (sizeDp * 0.45f).sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

/** What a day with no shift shows on a widget square. */
internal const val REST_GLYPH = "휴"

// [A11Y-03] The app's own day and night shades, so red, blue and green words keep 4.5:1 on the widget in both themes.
private val WidgetRed = DayNight(day = LightCalendarColors.sunday, night = DarkCalendarColors.sunday)
private val WidgetBlue = DayNight(day = LightCalendarColors.saturday, night = DarkCalendarColors.saturday)
private val WidgetGreen = DayNight(day = LightCalendarColors.income, night = DarkCalendarColors.income)

@Composable
private fun weekdayColor(dow: DayOfWeek, today: Boolean, holiday: Boolean = false): ColorProvider = when {
    today -> GlanceTheme.colors.primary
    dow == DayOfWeek.SUNDAY || holiday -> WidgetRed
    dow == DayOfWeek.SATURDAY -> WidgetBlue
    else -> GlanceTheme.colors.onSurfaceVariant
}

private fun small(color: ColorProvider) = TextStyle(color = color, fontSize = 12.sp)

private fun onColor(background: Color): Color = contentColorOn(background)
