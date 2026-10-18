package com.bamnat.app.sleep

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.sleep.SleepDebt
import com.bamnat.core.sleep.SleepEntry
import com.bamnat.core.sleep.SleepSchedule
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/** [SLP-15] "잠들었어요" and "깼어요" on a notice, usable from the lock screen. */
object SleepActions {
    const val ACTION_ASLEEP = "com.bamnat.app.sleep.ASLEEP"
    const val ACTION_AWAKE = "com.bamnat.app.sleep.AWAKE"

    fun asleep(context: Context): NotificationCompat.Action = action(context, "잠들었어요", ACTION_ASLEEP, 3311)
    fun awake(context: Context): NotificationCompat.Action = action(context, "깼어요", ACTION_AWAKE, 3312)

    private fun action(context: Context, title: String, action: String, requestCode: Int): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, title, PendingIntent.getBroadcast(
            context, requestCode, Intent(context, SleepActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )).setAuthenticationRequired(false).build()
}

/** Writes the tap into the sleep log; the 자는 중 notice follows the log by itself. */
class SleepActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    SleepActions.ACTION_ASLEEP -> {
                        container.sleepRepository.fellAsleep()
                        Notifications.cancel(context, Notifications.BEDTIME_ID)
                        Notifications.cancel(context, Notifications.COMMUTE_ID)
                    }
                    SleepActions.ACTION_AWAKE -> container.sleepRepository.wokeUp()
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * [SLP-15] 자는 중: while a logged sleep is open, a quiet notice that stays, counts the time and has
 * the 깼어요 button. After 깼어요 it says how long the sleep was and what comes next, for an hour.
 */
object SleepingNotice {
    private const val WOKE_SHOWN_MILLIS = 60 * 60 * 1000L

    fun show(context: Context, open: SleepEntry, nextAlarm: LocalDateTime?, now: LocalDateTime) {
        val start = open.start
        // A sleep left open past 16 hours is a missed 깼어요, not a sleep: the notice goes by itself.
        val left = SleepDebt.MAX_SLEEP_MINUTES * 60_000 - Duration.between(start, now).toMillis()
        if (left <= 0) return cancel(context)
        val title = (if (start.toLocalDate() == now.toLocalDate()) "" else "어제 ") + "${hm(start)}부터 자는 중"
        val notification = NotificationCompat.Builder(context, Notifications.DAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setSilent(true)
            .setContentTitle(title)
            .setContentText(nextAlarm?.let { "다음 알람 " + (if (it.toLocalDate() == now.toLocalDate()) "" else "내일 ") + hm(it) } ?: "깨면 ‘깼어요’를 눌러 주세요")
            .setWhen(start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.TODAY, 3313))
            .addAction(SleepActions.awake(context))
            .setTimeoutAfter(left)
            .build()
        Notifications.post(context, Notifications.SLEEPING_ID, notification)
    }

    fun woke(context: Context, slept: SleepEntry, next: String?) {
        val minutes = slept.minutes ?: return cancel(context)
        val notification = NotificationCompat.Builder(context, Notifications.DAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSilent(true)
            .setContentTitle("${HoursFormat.korean(minutes.toInt())} 잤어요")
            .apply { next?.let { setContentText("다음: $it") } }
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.TODAY, 3313))
            .setAutoCancel(true)
            .setTimeoutAfter(WOKE_SHOWN_MILLIS)
            .build()
        Notifications.post(context, Notifications.SLEEPING_ID, notification)
    }

    fun cancel(context: Context) = Notifications.cancel(context, Notifications.SLEEPING_ID)

    /** "오늘 19:00 야간": the next work after [now], for the line under how long the sleep was. */
    fun nextWork(resolver: ScheduleResolver, now: LocalDateTime): String? {
        val today = now.toLocalDate()
        val w = SleepSchedule.of(resolver, today, today.plusDays(6), alarmsOn = false).work
            .firstOrNull { it.from.isAfter(now) } ?: return null
        val day = when (w.from.toLocalDate()) {
            today -> "오늘"
            today.plusDays(1) -> "내일"
            else -> "${w.from.monthValue}/${w.from.dayOfMonth}"
        }
        return "$day ${hm(w.from)} ${w.name}"
    }

    private fun hm(x: LocalDateTime) = "%02d:%02d".format(x.hour, x.minute)
}
