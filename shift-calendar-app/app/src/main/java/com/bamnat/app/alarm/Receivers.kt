package com.bamnat.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bamnat.app.appContainer
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.diag.EventBook
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Receives the shift alarm and snooze alarms set by [AlarmScheduler]. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val ring = RingRequest.fromIntent(intent)
        when (intent.action) {
            ACTION_SHIFT_ALARM -> {
                val now = System.currentTimeMillis()
                val firedAt = maxOf(ring?.triggerAtMillis ?: now, now)
                val pending = goAsync()
                // A delivery hours late (device was off, clock jumped) is not worth waking anyone for.
                if (ring != null && now - ring.triggerAtMillis < STALE_AFTER_MILLIS) {
                    container.telemetry.event(
                        DiagEvents.ALARM_FIRED,
                        EventBook.triggerDetail(ring.triggerAtMillis, "late_s=${(now - ring.triggerAtMillis) / 1000}"),
                    )
                    startRinging(context, ring)
                } else if (ring != null) {
                    container.telemetry.event(
                        DiagEvents.ALARM_LATE_SKIPPED,
                        EventBook.triggerDetail(ring.triggerAtMillis, "late_s=${(now - ring.triggerAtMillis) / 1000}"),
                    )
                }
                container.appScope.launch {
                    try {
                        container.rescheduleAlarms(after = AlarmScheduler.millisToLocal(firedAt))
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_SNOOZE_ALARM -> {
                container.alarmScheduler.clearSnoozeState()
                container.telemetry.event(DiagEvents.SNOOZE_FIRED)
                if (ring != null) startRinging(context, ring)
            }
        }
    }

    /**
     * Starts the ringing service. Only exact alarms may start a foreground service from the
     * background; if that is refused (exact alarms revoked on Android 12), fall back to a
     * high-priority notification so the alarm is still noticed and the receiver never crashes.
     */
    private fun startRinging(context: Context, ring: RingRequest) {
        try {
            AlarmService.start(context, ring)
        } catch (e: RuntimeException) {
            context.appContainer.telemetry.event(
                DiagEvents.ALARM_FALLBACK,
                EventBook.triggerDetail(ring.triggerAtMillis, e.javaClass.simpleName),
            )
            Notifications.postFallbackAlarm(context, ring)
        }
    }

    companion object {
        const val ACTION_SHIFT_ALARM = "com.bamnat.app.action.SHIFT_ALARM"
        const val ACTION_SNOOZE_ALARM = "com.bamnat.app.action.SNOOZE_ALARM"
        private const val STALE_AFTER_MILLIS = 2 * 60 * 60 * 1000L
    }
}

/** Re-registers alarms after reboot, app update, clock or time zone changes. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        container.telemetry.event(DiagEvents.SYSTEM_EVENT, intent.action?.substringAfterLast('.') ?: "")
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.rescheduleAlarms(LocalDateTime.now())
                container.alarmScheduler.restoreSnooze()
            } finally {
                pending.finish()
            }
        }
    }
}
