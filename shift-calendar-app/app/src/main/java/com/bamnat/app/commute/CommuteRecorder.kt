package com.bamnat.app.commute

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.app.data.CommutePrefs
import com.bamnat.app.ui.Fmt
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.work.Commute
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import com.bamnat.core.time.Today

/**
 * [DAYW-07] 출퇴근 자동 기록 on the phone only: one geofence around the workplace the user set, handed to Google
 * Play services' geofencing on the device. Coming in records 출근, going out records 퇴근 once the phone has not
 * come back for [LEAVE_WAIT_MINUTES] (a walk at lunch is not 퇴근). Each record is asked with 맞아요·아니요, or
 * written at once when the user chose so. No location is sent anywhere or kept besides the workplace point.
 */
object CommuteRecorder {
    private const val FENCE_ID = "bamnat_workplace"
    private const val REQ_FENCE = 3101
    private const val REQ_FINISH = 3102
    private const val REQ_CHECK = 3103
    /** [DAYW-07] How often a check puts back a geofence lost while the app was not running. */
    const val CHECK_INTERVAL_MILLIS = 12 * 60 * 60 * 1000L
    // A lunch out of the fence runs about an hour; 90 minutes leaves room for it.
    const val LEAVE_WAIT_MINUTES = 90L
    const val CHANNEL_ID = "commute_record_v1"
    private const val NOTICE_ID = 7320
    const val ACTION_FENCE = "com.bamnat.app.commute.FENCE"
    const val ACTION_FINISH_LEAVE = "com.bamnat.app.commute.FINISH_LEAVE"
    const val ACTION_CHECK = "com.bamnat.app.commute.CHECK"
    /** Location turned back on (or providers changed): the system dropped the geofence, so it is put back. */
    const val ACTION_PROVIDERS_CHANGED = "android.location.PROVIDERS_CHANGED"
    const val ACTION_YES = "com.bamnat.app.commute.YES"
    const val ACTION_NO = "com.bamnat.app.commute.NO"
    const val EXTRA_ARRIVE = "arrive"
    const val EXTRA_AT = "at"
    const val EXTRA_DATE = "date"
    const val EXTRA_ARRIVED = "arrived"

