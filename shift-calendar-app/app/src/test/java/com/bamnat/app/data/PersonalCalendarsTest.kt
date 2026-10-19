package com.bamnat.app.data

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.share.ShareLinkState
import com.bamnat.app.share.ShareLinkStore
import com.bamnat.app.ui.setup.SetupViewModel
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.setup.SetupStep
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [MCAL-01][MCAL-02][MCAL-03][MCAL-04][MCAL-05] The user's own calendars: the one the app always had
 * stays as it was, each added one is set up with the first launch's questions and keeps its own
 * shifts, order, days and link, the list keeps the user's order, and backups carry them all.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PersonalCalendarsTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val now = LocalDateTime.of(2026, 10, 19, 10, 0)
    private val today = now.toLocalDate()

    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Before fun defaults() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        waitFor("the first shifts") { runBlocking { c.scheduleRepository.shiftTypes.first().isNotEmpty() } }
    }

    /** A weekday calendar through every question to the summary, then saved. */
    private fun addWeekdayCalendar(name: String): String {
        val id = c.calendars.begin(name)
        val vm = SetupViewModel(c, now = { now }, calendarId = id)
        vm.pickStyle(WorkStyle.DAY)
        repeat(12) {
            val d = c.calendarSetupDrafts.draft.value!!
            when (d.step) {
                SetupStep.SUMMARY -> return@repeat
                SetupStep.TIMES -> vm.confirmTimes()
                else -> vm.next()
            }
        }
        assertEquals(SetupStep.SUMMARY, c.calendarSetupDrafts.draft.value!!.step)
        vm.save()
        waitFor("the calendar's save") { vm.done.value || vm.message.value != null }
        assertNull("the save failed", vm.message.value)
        return id
    }

    /** A listed calendar without going through the questions, for what does not depend on them. */
    private fun listedCalendar(name: String): String = c.calendars.begin(name).also { c.calendars.finishSetup(it, WorkStyle.HOURLY) }

    @Test fun aPhoneFromBeforeOpensOnItsOwnCalendarAsItWas() {
        val list = c.calendars.calendars.value
        assertEquals(listOf(PersonalCalendar.MAIN), list.map { it.id })
        assertEquals(PersonalCalendar.MAIN_NAME, list.single().name)
        assertEquals(PersonalCalendar.MAIN, c.calendars.active.value)
        assertSame(c.scheduleRepository, c.calendars.activeRepository())
        // Nothing is written until the user changes something.
        assertFalse(File(app.filesDir, "calendars.json").exists())
    }

    @Test fun anAddedCalendarIsSetUpLikeTheFirstLaunchAndKeepsItsOwnDays() = runBlocking {
        val id = addWeekdayCalendar("알바")
        val cal = c.calendars.get(id)!!
        assertTrue(cal.ready)
        assertEquals(WorkStyle.DAY, cal.style)
        // It is listed and shown at once; the app's own setup and kind of work are untouched.
        assertEquals(listOf(PersonalCalendar.MAIN, id), c.calendars.calendars.value.map { it.id })
        assertEquals(id, c.calendars.active.value)
        assertNull(c.settingsRepository.current.workStyle)
        assertNull(c.calendarSetupDrafts.draft.value)

        val added = c.calendars.repository(id)
        assertNotSame(c.scheduleRepository, added)
        assertEquals(1, added.patterns.first().size)
        assertTrue(c.scheduleRepository.patterns.first().isEmpty())
        val monday = today.with(java.time.DayOfWeek.MONDAY).plusWeeks(1)
        assertTrue(added.snapshot.first().resolver.resolve(monday).isWorkDay)

        // A day changed on the added calendar stays there.
        added.setOverride(monday, ShiftOverride.Off)
        assertFalse(added.snapshot.first().resolver.resolve(monday).isWorkDay)
        assertTrue(c.scheduleRepository.snapshot.first().entries.none { it.date == monday })
        // The calendar tab follows the switch.
        assertEquals(id, c.calendars.activeRepository().let { if (it === added) id else "other" })
        c.calendars.setActive(PersonalCalendar.MAIN)
        assertSame(c.scheduleRepository, c.calendars.activeRepository())
    }

    @Test fun aCalendarLeftBeforeItsSetupSavedIsNotKept() {
        val id = c.calendars.begin("가족")
        assertNull(c.calendars.calendars.value.firstOrNull { it.id == id })
        assertEquals(id, c.calendars.unfinished()?.id)
        // Picking it is not possible until it is set up.
        c.calendars.setActive(id)
        assertEquals(PersonalCalendar.MAIN, c.calendars.active.value)

        val vm = SetupViewModel(c, now = { now }, calendarId = id)
        vm.pickStyle(WorkStyle.SHIFT)
        vm.cancelCalendar()
        assertTrue(vm.done.value)
        assertNull(c.calendars.get(id))
        assertNull(c.calendarSetupDrafts.draft.value)
        assertFalse(app.getDatabasePath(CalendarStorage.fileName(id)).exists())

        // Without an answer kept, one left over is cleared on the next start.
        val left = c.calendars.begin("남은 것")
        c.calendars.dropUnfinished()
        assertNull(c.calendars.get(left))
    }

    @Test fun theListKeepsTheUsersOrderWithReceivedCalendarsInIt() {
        val a = listedCalendar("알바")
        val b = listedCalendar("가족")
        val keys = CalendarKey.arrange(c.calendars.order.value, c.calendars.calendars.value.map { it.id }, listOf("LINK1"))
        assertEquals(listOf("mine:main", "mine:$a", "mine:$b", "got:LINK1"), keys.map { it.key })

        // The received one first, then 가족, 내 달력, 알바.
        c.calendars.arrange(listOf(CalendarKey.Received("LINK1"), CalendarKey.Mine(b), CalendarKey.Mine(PersonalCalendar.MAIN), CalendarKey.Mine(a)))
        assertEquals(listOf(b, PersonalCalendar.MAIN, a), c.calendars.calendars.value.map { it.id })
        // A link received later goes to the end; one taken out leaves no gap.
        val again = CalendarKey.arrange(c.calendars.order.value, c.calendars.calendars.value.map { it.id }, listOf("LINK2"))
        assertEquals(listOf("mine:$b", "mine:main", "mine:$a", "got:LINK2"), again.map { it.key })

        // The order, names and the calendar on screen survive a restart.
        c.calendars.rename(a, "  편의점 알바  ")
        val reread = CalendarBook(File(app.filesDir, "calendars.json"), c.scheduleRepository, CalendarStorage.Files(app), { error("not opened") })
        assertEquals(listOf(b, PersonalCalendar.MAIN, a), reread.calendars.value.map { it.id })
        assertEquals("편의점 알바", reread.get(a)?.name)
        assertEquals(c.calendars.active.value, reread.active.value)
    }

    @Test fun removingACalendarTakesItsDatabaseAndGoesBackToTheMainOne() {
        val id = listedCalendar("알바")
        assertEquals(id, c.calendars.active.value)
        runBlocking { c.calendars.repository(id).ensureDefaults() }
        assertTrue(app.getDatabasePath(CalendarStorage.fileName(id)).exists())
        c.calendars.delete(id)
        assertNull(c.calendars.get(id))
        assertEquals(PersonalCalendar.MAIN, c.calendars.active.value)
        assertFalse(app.getDatabasePath(CalendarStorage.fileName(id)).exists())
        // The main calendar cannot go.
        c.calendars.delete(PersonalCalendar.MAIN)
        assertNotNull(c.calendars.get(PersonalCalendar.MAIN))
    }

    @Test fun anAddedCalendarRingsUnderItsNameUntilItsAlarmsAreOff() = runBlocking {
        val id = listedCalendar("알바")
        assertEquals(listOf(null, "알바"), c.calendars.ringing.first().map { it.first })
        c.calendars.setAlarms(id, false)
        assertEquals(listOf<String?>(null), c.calendars.ringing.first().map { it.first })
    }

    @Test fun eachCalendarKeepsItsOwnLink() {
        val id = listedCalendar("알바")
        val link = ShareLinkState("abc", "https://example.test/s/abc", "token", Instant.now().plusSeconds(86_400), "민지")
        ShareLinkStore(app, id).save(link)
        assertEquals("abc", ShareLinkStore(app, id).load()?.id)
        assertNull(ShareLinkStore(app).load())
        ShareLinkStore.forget(app, id)
        assertNull(ShareLinkStore(app, id).load())
    }

    @Test fun aBackupCarriesTheAddedCalendarsWithTheirDays() = runBlocking {
        val id = addWeekdayCalendar("알바")
        val day = LocalDate.of(2026, 11, 2)
        val added = c.calendars.repository(id)
        added.setOverride(day, ShiftOverride.Off)
        c.calendars.rename(PersonalCalendar.MAIN, "회사")
        val json = c.backupManager.export()
        assertEquals(1, c.backupManager.summarize(json).calendars)

        c.calendars.delete(id)
        c.calendars.rename(PersonalCalendar.MAIN, "다른 이름")
        c.backupManager.restore(json)
        val back = c.calendars.get(id)
        assertEquals("알바", back?.name)
        assertEquals(WorkStyle.DAY, back?.style)
        assertEquals("회사", c.calendars.get(PersonalCalendar.MAIN)?.name)
        val snap = c.calendars.repository(id).snapshot.first()
        assertFalse(snap.resolver.resolve(day).isWorkDay)
        assertTrue(snap.resolver.resolve(day.plusDays(1)).isWorkDay)
        // A restore opens on the main calendar.
        assertEquals(PersonalCalendar.MAIN, c.calendars.active.value)
    }
}
