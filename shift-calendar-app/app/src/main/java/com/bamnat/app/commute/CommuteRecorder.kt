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
import android.content.pm.PackageManager
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
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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
    const val LEAVE_WAIT_MINUTES = 40L
    const val CHANNEL_ID = "commute_record_v1"
    private const val NOTICE_ID = 7320
    const val ACTION_FENCE = "com.bamnat.app.commute.FENCE"
    const val ACTION_FINISH_LEAVE = "com.bamnat.app.commute.FINISH_LEAVE"
    const val ACTION_YES = "com.bamnat.app.commute.YES"
    const val ACTION_NO = "com.bamnat.app.commute.NO"
    const val EXTRA_ARRIVE = "arrive"
    const val EXTRA_AT = "at"

    fun hasForeground(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Android 10 and later need 'always allow' for the geofence to fire with the app closed. */
    fun hasBackground(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun fenceIntent(context: Context): PendingIntent {
        // Geofencing fills the event into the intent, so it has to be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, REQ_FENCE, Intent(context, CommuteReceiver::class.java).setAction(ACTION_FENCE), flags)
    }

    /** Puts the geofence in place when the feature is on with a workplace and the permissions, takes it away otherwise. */
    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val s = CommutePrefs.get(context).current
        val client = runCatching { LocationServices.getGeofencingClient(context) }.getOrNull() ?: return
        runCatching { client.removeGeofences(fenceIntent(context)) }
        if (!s.enabled || !s.hasPlace || !hasForeground(context) || !hasBackground(context)) return
        val fence = Geofence.Builder()
            .setRequestId(FENCE_ID)
            .setCircularRegion(s.lat!!, s.lng!!, s.radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            // A couple of minutes late saves the battery; the times are rounded to 10 minutes anyway.
            .setNotificationResponsiveness(2 * 60 * 1000)
            .build()
        val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
        runCatching { client.addGeofences(request, fenceIntent(context)) }
    }

    /** A geofence crossing at [at]. */
    suspend fun crossed(context: Context, entering: Boolean, at: LocalDateTime) {
        val prefs = CommutePrefs.get(context)
        val s = prefs.current
        if (!s.enabled) return
        if (entering) {
            // Back within the wait: the leave was a walk, not 퇴근.
            if (s.pendingLeave != null && s.pendingLeave.toLocalDate() == at.toLocalDate()) {
                prefs.leavePending(null)
                cancelFinish(context)
                return
            }
            if (s.arrivalOn(at.toLocalDate()) != null) return
            prefs.arrived(at)
            propose(context, arrive = true, at = at)
        } else {
            if (s.arrivalOn(at.toLocalDate()) == null && s.arrivalOn(at.toLocalDate().minusDays(1)) == null) return
            prefs.leavePending(at)
            scheduleFinish(context, at.plusMinutes(LEAVE_WAIT_MINUTES))
        }
    }

    /** The wait after leaving is over and the phone did not come back. */
    suspend fun finishLeave(context: Context) {
        val prefs = CommutePrefs.get(context)
        val at = prefs.current.pendingLeave ?: return
        prefs.left(at)
        propose(context, arrive = false, at = at)
    }

    /** What the record writes on its calendar now, read against that day as it stands. */
    private suspend fun plan(context: Context, arrive: Boolean, at: LocalDateTime): Pair<LocalDate, Commute.Write>? {
        val c = context.appContainer
        val s = CommutePrefs.get(context).current
        if (c.calendars.get(s.calendarId) == null) return null
        val resolver = c.calendars.repository(s.calendarId).snapshot.first().resolver
        // A leave after midnight belongs to the day arrived on.
        val date = if (!arrive && s.arrivalOn(at.toLocalDate()) == null && s.arrivalOn(at.toLocalDate().minusDays(1)) != null) at.toLocalDate().minusDays(1) else at.toLocalDate()
        val day = resolver.resolve(date)
        val write = if (arrive) Commute.arrive(day, at) else Commute.leave(day, s.arrivalOn(date), at)
        return date to write
    }

    private suspend fun propose(context: Context, arrive: Boolean, at: LocalDateTime) {
        val (date, write) = plan(context, arrive, at) ?: return
        if (write == Commute.Write.None) return
        val s = CommutePrefs.get(context).current
        if (s.auto) {
            apply(context, date, write)
            notify(context, "${what(write)} 적었어요", "${if (arrive) "회사에 도착" else "회사에서 나감"} ${Fmt.time(at.toLocalTime())}. 달력에서 언제든 고칠 수 있어요.", null)
        } else {
            notify(context, "${what(write)} 적을까요?",
                "${if (arrive) "회사에 도착했어요" else "회사에서 나왔어요"} (${Fmt.time(at.toLocalTime())}). 맞으면 ‘맞아요’를 눌러 주세요.", arrive to at)
        }
    }

    /** The user said 맞아요 to a record. */
    suspend fun confirm(context: Context, arrive: Boolean, at: LocalDateTime) {
        val (date, write) = plan(context, arrive, at) ?: return
        if (write != Commute.Write.None) apply(context, date, write)
        Notifications.cancel(context, NOTICE_ID)
    }

    private suspend fun apply(context: Context, date: LocalDate, write: Commute.Write) {
        val c = context.appContainer
        c.calendars.repository(CommutePrefs.get(context).current.calendarId).updateDay(date) { Commute.apply(it, write) }
    }

    /** "출근 9:30으로", "잔업 1시간 40분(18:00–19:40)을", "15:00 퇴근으로", "특근 10:00–14:00을". */
    fun what(w: Commute.Write): String = when (w) {
        is Commute.Write.Late -> "출근 ${Fmt.time(w.start)}${Fmt.ro(Fmt.time(w.start))}"
        is Commute.Write.EarlyEnd -> "퇴근 ${Fmt.time(w.end)}${Fmt.ro(Fmt.time(w.end))}"
        is Commute.Write.Overtime -> "잔업 ${HoursFormat.korean(w.minutes)}(${Fmt.time(w.entry.start)}–${Fmt.time(w.entry.end)})${Fmt.obj(Fmt.time(w.entry.end))}"
        is Commute.Write.RestDayWork -> "특근 ${Fmt.time(w.entry.start)}–${Fmt.time(w.entry.end)}${Fmt.obj(Fmt.time(w.entry.end))}"
        Commute.Write.None -> ""
    }

    private fun notify(context: Context, title: String, text: String, ask: Pair<Boolean, LocalDateTime>?) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "출퇴근 자동 기록", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "회사 위치에 들어오고 나갈 때 출근·퇴근 시각을 적을지 물어봐요"
        })
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.TODAY, REQ_FENCE + 10))
            .setAutoCancel(true)
        if (ask != null) {
            fun action(action: String, code: Int) = PendingIntent.getBroadcast(context, code,
                Intent(context, CommuteReceiver::class.java).setAction(action)
                    .putExtra(EXTRA_ARRIVE, ask.first).putExtra(EXTRA_AT, ask.second.toString()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "맞아요", action(ACTION_YES, REQ_FENCE + 20)).addAction(0, "아니요", action(ACTION_NO, REQ_FENCE + 21))
        }
        Notifications.post(context, NOTICE_ID, b.build())
    }

    fun dismiss(context: Context) = Notifications.cancel(context, NOTICE_ID)

    private fun finishIntent(context: Context) = PendingIntent.getBroadcast(context, REQ_FINISH,
        Intent(context, CommuteReceiver::class.java).setAction(ACTION_FINISH_LEAVE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun scheduleFinish(context: Context, at: LocalDateTime) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.set(AlarmManager.RTC_WAKEUP, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), finishIntent(context))
    }

    private fun cancelFinish(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(finishIntent(context))
    }

    fun eventTime(millis: Long?): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis?.takeIf { it > 0 } ?: System.currentTimeMillis()), ZoneId.systemDefault())
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
                        if (event != null && !event.hasError()) {
                            val at = CommuteRecorder.eventTime(event.triggeringLocation?.time)
                            when (event.geofenceTransition) {
                                Geofence.GEOFENCE_TRANSITION_ENTER -> CommuteRecorder.crossed(context, true, at)
                                Geofence.GEOFENCE_TRANSITION_EXIT -> CommuteRecorder.crossed(context, false, at)
                            }
                        }
                    }
                    CommuteRecorder.ACTION_FINISH_LEAVE -> CommuteRecorder.finishLeave(context)
                    CommuteRecorder.ACTION_YES -> {
                        val at = intent.getStringExtra(CommuteRecorder.EXTRA_AT)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                        if (at != null) CommuteRecorder.confirm(context, intent.getBooleanExtra(CommuteRecorder.EXTRA_ARRIVE, true), at)
                    }
                    CommuteRecorder.ACTION_NO -> CommuteRecorder.dismiss(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
