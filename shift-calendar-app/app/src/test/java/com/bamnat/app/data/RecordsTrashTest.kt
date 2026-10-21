package com.bamnat.app.data

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.ui.calendar.removeCalendar
import com.bamnat.core.invest.CashFlow
import com.bamnat.core.invest.CashKind
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.Trade
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

        val entry = c.recordsTrash.clearRecords(id)!!
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
        val kept = c.recordsTrash.changeStyle(id, WorkStyle.HOURLY, clear = false, message = "")!!
        assertEquals(WorkStyle.HOURLY, c.calendars.get(id)!!.style)
        assertEquals(listOf(day), daysOf(id))
        assertTrue(c.recordsTrash.restore(kept.id))
        assertEquals(WorkStyle.BUSINESS, c.calendars.get(id)!!.style)

        val cleared = c.recordsTrash.changeStyle(id, WorkStyle.SHIFT, clear = true, message = "")!!
        assertEquals(WorkStyle.SHIFT, c.calendars.get(id)!!.style)
        assertTrue(daysOf(id).isEmpty())
        assertTrue(c.recordsTrash.restore(cleared.id))
        assertEquals(WorkStyle.BUSINESS, c.calendars.get(id)!!.style)
        assertEquals(listOf(day), daysOf(id))

        // 내 달력's kind of work lives in the settings and comes back there.
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.DAY) }
        val main = c.recordsTrash.changeStyle(PersonalCalendar.MAIN, WorkStyle.FIELD, clear = false, message = "")!!
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

    /** [UNDO-03] Its work tools, contract photos and where notices and 출퇴근 기록 went come back too. */
    @Test fun aRemovedCalendarBringsBackItsWorkToolsAndRouting() = runBlocking {
        val (id, _) = filledCalendar("편의점")
        val tools = WorkToolsStore.get(app)
        File(tools.photoDir(id).apply { mkdirs() }, "a.jpg").writeText("photo")
        tools.update(id) { WorkTools(contract = com.bamnat.core.work.WorkContract(photos = listOf("a.jpg"), hourlyWage = 11_000)) }
        val toolsFile = File(tools.dir(id), WorkToolsStore.FILE)
        waitFor("the tools file") { toolsFile.exists() }
        c.autoRecordPrefs.setCalendar(id)
        c.autoRecordPrefs.setAppCalendar("com.shop.pos", id)
        val commute = CommutePrefs.get(app)
        commute.setCalendar(id)

        assertTrue(removeCalendar(c, id, keepLink = true))
        assertFalse(toolsFile.exists())
        assertEquals(PersonalCalendar.MAIN, c.autoRecordPrefs.current.calendar)
        assertEquals(PersonalCalendar.MAIN, commute.current.calendarId)

        assertTrue(c.recordsTrash.restore(c.recordsTrash.entries.value.single().id))
        assertEquals(11_000L, WorkToolsStore.decode(org.json.JSONObject(toolsFile.readText())).contract.hourlyWage)
        assertTrue(tools.photo(id, "a.jpg")!!.exists())
        assertEquals(listOf("a.jpg"), tools.current(id).contract.photos)
        assertEquals(id, c.autoRecordPrefs.current.calendar)
        assertEquals(id, c.autoRecordPrefs.current.appCalendars["com.shop.pos"])
        assertEquals(id, commute.current.calendarId)
        // Its pay setup came back on its own path, not twice.
        assertEquals(profile, c.calendars.pay(id).profile.value)
    }

    /** [UNDO-03][HRLY-10] An import without an added calendar puts it in 최근 지운 기록 first, its contract photos too. */
    @Test fun aCalendarAnImportTakesAwayCanBeRestoredWithItsPhotos() = runBlocking {
        val json = c.backupManager.export()
        val (id, memo) = filledCalendar("편의점")
        val tools = WorkToolsStore.get(app)
        File(tools.photoDir(id).apply { mkdirs() }, "a.jpg").writeText("photo")
        tools.update(id) { WorkTools(contract = com.bamnat.core.work.WorkContract(photos = listOf("a.jpg"), hourlyWage = 11_000)) }
        waitFor("the tools file") { File(tools.dir(id), WorkToolsStore.FILE).exists() }

        c.backupManager.restore(json)
        assertNull(c.calendars.get(id))
        assertNull(tools.photo(id, "a.jpg")?.takeIf { it.exists() })
        val entry = c.recordsTrash.entries.value.single()
        assertEquals(RecordsTrash.Kind.DELETED, entry.kind)
        assertEquals(id, entry.calendarId)

        assertTrue(c.recordsTrash.restore(entry.id))
        assertEquals("편의점", c.calendars.get(id)!!.name)
        assertEquals(listOf(day), daysOf(id))
        assertEquals("photo", tools.photo(id, "a.jpg")!!.readText())
        assertEquals(listOf("a.jpg"), tools.current(id).contract.photos)
        assertEquals(setOf(id), c.memoRepository.get(memo.id)!!.calendars)
    }

    /** [UNDO-03][SET-01] Taking that import back brings the calendar back with its photos, and its trash entry goes. */
    @Test fun takingTheImportBackBringsThePhotosBackAndDropsTheEntry() = runBlocking {
        val json = c.backupManager.export()
        val (id, _) = filledCalendar("편의점")
        c.calendars.repository(id).ensureDefaults()
        val tools = WorkToolsStore.get(app)
        File(tools.photoDir(id).apply { mkdirs() }, "a.jpg").writeText("photo")
        tools.update(id) { WorkTools(contract = com.bamnat.core.work.WorkContract(photos = listOf("a.jpg"), hourlyWage = 11_000)) }
        waitFor("the tools file") { File(tools.dir(id), WorkToolsStore.FILE).exists() }

        c.importUndo.save()
        c.backupManager.restore(json)
        assertNull(c.calendars.get(id))
        assertEquals(1, c.recordsTrash.entries.value.size)

        c.importUndo.undo()
        assertEquals("편의점", c.calendars.get(id)!!.name)
        assertEquals("photo", tools.photo(id, "a.jpg")!!.readText())
        assertEquals(listOf("a.jpg"), tools.current(id).contract.photos)
        assertEquals(11_000L, tools.current(id).contract.hourlyWage)
        assertTrue(c.recordsTrash.entries.value.isEmpty())
    }

    /** [UNDO-02] Putting cleared records back replaces what was written since, and that is kept in turn. */
    @Test fun puttingBackKeepsWhatWasWrittenAfterTheClear() = runBlocking {
        val (id, _) = filledCalendar("가게")
        val entry = c.recordsTrash.clearRecords(id)!!
        c.calendars.money(id).save(DailySales(day.plusDays(1), 50_000))
        assertTrue(c.recordsTrash.restore(entry.id))
        assertEquals(listOf(day), c.calendars.money(id).sales.first().map { it.date })
        val since = c.recordsTrash.entries.value.single()
        assertEquals(RecordsTrash.Kind.CLEARED, since.kind)

        assertTrue(c.recordsTrash.restore(since.id))
        assertEquals(listOf(day.plusDays(1)), c.calendars.money(id).sales.first().map { it.date })
    }

    @Test fun afterSevenDaysItIsGoneForGood() = runBlocking {
        var now = System.currentTimeMillis()
        val dir = File(app.noBackupFilesDir, "trash_test")
        val trash = RecordsTrash(dir, c.calendars, c.database, c.settingsRepository, clock = { now })
        val (id, _) = filledCalendar("현장")
        val entry = trash.clearRecords(id)!!
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
        // [SHR-01] A link shows only the calendar's own memos, never those it sees through 메모 공유.
        assertEquals(mapOf(day to "알바 메모"), com.bamnat.app.share.ShareContent.memos(c, day, alba))
        c.memoSharing.set(MemoSharing.SEPARATE)
        assertEquals(setOf(onAlba.id), ids(alba))
    }

    @Test fun anInvestorCalendarsTradesAreClearedAndComeBack() = runBlocking {
        val id = c.calendars.begin("주식").also { c.calendars.finishSetup(it, WorkStyle.INVEST) }
        val repo = InvestRepository.of(c.calendars.database(id))
        val acc = repo.ensureAccount()
        repo.saveCash(CashFlow(accountId = acc, date = day, kind = CashKind.DEPOSIT, amount = 1_000_000))
        repo.saveTrade(Trade(accountId = acc, date = day, symbol = "005930", name = "삼성전자", side = Side.BUY, quantity = 1.0, price = 70_000.0))
        val before = c.recordsTrash.records(id)
        assertEquals(1, before.trades)
        assertFalse(before.isEmpty)
        assertTrue(before.text.contains("매매 기록 1건"))

        val entry = c.recordsTrash.clearRecords(id)!!
        assertTrue(repo.now().trades.isEmpty())
        assertTrue(repo.now().cash.isEmpty())
        assertTrue(c.recordsTrash.records(id).isEmpty)

        assertTrue(c.recordsTrash.restore(entry.id))
        assertEquals(1, repo.now().trades.size)
        assertEquals(1, repo.now().cash.size)
    }

    @Test fun aCalendarNotThereIsNeverClearedInsteadOfTheMainOne() = runBlocking {
        c.scheduleRepository.setOverride(day, ShiftOverride.Off)
        assertNull(c.recordsTrash.clearRecords("gone"))
        assertEquals(listOf(day), c.scheduleRepository.snapshot.first().entries.map { it.date })
    }
}
