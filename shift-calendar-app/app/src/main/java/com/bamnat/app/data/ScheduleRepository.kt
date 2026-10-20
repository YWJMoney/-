package com.bamnat.app.data

import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import com.bamnat.app.data.db.DayChangeEntity
import com.bamnat.core.model.DayChanges
import androidx.room.withTransaction
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.assembleDays
import com.bamnat.app.data.db.decodeSteps
import com.bamnat.app.data.db.encodeSteps
import com.bamnat.app.data.db.toEntity
import com.bamnat.app.data.db.toModel
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.DayAlarm
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.model.Leave
import com.bamnat.core.model.ShiftIcon
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.model.WorkTime
import com.bamnat.core.schedule.ExtraReview
import com.bamnat.core.schedule.PatternTimeline
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.setup.SetupDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime

/** Everything needed to draw the calendar, as one immutable value. */
data class ScheduleSnapshot(
    val shiftTypes: List<ShiftType>,
    /** Pattern periods, earliest first; more than one after a team change. */
    val patterns: List<WorkPattern>,
    val entries: List<DayEntry>,
    /** [EXTRA-01] The user's kinds of extra work: where each piece sits on the clock and its name. */
    val kinds: ExtraKinds = ExtraKinds(),
    /** [HRLY-05] 법정 휴게 자동 빼기 from the calendar's pay setup, so hours match pay. */
    val legalBreaks: Boolean = false,
) {
    constructor(shiftTypes: List<ShiftType>, pattern: WorkPattern?, entries: List<DayEntry>, kinds: ExtraKinds = ExtraKinds()) :
        this(shiftTypes, listOfNotNull(pattern), entries, kinds)

    val resolver: ScheduleResolver by lazy { ScheduleResolver(shiftTypes, patterns, entries, kinds, legalBreaks) }
    val typesById: Map<Long, ShiftType> by lazy { shiftTypes.associateBy { it.id } }
    val isEmpty: Boolean get() = patterns.isEmpty() && entries.isEmpty()
    val hasPattern: Boolean get() = patterns.isNotEmpty()
}

