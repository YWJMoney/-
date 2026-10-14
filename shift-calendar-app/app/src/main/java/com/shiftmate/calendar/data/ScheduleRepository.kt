package com.shiftmate.calendar.data

import androidx.room.withTransaction
import com.shiftmate.calendar.data.db.AppDatabase
import com.shiftmate.calendar.data.db.decodeSteps
import com.shiftmate.calendar.data.db.encodeSteps
import com.shiftmate.calendar.data.db.toEntity
import com.shiftmate.calendar.data.db.toModel
import com.shiftmate.core.model.AlarmSpec
import com.shiftmate.core.model.DayAlarm
import com.shiftmate.core.model.DayEntry
import com.shiftmate.core.model.ShiftOverride
import com.shiftmate.core.model.ShiftType
import com.shiftmate.core.model.WorkPattern
import com.shiftmate.core.schedule.ScheduleResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime

/** Everything needed to draw the calendar, as one immutable value. */
data class ScheduleSnapshot(
    val shiftTypes: List<ShiftType>,
    val pattern: WorkPattern?,
    val entries: List<DayEntry>,
) {
    val resolver: ScheduleResolver by lazy { ScheduleResolver(shiftTypes, pattern, entries) }
    val typesById: Map<Long, ShiftType> by lazy { shiftTypes.associateBy { it.id } }
    val isEmpty: Boolean get() = pattern == null && entries.isEmpty()
}

class ScheduleRepository(private val db: AppDatabase) {
    private val shiftTypeDao = db.shiftTypeDao()
    private val patternDao = db.patternDao()
    private val dayEntryDao = db.dayEntryDao()

    val shiftTypes: Flow<List<ShiftType>> =
        shiftTypeDao.observeAll().map { list -> list.map { it.toModel() } }.distinctUntilChanged()

    val pattern: Flow<WorkPattern?> =
        patternDao.observe().map { it?.toModel() }.distinctUntilChanged()

    val entries: Flow<List<DayEntry>> =
        dayEntryDao.observeAll().map { list -> list.map { it.toModel() } }.distinctUntilChanged()

    val snapshot: Flow<ScheduleSnapshot> =
        combine(shiftTypes, pattern, entries) { t, p, e -> ScheduleSnapshot(t, p, e) }

    /** Seeds the default shift types on first launch. */
    suspend fun ensureDefaults() {
        db.withTransaction {
            if (shiftTypeDao.count() > 0) return@withTransaction
            shiftTypeDao.insertAll(DefaultShiftTypes.all().map { it.toEntity() })
        }
    }

    suspend fun saveShiftType(type: ShiftType): Long {
        val clean = type.copy(
            name = type.name.trim().ifEmpty { "근무" },
            label = type.label.trim().take(MAX_LABEL_LENGTH).ifEmpty { type.name.trim().take(1).ifEmpty { "근" } },
            breakMinutes = type.breakMinutes.coerceIn(0, 12 * 60),
            alarms = type.alarms
                .map { it.copy(offsetMinutes = it.offsetMinutes.coerceIn(0, 24 * 60)) }
                .distinctBy { it.offsetMinutes }
                .sortedByDescending { it.offsetMinutes }
                .take(MAX_ALARMS),
            start = if (type.isOff) null else type.start,
            end = if (type.isOff) null else type.end,
            alarmEnabled = type.alarmEnabled && !type.isOff,
        )
        return db.withTransaction {
            if (clean.id == 0L) {
                shiftTypeDao.insert(clean.copy(sortOrder = shiftTypeDao.maxSortOrder() + 1).toEntity())
            } else {
                shiftTypeDao.update(clean.toEntity())
                clean.id
            }
        }
    }

    suspend fun moveShiftType(id: Long, delta: Int) {
        db.withTransaction {
            val list = shiftTypeDao.getAll().toMutableList()
            val from = list.indexOfFirst { it.id == id }
            if (from < 0) return@withTransaction
            val to = (from + delta).coerceIn(0, list.lastIndex)
            if (to == from) return@withTransaction
            val item = list.removeAt(from)
            list.add(to, item)
            shiftTypeDao.updateAll(list.mapIndexed { i, e -> e.copy(sortOrder = i) })
        }
    }

