package com.bamnat.app.imports

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.IncomingFiles
import com.bamnat.app.IncomingRoster
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.DutyCodeBook
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.data.ScheduleRepository
import com.bamnat.app.data.SettingsRepository
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.ui.imports.DutyRosterViewModel
import com.bamnat.core.imports.DutyCell
import com.bamnat.core.journal.JournalKind
import com.bamnat.core.model.LeaveKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * [ROST-01][ROST-02][ROST-05][ROST-06][ROST-08][ROST-09][ROST-11] 한 달 근무 넣기 as the screen drives it: keys typed day
 * after day, the order repeated to the month's end, a roster's 반차 and 병가, the code book that remembers an answer
 * (in the backup too), what a shared photo or text opens, and the journal line.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MonthInputTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container

    private fun idleUntil(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
    }

    @Before fun ready() = runBlocking {
        idleUntil("the first shifts") { runBlocking { c.scheduleRepository.shiftTypes.first().isNotEmpty() } }
        c.journal.clear()
    }

    private fun types() = runBlocking { c.scheduleRepository.shiftTypes.first() }.associateBy { it.name }

    @Test fun typedDayAfterDayThenRepeated() = runBlocking {
        val vm = DutyRosterViewModel(c, PersonalCalendar.MAIN)
        val t = types()
        val day = DutyCell.Shift(t.getValue("주간").id)
        val night = DutyCell.Shift(t.getValue("야간").id)
        val off = DutyCell.Shift(t.getValue("휴무").id)
        vm.tap(1)
        listOf(day, day, night, night, off, off).forEach(vm::put)
        assertEquals(7, vm.ui.value.cursor)
        assertTrue(vm.repeatOrder())
        val u = vm.ui.value
        val days = u.month.lengthOfMonth()
        assertEquals((1..days).toSet(), u.cells.keys)
        assertEquals(night, u.cells[9])
        // A drag fills with the last key, and one step back is one undo.
        vm.paint(listOf(1, 2))
        assertEquals(off, vm.ui.value.cells[1])
        vm.undo()
        assertEquals(day, vm.ui.value.cells[1])
        // The mark goes back to the first day the undone step changed, so the next key fills it instead of skipping it.
        assertEquals(1, vm.ui.value.cursor)
        vm.apply()
        val month = u.month
        idleUntil("the days written") {
            runBlocking { c.scheduleRepository.snapshot.first() }.resolver.resolve(month.atDay(9)).shiftType?.id == t.getValue("야간").id
        }
        idleUntil("the journal line") { runBlocking { c.journal.load() }.any { it.kind == JournalKind.ROSTER } }
        val line = runBlocking { c.journal.load() }.last { it.kind == JournalKind.ROSTER }
        assertEquals("${month.monthValue} $days 4", line.detail)
        assertEquals("${month.monthValue}월 근무 ${days}일 넣음 (직접)", com.bamnat.core.journal.Journal.text(line))
    }

    @Test fun halfDaysAndAskedLeaveFromAPastedRoster() = runBlocking {
        c.dutyCodes.forget(PersonalCalendar.MAIN, "병가")
        val vm = DutyRosterViewModel(c, PersonalCalendar.MAIN)
        vm.readText("2026년 10월 근무표\n1 2 3 4 5 6\n김민지RN 주* 반차 병가 야 N-O 휴")
        idleUntil("the roster read") { vm.ui.value.rows != null }
        var u = vm.ui.value
        assertEquals(YearMonth.of(2026, 10), u.month)
        assertEquals("김민지", u.row!!.label)
        assertEquals(DutyCell.Leave(quarters = 2), u.cells[2])
        assertEquals("병가", u.asks.single().raw)
        assertTrue(u.asks.single().leave)
        assertEquals(mapOf(1 to "*", 5 to "N-O"), u.notes)
        // The answer is the calendar's: the next roster needs no question.
        vm.answer("병가", DutyCell.Leave(LeaveKind.PAID))
        u = vm.ui.value
        assertTrue(u.asks.isEmpty())
        assertEquals(DutyCell.Leave(LeaveKind.PAID), u.cells[3])
        assertEquals(DutyCell.Leave(LeaveKind.PAID), c.dutyCodes.of(PersonalCalendar.MAIN)["병가"])
        val again = DutyRosterViewModel(c, PersonalCalendar.MAIN)
        again.readText("1 2 3 4 5\n주 병가 야 휴 휴")
        idleUntil("the second roster") { again.ui.value.rows != null }
        assertTrue(again.ui.value.asks.isEmpty())
        vm.apply()
        val oct = YearMonth.of(2026, 10)
        fun entry(d: Int) = runBlocking { c.scheduleRepository.snapshot.first() }.entries.firstOrNull { it.date == oct.atDay(d) }
        idleUntil("the leave written") { entry(3)?.leave?.kind == LeaveKind.PAID }
        val half = entry(2)!!.leave!!
        assertEquals(2, half.quarters)
        assertTrue(half.kind.annual)
        // The marks went into that day's memo, so nothing written on the roster is lost.
        idleUntil("the memo") { runBlocking { c.memoRepository.memosOn(YearMonth.of(2026, 10).atDay(5)).first() }.any { "N-O" in it.body } }
    }

    @Test fun theCodeBookTravelsInTheBackup() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val book = DutyCodeBook(app)
            book.learn("main", "M", DutyCell.Shift(7))
            book.learn("main", "병가", DutyCell.Leave(LeaveKind.UNPAID, 4))
            book.learn("main", "반", DutyCell.Leave(quarters = 2, fromStart = true))
            val backups = BackupManager(db, SettingsRepository(app), dutyCodes = { book })
            ScheduleRepository(db).ensureDefaults()
            val json = backups.export()
            book.import(emptyMap())
            assertTrue(book.of("main").isEmpty())
            backups.restore(json)
            assertEquals(DutyCell.Shift(7), book.of("main")["M"])
            assertEquals(DutyCell.Leave(LeaveKind.UNPAID), book.of("main")["병가"])
            assertEquals(DutyCell.Leave(quarters = 2, fromStart = true), book.of("main")["반"])
        } finally {
            db.close()
        }
    }

    @Test fun aSharedRosterIsNotABackup() {
        val photo = Uri.parse("content://media/external/images/1")
        val image = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo)
        assertEquals(IncomingRoster.Shared.Photo(photo), IncomingRoster.sharedOf(image))
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "1 2 3 4 5\n주 주 야 야 휴")
        assertEquals(IncomingRoster.Shared.Text("1 2 3 4 5\n주 주 야 야 휴"), IncomingRoster.sharedOf(text))
        // A backup or 회사 파일 shared as JSON stays the backup's.
        val json = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://files/backup.json"))
        assertNull(IncomingRoster.sharedOf(json))
        assertTrue(IncomingFiles.uriOf(json) != null)
        assertNull(IncomingRoster.sharedOf(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertTrue(IncomingRoster.read(text))
        assertEquals(IncomingRoster.Shared.Text("1 2 3 4 5\n주 주 야 야 휴"), IncomingRoster.take())
        assertNull(IncomingRoster.take())
    }

    @Test fun clipboardTextIsReadOnTheTapOnlyAndNeverKept() = runBlocking {
        val vm = DutyRosterViewModel(c, PersonalCalendar.MAIN)
        val roster = "1 2 3 4 5 6 7 8\n주 주 야 야 휴 휴 주 주"
        // The hint asks whether pasted words fit before handing them over (ClipboardHint reads the clip on the tap).
        assertTrue(vm.fitsClip(roster))
        assertTrue(!vm.fitsClip("점심 뭐 먹지"))
        vm.readText(roster)
        idleUntil("the roster read") { vm.ui.value.rows != null }
        assertEquals(8, vm.ui.value.cells.size)
        // A roster on screen takes no second clip, and the text itself is nowhere in the code book.
        assertTrue(!vm.fitsClip(roster))
        assertTrue(c.dutyCodes.export().values.none { "주 주" in it })
    }
}
