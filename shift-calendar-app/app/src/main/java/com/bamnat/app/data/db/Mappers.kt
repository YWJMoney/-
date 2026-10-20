package com.bamnat.app.data.db

import com.bamnat.core.alarm.AlarmCodec
import com.bamnat.core.memo.ChecklistCodec
import com.bamnat.core.memo.Memo
import com.bamnat.core.memo.MemoReminder
import java.time.LocalDateTime
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.Leave
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import java.time.LocalDate
import java.time.LocalTime

// A bad value from an old or hand-edited backup wraps onto the clock instead of crashing every launch.
internal fun Int.toLocalTime(): LocalTime = Math.floorMod(this, 24 * 60).let { LocalTime.of(it / 60, it % 60) }
internal fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute

fun ShiftTypeEntity.toModel() = ShiftType(
    id = id,
    name = name,
    label = label,
    colorArgb = colorArgb,
    start = if (isOff) null else startMinute?.toLocalTime(),
    end = if (isOff) null else endMinute?.toLocalTime(),
    breakMinutes = breakMinutes,
    isOff = isOff,
    alarmEnabled = alarmEnabled,
    alarms = AlarmCodec.decodeSpecs(alarmOffsets) ?: listOf(AlarmSpec(alarmOffsetMinutes)),
    alarmSoundUri = alarmSoundUri,
    sortOrder = sortOrder,
    icon = icon,
    breakStart = if (isOff) null else breakStartMinute?.toLocalTime(),
    premiumPercent = premiumPercent.coerceIn(0, 300),
)

fun ShiftType.toEntity() = ShiftTypeEntity(
    id = id,
    name = name,
    label = label,
    colorArgb = colorArgb,
    startMinute = start?.toMinuteOfDay(),
    endMinute = end?.toMinuteOfDay(),
    breakMinutes = breakMinutes,
    isOff = isOff,
    alarmEnabled = alarmEnabled,
    // Kept filled so a downgrade to version 1 would still find an alarm.
    alarmOffsetMinutes = alarms.firstOrNull()?.offsetMinutes ?: 60,
    alarmSoundUri = alarmSoundUri,
    sortOrder = sortOrder,
    alarmOffsets = AlarmCodec.encodeSpecs(alarms),
    icon = icon,
    breakStartMinute = breakStart?.toMinuteOfDay(),
    premiumPercent = premiumPercent.coerceIn(0, 300),
)

internal fun encodeSteps(steps: List<Long?>): String = steps.joinToString(",") { it?.toString() ?: "" }
internal fun decodeSteps(raw: String): List<Long?> = raw.split(',').map { it.trim().toLongOrNull() }

fun PatternEntity.toModel(): WorkPattern? {
    val parsed = decodeSteps(steps)
    val start = LocalDate.ofEpochDay(startEpochDay)
    val end = endEpochDay?.let(LocalDate::ofEpochDay)
    return when (kind) {
        PatternEntity.KIND_WEEKLY -> if (parsed.size == 7) WorkPattern.Weekly.fromSteps(start, parsed, end) else null
        else -> if (parsed.isNotEmpty()) {
            WorkPattern.Rotation(start, parsed, end, anchorEpochDay?.let(LocalDate::ofEpochDay) ?: start)
        } else {
            null
        }
    }
}

fun WorkPattern.toEntity(id: Int = PatternEntity.SINGLE_ID): PatternEntity = when (this) {
    is WorkPattern.Rotation -> PatternEntity(
        id = id,
        kind = PatternEntity.KIND_ROTATION,
        startEpochDay = startDate.toEpochDay(),
        endEpochDay = endDate?.toEpochDay(),
        steps = encodeSteps(steps),
        anchorEpochDay = anchorDate.toEpochDay().takeIf { anchorDate != startDate },
    )
    is WorkPattern.Weekly -> PatternEntity(
        id = id,
        kind = PatternEntity.KIND_WEEKLY,
        startEpochDay = startDate.toEpochDay(),
        endEpochDay = endDate?.toEpochDay(),
        steps = encodeSteps(steps),
    )
}

/** A stored day with its [extras] from `day_extras` [EXTRA-01]. */
fun DayEntryEntity.toModel(extras: List<ExtraEntry> = emptyList()) = DayEntry(
    date = LocalDate.ofEpochDay(epochDay),
    override = when (overrideMode) {
        DayEntryEntity.MODE_OFF -> ShiftOverride.Off
        DayEntryEntity.MODE_SHIFT -> overrideShiftTypeId?.let { ShiftOverride.Shift(it) } ?: ShiftOverride.None
        else -> ShiftOverride.None
    },
    customStart = customStartMinute?.toLocalTime(),
    customEnd = customEndMinute?.toLocalTime(),
    customBreakMinutes = customBreakMinutes,
    alarmSkipped = alarmSkipped,
    customAlarms = AlarmCodec.decodeDayAlarms(customAlarms),
    leave = Leave.fromCode(leave),
    extras = extras,
    attendance = com.bamnat.core.model.Attendance.fromCode(attendance),
    swapWith = swapWith,
)