    fun hasForeground(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Android 10 and later need 'always allow' for the geofence to fire with the app closed. */
    fun hasBackground(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun fenceIntent(context: Context): PendingIntent {
        // Geofencing fills the event into the intent, so it has to be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, REQ_FENCE, Intent(context, CommuteReceiver::class.java).setAction(ACTION_FENCE), flags)
    }

    /**
     * Puts the geofence in place when the feature is on with a workplace and the permissions, takes it away otherwise.
     * [DAYW-07] The same circle already handed over is left alone (re-adding it drops a crossing on its way);
     * [force] puts it back after a reboot, an update, GEOFENCE_NOT_AVAILABLE or location coming back on.
     */
    @SuppressLint("MissingPermission")
    fun sync(context: Context, force: Boolean = false) {
        val prefs = CommutePrefs.get(context)
        val s = prefs.current
        watchProviders(context, s.enabled)
        if (s.enabled && s.hasPlace) scheduleCheck(context) else cancelCheck(context)
        val client = runCatching { LocationServices.getGeofencingClient(context) }.getOrNull() ?: return
        if (!s.enabled || !s.hasPlace || !hasForeground(context) || !hasBackground(context)) {
            runCatching { client.removeGeofences(fenceIntent(context)) }
            if (s.fence != null) prefs.fenceRegistered(null)
            return
        }
        val key = fenceKey(s)
        if (!force && s.fence == key) return
        val fence = Geofence.Builder()
            .setRequestId(FENCE_ID)
            .setCircularRegion(s.lat!!, s.lng!!, s.radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            // A couple of minutes late saves the battery; the times are rounded to 10 minutes anyway.
            .setNotificationResponsiveness(2 * 60 * 1000)
            .build()
        // The same request id replaces the old circle, no removing first. Already inside says so once; a
        // second arrival in the same shift is not taken again.
        val request = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).addGeofence(fence).build()
        runCatching {
            client.addGeofences(request, fenceIntent(context))
                .addOnSuccessListener { prefs.fenceRegistered(key) }
                .addOnFailureListener { prefs.fenceRegistered(null) }
        }
    }

    /**
     * [DAYW-07] At app start and every [CHECK_INTERVAL_MILLIS]: location off drops the geofence, so it is
     * marked not there; with location on it goes back if it is not there. Location turned off and on while
     * the app was not running is caught this way, without a forced re-add (which drops a crossing).
     */
    fun catchUp(context: Context, locationOn: Boolean = locationOn(context)) {
        val prefs = CommutePrefs.get(context)
        if (!prefs.current.enabled) { sync(context); return }
        if (!locationOn) {
            if (prefs.current.fence != null) prefs.fenceRegistered(null)
            watchProviders(context, true)
            if (prefs.current.hasPlace) scheduleCheck(context)
            return
        }
        sync(context)
    }

    fun locationOn(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return true
        return androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun checkIntent(context: Context, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(context, REQ_CHECK,
        Intent(context, CommuteReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)

    /** [DAYW-07] Inexact and not waking the phone: it runs with whatever else wakes it. Left as it is when already set. */
    fun scheduleCheck(context: Context) {
        if (checkIntent(context, create = false) != null) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = checkIntent(context, create = true) ?: return
        am.setInexactRepeating(AlarmManager.RTC, Today.millis() + CHECK_INTERVAL_MILLIS, CHECK_INTERVAL_MILLIS, pi)
    }

    fun cancelCheck(context: Context) {
        val pi = checkIntent(context, create = false) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pi)
        pi.cancel()
    }

    /** Whether the periodic check is set. */
    fun checkScheduled(context: Context): Boolean = checkIntent(context, create = false) != null

    fun fenceKey(s: CommutePrefs.State): String = "${s.lat},${s.lng},${s.radius}"

    /**
     * [DAYW-07] The one way to turn it off (출퇴근 화면, 기능 스위치): the workplace and the day's records go,
     * the geofence is taken away and the wait after a leave is called off, so no location is checked any more.
     */
    fun turnOff(context: Context) {
        CommutePrefs.get(context).setEnabled(false)
        sync(context)
        cancelFinish(context)
    }

    @Volatile private var providers: BroadcastReceiver? = null

    /**
     * [DAYW-07] Location turned back on: the system dropped the geofence, so it goes back. A manifest receiver
     * no longer hears this broadcast (Android 8), so it is listened for while the app runs and the feature is on.
     */
    private fun watchProviders(context: Context, on: Boolean) = synchronized(this) {
        val app = context.applicationContext
        val current = providers
        if (on && current == null) {
            val r = CommuteReceiver()
            runCatching {
                ContextCompat.registerReceiver(app, r, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
                providers = r
            }
        } else if (!on && current != null) {
            runCatching { app.unregisterReceiver(current) }
            providers = null
        }
    }

    /** What coming in means: a walk out, a leave taken back, nothing new (the shift is open), or 출근. */
    enum class Enter { WALK, UNDO_LEAVE, OPEN, ARRIVE }

    /** [finishFirst]: the leave still waiting is 퇴근 and is written before [then]. */
    data class EnterPlan(val finishFirst: Boolean, val then: Enter)

    /** [DAYW-07] The arrival whose shift [at] still falls in (under [Commute.MAX_SHIFT_MINUTES] old), also across midnight. */
    fun openArrival(s: CommutePrefs.State, at: LocalDateTime): LocalDateTime? =
        s.arrivedAt?.takeIf { Duration.between(it, at).toMinutes() in 0 until Commute.MAX_SHIFT_MINUTES }

    /**
     * [DAYW-07] Coming in at [at], read against the state before it. [plannedEnd] is the planned end of the
     * open arrival's shift (its own day, so a night shift runs past midnight), null when it has none.
     */
    fun onEnter(s: CommutePrefs.State, at: LocalDateTime, plannedEnd: LocalDateTime?): EnterPlan {
        val pending = s.pendingLeave
        if (pending != null) {
            // Back within the wait: the leave was a walk, not 퇴근 — by the minutes, also across midnight.
            if (Commute.cameBack(pending, at, LEAVE_WAIT_MINUTES)) return EnterPlan(false, Enter.WALK)
            // The wait's alarm came late (it is inexact): an early leave and back before the end is still a walk.
            if (walk(s, pending, at, plannedEnd)) return EnterPlan(false, Enter.WALK)
            return EnterPlan(true, afterLeave(s.copy(leftAt = pending, pendingLeave = null), at, plannedEnd))
        }
        return EnterPlan(false, afterLeave(s, at, plannedEnd))
    }

    private fun afterLeave(s: CommutePrefs.State, at: LocalDateTime, plannedEnd: LocalDateTime?): Enter {
        openArrival(s, at) ?: return Enter.ARRIVE
        // Back before the end after an early leave was taken (a long lunch): that leave was not 퇴근. A 퇴근 at
        // or after the end (잔업 too) stays, whenever the phone comes back.
        val left = s.leftAt
        return if (left != null && walk(s, left, at, plannedEnd)) Enter.UNDO_LEAVE else Enter.OPEN
    }

    /** A leave at [left] in the open shift, before its planned end, and back at [at] before that end too. */
    private fun walk(s: CommutePrefs.State, left: LocalDateTime, at: LocalDateTime, plannedEnd: LocalDateTime?): Boolean {
        val open = openArrival(s, at) ?: return false
        val end = plannedEnd ?: return false
        return left.isAfter(open) && at.isAfter(left) && left.isBefore(end.minusMinutes(Commute.SLACK_MINUTES.toLong())) && at.isBefore(end)
    }

    /** [DAYW-07] The planned end of [day]'s shift: the shift type's own times, not an end written earlier. */
    fun plannedEnd(day: com.bamnat.core.schedule.ResolvedDay): LocalDateTime? =
        if (!day.isWorkDay || day.shiftFrom == null) null else Commute.plannedTo(day)

    /** The planned end of the shift arrived on before [at], read off its calendar. */
    private suspend fun openEnd(context: Context, s: CommutePrefs.State, at: LocalDateTime): LocalDateTime? {
        val arrived = openArrival(s, at) ?: return null
        val c = context.appContainer
        if (c.calendars.get(s.calendarId) == null) return null
        return plannedEnd(c.calendars.repository(s.calendarId).snapshot.first().resolver.resolve(arrived.toLocalDate()))
    }

    /** A geofence crossing at [at]. */
    suspend fun crossed(context: Context, entering: Boolean, at: LocalDateTime) {
        val prefs = CommutePrefs.get(context)
        val s = prefs.current
        if (!s.enabled) return
        if (entering) {
            val plan = onEnter(s, at, openEnd(context, s, at))
            if (plan.finishFirst) finishLeave(context)
            when (plan.then) {
                Enter.WALK -> { prefs.leavePending(null); cancelFinish(context) }
                Enter.UNDO_LEAVE -> undoLeave(context, openArrival(s, at)!!.toLocalDate())
                Enter.OPEN -> Unit
                Enter.ARRIVE -> { prefs.arrived(at); propose(context, arrive = true, at = at) }
            }
        } else {
            if (Commute.leaveDay(at, s.arrivedAt, s.leftAt) == null) return
            prefs.leavePending(at)
            scheduleFinish(context, at.plusMinutes(LEAVE_WAIT_MINUTES))
        }
    }

    /** The shift as it was before the last leave, which turned out to be a walk; its notice goes too. */
    private suspend fun undoLeave(context: Context, shiftDay: LocalDate) {
        val prefs = CommutePrefs.get(context)
        val s = prefs.current
        val date = s.undoDate
        val undo = s.undo
        // Only a leave written on the open shift's own day is put back.
        if (date == shiftDay && undo != null && c(context).calendars.get(s.calendarId) != null) {
            c(context).calendars.repository(s.calendarId).updateDay(date) { Commute.undo(it, undo) }
        }
        prefs.leaveUndone()
        cancelFinish(context)
        Notifications.cancel(context, NOTICE_ID, tag(false, shiftDay))
    }

    private fun c(context: Context) = context.appContainer

    /** The wait after leaving is over and the phone did not come back. */
    suspend fun finishLeave(context: Context) {
        val prefs = CommutePrefs.get(context)
        val at = prefs.current.pendingLeave ?: return
        prefs.left(at)
        propose(context, arrive = false, at = at)
    }

    /**
     * [DAYW-07] After a reboot the wait's alarm is gone: a leave still waiting is finished when its wait is
     * over, else waited for again.
     */
    suspend fun resume(context: Context, now: LocalDateTime = Today.dateTime()) {
        val s = CommutePrefs.get(context).current
        val pending = s.pendingLeave ?: return
        if (!s.enabled) return
        val due = pending.plusMinutes(LEAVE_WAIT_MINUTES)
        if (!now.isBefore(due)) finishLeave(context) else scheduleFinish(context, due)
    }

    /** A record: arriving or leaving at [at], for the shift of [date] arrived on at [arrived]. */
    data class Record(val arrive: Boolean, val at: LocalDateTime, val date: LocalDate, val arrived: LocalDateTime?)

    /** Which day a record goes on, from the state when it was seen. */
    fun record(s: CommutePrefs.State, arrive: Boolean, at: LocalDateTime): Record? {
        if (arrive) return Record(true, at, at.toLocalDate(), at)
        // A leave after midnight belongs to the day arrived on, while that arrival is recent and still open.
        val date = Commute.leaveDay(at, s.arrivedAt, s.leftAt) ?: return null
        return Record(false, at, date, s.arrivalOn(date))
    }

    /** What [r] writes on its calendar now, read against that day as it stands. */
    private suspend fun plan(context: Context, r: Record): Commute.Write? {
        val c = context.appContainer
        val s = CommutePrefs.get(context).current
        if (c.calendars.get(s.calendarId) == null) return null
        val day = c.calendars.repository(s.calendarId).snapshot.first().resolver.resolve(r.date)
        return if (r.arrive) Commute.arrive(day, r.at) else Commute.leave(day, r.arrived, r.at)
    }

    private suspend fun propose(context: Context, arrive: Boolean, at: LocalDateTime) {
        val r = record(CommutePrefs.get(context).current, arrive, at) ?: return
        val write = plan(context, r) ?: return
        if (write == Commute.Write.None) return
        val s = CommutePrefs.get(context).current
        if (s.auto) {
            apply(context, r.date, write)
            notify(context, "${what(write)} 적었어요", "${if (arrive) "회사에 도착" else "회사에서 나감"} ${Fmt.time(at.toLocalTime())}. 달력에서 언제든 고칠 수 있어요.", r, ask = false)
        } else {
            notify(context, "${what(write)} 적을까요?",
                "${if (arrive) "회사에 도착했어요" else "회사에서 나왔어요"} (${Fmt.time(at.toLocalTime())}). 맞으면 ‘맞아요’를 눌러 주세요.", r, ask = true)
        }
    }

    /** [DAYW-07] The user said 맞아요 to [r], as its notice carried it (not what the prefs say by now). */
    suspend fun confirm(context: Context, r: Record) {
        val write = plan(context, r)
        if (write != null && write != Commute.Write.None) apply(context, r.date, write)
        Notifications.cancel(context, NOTICE_ID, tag(r.arrive, r.date))
    }

    /** [DAYW-07] 아니요: an arrival that was not 출근 is forgotten, so the real one that day is still taken. */
    fun refuse(context: Context, r: Record) {
        if (r.arrive) CommutePrefs.get(context).arrivalCleared(r.at)
        Notifications.cancel(context, NOTICE_ID, tag(r.arrive, r.date))
    }

    /** One notice per arrival or leave and day, so asking about a leave never hides the arrival's question. */
    fun tag(arrive: Boolean, date: LocalDate) = "commute:${if (arrive) "in" else "out"}:$date"

    fun recordOf(intent: Intent): Record? {
        fun t(k: String) = intent.getStringExtra(k)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val at = t(EXTRA_AT) ?: return null
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: at.toLocalDate()
        return Record(intent.getBooleanExtra(EXTRA_ARRIVE, true), at, date, t(EXTRA_ARRIVED))
    }

    private suspend fun apply(context: Context, date: LocalDate, write: Commute.Write) {
        val c = context.appContainer
        val prefs = CommutePrefs.get(context)
        var undo: Commute.Undo? = null
        c.calendars.repository(prefs.current.calendarId).updateDay(date) { before ->
            undo = Commute.undoOf(before, write)
            Commute.apply(before, write)
        }
        // A leave is kept to put back should the phone come back the same day.
        if (undo != null) prefs.leaveWritten(date, undo)
    }

    /** "출근 9:30으로", "잔업 1시간 40분(18:00–19:40)을", "15:00 퇴근으로", "특근 10:00–14:00을". */
    fun what(w: Commute.Write): String = when (w) {
        is Commute.Write.Late -> "출근 ${Fmt.time(w.start)}${Fmt.ro(Fmt.time(w.start))}"
        is Commute.Write.EarlyEnd -> "퇴근 ${Fmt.time(w.end)}${Fmt.ro(Fmt.time(w.end))}"
        is Commute.Write.Overtime -> "잔업 ${HoursFormat.korean(w.minutes)}(${Fmt.time(w.entry.start)}–${Fmt.time(w.entry.end)})${Fmt.obj(Fmt.time(w.entry.end))}"
        is Commute.Write.RestDayWork -> "특근 ${Fmt.time(w.entry.start)}–${Fmt.time(w.entry.end)}${Fmt.obj(Fmt.time(w.entry.end))}"
        Commute.Write.None -> ""
    }

    private fun notify(context: Context, title: String, text: String, r: Record, ask: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "출퇴근 자동 기록", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "회사 위치에 들어오고 나갈 때 출근·퇴근 시각을 적을지 물어봐요"
        })
        val tag = tag(r.arrive, r.date)
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.TODAY, REQ_FENCE + 10))
            .setAutoCancel(true)
        if (ask) {
            // The data keeps each notice's buttons apart; the extras carry the record as it was seen.
            fun action(action: String, code: Int) = PendingIntent.getBroadcast(context, code,
                Intent(context, CommuteReceiver::class.java).setAction(action).setData(Uri.parse("bamnat-commute://$tag"))
                    .putExtra(EXTRA_ARRIVE, r.arrive).putExtra(EXTRA_AT, r.at.toString()).putExtra(EXTRA_DATE, r.date.toString())
                    .putExtra(EXTRA_ARRIVED, r.arrived?.toString()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "맞아요", action(ACTION_YES, REQ_FENCE + 20)).addAction(0, "아니요", action(ACTION_NO, REQ_FENCE + 21))
        }
        Notifications.post(context, NOTICE_ID, b.build(), tag)
    }

    private fun finishIntent(context: Context) = PendingIntent.getBroadcast(context, REQ_FINISH,
        Intent(context, CommuteReceiver::class.java).setAction(ACTION_FINISH_LEAVE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun scheduleFinish(context: Context, at: LocalDateTime) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // Allowed while idle, so a phone left on the desk overnight still finishes the leave.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.atZone(Today.zone()).toInstant().toEpochMilli(), finishIntent(context))
    }

    private fun cancelFinish(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(finishIntent(context))
    }

    fun eventTime(millis: Long?): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis?.takeIf { it > 0 } ?: Today.millis()), Today.zone())
}

/** [DAYW-07] Geofence crossings, the end of the wait after leaving, and 맞아요·아니요 on a record's notice. */
class CommuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.appContainer.appScope.launch {
            try {
                when (intent.action) {
                    CommuteRecorder.ACTION_FENCE -> {
                        val event = GeofencingEvent.fromIntent(intent)
                        // Location was turned off: the system dropped the geofence; it is put back when it can be.
                        if (event != null && event.hasError() && event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                            runCatching { CommuteRecorder.sync(context, force = true) }
                        }
                        if (event != null && !event.hasError()) {
                            val at = CommuteRecorder.eventTime(event.triggeringLocation?.time)
                            when (event.geofenceTransition) {
                                Geofence.GEOFENCE_TRANSITION_ENTER -> CommuteRecorder.crossed(context, true, at)
                                Geofence.GEOFENCE_TRANSITION_EXIT -> CommuteRecorder.crossed(context, false, at)
                            }
                        }
                    }
                    CommuteRecorder.ACTION_FINISH_LEAVE -> CommuteRecorder.finishLeave(context)
                    CommuteRecorder.ACTION_CHECK -> runCatching { CommuteRecorder.catchUp(context) }
                    CommuteRecorder.ACTION_PROVIDERS_CHANGED -> {
                        // Only location coming back on needs the geofence put back.
                        if (CommuteRecorder.locationOn(context) && CommutePrefs.get(context).current.enabled) runCatching { CommuteRecorder.sync(context, force = true) }
                    }
                    CommuteRecorder.ACTION_YES -> CommuteRecorder.recordOf(intent)?.let { CommuteRecorder.confirm(context, it) }
                    CommuteRecorder.ACTION_NO -> CommuteRecorder.recordOf(intent)?.let { CommuteRecorder.refuse(context, it) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
