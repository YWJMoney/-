package com.shiftmate.calendar.data.db

import com.shiftmate.core.alarm.AlarmCodec
import com.shiftmate.core.memo.ChecklistCodec
import com.shiftmate.core.memo.Memo
import com.shiftmate.core.model.AlarmSpec
import com.shiftmate.core.model.DayEntry
import com.shiftmate.core.model.ShiftOverride
import com.shiftmate.core.model.ShiftType
import com.shiftmate.core.model.WorkPattern
import java.time.LocalDate
import java.time.LocalTime

internal fun Int.toLocalTime(): LocalTime = LocalTime.of(this / 60 % 24, this % 60)
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
)

internal fun encodeSteps(steps: List<Long?>): String = steps.joinToString(",") { it?.toString() ?: "" }
internal fun decodeSteps(raw: String): List<Long?> = raw.split(',').map { it.trim().toLongOrNull() }

fun PatternEntity.toModel(): WorkPattern? {
    val parsed = decodeSteps(steps)
    val start = LocalDate.ofEpochDay(startEpochDay)
    val end = endEpochDay?.let(LocalDate::ofEpochDay)
    return when (kind) {
        PatternEntity.KIND_WEEKLY -> if (parsed.size == 7) WorkPattern.Weekly.fromSteps(start, parsed, end) else null
        else -> if (parsed.isNotEmpty()) WorkPattern.Rotation(start, parsed, end) else null
    }
}

fun WorkPattern.toEntity(): PatternEntity = when (this) {
    is WorkPattern.Rotation -> PatternEntity(
        kind = PatternEntity.KIND_ROTATION,
        startEpochDay = startDate.toEpochDay(),
        endEpochDay = endDate?.toEpochDay(),
        steps = encodeSteps(steps),
    )
    is WorkPattern.Weekly -> PatternEntity(
        kind = PatternEntity.KIND_WEEKLY,
        startEpochDay = startDate.toEpochDay(),
        endEpochDay = endDate?.toEpochDay(),
        steps = encodeSteps(steps),
    )
}

fun DayEntryEntity.toModel() = DayEntry(
    date = LocalDate.ofEpochDay(epochDay),
    override = when (overrideMode) {
        DayEntryEntity.MODE_OFF -> ShiftOverride.Off
        DayEntryEntity.MODE_SHIFT -> overrideShiftTypeId?.let { ShiftOverride.Shift(it) } ?: ShiftOverride.None
        else -> ShiftOverride.None
    },
    customStart = customStartMinute?.toLocalTime(),
    customEnd = customEndMinute?.toLocalTime(),
    customBreakMinutes = customBreakMinutes,
    overtimeMinutes = overtimeMinutes,
    holidayWorkMinutes = holidayWorkMinutes,
    alarmSkipped = alarmSkipped,
    customAlarms = AlarmCodec.decodeDayAlarms(customAlarms),
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
    overtimeMinutes = overtimeMinutes,
    holidayWorkMinutes = holidayWorkMinutes,
    alarmSkipped = alarmSkipped,
    customAlarms = AlarmCodec.encodeDayAlarms(customAlarms),
)

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
)

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
)
