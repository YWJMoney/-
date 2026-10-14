package com.shiftmate.calendar.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.shiftmate.calendar.MainActivity
import com.shiftmate.calendar.data.ScheduleSnapshot
import com.shiftmate.core.alarm.AlarmPlanner
import com.shiftmate.core.alarm.PlannedAlarm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** What is about to ring, shown in the app. */
data class UpcomingAlarm(val plan: PlannedAlarm, val triggerAtMillis: Long, val exact: Boolean)

/**
 * Keeps exactly one shift alarm registered with the system: the next one.
 * Called whenever the schedule changes, after an alarm rings, after reboot and on time changes.
 */
class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("alarm_state", Context.MODE_PRIVATE)

    private val _upcoming = MutableStateFlow<UpcomingAlarm?>(null)
    val upcoming: StateFlow<UpcomingAlarm?> = _upcoming.asStateFlow()

    private val _snoozeAt = MutableStateFlow(prefs.getLong(K_SNOOZE_AT, 0L).takeIf { it > System.currentTimeMillis() })
    /** When a pending snooze will ring again, or null. */
    val snoozeAt: StateFlow<Long?> = _snoozeAt.asStateFlow()

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * Plans the first alarm strictly after [after]. Passing the trigger time of the alarm that
     * just rang guarantees the same alarm is never registered twice.
     */
    @Synchronized
    fun reschedule(snapshot: ScheduleSnapshot, after: LocalDateTime = LocalDateTime.now()) {
        val plan = AlarmPlanner.nextAlarm(snapshot.resolver, after)
        val operation = shiftAlarmIntent(plan)
        if (plan == null) {
            alarmManager.cancel(operation)
            _upcoming.value = null
            return
        }
        val triggerAt = plan.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exact = setAlarm(triggerAt, operation)
        _upcoming.value = UpcomingAlarm(plan, triggerAt, exact)
    }

    /** Schedules a one-off snooze alarm that rings again with the same shift details. */
    fun scheduleSnooze(ring: RingRequest, minutes: Int) {
        val triggerAt = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        val snoozed = ring.copy(isSnooze = true)
        setAlarm(triggerAt, snoozeIntent(snoozed))
        prefs.edit()
            .putLong(K_SNOOZE_AT, triggerAt)
            .putString(K_SNOOZE_PAYLOAD, snoozed.encode())
            .apply()
        _snoozeAt.value = triggerAt
    }

    fun cancelSnooze() {
        alarmManager.cancel(snoozeIntent(null))
        clearSnoozeState()
    }

    fun clearSnoozeState() {
        prefs.edit().remove(K_SNOOZE_AT).remove(K_SNOOZE_PAYLOAD).apply()
        _snoozeAt.value = null
    }

    /** Alarms do not survive a reboot; put a pending snooze back if it is still in the future. */
    fun restoreSnooze() {
        val at = prefs.getLong(K_SNOOZE_AT, 0L)
        val ring = RingRequest.decode(prefs.getString(K_SNOOZE_PAYLOAD, null))
        if (ring == null || at <= System.currentTimeMillis()) {
            clearSnoozeState()
            return
        }
        setAlarm(at, snoozeIntent(ring))
    }

    /** Returns true when the alarm is exact. */
    private fun setAlarm(triggerAt: Long, operation: PendingIntent): Boolean {
        return if (canScheduleExact()) {
            val show = PendingIntent.getActivity(
                context, REQ_SHOW,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // setAlarmClock rings on time even in Doze and shows the alarm icon in the status bar.
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
            true
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            false
        }
    }

    private fun shiftAlarmIntent(plan: PlannedAlarm?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SHIFT_ALARM)
        if (plan != null) {
            val triggerAt = plan.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            RingRequest.from(plan, triggerAt).putInto(intent)
        }
        return PendingIntent.getBroadcast(
            context, REQ_SHIFT, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun snoozeIntent(ring: RingRequest?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SNOOZE_ALARM)
        ring?.putInto(intent)
        return PendingIntent.getBroadcast(
            context, REQ_SNOOZE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val REQ_SHIFT = 1001
        private const val REQ_SNOOZE = 1002
        private const val REQ_SHOW = 1003
        private const val K_SNOOZE_AT = "snooze_at"
        private const val K_SNOOZE_PAYLOAD = "snooze_payload"

        fun millisToLocal(millis: Long): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    }
}
