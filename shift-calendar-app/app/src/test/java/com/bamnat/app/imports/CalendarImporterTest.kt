package com.bamnat.app.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.MemoRepository
import com.bamnat.app.data.ScheduleRepository
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.core.imports.CalendarImport
import com.bamnat.core.imports.DeviceCalendar
import com.bamnat.core.imports.DeviceEvent
import com.bamnat.core.model.ShiftOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/** [IMPORT-01] Events from Samsung Calendar land as shifts and memos once, and one tap puts them back. */
@RunWith(AndroidJUnit4::class)
class CalendarImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val schedule = ScheduleRepository(db)
    private val memos = MemoRepository(db)
    private val calendar = DeviceCalendar(1, "내 캘린더", "My calendar", "LOCAL")
    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0)

    private val source = object : CalendarSource {
        override fun allowed() = true
        override fun calendars() = listOf(calendar)
        override fun events(calendarIds: Set<Long>, from: LocalDate, toInclusive: LocalDate) = listOf(
            DeviceEvent(10, 1, "야간", at(3, 0), at(4, 0), allDay = true),
            DeviceEvent(11, 1, "치과", at(5, 15), at(5, 16), location = "가나다치과"),
        )
    }
    private val importer = CalendarImporter(context, source, schedule, memos, db.importedEventDao())

    @After fun close() = db.close()

    @Test fun eventsComeInOnceAndCanBeTakenBack() = runBlocking {
        schedule.ensureDefaults()
        val night = schedule.snapshot.first().shiftTypes.first { it.name == "야간" }
        val from = LocalDate.of(2026, 11, 1)
        val to = LocalDate.of(2026, 11, 30)
        val proposals = importer.propose(listOf(calendar), setOf(1), from, to)
        assertEquals(listOf(CalendarImport.As.SHIFT, CalendarImport.As.MEMO), proposals.map { it.kind })
        val done = importer.apply(proposals.filter { it.skip == null })
        assertEquals(1, done.shifts)
        assertEquals(1, done.memos)
        assertEquals(ShiftOverride.Shift(night.id), schedule.snapshot.first().entries.single { it.date == LocalDate.of(2026, 11, 3) }.override)
        val memo = memos.memos.first().single()
        assertEquals("치과", memo.title)
        assertEquals("15:00–16:00 · 가나다치과", memo.body)
        assertEquals(LocalDate.of(2026, 11, 5), memo.date)
        // A second look finds nothing new.
        assertTrue(importer.propose(listOf(calendar), setOf(1), from, to).all { it.skip != null })
        importer.undo(done)
        assertTrue(schedule.snapshot.first().entries.none { it.date == LocalDate.of(2026, 11, 3) && it.override != ShiftOverride.None })
        assertTrue(memos.memos.first().none { !it.inTrash })
        assertTrue(importer.propose(listOf(calendar), setOf(1), from, to).all { it.skip == null })
    }
}
