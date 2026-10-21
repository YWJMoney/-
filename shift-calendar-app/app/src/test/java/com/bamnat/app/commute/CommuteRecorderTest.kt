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

    // Fixtures: a day shift 09:00–18:00, a night shift 22:00–06:00 (planned end on the next day).
    private val dayEnd = d1.atTime(18, 0)
    private val nightEnd = d2.atTime(6, 0)

    @Test fun backWithinTheWaitIsAWalk() {
        val s = state(arrived = d1.atTime(9, 0), pending = d1.atTime(12, 0))
        assertEquals(EnterPlan(false, Enter.WALK), CommuteRecorder.onEnter(s, d1.atTime(13, 0), dayEnd))
    }

    @Test fun anEarlyLeaveAndBackBeforeTheEndIsAWalkEvenWhenTheWaitsAlarmIsLate() {
        // Out at 12:00, back at 13:40 (past the 90 minutes): the inexact alarm has not finished the leave yet.
        val s = state(arrived = d1.atTime(9, 0), pending = d1.atTime(12, 0))
        assertEquals(EnterPlan(false, Enter.WALK), CommuteRecorder.onEnter(s, d1.atTime(13, 40), dayEnd))
        // The alarm did finish it: the early end written is taken back.
        val left = state(arrived = d1.atTime(9, 0), left = d1.atTime(12, 0))
        assertEquals(EnterPlan(false, Enter.UNDO_LEAVE), CommuteRecorder.onEnter(left, d1.atTime(13, 40), dayEnd))
    }

    @Test fun aNormalLeaveStillWaitingIsFinishedNotTakenBackByComingBackThatEvening() {
        val s = state(arrived = d1.atTime(9, 0), pending = d1.atTime(18, 0))
        assertEquals(EnterPlan(true, Enter.OPEN), CommuteRecorder.onEnter(s, d1.atTime(21, 0), dayEnd))
    }

    @Test fun aNormalLeaveIsNeverUndoneByComingBackInTheNight() {
        // 09:00 in, out at the planned end 18:00, back at 03:00 (still under 20 hours): 퇴근 stays, no new arrival.
        val s = state(arrived = d1.atTime(9, 0), left = d1.atTime(18, 0))
        assertEquals(EnterPlan(false, Enter.OPEN), CommuteRecorder.onEnter(s, d2.atTime(3, 0), dayEnd))
        // Nor 잔업 coming back the same evening.
        val late = state(arrived = d1.atTime(9, 0), left = d1.atTime(19, 30))
        assertEquals(EnterPlan(false, Enter.OPEN), CommuteRecorder.onEnter(late, d1.atTime(20, 30), dayEnd))
    }

    @Test fun anEarlyLeaveIsNotUndoneByComingBackAfterTheEnd() {
        val s = state(arrived = d1.atTime(9, 0), left = d1.atTime(15, 0))
        assertEquals(EnterPlan(false, Enter.OPEN), CommuteRecorder.onEnter(s, d1.atTime(19, 0), dayEnd))
    }

    @Test fun aLeaveStillWaitingIsFinishedBeforeTheNextShiftsArrival() {
        val s = state(arrived = d1.atTime(22, 0), pending = d2.atTime(7, 0))
        assertEquals(EnterPlan(true, Enter.ARRIVE), CommuteRecorder.onEnter(s, d2.atTime(21, 30), null))
    }

    @Test fun aLongBreakOnANightShiftTakesTheLeaveBackAcrossMidnight() {
        // 21:50 in, out at 02:00, back at 04:00: before the 06:00 end of the shift arrived on.
        val s = state(arrived = d1.atTime(21, 50), left = d2.atTime(2, 0))
        assertEquals(EnterPlan(false, Enter.UNDO_LEAVE), CommuteRecorder.onEnter(s, d2.atTime(4, 0), nightEnd))
        assertEquals(d1.atTime(21, 50), CommuteRecorder.openArrival(s, d2.atTime(4, 0)))
    }

    @Test fun noNewArrivalWhileTheShiftIsOpenAfterMidnight() {
        val s = state(arrived = d1.atTime(22, 0))
        assertEquals(EnterPlan(false, Enter.OPEN), CommuteRecorder.onEnter(s, d2.atTime(2, 0), nightEnd))
    }

    @Test fun theNextDayIsANewArrival() {
        val s = state(arrived = d1.atTime(8, 0), left = d1.atTime(17, 0))
        assertEquals(EnterPlan(false, Enter.ARRIVE), CommuteRecorder.onEnter(s, d2.atTime(8, 0), null))
        assertEquals(EnterPlan(false, Enter.ARRIVE), CommuteRecorder.onEnter(state(), d2.atTime(8, 0), null))
    }

    @Test fun thePlannedEndIsTheShiftTypesOwnAndANightShiftEndsTheNextDay() {
        val night = com.bamnat.core.model.ShiftType(2, "야간", "야", 0xFF000000, java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0))
        val snapshot = com.bamnat.app.data.ScheduleSnapshot(listOf(night), com.bamnat.core.model.WorkPattern.Rotation(d1, listOf(2L)),
            listOf(com.bamnat.core.model.DayEntry(d1, customStart = java.time.LocalTime.of(22, 0), customEnd = java.time.LocalTime.of(2, 0))))
        // An end written earlier (02:00) does not move the planned one.
        assertEquals(nightEnd, CommuteRecorder.plannedEnd(snapshot.resolver.resolve(d1)))
        assertEquals(nightEnd.plusDays(1), CommuteRecorder.plannedEnd(snapshot.resolver.resolve(d2)))
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

    private fun checks() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.action == CommuteRecorder.ACTION_CHECK }

    /** [DAYW-07] The periodic check runs only while the feature is on, once, not waking the phone, and goes when it is turned off. */
    @Test fun thePeriodicCheckIsSetWhileOnAndGoesWhenTurnedOff() {
        val prefs = CommutePrefs.get(context)
        CommuteRecorder.turnOff(context)
        CommuteRecorder.catchUp(context, locationOn = true)
        assertTrue(checks().isEmpty())

        prefs.setEnabled(true)
        prefs.setPlace(37.5, 127.0)
        CommuteRecorder.catchUp(context, locationOn = true)
        CommuteRecorder.sync(context)
        val set = checks().single()
        assertEquals(AlarmManager.RTC, set.type)
        assertEquals(CommuteRecorder.CHECK_INTERVAL_MILLIS, set.interval)
        assertTrue(CommuteRecorder.checkScheduled(context))

        CommuteRecorder.turnOff(context)
        assertTrue(checks().isEmpty())
        assertFalse(CommuteRecorder.checkScheduled(context))
    }

    /** [DAYW-07] Location seen off: the system dropped the geofence, so the next check with location on puts it back. */
    @Test fun locationSeenOffMarksTheGeofenceGone() {
        val prefs = CommutePrefs.get(context)
        prefs.setEnabled(true)
        prefs.setPlace(37.5, 127.0)
        prefs.fenceRegistered(CommuteRecorder.fenceKey(prefs.current))
        CommuteRecorder.catchUp(context, locationOn = false)
        assertNull(prefs.current.fence)
        assertTrue(CommuteRecorder.checkScheduled(context))
        CommuteRecorder.turnOff(context)
    }
}
