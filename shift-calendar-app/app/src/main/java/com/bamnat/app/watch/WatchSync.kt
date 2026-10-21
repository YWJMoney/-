package com.bamnat.app.watch

import android.content.Context
import com.bamnat.app.alarm.UpcomingAlarm
import com.bamnat.app.appContainer
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.work.WatchDays
import com.bamnat.core.work.WatchPayload
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * [SHFT-08][REL-09] Sends the main calendar (the one the home widgets show) to a paired Wear OS watch through
 * the Wearable Data Layer: the days from last month's 1st to the end of next month ([WatchDays.window]), the next alarm
 * and when it was sent. The Data Layer goes phone to watch directly (Bluetooth or Wi-Fi via Google Play
 * services); nothing goes to the app's servers. On a phone with no watch it only keeps one small item locally.
 */
object WatchSync {
    private val lock = Mutex()
    private var lastBody: String? = null
    private var lastAt = 0L
    /** The same days are sent again at most this often, so the watch's "몇 분 전" stays honest. */
    private const val RESEND_MILLIS = 60 * 60 * 1000L

    suspend fun push(context: Context, snapshot: ScheduleSnapshot? = null, upcoming: UpcomingAlarm? = null, force: Boolean = false) = lock.withLock {
        val c = context.appContainer
        val snap = snapshot ?: c.scheduleRepository.snapshot.first()
        val next = upcoming ?: c.alarmScheduler.upcoming.value
        val today = LocalDate.now()
        val (from, until) = WatchDays.window(today)
        val days = WatchDays.of(from, until, snap.resolver::resolve)
        val alarmAt = next?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.triggerAtMillis), ZoneId.systemDefault()) }
        val label = next?.plan?.let { p -> if (p.shiftName.isNotBlank()) "${p.shiftName} 근무" else "알람" }.orEmpty()
        val name = c.calendars.get(PersonalCalendar.MAIN)?.name ?: PersonalCalendar.MAIN_NAME
        val sameDays = WatchDays.encode(WatchPayload(days, LocalDateTime.of(2000, 1, 1, 0, 0), alarmAt, label, name))
        val now = System.currentTimeMillis()
        if (!force && sameDays == lastBody && now - lastAt < RESEND_MILLIS) return@withLock
        val body = WatchDays.encode(WatchPayload(days, LocalDateTime.now(), alarmAt, label, name, sentAtMillis = now))
        val request = PutDataMapRequest.create(WatchDays.PATH).apply { dataMap.putString(WatchDays.KEY, body) }.asPutDataRequest().setUrgent()
        val ok = runCatching { await(Wearable.getDataClient(context).putDataItem(request)) != null }.getOrDefault(false)
        if (ok) {
            lastBody = sameDays
            lastAt = now
        }
    }

    /** A Play services task's result, or null when it failed (no Play services, no Wear OS app). */
    private suspend fun <T> await(task: Task<T>): T? = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
    }
}

/** [SHFT-08] The watch app asks for fresh days when it opens ("/bamnat/sync"); the phone sends them. */
class WatchRequestService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != SYNC_PATH) return
        val c = applicationContext.appContainer
        c.appScope.launch { WatchSync.push(applicationContext, force = true) }
    }

    companion object {
        const val SYNC_PATH = "/bamnat/sync"
    }
}
