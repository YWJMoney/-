package com.bamnat.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.bamnat.app.R
import com.bamnat.app.appContainer
import com.bamnat.core.time.Today

/**
 * [PROV-07] 일어났는지 확인 (프로): some minutes after an alarm is turned off, a notice asks 일어났나요?
 * with 일어났어요. With no answer in [ANSWER_SECONDS] the alarm rings again, once, with its mission; that
 * ring asks no more. Not while the phone is still locked after a reboot.
 */
object WakeCheck {
    const val ANSWER_SECONDS = 90
    const val ACTION_ASK = "com.bamnat.app.alarm.WAKE_ASK"
    const val ACTION_RING = "com.bamnat.app.alarm.WAKE_RING"
    const val ACTION_AWAKE = "com.bamnat.app.alarm.WAKE_AWAKE"
    private const val REQ_ASK = 2101
    private const val REQ_RING = 2102
    private const val REQ_AWAKE = 2103
    private const val PREFS = "wake_check"
    private const val K_RING = "ring"

    /** Asks [minutes] from now about [ring], which rings again if nobody answers. */
    fun schedule(context: Context, ring: RingRequest, minutes: Int, now: Long = Today.millis()) {
        prefs(context).edit().putString(K_RING, ring.encode()).apply()
        set(context, now + minutes * 60_000L, intent(context, ACTION_ASK, REQ_ASK))
        context.appContainer.telemetry.event("wake_check", "scheduled")
    }

    /** The notice, and the ring that follows it unless 일어났어요 comes first. */
    fun ask(context: Context, now: Long = Today.millis()) {
        if (pendingRing(context) == null) return
        // [ALRM-01] R11: without the notification permission nobody could see the question, and the alarm would
        // ring again 90 seconds later for no reason the user could tell. It is left out; 알람 점검 says why.
        if (!Notifications.canPost(context)) {
            context.appContainer.telemetry.event("wake_check", "skipped_no_notifications")
            clear(context)
            return
        }
        set(context, now + ANSWER_SECONDS * 1_000L, intent(context, ACTION_RING, REQ_RING))
        val notification = NotificationCompat.Builder(context, Notifications.STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle("일어났나요?")
            .setContentText("${ANSWER_SECONDS}초 안에 ‘일어났어요’를 누르지 않으면 알람이 다시 울려요")
            .addAction(NotificationCompat.Action.Builder(0, "일어났어요", intent(context, ACTION_AWAKE, REQ_AWAKE))
                .setAuthenticationRequired(false).build())
            .setContentIntent(intent(context, ACTION_AWAKE, REQ_AWAKE))
            .setAutoCancel(true)
            .setTimeoutAfter(ANSWER_SECONDS * 1_000L)
            .build()
        Notifications.post(context, Notifications.WAKE_CHECK_ID, notification)
    }

    /** No answer: the same alarm again, marked so it asks no more. */
    fun ringAgain(context: Context) {
        val ring = pendingRing(context) ?: return
        clear(context)
        Notifications.cancel(context, Notifications.WAKE_CHECK_ID)
        context.appContainer.telemetry.event("wake_check", "rang")
        AlarmService.start(context, ring.copy(recheck = true, isSnooze = false, triggerAtMillis = Today.millis()))
    }

    /** 일어났어요, or a new alarm in the meantime: nothing more for this one. */
    fun awake(context: Context) {
        if (pendingRing(context) != null) context.appContainer.telemetry.event("wake_check", "awake")
        clear(context)
        Notifications.cancel(context, Notifications.WAKE_CHECK_ID)
    }

    /** A new alarm is ringing: the one before needs no question any more. */
    fun cancel(context: Context) {
        if (pendingRing(context) == null) return
        clear(context)
        Notifications.cancel(context, Notifications.WAKE_CHECK_ID)
    }

    fun pendingRing(context: Context): RingRequest? = RingRequest.decode(prefs(context).getString(K_RING, null))

    private fun clear(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am?.cancel(intent(context, ACTION_ASK, REQ_ASK))
        am?.cancel(intent(context, ACTION_RING, REQ_RING))
        prefs(context).edit().remove(K_RING).apply()
    }

    private fun set(context: Context, at: Long, operation: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // Exact, but not an alarm clock: the status bar and 알람 점검 keep showing the next real alarm.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }

    private fun intent(context: Context, action: String, request: Int): PendingIntent = PendingIntent.getBroadcast(
        context, request, Intent(context, WakeCheckReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class WakeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BootAlarms.isLocked(context)) return
        when (intent.action) {
            WakeCheck.ACTION_ASK -> WakeCheck.ask(context)
            WakeCheck.ACTION_RING -> WakeCheck.ringAgain(context)
            WakeCheck.ACTION_AWAKE -> WakeCheck.awake(context)
        }
    }
}
