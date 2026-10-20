package com.bamnat.app.data

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.ui.calendar.removeCalendar
import com.bamnat.core.memo.Memo
import com.bamnat.core.memo.MemoCalendars
import com.bamnat.core.memo.MemoSharing
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.money.DailySales
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * [UNDO-01][UNDO-02][UNDO-03] A calendar's records cleared, its kind of work changed or the whole
 * calendar removed come back as they were within 7 days, and go for good after. [MEMO-01] Memos stay
 * with their own calendar unless calendars share them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecordsTrashTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val day = LocalDate.of(2026, 10, 21)
    private val profile = PayProfile(Occupation.MANUFACTURING, WageType.HOURLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 10_030))))

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

    /** An added calendar with a changed day, a day's sales, a pay setup and a memo of its own. */
    private suspend fun filledCalendar(name: String): Pair<String, Memo> {
        val id = c.calendars.begin(name).also { c.calendars.finishSetup(it, WorkStyle.BUSINESS) }
        c.calendars.repository(id).setOverride(day, ShiftOverride.Off)
        c.calendars.money(id).save(DailySales(day, 120_000))
        c.calendars.pay(id).save(profile)
        val memo = c.memoRepository.save(Memo(date = day, body = "재고 확인", calendars = setOf(id)))
        return id to memo
    }

    private suspend fun daysOf(id: String) = c.calendars.repository(id).snapshot.first().entries.map { it.date }

    @Test fun clearedRecordsComeBackWithTheirMemoAndPay() = runBlocking {
        val (id, memo) = filledCalendar("가게")
        val shared = c.memoRepository.save(Memo(date = day, body = "모두", calendars = setOf(MemoCalendars.EVERY)))
        val before = c.recordsTrash.records(id)
        assertEquals(1, before.days)
        assertEquals(1, before.sales)
        assertEquals(1, before.memos)
        assertTrue(before.pay)

        val entry = c.recordsTrash.clearRecords(id)
        assertTrue(daysOf(id).isEmpty())
        assertTrue(c.calendars.money(id).sales.first().isEmpty())
        assertNull(c.calendars.pay(id).profile.value)
        assertNotNull(c.memoRepository.get(memo.id)!!.deletedAtMillis)
        // A memo for every calendar is not this calendar's to clear.
        assertNull(c.memoRepository.get(shared.id)!!.deletedAtMillis)
        assertEquals(WorkStyle.BUSINESS, c.calendars.get(id)!!.style)
        assertEquals(listOf(entry.id), c.recordsTrash.entries.value.map { it.id })

        assertTrue(c.recordsTrash.restore(entry.id))
        assertEquals(listOf(day), daysOf(id))
        assertEquals(120_000L, c.calendars.money(id).sales.first().single().sales)
        assertEquals(profile, c.calendars.pay(id).profile.value)
        assertNull(c.memoRepository.get(memo.id)!!.deletedAtMillis)
        assertTrue(c.recordsTrash.entries.value.isEmpty())
        // Used up: it cannot be put back twice.
        assertFalse(c.recordsTrash.restore(entry.id))
    }

    @Test fun aChangedKindOfWorkCanBeTakenBackKeptOrCleared() = runBlocking {
        val (id, _) = filledCalendar("알바")
        val kept = c.recordsTrash.changeStyle(id, WorkStyle.HOURLY, clear = false, message = "")
        assertEquals(WorkStyle.HOURLY, c.calendars.get(id)!!.style)
        assertEquals(listOf(day), daysOf(id))
        assertTrue(c.recordsTrash.restore(kept.id))
        assertEquals(WorkStyle.BUSINESS, c.calendars.get(id)!!.style)

        val cleared = c.recordsTrash.changeStyle(id, WorkStyle.SHIFT, clear = true, message = "")
        assertEquals(WorkStyle.SHIFT, c.calendars.get(id)!!.style)
        assertTrue(daysOf(id).isEmpty())
        assertTrue(c.recordsTrash.restore(cleared.id))
        assertEquals(WorkStyle.BUSINESS, c.calendars.get(id)!!.style)
        assertEquals(listOf(day), daysOf(id))

        // 내 달력's kind of work lives in the settings and comes back there.
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.DAY) }
        val main = c.recordsTrash.changeStyle(PersonalCalendar.MAIN, WorkStyle.FIELD, clear = false, message = "")
        assertEquals(WorkStyle.FIELD, c.settingsRepository.current.workStyle)
        assertTrue(c.recordsTrash.restore(main.id))
        assertEquals(WorkStyle.DAY, c.settingsRepository.current.workStyle)
    }

    @Test fun aRemovedCalendarComesBackWholeWithTheSameId() = runBlocking {
        val (id, memo) = filledCalendar("편의점")
        assertTrue(removeCalendar(c, id, keepLink = true))
        assertNull(c.calendars.get(id))
        // Its memo went to 내 달력 meanwhile.
        assertEquals(setOf(PersonalCalendar.MAIN), c.memoRepository.get(memo.id)!!.calendars)
        val entry = c.recordsTrash.entries.value.single()
        assertEquals(RecordsTrash.Kind.DELETED, entry.kind)

        assertTrue(c.recordsTrash.restore(entry.id))
        val back = c.calendars.get(id)!!
        assertEquals("편의점", back.name)
        assertEquals(WorkStyle.BUSINESS, back.style)
        assertTrue(id in c.calendars.calendars.value.map { it.id })
        assertEquals(listOf(day), daysOf(id))
        assertEquals(120_000L, c.calendars.money(id).sales.first().single().sales)
        assertEquals(profile, c.calendars.pay(id).profile.value)
        assertEquals(setOf(id), c.memoRepository.get(memo.id)!!.calendars)
    }

    @Test fun afterSevenDaysItIsGoneForGood() = runBlocking {
        var now = System.currentTimeMillis()
        val dir = File(app.noBackupFilesDir, "trash_test")
        val trash = RecordsTrash(dir, c.calendars, c.database, c.settingsRepository, clock = { now })
        val (id, _) = filledCalendar("현장")
        val entry = trash.clearRecords(id)
        now += 6 * 24 * 60 * 60 * 1000L
        trash.purge()
        assertEquals(1, trash.entries.value.size)
        assertEquals(1, trash.entries.value.single().daysLeft(now))
        now += 2 * 24 * 60 * 60 * 1000L
        trash.purge()
        assertTrue(trash.entries.value.isEmpty())
        assertFalse(File(dir, entry.id).exists())
        assertFalse(trash.restore(entry.id))
        assertTrue(daysOf(id).isEmpty())
    }

    @Test fun memosStayOnTheirCalendarUnlessCalendarsShare() = runBlocking {
        val alba = c.calendars.begin("알바").also { c.calendars.finishSetup(it, WorkStyle.HOURLY) }
        val shop = c.calendars.begin("가게").also { c.calendars.finishSetup(it, WorkStyle.BUSINESS) }
        val old = c.memoRepository.save(Memo(date = day, body = "예전 메모"))
        val onAlba = c.memoRepository.save(Memo(date = day, body = "알바 메모", calendars = setOf(alba)))
        fun ids(cal: String) = runBlocking { c.memoRepository.memosOn(day, cal).first().map { it.id }.toSet() }

        assertEquals(setOf(old.id), ids(PersonalCalendar.MAIN))
        assertEquals(setOf(onAlba.id), ids(alba))
        assertTrue(ids(shop).isEmpty())
        assertEquals(setOf(day), c.memoRepository.datesWithMemos(alba).first())
        assertTrue(c.memoRepository.datesWithMemos(shop).first().isEmpty())

        c.memoSharing.set(MemoSharing(listOf(setOf(PersonalCalendar.MAIN, alba))))
        assertEquals(setOf(old.id, onAlba.id), ids(PersonalCalendar.MAIN))
        assertEquals(setOf(old.id, onAlba.id), ids(alba))
        assertTrue(ids(shop).isEmpty())
        c.memoSharing.set(MemoSharing.SEPARATE)
        assertEquals(setOf(onAlba.id), ids(alba))
    }
}
