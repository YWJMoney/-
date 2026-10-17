package com.bamnat.app.pay

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
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.pay.LeaveBook
import com.bamnat.core.pay.PayProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [PAY-12][REL-08] On 11/1 and 12/1 at 09:00, a notice of 연차 that lapses on 12/31 unless used.
 * Inexact: a notice an hour late is fine and needs no exact-alarm permission. Nothing is set
 * while the user has not entered how many days they get.
 */
object LeaveReminder {
    private const val CHANNEL = "leave_notice_v1"
    private const val REQUEST = 3201
    private const val NOTIFICATION_ID = 7201

    fun reschedule(context: Context, profile: PayProfile?, now: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val op = PendingIntent.getBroadcast(
            context, REQUEST, Intent(context, LeaveReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (profile?.rules?.annualLeaveDays == null) {
            am.cancel(op)
            return
        }
        val at = LeaveBook.nextNotice(now).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
    }

    fun post(context: Context, days: String, until: LocalDate) {
        if (!Notifications.canPost(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "연차 알림", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "올해 안 쓰면 없어지는 연차를 11월과 12월 첫날에 알려요" },
        )
        val open = PendingIntent.getActivity(
            context, 3202, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("남은 연차 $days")
            .setContentText("${until.monthValue}월 ${until.dayOfMonth}일까지 안 쓰면 없어질 수 있어요. 달력에서 날을 길게 눌러 넣어요.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications were turned off in the meantime.
        }
    }
}

class LeaveReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val pending = goAsync()
        container.appScope.launch {
            try {
                val profile = container.payStore.profile.value
                val rules = profile?.rules
                val today = LocalDate.now()
                if (rules?.annualLeaveDays != null) {
                    // Read the calendar now, so leave added since the alarm was set counts.
                    val snapshot = container.scheduleRepository.snapshot.first()
                    val balance = LeaveBook.balance(today.year, rules.annualLeaveDays, today, snapshot.resolver::resolve,
                        free = { rules.holidayLeaveFree && KoreanHolidays.isHoliday(it) }, carried = rules.annualLeaveCarry)
                    balance.expiring(today)?.let { LeaveReminder.post(context, LeaveBook.days(it), balance.expiresOn) }
                }
                LeaveReminder.reschedule(context, profile, LocalDateTime.now().plusSeconds(1))
            } finally {
                pending.finish()
            }
        }
    }
}
