package com.bamnat.app.data

import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.CategoryRuleEntity
import com.bamnat.app.data.db.toEntity
import com.bamnat.app.data.db.toModel
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import com.bamnat.core.money.PaymentParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A notification text the app kept because it could not write it down alone [LEDG-02]. */
data class Capture(val id: Long, val postedAt: Long, val app: String, val text: String, val reason: String)

/**
 * Ledger lines, booked jobs, shop sales, fixed costs and learned categories
 * [LEDG-01][WORK-02][WORK-03][LEDG-04][LEDG-05]. Everything stays on the phone.
 */
class MoneyRepository(
    db: AppDatabase,
    private val kept: CaptureStore = CaptureStore(null),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.moneyDao()

    val entries: Flow<List<MoneyEntry>> = dao.observeEntries().map { list -> list.map { it.toModel() } }
    val jobs: Flow<List<Job>> = dao.observeJobs().map { list -> list.map { it.toModel() } }
    val sales: Flow<List<DailySales>> = dao.observeSales().map { list -> list.map { it.toModel() } }
    val fixed: Flow<List<FixedCost>> = dao.observeFixed().map { list -> list.map { it.toModel() } }
    val rules: Flow<Map<String, String>> = dao.observeRules().map { list -> list.associate { it.merchant to it.category } }
    val captures: Flow<List<Capture>> = kept.captures
    val book: Flow<MoneyBook> = combine(entries, jobs, sales, fixed) { e, j, s, f -> MoneyBook(e, j, s, f) }

    /**
     * Inserts a new line or replaces the one with the same id; returns it with its id. When the user
     * moves a place to another category, that place keeps the new category from now on [LEDG-04].
     */
    suspend fun save(entry: MoneyEntry): MoneyEntry {
        if (entry.id == 0L) return entry.copy(id = dao.insertEntry(entry.toEntity(clock())))
        val before = dao.entry(entry.id)?.toModel()
        if (before != null && entry.merchant.isNotBlank() && entry.kind == MoneyKind.EXPENSE && before.category != entry.category) {
            dao.upsertRule(CategoryRuleEntity(entry.merchant, entry.category))
        }
        return entry.also { dao.restoreEntry(it.toEntity(clock())) }
    }

    suspend fun delete(entry: MoneyEntry) = dao.deleteEntry(entry.id)

    suspend fun markAllReviewed() = dao.markAllReviewed()

    suspend fun save(job: Job): Job =
        if (job.id == 0L) job.copy(id = dao.insertJob(job.toEntity())) else job.also { dao.restoreJob(it.toEntity()) }

    suspend fun job(id: Long): Job? = dao.job(id)?.toModel()

    suspend fun delete(job: Job) = dao.deleteJob(job.id)

    /** A day with nothing left in it is removed rather than kept as zeros. */
    suspend fun save(sales: DailySales) =
        if (sales.sales == 0L && sales.costs == 0L && sales.note.isBlank()) dao.deleteSales(sales.date.toEpochDay())
        else dao.upsertSales(sales.toEntity())

    suspend fun allJobs(): List<Job> = dao.allJobs().map { it.toModel() }

    suspend fun allFixed(): List<FixedCost> = dao.allFixed().map { it.toModel() }

    suspend fun allEntries(): List<MoneyEntry> = dao.allEntries().map { it.toModel() }

    suspend fun clearSales(date: LocalDate) = dao.deleteSales(date.toEpochDay())

    suspend fun save(cost: FixedCost): FixedCost =
        if (cost.id == 0L) cost.copy(id = dao.insertFixed(cost.toEntity())) else cost.also { dao.restoreFixed(it.toEntity()) }

    suspend fun delete(cost: FixedCost) = dao.deleteFixed(cost.id)

    suspend fun rules(): Map<String, String> = dao.allRules().associate { it.merchant to it.category }

    /** Lines from the last month, enough to find a duplicate or the payment a cancellation undoes. */
    suspend fun recent(date: LocalDate): List<MoneyEntry> = dao.entriesFrom(date.minusDays(31).toEpochDay()).map { it.toModel() }

    enum class Outcome { SAVED, DUPLICATE, KEPT, IGNORED }

    /**
     * [LEDG-02] One payment notification. A clear one becomes a ledger line marked unseen; a second
     * copy of the same payment is dropped; one the parser cannot finish is kept for the user (30 days).
     * Texts that are not payments are never stored.
     */
    suspend fun capture(text: String, app: String, postedAt: Long): Outcome {
        kept.purge(postedAt - KEEP_MILLIS)
        if (!PaymentParser.looksFinancial(text)) return Outcome.IGNORED
        val arrived = LocalDateTime.ofInstant(Instant.ofEpochMilli(postedAt), ZoneId.systemDefault())
        val result = PaymentParser.parse(text, arrived, rules())
        val parsed = (result as? PaymentParser.Result.Ok)?.parsed
        if (parsed == null || !parsed.confident) {
            if (kept.has(text)) return Outcome.DUPLICATE
            val reason = when {
                parsed?.needsWon == true -> "해외 결제예요. 원화 금액을 적어 주세요"
                result is PaymentParser.Result.Fail -> result.reason
                else -> "어디에 쓴 돈인지 못 읽었어요"
            }
            if (result is PaymentParser.Result.Fail && result.reason.contains("거절")) return Outcome.IGNORED
            kept.add(postedAt, app, text, reason)
            return Outcome.KEPT
        }
        val day = parsed.date ?: arrived.toLocalDate()
        val recent = recent(day)
        if (PaymentParser.findDuplicate(parsed, day, recent) != null) return Outcome.DUPLICATE
        val category = if (parsed.type == PaymentParser.Type.CANCEL) {
            PaymentParser.findCancelTarget(parsed, day, recent)?.category ?: parsed.category
        } else parsed.category
        PaymentParser.toEntry(parsed, arrived.toLocalDate(), MoneySource.NOTIFICATION, category)?.let { save(it) }
        return Outcome.SAVED
    }

    suspend fun dismissCapture(id: Long) = kept.delete(id)

    suspend fun clearCaptures() = kept.clear()

    companion object {
        const val KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
