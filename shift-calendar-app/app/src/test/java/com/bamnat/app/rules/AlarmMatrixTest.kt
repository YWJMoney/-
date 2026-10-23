package com.bamnat.app.rules

import android.app.AlarmManager
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.alarm.RingRequest
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [REL-14] 326 (검토 C1·C2): every kind of alarm the app sets (the shift alarm, 알람 점검's test alarm, a snooze),
 * on Android 8, 11, 13 and 15, with 알람 및 리마인더 allowed and refused. Allowed (or before Android 12, where
 * there is no such permission): an alarm clock, which rings in Doze. Refused: still set, allowed while idle.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 30, 33, 35])
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class AlarmMatrixTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val day = ShiftType(1, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0), alarmEnabled = true)
    private val calendars = listOf(null to ScheduleSnapshot(listOf(day), WorkPattern.Rotation(LocalDate.now().minusDays(3), listOf(1L)), emptyList()))

    @Before fun quiet() {
        // The app's own planning would use the same slots a moment later.
        app.container.appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun allowAgain() = ShadowAlarmManager.setCanScheduleExactAlarms(true)

    /** Sets each kind and returns what the system holds after each, by name. */
    private fun setEveryKind(): Map<String, ShadowAlarmManager.ScheduledAlarm?> {
        val s = AlarmScheduler(app, app.container.telemetry)
        val out = LinkedHashMap<String, ShadowAlarmManager.ScheduledAlarm?>()
        fun added(set: () -> Unit): ShadowAlarmManager.ScheduledAlarm? {
            val before = alarms.scheduledAlarms.toSet()
            set()
            return (alarms.scheduledAlarms - before).firstOrNull()
        }
        out["shift"] = added { s.reschedule(calendars, true, LocalDateTime.now()) }
        out["test"] = added { s.scheduleTest() }
        val ring = RingRequest("주간", LocalDate.now(), LocalTime.of(7, 0), 0, null, System.currentTimeMillis(), label = "주간")
        out["snooze"] = added { s.scheduleSnooze(ring, 5) }
        return out
    }

    @Test fun withThePermissionEveryKindIsAnAlarmClock() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val set = setEveryKind()
        assertEquals(listOf("shift", "test", "snooze"), set.keys.toList())
        for ((kind, alarm) in set) {
            assertNotNull("$kind on API ${Build.VERSION.SDK_INT}", alarm)
            assertNotNull("$kind is an alarm clock on API ${Build.VERSION.SDK_INT}", alarm!!.alarmClockInfo)
        }
    }

    @Test fun refusedEveryKindStillRings() {
        assumeTrue("the permission exists from Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        for ((kind, alarm) in setEveryKind()) {
            assertNotNull("$kind still set on API ${Build.VERSION.SDK_INT}", alarm)
            assertNull("$kind is not an alarm clock without the permission", alarm!!.alarmClockInfo)
            assertTrue("$kind rings while the phone dozes", alarm.isAllowWhileIdle)
        }
    }
}
