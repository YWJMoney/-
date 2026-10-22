package com.bamnat.app.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.data.SettingsRepository
import com.bamnat.app.diag.EventLog
import com.bamnat.app.diag.Telemetry
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** [REL-13] The alarm self-check and the one-minute test alarm of 알람 점검. */
@RunWith(AndroidJUnit4::class)
class AlarmSelfCheckTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val day = ShiftType(1, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0), alarmEnabled = true)
    private val calendars = listOf(null to ScheduleSnapshot(listOf(day), WorkPattern.Rotation(LocalDate.now().minusDays(3), listOf(1L)), emptyList()))
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
    private lateinit var scheduler: AlarmScheduler

    @Before fun setUp() {
        // Let the app's own first planning run before this test plans its schedule.
        Thread.sleep(800)
        shadowOf(Looper.getMainLooper()).idle()
        scheduler = AlarmScheduler(context, Telemetry(EventLog(context), SettingsRepository(context)))
    }

    @Test fun aLostAlarmIsRegisteredAgain() {
        val now = LocalDateTime.now()
        scheduler.reschedule(calendars, true, now)
        assertTrue(scheduler.systemHolds())
        assertFalse("nothing to repair", scheduler.verify(calendars, true, now))

        // A force stop or a cleaner app: the system drops the alarm and its intent.
        val pi = AlarmScheduler.shiftAlarmIntent(context, null)
        alarmManager.cancel(pi)
        pi.cancel()
        assertFalse(scheduler.systemHolds())

        assertTrue("repaired", scheduler.verify(calendars, true, now))
        assertTrue(scheduler.systemHolds())
        val next = shadowOf(alarmManager).nextScheduledAlarm
        assertNotNull(next)
        val expected = now.toLocalDate().plusDays(if (now.toLocalTime() < LocalTime.of(6, 0)) 0 else 1).atTime(6, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, next!!.triggerAtTime)
    }

    /** Between an alarm ringing and its re-plan the alarm is not lost: no repair is logged then. */
    @Test fun anAlarmThatJustRangIsNotRepaired() {
        val now = LocalDateTime.now()
        scheduler.reschedule(calendars, true, now)
        scheduler.markFired(scheduler.upcoming.value!!.triggerAtMillis)
        assertFalse(scheduler.systemHolds())
        assertFalse("still settling", scheduler.verify(calendars, true, now))
    }

    @Test fun alarmsSwitchedOffNeedNoRepair() {
        scheduler.reschedule(calendars, false)
        assertFalse(scheduler.verify(calendars, false))
    }

    @Test fun theTestAlarmRingsInAMinuteAndLeavesTheShiftAlarm() {
        val now = LocalDateTime.of(LocalDate.now(), LocalTime.of(12, 0, 10))
        scheduler.reschedule(calendars, true, now)
        val at = scheduler.scheduleTest(now)
        assertEquals(now.toLocalDate().atTime(12, 1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), at)
        assertEquals(at, scheduler.testAt.value)
        val scheduled = shadowOf(alarmManager).scheduledAlarms
        val test = scheduled.single { shadowOf(it.operation).savedIntent.action == AlarmReceiver.ACTION_TEST_ALARM }
        assertEquals(at, test.triggerAtTime)
        // The shift alarm is still there.
        assertTrue(scheduled.any { shadowOf(it.operation).savedIntent.action == AlarmReceiver.ACTION_SHIFT_ALARM })
        val ring = RingRequest.fromIntent(shadowOf(test.operation).savedIntent)
        assertEquals(AlarmScheduler.TEST_NAME, ring?.label)

        scheduler.cancelTest()
        assertNull(scheduler.testAt.value)
        assertTrue(shadowOf(alarmManager).scheduledAlarms.none { shadowOf(it.operation).savedIntent.action == AlarmReceiver.ACTION_TEST_ALARM })
    }

    @Test fun aTestSetLateInAMinuteStillGivesHalfAMinute() {
        val now = LocalDateTime.of(LocalDate.now(), LocalTime.of(12, 0, 45))
        val at = scheduler.scheduleTest(now)
        assertEquals(now.toLocalDate().atTime(12, 2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), at)
        scheduler.cancelTest()
    }

    @Test fun theTestIntentCarriesNoShiftData() {
        val i = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TEST_ALARM)
        assertNull(RingRequest.fromIntent(i))
    }

    /** [FEAT-18] The lock-screen notice reads today and tomorrow, and shows a leave only as a link would. */
    @Test fun theTodayNoticeReadsTodayAndTomorrow() {
        val off = ShiftType(3, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
        val start = LocalDate.of(2026, 11, 2)
        val snapshot = ScheduleSnapshot(listOf(day, off), WorkPattern.Rotation(start, listOf(1L, 3L)),
            listOf(com.bamnat.core.model.DayEntry(start.plusDays(2), leave = com.bamnat.core.model.Leave(com.bamnat.core.model.LeaveKind.UNPAID))))
        assertEquals("오늘 주간 07:00~19:00" to "내일 휴무", TodayShiftNotice.lines(snapshot.resolver, start))
        assertEquals("휴가", TodayShiftNotice.lines(snapshot.resolver, start.plusDays(1)).second.removePrefix("내일 "))
    }

    /** 반차 keeps the shift on the lock screen, with the leave beside it; a whole day off shows the leave alone. */
    @Test fun aHalfDayLeaveStillShowsTheShift() {
        val start = LocalDate.of(2026, 11, 2)
        val snapshot = ScheduleSnapshot(listOf(day), WorkPattern.Rotation(start, listOf(1L)),
            listOf(com.bamnat.core.model.DayEntry(start, leave = com.bamnat.core.model.Leave(com.bamnat.core.model.LeaveKind.ANNUAL, 2))))
        val today = TodayShiftNotice.lines(snapshot.resolver, start).first.removePrefix("오늘 ")
        assertTrue(today, today.startsWith("주간 ") && today.endsWith(" · 반차"))
        assertEquals("주간 07:00~19:00", TodayShiftNotice.lines(snapshot.resolver, start).second.removePrefix("내일 "))
    }
}
