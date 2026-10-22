package com.bamnat.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "shift_types")
data class ShiftTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val label: String,
    val colorArgb: Long,
    /** Minutes after midnight, null for off types. */
    val startMinute: Int?,
    val endMinute: Int?,
    val breakMinutes: Int,
    val isOff: Boolean,
    val alarmEnabled: Boolean,
    val alarmOffsetMinutes: Int,
    val alarmSoundUri: String?,
    val sortOrder: Int,
    /** All alarms, see [com.bamnat.core.alarm.AlarmCodec]; empty on rows from version 1. */
    @ColumnInfo(defaultValue = "") val alarmOffsets: String = "",
    /** [com.bamnat.core.model.ShiftIcon.key]; null shows the label only [FEAT-01]. */
    @ColumnInfo(defaultValue = "NULL") val icon: String? = null,
    /** Minutes after midnight the break starts; null when unknown [PAY-01]. */
    @ColumnInfo(defaultValue = "NULL") val breakStartMinute: Int? = null,
    /** Version 17 [SHFT-01]. */
    @ColumnInfo(defaultValue = "0") val premiumPercent: Int = 0,
)

/**
 * One period of the pattern history. Version 2 kept a single row with id 1; since version 3 a
 * team change adds a row that starts where the previous one now ends.
 */
@Entity(tableName = "pattern")
data class PatternEntity(
    @PrimaryKey val id: Int = SINGLE_ID,
    /** [KIND_ROTATION] or [KIND_WEEKLY]. */
    val kind: String,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    /** Comma separated shift type ids; an empty item is a plain day off. */
    val steps: String,
    /** Day of step 0 for a rotation; null means [startEpochDay]. */
    @ColumnInfo(defaultValue = "NULL") val anchorEpochDay: Long? = null,
) {
    companion object {
        const val SINGLE_ID = 1
        const val KIND_ROTATION = "ROTATION"
        const val KIND_WEEKLY = "WEEKLY"
    }
}

@Entity(tableName = "day_entries")
data class DayEntryEntity(
    @PrimaryKey val epochDay: Long,
    /** 0 = follow pattern, 1 = day off, 2 = [overrideShiftTypeId]. */
    val overrideMode: Int,
    val overrideShiftTypeId: Long?,
    val customStartMinute: Int?,
    val customEndMinute: Int?,
    val customBreakMinutes: Int?,
    /** Version 11 and before; since version 12 extra work is in [DayExtraEntity] and this is always 0. */
    val overtimeMinutes: Int = 0,
    /** Version 11 and before; always 0 since version 12. */
    val holidayWorkMinutes: Int = 0,
    val alarmSkipped: Boolean,
    /** Alarms set by hand for this date; null follows the shift type. */
    @ColumnInfo(defaultValue = "NULL") val customAlarms: String? = null,
    /** Version 11 and before (잔업 1, 특근 2, 대근 3); always 0 since version 12. */
    @ColumnInfo(defaultValue = "0") val extraWork: Int = 0,
    /** [com.bamnat.core.model.Leave.code]; 0 means no leave [FEAT-10]. */
    @ColumnInfo(defaultValue = "0") val leave: Int = 0,
    /** Version 5 to 11 [FEAT-02]; since version 12 a second shift is a [DayExtraEntity] covering it. */
    @ColumnInfo(defaultValue = "NULL") val secondShiftTypeId: Long? = null,
    /** Version 17: [com.bamnat.core.model.Attendance.code], 0 for none [HRLY-01]; who the day was swapped with [SHFT-03]. */
    @ColumnInfo(defaultValue = "0") val attendance: Int = 0,
    @ColumnInfo(defaultValue = "''") val swapWith: String = "",
) {
    companion object {
        const val MODE_NONE = 0
        const val MODE_OFF = 1
        const val MODE_SHIFT = 2
    }
}

/**
 * [EXTRA-01] One piece of extra work on a date (version 12): 잔업, 특근, 대근 … A day can hold several,
 * in [position] order. Clock times are minutes after midnight; null when only the length is known.
 */
@Entity(tableName = "day_extras", indices = [Index("epochDay")])
data class DayExtraEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    /** [com.bamnat.core.model.ExtraKind.id]. */
    val kindId: Long,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    @ColumnInfo(defaultValue = "0") val breakMinutes: Int = 0,
    val breakStartMinute: Int? = null,
    /** Length when there are no clock times. */
    @ColumnInfo(defaultValue = "0") val minutes: Int = 0,
    val coveredShiftTypeId: Long? = null,
    /** [EXTRA-05] Moved from a version 11 record that may hold hours nobody meant. */
    @ColumnInfo(defaultValue = "0") val needsReview: Boolean = false,
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
    /** [PAYX-18] Version 19: paid in time off (보상휴가) instead of money. */
    @ColumnInfo(defaultValue = "0") val compLeave: Boolean = false,
)

@Entity(tableName = "memos", indices = [Index("epochDay"), Index("updatedAt"), Index("deletedAt")])
data class MemoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long?,
    val title: String,
    val body: String,
    /** See [com.bamnat.core.memo.ChecklistCodec]. */
    val checklist: String,
    val isChecklist: Boolean,
    val colorIndex: Int,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** When the memo went to the trash; null for live memos. */
    @ColumnInfo(defaultValue = "NULL") val deletedAt: Long? = null,
    /** [SCHED-01] When the memo's schedule is, as a local date and time ("2026-10-20T15:00"); null for a plain memo. */
    @ColumnInfo(defaultValue = "NULL") val remindAt: String? = null,
    @ColumnInfo(defaultValue = "0") val remindRing: Boolean = false,
    @ColumnInfo(defaultValue = "0") val remindNotify: Boolean = false,
    /** [SCHED-01] The found time the user last answered about. */
    @ColumnInfo(defaultValue = "NULL") val remindAsked: String? = null,
    /** [NOTE-01] See [com.bamnat.core.memo.MemoCalendars]; '' is a memo from before calendars, 내 달력's alone. */
    @ColumnInfo(defaultValue = "''") val calendars: String = "",
    /** Version 17 [NOTE-05]. */
    @ColumnInfo(defaultValue = "''") val repeat: String = "",
)
