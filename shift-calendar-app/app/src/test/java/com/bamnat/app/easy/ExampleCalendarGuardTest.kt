package com.bamnat.app.easy

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.CalendarStorage
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.core.example.Browse
import com.bamnat.core.example.BrowseState
import com.bamnat.core.memo.Memo
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * [EASY-01][EASY-02] The example of 우선 둘러볼게요 never leaves the phone: not in a backup file, not in what Android
 * backs up or moves to a new phone, not ringing, not counted as an added calendar; and it goes whole.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ExampleCalendarGuardTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container

    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 60_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Before fun defaults() {
        waitFor("the first shifts") { runBlocking { c.scheduleRepository.shiftTypes.first().isNotEmpty() } }
    }

    @Test fun `every kind of work makes a full example in no-backup storage, alarms off`() = runBlocking {
        WorkStyle.entries.forEach { style ->
            val cal = c.example.make(style)
            assertTrue(cal.isExample)
            assertFalse(cal.alarms)
            assertEquals(PersonalCalendar.EXAMPLE, c.calendars.active.value)
            assertEquals(style, c.calendars.get(PersonalCalendar.EXAMPLE)!!.style)
            val db = File(CalendarStorage.pathOf(app, PersonalCalendar.EXAMPLE))
            assertTrue("$style DB", db.exists())
            assertTrue(db.absolutePath.startsWith(app.noBackupFilesDir.absolutePath))
            assertTrue(CalendarStorage.filesOf(app, PersonalCalendar.EXAMPLE).absolutePath.startsWith(app.noBackupFilesDir.absolutePath))
            val memos = c.memoRepository.memos.first().filter { PersonalCalendar.EXAMPLE in it.calendars }
            assertTrue("$style 메모", memos.isNotEmpty())
            if (style.usesPayEngine) assertNotNull("$style 급여", c.calendars.pay(PersonalCalendar.EXAMPLE).profile.value)
        }
        // Nothing of it rings.
        assertTrue(c.calendars.ringing.first().none { it.first == com.bamnat.core.example.ExamplePlan.NAME })
        assertTrue(c.calendars.added().none { it.isExample })
    }

    @Test fun `a backup file holds nothing of the example`() = runBlocking {
        c.example.make(WorkStyle.SHIFT)
        // A memo of the user's own, on 내 달력, and one shared between the two.
        c.memoRepository.save(Memo(title = "내 메모", body = "진짜"))
        c.memoRepository.save(Memo(title = "같이", body = "둘 다", calendars = setOf(PersonalCalendar.MAIN, PersonalCalendar.EXAMPLE)))
        val root = JSONObject(c.backupManager.export())
        val text = root.toString()
        assertFalse(text.contains(PersonalCalendar.EXAMPLE + "\""))
        assertFalse(text.contains("(예시)"))
        val memos = root.getJSONArray("memos")
        val titles = (0 until memos.length()).map { memos.getJSONObject(it).getString("title") }
        assertTrue("내 메모" in titles)
        assertTrue("같이" in titles)
        assertTrue(titles.none { it.contains("예시") })
        val shared = (0 until memos.length()).map { memos.getJSONObject(it) }.first { it.getString("title") == "같이" }
        assertFalse(shared.optString("calendars").contains(PersonalCalendar.EXAMPLE))
    }

    @Test fun `removing takes everything, and its memos never go to 내 달력`() = runBlocking {
        c.example.make(WorkStyle.HOURLY)
        c.example.remove()
        assertNull(c.calendars.get(PersonalCalendar.EXAMPLE))
        assertEquals(PersonalCalendar.MAIN, c.calendars.active.value)
        assertFalse(File(CalendarStorage.pathOf(app, PersonalCalendar.EXAMPLE)).exists())
        val left = c.memoRepository.memos.first()
        assertTrue(left.none { it.title.contains("예시") })
    }

    @Test fun `on start an example not wanted any more goes`() = runBlocking {
        val today = LocalDate.of(2026, 10, 23)
        c.example.make(WorkStyle.DAY)
        // Still looking around: it stays.
        c.browse.set(Browse.start(WorkStyle.DAY, today)); c.browse.kept = false
        c.example.sweep()
        assertNotNull(c.calendars.get(PersonalCalendar.EXAMPLE))
        // Kept beside the user's own calendar: it stays.
        c.browse.set(BrowseState()); c.browse.kept = true
        c.example.sweep()
        assertNotNull(c.calendars.get(PersonalCalendar.EXAMPLE))
        // Neither: it goes.
        c.browse.kept = false
        c.example.sweep()
        assertNull(c.calendars.get(PersonalCalendar.EXAMPLE))
        assertTrue(c.memoRepository.memos.first().none { it.title.contains("예시") })
    }
}
