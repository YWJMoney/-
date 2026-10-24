package com.bamnat.app.sleep

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.bamnat.core.sleep.SleepEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.bamnat.core.time.Today

/**
 * [SLP-08] Sleep a watch or the phone measured, read from Health Connect on the phone.
 * Read only and kept in memory: nothing is written to Health Connect or stored by the app, and
 * the read goes to the Health Connect app on the phone, not online.
 */
class HealthSleep(private val context: Context, private val clock: () -> Long = Today::millis) {
    enum class State { OFF, UNAVAILABLE, NEEDS_UPDATE, NEEDS_PERMISSION, READY, FAILED }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _sleeps = MutableStateFlow<List<SleepEntry>>(emptyList())
    /** The last [DAYS] days of measured sleep, empty while off or not allowed. */
    val sleeps: StateFlow<List<SleepEntry>> = _sleeps.asStateFlow()

    private val lock = Mutex()
    private var readAt = 0L

    fun sdkStatus(): Int = runCatching { HealthConnectClient.getSdkStatus(context, PROVIDER) }
        .getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun granted(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE &&
        runCatching { client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS) }.getOrDefault(false)

    /**
     * Reads again when [enabled]; at most every few minutes unless [force]. Health Connect only
     * answers an app on screen, so this is called from the screens, never in the background.
     */
    suspend fun refresh(enabled: Boolean, force: Boolean = false) = lock.withLock {
        if (!enabled) {
            _state.value = State.OFF
            _sleeps.value = emptyList()
            readAt = 0L
            return@withLock
        }
        when (sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> Unit
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> { _state.value = State.NEEDS_UPDATE; return@withLock }
            else -> { _state.value = State.UNAVAILABLE; return@withLock }
        }
        if (!force && _state.value == State.READY && clock() - readAt < REFRESH_MILLIS) return@withLock
        if (!granted()) {
            _state.value = State.NEEDS_PERMISSION
            _sleeps.value = emptyList()
            return@withLock
        }
        runCatching { read() }
            .onSuccess { _sleeps.value = it; _state.value = State.READY; readAt = clock() }
            .onFailure { _state.value = State.FAILED }
    }

    /** Screenshots and tests: what a read would have found, without Health Connect. */
    @androidx.annotation.VisibleForTesting
    fun showForTest(state: State, sleeps: List<SleepEntry>) {
        _state.value = state
        _sleeps.value = sleeps
        readAt = clock()
    }

    private suspend fun read(): List<SleepEntry> {
        val end = Instant.ofEpochMilli(clock())
        val filter = TimeRangeFilter.between(end.minus(DAYS, ChronoUnit.DAYS), end)
        val out = ArrayList<SleepEntry>()
        var page: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, filter, pageToken = page))
            response.records.mapTo(out) { r ->
                val zone = Today.zone()
                SleepEntry(start = LocalDateTime.ofInstant(r.startTime, zone), end = LocalDateTime.ofInstant(r.endTime, zone), imported = true)
            }
            page = response.pageToken
        } while (page != null && out.size < MAX_RECORDS)
        return out
    }

    companion object {
        const val DAYS = 30L
        private const val REFRESH_MILLIS = 5 * 60 * 1000L
        private const val MAX_RECORDS = 500
        private const val PROVIDER = "com.google.android.apps.healthdata"
        val PERMISSIONS = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

        /** Where to get or update Health Connect (Android 13 and older; it is built in from 14). */
        fun installIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"))
            .setPackage("com.android.vending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
