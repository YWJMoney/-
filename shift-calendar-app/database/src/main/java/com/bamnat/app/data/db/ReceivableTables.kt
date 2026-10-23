package com.bamnat.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.bamnat.core.money.Receivable
import com.bamnat.core.money.ReceivableKind
import com.bamnat.core.money.ReceivablePayment
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** [RECV-01] Money others owe the user; version 13. */
@Entity(tableName = "receivables")
data class ReceivableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val who: String,
    val title: String,
    val amount: Long,
    val epochDay: Long,
    val dueEpochDay: Long?,
    /** [ReceivableKind.name]. */
    val kind: String,
    val note: String,
    val countAsIncome: Boolean,
    val closedEpochDay: Long?,
    val createdAt: Long,
)

/** [RECV-01] Money that came back for a receivable, in parts; version 13. */
@Entity(tableName = "receivable_payments", indices = [Index("receivableId")])
data class ReceivablePaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receivableId: Long,
    val epochDay: Long,
    val amount: Long,
    /** The ledger line written for it, when it counted as income. */
    val entryId: Long?,
)

/**
 * [IMPORT-01] An event brought in from the phone's calendar, so it is never brought in twice;
 * [memoId] is the memo it became, if any. Version 13.
 */
@Entity(tableName = "imported_events")
data class ImportedEventEntity(
    @PrimaryKey val eventKey: String,
    val epochDay: Long,
    val memoId: Long?,
    val importedAt: Long,
)

@Dao
interface ReceivableDao {
    @Query("SELECT * FROM receivables ORDER BY epochDay, id")
    fun observe(): Flow<List<ReceivableEntity>>

    @Query("SELECT * FROM receivable_payments ORDER BY epochDay, id")
    fun observePayments(): Flow<List<ReceivablePaymentEntity>>

    @Query("SELECT * FROM receivables")
    suspend fun all(): List<ReceivableEntity>

    @Query("SELECT * FROM receivable_payments")
    suspend fun allPayments(): List<ReceivablePaymentEntity>

    @Query("SELECT * FROM receivable_payments WHERE receivableId = :id")
    suspend fun paymentsOf(id: Long): List<ReceivablePaymentEntity>

    @Insert
    suspend fun insert(e: ReceivableEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(e: ReceivableEntity)

    @Insert
    suspend fun insertPayment(e: ReceivablePaymentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restorePayment(e: ReceivablePaymentEntity)

    @Query("DELETE FROM receivables WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM receivable_payments WHERE receivableId = :id")
    suspend fun deletePaymentsOf(id: Long)

    @Query("DELETE FROM receivable_payments WHERE id = :id")
    suspend fun deletePayment(id: Long)

    @Query("DELETE FROM receivables")
    suspend fun clear()

    @Query("DELETE FROM receivable_payments")
    suspend fun clearPayments()
}

@Dao
interface ImportedEventDao {
    @Query("SELECT eventKey FROM imported_events")
    suspend fun keys(): List<String>

    @Query("SELECT eventKey FROM imported_events")
    fun observeKeys(): Flow<List<String>>

    @Upsert
    suspend fun upsert(e: ImportedEventEntity)

    @Query("DELETE FROM imported_events WHERE eventKey IN (:keys)")
    suspend fun delete(keys: List<String>)
}

fun ReceivableEntity.toModel(payments: List<ReceivablePaymentEntity>) = Receivable(
    id = id, who = who, title = title, amount = amount, date = LocalDate.ofEpochDay(epochDay),
    due = dueEpochDay?.let(LocalDate::ofEpochDay),
    kind = runCatching { ReceivableKind.valueOf(kind) }.getOrDefault(ReceivableKind.OTHER),
    note = note, countAsIncome = countAsIncome, closedOn = closedEpochDay?.let(LocalDate::ofEpochDay),
    payments = payments.map { it.toModel() }.sortedWith(compareBy({ it.date }, { it.id })),
)

fun Receivable.toEntity(createdAt: Long = System.currentTimeMillis()) = ReceivableEntity(
    id = id, who = who.trim(), title = title.trim(), amount = amount, epochDay = date.toEpochDay(), dueEpochDay = due?.toEpochDay(),
    kind = kind.name, note = note, countAsIncome = countAsIncome, closedEpochDay = closedOn?.toEpochDay(), createdAt = createdAt,
)

fun ReceivablePaymentEntity.toModel() = ReceivablePayment(id, receivableId, LocalDate.ofEpochDay(epochDay), amount, entryId)

fun ReceivablePayment.toEntity() = ReceivablePaymentEntity(id, receivableId, date.toEpochDay(), amount, entryId)
