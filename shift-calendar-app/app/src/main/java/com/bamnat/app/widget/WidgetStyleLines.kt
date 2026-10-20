package com.bamnat.app.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.pay.LeaveBook
import com.bamnat.core.work.WidgetFigures
import com.bamnat.core.work.WorkStyle

/**
 * [COMN-05] The today widget's line for the kind of work, under today: a part-timer's hours this week
 * and how far 주휴 15시간 is, a day worker's 연차 left and today's 야근. Nothing for shift work.
 */
@Composable
internal fun WorkStyleLine(data: WidgetData) {
    val style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
    when (data.style) {
        WorkStyle.HOURLY -> data.hourlyWeek?.let { w ->
            Column(GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    "이번 주 ${HoursFormat.korean(w.minutes)} · " +
                        if (w.reached) "주휴 15시간 채웠어요" else "주휴까지 ${HoursFormat.korean(w.left)}",
                    style = TextStyle(color = if (w.reached) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(3.dp))
                LinearProgressIndicator(progress = w.progress, modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = GlanceTheme.colors.primary, backgroundColor = GlanceTheme.colors.surfaceVariant)
            }
        }
        WorkStyle.DAY -> {
            val extra = data.day(data.today).extraMinutes
            val parts = listOfNotNull(
                data.leaveLeft?.let { "남은 연차 ${LeaveBook.days(it.coerceAtLeast(0.0))}" },
                extra.takeIf { it > 0 }?.let { "오늘 야근 ${HoursFormat.korean(it)}" },
            )
            if (parts.isNotEmpty()) {
                Spacer(GlanceModifier.height(6.dp))
                Text(parts.joinToString(" · "), style = style, maxLines = 1)
            }
        }
        else -> Unit
    }
}

/**
 * [COMN-05] A field worker's week under the jobs: days worked (or 공수) and whether money is still owed.
 * A home screen is seen by others, so the amount itself stays in the app.
 */
@Composable
internal fun FieldWeekLine(data: WidgetData) {
    val w = data.fieldWeek ?: return
    Spacer(GlanceModifier.height(6.dp))
    Text(
        "이번 주 " + (w.units?.let { WidgetFigures.units(it) } ?: "${w.days}일") +
            if (w.unpaid > 0) " · 받을 돈 있어요" else " · 받을 돈 없음",
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        maxLines = 1,
    )
}
