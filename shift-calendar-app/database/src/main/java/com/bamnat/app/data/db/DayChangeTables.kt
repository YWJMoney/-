package com.bamnat.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** [MKT-08] One change to one day, in words, with when it was made. */
@Entity(tableName = "day_changes", indices = [Index("epochDay")])
data class DayChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val atMillis: Long,
    @ColumnInfo(defaultValue = "") val text: String,
)

@Dao
interface DayChangeDao {
    @Query("SELECT * FROM day_changes WHERE epochDay = :epochDay ORDER BY atMillis DESC, id DESC")
    fun observe(epochDay: Long): Flow<List<DayChangeEntity>>

    @Query("SELECT * FROM day_changes WHERE epochDay = :epochDay ORDER BY atMillis DESC, id DESC LIMIT 1")
    suspend fun latest(epochDay: Long): DayChangeEntity?

    @Query("DELETE FROM day_changes WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insert(change: DayChangeEntity)

    /** Keeps the log small: changes older than [beforeMillis] go. */
    @Query("DELETE FROM day_changes WHERE atMillis < :beforeMillis")
    suspend fun purge(beforeMillis: Long)

    @Query("DELETE FROM day_changes")
    suspend fun clear()
}
