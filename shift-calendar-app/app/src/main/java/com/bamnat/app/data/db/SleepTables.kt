package com.bamnat.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** [SLP-06] One logged sleep; [endMillis] is null until the user taps "깼어요". Kept on the phone only. */
@Entity(tableName = "sleep_logs", indices = [Index("startMillis")])
data class SleepLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long,
    val endMillis: Long?,
)

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep_logs WHERE startMillis >= :sinceMillis OR endMillis IS NULL ORDER BY startMillis DESC")
    fun observeSince(sinceMillis: Long): Flow<List<SleepLogEntity>>

    @Query("SELECT * FROM sleep_logs ORDER BY startMillis")
    suspend fun all(): List<SleepLogEntity>

    @Query("SELECT * FROM sleep_logs WHERE endMillis IS NULL ORDER BY startMillis DESC LIMIT 1")
    suspend fun open(): SleepLogEntity?

    @Insert
    suspend fun insert(log: SleepLogEntity): Long

    @Query("UPDATE sleep_logs SET endMillis = :endMillis WHERE id = :id")
    suspend fun finish(id: Long, endMillis: Long)

    @Query("DELETE FROM sleep_logs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sleep_logs")
    suspend fun clear()
}
