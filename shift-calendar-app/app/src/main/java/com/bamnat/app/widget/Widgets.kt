package com.bamnat.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import com.bamnat.app.MainActivity
import com.bamnat.app.ui.Fmt
import com.bamnat.core.schedule.CalendarGrid
import com.bamnat.core.schedule.ResolvedDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/*
 * Home screen widgets [FEAT-03, REL-03]: today and tomorrow, this week, and the month.
 * They are redrawn whenever the schedule changes, at midnight, and after time or time zone
 * changes (see WidgetRefresher), so the date never sticks.
 */

class TodayWidget : GlanceAppWidget() {
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

@Composable
private fun TodayContent(data: WidgetData) {
    Column(GlanceModifier.fillMaxSize()) {
        if (!data.hasPattern) {
            Text("근무 패턴을 정하면 여기에 오늘 근무가 보여요", style = small(GlanceTheme.colors.onSurfaceVariant))
            return@Column
        }
        DayLine("오늘", data.day(data.today), big = true)
        Spacer(GlanceModifier.height(8.dp))
        DayLine("내일", data.day(data.today.plusDays(1)), big = false)
        data.nextAlarm?.let { alarm ->
            Spacer(GlanceModifier.height(6.dp))
            val at = alarm.triggerAt
            val dayLabel = when (at.toLocalDate()) {
                data.today -> "오늘"
                data.today.plusDays(1) -> "내일"
                else -> Fmt.shortDate(at.toLocalDate())
            }
            Text("⏰ $dayLabel ${Fmt.time(at.toLocalTime())}", style = small(GlanceTheme.colors.primary))
        }
    }
}

@Composable
private fun DayLine(title: String, day: ResolvedDay, big: Boolean) {
    val type = day.shiftType
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$title ${day.date.monthValue}/${day.date.dayOfMonth}(${Fmt.dayOfWeekShort(day.date.dayOfWeek)})",
            style = small(GlanceTheme.colors.onSurfaceVariant),
        )
    }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ShiftChip(day, if (big) 28 else 22)
        Spacer(GlanceModifier.width(8.dp))
        Column {
            Text(
                type?.name ?: "일정 없음",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = if (big) 18.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            val start = day.start
            val end = day.end
            if (day.isWorkDay && start != null && end != null) {
                Text(Fmt.timeRange(start, end), style = small(GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            }
        }
    }
}

@Composable
private fun WeekContent(data: WidgetData) {
    // "이번 주" starts on the user's first day of the week, like the calendar.
    val days = CalendarGrid.week(CalendarGrid.weekStartOf(data.today, data.weekStart))
    Column(GlanceModifier.fillMaxSize()) {
        Text("이번 주 근무", style = small(GlanceTheme.colors.onSurfaceVariant))
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            for (date in days) {
                val day = data.day(date)
                Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (date == data.today) "오늘" else Fmt.dayOfWeekShort(date.dayOfWeek),
                        style = small(weekdayColor(date.dayOfWeek, date == data.today)).copy(textAlign = TextAlign.Center),
                    )
                    Text(
                        "${date.dayOfMonth}",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 15.sp,
                            fontWeight = if (date == data.today) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        ),
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    ShiftChip(day, 26)
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

@Composable
private fun MonthCell(data: WidgetData, date: LocalDate, inMonth: Boolean, modifier: GlanceModifier) {
    val day = data.day(date)
    val type = day.shiftType
    val isToday = date == data.today
    Column(modifier.padding(1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${date.dayOfMonth}",
            style = TextStyle(
                color = when {
                    isToday -> GlanceTheme.colors.primary
                    inMonth -> GlanceTheme.colors.onSurface
                    else -> GlanceTheme.colors.outline
                },
                fontSize = 11.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
        if (type != null && inMonth) {
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

/** A rounded square in the shift's color with its label, or a dash for an empty day. */
@Composable
private fun ShiftChip(day: ResolvedDay, sizeDp: Int) {
    val type = day.shiftType
    val color = type?.let { Color(it.colorArgb.toInt()) }
    Box(
        GlanceModifier
            .size(sizeDp.dp)
            .background(if (color == null || type.isOff) Color(0x22888888) else color)
            .cornerRadius((sizeDp / 3).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            type?.label ?: "–",
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

@Composable
private fun weekdayColor(dow: DayOfWeek, today: Boolean): ColorProvider = when {
    today -> GlanceTheme.colors.primary
    dow == DayOfWeek.SUNDAY -> ColorProvider(Color(0xFFE5484D))
    dow == DayOfWeek.SATURDAY -> ColorProvider(Color(0xFF3E7BFA))
    else -> GlanceTheme.colors.onSurfaceVariant
}

private fun small(color: ColorProvider) = TextStyle(color = color, fontSize = 12.sp)

private fun onColor(background: Color): Color = if (background.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White
