package com.bamnat.app.memo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.alarm.AlarmService
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.alarm.RingRequest
import com.bamnat.app.appContainer
import com.bamnat.core.memo.Memo
import com.bamnat.core.memo.MemoSchedules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [SCHED-01] Memos that are schedules: one system alarm for the next of them. At that minute each
 * memo shows a notice, rings like an alarm, or both, as the user chose. A ringing schedule is set
 * as an alarm clock, so it is on time in Doze like the shift alarm; a notice-only one is exact
 * when the phone allows it.
 */
object MemoScheduleAlarm {
    private const val REQUEST = 3201
    private const val NOTIFICATION_BASE = 7400
    private const val EXTRA_AT = "at"
    /** A schedule delivered later than this (the phone was off) is not announced any more. */
    private const val STALE_AFTER_MILLIS = 2 * 60 * 60 * 1000L

    fun reschedule(context: Context, memos: List<Memo>, after: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = MemoSchedules.next(memos, after)
        if (next == null) {
            am.cancel(pending(context, 0L))
            return
        }
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val op = pending(context, at)
        val rings = MemoSchedules.dueAt(memos, next).any { it.reminder?.ring == true }
        when {
            rings -> AlarmScheduler.setAlarm(context, am, at, op)
            Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms() -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    private fun pending(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, MemoScheduleReceiver::class.java).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** What the memo is called in a notice or on the ringing screen. */
    fun label(memo: Memo): String = memo.title.ifBlank { memo.preview }.ifBlank { "일정" }.take(40)

    private val time = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

    fun post(context: Context, memo: Memo, at: LocalDateTime) {
        val body = memo.preview.takeIf { it.isNotBlank() && memo.title.isNotBlank() }
        val notification = NotificationCompat.Builder(context, Notifications.MEMO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle(label(memo))
            .setContentText(listOfNotNull("${at.format(time)} 일정", body).joinToString(" · "))
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.MEMOS, REQUEST + 1))
            .setAutoCancel(true)
            .build()
        Notifications.post(context, NOTIFICATION_BASE + (memo.id.toInt() and 0x3ff), notification)
    }

    internal fun handle(context: Context, memos: List<Memo>, atMillis: Long, now: Long = System.currentTimeMillis()) {
        if (atMillis == 0L || now - atMillis > STALE_AFTER_MILLIS) return
        val at = AlarmScheduler.millisToLocal(atMillis)
        val due = MemoSchedules.dueAt(memos, at)
        due.filter { it.reminder?.notify == true }.forEach { post(context, it, at) }
        val ringing = due.filter { it.reminder?.ring == true }
        if (ringing.isEmpty()) return
        val ring = RingRequest(
            shiftName = "",
            shiftDate = at.toLocalDate(),
            shiftStart = at.toLocalTime(),
            offsetMinutes = 0,
            soundUri = null,
            triggerAtMillis = atMillis,
            custom = true,
            label = ringing.joinToString(" · ") { label(it) }.take(60),
        )
        try {
            AlarmService.start(context, ring)
        } catch (e: RuntimeException) {
            // Not allowed to start the ringing screen from here: the loud notice rings instead.
            Notifications.postFallbackAlarm(context, ring)
        }
    }
}

class MemoScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val atMillis = intent.getLongExtra("at", 0L)
        val pending = goAsync()
        container.appScope.launch {
            try {
                // Read again now, so a memo changed or deleted since the alarm was set is not announced.
                val memos = container.memoRepository.memos.first()
                MemoScheduleAlarm.handle(context, memos, atMillis)
                val after = if (atMillis != 0L) AlarmScheduler.millisToLocal(atMillis) else LocalDateTime.now()
                MemoScheduleAlarm.reschedule(context, memos, maxOf(after, LocalDateTime.now().minusSeconds(1)))
            } finally {
                pending.finish()
            }
        }
    }
}