    /**
     * Deletes a shift type and every reference to it: pattern steps become plain days off and
     * days forced to this type go back to following the pattern.
     */
    suspend fun deleteShiftType(id: Long) {
        db.withTransaction {
            patternDao.get()?.let { p ->
                val steps = decodeSteps(p.steps).map { if (it == id) null else it }
                patternDao.save(p.copy(steps = encodeSteps(steps)))
            }
            for (e in dayEntryDao.withOverride(id)) {
                val updated = e.toModel().copy(override = ShiftOverride.None)
                if (updated.isEmpty) dayEntryDao.delete(e.epochDay) else dayEntryDao.upsert(updated.toEntity())
            }
            shiftTypeDao.delete(id)
        }
    }

    suspend fun savePattern(pattern: WorkPattern) = patternDao.save(pattern.toEntity())

    suspend fun clearPattern() = patternDao.clear()

    /** Reads, changes and writes one day atomically. Entries that end up empty are removed. */
    suspend fun updateDay(date: LocalDate, transform: (DayEntry) -> DayEntry): DayEntry? =
        db.withTransaction {
            val epochDay = date.toEpochDay()
            val before = dayEntryDao.get(epochDay)?.toModel()
            val changed = transform(before ?: DayEntry(date))
            val after = changed.copy(
                date = date,
                overtimeMinutes = changed.overtimeMinutes.coerceIn(0, MAX_EXTRA_MINUTES),
                holidayWorkMinutes = changed.holidayWorkMinutes.coerceIn(0, MAX_EXTRA_MINUTES),
            )
            if (after.isEmpty) dayEntryDao.delete(epochDay) else dayEntryDao.upsert(after.toEntity())
            before
        }

    /** Puts a day back exactly as it was (undo). */
    suspend fun restoreDay(date: LocalDate, entry: DayEntry?) {
        if (entry == null || entry.isEmpty) dayEntryDao.delete(date.toEpochDay())
        else dayEntryDao.upsert(entry.copy(date = date).toEntity())
    }

    suspend fun setOverride(date: LocalDate, override: ShiftOverride) =
        updateDay(date) { it.copy(override = override) }

    suspend fun setCustomTimes(date: LocalDate, start: LocalTime?, end: LocalTime?, breakMinutes: Int?) =
        updateDay(date) { it.copy(customStart = start, customEnd = end, customBreakMinutes = breakMinutes) }

    suspend fun setOvertime(date: LocalDate, minutes: Int) = updateDay(date) { it.copy(overtimeMinutes = minutes) }

    suspend fun setHolidayWork(date: LocalDate, minutes: Int) =
        updateDay(date) { it.copy(holidayWorkMinutes = minutes) }

    suspend fun setAlarmSkipped(date: LocalDate, skipped: Boolean) =
        updateDay(date) { it.copy(alarmSkipped = skipped) }

    /** Hand-set alarms for one date; null goes back to the shift's own alarms. */
    suspend fun setDayAlarms(date: LocalDate, alarms: List<DayAlarm>?) =
        updateDay(date) { entry ->
            entry.copy(
                customAlarms = alarms?.distinctBy { it.time }?.sortedBy { it.time }?.take(MAX_ALARMS),
                // Choosing alarms for a day means the user wants that day to ring.
                alarmSkipped = if (alarms != null) false else entry.alarmSkipped,
            )
        }

    suspend fun resetDay(date: LocalDate) = updateDay(date) { DayEntry(date) }

    companion object {
        const val MAX_LABEL_LENGTH = 2
        const val MAX_ALARMS = 10
        /** 24 hours: one day cannot hold more extra work than that. */
        const val MAX_EXTRA_MINUTES = 24 * 60
    }
}

object DefaultShiftTypes {
    fun all(): List<ShiftType> = listOf(
        ShiftType(0, "주간", "주", 0xFF2F80ED, LocalTime.of(9, 0), LocalTime.of(18, 0), breakMinutes = 60,
            alarmEnabled = true, alarms = listOf(AlarmSpec(60)), sortOrder = 0),
        ShiftType(0, "오후", "오", 0xFFF2994A, LocalTime.of(14, 0), LocalTime.of(22, 0), breakMinutes = 30,
            alarmEnabled = true, alarms = listOf(AlarmSpec(60)), sortOrder = 1),
        ShiftType(0, "야간", "야", 0xFF6C5CE7, LocalTime.of(22, 0), LocalTime.of(7, 0), breakMinutes = 60,
            alarmEnabled = true, alarms = listOf(AlarmSpec(90)), sortOrder = 2),
        ShiftType(0, "휴무", "휴", 0xFF8E9AAF, null, null, isOff = true, sortOrder = 3),
    )
}
