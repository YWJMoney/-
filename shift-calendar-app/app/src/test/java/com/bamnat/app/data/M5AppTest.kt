package com.bamnat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.sleep.SleepReminder
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.sleep.SleepPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** [SLP-04][SLP-06][SLP-07] The sleep log, its place in the backup, and which tips notify. */
@RunWith(AndroidJUnit4::class)
class M5AppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val zone = ZoneId.of("Asia/Seoul")
    private fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    @After fun close() = db.close()

    @Test fun twoTapsMakeOneSleepAndTheBackupKeepsIt() = runBlocking {
        var now = millis(LocalDateTime.of(2026, 11, 3, 23, 30))
        val sleep = SleepRepository(db, clock = { now }, zone = { zone })
        assertNull(sleep.wokeUp())
        sleep.fellAsleep()
        now = millis(LocalDateTime.of(2026, 11, 4, 6, 45))
        sleep.wokeUp()
        val log = sleep.recent(days = 3650).first()
        assertEquals(435L, log.single().minutes)
        // [SLP-14] A second "잠들었어요" keeps the sleep already open, and when it began, rather than piling up or starting over.
        now = millis(LocalDateTime.of(2026, 11, 4, 23, 0))
        assertTrue(sleep.fellAsleep().started)
        now = millis(LocalDateTime.of(2026, 11, 4, 23, 20))
        val again = sleep.fellAsleep()
        assertFalse(again.started)
        assertEquals(LocalDateTime.of(2026, 11, 4, 23, 0), again.entry.start)
        assertEquals(1, sleep.recent(days = 3650).first().count { it.end == null })
        // Woke at a time given later; undoing "깼어요" opens the same sleep again.
        now = millis(LocalDateTime.of(2026, 11, 5, 9, 0))
        val woke = sleep.wokeUp(millis(LocalDateTime.of(2026, 11, 5, 6, 30)))!!
        assertEquals(450L, woke.minutes)
        sleep.reopen(woke.id)
        assertEquals(LocalDateTime.of(2026, 11, 4, 23, 0), sleep.recent(days = 3650).first().single { it.end == null }.start)
        // Left open past 16 hours, the next "잠들었어요" closes it where it stands and starts anew.
        now = millis(LocalDateTime.of(2026, 11, 5, 17, 0))
        assertTrue(sleep.fellAsleep().started)
        assertEquals(1, sleep.recent(days = 3650).first().count { it.end == null })

        val settings = SettingsRepository(context)
        val backups = BackupManager(db, settings)
        ScheduleRepository(db).ensureDefaults()
        val json = backups.export()
        db.sleepDao().clear()
        backups.restore(json)
        assertEquals(3, db.sleepDao().all().size)
    }

    @Test fun remindersFollowTheScheduleAndStayOffUntilTurnedOn() = runBlocking {
        val schedule = ScheduleRepository(db)
        schedule.ensureDefaults()
        val night = schedule.snapshot.first().shiftTypes.first { it.name == "야간" }
        val day = LocalDate.of(2026, 11, 3)
        schedule.setOverride(day, ShiftOverride.Shift(night.id))
        val snapshot = schedule.snapshot.first()
        val now = day.atTime(12, 0)
        val tips = SleepReminder.upcoming(snapshot, AppSettings(sleepReminders = true), now)
        // 야간 22:00–07:00: nap, last coffee, drowsy reminder at 07:00, sleep from 08:00.
        assertEquals(SleepPlan.Kind.NAP, tips.first().kind)
        assertTrue(tips.any { it.kind == SleepPlan.Kind.DROWSY && it.at == day.plusDays(1).atTime(7, 0) })
        assertTrue(tips.all { it.at.isAfter(now) })
        assertEquals(tips.sortedBy { it.at }, tips)
    }
}
