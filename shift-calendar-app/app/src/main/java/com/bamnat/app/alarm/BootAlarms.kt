package com.bamnat.app.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import com.bamnat.app.appContainer
import com.bamnat.app.data.AppSettings
import com.bamnat.core.alarm.AlarmMission
import org.json.JSONArray

/**
 * [REL-10] The next few shift alarms and the ringing settings, copied to device-protected storage.
 * After a reboot the phone stays locked until the user unlocks it, and until then nothing in the
 * app's normal storage (the database, settings) can be read. An overnight update that restarts the
 * phone must still wake the user for an early shift, so the alarms ring from this copy alone.
 */
object BootAlarms {
    /** What ringing needs from the settings while the phone is locked. */
    data class RingSettings(
        val defaultSound: String? = null,
        val vibrate: Boolean = true,
        val gradualVolume: Boolean = true,
        val snoozeMinutes: Int = 5,
        val ringMinutes: Int = 10,
        /** [PROV-06] 끄기 미션 and each shift's own; none without 프로. */
        val mission: AlarmMission = AlarmMission.NONE,
        val shiftMissions: Map<Long, AlarmMission> = emptyMap(),
        /** [PROV-07] 0 without 프로 or when off. */
        val wakeCheckMinutes: Int = 0,
    ) {
        /** [PROV-06] What turning [ring] off asks for. */
        fun missionFor(ring: RingRequest): AlarmMission = AlarmMission.forAlarm(mission, shiftMissions, ring.shiftTypeId)

        companion object {
            /** [sure] is whether 확실한 알람 is open ([com.bamnat.app.tier.Feature.SURE_ALARM]). */
            fun of(s: AppSettings, sure: Boolean = false) = RingSettings(
                s.defaultAlarmSound, s.vibrate, s.gradualVolume, s.snoozeMinutes, s.ringMinutes,
                mission = if (sure) AlarmMission.of(s.alarmMission) else AlarmMission.NONE,
                shiftMissions = if (sure) s.shiftMissions.mapValues { AlarmMission.of(it.value) } else emptyMap(),
                wakeCheckMinutes = if (sure) s.wakeCheckMinutes.coerceIn(0, 30) else 0,
            )
        }
    }

    /** How many alarms ahead are kept: enough for a long weekend locked after a reboot. */
    const val AHEAD = 6

    private fun prefs(context: Context) =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context)
            .getSharedPreferences("boot_alarms", Context.MODE_PRIVATE)

    /** True between a reboot and the first unlock: only device-protected storage can be read. */
    fun isLocked(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !context.getSystemService(UserManager::class.java).isUserUnlocked

    fun save(context: Context, upcoming: List<RingRequest>, settings: RingSettings) {
        prefs(context).edit()
            .putString(K_UPCOMING, JSONArray(upcoming.take(AHEAD).map { it.encode() }).toString())
            .putString(K_SOUND, settings.defaultSound)
            .putBoolean(K_VIBRATE, settings.vibrate)
            .putBoolean(K_GRADUAL, settings.gradualVolume)
            .putInt(K_SNOOZE_MIN, settings.snoozeMinutes)
            .putInt(K_RING_MIN, settings.ringMinutes)
            .putString(K_MISSION, settings.mission.key)
            .putString(K_SHIFT_MISSIONS, com.bamnat.app.data.encodeShiftMissions(settings.shiftMissions.mapValues { it.value.key }))
            .putInt(K_WAKE_CHECK, settings.wakeCheckMinutes)
            .apply()
    }

    fun upcoming(context: Context): List<RingRequest> = runCatching {
        val a = JSONArray(prefs(context).getString(K_UPCOMING, "[]"))
        (0 until a.length()).mapNotNull { RingRequest.decode(a.getString(it)) }
    }.getOrDefault(emptyList())

    fun settings(context: Context): RingSettings {
        val p = prefs(context)
        return RingSettings(p.getString(K_SOUND, null), p.getBoolean(K_VIBRATE, true), p.getBoolean(K_GRADUAL, true),
            p.getInt(K_SNOOZE_MIN, 5), p.getInt(K_RING_MIN, 10),
            AlarmMission.of(p.getString(K_MISSION, null)),
            com.bamnat.app.data.decodeShiftMissions(p.getString(K_SHIFT_MISSIONS, null).orEmpty()).mapValues { AlarmMission.of(it.value) },
            p.getInt(K_WAKE_CHECK, 0))
    }

    /** The settings for a ringing alarm: the real ones when unlocked, the copy before that. */
    fun ringSettings(context: Context): RingSettings =
        if (isLocked(context)) settings(context) else context.appContainer.ringSettings()

    fun saveSnooze(context: Context, ring: RingRequest?, at: Long) {
        prefs(context).edit().apply {
            if (ring == null) remove(K_SNOOZE).remove(K_SNOOZE_AT) else putString(K_SNOOZE, ring.encode()).putLong(K_SNOOZE_AT, at)
        }.apply()
    }

    /** A snooze set while the phone was still locked, if it is still ahead. */
    fun snooze(context: Context): Pair<RingRequest, Long>? {
        val p = prefs(context)
        val at = p.getLong(K_SNOOZE_AT, 0L)
        return RingRequest.decode(p.getString(K_SNOOZE, null))?.takeIf { at > System.currentTimeMillis() }?.let { it to at }
    }

    /**
     * Registers the first alarm still ahead from the copy (and a pending snooze), without the
     * database. Called at a locked boot and after each alarm that rings while still locked.
     */
    fun registerNext(context: Context, after: Long = System.currentTimeMillis()) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        upcoming(context).firstOrNull { it.triggerAtMillis > after }?.let { ring ->
            AlarmScheduler.setAlarm(context, alarmManager, ring.triggerAtMillis, AlarmScheduler.shiftAlarmIntent(context, ring))
        }
        val p = prefs(context)
        val snoozeAt = p.getLong(K_SNOOZE_AT, 0L)
        RingRequest.decode(p.getString(K_SNOOZE, null))?.takeIf { snoozeAt > System.currentTimeMillis() }?.let { ring ->
            AlarmScheduler.setAlarm(context, alarmManager, snoozeAt, AlarmScheduler.snoozeIntent(context, ring))
        }
    }

    private const val K_UPCOMING = "upcoming"
    private const val K_SOUND = "sound"
    private const val K_VIBRATE = "vibrate"
    private const val K_GRADUAL = "gradual"
    private const val K_SNOOZE_MIN = "snooze_minutes"
    private const val K_RING_MIN = "ring_minutes"
    private const val K_MISSION = "mission"
    private const val K_SHIFT_MISSIONS = "shift_missions"
    private const val K_WAKE_CHECK = "wake_check_minutes"
    private const val K_SNOOZE = "snooze"
    private const val K_SNOOZE_AT = "snooze_at"
}

/** [REL-10] A reboot before the first unlock: alarms come back from the device-protected copy. */
class LockedBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) BootAlarms.registerNext(context)
    }
}
