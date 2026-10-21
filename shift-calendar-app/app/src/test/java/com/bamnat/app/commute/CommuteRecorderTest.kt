package com.bamnat.app.commute

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.commute.CommuteRecorder.Enter
import com.bamnat.app.commute.CommuteRecorder.EnterPlan
import com.bamnat.app.data.CommutePrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

/** [DAYW-07] What a workplace crossing means, the records' notices, and turning 출퇴근 자동 기록 off. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CommuteRecorderTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val d1 = LocalDate.of(2026, 11, 2)
    private val d2 = d1.plusDays(1)
    private fun state(arrived: LocalDateTime? = null, left: LocalDateTime? = null, pending: LocalDateTime? = null) =
        CommutePrefs.State(enabled = true, lat = 37.5, lng = 127.0, arrivedOn = arrived?.toLocalDate(), arrivedAt = arrived, leftAt = left, pendingLeave = pending)

    @Before fun setUp() {
        CommutePrefs.get(context).setEnabled(false)
    }

    @Test fun backWithinTheWaitIsAWalk() {
        val s = state(arrived = d1.atTime(9, 0), pending = d1.atTime(12, 0))
        assertEquals(EnterPlan(false, Enter.WALK), CommuteRecorder.onEnter(s, d1.atTime(13, 0)))
    }

    @Test fun backLateInTheSameShiftWhileTheWaitsAlarmIsStillDueIsAWalk() {
        // The inexact alarm has not finished the 18:00 leave by 21:00: still the shift arrived at 9:00.
        val s = state(arrived = d1.atTime(9, 0), pending = d1.atTime(18, 0))
        assertEquals(EnterPlan(false, Enter.WALK), CommuteRecorder.onEnter(s, d1.atTime(21, 0)))
    }

    @Test fun aLeaveStillWaitingIsFinishedBeforeTheNextShiftsArrival() {
        val s = state(arrived = d1.atTime(22, 0), pending = d2.atTime(7, 0))
        assertEquals(EnterPlan(true, Enter.ARRIVE), CommuteRecorder.onEnter(s, d2.atTime(21, 30)))
    }

    @Test fun aLongBreakOnANightShiftTakesTheLeaveBackAcrossMidnight() {
        val s = state(arrived = d1.atTime(22, 0), left = d2.atTime(1, 0))
        assertEquals(EnterPlan(false, Enter.UNDO_LEAVE), CommuteRecorder.onEnter(s, d2.atTime(3, 30)))
        assertEquals(d1.atTime(22, 0), CommuteRecorder.openArrival(s, d2.atTime(3, 30)))
    }

    @Test fun noNewArrivalWhileTheShiftIsOpenAfterMidnight() {
        val s = state(arrived = d1.atTime(22, 0))
        assertEquals(EnterPlan(false, Enter.OPEN), CommuteRecorder.onEnter(s, d2.atTime(2, 0)))
    }

    @Test fun theNextDayIsANewArrival() {
        val s = state(arrived = d1.atTime(8, 0), left = d1.atTime(17, 0))
        assertEquals(EnterPlan(false, Enter.ARRIVE), CommuteRecorder.onEnter(s, d2.atTime(8, 0)))
        assertEquals(EnterPlan(false, Enter.ARRIVE), CommuteRecorder.onEnter(state(), d2.atTime(8, 0)))
    }

    @Test fun aLeaveAfterMidnightGoesOnTheDayArrivedOn() {
        val s = state(arrived = d1.atTime(22, 0))
        assertEquals(CommuteRecorder.Record(false, d2.atTime(7, 0), d1, d1.atTime(22, 0)), CommuteRecorder.record(s, false, d2.atTime(7, 0)))
        assertNull(CommuteRecorder.record(state(), false, d2.atTime(7, 0)))
    }

    @Test fun aNoticeCarriesItsRecordAndEachKindAndDayHasItsOwn() {
        val r = CommuteRecorder.Record(false, d2.atTime(7, 0), d1, d1.atTime(22, 0))
        val i = Intent().putExtra(CommuteRecorder.EXTRA_ARRIVE, r.arrive).putExtra(CommuteRecorder.EXTRA_AT, r.at.toString())
            .putExtra(CommuteRecorder.EXTRA_DATE, r.date.toString()).putExtra(CommuteRecorder.EXTRA_ARRIVED, r.arrived.toString())
        assertEquals(r, CommuteRecorder.recordOf(i))
        val tags = setOf(CommuteRecorder.tag(true, d1), CommuteRecorder.tag(false, d1), CommuteRecorder.tag(true, d2))
        assertEquals(3, tags.size)
    }

    @Test fun noToAnArrivalForgetsIt() {
        val prefs = CommutePrefs.get(context)
        prefs.setEnabled(true)
        val at = d1.atTime(9, 0)
        prefs.arrived(at)
        CommuteRecorder.refuse(context, CommuteRecorder.Record(true, at, d1, at))
        assertNull(prefs.current.arrivedAt)
        // 아니요 on an older notice leaves a newer arrival alone.
        prefs.arrived(d2.atTime(9, 0))
        CommuteRecorder.refuse(context, CommuteRecorder.Record(true, at, d1, at))
        assertEquals(d2.atTime(9, 0), prefs.current.arrivedAt)
    }

    @Test fun turningOffForgetsEverythingAndCallsOffTheWait() = runBlocking {
        val prefs = CommutePrefs.get(context)
        prefs.setEnabled(true)
        prefs.setPlace(37.5, 127.0)
        prefs.fenceRegistered(CommuteRecorder.fenceKey(prefs.current))
        val now = LocalDateTime.now().withNano(0)
        prefs.arrived(now.minusHours(8))
        CommuteRecorder.crossed(context, false, now)
        assertEquals(now, prefs.current.pendingLeave)
        val am = context.getSystemService(AlarmManager::class.java)
        assertTrue(shadowOf(am).scheduledAlarms.isNotEmpty())

        CommuteRecorder.turnOff(context)
        val s = prefs.current
        assertFalse(s.enabled)
        assertFalse(s.hasPlace)
        assertNull(s.pendingLeave)
        assertNull(s.arrivedAt)
        assertNull(s.fence)
        assertTrue(shadowOf(am).scheduledAlarms.none { shadowOf(it.operation).savedIntent.action == CommuteRecorder.ACTION_FINISH_LEAVE })
    }
}