fun DayEntry.toEntity() = DayEntryEntity(
    epochDay = date.toEpochDay(),
    overrideMode = when (override) {
        ShiftOverride.None -> DayEntryEntity.MODE_NONE
        ShiftOverride.Off -> DayEntryEntity.MODE_OFF
        is ShiftOverride.Shift -> DayEntryEntity.MODE_SHIFT
    },
    overrideShiftTypeId = (override as? ShiftOverride.Shift)?.shiftTypeId,
    customStartMinute = customStart?.toMinuteOfDay(),
    customEndMinute = customEnd?.toMinuteOfDay(),
    customBreakMinutes = customBreakMinutes,
    alarmSkipped = alarmSkipped,
    customAlarms = AlarmCodec.encodeDayAlarms(customAlarms),
    leave = leave?.code ?: 0,
    attendance = attendance?.code ?: 0,
    swapWith = swapWith.trim(),
)

fun DayExtraEntity.toModel() = ExtraEntry(
    kindId = kindId,
    start = startMinute?.toLocalTime(),
    end = endMinute?.toLocalTime(),
    breakMinutes = breakMinutes.coerceAtLeast(0),
    breakStart = breakStartMinute?.toLocalTime(),
    minutes = minutes.coerceAtLeast(0),
    coveredShiftId = coveredShiftTypeId,
    needsReview = needsReview,
)

fun ExtraEntry.toEntity(epochDay: Long, position: Int) = DayExtraEntity(
    epochDay = epochDay,
    kindId = kindId,
    // Times come as a pair; half of one is no time at all.
    startMinute = start?.takeIf { hasTimes }?.toMinuteOfDay(),
    endMinute = end?.takeIf { hasTimes }?.toMinuteOfDay(),
    breakMinutes = breakMinutes,
    breakStartMinute = breakStart?.takeIf { hasTimes }?.toMinuteOfDay(),
    minutes = minutes,
    coveredShiftTypeId = coveredShiftId,
    needsReview = needsReview,
    position = position,
)

/** [EXTRA-01] Days as the calendar sees them: each stored day with its extra work, and days that have only extra work. */
fun assembleDays(rows: List<DayEntryEntity>, extras: List<DayExtraEntity>): List<DayEntry> {
    val byDay = extras.groupBy { it.epochDay }
        .mapValues { (_, list) -> list.sortedWith(compareBy({ it.position }, { it.id })).map { it.toModel() } }
    val stored = rows.map { it.epochDay }.toSet()
    return rows.map { it.toModel(byDay[it.epochDay].orEmpty()) } +
        byDay.filterKeys { it !in stored }.map { (day, list) -> DayEntry(LocalDate.ofEpochDay(day), extras = list) }
}

fun MemoEntity.toModel() = Memo(
    id = id,
    date = epochDay?.let(LocalDate::ofEpochDay),
    title = title,
    body = body,
    checklist = ChecklistCodec.decode(checklist),
    isChecklist = isChecklist,
    colorIndex = colorIndex,
    pinned = pinned,
    createdAtMillis = createdAt,
    updatedAtMillis = updatedAt,
    deletedAtMillis = deletedAt,
    reminder = memoReminder(remindAt, remindRing, remindNotify),
    reminderAsked = remindAsked,
    calendars = com.bamnat.core.memo.MemoCalendars.decode(calendars),
    repeat = repeat,
)

/** [SCHED-01] A stored schedule, or null when it is unreadable or neither rings nor notifies. */
fun memoReminder(at: String?, ring: Boolean, notify: Boolean): MemoReminder? {
    if (at == null || !(ring || notify)) return null
    val time = runCatching { LocalDateTime.parse(at) }.getOrNull() ?: return null
    return MemoReminder(time, ring, notify)
}

fun Memo.toEntity() = MemoEntity(
    id = id,
    epochDay = date?.toEpochDay(),
    title = title,
    body = body,
    checklist = ChecklistCodec.encode(checklist),
    isChecklist = isChecklist,
    colorIndex = colorIndex,
    pinned = pinned,
    createdAt = createdAtMillis,
    updatedAt = updatedAtMillis,
    deletedAt = deletedAtMillis,
    remindAt = reminder?.at?.toString(),
    remindRing = reminder?.ring == true,
    remindNotify = reminder?.notify == true,
    remindAsked = reminderAsked,
    calendars = com.bamnat.core.memo.MemoCalendars.encode(calendars),
    repeat = repeat,
)
