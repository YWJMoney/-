package com.bamnat.app

import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.ui.calendar.CalendarViewModel
import com.bamnat.app.ui.money.MoneyViewModel
import com.bamnat.app.ui.stats.StatsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.YearMonth

/** [NAV-01] Back within half an hour the screen stays; the calendar, 돈 and 근무·급여 show one month. */
@RunWith(AndroidJUnit4::class)
class M10NavigationTest {
    private fun away(minutes: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(minutes))

    @Test fun aShortLookElsewhereKeepsTheScreen() {
        val start = HomeRequests.count.value
        // A message read, a call taken: back where they were.
        HomeRequests.onStop(changingConfigurations = false)
        away(29)
        HomeRequests.onStart()
        assertEquals(start, HomeRequests.count.value)
        // Half an hour or more is opening the app again: the calendar.
        HomeRequests.onStop(changingConfigurations = false)
        away(30)
        HomeRequests.onStart()
        assertEquals(start + 1, HomeRequests.count.value)
        // Folding or turning the phone is not leaving, however long it takes.
        HomeRequests.onStop(changingConfigurations = true)
        away(45)
        HomeRequests.onStart()
        assertEquals(start + 1, HomeRequests.count.value)
        // Nor is a file picker or a permission page the app opened itself.
        HomeRequests.awaitingResult = true
        HomeRequests.onStop(changingConfigurations = false)
        away(45)
        HomeRequests.onStart()
        assertEquals(start + 1, HomeRequests.count.value)
        HomeRequests.awaitingResult = false
        // [285] Five minutes over midnight is a new day: the calendar opens on it.
        val day = java.time.LocalDate.of(2026, 10, 22)
        HomeRequests.today = { day }
        HomeRequests.onStop(changingConfigurations = false)
        away(5)
        HomeRequests.today = { day.plusDays(1) }
        HomeRequests.onStart()
        assertEquals(start + 2, HomeRequests.count.value)
        HomeRequests.today = { java.time.LocalDate.now() }
    }

    @Test fun aClosedAppBroughtBackSoonKeepsTheScreen() {
        val now = SystemClock.elapsedRealtime() + Duration.ofHours(5).toMillis()
        val minute = 60_000L
        assertTrue(HomeRequests.leftRecently(now - 29 * minute, now))
        assertFalse(HomeRequests.leftRecently(now - 30 * minute, now))
        // Nothing saved, or a time from before the phone restarted.
        assertFalse(HomeRequests.leftRecently(0L, now))
        assertFalse(HomeRequests.leftRecently(now + minute, now))
        // Saved before onStop (Android 8): the time is taken when the state is saved.
        HomeRequests.onStart()
        assertEquals(SystemClock.elapsedRealtime(), HomeRequests.leftAt)
        HomeRequests.onStop(changingConfigurations = false)
        val left = HomeRequests.leftAt
        away(10)
        assertEquals(left, HomeRequests.leftAt)
        HomeRequests.onStart()
    }

    @Test fun theCalendarMoneyAndStatsShowOneMonth() {
        val c = ApplicationProvider.getApplicationContext<BamnatApp>().container
        val calendar = CalendarViewModel(c)
        val money = MoneyViewModel(c)
        val stats = StatsViewModel(c)
        val last = YearMonth.now().minusMonths(1)
        // Turned back on the calendar, 돈 and 근무·급여 open on the same month.
        calendar.showMonth(last)
        assertEquals(last, money.shownMonth.value)
        assertEquals(last, stats.shownMonth.value)
        // And the other way round.
        stats.showMonth(last.minusMonths(1))
        assertEquals(last.minusMonths(1), calendar.shownMonth.value)
        assertEquals(last.minusMonths(1), money.shownMonth.value)
    }
}
