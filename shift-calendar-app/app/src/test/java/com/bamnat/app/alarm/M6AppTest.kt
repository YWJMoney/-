package com.bamnat.app.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.appContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.sleep.HealthSleep
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** [REL-10][REL-11][SLP-08] Alarms after a locked reboot, late inexact alarms, Health Connect. */
@RunWith(AndroidJUnit4::class)
class M6AppTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val day = ShiftType(1, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0), alarmEnabled = true)
    private val start = LocalDate.of(2026, 11, 2)
    private val snapshot = ScheduleSnapshot(listOf(day), WorkPattern.Rotation(start, listOf(1L)), emptyList())
    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val six = start.atTime(6, 0)

    private fun scheduler() = AlarmScheduler(context, context.appContainer.telemetry) { BootAlarms.RingSettings(snoozeMinutes = 9) }

    @Test fun aLateInexactAlarmIsKeptUntilItRings() {
        val s = scheduler()
        s.reschedule(snapshot, enabled = true, after = start.atTime(5, 0))
        assertEquals(millis(six), s.upcoming.value!!.triggerAtMillis)
        // Three minutes late and not delivered yet: a re-plan must not skip it.
        s.reschedule(snapshot, enabled = true, after = start.atTime(6, 3))
        assertEquals(millis(six), s.upcoming.value!!.triggerAtMillis)
        s.markFired(millis(six))
        s.reschedule(snapshot, enabled = true, after = start.atTime(6, 3))
        assertEquals(millis(six.plusDays(1)), s.upcoming.value!!.triggerAtMillis)
    }

    @Test fun theBootCopyHoldsTheNextAlarmsAndRingSettings() {
        scheduler().reschedule(snapshot, enabled = true, after = start.atTime(5, 0))
        val copy = BootAlarms.upcoming(context)
        assertEquals(BootAlarms.AHEAD, copy.size)
        assertEquals((0L until BootAlarms.AHEAD).map { millis(six.plusDays(it)) }, copy.map { it.triggerAtMillis })
        assertEquals(9, BootAlarms.settings(context).snoozeMinutes)
        // Switching alarms off empties the copy too, so a locked boot rings nothing.
        scheduler().reschedule(snapshot, enabled = false, after = start.atTime(5, 0))
        assertTrue(BootAlarms.upcoming(context).isEmpty())
    }

    @Test fun aLockedBootRegistersTheNextAlarmFromTheCopy() {
        scheduler().reschedule(snapshot, enabled = true, after = start.atTime(5, 0))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        alarms.scheduledAlarms.toList().forEach { context.getSystemService(AlarmManager::class.java).cancel(it.operation!!) }
        assertNull(alarms.nextScheduledAlarm)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertTrue(BootAlarms.isLocked(context))
        BootAlarms.registerNext(context, after = millis(six.plusDays(1)))
        assertEquals(millis(six.plusDays(2)), alarms.nextScheduledAlarm!!.triggerAtTime)
    }

    @Test fun anAlarmWhileStillLockedRingsFromTheIntent() {
        val now = System.currentTimeMillis()
        val ring = RingRequest("주간", LocalDate.now(), LocalTime.of(7, 0), 60, null, now - 30_000)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        AlarmReceiver().onReceive(context, ring.putInto(Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SHIFT_ALARM)))
        val started = shadowOf(context).nextStartedService
        assertNotNull(started)
        assertEquals(AlarmService::class.java.name, started.component!!.className)
    }

    @Test fun withoutHealthConnectNothingIsReadAndTheDayViewCanSaySo() = runBlocking {
        val health = HealthSleep(context)
        health.refresh(enabled = true)
        assertTrue(health.state.value in setOf(HealthSleep.State.UNAVAILABLE, HealthSleep.State.NEEDS_UPDATE))
        assertTrue(health.sleeps.value.isEmpty())
        health.refresh(enabled = false)
        assertEquals(HealthSleep.State.OFF, health.state.value)
    }

    @Test fun healthSleepIsOffUntilTurnedOnAndRemembered() {
        val settings = context.appContainer.settingsRepository
        assertEquals(false, settings.current.healthSleep)
        settings.update { it.copy(healthSleep = true) }
        assertEquals(true, com.bamnat.app.data.SettingsRepository(context).current.healthSleep)
    }
}
