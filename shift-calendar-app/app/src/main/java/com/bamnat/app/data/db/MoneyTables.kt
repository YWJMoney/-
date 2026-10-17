package com.bamnat.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime

/** [LEDG-01] Household ledger lines; version 7, place/time/card/reviewed in version 9. */
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
    /** Version 9: place, time and card read from a payment text, and whether the user has seen it [LEDG-02]. */
    @ColumnInfo(defaultValue = "''") val merchant: String = "",
    @ColumnInfo(defaultValue = "NULL") val minuteOfDay: Int? = null,
    @ColumnInfo(defaultValue = "''") val cardLast4: String = "",
    @ColumnInfo(defaultValue = "1") val reviewed: Boolean = true,
)

/** [LEDG-05] Money leaving on a set day each month; version 9. */
@Entity(tableName = "fixed_costs")
data class FixedCostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: Long,
    val day: Int,
    val kind: String,
    val category: String,
    val remind: Boolean,
)

/** [LEDG-04] The category the user gave a place; it wins over the built-in dictionary. Version 9. */
@Entity(tableName = "category_rules")
data class CategoryRuleEntity(
    @PrimaryKey val merchant: String,
    val category: String,
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
    /** Version 8: last day of a job over several days, its costs and 3.3% withholding. */
    @ColumnInfo(defaultValue = "NULL") val endEpochDay: Long? = null,
    @ColumnInfo(defaultValue = "0") val costs: Long = 0,
    @ColumnInfo(defaultValue = "0") val withholding: Boolean = false,
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

    @Query("SELECT * FROM money_entries WHERE id = :id")
    suspend fun entry(id: Long): MoneyEntryEntity?

    @Query("SELECT * FROM money_entries WHERE epochDay >= :fromDay")
    suspend fun entriesFrom(fromDay: Long): List<MoneyEntryEntity>

    @Query("UPDATE money_entries SET reviewed = 1 WHERE reviewed = 0")
    suspend fun markAllReviewed()

    @Query("SELECT * FROM fixed_costs ORDER BY day, id")
    fun observeFixed(): Flow<List<FixedCostEntity>>

    @Query("SELECT * FROM fixed_costs")
    suspend fun allFixed(): List<FixedCostEntity>

    @Insert
    suspend fun insertFixed(e: FixedCostEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreFixed(e: FixedCostEntity)

    @Query("DELETE FROM fixed_costs WHERE id = :id")
    suspend fun deleteFixed(id: Long)

    @Query("DELETE FROM fixed_costs")
    suspend fun clearFixed()

    @Query("SELECT * FROM category_rules")
    fun observeRules(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules")
    suspend fun allRules(): List<CategoryRuleEntity>

    @Upsert
    suspend fun upsertRule(e: CategoryRuleEntity)

    @Query("DELETE FROM category_rules")
    suspend fun clearRules()
}

fun MoneyEntryEntity.toModel() = MoneyEntry(
    id = id,
    date = LocalDate.ofEpochDay(epochDay),
    kind = runCatching { MoneyKind.valueOf(kind) }.getOrDefault(MoneyKind.EXPENSE),
    amount = amount,
    category = category,
    note = note,
    source = runCatching { MoneySource.valueOf(source) }.getOrDefault(MoneySource.MANUAL),
    merchant = merchant,
    time = minuteOfDay?.let { it.toLocalTime() },
    cardLast4 = cardLast4,
    reviewed = reviewed,
)

fun MoneyEntry.toEntity(createdAt: Long = System.currentTimeMillis()) = MoneyEntryEntity(
    id = id, epochDay = date.toEpochDay(), kind = kind.name, amount = amount, category = category, note = note,
    source = source.name, createdAt = createdAt, merchant = merchant, minuteOfDay = time?.let { it.hour * 60 + it.minute },
    cardLast4 = cardLast4, reviewed = reviewed,
)

fun FixedCostEntity.toModel() = FixedCost(
    id = id, name = name, amount = amount, day = day,
    kind = runCatching { MoneyKind.valueOf(kind) }.getOrDefault(MoneyKind.EXPENSE), category = category, remind = remind,
)

fun FixedCost.toEntity() = FixedCostEntity(id, name, amount, day, kind.name, category, remind)

fun JobEntity.toModel() = Job(
    id = id, date = LocalDate.ofEpochDay(epochDay), title = title, client = client, place = place,
    start = startMinute?.let { it.toLocalTime() }, end = endMinute?.let { it.toLocalTime() },
    amount = amount, deposit = deposit, depositPaid = depositPaid, balancePaid = balancePaid,
    remindMinutes = remindMinutes, note = note,
    endDate = endEpochDay?.let(LocalDate::ofEpochDay), costs = costs, withholding = withholding,
)

fun Job.toEntity() = JobEntity(
    id = id, epochDay = date.toEpochDay(), title = title, client = client, place = place,
    startMinute = start?.let { it.hour * 60 + it.minute }, endMinute = end?.let { it.hour * 60 + it.minute },
    amount = amount, deposit = deposit, depositPaid = depositPaid, balancePaid = balancePaid,
    remindMinutes = remindMinutes, note = note,
    endEpochDay = endDate?.takeIf { it.isAfter(date) }?.toEpochDay(), costs = costs, withholding = withholding,
)

fun DailySalesEntity.toModel() = DailySales(LocalDate.ofEpochDay(epochDay), sales, costs, note)

fun DailySales.toEntity() = DailySalesEntity(date.toEpochDay(), sales, costs, note)
