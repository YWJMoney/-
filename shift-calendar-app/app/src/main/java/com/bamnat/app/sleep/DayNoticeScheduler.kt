package com.bamnat.app.sleep

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.notify.DayNotices
import com.bamnat.core.sleep.SleepDebt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import com.bamnat.core.time.Today

/**
 * [SLP-15] 하루 브리핑, 퇴근길 안전 and 잘 준비, planned by [DayNotices] from the schedule and the
 * alarms. One alarm for the next of them, planned again whenever the schedule or the settings
 * change, so a swapped shift moves them too. On time, because a commute notice more than ten
 * minutes late is dropped rather than sent mid-drive.
 */
object DayNoticeScheduler {
    private const val REQUEST = 3301
    const val EXTRA_AT = "at"

    fun wanted(s: AppSettings) = DayNotices.Wanted(briefing = s.briefing, commute = s.commuteNotice, bedtime = s.bedtimeNotice)

    /** Every notice from yesterday to a few days ahead; none for work that does not come in shifts and hours. */
    fun plan(snapshot: ScheduleSnapshot, settings: AppSettings, now: LocalDateTime): List<DayNotices.Notice> =
        if (settings.workStyle?.usesPayEngine == false) emptyList()
        else DayNotices.plan(now, snapshot.resolver, settings.alarmsEnabled, wanted(settings), settings.sleepTargetMinutes, settings.commuteMinutes)

    fun reschedule(context: Context, snapshot: ScheduleSnapshot, settings: AppSettings, now: LocalDateTime = Today.dateTime()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = plan(snapshot, settings, now).firstOrNull { it.at.isAfter(now) }
        if (next == null) {
            am.cancel(pending(context, 0L))
            return
        }
        val at = next.at.atZone(Today.zone()).toInstant().toEpochMilli()
        val op = pending(context, at)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
    }

    private fun pending(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, SleepReminderReceiver::class.java).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun post(context: Context, n: DayNotices.Notice, now: LocalDateTime) {
        val left = Duration.between(now, n.until).toMillis()
        if (left <= 0) return
        val commute = n.kind == DayNotices.Kind.COMMUTE
        val builder = NotificationCompat.Builder(context, if (commute) Notifications.COMMUTE_CHANNEL_ID else Notifications.DAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${n.text}\n일정에 맞춘 참고 정보예요"))
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.TODAY, 3302 + n.kind.ordinal))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(left)
        // Read at a glance on the lock screen, without unlocking on the way home.
        if (commute) builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (n.kind != DayNotices.Kind.BRIEFING) builder.addAction(SleepActions.asleep(context))
        Notifications.post(context, id(n.kind), builder.build())
    }

    fun id(kind: DayNotices.Kind): Int = when (kind) {
        DayNotices.Kind.BRIEFING -> Notifications.BRIEFING_ID
        DayNotices.Kind.BEDTIME -> Notifications.BEDTIME_ID
        DayNotices.Kind.COMMUTE -> Notifications.COMMUTE_ID
    }
}

/**
 * The alarm for the next day notice. The name stays from 1.10, when it sent sleep tips, so an
 * alarm set by that version still arrives here after the update.
 */
class SleepReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val atMillis = intent.getLongExtra(DayNoticeScheduler.EXTRA_AT, 0L)
        val pending = goAsync()
        container.appScope.launch {
            try {
                val settings = container.settingsRepository.current
                val snapshot = container.scheduleRepository.snapshot.first()
                val now = Today.dateTime()
                if (atMillis != 0L) {
                    val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), Today.zone())
                    // Asleep by the log already: no bed time or commute notice to wake up to.
                    val asleep = container.sleepRepository.recent(days = 2).first().any { e ->
                        e.end == null && !e.imported && Duration.between(e.start, now).toMinutes() < SleepDebt.MAX_SLEEP_MINUTES
                    }
                    // Planned again now: a notice whose work moved since is not sent.
                    DayNotices.due(DayNoticeScheduler.plan(snapshot, settings, at), at, now)
                        .filter { !asleep || it.kind == DayNotices.Kind.BRIEFING }
                        .forEach { DayNoticeScheduler.post(context, it, now) }
                }
                DayNoticeScheduler.reschedule(context, snapshot, settings, now.plusSeconds(1))
            } finally {
                pending.finish()
            }
        }
    }
}
