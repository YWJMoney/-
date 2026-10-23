package com.bamnat.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.bamnat.core.leave.EntryKind
import com.bamnat.core.leave.LeavePool
import com.bamnat.core.leave.LedgerEntry
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * [LEAVE-01] Version 19: 연차 원장. Only what the calendar does not hold (a grant that replaces the app's own count,
 * 이월, 조정, 정산); leave used stays on the days it was taken. Each calendar's database has its own.
 */
@Entity(tableName = "leave_ledger", indices = [Index("epochDay")])
data class LeaveLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    /** [EntryKind.name]. */
    val kind: String,
    val days: Double,
    val expiresEpochDay: Long? = null,
    val yearStartEpochDay: Long? = null,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    /** [LeavePool.name]. */
    @ColumnInfo(defaultValue = "'ANNUAL'") val pool: String = LeavePool.ANNUAL.name,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
)

fun LeaveLedgerEntity.toModel(): LedgerEntry? = EntryKind.of(kind)?.let { k ->
    LedgerEntry(
        id = id, kind = k, date = LocalDate.ofEpochDay(epochDay), days = days,
        expires = expiresEpochDay?.let(LocalDate::ofEpochDay), yearStart = yearStartEpochDay?.let(LocalDate::ofEpochDay),
        note = note, pool = LeavePool.entries.firstOrNull { it.name == pool } ?: LeavePool.ANNUAL,
    )
}

fun LedgerEntry.toEntity(createdAt: Long) = LeaveLedgerEntity(
    id = id, epochDay = date.toEpochDay(), kind = kind.name, days = days,
    expiresEpochDay = expires?.toEpochDay(), yearStartEpochDay = yearStart?.toEpochDay(), note = note.trim(), pool = pool.name,
    createdAt = createdAt,
)

@Dao
interface LeaveLedgerDao {
    @Query("SELECT * FROM leave_ledger ORDER BY epochDay, id")
    fun observeAll(): Flow<List<LeaveLedgerEntity>>

    @Query("SELECT * FROM leave_ledger ORDER BY epochDay, id")
    suspend fun all(): List<LeaveLedgerEntity>

    @Insert
    suspend fun insert(row: LeaveLedgerEntity): Long

    @Insert
    suspend fun insertAll(rows: List<LeaveLedgerEntity>)

    @Update
    suspend fun update(row: LeaveLedgerEntity)

    @Query("DELETE FROM leave_ledger WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM leave_ledger")
    suspend fun clear()
}
