package com.bamnat.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bamnat.app.MainActivity
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.diag.Telemetry
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.diag.EventBook
import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.alarm.PlannedAlarm
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
class AlarmScheduler(
    private val context: Context,
    private val telemetry: Telemetry,
    /** The ringing settings, copied with the alarms for a locked boot [REL-10]. */
    private val ringSettings: () -> BootAlarms.RingSettings = { BootAlarms.RingSettings() },
) {
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
    fun reschedule(snapshot: ScheduleSnapshot, enabled: Boolean, after: LocalDateTime = LocalDateTime.now()) =
        reschedule(listOf(null to snapshot), enabled, after)

    /**
     * [MCAL-01] The same over several calendars: the earliest alarm of any of them rings, and one from
     * an added calendar carries that calendar's name ("알바 주간 근무 알람"), the main calendar's none.
     */
    @Synchronized
    fun reschedule(calendars: List<Pair<String?, ScheduleSnapshot>>, enabled: Boolean, after: LocalDateTime = LocalDateTime.now()) {
        // [REL-11] An inexact alarm can arrive minutes late. Until it has, re-planning (another
        // alarm or a widget waking the app) must keep it rather than skip past it to the next one.
        val pending = prefs.getLong(K_PENDING, 0L)
        val afterMillis = after.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val from = if (pending in (afterMillis - LATE_KEEP_MILLIS + 1)..afterMillis) millisToLocal(pending - 1_000) else after
        val plan = if (enabled) nextOf(calendars, from) else null
        val operation = shiftAlarmIntent(plan)
        saveBootCopy(if (enabled) planAhead(calendars, from) else emptyList())
        if (plan == null) {
            prefs.edit().remove(K_PENDING).apply()
            alarmManager.cancel(operation)
            if (_upcoming.value != null) telemetry.event(DiagEvents.ALARM_CLEARED, if (enabled) "none_ahead" else "switched_off")
            _upcoming.value = null
            return
        }
        val triggerAt = plan.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exact = setAlarm(triggerAt, operation)
        prefs.edit().putLong(K_PENDING, triggerAt).apply()
        val previous = _upcoming.value
        // The schedule is re-planned on every edit; only a different alarm is worth a log line.
        if (previous == null || previous.triggerAtMillis != triggerAt || previous.exact != exact) {
            telemetry.event(DiagEvents.ALARM_SCHEDULED, EventBook.triggerDetail(triggerAt, "exact=$exact"))
        }
        // Warn once each time the permission goes from on to off, not on every re-plan.
        val wasExact = prefs.getBoolean(K_LAST_EXACT, true)
        if (wasExact != exact) {
            prefs.edit().putBoolean(K_LAST_EXACT, exact).apply()
            if (!exact) Notifications.postExactAlarmWarning(context)
        }
        _upcoming.value = UpcomingAlarm(plan, triggerAt, exact)
    }

    /** The alarm that was registered has rung (or was dropped as stale): plan past it from now on. */
    fun markFired(triggerAt: Long) {
        if (prefs.getLong(K_PENDING, 0L) <= triggerAt) prefs.edit().remove(K_PENDING).apply()
    }

    private var lastAhead: List<RingRequest> = emptyList()

    /** The next [BootAlarms.AHEAD] alarms, so a locked phone can ring several in a row. */
    private fun planAhead(calendars: List<Pair<String?, ScheduleSnapshot>>, after: LocalDateTime): List<RingRequest> {
        val out = ArrayList<RingRequest>()
        var from = after
        while (out.size < BootAlarms.AHEAD) {
            val p = nextOf(calendars, from) ?: break
            out += RingRequest.from(p, p.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            from = p.triggerAt
        }
        return out
    }

    /** [MCAL-01] The first alarm after [from] across [calendars]; at the same minute the earlier calendar's. */
    private fun nextOf(calendars: List<Pair<String?, ScheduleSnapshot>>, from: LocalDateTime): PlannedAlarm? =
        calendars.mapNotNull { (name, snapshot) ->
            AlarmPlanner.nextAlarm(snapshot.resolver, from)?.let { p ->
                if (name == null || p.shiftName.isBlank()) p else p.copy(shiftName = "$name ${p.shiftName}")
            }
        }.minByOrNull { it.triggerAt }

    /** Copies the planned alarms and ringing settings for a locked boot [REL-10]. */
    fun saveBootCopy(ahead: List<RingRequest> = lastAhead) {
        lastAhead = ahead
        BootAlarms.save(context, ahead, ringSettings())
    }

    /** Schedules a one-off snooze alarm that rings again with the same shift details. */
    fun scheduleSnooze(ring: RingRequest, minutes: Int) {
        val triggerAt = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        val snoozed = ring.copy(isSnooze = true)
        setAlarm(triggerAt, snoozeIntent(snoozed))
        BootAlarms.saveSnooze(context, snoozed, triggerAt)
        prefs.edit()
            .putLong(K_SNOOZE_AT, triggerAt)
            .putString(K_SNOOZE_PAYLOAD, snoozed.encode())
            .apply()
        _snoozeAt.value = triggerAt
        // [SLP-15] A quiet notice says when it rings again, with 지금 끄기.
        Notifications.postSnoozeWaiting(context, snoozed, triggerAt)
    }

    fun cancelSnooze() {
        alarmManager.cancel(snoozeIntent(null))
        clearSnoozeState()
    }

    fun clearSnoozeState() {
        BootAlarms.saveSnooze(context, null, 0L)
        prefs.edit().remove(K_SNOOZE_AT).remove(K_SNOOZE_PAYLOAD).apply()
        _snoozeAt.value = null
        Notifications.cancel(context, Notifications.SNOOZE_NOTIFICATION_ID)
    }

    /** Alarms do not survive a reboot; put a pending snooze back if it is still in the future. */
    fun restoreSnooze() {
        // A snooze tapped while still locked after a reboot exists only in the boot copy [REL-10].
        BootAlarms.snooze(context)?.let { (ring, at) ->
            if (prefs.getLong(K_SNOOZE_AT, 0L) < at) {
                prefs.edit().putLong(K_SNOOZE_AT, at).putString(K_SNOOZE_PAYLOAD, ring.encode()).apply()
                _snoozeAt.value = at
            }
        }
        val at = prefs.getLong(K_SNOOZE_AT, 0L)
        val ring = RingRequest.decode(prefs.getString(K_SNOOZE_PAYLOAD, null))
        if (ring == null || at <= System.currentTimeMillis()) {
            clearSnoozeState()
            return
        }
        setAlarm(at, snoozeIntent(ring))
        Notifications.postSnoozeWaiting(context, ring, at)
    }

    private fun setAlarm(triggerAt: Long, operation: PendingIntent): Boolean = setAlarm(context, alarmManager, triggerAt, operation)

    private fun shiftAlarmIntent(plan: PlannedAlarm?): PendingIntent = shiftAlarmIntent(
        context, plan?.let { RingRequest.from(it, it.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) },
    )

    private fun snoozeIntent(ring: RingRequest?): PendingIntent = snoozeIntent(context, ring)

    companion object {
        /** Returns true when the alarm is exact. Usable while the phone is still locked [REL-10]. */
        fun setAlarm(context: Context, alarmManager: AlarmManager, triggerAt: Long, operation: PendingIntent): Boolean {
            return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
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

        fun shiftAlarmIntent(context: Context, ring: RingRequest?): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SHIFT_ALARM)
            ring?.putInto(intent)
            return PendingIntent.getBroadcast(
                context, REQ_SHIFT, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun snoozeIntent(context: Context, ring: RingRequest?): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SNOOZE_ALARM)
            ring?.putInto(intent)
            return PendingIntent.getBroadcast(
                context, REQ_SNOOZE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val K_PENDING = "pending_trigger"
        /** A registered alarm overdue by more than this is not kept; the receiver drops it as stale too. */
        private const val LATE_KEEP_MILLIS = 2 * 60 * 60 * 1000L
        private const val REQ_SHIFT = 1001
        private const val REQ_SNOOZE = 1002
        private const val REQ_SHOW = 1003
        private const val K_SNOOZE_AT = "snooze_at"
        private const val K_SNOOZE_PAYLOAD = "snooze_payload"
        private const val K_LAST_EXACT = "last_exact"

        fun millisToLocal(millis: Long): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    }
}
