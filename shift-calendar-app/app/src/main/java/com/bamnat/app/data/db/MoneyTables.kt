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
import com.bamnat.core.money.AccountType
import com.bamnat.core.money.CategoryEdit
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.EntryPart
import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyAccount
import com.bamnat.core.money.MoneyBudget
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import com.bamnat.core.money.Repeat
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
    /** Version 15: account and transfer target [LEDG-08], memo, tags and split parts [LEDG-11], installments, repeating line [LEDG-09]. */
    @ColumnInfo(defaultValue = "NULL") val accountId: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val toAccountId: Long? = null,
    @ColumnInfo(defaultValue = "''") val memo: String = "",
    /** Tags joined by commas. */
    @ColumnInfo(defaultValue = "''") val tags: String = "",
    /** Split parts, one "amount<TAB>category" a line; empty for one category. */
    @ColumnInfo(defaultValue = "''") val parts: String = "",
    @ColumnInfo(defaultValue = "1") val installments: Int = 1,
    @ColumnInfo(defaultValue = "NULL") val fixedId: Long? = null,
)

/** [LEDG-05] Money leaving on a set day each month; version 9. [LEDG-09] Any repeating line since version 15. */
@Entity(tableName = "fixed_costs")
data class FixedCostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: Long,
    val day: Int,
    val kind: String,
    val category: String,
    val remind: Boolean,
    /** [com.bamnat.core.money.Repeat.name]. */
    @ColumnInfo(defaultValue = "'MONTH'") val repeat: String = "MONTH",
    @ColumnInfo(defaultValue = "1") val monthOfYear: Int = 1,
    @ColumnInfo(defaultValue = "NULL") val accountId: Long? = null,
    @ColumnInfo(defaultValue = "0") val auto: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val startEpochDay: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val endEpochDay: Long? = null,
)

/** [LEDG-08] Cash, bank accounts, cards and pay balances; version 15. */
@Entity(tableName = "money_accounts")
data class MoneyAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** [AccountType.name]. */
    val type: String,
    val last4: String,
    val payDay: Int?,
    val closeDay: Int?,
    val payFromId: Long?,
    val opening: Long,
    val color: Int,
    val sortOrder: Int,
    val hidden: Boolean,
    val remind: Boolean,
)

/** [LEDG-06] Monthly budgets; "" is the whole month's. Version 15. */
@Entity(tableName = "money_budgets")
data class MoneyBudgetEntity(
    @PrimaryKey val category: String,
    val amount: Long,
)

