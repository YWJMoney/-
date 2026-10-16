package com.bamnat.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime

/** [LEDG-01] Household ledger lines; version 7. */
@Entity(tableName = "money_entries", indices = [Index("epochDay")])
data class MoneyEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    /** [MoneyKind.name]. */
    val kind: String,
    val amount: Long,
    val category: String,
    val note: String,
    /** [MoneySource.name]. */
    val source: String,
    val createdAt: Long,
)

/** [WORK-02] Booked jobs for field and freelance work; version 7. */
@Entity(tableName = "jobs", indices = [Index("epochDay")])
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val title: String,
    val client: String,
    val place: String,
    val startMinute: Int?,
    val endMinute: Int?,
    val amount: Long,
    val deposit: Long,
    val depositPaid: Boolean,
    val balancePaid: Boolean,
    val remindMinutes: Int?,
    val note: String,
)

/** [WORK-03] One row per day a shop recorded; version 7. */
@Entity(tableName = "daily_sales")
data class DailySalesEntity(
    @PrimaryKey val epochDay: Long,
    val sales: Long,
    val costs: Long,
    val note: String,
)

@Dao
interface MoneyDao {
    @Query("SELECT * FROM money_entries ORDER BY epochDay DESC, id DESC")
    fun observeEntries(): Flow<List<MoneyEntryEntity>>

    @Query("SELECT * FROM money_entries")
    suspend fun allEntries(): List<MoneyEntryEntity>

    @Insert
    suspend fun insertEntry(e: MoneyEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreEntry(e: MoneyEntryEntity)

    @Query("DELETE FROM money_entries WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Query("SELECT * FROM jobs ORDER BY epochDay, startMinute, id")
    fun observeJobs(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs")
    suspend fun allJobs(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun job(id: Long): JobEntity?

    @Insert
    suspend fun insertJob(e: JobEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreJob(e: JobEntity)

    @Query("DELETE FROM jobs WHERE id = :id")
    suspend fun deleteJob(id: Long)

    @Query("SELECT * FROM daily_sales ORDER BY epochDay")
    fun observeSales(): Flow<List<DailySalesEntity>>

    @Query("SELECT * FROM daily_sales")
    suspend fun allSales(): List<DailySalesEntity>

    @Upsert
    suspend fun upsertSales(e: DailySalesEntity)

    @Query("DELETE FROM daily_sales WHERE epochDay = :epochDay")
    suspend fun deleteSales(epochDay: Long)

    @Query("DELETE FROM money_entries")
    suspend fun clearEntries()

    @Query("DELETE FROM jobs")
    suspend fun clearJobs()

    @Query("DELETE FROM daily_sales")
    suspend fun clearSales()
}

fun MoneyEntryEntity.toModel() = MoneyEntry(
    id = id,
    date = LocalDate.ofEpochDay(epochDay),
    kind = runCatching { MoneyKind.valueOf(kind) }.getOrDefault(MoneyKind.EXPENSE),
    amount = amount,
    category = category,
    note = note,
    source = runCatching { MoneySource.valueOf(source) }.getOrDefault(MoneySource.MANUAL),
)

fun MoneyEntry.toEntity(createdAt: Long = System.currentTimeMillis()) = MoneyEntryEntity(
    id = id, epochDay = date.toEpochDay(), kind = kind.name, amount = amount, category = category, note = note,
    source = source.name, createdAt = createdAt,
)

fun JobEntity.toModel() = Job(
    id = id, date = LocalDate.ofEpochDay(epochDay), title = title, client = client, place = place,
    start = startMinute?.let { LocalTime.of(it / 60, it % 60) }, end = endMinute?.let { LocalTime.of(it / 60, it % 60) },
    amount = amount, deposit = deposit, depositPaid = depositPaid, balancePaid = balancePaid,
    remindMinutes = remindMinutes, note = note,
)

fun Job.toEntity() = JobEntity(
    id = id, epochDay = date.toEpochDay(), title = title, client = client, place = place,
    startMinute = start?.let { it.hour * 60 + it.minute }, endMinute = end?.let { it.hour * 60 + it.minute },
    amount = amount, deposit = deposit, depositPaid = depositPaid, balancePaid = balancePaid,
    remindMinutes = remindMinutes, note = note,
)

fun DailySalesEntity.toModel() = DailySales(LocalDate.ofEpochDay(epochDay), sales, costs, note)

fun DailySales.toEntity() = DailySalesEntity(date.toEpochDay(), sales, costs, note)
