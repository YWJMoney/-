package com.bamnat.app.money

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
import com.bamnat.app.data.MoneyRepository
import com.bamnat.app.sleep.QuietHold
import com.bamnat.core.money.MoneyAlerts
import com.bamnat.core.money.MoneyBook
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [WORK-02][LEDG-05] Money notifications: a job about to start, money still owed after a job or on a
 * receivable [RECV-03], and
 * fixed costs three days ahead and on the day. One alarm is set for the earliest of them, exact
 * when the user allows it so a 30-minute heads-up is not pushed back by Doze.
 * [SLP-15] Money due or owed waits while the user sleeps; a job never does. Several at once come
 * as one notice per kind.
 */
object JobReminderScheduler {
    private const val REQUEST = 3101
    private const val NOTIFICATION_BASE = 7100
    const val EXTRA_AT = "at"

    private fun isJob(a: MoneyAlerts.Alert) = a.key.startsWith("job-")

    /** Alerts the user wants: a job's own reminder, and money only while 나갈 돈·받을 돈 is on. */
    private fun wanted(a: MoneyAlerts.Alert, settings: AppSettings) = isJob(a) || settings.moneyNotices

    fun reschedule(context: Context, book: MoneyBook, settings: AppSettings, now: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = MoneyAlerts.all(book.jobs, book.fixed, book.entries, now.toLocalDate(), book.receivables, book.accounts, book.allStyles)
            .filter { it.at.isAfter(now) && wanted(it, settings) }.minOfOrNull { it.at }
        if (next == null) {
            am.cancel(pending(context, 0L))
            return
        }
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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

    /** What is due at [at], read again now so a job paid or a bill recorded since is not announced. */
    internal suspend fun dueAt(money: MoneyRepository, settings: AppSettings, at: LocalDateTime): List<MoneyAlerts.Alert> =
        dueAt(MoneyBook(money.allEntries(), money.allJobs(), emptyList(), money.allFixed(), money.allReceivables(), money.allAccounts()), settings, at)

    /** [MCAL-06] The same from every calendar's money at once. */
    internal fun dueAt(book: MoneyBook, settings: AppSettings, at: LocalDateTime): List<MoneyAlerts.Alert> {
        return MoneyAlerts.dueAt(book.jobs, book.fixed, book.entries, at, book.receivables, book.accounts, book.allStyles).filter { wanted(it, settings) }
    }

    /** [SLP-15] Money held while the user slept, now that they are up. */
    suspend fun postMoneyDue(context: Context, at: LocalDateTime) {
        val c = context.appContainer
        postMoney(context, dueAt(c.calendars.everyMoneyNow(), c.settingsRepository.current, at).filterNot(::isJob))
    }

    fun postJob(context: Context, alert: MoneyAlerts.Alert) {
        val notification = NotificationCompat.Builder(context, Notifications.JOB_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.MONEY, 3102))
            .setAutoCancel(true)
            .build()
        Notifications.post(context, NOTIFICATION_BASE + (alert.key.hashCode() and 0x3ff), notification)
    }

    /** One notice per kind ("오늘 나갈 돈 2건"), the lines under it. */
    fun postMoney(context: Context, alerts: List<MoneyAlerts.Alert>) {
        for ((title, list) in alerts.groupBy { it.title }) {
            val builder = NotificationCompat.Builder(context, Notifications.MONEY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentTitle(if (list.size == 1) title else "$title ${list.size}건")
                .setContentText(list.joinToString(" · ") { it.text })
                .setContentIntent(Notifications.open(context, NoticeLinks.Target.MONEY, 3103))
                .setAutoCancel(true)
            if (list.size > 1) builder.setStyle(NotificationCompat.InboxStyle().also { s -> list.forEach { s.addLine(it.text) } })
            Notifications.post(context, NOTIFICATION_BASE + 0x400 + (title.hashCode() and 0xff), builder.build())
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
                // [MCAL-06] Every calendar's jobs, bills and money owed.
                val settings = container.settingsRepository.current
                // [LEDG-09] Repeating lines that write themselves are written before their notice says so.
                for (cal in container.calendars.calendars.value) {
                    runCatching { container.calendars.money(cal.id).catchUpRecurring(java.time.LocalDate.now()) }
                }
                if (atMillis != 0L) {
                    val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), ZoneId.systemDefault())
                    val (jobs, rest) = JobReminderScheduler.dueAt(container.calendars.everyMoneyNow(), settings, at).partition { it.key.startsWith("job-") }
                    jobs.forEach { JobReminderScheduler.postJob(context, it) }
                    if (rest.isNotEmpty() && !QuietHold.holdIfAsleep(context, QuietHold.Source.MONEY, at)) {
                        JobReminderScheduler.postMoney(context, rest)
                    }
                }
                // Plan the next one after this moment, so the same alert never repeats.
                JobReminderScheduler.reschedule(context, container.calendars.everyMoneyNow(), settings, LocalDateTime.now().plusSeconds(1))
            } finally {
                pending.finish()
            }
        }
    }
}