/** [LEDG-14] Categories the user added, hid or moved, per kind of line. Version 15. */
@Entity(tableName = "money_categories", primaryKeys = ["kind", "name"])
data class MoneyCategoryEntity(
    val kind: String,
    val name: String,
    val position: Int,
    val hidden: Boolean,
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

    // [LEDG-07] Several lines at once, for the list's 고르기 and its undo.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreEntries(list: List<MoneyEntryEntity>)

    @Query("DELETE FROM money_entries WHERE id IN (:ids)")
    suspend fun deleteEntries(ids: List<Long>)

    // [LEDG-08] Accounts.
    @Query("SELECT * FROM money_accounts ORDER BY sortOrder, id")
    fun observeAccounts(): Flow<List<MoneyAccountEntity>>

    @Query("SELECT * FROM money_accounts")
    suspend fun allAccounts(): List<MoneyAccountEntity>

    @Insert
    suspend fun insertAccount(e: MoneyAccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAccount(e: MoneyAccountEntity)

    @Query("DELETE FROM money_accounts WHERE id = :id")
    suspend fun deleteAccount(id: Long)

    @Query("DELETE FROM money_accounts")
    suspend fun clearAccounts()

    @Query("SELECT COUNT(*) FROM money_entries WHERE accountId = :id OR toAccountId = :id")
    suspend fun linesOfAccount(id: Long): Int

    // [LEDG-06] Budgets.
    @Query("SELECT * FROM money_budgets")
    fun observeBudgets(): Flow<List<MoneyBudgetEntity>>

    @Query("SELECT * FROM money_budgets")
    suspend fun allBudgets(): List<MoneyBudgetEntity>

    @Upsert
    suspend fun upsertBudget(e: MoneyBudgetEntity)

    @Query("DELETE FROM money_budgets WHERE category = :category")
    suspend fun deleteBudget(category: String)

    @Query("DELETE FROM money_budgets")
    suspend fun clearBudgets()

    // [LEDG-14] The user's categories.
    @Query("SELECT * FROM money_categories ORDER BY kind, position")
    fun observeCategories(): Flow<List<MoneyCategoryEntity>>

    @Query("SELECT * FROM money_categories")
    suspend fun allCategories(): List<MoneyCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(list: List<MoneyCategoryEntity>)

    @Query("DELETE FROM money_categories WHERE kind = :kind")
    suspend fun clearCategories(kind: String)

    @Query("DELETE FROM money_categories")
    suspend fun clearAllCategories()

    /** [LEDG-14] A renamed category takes its lines, budget, learned places and repeating lines with it. */
    @Query("UPDATE money_entries SET category = :to WHERE kind = :kind AND category = :from")
    suspend fun renameInEntries(kind: String, from: String, to: String)

    @Query("UPDATE fixed_costs SET category = :to WHERE kind = :kind AND category = :from")
    suspend fun renameInFixed(kind: String, from: String, to: String)

    @Query("UPDATE category_rules SET category = :to WHERE category = :from")
    suspend fun renameInRules(from: String, to: String)

    @Query("SELECT * FROM money_entries WHERE parts LIKE '%' || :category || '%'")
    suspend fun entriesWithPart(category: String): List<MoneyEntryEntity>
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
    accountId = accountId,
    toAccountId = toAccountId,
    memo = memo,
    tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    parts = decodeParts(parts),
    installments = installments.coerceAtLeast(1),
    fixedId = fixedId,
)

fun MoneyEntry.toEntity(createdAt: Long = System.currentTimeMillis()) = MoneyEntryEntity(
    id = id, epochDay = date.toEpochDay(), kind = kind.name, amount = amount, category = category, note = note,
    source = source.name, createdAt = createdAt, merchant = merchant, minuteOfDay = time?.let { it.hour * 60 + it.minute },
    cardLast4 = cardLast4, reviewed = reviewed,
    accountId = accountId, toAccountId = toAccountId, memo = memo, tags = tags.joinToString(","),
    parts = encodeParts(parts), installments = installments.coerceAtLeast(1), fixedId = fixedId,
)

/** [LEDG-11] Split parts as "amount<TAB>category" lines; categories never hold a tab ([MoneyCategories.clean]). */
fun encodeParts(parts: List<EntryPart>): String = parts.joinToString("\n") { "${it.amount}\t${it.category}" }

fun decodeParts(text: String): List<EntryPart> = if (text.isBlank()) emptyList() else text.lines().mapNotNull { line ->
    val tab = line.indexOf('\t')
    if (tab <= 0) return@mapNotNull null
    val amount = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
    val category = line.substring(tab + 1).trim()
    if (category.isEmpty()) null else EntryPart(category, amount)
}

fun FixedCostEntity.toModel() = FixedCost(
    id = id, name = name, amount = amount, day = day,
    kind = runCatching { MoneyKind.valueOf(kind) }.getOrDefault(MoneyKind.EXPENSE), category = category, remind = remind,
    repeat = runCatching { Repeat.valueOf(repeat) }.getOrDefault(Repeat.MONTH), monthOfYear = monthOfYear.coerceIn(1, 12),
    accountId = accountId, auto = auto,
    start = startEpochDay?.let(LocalDate::ofEpochDay), end = endEpochDay?.let(LocalDate::ofEpochDay),
)

fun FixedCost.toEntity() = FixedCostEntity(
    id = id, name = name, amount = amount, day = day, kind = kind.name, category = category, remind = remind,
    repeat = repeat.name, monthOfYear = monthOfYear, accountId = accountId, auto = auto,
    startEpochDay = start?.toEpochDay(), endEpochDay = end?.toEpochDay(),
)

fun MoneyAccountEntity.toModel() = MoneyAccount(
    id = id, name = name, type = runCatching { AccountType.valueOf(type) }.getOrDefault(AccountType.OTHER), last4 = last4,
    payDay = payDay, closeDay = closeDay, payFromId = payFromId, opening = opening, color = color, order = sortOrder,
    hidden = hidden, remind = remind,
)

fun MoneyAccount.toEntity() = MoneyAccountEntity(
    id = id, name = name, type = type.name, last4 = last4, payDay = payDay, closeDay = closeDay, payFromId = payFromId,
    opening = opening, color = color, sortOrder = order, hidden = hidden, remind = remind,
)

fun MoneyBudgetEntity.toModel() = MoneyBudget(category, amount)

fun MoneyBudget.toEntity() = MoneyBudgetEntity(category, amount)

fun MoneyCategoryEntity.toModel(): CategoryEdit? =
    runCatching { MoneyKind.valueOf(kind) }.getOrNull()?.let { CategoryEdit(it, name, position, hidden) }

fun CategoryEdit.toEntity() = MoneyCategoryEntity(kind.name, name, position, hidden)

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