class ScheduleRepository(
    private val db: AppDatabase,
    /** [EXTRA-01] The user's kinds of extra work, from settings. */
    private val kinds: Flow<ExtraKinds> = flowOf(ExtraKinds()),
    /** [PAYX-09] The user's names for each kind, used in the change log. */
    private val extraName: (Long) -> String = { ExtraKinds().name(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val changeDao = db.dayChangeDao()

    /** [MKT-08] What changed on [date] and when, newest first. */
    fun changesOn(date: LocalDate): Flow<List<DayChange>> =
        changeDao.observe(date.toEpochDay()).map { list -> list.map { DayChange(it.atMillis, it.text) } }

    private suspend fun logChange(date: LocalDate, before: DayEntry?, after: DayEntry) {
        val names = shiftTypeDao.getAll().associate { it.id to it.name }
        val text = DayChanges.describe(before, after, { names[it] ?: "근무" }, extraName) ?: return
        val now = clock()
        // Taps on a stepper (잔업 30분 → 1시간 → 1시간 30분) read as one change, not a list of steps.
        val last = changeDao.latest(date.toEpochDay())
        if (last != null && now - last.atMillis in 0 until COALESCE_MILLIS && last.text.substringBefore(' ') == text.substringBefore(' ')) {
            changeDao.delete(last.id)
        }
        changeDao.insert(DayChangeEntity(epochDay = date.toEpochDay(), atMillis = now, text = text))
        changeDao.purge(now - CHANGE_KEEP_MILLIS)
    }

    private val shiftTypeDao = db.shiftTypeDao()
    private val patternDao = db.patternDao()
    private val dayEntryDao = db.dayEntryDao()
    private val extraDao = db.dayExtraDao()

    val shiftTypes: Flow<List<ShiftType>> =
        shiftTypeDao.observeAll().map { list -> list.map { it.toModel() } }.distinctUntilChanged().flowOn(Dispatchers.Default)

    val patterns: Flow<List<WorkPattern>> =
        patternDao.observeAll().map { list -> list.mapNotNull { it.toModel() } }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** The period being worked now (or the latest one), shown in the pattern editor. */
    val pattern: Flow<WorkPattern?> = patterns.map { PatternTimeline.current(it, LocalDate.now()) }

    val entries: Flow<List<DayEntry>> =
        combine(dayEntryDao.observeAll(), extraDao.observeAll()) { rows, extras -> assembleDays(rows, extras) }.distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /**
     * [STAB-01] Turning rows into days and comparing whole lists runs off the main thread: with years of
     * days it was long enough, on every edit and for every screen watching, to make taps stop answering.
     */
    val snapshot: Flow<ScheduleSnapshot> =
        combine(shiftTypes, patterns, entries, kinds.distinctUntilChanged()) { t, p, e, k -> ScheduleSnapshot(t, p, e, k) }
            .flowOn(Dispatchers.Default)

    /** [EXTRA-05] Pieces of extra work moved from an older record that still need a look. */
    val extrasToReview: Flow<Int> = extraDao.observeNeedsReview().distinctUntilChanged()

    /** Seeds the default shift types on first launch. */
    suspend fun ensureDefaults() {
        db.withTransaction {
            if (shiftTypeDao.count() > 0) return@withTransaction
            shiftTypeDao.insertAll(DefaultShiftTypes.all().map { it.toEntity() })
        }
    }

    /**
     * [WORK-01] Hourly workers get part-time shifts to tap instead of 주간·야간: morning, afternoon
     * and evening. Added once; types the user already has are left alone.
     */
    suspend fun addHourlyTypes() {
        db.withTransaction {
            val have = shiftTypeDao.getAll().map { it.toModel() }
            val names = have.map { it.name }.toSet()
            val next = (have.maxOfOrNull { it.sortOrder } ?: -1) + 1
            val add = DefaultShiftTypes.hourly().filter { it.name !in names }
                .mapIndexed { i, t -> t.copy(sortOrder = next + i) }
            if (add.isNotEmpty()) shiftTypeDao.insertAll(add.map { it.toEntity() })
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
            for (p in patternDao.getAll()) {
                val steps = decodeSteps(p.steps).map { if (it == id) null else it }
                patternDao.save(p.copy(steps = encodeSteps(steps)))
            }
            for (e in dayEntryDao.withOverride(id)) {
                val updated = e.toModel().copy(override = ShiftOverride.None)
                if (updated.isEmpty) dayEntryDao.delete(e.epochDay) else dayEntryDao.upsert(updated.toEntity())
            }
            // Work that covered this shift keeps its hours; only the link to the type goes.
            val type = shiftTypeDao.getAll().firstOrNull { it.id == id }
            val covering = extraDao.covering(id)
            if (covering.isNotEmpty()) extraDao.updateAll(covering.map { x ->
                if (x.startMinute != null && x.endMinute != null || type == null || type.isOff) x.copy(coveredShiftTypeId = null)
                else x.copy(startMinute = type.startMinute, endMinute = type.endMinute, breakMinutes = type.breakMinutes,
                    breakStartMinute = type.breakStartMinute, coveredShiftTypeId = null)
            })
            shiftTypeDao.delete(id)
        }
    }

    /**
     * [SETUP-01] The first setup, all in one transaction: the draft's shift types (a type the phone
     * already has by that name is updated, not doubled), the shifts it seeded at first launch that the
     * draft does not use and nothing points at removed, then its pattern from two years back (from today
     * when a pattern was made meanwhile) or its painted days. Returns the draft with the saved ids.
     */
    suspend fun applySetup(draft: SetupDraft, today: LocalDate = LocalDate.now()): SetupDraft = db.withTransaction {
        val existing = shiftTypeDao.getAll().map { it.toModel() }
        val ids = LinkedHashMap<Long, Long>()
        for (t in draft.typesToSave().sortedBy { it.sortOrder }) {
            val mine = existing.firstOrNull { it.name == t.name.trim() }
            val icon = t.icon ?: mine?.icon ?: ShiftIcon.suggest(t)?.key
            ids[t.id] = saveShiftType(t.copy(id = mine?.id ?: 0, icon = icon, alarmSoundUri = mine?.alarmSoundUri))
        }
        // Field and shop work bring no shifts and keep the first ones, to mark a shift now and then.
        val seeded = if (ids.isEmpty()) emptySet() else DefaultShiftTypes.all().map { it.name }.toSet()
        for (t in existing) {
            if (t.id in ids.values || t.name !in seeded || isReferenced(t.id)) continue
            shiftTypeDao.delete(t.id)
        }
        // The draft's shifts first, in its order; any the user made meanwhile after them.
        val rank = ids.values.withIndex().associate { (i, id) -> id to i }
        val all = shiftTypeDao.getAll()
        shiftTypeDao.updateAll(all.sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.sortOrder })).mapIndexed { i, e -> e.copy(sortOrder = i) })
        val saved = draft.remap(ids)
        saved.pattern(today)?.let { pattern ->
            val periods = patternDao.getAll().mapNotNull { it.toModel() }
            when {
                periods.isEmpty() -> setFirstPattern(pattern, today)
                // A save cut short and finished on the next start: the order is already there.
                PatternTimeline.on(periods, today)?.withRange(pattern.startDate, pattern.endDate) == pattern -> Unit
                else -> changePatternFrom(pattern, today)
            }
        }
        // Painted days are the roster as given, not changes to it: no change log.
        for (day in saved.paintedEntries()) {
            val before = readDay(day.date.toEpochDay())
            writeDay((before ?: DayEntry(day.date)).copy(override = day.override))
        }
        saved
    }

    /** Whether a pattern, a day or a piece of extra work still points at shift type [id]. */
    private suspend fun isReferenced(id: Long): Boolean =
        patternDao.getAll().any { id in decodeSteps(it.steps) } || dayEntryDao.withOverride(id).isNotEmpty() || extraDao.covering(id).isNotEmpty()

    /** Replaces the whole history with [pattern] (a test or restore uses this as is). */
    suspend fun savePattern(pattern: WorkPattern) = replacePatterns(listOf(pattern))

    /** The first pattern: it also fills the last two years so past days are not empty. */
    suspend fun setFirstPattern(pattern: WorkPattern, today: LocalDate = LocalDate.now()) =
        replacePatterns(listOf(PatternTimeline.first(pattern, today)))

    /**
     * A team change: [pattern] applies from [from] on, and every day before keeps the pattern it
     * had. Changes made on single days (extra work, swaps, memos) are untouched either way.
     */
    suspend fun changePatternFrom(pattern: WorkPattern, from: LocalDate) {
        db.withTransaction {
            val existing = patternDao.getAll().mapNotNull { it.toModel() }
            replacePatterns(PatternTimeline.changeFrom(existing, pattern, from))
        }
    }

    /** Rewrites the period being edited in place (fixing a typo, not a team change). */
    suspend fun replaceCurrentPattern(pattern: WorkPattern, today: LocalDate = LocalDate.now()) {
        db.withTransaction {
            val existing = patternDao.getAll().mapNotNull { it.toModel() }
            val current = PatternTimeline.current(existing, today)
            if (current == null) {
                replacePatterns(listOf(PatternTimeline.first(pattern, today)))
            } else {
                // Keeps the period's dates; only what it holds changes.
                val isLast = current == existing.last()
                val end = if (isLast) pattern.endDate else current.endDate
                replacePatterns(existing.map { if (it == current) pattern.withRange(current.startDate, end) else it })
            }
        }
    }

    private suspend fun replacePatterns(patterns: List<WorkPattern>) {
        db.withTransaction {
            patternDao.clear()
            patternDao.saveAll(patterns.sortedBy { it.startDate }.mapIndexed { i, p -> p.toEntity(id = i + 1) })
        }
    }

    suspend fun clearPattern() = patternDao.clear()

    /** One day as stored, with its extra work; null when the day follows the pattern untouched. */
    private suspend fun readDay(epochDay: Long): DayEntry? {
        val row = dayEntryDao.get(epochDay)
        val extras = extraDao.onDay(epochDay).map { it.toModel() }
        return row?.toModel(extras) ?: extras.takeIf { it.isNotEmpty() }?.let { DayEntry(LocalDate.ofEpochDay(epochDay), extras = it) }
    }

    /** Writes [entry] for its date: the row when anything besides extra work changed, and the extra work in order. */
    private suspend fun writeDay(entry: DayEntry) {
        val epochDay = entry.date.toEpochDay()
        if (entry.copy(extras = emptyList()).isEmpty) dayEntryDao.delete(epochDay) else dayEntryDao.upsert(entry.toEntity())
        extraDao.deleteDay(epochDay)
        if (entry.extras.isNotEmpty()) extraDao.insertAll(entry.extras.mapIndexed { i, e -> e.toEntity(epochDay, i) })
    }

    /** Reads, changes and writes one day atomically. Entries that end up empty are removed. */
    suspend fun updateDay(date: LocalDate, transform: (DayEntry) -> DayEntry): DayEntry? =
        db.withTransaction {
            val before = readDay(date.toEpochDay())
            val changed = transform(before ?: DayEntry(date))
            val after = changed.copy(date = date, extras = changed.extras.take(MAX_EXTRAS).map(::clean))
            writeDay(after)
            logChange(date, before, after)
            before
        }

    /** Puts a day back exactly as it was (undo). */
    suspend fun restoreDay(date: LocalDate, entry: DayEntry?) {
        db.withTransaction { writeDay(entry?.copy(date = date) ?: DayEntry(date)) }
    }

    /** [CAL-01] Several days changed in one write, e.g. quick input dragged over them; returns each as it was. */
    suspend fun updateDays(dates: List<LocalDate>, transform: (DayEntry) -> DayEntry): Map<LocalDate, DayEntry?> =
        db.withTransaction { dates.associateWith { updateDay(it, transform) } }

    /** A piece of extra work within what one day can hold. */
    private fun clean(e: ExtraEntry): ExtraEntry {
        val timed = e.hasTimes
        return e.copy(
            start = e.start.takeIf { timed }, end = e.end.takeIf { timed },
            breakMinutes = if (timed) e.breakMinutes.coerceIn(0, (WorkTime.spanMinutes(e.start!!, e.end!!) - 1).coerceAtLeast(0)) else 0,
            breakStart = e.breakStart.takeIf { timed && e.breakMinutes > 0 },
            minutes = if (timed) 0 else e.minutes.coerceIn(0, MAX_EXTRA_MINUTES),
        )
    }

    suspend fun setOverride(date: LocalDate, override: ShiftOverride) =
        updateDay(date) { it.copy(override = override) }

    suspend fun setCustomTimes(date: LocalDate, start: LocalTime?, end: LocalTime?, breakMinutes: Int?) =
        updateDay(date) { it.copy(customStart = start, customEnd = end, customBreakMinutes = breakMinutes) }

    /** [EXTRA-01] Adds a piece of extra work at the end of the day's list. */
    suspend fun addExtra(date: LocalDate, entry: ExtraEntry) = updateDay(date) { it.copy(extras = it.extras + entry) }

    /** [EXTRA-01] Changes the piece at [index]; a piece that changed is no longer one to check. */
    suspend fun updateExtra(date: LocalDate, index: Int, entry: ExtraEntry) = updateDay(date) { d ->
        if (index !in d.extras.indices) d else d.copy(extras = d.extras.mapIndexed { i, e -> if (i == index) entry.copy(needsReview = false) else e })
    }

    suspend fun removeExtra(date: LocalDate, index: Int) = updateDay(date) { d ->
        d.copy(extras = d.extras.filterIndexed { i, _ -> i != index })
    }

    /** [EXTRA-05] The day's old pieces are as the user meant them. */
    suspend fun markExtrasReviewed(date: LocalDate) = extraDao.markReviewed(date.toEpochDay())

    suspend fun markAllExtrasReviewed() = extraDao.markAllReviewed()

    /**
     * [EXTRA-05] Turns each day off that was changed into a shift for a 대근 back into a day off with
     * a 대근 of that shift's hours. Returns how many days changed.
     */
    suspend fun convertShiftSwapCovers(dates: Collection<LocalDate>): Int = db.withTransaction {
        val types = shiftTypeDao.getAll().map { it.toModel() }.associateBy { it.id }
        var n = 0
        for (date in dates) {
            val before = readDay(date.toEpochDay()) ?: continue
            val type = (before.override as? ShiftOverride.Shift)?.let { types[it.shiftTypeId] } ?: continue
            val after = ExtraReview.convert(before, type)
            writeDay(after)
            logChange(date, before, after)
            n++
        }
        n
    }

    /** 연차·반차·반반차 on [date]; null removes it [FEAT-10]. */
    suspend fun setLeave(date: LocalDate, leave: Leave?) = updateDay(date) { it.copy(leave = leave) }

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
        /** 24 hours: one piece of extra work cannot be longer than that. */
        const val MAX_EXTRA_MINUTES = 24 * 60
        /** Pieces of extra work one day can hold. */
        const val MAX_EXTRAS = 6
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

    /** [WORK-01] Part-time blocks; under 4 hours of work needs no break, 4 hours or more gets 30 minutes. */
    fun hourly(): List<ShiftType> = listOf(
        ShiftType(0, "오전 알바", "전", 0xFF00A88F, LocalTime.of(9, 0), LocalTime.of(13, 0), breakMinutes = 0,
            alarmEnabled = true, alarms = listOf(AlarmSpec(60)), sortOrder = 0),
        ShiftType(0, "오후 알바", "후", 0xFFE0A100, LocalTime.of(13, 0), LocalTime.of(18, 0), breakMinutes = 30,
            alarmEnabled = true, alarms = listOf(AlarmSpec(60)), sortOrder = 1),
        ShiftType(0, "저녁 알바", "저", 0xFFD6457A, LocalTime.of(18, 0), LocalTime.of(23, 0), breakMinutes = 30,
            alarmEnabled = true, alarms = listOf(AlarmSpec(60)), sortOrder = 2),
    )
}

/** [MKT-08] A change to a day as the log keeps it. */
data class DayChange(val atMillis: Long, val text: String)

/** The change log keeps a year. */
private const val COALESCE_MILLIS = 60_000L
const val CHANGE_KEEP_MILLIS = 366L * 24 * 60 * 60 * 1000
