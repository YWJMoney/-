package com.bamnat.app.rules

import android.app.AlarmManager
import android.app.NotificationManager
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
 * [REL-14] The app's start, its alarm and its backup on the Android versions it supports, not only the newest:
 * Android 8 (minSdk 26), 11 (30), 13 (33) and 15 (35). Robolectric runs every test once per version, so the
 * branches older phones take (no exact-alarm permission before 12, no notification permission before 13) run too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 30, 33, 35])
// The native SQLite library loads into one Robolectric sandbox per JVM; with four Android versions in one class
// the database goes through Robolectric's legacy SQLite instead.
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ApiLevelSmokeTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val alarmManager get() = app.getSystemService(AlarmManager::class.java)
    private val day = ShiftType(1, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0), alarmEnabled = true)
    private val calendars = listOf(null to ScheduleSnapshot(listOf(day), WorkPattern.Rotation(LocalDate.now().minusDays(3), listOf(1L)), emptyList()))

    private fun scheduler() = AlarmScheduler(app, app.container.telemetry)

    /**
     * The app's own background planning stops, so it cannot plan over this test's alarm a moment later (it shares
     * the same alarm slot); as [BamnatApp.onTerminate] does at the end of every test.
     */
    private fun quietApp() {
        app.container.appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun theAppStartsAndMakesItsNotificationChannels() {
        val c = app.container
        assertTrue("the start follows the data", c.kept.isNotEmpty())
        val channels = app.getSystemService(NotificationManager::class.java).notificationChannels
        assertTrue("channels on API ${Build.VERSION.SDK_INT}: ${channels.map { it.id }}", channels.isNotEmpty())
    }

    @Test fun theNextShiftAlarmIsSetOnTime() {
        quietApp()
        // The user allowed 알람 및 리마인더 (Android 12+), as the first setup asks.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val s = scheduler()
        s.reschedule(calendars, true, LocalDateTime.now())
        val next = shadowOf(alarmManager).nextScheduledAlarm
        assertNotNull("an alarm on API ${Build.VERSION.SDK_INT}", next)
        assertTrue("exact before Android 12, and with the permission after", s.canScheduleExact())
        assertNotNull("an alarm clock (rings in Doze, shows in the status bar)", next!!.alarmClockInfo)
    }

    /** [REL-14] 결정 C2: the user said no to 알람 및 리마인더 (Android 12+): the alarm still rings, a little late at worst. */
    @Test fun aRefusedExactAlarmStillRings() {
        assumeTrue("the permission exists from Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        quietApp()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        try {
            val s = scheduler()
            assertFalse(s.canScheduleExact())
            s.reschedule(calendars, true, LocalDateTime.now())
            val next = shadowOf(alarmManager).nextScheduledAlarm
            assertNotNull("an inexact alarm still goes in", next)
            assertNull("not an alarm clock without the permission", next!!.alarmClockInfo)
            assertTrue("allowed to ring while the phone dozes", next.isAllowWhileIdle)
        } finally {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
        }
    }

    @Test fun aBackupRestoresToTheSameData() = runBlocking {
        val c = app.container
        c.scheduleRepository.ensureDefaults()
        val backup = c.backupManager.export()
        c.backupManager.restore(backup)
        val again = c.backupManager.export()
        fun strip(json: String) = JSONObject(json).apply { remove("exportedAt") }.toString(2)
        assertEquals("backup round trip on API ${Build.VERSION.SDK_INT}", strip(backup), strip(again))
    }
}
