package com.bamnat.app.alarm

import kotlinx.coroutines.flow.first
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bamnat.app.appContainer
import com.bamnat.app.widget.WidgetRefresher
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.diag.EventBook
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Receives the shift alarm and snooze alarms set by [AlarmScheduler]. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ring = RingRequest.fromIntent(intent)
        if (BootAlarms.isLocked(context)) {
            // [REL-10] Rebooted and not unlocked yet: ring from the intent and line up the next one
            // from the device-protected copy; the full re-plan runs once the phone is unlocked.
            when (intent.action) {
                ACTION_SHIFT_ALARM -> if (ring != null && System.currentTimeMillis() - ring.triggerAtMillis < STALE_AFTER_MILLIS) {
                    startRinging(context, ring)
                }
                ACTION_SNOOZE_ALARM -> {
                    BootAlarms.saveSnooze(context, null, 0L)
                    Notifications.cancel(context, Notifications.SNOOZE_NOTIFICATION_ID)
                    ring?.let { startRinging(context, it) }
                }
            }
            BootAlarms.registerNext(context, after = ring?.triggerAtMillis ?: System.currentTimeMillis())
            return
        }
        val container = context.appContainer
        when (intent.action) {
            ACTION_SHIFT_ALARM -> {
                val now = System.currentTimeMillis()
                val firedAt = maxOf(ring?.triggerAtMillis ?: now, now)
                ring?.let { container.alarmScheduler.markFired(it.triggerAtMillis) }
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
                // A short wake lock carries the phone until the ringing service holds its own.
                if (ring != null) {
                    (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "bamnat:snooze").acquire(10_000L)
                    startRinging(context, ring)
                }
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
            if (!BootAlarms.isLocked(context)) context.appContainer.telemetry.event(
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
                val settings = container.settingsRepository.current
                container.moneyRepository.let { m ->
                    com.bamnat.app.money.JobReminderScheduler.reschedule(context,
                        com.bamnat.core.money.MoneyBook(m.allEntries(), m.allJobs(), emptyList(), m.allFixed(), m.allReceivables()), settings)
                }
                com.bamnat.app.pay.LeaveReminder.reschedule(context, container.payStore.profile.value, settings)
                // [SCHED-01] Memo schedules did not survive the reboot either.
                com.bamnat.app.memo.MemoScheduleAlarm.reschedule(context, container.memoRepository.memos.first())
                com.bamnat.app.sleep.DayNoticeScheduler.reschedule(context, container.scheduleRepository.snapshot.first(), settings)
                // [SLP-15] What waited while the user slept: its alarm did not survive the reboot either.
                com.bamnat.app.sleep.QuietHold.release(context)
                // A new date or time zone changes what "today" is on the widgets.
                WidgetRefresher.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * [REL-12] The ringing notification's 끄기 and 다시 울림 buttons, usable without unlocking.
 * [SLP-15] And 지금 끄기 on the notice of a snooze still to ring.
 */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmService.ACTION_DISMISS -> AlarmService.dismiss(context)
            AlarmService.ACTION_SNOOZE -> AlarmService.snooze(context)
            ACTION_CANCEL_SNOOZE -> if (BootAlarms.isLocked(context)) {
                // [REL-10] Still locked after a reboot: the snooze lives only in the device-protected copy.
                context.getSystemService(android.app.AlarmManager::class.java)?.cancel(AlarmScheduler.snoozeIntent(context, null))
                BootAlarms.saveSnooze(context, null, 0L)
                Notifications.cancel(context, Notifications.SNOOZE_NOTIFICATION_ID)
            } else {
                context.appContainer.alarmScheduler.cancelSnooze()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_SNOOZE = "com.bamnat.app.alarm.CANCEL_SNOOZE"
    }
}
