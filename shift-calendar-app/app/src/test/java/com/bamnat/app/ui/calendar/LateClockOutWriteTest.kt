package com.bamnat.app.ui.calendar

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/** [HRLY-08][DAYW-03] 어제 퇴근 on today's sheet after work run past midnight is written on yesterday, not today. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LateClockOutWriteTest {
    private val c get() = ApplicationProvider.getApplicationContext<BamnatApp>().container
    private val schedule get() = c.calendars.repository(PersonalCalendar.MAIN)
    private val today = LocalDate.of(2026, 10, 21)
    private val yesterday = today.minusDays(1)

    private fun settle() = repeat(20) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun stored(date: LocalDate): DayEntry? = runBlocking { schedule.entries.first() }.firstOrNull { it.date == date }

    /** Yesterday on a shift of [from]–[to], today off. */
    private suspend fun shifts(from: LocalTime, to: LocalTime) {
        val id = schedule.saveShiftType(ShiftType(0, "야간", "야", 0xFF3949AB, from, to))
        schedule.setOverride(yesterday, ShiftOverride.Shift(id))
        schedule.setOverride(today, ShiftOverride.Off)
    }

    @Test fun anHourlyOvernightShiftsClockOutChipOnTodaysSheetWritesYesterday() = runBlocking {
        shifts(LocalTime.of(22, 0), LocalTime.of(6, 0))
        val snapshot = schedule.snapshot.first()
        val todayDay = snapshot.resolver.resolve(today)
        val out = clockOutDay(todayDay, today, today.atTime(9, 30)) { snapshot.resolver.resolve(yesterday) }!!
        assertEquals(yesterday, out.date)
        assertEquals("어제 퇴근", clockOutLabel(todayDay, out))
        // From the planned end + 4h the chip is gone (today has no work).
        assertNull(clockOutDay(todayDay, today, today.atTime(10, 0)) { snapshot.resolver.resolve(yesterday) })

        val todayBefore = stored(today)
        CalendarViewModel(c).clockOut(out, today, LocalTime.of(9, 31))
        settle()
        val y = stored(yesterday)!!
        assertEquals(LocalTime.of(9, 30), y.customEnd)
        assertEquals(LocalTime.of(22, 0), y.customStart)
        assertEquals(todayBefore, stored(today))
    }

    @Test fun aDayWorkersLeaveNowAfterMidnightWritesYesterday() = runBlocking {
        shifts(LocalTime.of(9, 0), LocalTime.of(18, 0))
        val snapshot = schedule.snapshot.first()
        val todayDay = snapshot.resolver.resolve(today)
        val now = today.atTime(1, 0)
        val leave = leaveNowDay(WorkStyle.DAY, todayDay, today, now) { snapshot.resolver.resolve(yesterday) }!!
        assertEquals(yesterday, leave.date)

        val todayBefore = stored(today)
        CalendarViewModel(c).leaveNow(leave, now)
        settle()
        assertTrue(stored(yesterday)!!.extras.any { it.kindId == ExtraKind.OVERTIME_ID })
        assertEquals(todayBefore, stored(today))
    }
}
