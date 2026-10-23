package com.bamnat.app.ui.calendar

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.ui.UndoMessage
import com.bamnat.core.alarm.AlarmSuggest
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * [ALRM-03] 스마트 알람 제안 in the app: five mornings of turning 주간's alarm off half an hour late bring the card,
 * one tap moves that shift's alarm only, undo puts it back, and the card then rests. The switch is in the backup.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AlarmSuggestFlowTest {
    private val c get() = ApplicationProvider.getApplicationContext<BamnatApp>().container
    private val repo get() = c.scheduleRepository
    private val today = LocalDate.now()
    private var day = 0L
    private var night = 0L

    @Before fun seed() = runBlocking {
        c.telemetry.log.clear()
        day = repo.saveShiftType(ShiftType(0, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(16, 0), alarmEnabled = true, alarms = listOf(AlarmSpec(60), AlarmSpec(30))))
        night = repo.saveShiftType(ShiftType(0, "야간", "야", 0xFF3949AB, LocalTime.of(19, 0), LocalTime.of(7, 0), alarmEnabled = true, alarms = listOf(AlarmSpec(90))))
        for (n in 1L..5L) {
            val date = today.minusDays(n)
            repo.setOverride(date, ShiftOverride.Shift(day))
            val due = date.atTime(6, 0)
            c.telemetry.log.append(DiagEvents.ALARM_DISMISSED, AlarmSuggest.offDetail(day, millis(due), 60), millis(due.plusMinutes(30)))
        }
    }

    private fun millis(t: java.time.LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun find(vm: CalendarViewModel) =
        vm.findAlarmSuggestion(repo.snapshot.first(), c.settingsRepository.current, emptyList(), today)

    private suspend fun alarmsOf(id: Long) = repo.shiftTypes.first().first { it.id == id }.alarms

    @Test fun lateMorningsBringOneCardThatMovesOnlyThatShift() = runBlocking {
        val vm = CalendarViewModel(c)
        val s = find(vm)
        assertNotNull(s)
        assertEquals(AlarmSuggest.Kind.LATE, s!!.kind)
        assertEquals(day, s.type.id)
        assertTrue(s.text, s.text.startsWith("주간 알람을 보통 30분 뒤에 꺼요"))

        val said = async(start = CoroutineStart.UNDISPATCHED) { vm.messages.first() }
        vm.applyAlarmSuggestion(s)
        val msg: UndoMessage = withTimeout(5_000) { said.await() }
        assertTrue("ten seconds to take it back", msg.long)
        withTimeout(5_000) { repo.shiftTypes.first { list -> list.first { it.id == day }.alarms.map { it.offsetMinutes } == listOf(80, 50) } }
        assertEquals(listOf(90), alarmsOf(night).map { it.offsetMinutes })
        // Answered: no card for a week, whatever the log says.
        assertNull(find(vm))
        assertEquals(today.toEpochDay(), AlarmSuggest.Memory.decode(c.settingsRepository.current.alarmSuggestMemory).answeredDay)

        msg.undo!!()
        assertEquals(listOf(60, 30), alarmsOf(day).map { it.offsetMinutes })
        // The log keeps only the kind and the answer.
        val logged = c.telemetry.log.read().filter { it.name == DiagEvents.ALARM_SUGGEST }.map { it.detail }
        assertEquals(listOf("late applied", "late undone"), logged)
    }

    @Test fun neverAgainStaysAwayUntilReset() = runBlocking {
        val vm = CalendarViewModel(c)
        val s = find(vm)!!
        vm.declineAlarmSuggestion(s, forGood = true)
        val memory = AlarmSuggest.Memory.decode(c.settingsRepository.current.alarmSuggestMemory)
        assertTrue(s.key in memory.never)
        assertNull(AlarmSuggest.find(today.plusDays(60), repo.shiftTypes.first(), listOf(today.minusDays(1) to s.type), emptyList(), emptyList(),
            420, false, memory))
        assertEquals(listOf(60, 30), alarmsOf(day).map { it.offsetMinutes })
    }

    @Test fun theSwitchIsInTheBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val settings = c.settingsRepository
            val backups = BackupManager(db, settings)
            com.bamnat.app.data.ScheduleRepository(db).ensureDefaults()
            assertTrue("on by default", settings.current.alarmSuggest)
            settings.update { it.copy(alarmSuggest = false) }
            val json = backups.export()
            settings.update { it.copy(alarmSuggest = true) }
            backups.restore(json)
            assertFalse(settings.current.alarmSuggest)
        } finally {
            db.close()
        }
    }
}
