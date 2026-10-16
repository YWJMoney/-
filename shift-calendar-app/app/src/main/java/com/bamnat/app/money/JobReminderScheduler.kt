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
import com.bamnat.app.ui.Fmt
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobReminders
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [WORK-02] Reminds the user before a booked job. A reminder only needs to arrive about on
 * time, so it uses an idle-allowed alarm and never the exact-alarm permission shift alarms need.
 */
object JobReminderScheduler {
    private const val CHANNEL_ID = "job_reminder_v1"
    private const val REQUEST = 3101
    private const val NOTIFICATION_BASE = 7100
    const val EXTRA_JOB_ID = "job_id"

    fun reschedule(context: Context, jobs: List<Job>, now: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pending(context, null)
        val next = JobReminders.next(jobs, now)
        if (next == null) {
            am.cancel(intent)
            return
        }
        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val op = pending(context, next.job.id)
        // WORK-02: exact when the user allows it, so a 30-minute heads-up isn't pushed back by Doze.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    private fun pending(context: Context, jobId: Long?): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, JobReminderReceiver::class.java).apply { jobId?.let { putExtra(EXTRA_JOB_ID, it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun post(context: Context, job: Job) {
        if (!Notifications.canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "일감 알림", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "잡아 둔 일정이 다가오면 알려요" },
        )
        val open = PendingIntent.getActivity(
            context, 3102, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val time = job.start?.let { Fmt.time(it) }.orEmpty()
        val where = listOf(job.client, job.place).filter { it.isNotBlank() }.joinToString(" · ")
        val text = listOf("$time 시작", where).filter { it.isNotBlank() }.joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(job.title.ifBlank { "일감" })
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + (job.id % 100).toInt(), notification)
        } catch (e: SecurityException) {
            // Notifications were turned off in the meantime.
        }
    }
}

class JobReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val id = intent.getLongExtra(JobReminderScheduler.EXTRA_JOB_ID, 0L)
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (id != 0L) container.moneyRepository.job(id)?.let { JobReminderScheduler.post(context, it) }
                // Plan the next one after this moment, so the same reminder never repeats.
                JobReminderScheduler.reschedule(context, container.moneyRepository.allJobs(), LocalDateTime.now().plusSeconds(1))
            } finally {
                pending.finish()
            }
        }
    }
}
