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
    val overtimeMinutes: Int,
    val holidayWorkMinutes: Int,
    val alarmSkipped: Boolean,
    /** Alarms set by hand for this date; null follows the shift type. */
    @ColumnInfo(defaultValue = "NULL") val customAlarms: String? = null,
    /** [com.bamnat.core.model.ExtraWork.code]; 0 means not marked. */
    @ColumnInfo(defaultValue = "0") val extraWork: Int = 0,
    /** [com.bamnat.core.model.Leave.code]; 0 means no leave [FEAT-10]. */
    @ColumnInfo(defaultValue = "0") val leave: Int = 0,
) {
    companion object {
        const val MODE_NONE = 0
        const val MODE_OFF = 1
        const val MODE_SHIFT = 2
    }
}

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
)
