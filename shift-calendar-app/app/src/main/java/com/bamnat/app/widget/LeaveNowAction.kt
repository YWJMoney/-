package com.bamnat.app.widget

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.bamnat.app.appContainer
import com.bamnat.app.ui.calendar.LeaveNowWriter
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import com.bamnat.core.time.Today

/**
 * [DAYW-03] 지금 퇴근 from the today widget of a day calendar: the main calendar's today gets 잔업 from
 * the end to now, or an earlier end, as the day sheet's chip does. Said back in a short toast.
 */
class LeaveNowAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = context.appContainer
        val repo = container.scheduleRepository
        val snapshot = repo.snapshot.first()
        val day = snapshot.resolver.resolve(Today.date())
        val (_, text) = runCatching {
            LeaveNowWriter.write(repo, day, Today.dateTime(), snapshot.kinds.name(ExtraKind.OVERTIME_ID))
        }.getOrDefault(null to null)
        withContext(Dispatchers.Main) {
            Toast.makeText(context.applicationContext, text ?: "퇴근 시각이에요. 따로 적을 게 없어요", Toast.LENGTH_SHORT).show()
        }
        WidgetRefresher.refresh(context)
    }

    companion object {
        /** Today's working day on a day calendar with times, the only day the button shows on. */
        fun offered(style: WorkStyle?, day: ResolvedDay): Boolean =
            style == WorkStyle.DAY && day.isWorkDay && day.shiftFrom != null
    }
}

/** [DAYW-03] The widget's 지금 퇴근 button. */
@Composable
internal fun LeaveNowWidgetButton() {
    Text(
        "지금 퇴근",
        style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = GlanceModifier
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clickable(actionRunCallback<LeaveNowAction>()),
    )
}
