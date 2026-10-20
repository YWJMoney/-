package com.bamnat.app.imports

import android.content.Context
import com.bamnat.app.data.MemoRepository
import com.bamnat.app.data.ScheduleRepository
import com.bamnat.app.data.db.ImportedEventDao
import com.bamnat.app.data.db.ImportedEventEntity
import com.bamnat.core.imports.CalendarImport
import com.bamnat.core.imports.DeviceCalendar
import com.bamnat.core.memo.Memo
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * [IMPORT-01] Brings events from the phone's calendars into 밤낮: shifts onto their days, the rest
 * as memos. Which calendars to read is remembered on this phone only (not in the backup: calendar
 * ids differ on a new phone). What one import changed can be put back with one tap.
 */
class CalendarImporter(
    context: Context,
    private val source: CalendarSource,
    private val schedule: ScheduleRepository,
    private val memos: MemoRepository,
    private val imported: ImportedEventDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.applicationContext.getSharedPreferences("calendar_import", Context.MODE_PRIVATE)

    /** The calendars picked last time; null before the first import (then all but holidays and birthdays). */
    var chosen: Set<Long>?
        get() = prefs.getStringSet(KEY_CHOSEN, null)?.mapNotNull { it.toLongOrNull() }?.toSet()
        set(value) { prefs.edit().apply { if (value == null) remove(KEY_CHOSEN) else putStringSet(KEY_CHOSEN, value.map { it.toString() }.toSet()) }.apply() }

    /** Look for new events each time the app opens, and offer them. */
    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO, value).apply() }

    fun allowed(): Boolean = source.allowed()

    fun calendars(): List<DeviceCalendar> = source.calendars()

    fun defaultChoice(calendars: List<DeviceCalendar>): Set<Long> = calendars.filterNot { it.generated }.map { it.id }.toSet()

    /** What the calendars hold from [from] to [to], each as it would come in. */
    suspend fun propose(calendars: List<DeviceCalendar>, ids: Set<Long>, from: LocalDate, to: LocalDate): List<CalendarImport.Proposal> =
        withContext(Dispatchers.IO) {
            val events = source.events(ids, from, to)
            val snapshot = schedule.snapshot.first()
            CalendarImport.propose(
                events, snapshot.shiftTypes, imported.keys().toSet(),
                current = { d -> snapshot.resolver.resolve(d).shiftType?.id },
                generatedCalendars = calendars.filter { it.generated }.map { it.id }.toSet(),
            )
        }

    /** What [apply] changed, to put back. */
    data class Done(val days: Map<LocalDate, DayEntry?>, val memoIds: List<Long>, val keys: List<String>) {
        val shifts: Int get() = days.size
        val memos: Int get() = memoIds.size
    }

    suspend fun apply(proposals: List<CalendarImport.Proposal>): Done = withContext(Dispatchers.IO) {
        val days = LinkedHashMap<LocalDate, DayEntry?>()
        val memoIds = ArrayList<Long>()
        val keys = ArrayList<String>()
        for (p in proposals) {
            var memoId: Long? = null
            when (p.kind) {
                CalendarImport.As.SHIFT, CalendarImport.As.OFF -> {
                    val override = p.shiftTypeId?.let { ShiftOverride.Shift(it) } ?: ShiftOverride.Off
                    val before = schedule.setOverride(p.date, override)
                    if (p.date !in days) days[p.date] = before
                }
                CalendarImport.As.MEMO -> {
                    val text = CalendarImport.memoText(p.event)
                    val title = text.substringBefore('\n')
                    val body = text.substringAfter('\n', "")
                    memoId = memos.save(Memo(date = p.date, title = title, body = body, calendars = setOf(com.bamnat.app.data.PersonalCalendar.MAIN))).id // [MEMO-01] onto 내 달력, as its days
                    memoIds += memoId
                }
            }
            imported.upsert(ImportedEventEntity(p.event.key, p.date.toEpochDay(), memoId, clock()))
            keys += p.event.key
        }
        Done(days, memoIds, keys)
    }

    /** Puts the days and memos back as they were before [done], and forgets that those events came in. */
    suspend fun undo(done: Done) = withContext(Dispatchers.IO) {
        done.days.forEach { (date, entry) -> schedule.restoreDay(date, entry) }
        done.memoIds.forEach { id -> memos.get(id)?.let { memos.delete(it, offerUndo = false) } }
        imported.delete(done.keys)
    }

    /** [IMPORT-02] New events since the last look, for the offer when the app opens; 0 when not set up. */
    suspend fun countNew(today: LocalDate): Int = withContext(Dispatchers.IO) {
        val ids = chosen ?: return@withContext 0
        if (!autoCheck || !source.allowed()) return@withContext 0
        val calendars = source.calendars()
        CalendarImport.count(propose(calendars, ids, today.minusDays(LOOK_BACK_DAYS), today.plusDays(LOOK_AHEAD_DAYS))).total
    }

    companion object {
        private const val KEY_CHOSEN = "chosen"
        private const val KEY_AUTO = "auto"
        /** The window the automatic look covers. */
        const val LOOK_BACK_DAYS = 7L
        const val LOOK_AHEAD_DAYS = 60L
    }
}
