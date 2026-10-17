package com.bamnat.app.sleep

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bamnat.app.MainActivity
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.sleep.SleepPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [SLP-04][SLP-05][SLP-07] Notifications for the sleep times in the day view: nap, sleep after a
 * night, bedtime before an early shift, the last coffee, and one drowsy-driving reminder when a
 * night shift ends. Off until the user turns them on. One inexact alarm for the next of them,
 * planned again whenever the schedule or the settings change, so a swapped shift moves them too.
 */
object SleepReminder {
    private const val CHANNEL = "sleep_tips_v1"
    private const val REQUEST = 3301
    private const val NOTIFICATION_BASE = 7300
    const val EXTRA_AT = "at"
    private const val LOOK_AHEAD_DAYS = 3L

    /** Tips that may notify, from yesterday (a night that ends this morning) on. */
    fun upcoming(snapshot: ScheduleSnapshot, settings: AppSettings, now: LocalDateTime): List<SleepPlan.Tip> {
        val r = snapshot.resolver
        val today = now.toLocalDate()
        return (-1L..LOOK_AHEAD_DAYS).flatMap { d ->
            val date = today.plusDays(d)
            SleepPlan.forDay(r.resolve(date.minusDays(1)), r.resolve(date), r.resolve(date.plusDays(1)), settings.sleepTargetMinutes)
        }.filter { it.at.isAfter(now) }.distinctBy { it.kind to it.at }.sortedBy { it.at }
    }

    fun reschedule(context: Context, snapshot: ScheduleSnapshot, settings: AppSettings, now: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = if (settings.sleepReminders) upcoming(snapshot, settings, now).firstOrNull() else null
        if (next == null) {
            am.cancel(pending(context, 0L))
            return
        }
        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Inexact is enough: a sleep tip a few minutes late does no harm and needs no extra permission.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, at))
    }

    private fun pending(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, SleepReminderReceiver::class.java).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun post(context: Context, tip: SleepPlan.Tip) {
        if (!Notifications.canPost(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "수면 알림", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "근무에 맞춘 잠자리·낮잠·카페인 시각과 야간 뒤 퇴근길 졸음 주의" },
        )
        val open = PendingIntent.getActivity(
            context, 3302, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tip.kind.label)
            .setContentText(tip.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${tip.text}\n${tip.basis.label} · 일정에 맞춘 제안이에요"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + tip.kind.ordinal, notification)
        } catch (e: SecurityException) {
            // Notifications were turned off in the meantime.
        }
    }
}

class SleepReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val atMillis = intent.getLongExtra(SleepReminder.EXTRA_AT, 0L)
        val pending = goAsync()
        container.appScope.launch {
            try {
                val settings = container.settingsRepository.current
                val snapshot = container.scheduleRepository.snapshot.first()
                if (settings.sleepReminders && atMillis != 0L) {
                    // Read the schedule again now: a tip whose shift moved since is not sent.
                    val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), ZoneId.systemDefault())
                    SleepReminder.upcoming(snapshot, settings, at.minusMinutes(1))
                        .filter { !it.at.isAfter(at) }
                        .forEach { SleepReminder.post(context, it) }
                }
                SleepReminder.reschedule(context, snapshot, settings, LocalDateTime.now().plusSeconds(1))
            } finally {
                pending.finish()
            }
        }
    }
}
