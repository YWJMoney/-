package com.bamnat.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.pay.PayCodec
import com.bamnat.app.pay.SnapshotCodec
import com.bamnat.core.memo.MemoCalendars
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [UNDO-01][UNDO-02][UNDO-03] 최근 지운 기록: whatever a calendar loses — its kind of work changed, its
 * records cleared, the whole calendar removed — can be put back for [KEEP_DAYS] days.
 *
 * Each change leaves a folder in the app's no-backup storage (never in a phone backup, so a restore
 * elsewhere does not bring old trash along) with
 * - `meta.json`: what happened, the calendar's entry as it was (kind of work, shop days, field and
 *   shop money settings; for 내 달력 also its kind of work from the settings), its pay setup and closed
 *   months, and its place in the calendar list;
 * - `data.db`: a copy of the calendar's tables that were cleared (every table for a removed calendar);
 * - `memos.db`: the memos that were moved to the memo trash or off the calendar.
 *
 * Putting back never closes or swaps a database that screens are reading: rows are written into the
 * open database in one transaction, so every list follows at once. A removed calendar comes back with
 * the same id, so its alarms, memos and place in the list find it again.
 */
class RecordsTrash(
    private val dir: File,
    private val calendars: CalendarBook,
    private val mainDb: AppDatabase,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val label: String) {
        STYLE("근무 형태를 바꿨어요"),
        STYLE_CLEARED("근무 형태를 바꾸며 기록을 지웠어요"),
        CLEARED("기록을 지웠어요"),
        DELETED("달력을 지웠어요"),
    }

    data class Entry(val id: String, val kind: Kind, val calendarId: String, val name: String, val at: Long, val detail: String) {
        /** Whole days left before it goes, at least 1 while it is still listed. */
        fun daysLeft(now: Long): Int = (((at + KEEP_MILLIS - now) + DAY_MILLIS - 1) / DAY_MILLIS).toInt().coerceAtLeast(1)
    }

    /** Said right after a change, with 되돌리기. */
    data class Offer(val entry: Entry, val message: String)

    /** [UNDO-01] What clearing would take from a calendar, to say in the question before it. */
    data class Records(
        val days: Int, val order: Boolean, val jobs: Int, val sales: Int, val owed: Int, val memos: Int, val pay: Boolean,
        val trades: Int = 0, val invest: Boolean = false,
    ) {
        val isEmpty: Boolean get() = days == 0 && !order && jobs == 0 && sales == 0 && owed == 0 && memos == 0 && !pay && trades == 0 && !invest

        val text: String
            get() = listOfNotNull(
                "근무 기록 ${days}일".takeIf { days > 0 },
                "근무 순서".takeIf { order },
                "급여 설정".takeIf { pay },
                "일감 ${jobs}건".takeIf { jobs > 0 },
                "매출 ${sales}일".takeIf { sales > 0 },
                "받을 돈 ${owed}건".takeIf { owed > 0 },
                "매매 기록 ${trades}건".takeIf { trades > 0 },
                "입출금·일지·알람".takeIf { invest },
                "이 달력에만 있는 메모 ${memos}개".takeIf { memos > 0 },
            ).joinToString(" · ").ifEmpty { "지울 기록이 아직 없어요" }
    }

    private val lock = Mutex()
    private val _entries = MutableStateFlow(load())
    /** Newest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _offers = MutableSharedFlow<Offer>(extraBufferCapacity = 4)
    val offers: SharedFlow<Offer> = _offers.asSharedFlow()

    // ---- what a calendar has ----

    suspend fun records(calendarId: String): Records = withContext(Dispatchers.IO) {
        val live = liveOf(calendarId) ?: return@withContext Records(0, false, 0, 0, 0, 0, false)
        fun count(table: String) = runCatching { live.query("SELECT COUNT(*) FROM `$table`").use { if (it.moveToFirst()) it.getInt(0) else 0 } }.getOrDefault(0)
        Records(
            days = count("day_entries"),
            order = count("pattern") > 0,
            jobs = count("jobs"),
            sales = count("daily_sales"),
            owed = count("receivables"),
            memos = ownMemoIds(calendarId).size,
            pay = calendars.pay(calendarId).profile.value != null,
            // [INV-01][UNDO-02] An investor calendar's records: its trades, and the rest of its lines.
            trades = count("invest_trades"),
            invest = INVEST_TABLES.any { it != "invest_trades" && it != "invest_quotes" && count(it) > 0 },
        )
    }

    // ---- changes that leave something to put back ----

    /**
     * [UNDO-01] Changes calendar [calendarId]'s kind of work to [style]. With [clear] its records go first
     * (근무 기록·순서, 급여 설정, 일감, 매출, 받을 돈, and the memos only it had); either way the change can be
     * taken back for [KEEP_DAYS] days.
     */
    suspend fun changeStyle(calendarId: String, style: WorkStyle, clear: Boolean, message: String): Entry? = lock.withLock {
        withContext(Dispatchers.IO) {
            val entry = save(if (clear) Kind.STYLE_CLEARED else Kind.STYLE, calendarId, withData = clear, detail = "→ ${style.label}") ?: return@withContext null
            if (clear) clearNow(calendarId)
            if (calendarId == PersonalCalendar.MAIN || calendars.get(calendarId)?.isMain == true) {
                settings.update { it.copy(workStyle = style) }
            } else {
                calendars.setStyle(calendarId, style)
            }
            entry
        }
    }?.also { _offers.tryEmit(Offer(it, message)) }

    /**
     * [UNDO-02] Clears calendar [calendarId]'s records, keeping its kind of work, day kinds and 가계부.
     * Null, and nothing cleared, for a calendar that is not there.
     */
    suspend fun clearRecords(calendarId: String): Entry? = lock.withLock {
        withContext(Dispatchers.IO) {
            val entry = save(Kind.CLEARED, calendarId, withData = true, detail = "") ?: return@withContext null
            clearNow(calendarId)
            entry
        }
    }?.also { _offers.tryEmit(Offer(it, "‘${it.name}’ 기록을 지웠어요")) }

    /**
     * [UNDO-03] Keeps everything of added calendar [calendarId] right before it is removed; call it
     * before its memos are moved and its database goes. Null for the main calendar or one not there.
     */
    suspend fun beforeDelete(calendarId: String): Entry? = lock.withLock {
        withContext(Dispatchers.IO) {
            val cal = calendars.get(calendarId)?.takeIf { !it.isMain } ?: return@withContext null
            save(Kind.DELETED, cal.id, withData = true, detail = "")
        }
    }

    /** [UNDO-03] Said once the calendar is gone. */
    fun deleted(entry: Entry) {
        _offers.tryEmit(Offer(entry, "‘${entry.name}’ 달력을 지웠어요"))
    }

    // ---- putting back ----

    /**
     * Puts entry [id] back and forgets it. False when it is gone (older than [KEEP_DAYS] days) or could
     * not be read; then nothing changed.
     */
    suspend fun restore(id: String): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val folder = File(dir, id)
            val meta = runCatching { JSONObject(File(folder, META).readText()) }.getOrNull() ?: return@withContext false
            val kind = runCatching { Kind.valueOf(meta.getString("kind")) }.getOrNull() ?: return@withContext false
            if (clock() - meta.optLong("at") > KEEP_MILLIS) {
                folder.deleteRecursively()
                _entries.value = load()
                return@withContext false
            }
            val cal = meta.optJSONObject("calendar")?.let(CalendarBook::fromJson) ?: return@withContext false
            val ok = runCatching {
                when (kind) {
                    Kind.STYLE -> putSettings(meta, cal)
                    Kind.STYLE_CLEARED, Kind.CLEARED -> {
                        // The rows first: when they cannot be written, the kind of work stays as it is too.
                        putData(folder, cal.id)
                        putSettings(meta, cal)
                        putPay(meta, cal.id)
                        putMemos(folder)
                    }
                    Kind.DELETED -> {
                        if (calendars.get(cal.id) != null) return@runCatching false
                        val order = meta.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                        // Listed again with the same id and place; its database is made anew, then filled.
                        calendars.restoreEntry(cal, placed(order, CalendarKey.Mine(cal.id).key))
                        putData(folder, cal.id)
                        putPay(meta, cal.id)
                        putMemos(folder)
                    }
                }
                true
            }.getOrDefault(false)
            if (ok) {
                folder.deleteRecursively()
                _entries.value = load()
            }
            ok
        }
    }

    /** Drops what is older than [KEEP_DAYS] days; at each app start. */
    suspend fun purge() = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = clock()
            dir.listFiles()?.forEach { f ->
                val at = runCatching { JSONObject(File(f, META).readText()).getLong("at") }.getOrNull()
                if (at == null || now - at > KEEP_MILLIS) f.deleteRecursively()
            }
            _entries.value = load()
        }
    }

    // ---- inside ----

    /** Null for a calendar no longer there: it has nothing to keep, and never stands in for 내 달력. */
    private fun save(kind: Kind, calendarId: String, withData: Boolean, detail: String): Entry? {
        val cal = calendars.get(calendarId) ?: return null
        val id = "${clock()}-${UUID.randomUUID().toString().take(8)}"
        val folder = File(dir, "$id.part").apply { deleteRecursively(); mkdirs() }
        val s = settings.current
        val pay = calendars.pay(cal.id)
        val meta = JSONObject()
            .put("v", 1)
            .put("kind", kind.name)
            .put("calendarId", cal.id)
            .put("name", cal.name)
            .put("at", clock())
            .put("detail", detail)
            .put("calendar", CalendarBook.run { cal.toJson() })
            .put("order", JSONArray(calendars.order.value))
            .putOpt("mainStyle", s.workStyle?.name)
            .put("rosterMonthly", s.rosterMonthly)
            .putOpt("pay", pay.profile.value?.let(PayCodec::encode))
            .put("closed", JSONArray(pay.closed.value.values.map(SnapshotCodec::encode)))
        if (withData) {
            val live = liveOf(cal.id)
            if (live != null) {
                val tables = if (kind == Kind.DELETED) tablesOf(live) else CLEARED_TABLES.filter { it in tablesOf(live) }
                fileDb(File(folder, DATA)).use { snap -> tables.forEach { t -> dump(SupportDb(live), snap, t) } }
            }
            val memos = if (kind == Kind.DELETED) memoIdsOn(cal.id) else ownMemoIds(cal.id)
            meta.put("memos", JSONArray(memos))
            if (memos.isNotEmpty()) {
                fileDb(File(folder, MEMOS)).use { snap ->
                    dump(SupportDb(mainDb.openHelper.writableDatabase), snap, "memos", "id IN (${memos.joinToString(",")})")
                }
            }
        }
        File(folder, META).writeText(meta.toString())
        val done = File(dir, id)
        folder.renameTo(done)
        _entries.value = load()
        return entryOf(meta, id)!!
    }

    /** The records of calendar [calendarId] go; its day kinds, 가계부 and the memos it shares stay. */
    private suspend fun clearNow(calendarId: String) {
        val live = liveOf(calendarId)
        val roomDb = roomOf(calendarId)
        if (live != null && roomDb != null) {
            val tables = tablesOf(live)
            roomDb.runInTransaction(Runnable { CLEARED_TABLES.reversed().filter { it in tables }.forEach { live.execSQL("DELETE FROM `$it`") } })
        }
        val pay = calendars.pay(calendarId)
        pay.save(null, keepUndo = false)
        pay.replaceClosed(emptyMap())
        val memos = ownMemoIds(calendarId)
        if (memos.isNotEmpty()) mainDb.memoDao().moveToTrash(memos, clock())
    }

    private fun putSettings(meta: JSONObject, cal: PersonalCalendar) {
        if (cal.isMain) {
            val style = meta.optString("mainStyle").takeIf { it.isNotEmpty() }?.let { n -> WorkStyle.entries.firstOrNull { it.name == n } }
            settings.update { it.copy(workStyle = style ?: it.workStyle, rosterMonthly = meta.optBoolean("rosterMonthly", it.rosterMonthly)) }
        }
        calendars.restoreEntry(cal, null)
    }

    private fun putData(folder: File, calendarId: String) {
        val file = File(folder, DATA)
        if (!file.exists()) return
        val live = liveOf(calendarId) ?: return
        val roomDb = roomOf(calendarId) ?: return
        fileDb(file).use { snap ->
            val there = tablesOf(live)
            val tables = tablesOf(snap).filter { it in there }
            roomDb.runInTransaction(Runnable {
                tables.reversed().forEach { live.execSQL("DELETE FROM `$it`") }
                tables.forEach { copyRows(snap, SupportDb(live), it) }
            })
        }
    }

    private suspend fun putPay(meta: JSONObject, calendarId: String) {
        val store = calendars.pay(calendarId)
        store.save(meta.optJSONObject("pay")?.let(PayCodec::decode), keepUndo = false)
        val closed = meta.optJSONArray("closed")
        store.replaceClosed((0 until (closed?.length() ?: 0)).map { SnapshotCodec.decode(closed!!.getJSONObject(it)) }.associateBy { it.month })
    }

    /** Memos come back on the calendars they were on and out of the memo trash; one deleted for good comes back whole. */
    private fun putMemos(folder: File) {
        val file = File(folder, MEMOS)
        if (!file.exists()) return
        val live = mainDb.openHelper.writableDatabase
        fileDb(file).use { snap ->
            mainDb.runInTransaction(Runnable {
                snap.query("SELECT id, calendars, deletedAt FROM memos").use { c ->
                    val missing = ArrayList<Long>()
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val there = live.query("SELECT 1 FROM memos WHERE id = ?", arrayOf<Any?>(id)).use { it.moveToFirst() }
                        if (there) {
                            live.execSQL("UPDATE memos SET calendars = ?, deletedAt = ? WHERE id = ?",
                                arrayOf<Any?>(c.getString(1), if (c.isNull(2)) null else c.getLong(2), id))
                        } else missing += id
                    }
                    if (missing.isNotEmpty()) copyRows(snap, SupportDb(live), "memos", "id IN (${missing.joinToString(",")})")
                }
            })
        }
    }

    /** Memos only calendar [id] has (not shared, not for every calendar), outside the memo trash. */
    private fun ownMemoIds(id: String): List<Long> = memoRows().filter { (_, raw, deleted) ->
        val ids = MemoCalendars.decode(raw)
        !deleted && MemoCalendars.EVERY !in ids && (ids - MemoCalendars.EVERY).ifEmpty { setOf(MemoCalendars.MAIN) } == setOf(id)
    }.map { it.first }

    /** Memos that name calendar [id] at all, in the trash or not: they move when it is removed. */
    private fun memoIdsOn(id: String): List<Long> = memoRows().filter { (_, raw, _) -> id in MemoCalendars.decode(raw) }.map { it.first }

    private fun memoRows(): List<Triple<Long, String?, Boolean>> =
        mainDb.openHelper.writableDatabase.query("SELECT id, calendars, deletedAt FROM memos").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), !c.isNull(2))) }
        }

    private fun roomOf(calendarId: String): AppDatabase? = when {
        calendarId == PersonalCalendar.MAIN -> mainDb
        calendars.get(calendarId)?.takeIf { it.ready } != null -> calendars.database(calendarId)
        else -> null
    }

    private fun liveOf(calendarId: String): SupportSQLiteDatabase? = roomOf(calendarId)?.openHelper?.writableDatabase

    /** [order] with [key] back where it was, the rest as the user has it now. */
    private fun placed(order: List<String>, key: String): List<String>? {
        val now = calendars.order.value.filter { it != key }
        val at = order.indexOf(key).takeIf { it >= 0 } ?: return null
        val before = order.take(at).toSet()
        val index = now.indexOfLast { it in before } + 1
        return now.take(index) + key + now.drop(index)
    }

    private fun load(): List<Entry> {
        val now = clock()
        return dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.endsWith(".part") }.mapNotNull { f ->
            runCatching { entryOf(JSONObject(File(f, META).readText()), f.name) }.getOrNull()
        }.filter { now - it.at <= KEEP_MILLIS }.sortedByDescending { it.at }
    }

    private fun entryOf(o: JSONObject, id: String): Entry? {
        val kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrNull() ?: return null
        return Entry(id, kind, o.optString("calendarId"), o.optString("name"), o.optLong("at"), o.optString("detail"))
    }

    // ---- copying tables ----

    private interface Db : AutoCloseable {
        fun query(sql: String, args: Array<Any?> = emptyArray()): Cursor
        fun exec(sql: String)
        fun insert(table: String, values: ContentValues)
        override fun close() {}
    }

    private class SupportDb(val db: SupportSQLiteDatabase) : Db {
        override fun query(sql: String, args: Array<Any?>): Cursor = db.query(sql, args)
        override fun exec(sql: String) = db.execSQL(sql)
        override fun insert(table: String, values: ContentValues) {
            db.insert(table, SQLiteDatabase.CONFLICT_REPLACE, values)
        }
    }

    private class FileDb(val db: SQLiteDatabase) : Db {
        override fun query(sql: String, args: Array<Any?>): Cursor = db.rawQuery(sql, args.map { it?.toString() }.toTypedArray())
        override fun exec(sql: String) = db.execSQL(sql)
        override fun insert(table: String, values: ContentValues) {
            db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        override fun close() = db.close()
    }

    private fun fileDb(file: File): FileDb = FileDb(SQLiteDatabase.openOrCreateDatabase(file, null))

    private fun tablesOf(db: SupportSQLiteDatabase): Set<String> = tablesOf(SupportDb(db)).toSet()

    private fun tablesOf(db: Db): List<String> = db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
        buildList { while (c.moveToNext()) c.getString(0).takeIf { it !in INTERNAL && !it.startsWith("sqlite_") }?.let(::add) }
    }

    private fun columnsOf(db: Db, table: String): Set<String> = db.query("PRAGMA table_info(`$table`)").use { c ->
        val name = c.getColumnIndex("name")
        buildSet { while (c.moveToNext()) add(c.getString(name)) }
    }

    /** Table [table] of [from] made in [to] with its rows ([where] picks some). */
    private fun dump(from: Db, to: Db, table: String, where: String? = null) {
        val create = from.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return
        to.exec(create.replaceFirst("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ").replace("IF NOT EXISTS IF NOT EXISTS", "IF NOT EXISTS"))
        copyRows(from, to, table, where)
    }

    /** Rows of [table] from [from] into [to], by the columns both have; a column [to] has alone keeps its default. */
    private fun copyRows(from: Db, to: Db, table: String, where: String? = null) {
        val target = columnsOf(to, table)
        from.query("SELECT * FROM `$table`" + (where?.let { " WHERE $it" } ?: "")).use { c ->
            val cols = (0 until c.columnCount).filter { c.getColumnName(it) in target }
            while (c.moveToNext()) {
                val v = ContentValues()
                for (i in cols) {
                    val name = c.getColumnName(i)
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> v.putNull(name)
                        Cursor.FIELD_TYPE_INTEGER -> v.put(name, c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> v.put(name, c.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> v.put(name, c.getBlob(i))
                        else -> v.put(name, c.getString(i))
                    }
                }
                to.insert(table, v)
            }
        }
    }

    companion object {
        const val KEEP_DAYS = 7L
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val KEEP_MILLIS = KEEP_DAYS * DAY_MILLIS
        private const val META = "meta.json"
        private const val DATA = "data.db"
        private const val MEMOS = "memos.db"
        private val INTERNAL = setOf("android_metadata", "room_master_table")
        private val CLEARED_TABLES get() = RECORD_TABLES + INVEST_TABLES

        /**
         * [UNDO-01] A calendar's records, cleared when it starts over: its days, order, changes and extra
         * work, its jobs, sales and money owed. Its day kinds and the 가계부 lines stay.
         */
        val RECORD_TABLES = listOf("pattern", "day_entries", "day_extras", "day_changes", "jobs", "daily_sales", "receivables", "receivable_payments")

        /**
         * [INV-01][UNDO-02] An investor calendar's records: accounts, trades, money in and out, prices,
         * journal, dates, alarms and the daily totals. Its own choices (invest_settings) stay.
         */
        val INVEST_TABLES = listOf(
            "invest_accounts", "invest_trades", "invest_cash", "invest_quotes", "invest_journal",
            "invest_events", "invest_alarms", "invest_snapshots",
        )
    }
}
