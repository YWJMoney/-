package com.bamnat.core.setup

import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.BundleShift
import com.bamnat.core.schedule.ShiftRole
import com.bamnat.core.work.WorkStyle
import java.time.DayOfWeek
import java.time.LocalDate

/** [SETUP-01] The steps of the first setup, in order. Only the ones that apply are shown. */
enum class SetupStep { STYLE, COMPANY, BUNDLE, TIMES, ORDER, TODAY, ALARM, SUMMARY }

/**
 * [SETUP-01][COMM-05] A setup that came from elsewhere: a built-in template, the company server or a
 * coworker's file. [text] is the company settings file as it came; [team] and [anchor] are the
 * sender's 조 and step 0 date when the file says them.
 */
data class SetupSource(
    val kind: Kind,
    val text: String,
    /** The template's id, or the company's on the server; null for a file. */
    val id: String? = null,
    /** The server's id of the setup chosen at that company. */
    val setupId: String? = null,
    val name: String = "",
    val dept: String = "",
    val version: Int? = null,
    val adopted: Boolean = false,
    val team: String? = null,
    val anchor: LocalDate? = null,
) {
    enum class Kind { TEMPLATE, SERVER, FILE }
}

/**
 * [SETUP-01] Everything the first setup has been told so far. Nothing is written to the calendar
 * until the last step saves it all at once; until then the draft is kept on the phone so the user
 * can stop and go on later. Shift types here have temporary negative ids.
 */
