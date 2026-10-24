package com.bamnat.app.pay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.app.sleep.QuietHold
import com.bamnat.core.leave.LeaveLot
import com.bamnat.core.leave.LeavePromotion
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [LEAVE-03] 연차 사용 촉진: on the dates 근로기준법 제61조 sets for the company's own notices (6개월 전·2개월 전
 * before a year's leave lapses, 3개월 전·1개월 전 for the first year's monthly days) at 09:00, a notice of the days
 * that would lapse after what is already planned. Replaces 1.21's 11/1 and 12/1, which only fit a 1월 leave year.
 * On by default (S10); off with the switch on the 연차 screen or in 알림 settings.
 * Inexact: a notice an hour late is fine and needs no exact-alarm permission.
 * [SLP-15] Waits while the user sleeps, and opens the 연차 screen.
 */
object LeaveReminder {
    private const val REQUEST = 3201
    const val EXTRA_AT = "at"

    fun reschedule(context: Context, lots: List<LeaveLot>, on: Boolean, now: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = if (on) LeavePromotion.next(lots, now) else null
        if (next == null) {
            am.cancel(pending(context, 0L))
            return
        }
        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, at))
    }

    /** [reschedule] from the ledger as it is now, e.g. after a reboot. */
    suspend fun rescheduleNow(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val c = context.appContainer
        reschedule(context, c.leaveRepository.now().lots.map { it.lot }, c.settingsRepository.current.leaveNotice, now)
    }

    private fun pending(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, LeaveReminderReceiver::class.java).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Works the ledger out now, so leave added since the alarm was set counts, and posts it for the notice
     * that is due (the latest one at or before [now] whose lapse is still ahead) when some would lapse.
     */
    suspend fun postIfLapsing(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val c = context.appContainer
        if (!c.settingsRepository.current.leaveNotice) return
        val summary = c.leaveRepository.now()
        val notice = LeavePromotion.notices(summary.lots.map { it.lot })
            .filter { !it.at.isAfter(now) && !it.lapse.isBefore(now.toLocalDate()) }
            .maxByOrNull { it.at } ?: return
        val days = LeavePromotion.lapsing(summary, notice.lapse) ?: return
        post(context, notice, days)
    }

    fun post(context: Context, notice: LeavePromotion.Notice, days: Double) {
        val notification = NotificationCompat.Builder(context, Notifications.LEAVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle(LeavePromotion.title(days))
            .setContentText(LeavePromotion.text(notice))
            .setStyle(NotificationCompat.BigTextStyle().bigText(LeavePromotion.text(notice) + " 이 알림은 연차 화면에서 끌 수 있어요."))
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.LEAVE, 3202))
            .setAutoCancel(true)
            .build()
        Notifications.post(context, Notifications.LEAVE_ID, notification)
        // [OBS-01] 기록 지점 8: the days left, not the pay they are worth.
        val left = if (days % 1.0 == 0.0) "${days.toInt()}일" else "${days}일"
        runCatching { context.appContainer.journal.note(com.bamnat.core.journal.JournalKind.LEAVE_NOTICE, left) }
    }
}

class LeaveReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val atMillis = intent.getLongExtra(LeaveReminder.EXTRA_AT, 0L)
        val pending = goAsync()
        container.appScope.launch {
            try {
                // An alarm set by 1.10 carries no time: it rang when it was due.
                val at = if (atMillis == 0L) LocalDateTime.now()
                else LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), ZoneId.systemDefault())
                if (!QuietHold.holdIfAsleep(context, QuietHold.Source.LEAVE, at)) LeaveReminder.postIfLapsing(context)
                LeaveReminder.rescheduleNow(context, LocalDateTime.now().plusSeconds(1))
            } finally {
                pending.finish()
            }
        }
    }
}
