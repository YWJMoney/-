package com.bamnat.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftTypeDao {
    @Query("SELECT * FROM shift_types ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ShiftTypeEntity>>

    @Query("SELECT * FROM shift_types ORDER BY sortOrder, id")
    suspend fun getAll(): List<ShiftTypeEntity>

    @Query("SELECT COUNT(*) FROM shift_types")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM shift_types")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(entity: ShiftTypeEntity): Long

    @Insert
    suspend fun insertAll(entities: List<ShiftTypeEntity>)

    @Update
    suspend fun update(entity: ShiftTypeEntity)

    @Update
    suspend fun updateAll(entities: List<ShiftTypeEntity>)

    @Query("DELETE FROM shift_types WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM shift_types")
    suspend fun clear()
}

@Dao
interface PatternDao {
    @Query("SELECT * FROM pattern ORDER BY startEpochDay, id")
    fun observeAll(): Flow<List<PatternEntity>>

    @Query("SELECT * FROM pattern ORDER BY startEpochDay, id")
    suspend fun getAll(): List<PatternEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: PatternEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(entities: List<PatternEntity>)

    @Query("DELETE FROM pattern")
    suspend fun clear()
}

@Dao
interface DayEntryDao {
    @Query("SELECT * FROM day_entries")
    fun observeAll(): Flow<List<DayEntryEntity>>

    @Query("SELECT * FROM day_entries WHERE epochDay = :epochDay")
    suspend fun get(epochDay: Long): DayEntryEntity?

    @Upsert
    suspend fun upsert(entity: DayEntryEntity)

    @Query("DELETE FROM day_entries WHERE epochDay = :epochDay")
    suspend fun delete(epochDay: Long)

    @Query("SELECT * FROM day_entries WHERE overrideMode = 2 AND overrideShiftTypeId = :shiftTypeId")
    suspend fun withOverride(shiftTypeId: Long): List<DayEntryEntity>

    @Query("SELECT * FROM day_entries")
    suspend fun getAll(): List<DayEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DayEntryEntity>)

    @Query("DELETE FROM day_entries")
    suspend fun clear()
}

/** [EXTRA-01] Extra work by date; [ScheduleRepository] replaces a day's list as a whole. */
@Dao
interface DayExtraDao {
    @Query("SELECT * FROM day_extras ORDER BY epochDay, position, id")
    fun observeAll(): Flow<List<DayExtraEntity>>

    @Query("SELECT * FROM day_extras ORDER BY epochDay, position, id")
    suspend fun getAll(): List<DayExtraEntity>

    @Query("SELECT * FROM day_extras WHERE epochDay = :epochDay ORDER BY position, id")
    suspend fun onDay(epochDay: Long): List<DayExtraEntity>

    @Query("SELECT * FROM day_extras WHERE coveredShiftTypeId = :shiftTypeId")
    suspend fun covering(shiftTypeId: Long): List<DayExtraEntity>

    @Query("SELECT COUNT(*) FROM day_extras WHERE needsReview = 1")
    fun observeNeedsReview(): Flow<Int>

    @Insert
    suspend fun insertAll(entities: List<DayExtraEntity>)

    @Update
    suspend fun updateAll(entities: List<DayExtraEntity>)

    @Query("DELETE FROM day_extras WHERE epochDay = :epochDay")
    suspend fun deleteDay(epochDay: Long)

    @Query("UPDATE day_extras SET needsReview = 0 WHERE epochDay = :epochDay")
    suspend fun markReviewed(epochDay: Long)

    @Query("UPDATE day_extras SET needsReview = 0")
    suspend fun markAllReviewed()

    @Query("DELETE FROM day_extras")
    suspend fun clear()
}

@Dao
interface MemoDao {
    @Query("SELECT * FROM memos WHERE deletedAt IS NULL ORDER BY pinned DESC, updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<MemoEntity>>

    @Query("SELECT * FROM memos WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<MemoEntity>>

    @Query(
        "SELECT * FROM memos WHERE epochDay = :epochDay AND deletedAt IS NULL " +
            "ORDER BY pinned DESC, updatedAt DESC, id DESC",
    )
    fun observeOn(epochDay: Long): Flow<List<MemoEntity>>

    @Query("SELECT DISTINCT epochDay FROM memos WHERE epochDay IS NOT NULL AND deletedAt IS NULL")
    fun observeDatesWithMemos(): Flow<List<Long>>

    @Query("SELECT * FROM memos WHERE id = :id")
    suspend fun get(id: Long): MemoEntity?

    @Query("SELECT * FROM memos")
    suspend fun getAll(): List<MemoEntity>

    @Insert
    suspend fun insert(entity: MemoEntity): Long

    /** Re-inserts a memo with its original id (undo, backup restore). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(entity: MemoEntity)

    @Update
    suspend fun update(entity: MemoEntity)

    @Query("UPDATE memos SET deletedAt = :at WHERE id IN (:ids)")
    suspend fun moveToTrash(ids: List<Long>, at: Long)

    @Query("UPDATE memos SET deletedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreFromTrash(ids: List<Long>)

    @Query("UPDATE memos SET pinned = :pinned WHERE id IN (:ids)")
    suspend fun setPinned(ids: List<Long>, pinned: Boolean)

    @Query("UPDATE memos SET colorIndex = :color WHERE id IN (:ids)")
    suspend fun setColor(ids: List<Long>, color: Int)

    @Query("DELETE FROM memos WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memos WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    @Query("DELETE FROM memos WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeTrash(before: Long)

    @Query("DELETE FROM memos WHERE deletedAt IS NOT NULL")
    suspend fun emptyTrash()

    @Query("DELETE FROM memos")
    suspend fun clear()
}
