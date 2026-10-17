package com.bamnat.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalTime

/**
 * Builds a database exactly as version 1 of the app wrote it, then opens it with the current
 * version. Room checks every table against the entities on open, so a migration that leaves a
 * column or index different from a fresh install fails here instead of on users' phones.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun version1DataSurvivesTheUpgrade() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        createVersion1(context.getDatabasePath(name).apply { parentFile?.mkdirs() })

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12)
            .allowMainThreadQueries()
            .build()
        try {
            val types = db.shiftTypeDao().getAll().map { it.toModel() }
            assertEquals(listOf("주간"), types.map { it.name })
            // The old single alarm (90 minutes before) carries over as the first of the list.
            assertEquals(listOf(AlarmSpec(90)), types.single().alarms)
            assertEquals(true, types.single().alarmEnabled)
            assertNull(types.single().icon)

            // Version 12: the day's 2 hours of 잔업 are a piece of extra work; the day held nothing else.
            val day = assembleDays(db.dayEntryDao().getAll(), db.dayExtraDao().getAll()).single()
            assertEquals(listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120)), day.extras)
            assertTrue(db.dayEntryDao().getAll().isEmpty())
            assertNull(day.customAlarms)

            val pattern = db.patternDao().getAll().single().toModel() as WorkPattern.Rotation
            assertEquals(listOf(1L, 1L, null, null), pattern.steps)
            assertEquals(pattern.startDate, pattern.anchorDate)
            assertNull(day.leave)

            val memo = db.memoDao().getAll().single().toModel()
            assertEquals("인수인계", memo.title)
            assertNull(memo.deletedAtMillis)

            // Version 7 tables start empty and take rows [WORK-06].
            val money = db.moneyDao()
            assertEquals(0, money.allEntries().size)
            val id = money.insertJob(com.bamnat.core.money.Job(date = java.time.LocalDate.of(2026, 11, 3), title = "욕실", amount = 900_000).toEntity())
            assertEquals("욕실", money.job(id)?.toModel()?.title)
            // Version 9: a line read from a payment text keeps its place and time [LEDG-02].
            val line = com.bamnat.core.money.MoneyEntry(date = java.time.LocalDate.of(2026, 11, 3), kind = com.bamnat.core.money.MoneyKind.EXPENSE,
                amount = 4_500, category = "카페", merchant = "스타벅스", time = java.time.LocalTime.of(8, 5), reviewed = false)
            money.insertEntry(line.toEntity())
            val read = money.allEntries().single().toModel()
            assertEquals("스타벅스", read.merchant)
            assertEquals(java.time.LocalTime.of(8, 5), read.time)
            assertEquals(false, read.reviewed)
            money.insertFixed(com.bamnat.core.money.FixedCost(name = "월세", amount = 500_000, day = 25).toEntity())
            assertEquals("월세", money.allFixed().single().toModel().name)
            // Version 10: each day keeps a log of what was changed [MKT-08].
            val changes = db.dayChangeDao()
            changes.insert(DayChangeEntity(epochDay = 20000, atMillis = 5, text = "잔업 2시간"))
            assertEquals(listOf("잔업 2시간"), changes.observe(20000).first().map { it.text })
            // Version 11: the sleep log [SLP-06].
            val sleep = db.sleepDao()
            val sleepId = sleep.insert(SleepLogEntity(startMillis = 1_000, endMillis = null))
            sleep.finish(sleepId, 9_000)
            assertEquals(9_000L, sleep.all().single().endMillis)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /** [EXTRA-05] T17: every kind of version 11 extra work lands as pieces, and nothing is counted twice. */
    @Test
    fun version11ExtraWorkBecomesPieces() = runBlocking {
        val name = "migration-11.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val sql = createTables(file, 11)
        // 주간 09–18, 야간 22–07 (휴게 1시간 02:00부터), 휴무.
        sql.execSQL("INSERT INTO shift_types (id, name, label, colorArgb, startMinute, endMinute, breakMinutes, isOff, alarmEnabled, alarmOffsetMinutes, sortOrder, alarmOffsets, breakStartMinute) VALUES " +
            "(1, '주간', '주', 0, 540, 1080, 60, 0, 0, 60, 0, '', 720), (2, '야간', '야', 0, 1320, 420, 60, 0, 0, 60, 1, '', 120), (3, '휴무', '휴', 0, NULL, NULL, 0, 1, 0, 60, 2, '', NULL)")
        fun day(epochDay: Int, values: String) = sql.execSQL(
            "INSERT INTO day_entries (epochDay, overrideMode, overrideShiftTypeId, customStartMinute, customEndMinute, customBreakMinutes, " +
                "overtimeMinutes, holidayWorkMinutes, alarmSkipped, customAlarms, extraWork, leave, secondShiftTypeId) VALUES ($epochDay, $values)",
        )
        day(20000, "0, NULL, NULL, NULL, NULL, 120, 0, 0, NULL, 1, 0, NULL")   // 잔업 2시간
        day(20001, "0, NULL, NULL, NULL, NULL, 0, 480, 0, NULL, 2, 0, NULL")   // 쉬는 날 특근 8시간
        day(20002, "0, NULL, NULL, NULL, NULL, 120, 0, 0, NULL, 3, 0, 2")      // 대근 칩 2시간 + 두 번째 근무 야간
        day(20003, "2, 2, NULL, NULL, NULL, 0, 0, 0, NULL, 3, 0, NULL")        // 근무를 야간으로 바꾸고 대근 표시만
        day(20004, "0, NULL, NULL, NULL, NULL, 60, 0, 1, NULL, 0, 0, NULL")    // 알람 끈 날의 1시간
        day(20005, "1, NULL, NULL, NULL, NULL, 0, 0, 0, NULL, 0, 0, NULL")     // 쉬는 날로 바꾼 날
        sql.version = 11
        sql.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_11_12).allowMainThreadQueries().build()
        try {
            val days = assembleDays(db.dayEntryDao().getAll(), db.dayExtraDao().getAll()).associateBy { it.date.toEpochDay() }
            assertEquals(listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120)), days.getValue(20000).extras)
            assertEquals(listOf(ExtraEntry(ExtraKind.SPECIAL_ID, minutes = 480)), days.getValue(20001).extras)
            // The second shift keeps its own hours; the chip's 2 hours next to it are asked about.
            assertEquals(
                listOf(
                    ExtraEntry(ExtraKind.COVER_ID, LocalTime.of(22, 0), LocalTime.of(7, 0), 60, LocalTime.of(2, 0), coveredShiftId = 2),
                    ExtraEntry(ExtraKind.COVER_ID, minutes = 120, needsReview = true),
                ),
                days.getValue(20002).extras,
            )
            // A mark with no hours stays visible and is asked about; the shift change stays.
            assertEquals(ShiftOverride.Shift(2), days.getValue(20003).override)
            assertEquals(listOf(ExtraEntry(ExtraKind.COVER_ID, needsReview = true)), days.getValue(20003).extras)
            assertTrue(days.getValue(20004).alarmSkipped)
            assertEquals(listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 60)), days.getValue(20004).extras)
            assertEquals(ShiftOverride.Off, days.getValue(20005).override)
            assertTrue(days.getValue(20005).extras.isEmpty())
            assertEquals(2, db.dayExtraDao().observeNeedsReview().first())
            // The old columns are empty from now on, and days that held only extra work have no row.
            db.dayEntryDao().getAll().forEach { e ->
                assertEquals(0, e.overtimeMinutes + e.holidayWorkMinutes + e.extraWork)
                assertNull(e.secondShiftTypeId)
            }
            assertEquals(setOf(20003L, 20004L, 20005L), db.dayEntryDao().getAll().map { it.epochDay }.toSet())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    /** The tables of schema [version] exactly as Room wrote them, empty. */
    private fun createTables(file: File, version: Int): SQLiteDatabase {
        val schema = JSONObject(File(SCHEMA_DIR, "$version.json").readText()).getJSONObject("database")
        val sql = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
        return sql
    }

    private fun createVersion1(file: File) {
        val schema = JSONObject(File(SCHEMA_DIR, "1.json").readText()).getJSONObject("database")
        val sql = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
        sql.execSQL(
            "INSERT INTO shift_types (id, name, label, colorArgb, startMinute, endMinute, breakMinutes, isOff, " +
                "alarmEnabled, alarmOffsetMinutes, alarmSoundUri, sortOrder) " +
                "VALUES (1, '주간', '주', 4280391411, 480, 1200, 60, 0, 1, 90, NULL, 0)",
        )
        sql.execSQL(
            "INSERT INTO day_entries (epochDay, overrideMode, overrideShiftTypeId, customStartMinute, customEndMinute, " +
                "customBreakMinutes, overtimeMinutes, holidayWorkMinutes, alarmSkipped) " +
                "VALUES (20000, 0, NULL, NULL, NULL, NULL, 120, 0, 0)",
        )
        sql.execSQL("INSERT INTO pattern (id, kind, startEpochDay, endEpochDay, steps) VALUES (1, 'ROTATION', 20000, NULL, '1,1,,')")
        sql.execSQL(
            "INSERT INTO memos (id, epochDay, title, body, checklist, isChecklist, colorIndex, pinned, createdAt, updatedAt) " +
                "VALUES (1, 20000, '인수인계', '본문', '', 0, 0, 0, 1, 2)",
        )
        sql.version = 1
        sql.close()
    }

    private companion object {
        val SCHEMA_DIR = File("schemas/com.bamnat.app.data.db.AppDatabase")
    }
}
