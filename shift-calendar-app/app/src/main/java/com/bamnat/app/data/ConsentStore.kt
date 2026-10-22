package com.bamnat.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.sleep.HealthSleep
import com.bamnat.core.consent.ConsentItem
import com.bamnat.core.consent.ConsentPlan
import com.bamnat.core.consent.SleepDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ONBOARD-01] What this phone was told about on the consent pages, and [ONBOARD-02] whether the user
 * sleeps with a watch. Kept beside the notification question in the phone's own preferences.
 */
class ConsentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)

    private val _seen = MutableStateFlow(ConsentPlan.decode(prefs.getStringSet(K_SEEN, null)))
    /** Null until the phone has been through the consent pages (or the short prompt) once. */
    val seen: StateFlow<Set<ConsentItem>?> = _seen.asStateFlow()

    val sleepDevice: SleepDevice?
        get() = prefs.getString(K_SLEEP_DEVICE, null)?.let { n -> SleepDevice.entries.firstOrNull { it.name == n } }

    /** The pages or the prompt are done: [items] were told about, on top of what was before. */
    fun markSeen(items: Collection<ConsentItem>) {
        val all = (_seen.value ?: ConsentPlan.BEFORE_PAGES) + items
        prefs.edit().putStringSet(K_SEEN, ConsentPlan.encode(all)).apply()
        _seen.value = all
    }

    /**
     * The first-start pages are done: the user was told about what this build could use. An item a
     * later build adds (one this build could not ask for) is still asked about then.
     */
    fun markFirstStartDone(available: Collection<ConsentItem>) = markSeen(available)

    /** Screenshots and tests: the items to show, in place of what the phone and build can use. */
    @androidx.annotation.VisibleForTesting
    var availableForTest: Set<ConsentItem>? = null

    /** Screenshots and tests: a phone that has not seen the pages yet. */
    @androidx.annotation.VisibleForTesting
    fun forget() {
        prefs.edit().remove(K_SEEN).remove(K_SLEEP_DEVICE).remove(K_RECOMMEND_OFFERED).apply()
        _seen.value = null
    }

    fun setSleepDevice(answer: SleepDevice) {
        prefs.edit().putString(K_SLEEP_DEVICE, answer.name).apply()
    }

    /** [ONBOARD-04] The phone set up before 1.22 was offered 추천 기능 한 번에 켜기 once (or had the first-start screen). */
    val recommendOffered: Boolean get() = prefs.getBoolean(K_RECOMMEND_OFFERED, false)

    fun markRecommendOffered() {
        prefs.edit().putBoolean(K_RECOMMEND_OFFERED, true).apply()
    }

    private companion object {
        const val K_SEEN = "consent_seen"
        const val K_RECOMMEND_OFFERED = "recommend_offered"
        const val K_SLEEP_DEVICE = "sleep_device"
    }
}

/** [ONBOARD-01] Which items this phone and build can ask for, and which are allowed now. */
object ConsentChecks {
    /** Declared by the build that reads the phone's calendar; other builds leave the item out. */
    const val READ_CALENDAR = Manifest.permission.READ_CALENDAR

    fun available(context: Context, health: HealthSleep, store: ConsentStore): Set<ConsentItem> =
        store.availableForTest ?: available(context, health)

    fun available(context: Context, health: HealthSleep): Set<ConsentItem> = buildSet {
        add(ConsentItem.NOTIFICATIONS)
        // Android 14 lets the user turn off alarms over the lock screen; Android 12 asks for alarms on time.
        if (Build.VERSION.SDK_INT >= 34 || Build.VERSION.SDK_INT in 31..32) add(ConsentItem.ALARMS)
        if (health.sdkStatus() != HealthConnectClient.SDK_UNAVAILABLE) add(ConsentItem.SLEEP)
        if (declares(context, READ_CALENDAR)) add(ConsentItem.CALENDAR)
    }

    fun alarmsAllowed(context: Context): Boolean {
        val exact = Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() != false
        return exact && Notifications.canUseFullScreen(context)
    }

    fun calendarAllowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun granted(context: Context, health: HealthSleep): Set<ConsentItem> = buildSet {
        if (Notifications.canPost(context)) add(ConsentItem.NOTIFICATIONS)
        if (alarmsAllowed(context)) add(ConsentItem.ALARMS)
        if (health.granted()) add(ConsentItem.SLEEP)
        if (calendarAllowed(context)) add(ConsentItem.CALENDAR)
    }

    private fun declares(context: Context, permission: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)
}
