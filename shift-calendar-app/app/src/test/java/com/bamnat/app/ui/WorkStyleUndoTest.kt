package com.bamnat.app.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** [UNDO-01] 되돌리기 of a change to hourly work takes back the 시급 types it added, and the kind of work. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WorkStyleUndoTest {
    private val c get() = ApplicationProvider.getApplicationContext<BamnatApp>().container

    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 60_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test fun undoingAChangeToHourlyWorkDeletesTheTypesItAdded() = runBlocking {
        val schedule = c.calendars.repository(PersonalCalendar.MAIN)
        waitFor("the first shifts") { runBlocking { schedule.shiftTypes.first().isNotEmpty() } }
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.SHIFT) }
        val before = schedule.shiftTypes.first().map { it.id }.toSet()

        val entry = c.recordsTrash.changeStyle(PersonalCalendar.MAIN, WorkStyle.HOURLY, clear = false, message = "바꿨어요")!!
        val added = addStyleTypes(c, PersonalCalendar.MAIN, WorkStyle.HOURLY)
        assertTrue(added.isNotEmpty())
        assertEquals(WorkStyle.HOURLY, c.settingsRepository.current.workStyle)

        assertTrue(undoOffer(c, entry.id, StyleExtras(entry.id, PersonalCalendar.MAIN, added, null)))
        assertEquals(before, schedule.shiftTypes.first().map { it.id }.toSet())
        assertEquals(WorkStyle.SHIFT, c.settingsRepository.current.workStyle)
        assertTrue(c.recordsTrash.entries.value.isEmpty())
    }

    /** Extras of another change are not this entry's to take back. */
    @Test fun anotherChangesExtrasStay() = runBlocking {
        val schedule = c.calendars.repository(PersonalCalendar.MAIN)
        waitFor("the first shifts") { runBlocking { schedule.shiftTypes.first().isNotEmpty() } }
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.SHIFT) }
        val entry = c.recordsTrash.changeStyle(PersonalCalendar.MAIN, WorkStyle.HOURLY, clear = false, message = "바꿨어요")!!
        val added = addStyleTypes(c, PersonalCalendar.MAIN, WorkStyle.HOURLY)
        assertTrue(undoOffer(c, entry.id, StyleExtras("other", PersonalCalendar.MAIN, added, null)))
        assertTrue(schedule.shiftTypes.first().map { it.id }.containsAll(added))
        assertTrue(addStyleTypes(c, PersonalCalendar.MAIN, WorkStyle.DAY).isEmpty())
    }
}
