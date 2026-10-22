package com.bamnat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

/** [CAL-02][CAL-03][DAY-04] What 1.12 saved turns into the new calendar settings, and they stay as chosen. */
@RunWith(AndroidJUnit4::class)
class CalendarSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @After fun clear() {
        prefs.edit().clear().commit()
    }

    @Test fun mondayWrittenBy112StartsOnSundayOnce() {
        prefs.edit().clear().putString("week_start", "MONDAY").commit()
        val repo = SettingsRepository(context)
        assertEquals(DayOfWeek.SUNDAY, repo.current.weekStart)
        repo.update { it.copy(weekStart = DayOfWeek.MONDAY) }
        assertEquals(DayOfWeek.MONDAY, SettingsRepository(context).current.weekStart)
    }

    @Test fun memoFirstOf112BecomesMemoInCellsAndMemoOnTop() {
        prefs.edit().clear().putBoolean("memo_first", true).commit()
        val s = SettingsRepository(context).current
        assertTrue(s.memoInCells)
        assertEquals(listOf("memo"), s.dayGroupOrder)
    }

    @Test fun dayViewAndOrderAreKept() {
        prefs.edit().clear().commit()
        val repo = SettingsRepository(context)
        // [CAL-20] A tap opens the day; the small card is off until turned on.
        assertEquals(PhoneDayView.SHEET, repo.current.phoneDayView)
        assertEquals(false, repo.current.showDayCard)
        repo.update { it.copy(phoneDayView = PhoneDayView.WEEK, dayGroupOrder = listOf("alarm", "work")) }
        val again = SettingsRepository(context).current
        assertEquals(PhoneDayView.WEEK, again.phoneDayView)
        assertEquals(listOf("alarm", "work"), again.dayGroupOrder)
        // Back to the default order stays default, not a stale one.
        repo.update { it.copy(dayGroupOrder = emptyList(), memoInCells = false) }
        assertEquals(emptyList<String>(), SettingsRepository(context).current.dayGroupOrder)
    }
}
