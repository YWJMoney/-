package com.bamnat.app.alarm

import com.bamnat.app.invest.investCalendars
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
                ACTION_TEST_ALARM -> ring?.let { startRinging(context, it) }
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
            ACTION_TEST_ALARM -> {
                // [REL-13] The 알람 점검 test: rings like a shift alarm, then leaves the schedule alone.
                container.alarmScheduler.clearTest()
                if (ring != null) {
                    container.telemetry.event(
                        DiagEvents.ALARM_TEST_FIRED,
                        EventBook.triggerDetail(ring.triggerAtMillis, "late_s=${(System.currentTimeMillis() - ring.triggerAtMillis) / 1000}"),
                    )
                    startRinging(context, ring)
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
        const val ACTION_TEST_ALARM = "com.bamnat.app.action.TEST_ALARM"
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
            // [DAYW-07] Geofences and the wait after a leave do not survive a reboot; put back on their own,
            // so a failure in the alarms below never skips them.
            val booted = intent.action == Intent.ACTION_BOOT_COMPLETED
            runCatching {
                if (com.bamnat.app.data.CommutePrefs.get(context).current.enabled) {
                    // A clock or time zone change keeps the geofence; a reboot or an update may not.
                    com.bamnat.app.commute.CommuteRecorder.sync(context, force = booted || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
                    com.bamnat.app.commute.CommuteRecorder.resume(context)
                }
            }
            // [REL-13] The test alarm did not survive the reboot; 알람 점검 must not wait for it.
            if (booted) runCatching { container.alarmScheduler.cancelTest() }
            try {
                container.rescheduleAlarms(LocalDateTime.now())
                container.alarmScheduler.restoreSnooze()
                val settings = container.settingsRepository.current
                // [MCAL-06] Every calendar's jobs and money.
                com.bamnat.app.money.JobReminderScheduler.reschedule(context, container.calendars.everyMoneyNow(), settings)
                com.bamnat.app.pay.LeaveReminder.reschedule(context, container.payStore.profile.value, settings)
                // [SHR-12] The link's expiry notice did not survive the reboot either.
                container.calendars.calendars.value.flatMap { com.bamnat.app.share.ShareLinkKey.all(it.id) }.forEach { key ->
                    val links = container.shareLinksOf(key)
                    com.bamnat.app.share.ShareExpiryReminder.reschedule(context, key, links.link.value, links.noticedFor)
                }
                // [SCHED-01] Memo schedules did not survive the reboot either.
                com.bamnat.app.memo.MemoScheduleAlarm.reschedule(context, container.memoRepository.memos.first())
                // [INV-08] The investor's alarms too.
                com.bamnat.app.invest.InvestNotices.reschedule(context, com.bamnat.app.invest.InvestNotices.plansNow(container))
                com.bamnat.app.invest.InvestMonthNotice.schedule(context, container.investCalendars().isNotEmpty())
                com.bamnat.app.sleep.DayNoticeScheduler.reschedule(context, container.scheduleRepository.snapshot.first(), settings)
                // [MON-06] The trial's question before it turns paid.
                com.bamnat.app.tier.TrialNotice.reschedule(context, container.proStore.current)
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
