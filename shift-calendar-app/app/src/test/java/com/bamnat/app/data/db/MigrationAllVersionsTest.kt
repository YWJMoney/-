package com.bamnat.app.data.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.SettingsRepository
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import java.io.File
import java.time.LocalDate

/**
 * [REL-01] An old phone's database of every version, 1 to the one before the newest, carried up to the newest.
 *
 * Each run writes the same user data (shifts, pattern, changed days, extra work, memos, money, jobs, sales,
 * sleep, money owed, investing) into the tables of version [from] exactly as that version's schema JSON
 * has them, keeping only the columns that version had. The same rows also go into a fresh install of the
 * newest version. After the upgrade both must hold the same rows, column by column: what the old version
 * had is kept, and what it did not have gets the newest version's defaults. Room's own check of every
 * table runs on open. Then the upgraded data goes through a backup and back unchanged.
 *
 * The newest version is [AppDatabase.MIGRATIONS]' last step, so a new version (20 from 휴일대체·촉진 기록) is tested
 * from every older one as soon as its migration and schema file are in.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class MigrationAllVersionsTest(private val from: Int) {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyRowArrivesInTheNewestVersion() = runBlocking {
        val oldName = "old-$from.db"
        val freshName = "fresh-$from.db"
        listOf(oldName, freshName, "restored-$from.db").forEach(context::deleteDatabase)

        // The phone as version [from] left it.
        val schema = Schema.read(from)
        val file = context.getDatabasePath(oldName).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            schema.create(sql)
            val written = Fixture.write(schema) { q, args -> sql.execSQL(q, args) }
            assertEquals("version $from holds data in every table", schema.tables.size, written)
            if (!schema.has("day_extras")) Fixture.writeLegacyExtraWork { q, args -> sql.execSQL(q, args) }
            sql.version = from
        }

        val old = Room.databaseBuilder(context, AppDatabase::class.java, oldName).addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        val fresh = Room.databaseBuilder(context, AppDatabase::class.java, freshName).allowMainThreadQueries().build()
        try {
            val freshSql = fresh.openHelper.writableDatabase
            // What a migration works out from older columns is part of that version's data in the newest one.
            Fixture.write(schema, derived = Fixture.DERIVED.filterKeys { from < it }.values.flatten().toSet()) { q, args -> freshSql.execSQL(q, args) }
            // Version 11 and before kept 잔업 on the day itself; it arrives as a piece of extra work.
            if (!schema.has("day_extras")) Fixture.writeLegacyExtraWorkAsPiece { q, args -> freshSql.execSQL(q, args) }

            val upgraded = old.openHelper.writableDatabase
            assertEquals("version", LATEST, upgraded.version)
            val tables = Schema.read(LATEST).tables.keys
            for (table in tables) {
                assertEquals("$table, upgraded from version $from", rows(freshSql, table), rows(upgraded, table))
            }

            // Read through the app's own code, the data means what it meant.
            val types = old.shiftTypeDao().getAll().map { it.toModel() }
            assertEquals(listOf("주간", "야간", "휴무"), types.map { it.name })
            assertEquals(90, types.first().alarms.first().offsetMinutes)
            val days = assembleDays(old.dayEntryDao().getAll(), old.dayExtraDao().getAll()).associateBy { it.date.toEpochDay() }
            assertEquals(listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120)), days.getValue(Fixture.OVERTIME_DAY).extras)
            assertEquals("인수인계", old.memoDao().getAll().first { it.id == 1L }.toModel().title)
            if (schema.has("money_entries")) assertEquals(4_500L, old.moneyDao().allEntries().single().toModel().amount)
            if (schema.has("sleep_logs")) assertEquals(9_000L, old.sleepDao().all().single().endMillis)
            if (schema.has("receivables")) assertEquals(200_000L, old.receivableDao().all().single().toModel(old.receivableDao().allPayments()).left)
            if (schema.has("jobs")) assertEquals(LocalDate.ofEpochDay(20741), old.moneyDao().allJobs().single().toModel().date)

            // A backup made on the upgraded phone restores to the same data.
            // An upgraded phone with a pattern is a shift worker's (AppRoot settles that at its first start).
            val settings = SettingsRepository(context).apply { update { it.copy(workStyle = com.bamnat.core.work.WorkStyle.SHIFT) } }
            val backup = BackupManager(old, settings).export()
            val restored = Room.databaseBuilder(context, AppDatabase::class.java, "restored-$from.db").allowMainThreadQueries().build()
            try {
                val again = BackupManager(restored, settings).run { restore(backup); export() }
                assertEquals("backup round trip from version $from", strip(backup), strip(again))
            } finally {
                restored.close()
            }
        } finally {
            old.close()
            fresh.close()
            listOf(oldName, freshName, "restored-$from.db").forEach(context::deleteDatabase)
        }
    }

    /** Every row of [table] as text, in a fixed order; a piece of extra work's own number is left out (it is given on insert). */
    private fun rows(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("SELECT * FROM `$table`").use { c -> c.rows(skip = if (table == "day_extras") setOf("id") else emptySet()) }.sorted()

    private fun Cursor.rows(skip: Set<String>): List<String> {
        val out = mutableListOf<String>()
        while (moveToNext()) {
            out += columnNames.indices.filter { columnNames[it] !in skip }.joinToString(", ") { i ->
                val v = when (getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> "NULL"
                    Cursor.FIELD_TYPE_INTEGER -> getLong(i).toString()
                    Cursor.FIELD_TYPE_FLOAT -> getDouble(i).toString()
                    else -> "'${getString(i)}'"
                }
                "${columnNames[i]}=$v"
            }
        }
        return out
    }

    private fun strip(json: String) = JSONObject(json).apply { remove("exportedAt") }.toString(2)

    /** The tables of one schema version, as Room wrote them in database/schemas. */
    private class Schema(val version: Int, private val json: JSONObject) {
        /** Table name → its columns in that version. */
        val tables: Map<String, Set<String>> = buildMap {
            val entities = json.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val fields = e.getJSONArray("fields")
                put(e.getString("tableName"), (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }.toSet())
            }
        }

        fun has(table: String) = table in tables

        fun create(sql: SQLiteDatabase) {
            val entities = json.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = json.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
        }

        companion object {
            fun read(version: Int) = Schema(version, JSONObject(File(SCHEMA_DIR, "$version.json").readText()).getJSONObject("database"))
        }
    }

    /**
     * One user's data in the newest version's column names. A version gets the tables and columns it had;
     * the values are chosen so every older version's rows mean the same after the upgrade (version 1's single
     * alarm 90 minutes before becomes "90:1", as its migration writes it).
     */
    private object Fixture {
        const val OVERTIME_DAY = 20003L

        val ROWS: List<Pair<String, Map<String, Any?>>> = listOf(
            "shift_types" to mapOf("id" to 1, "name" to "주간", "label" to "주", "colorArgb" to 4280391411L, "startMinute" to 540, "endMinute" to 1080,
                "breakMinutes" to 60, "isOff" to 0, "alarmEnabled" to 1, "alarmOffsetMinutes" to 90, "alarmSoundUri" to null, "sortOrder" to 0,
                "alarmOffsets" to "90:1", "icon" to "sun", "breakStartMinute" to 720, "premiumPercent" to 0, "breakPaid" to null),
            "shift_types" to mapOf("id" to 2, "name" to "야간", "label" to "야", "colorArgb" to 4282664004L, "startMinute" to 1320, "endMinute" to 420,
                "breakMinutes" to 60, "isOff" to 0, "alarmEnabled" to 0, "alarmOffsetMinutes" to 60, "alarmSoundUri" to "content://media/alarm/7",
                "sortOrder" to 1, "alarmOffsets" to "60:1", "icon" to null, "breakStartMinute" to 120, "premiumPercent" to 50, "breakPaid" to 1),
            "shift_types" to mapOf("id" to 3, "name" to "휴무", "label" to "휴", "colorArgb" to 4288585374L, "startMinute" to null, "endMinute" to null,
                "breakMinutes" to 0, "isOff" to 1, "alarmEnabled" to 0, "alarmOffsetMinutes" to 60, "alarmSoundUri" to null, "sortOrder" to 2,
                "alarmOffsets" to "60:1", "icon" to null, "breakStartMinute" to null, "premiumPercent" to 0),
            "pattern" to mapOf("id" to 1, "kind" to "ROTATION", "startEpochDay" to 20000, "endEpochDay" to null, "steps" to "1,1,2,2,,",
                "anchorEpochDay" to 20004),
            // A day changed to 야간 with its alarm off, 출근 recorded and a swap.
            "day_entries" to mapOf("epochDay" to 20001, "overrideMode" to 2, "overrideShiftTypeId" to 2, "customStartMinute" to null,
                "customEndMinute" to null, "customBreakMinutes" to null, "overtimeMinutes" to 0, "holidayWorkMinutes" to 0, "alarmSkipped" to 1,
                "customAlarms" to null, "extraWork" to 0, "leave" to 0, "secondShiftTypeId" to null, "attendance" to 2, "swapWith" to "민지"),
            // A day with its own hours and 연차.
            "day_entries" to mapOf("epochDay" to 20002, "overrideMode" to 0, "overrideShiftTypeId" to null, "customStartMinute" to 600,
                "customEndMinute" to 1140, "customBreakMinutes" to 60, "overtimeMinutes" to 0, "holidayWorkMinutes" to 0, "alarmSkipped" to 0,
                "customAlarms" to null, "extraWork" to 0, "leave" to 1, "secondShiftTypeId" to null, "attendance" to 0, "swapWith" to ""),
            "day_extras" to mapOf("epochDay" to 20001, "kindId" to 3, "startMinute" to 540, "endMinute" to 1080, "breakMinutes" to 60,
                "breakStartMinute" to 720, "minutes" to 0, "coveredShiftTypeId" to 1, "needsReview" to 0, "position" to 0,
                "substituteEpochDay" to 20004),
            "day_extras" to mapOf("epochDay" to OVERTIME_DAY, "kindId" to 1, "startMinute" to null, "endMinute" to null, "breakMinutes" to 0,
                "breakStartMinute" to null, "minutes" to 120, "coveredShiftTypeId" to null, "needsReview" to 0, "position" to 1),
            "memos" to mapOf("id" to 1, "epochDay" to 20001, "title" to "인수인계", "body" to "본문", "checklist" to "", "isChecklist" to 0,
                "colorIndex" to 2, "pinned" to 1, "createdAt" to 1, "updatedAt" to 2, "deletedAt" to null, "remindAt" to "2026-11-03T15:00",
                "remindRing" to 1, "remindNotify" to 0, "remindAsked" to "2026-11-03T15:00", "calendars" to ",*,", "repeat" to "M25"),
            "memos" to mapOf("id" to 2, "epochDay" to null, "title" to "지운 메모", "body" to "", "checklist" to "", "isChecklist" to 0,
                "colorIndex" to 0, "pinned" to 0, "createdAt" to 3, "updatedAt" to 4, "deletedAt" to 5, "remindAt" to null,
                "remindRing" to 0, "remindNotify" to 0, "remindAsked" to null, "calendars" to "", "repeat" to ""),
            "money_entries" to mapOf("id" to 1, "epochDay" to 20745, "kind" to "EXPENSE", "amount" to 4500, "category" to "카페", "note" to "",
                "source" to "NOTIFICATION", "createdAt" to 1, "merchant" to "가나다카페", "minuteOfDay" to 485, "cardLast4" to "1234", "reviewed" to 0,
                "accountId" to 1, "toAccountId" to null, "memo" to "민지랑", "tags" to "여행", "parts" to "", "installments" to 1, "fixedId" to null,
                "proof" to "CARD", "receipt" to ""),
            "jobs" to mapOf("id" to 1, "epochDay" to 20741, "title" to "타일", "client" to "가나다", "place" to "현장", "startMinute" to 480,
                "endMinute" to 1020, "amount" to 300000, "deposit" to 50000, "depositPaid" to 1, "balancePaid" to 0, "remindMinutes" to 60,
                "note" to "", "endEpochDay" to 20742, "costs" to 20000, "withholding" to 1, "status" to 2, "units" to 1.5, "dueEpochDay" to 20760),
            "daily_sales" to mapOf("epochDay" to 20742, "sales" to 500000, "costs" to 30000, "note" to "", "card" to 400000, "cash" to 100000,
                "transfer" to 0, "delivery" to 0, "cashCounted" to 98000, "cardSettled" to 1),
            "fixed_costs" to mapOf("id" to 1, "name" to "월세", "amount" to 500000, "day" to 25, "kind" to "EXPENSE", "category" to "주거",
                "remind" to 1, "repeat" to "MONTH", "monthOfYear" to 1, "accountId" to null, "auto" to 1, "startEpochDay" to 20000, "endEpochDay" to null),
            "category_rules" to mapOf("merchant" to "가나다카페", "category" to "카페"),
            "day_changes" to mapOf("id" to 1, "epochDay" to 20001, "atMillis" to 5, "text" to "야간으로 바꿈"),
            "sleep_logs" to mapOf("id" to 1, "startMillis" to 1000, "endMillis" to 9000),
            "receivables" to mapOf("id" to 1, "who" to "가나다화학", "title" to "잔금", "amount" to 300000, "epochDay" to 20760, "dueEpochDay" to 20790,
                "kind" to "OTHER", "note" to "", "countAsIncome" to 1, "closedEpochDay" to null, "createdAt" to 1),
            "receivable_payments" to mapOf("id" to 1, "receivableId" to 1, "epochDay" to 20767, "amount" to 100000, "entryId" to null),
            "imported_events" to mapOf("eventKey" to "7@2026-11-03T19:00", "epochDay" to 20760, "memoId" to 1, "importedAt" to 1),
            "money_accounts" to mapOf("id" to 1, "name" to "신한 Deep", "type" to "CREDIT", "last4" to "1234", "payDay" to 14, "closeDay" to null,
                "payFromId" to null, "opening" to 0, "color" to 0, "sortOrder" to 0, "hidden" to 0, "remind" to 1),
            "money_budgets" to mapOf("category" to "식비", "amount" to 300000),
            "money_categories" to mapOf("kind" to "EXPENSE", "name" to "운동", "position" to 0, "hidden" to 0),
            "invest_accounts" to mapOf("id" to 1, "name" to "일반 계좌", "type" to "OTHER", "broker" to "", "feeRate" to 0.00015, "sortOrder" to 0, "hidden" to 0),
            "invest_trades" to mapOf("id" to 1, "accountId" to 1, "epochDay" to 20745, "minuteOfDay" to 600, "symbol" to "005930", "name" to "삼성전자",
                "market" to "KR_STOCK", "side" to "BUY", "quantity" to 1.0, "price" to 70000.0, "currency" to "KRW", "fxRate" to 1.0, "fee" to 10.0,
                "tax" to 0.0, "reason" to "", "emotions" to "", "tags" to "", "followedRules" to 1, "memo" to "", "alarmId" to null, "createdAt" to 1),
            "invest_cash" to mapOf("id" to 1, "accountId" to 1, "epochDay" to 20744, "minuteOfDay" to null, "kind" to "DEPOSIT", "amount" to 1000000,
                "tax" to 0, "symbol" to "", "memo" to "", "createdAt" to 1),
            "invest_quotes" to mapOf("symbol" to "005930", "name" to "삼성전자", "market" to "KR_STOCK", "price" to 71000.0, "epochDay" to 20745),
            "invest_journal" to mapOf("epochDay" to 20745, "market" to "", "review" to "원칙대로", "checked" to "", "grade" to null, "updatedAt" to 1),
            "invest_events" to mapOf("id" to 1, "epochDay" to 20760, "kind" to "OTHER", "symbol" to "", "title" to "배당", "memo" to "", "remind" to 1),
            "invest_alarms" to mapOf("id" to 1, "kind" to "CUSTOM", "label" to "장 시작", "minuteOfDay" to 540, "weekdays" to "MONDAY",
                "monthDay" to null, "onceEpochDay" to null, "skipHolidays" to 1, "symbol" to "", "name" to "", "market" to "KR_STOCK",
                "accountId" to null, "side" to "BUY", "quantity" to null, "amount" to null, "targetPrice" to null, "enabled" to 1, "ring" to 0, "createdAt" to 1),
            "invest_snapshots" to mapOf("epochDay" to 20745, "value" to 1000000, "principal" to 1000000),
            "invest_settings" to mapOf("key" to "fee", "value" to "0.015"),
            // [LEAVE-01] Version 19's 연차 원장: no older version had it, so it arrives empty and takes this row on a fresh install.
            "leave_ledger" to mapOf("id" to 1, "epochDay" to 20454, "kind" to "CARRY", "days" to 2.5, "expiresEpochDay" to 20818,
                "yearStartEpochDay" to null, "note" to "이월", "pool" to "ANNUAL", "createdAt" to 1),
            // [LEAVE-11] Version 20's 연차 사용 촉진 기록, likewise new.
            "leave_notices" to mapOf("id" to 1, "lapseEpochDay" to 20818, "step" to "FIRST_NOTICE", "epochDay" to 20636, "note" to "메일", "createdAt" to 1),
        )

        /**
         * Columns a migration fills from older ones, by the version that first had them: version 1's single alarm
         * becomes the list of alarms (MIGRATION_1_2).
         */
        val DERIVED: Map<Int, List<String>> = mapOf(2 to listOf("shift_types.alarmOffsets"))

        /**
         * Writes the rows [schema] has tables for, with the columns it had (and the [derived] "table.column"s a
         * migration fills from them); returns how many tables got rows.
         */
        fun write(schema: Schema, derived: Set<String> = emptySet(), exec: (String, Array<Any?>) -> Unit): Int {
            val newest = Schema.read(LATEST).tables
            val missing = ROWS.map { it.first }.toSet().let { have -> newest.keys.filter { it !in have } }
            assertEquals("the fixture has a row for every table of the newest version", emptyList<String>(), missing)
            val filled = mutableSetOf<String>()
            for ((table, row) in ROWS) {
                val columns = schema.tables[table] ?: continue
                val unknown = row.keys - newest.getValue(table)
                assertEquals("fixture columns of $table", emptySet<String>(), unknown)
                val kept = row.filterKeys { it in columns || "$table.$it" in derived }
                exec("INSERT INTO `$table` (${kept.keys.joinToString { "`$it`" }}) VALUES (${kept.keys.joinToString { "?" }})", kept.values.toTypedArray())
                filled += table
            }
            return filled.size
        }

        /** Version 11 and before: 잔업 2시간 kept on the day row itself, nothing else on that day. */
        fun writeLegacyExtraWork(exec: (String, Array<Any?>) -> Unit) = exec(
            "INSERT INTO day_entries (epochDay, overrideMode, overtimeMinutes, holidayWorkMinutes, alarmSkipped) VALUES (?, 0, 120, 0, 0)",
            arrayOf(OVERTIME_DAY),
        )

        /** What [writeLegacyExtraWork]'s day is in the newest version: a 잔업 piece of 120 minutes, the day row gone. */
        fun writeLegacyExtraWorkAsPiece(exec: (String, Array<Any?>) -> Unit) {
            val row = ROWS.first { it.first == "day_extras" && it.second["epochDay"] == OVERTIME_DAY }.second
            exec("INSERT INTO day_extras (${row.keys.joinToString { "`$it`" }}) VALUES (${row.keys.joinToString { "?" }})", row.values.toTypedArray())
        }
    }

    companion object {
        val SCHEMA_DIR = File("../database/schemas/com.bamnat.app.data.db.AppDatabase")
        val LATEST: Int get() = AppDatabase.MIGRATIONS.last().endVersion

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "version {0}")
        fun versions(): List<Array<Any>> = (1 until LATEST).map { arrayOf(it) }
    }
}
