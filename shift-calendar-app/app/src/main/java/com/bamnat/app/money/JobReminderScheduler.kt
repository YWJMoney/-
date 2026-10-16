package com.bamnat.app.money

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bamnat.app.MainActivity
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.core.money.MoneyAlerts
import com.bamnat.core.money.MoneyBook
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [WORK-02][LEDG-05] Money notifications: a job about to start, money still owed after a job, and
 * fixed costs three days ahead and on the day. One alarm is set for the earliest of them, exact
 * when the user allows it so a 30-minute heads-up is not pushed back by Doze.
 */
object JobReminderScheduler {
    private const val JOB_CHANNEL = "job_reminder_v1"
    private const val MONEY_CHANNEL = "money_alert_v1"
    private const val REQUEST = 3101
    private const val NOTIFICATION_BASE = 7100
    const val EXTRA_AT = "at"

    fun reschedule(context: Context, book: MoneyBook, now: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = MoneyAlerts.next(book.jobs, book.fixed, book.entries, now).firstOrNull()
        if (next == null) {
            am.cancel(pending(context, 0L))
            return
        }
        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val op = pending(context, at)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    private fun pending(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, JobReminderReceiver::class.java).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun post(context: Context, alert: MoneyAlerts.Alert) {
        if (!Notifications.canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val job = alert.key.startsWith("job-")
        nm.createNotificationChannel(
            if (job) NotificationChannel(JOB_CHANNEL, "일감 알림", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "잡아 둔 일정이 다가오면 알려요" }
            else NotificationChannel(MONEY_CHANNEL, "돈 알림", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "곧 나갈 돈과 아직 못 받은 돈을 알려요" },
        )
        val open = PendingIntent.getActivity(
            context, 3102, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, if (job) JOB_CHANNEL else MONEY_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + (alert.key.hashCode() and 0x3ff), notification)
        } catch (e: SecurityException) {
            // Notifications were turned off in the meantime.
        }
    }
}

class JobReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val atMillis = intent.getLongExtra(JobReminderScheduler.EXTRA_AT, 0L)
        val pending = goAsync()
        container.appScope.launch {
            try {
                val money = container.moneyRepository
                val book = MoneyBook(money.allEntries(), money.allJobs(), emptyList(), money.allFixed())
                if (atMillis != 0L) {
                    val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), ZoneId.systemDefault())
                    // Read again now, so a job paid or a bill recorded since is not announced.
                    MoneyAlerts.dueAt(book.jobs, book.fixed, book.entries, at).forEach { JobReminderScheduler.post(context, it) }
                }
                // Plan the next one after this moment, so the same alert never repeats.
                JobReminderScheduler.reschedule(context, book, LocalDateTime.now().plusSeconds(1))
            } finally {
                pending.finish()
            }
        }
    }
}