data class SetupDraft(
    val style: WorkStyle? = null,
    /** [SETUP-02] Shift work with a new roster every month: the days are painted, not repeated. */
    val rosterMonthly: Boolean = false,
    val step: SetupStep = SetupStep.STYLE,
    /** The bundle chosen, a [com.bamnat.core.schedule.ShiftBundles] key or [CUSTOM]. */
    val bundle: String? = null,
    /** [COMM-05] The company typed when none was found, to invite the user to share their setup later. */
    val companyName: String = "",
    val types: List<ShiftType> = emptyList(),
    val roles: Map<Long, ShiftRole> = emptyMap(),
    /** "네, 맞아요" on the times: required before going on. */
    val timesConfirmed: Boolean = false,
    val rotation: List<Long?> = emptyList(),
    /** Monday..Sunday for weekly work; null is a day off. */
    val weekly: List<Long?> = List(7) { null },
    /** [SETUP-02] Hourly work on days that change: no weekly pattern at all. */
    val noFixedDays: Boolean = false,
    /** [SETUP-02] Painted roster days: a type id, or null for a plain day off. */
    val painted: Map<LocalDate, Long?> = emptyMap(),
    /** The rotation's step 0 date, from the answer to "오늘 무슨 근무예요?". */
    val anchor: LocalDate? = null,
    /** "회사 근무표와 같아요": required before going on. */
    val todayConfirmed: Boolean = false,
    /** Minutes before the start every work shift rings; null rings none. */
    val alarmMinutes: Int? = DEFAULT_ALARM_MINUTES,
    /** "근무마다 다르게": minutes for one shift, null for none; overrides [alarmMinutes]. */
    val alarmByType: Map<Long, Int?> = emptyMap(),
    val source: SetupSource? = null,
    /** The user chose "나중에": the app opens on the calendar with a card to go on. */
    val later: Boolean = false,
    /** The save has started; on the next start it is finished, never asked again. */
    val committing: Boolean = false,
) {
    val workTypes: List<ShiftType> get() = types.filter { !it.isOff && it.hasTimes }

    fun type(id: Long?): ShiftType? = id?.let { i -> types.firstOrNull { it.id == i } }

    fun isOff(id: Long?): Boolean = id == null || type(id)?.isOff != false

    /** The minutes before [type] starts that it rings, or null for no alarm. */
    fun alarmFor(type: ShiftType): Int? = if (type.id in alarmByType) alarmByType[type.id] else alarmMinutes

    /** A rotation that the steps say, with today on the answered step. */
    val usesRotation: Boolean get() = style == WorkStyle.SHIFT && !rosterMonthly

    /** The steps this draft goes through. */
    val steps: List<SetupStep>
        get() = when (style) {
            null, WorkStyle.FIELD, WorkStyle.BUSINESS -> listOf(SetupStep.STYLE)
            WorkStyle.SHIFT -> listOfNotNull(
                SetupStep.STYLE, SetupStep.COMPANY, SetupStep.BUNDLE.takeIf { source == null }, SetupStep.TIMES,
                // A shared order is asked too, already chosen: a template's is only the usual one for that work.
                SetupStep.ORDER, SetupStep.TODAY.takeIf { usesRotation }, SetupStep.ALARM, SetupStep.SUMMARY,
            )
            // [COMM-05] Weekday and part-time work look for the company too, or start from a template.
            WorkStyle.DAY, WorkStyle.HOURLY ->
                listOf(SetupStep.STYLE, SetupStep.COMPANY, SetupStep.TIMES, SetupStep.ORDER, SetupStep.ALARM, SetupStep.SUMMARY)
        }

    /** "근무 설정 3/6": [step]'s place among [steps], 1-based. */
    val position: Int get() = steps.indexOf(step).coerceAtLeast(0) + 1

    fun next(): SetupStep? = steps.getOrNull(steps.indexOf(step) + 1)

    fun previous(): SetupStep? = steps.getOrNull(steps.indexOf(step) - 1)

    /** Whether the user may go on from [step]: the required answers are in. */
    fun canLeave(step: SetupStep = this.step): Boolean = when (step) {
        SetupStep.STYLE -> style != null
        SetupStep.COMPANY -> true
        SetupStep.BUNDLE -> types.isNotEmpty()
        SetupStep.TIMES -> timesConfirmed && workTypes.isNotEmpty() && timeProblems().isEmpty()
        SetupStep.ORDER -> when {
            usesRotation -> rotation.any { !isOff(it) }
            style == WorkStyle.SHIFT -> true
            style == WorkStyle.HOURLY && noFixedDays -> true
            else -> weekly.any { !isOff(it) }
        }
        SetupStep.TODAY -> anchor != null && todayConfirmed
        SetupStep.ALARM -> true
        SetupStep.SUMMARY -> true
    }

    /** What keeps the times from being confirmed, one line per shift. */
    fun timeProblems(): List<String> = workTypes.mapNotNull { t ->
        when {
            t.breakMinutes >= t.durationMinutes -> "${t.name}: 쉬는 시간이 근무 시간보다 길어요"
            else -> null
        }
    }

    /** Shifts longer than 16 hours, asked again so a wrong end time is not saved by accident. */
    fun longShifts(): List<ShiftType> = workTypes.filter { it.durationMinutes > 16 * 60 }

    /** The pattern this draft saves, starting on [today]; null when the days are painted or not fixed. */
    fun pattern(today: LocalDate): WorkPattern? = when {
        usesRotation -> rotation.takeIf { it.isNotEmpty() }?.let { WorkPattern.Rotation(today, it, anchorDate = anchor ?: today) }
        style == WorkStyle.DAY || style == WorkStyle.HOURLY && !noFixedDays ->
            WorkPattern.Weekly.fromSteps(today, weekly).takeIf { w -> w.steps.any { !isOff(it) } }
        else -> null
    }

    /** [SETUP-02] Painted days as the calendar keeps them. */
    fun paintedEntries(): List<DayEntry> = painted.toSortedMap().map { (date, id) ->
        DayEntry(date, override = if (id == null) ShiftOverride.Off else ShiftOverride.Shift(id))
    }

    /** The same draft with the shift ids the phone gave each type on saving; ids not in [ids] stay. */
    fun remap(ids: Map<Long, Long>): SetupDraft {
        fun id(i: Long?) = i?.let { ids[it] ?: it }
        return copy(
            types = types.map { it.copy(id = id(it.id)!!) },
            roles = roles.mapKeys { id(it.key)!! },
            rotation = rotation.map(::id),
            weekly = weekly.map(::id),
            painted = painted.mapValues { id(it.value) },
            alarmByType = alarmByType.mapKeys { id(it.key)!! },
        )
    }

    /** The shift types to save, each with the alarm chosen for it. */
    fun typesToSave(): List<ShiftType> = types.map { t ->
        val minutes = if (t.isOff || !t.hasTimes) null else alarmFor(t)
        t.copy(alarmEnabled = minutes != null, alarms = listOf(AlarmSpec(minutes ?: DEFAULT_ALARM_MINUTES)))
    }

    /** Starts the draft's shifts from a bundle (or the weekday, part-time or custom set). */
    fun withShifts(bundle: String?, shifts: List<BundleShift>): SetupDraft {
        val types = shifts.mapIndexed { i, s -> s.type.copy(id = -(i + 1L), sortOrder = i) }
        val roles = shifts.mapIndexed { i, s -> -(i + 1L) to s.role }.toMap()
        val day = types.firstOrNull { !it.isOff }?.id
        return copy(
            bundle = bundle, types = types, roles = roles, timesConfirmed = false, rotation = emptyList(),
            // [WORK-01] Weekday work starts Monday to Friday on its day shift.
            weekly = DayOfWeek.entries.map { if (style == WorkStyle.DAY && it.value <= 5) day else null },
            painted = emptyMap(), anchor = null, todayConfirmed = false, alarmByType = emptyMap(),
        )
    }

    /**
     * [COMM-01][SETUP-02] Starts the draft from a shared setup: its shifts and the order as the file
     * names them. The roles are guessed from the times, so the order can still be changed later.
     */
    fun withShared(source: SetupSource, shifts: List<ShiftType>, order: List<String?>): SetupDraft {
        val types = shifts.mapIndexed { i, t -> t.copy(id = -(i + 1L), sortOrder = i) }
        val byName = types.associateBy { it.name }
        val day = types.firstOrNull { !it.isOff && it.hasTimes }?.id
        return copy(
            bundle = null, source = source, types = types, roles = types.associate { it.id to ShiftRole.guess(it) },
            timesConfirmed = false, rotation = order.map { name -> name?.let { byName[it]?.id } },
            weekly = DayOfWeek.entries.map { if (style == WorkStyle.DAY && it.value <= 5) day else null },
            painted = emptyMap(), anchor = null, todayConfirmed = false, alarmByType = emptyMap(),
        )
    }

    /** A new id for a shift the user adds. */
    fun nextTypeId(): Long = (types.minOfOrNull { it.id } ?: 0L).coerceAtMost(0L) - 1

    companion object {
        const val CUSTOM = "custom"
        const val DEFAULT_ALARM_MINUTES = 60
        /** The choices of "출근 몇 분 전에 깨워 드릴까요?", besides 안 써요 and 직접. */
        val ALARM_CHOICES = listOf(30, 60, 90, 120)
    }
}
