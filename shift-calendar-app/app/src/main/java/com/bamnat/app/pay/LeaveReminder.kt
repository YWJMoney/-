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
import com.bamnat.app.data.AppSettings
import com.bamnat.app.sleep.QuietHold
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.pay.LeaveBook
import com.bamnat.core.pay.PayProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [PAY-12][REL-08] On 11/1 and 12/1 at 09:00, a notice of 연차 that lapses on 12/31 unless used.
 * Inexact: a notice an hour late is fine and needs no exact-alarm permission. Nothing is set
 * while the user has not entered how many days they get.
 * [SLP-15] Off with its own switch, waits while the user sleeps, and opens the 연차 screen.
 */
object LeaveReminder {
    private const val REQUEST = 3201
    const val EXTRA_AT = "at"

    fun reschedule(context: Context, profile: PayProfile?, settings: AppSettings, now: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (profile?.rules?.annualLeaveDays == null || !settings.leaveNotice) {
            am.cancel(pending(context, 0L))
            return
        }
        val at = LeaveBook.nextNotice(now).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, at))
    }

    private fun pending(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, LeaveReminderReceiver::class.java).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Works the balance out now, so leave added since the alarm was set counts, and posts it when some lapses. */
    suspend fun postIfLapsing(context: Context) {
        val c = context.appContainer
        if (!c.settingsRepository.current.leaveNotice) return
        val rules = c.payStore.profile.value?.rules ?: return
        val total = rules.annualLeaveDays ?: return
        val today = LocalDate.now()
        val snapshot = c.scheduleRepository.snapshot.first()
        val balance = LeaveBook.balance(today.year, total, today, snapshot.resolver::resolve,
            free = { rules.holidayLeaveFree && KoreanHolidays.isHoliday(it) }, carried = rules.annualLeaveCarry)
        balance.expiring(today)?.let { post(context, LeaveBook.days(it), balance.expiresOn) }
    }

    fun post(context: Context, days: String, until: LocalDate) {
        val notification = NotificationCompat.Builder(context, Notifications.LEAVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle("남은 연차 $days")
            .setContentText("${until.monthValue}월 ${until.dayOfMonth}일까지 안 쓰면 없어질 수 있어요. 달력에서 날을 길게 눌러 넣어요.")
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.LEAVE, 3202))
            .setAutoCancel(true)
            .build()
        Notifications.post(context, Notifications.LEAVE_ID, notification)
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
                LeaveReminder.reschedule(context, container.payStore.profile.value, container.settingsRepository.current,
                    LocalDateTime.now().plusSeconds(1))
            } finally {
                pending.finish()
            }
        }
    }
}
