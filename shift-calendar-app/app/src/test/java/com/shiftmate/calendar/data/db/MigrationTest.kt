package com.shiftmate.calendar.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shiftmate.core.model.AlarmSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val types = db.shiftTypeDao().getAll().map { it.toModel() }
            assertEquals(listOf("주간"), types.map { it.name })
            // The old single alarm (90 minutes before) carries over as the first of the list.
            assertEquals(listOf(AlarmSpec(90)), types.single().alarms)
            assertEquals(true, types.single().alarmEnabled)

            val day = db.dayEntryDao().getAll().single().toModel()
            assertEquals(120, day.overtimeMinutes)
            assertNull(day.customAlarms)

            val memo = db.memoDao().getAll().single().toModel()
            assertEquals("인수인계", memo.title)
            assertNull(memo.deletedAtMillis)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
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
        sql.execSQL(
            "INSERT INTO memos (id, epochDay, title, body, checklist, isChecklist, colorIndex, pinned, createdAt, updatedAt) " +
                "VALUES (1, 20000, '인수인계', '본문', '', 0, 0, 0, 1, 2)",
        )
        sql.version = 1
        sql.close()
    }

    private companion object {
        val SCHEMA_DIR = File("schemas/com.shiftmate.calendar.data.db.AppDatabase")
    }
}
