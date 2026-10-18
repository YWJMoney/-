package com.bamnat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.core.model.ShiftOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** [SET-01] A file put in by mistake can be taken back for a week, and the restore says what it replaces. */
@RunWith(AndroidJUnit4::class)
class M10SettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val copy = File(context.noBackupFilesDir, "m10_before_import.json")

    @After fun close() {
        db.close()
        copy.delete()
    }

    @Test fun aFileLoadedByMistakeCanBeTakenBackForAWeek() = runBlocking {
        val schedule = ScheduleRepository(db)
        schedule.ensureDefaults()
        val backups = BackupManager(db, SettingsRepository(context))
        val night = schedule.snapshot.first().shiftTypes.first { it.name == "야간" }
        val day = LocalDate.of(2026, 11, 3)
        // Someone else's backup: a night on the 3rd.
        schedule.setOverride(day, ShiftOverride.Shift(night.id))
        val theirs = backups.export()
        schedule.resetDay(day)
        val mine = schedule.snapshot.first().entries.filter { it.date == day && it.override != null }
        assertTrue(mine.isEmpty())

        val start = 1_800_000_000_000L
        var now = start
        val undo = ImportUndo(copy, backups, clock = { now })
        assertNull(undo.savedAt.value)
        undo.save()
        backups.restore(theirs)
        assertEquals(start, undo.savedAt.value)
        assertNotNull(schedule.snapshot.first().entries.single { it.date == day }.override)

        // Taken back: the night is gone, and so is the copy, so a second tap cannot undo twice.
        undo.undo()
        assertNull(schedule.snapshot.first().entries.firstOrNull { it.date == day }?.override)
        assertNull(undo.savedAt.value)
        assertFalse(copy.exists())
        try {
            undo.undo()
            fail("undid twice")
        } catch (e: BackupException) {
            assertEquals("되돌릴 데이터가 없어요", e.message)
        }

        // Still there six days later, after an app restart; gone after a week.
        undo.save()
        now = start + 6 * DAY
        assertEquals(start, ImportUndo(copy, backups, clock = { now }).savedAt.value)
        now = start + ImportUndo.KEEP_DAYS * DAY + 1
        assertNull(ImportUndo(copy, backups, clock = { now }).savedAt.value)
        assertFalse(copy.exists())
    }

    @Test fun beforeARestoreItSaysWhatItReplaces() = runBlocking {
        val schedule = ScheduleRepository(db)
        schedule.ensureDefaults()
        var now = 1_800_000_000_000L
        val sleep = SleepRepository(db, clock = { now })
        sleep.fellAsleep()
        now += 7 * 60 * 60 * 1000L
        sleep.wokeUp()
        val backups = BackupManager(db, SettingsRepository(context))
        val summary = backups.summarize(backups.export())
        assertEquals(1, summary.sleeps)
        assertFalse(summary.hasPay)
        assertTrue(summary.shiftTypes > 0)
        // A backup without work types is turned away in words, and nothing is replaced.
        val broken = JSONObject(backups.export()).put("shiftTypes", JSONArray()).toString()
        try {
            backups.restore(broken)
            fail("restored a backup without work types")
        } catch (e: BackupException) {
            assertEquals("근무 종류가 없는 백업 파일이에요", e.message)
        }
        assertEquals(1, db.sleepDao().all().size)
    }

    /** [SET-01][OPS-13] The privacy policy says how long the copy before an import stays, in the app's number. */
    @Test fun thePrivacyPolicySaysHowLongTheCopyStays() {
        val policy = File("../../privacy/index.html")
        assumeTrue(policy.exists())
        val text = policy.readText()
        // Later versions keep the 1.7 change note; the version itself is checked in ShareServerTest.
        assertTrue(text.contains("1.7에서 바뀐 점"))
        assertTrue(text.contains("불러오기 직전의 데이터를 휴대폰 안에 ${ImportUndo.KEEP_DAYS}일 동안"))
        assertTrue(text.contains("불러오기 직전 데이터의 사본은 백업에서 뺍니다"))
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
