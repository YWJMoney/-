package com.bamnat.app.alarm

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bamnat.app.R
import com.bamnat.app.appContainer
import com.bamnat.app.NoticeLinks
import com.bamnat.app.share.ShareLinkPayload
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.ScheduleResolver
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

/**
 * [FEAT-18] 잠금 화면 '오늘 근무': a quiet notice that stays, so today's and tomorrow's shift can be
 * read on the lock screen without unlocking. Android has no lock-screen widgets on phones, so this
 * stands in for one. Off by default; turned on in 설정 › 알림. It moves on at midnight with the widgets.
 */
object TodayShiftNotice {
    const val ID = 7314

    /** Posts or removes the notice; returns true while it is shown, so a midnight wake-up is kept. */
    suspend fun update(context: Context, today: LocalDate = LocalDate.now()): Boolean {
        val container = context.appContainer
        if (!container.settingsRepository.current.todayShiftNotice || !Notifications.canPost(context)) {
            cancel(context)
            return false
        }
        val snapshot = container.scheduleRepository.snapshot.first()
        if (snapshot.isEmpty) {
            cancel(context)
            return false
        }
        val (title, text) = lines(snapshot.resolver, today)
        val notification = NotificationCompat.Builder(context, Notifications.TODAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.TODAY, 3314))
            .build()
        Notifications.post(context, ID, notification)
        return true
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    /** "오늘 주간 07:00~19:00" over "내일 야간 19:00~07:00". */
    fun lines(resolver: ScheduleResolver, today: LocalDate): Pair<String, String> =
        "오늘 ${describe(resolver.resolve(today))}" to "내일 ${describe(resolver.resolve(today.plusDays(1)))}"

    internal fun describe(day: ResolvedDay): String {
        // The lock screen is seen by anyone nearby: the kind of leave shows only as a link shows it.
        val leave = day.leave?.let { ShareLinkPayload.leaveLabel(it, kinds = false) }
        val name = day.shiftType?.name?.takeIf { it.isNotBlank() }
        val start = day.start
        val end = day.end
        return when {
            leave != null -> leave
            day.isWorkDay && start != null && end != null -> "${name ?: "근무"} ${hm(start)}~${hm(end)}"
            day.isWorkDay -> name ?: "근무"
            name != null -> name
            else -> "쉬는 날"
        }
    }

    private fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
}
