package com.bamnat.app.widget

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bamnat.app.NoticeLinks
import com.bamnat.app.appContainer
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.calendar.clockOutDay
import com.bamnat.core.model.Attendance
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.work.HourlyWork
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * 1.22 묶음 E on the home screen: [DAILY-03] 오늘 모든 조, [DAILY-01] a day's memo line,
 * [DAILY-06] a date that opens its own day, and [DAILY-11] an 알바's 출근·퇴근 buttons.
 */

class TeamsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context)
        provideContent { GlanceTheme { WidgetBox { TeamsContent(data) } } }
    }
}

class TeamsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TeamsWidget()
}

@Composable
private fun WidgetBox(content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(12.dp)
            .clickable(actionStartActivity(NoticeLinks.intent(androidx.glance.LocalContext.current, NoticeLinks.Target.TEAMS))),
    ) { content() }
}

/** [DAILY-03] Each team's shift today, mine marked, with tomorrow's beside it. */
@Composable
internal fun TeamsContent(data: WidgetData) {
    val teams = data.teams
    Column(GlanceModifier.fillMaxSize()) {
        Text("오늘 모든 조 · ${data.today.monthValue}/${data.today.dayOfMonth}(${Fmt.dayOfWeekShort(data.today.dayOfWeek)})",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        Spacer(GlanceModifier.height(6.dp))
        if (teams.isNullOrEmpty()) {
            Text("주주야야비휴처럼 돌아가는 근무 주기를 정하면 조마다 오늘 근무가 보여요",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            return@Column
        }
        for (t in teams) {
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name + if (t.mine) " (내 조)" else "",
                    style = TextStyle(color = if (t.mine) GlanceTheme.colors.primary else GlanceTheme.colors.onSurface, fontSize = 14.sp,
                        fontWeight = if (t.mine) FontWeight.Bold else FontWeight.Normal),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                TeamChip(t.today, 24)
                Spacer(GlanceModifier.width(6.dp))
                Text("내일", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp), maxLines = 1)
                Spacer(GlanceModifier.width(3.dp))
                TeamChip(t.tomorrow, 18)
            }
        }
    }
}

@Composable
private fun TeamChip(type: ShiftType?, sizeDp: Int) {
    val color = type?.let { Color(it.colorArgb.toInt()) }
    Box(
        GlanceModifier.size(sizeDp.dp).background(color ?: Color(0x22888888)).cornerRadius((sizeDp / 3).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(type?.label ?: "휴", style = TextStyle(
            color = if (color == null) GlanceTheme.colors.onSurfaceVariant else ColorProvider(if (color.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White),
            fontSize = (sizeDp * 0.45f).sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/** [DAILY-06] Opens [date] on 내 달력 with the day showing, as tapping it there does. */
internal fun openDay(context: Context, date: LocalDate) =
    actionStartActivity(NoticeLinks.intent(context, NoticeLinks.Target.DAY, payload = date.toString()))

/** [DAILY-01] The day's memo line under its shift. */
@Composable
internal fun MemoLine(text: String) {
    Text("📝 $text", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
}

/**
 * [DAILY-11] 결정 D7-1: an 알바's 출근 and 퇴근 from the today widget, as the day sheet's chips write them
 * (the time now to 5 minutes, 퇴근 of a shift past midnight on the day it started).
 */
class HourlyClockAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repo = context.appContainer.scheduleRepository
        val snapshot = repo.snapshot.first()
        val now = LocalDateTime.now()
        val text = runCatching {
            write(repo, parameters[OUT] == true, snapshot.resolver.resolve(now.toLocalDate()), now) { snapshot.resolver.resolve(now.toLocalDate().minusDays(1)) }
        }.getOrNull()
        withContext(Dispatchers.Main) {
            Toast.makeText(context.applicationContext, text ?: "적지 못했어요. 앱에서 그날을 열어 적어 주세요", Toast.LENGTH_SHORT).show()
        }
        WidgetRefresher.refresh(context)
    }

    companion object {
        val OUT = ActionParameters.Key<Boolean>("out")

        /** What the widget offers today: 출근 before clocking in, 퇴근 once in (or for last night's shift); null for none. */
        fun offered(style: WorkStyle?, today: ResolvedDay, now: LocalDateTime, yesterday: () -> ResolvedDay): Boolean? {
            if (style != WorkStyle.HOURLY) return null
            val out = clockOutDay(today, now.toLocalDate(), now, yesterday)
            val works = today.shiftType?.isOff == false && today.entry?.attendance != Attendance.ABSENT
            return when {
                out != null && out.date != today.date -> true
                works && today.entry?.customStart == null -> false
                out != null -> true
                else -> null
            }
        }

        /** Writes 출근 or 퇴근 at [now]; what to say. */
        suspend fun write(repo: com.bamnat.app.data.ScheduleRepository, out: Boolean, today: ResolvedDay, now: LocalDateTime,
            yesterday: () -> ResolvedDay): String? {
            val at = HourlyWork.clock(now.toLocalTime())
            if (!out) {
                repo.updateDay(today.date) { e -> e.copy(customStart = at, customEnd = e.customEnd ?: today.shiftType?.end) }
                return "출근 ${Fmt.time(at)} 적었어요"
            }
            val day = clockOutDay(today, now.toLocalDate(), now, yesterday) ?: return null
            repo.updateDay(day.date) { e -> e.copy(customEnd = at, customStart = e.customStart ?: day.start ?: day.shiftType?.start) }
            return (if (day.date != today.date) "어제 " else "") + "퇴근 ${Fmt.time(at)} 적었어요"
        }
    }
}

/** [DAILY-11] The widget's 출근 or 퇴근 button. */
@Composable
internal fun HourlyClockButton(out: Boolean, yesterday: Boolean) {
    Text(
        if (out) (if (yesterday) "어제 퇴근" else "퇴근") else "출근",
        style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = GlanceModifier.background(GlanceTheme.colors.primaryContainer).cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clickable(actionRunCallback<HourlyClockAction>(actionParametersOf(HourlyClockAction.OUT to out))),
    )
}
