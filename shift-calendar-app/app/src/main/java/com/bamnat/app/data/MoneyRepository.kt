package com.bamnat.app.data

import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.CategoryRuleEntity
import com.bamnat.app.data.db.decodeParts
import com.bamnat.app.data.db.encodeParts
import com.bamnat.app.data.db.toEntity
import com.bamnat.app.data.db.toModel
import com.bamnat.core.money.CategoryEdit
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyAccount
import com.bamnat.core.money.MoneyAccounts
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyBudget
import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import com.bamnat.core.money.PaymentParser
import com.bamnat.core.money.Receivable
import com.bamnat.core.money.ReceivablePayment
import com.bamnat.core.money.Receivables
import com.bamnat.core.money.Recurring
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    val entries: Flow<List<MoneyEntry>> = dao.observeEntries().map { list -> list.map { it.toModel() } }.flowOn(Dispatchers.Default)
    val jobs: Flow<List<Job>> = dao.observeJobs().map { list -> list.map { it.toModel() } }
    val sales: Flow<List<DailySales>> = dao.observeSales().map { list -> list.map { it.toModel() } }
    val fixed: Flow<List<FixedCost>> = dao.observeFixed().map { list -> list.map { it.toModel() } }
    val rules: Flow<Map<String, String>> = dao.observeRules().map { list -> list.associate { it.merchant to it.category } }
    val captures: Flow<List<Capture>> = kept.captures
    private val owedDao = db.receivableDao()
    /** [RECV-01] Money others owe the user, each with what came back for it. */
    val receivables: Flow<List<Receivable>> = combine(owedDao.observe(), owedDao.observePayments()) { list, paid ->
        val byId = paid.groupBy { it.receivableId }
        list.map { it.toModel(byId[it.id].orEmpty()) }
    }
    /** [LEDG-08] Cash, bank accounts and cards, in the user's order. */
    val accounts: Flow<List<MoneyAccount>> = dao.observeAccounts().map { list -> list.map { it.toModel() } }
    /** [LEDG-06] Monthly budgets. */
    val budgets: Flow<List<MoneyBudget>> = dao.observeBudgets().map { list -> list.map { it.toModel() } }
    /** [LEDG-14] Categories the user added, hid or moved. */
    val categories: Flow<List<CategoryEdit>> = dao.observeCategories().map { list -> list.mapNotNull { it.toModel() } }
    private val plans: Flow<Triple<List<MoneyAccount>, List<MoneyBudget>, List<CategoryEdit>>> =
        combine(accounts, budgets, categories) { a, b, c -> Triple(a, b, c) }
    /** [STAB-01] Every ledger row is turned into a line off the main thread. */
    val book: Flow<MoneyBook> = combine(
        combine(entries, jobs, sales, fixed, receivables) { e, j, s, f, r -> MoneyBook(e, j, s, f, r) }, plans,
    ) { b, (a, bu, c) -> b.copy(accounts = a, budgets = bu, categories = c) }.flowOn(Dispatchers.Default)

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

    /** [LEDG-07] Several lines changed at once (고르기), saved as they are. */
    suspend fun saveAll(list: List<MoneyEntry>) {
        if (list.isNotEmpty()) dao.restoreEntries(list.map { it.toEntity(clock()) })
    }

    /** [LEDG-07] Several lines removed at once; [saveAll] with the same lines puts them back. */
    suspend fun deleteAll(list: List<MoneyEntry>) {
        if (list.isNotEmpty()) dao.deleteEntries(list.map { it.id })
    }

    suspend fun allAccounts(): List<MoneyAccount> = dao.allAccounts().map { it.toModel() }

    suspend fun allBudgets(): List<MoneyBudget> = dao.allBudgets().map { it.toModel() }

    suspend fun allCategories(): List<CategoryEdit> = dao.allCategories().mapNotNull { it.toModel() }

    /** [LEDG-08] Adds an account at the end of the list, or saves the one with the same id. */
    suspend fun save(account: MoneyAccount): MoneyAccount {
        val clean = account.copy(name = account.name.trim().take(MoneyAccount.MAX_NAME), last4 = account.last4.filter { it.isDigit() }.takeLast(4))
        if (clean.id != 0L) return clean.also { dao.restoreAccount(it.toEntity()) }
        val order = (dao.allAccounts().maxOfOrNull { it.sortOrder } ?: -1) + 1
        return clean.copy(order = order).let { it.copy(id = dao.insertAccount(it.toEntity())) }
    }

    /**
     * [LEDG-08] Removes an account. One with lines is put away instead, so its old lines keep their
     * name and balances stay right; returns true when it was really removed. Cards that paid from a
     * removed account no longer point at it.
     */
    suspend fun remove(account: MoneyAccount): Boolean {
        if (dao.linesOfAccount(account.id) > 0) {
            dao.restoreAccount(account.copy(hidden = true).toEntity())
            return false
        }
        dao.deleteAccount(account.id)
        dao.allAccounts().filter { it.payFromId == account.id }.forEach { dao.restoreAccount(it.copy(payFromId = null)) }
        return true
    }

    /** [LEDG-08] Saves the user's order of accounts. */
    suspend fun reorder(accounts: List<MoneyAccount>) =
        accounts.forEachIndexed { i, a -> if (a.order != i) dao.restoreAccount(a.copy(order = i).toEntity()) }

    /** [LEDG-06] Sets a monthly budget; 0 or less removes it. */
    suspend fun setBudget(category: String, amount: Long) =
        if (amount <= 0) dao.deleteBudget(category) else dao.upsertBudget(MoneyBudget(category, amount).toEntity())

    /** [LEDG-14] Saves the user's categories for [kind] in their order; null names are dropped. */
    suspend fun saveCategories(kind: MoneyKind, list: List<CategoryEdit>) {
        dao.clearCategories(kind.name)
        dao.insertCategories(list.filter { it.kind == kind && it.name.isNotBlank() }.map { it.toEntity() })
    }

    /**
     * [LEDG-14] Renames a category everywhere it is used: lines (split parts too), repeating
     * lines, learned places and its budget, so nothing is left under the old name.
     */
    suspend fun renameCategory(kind: MoneyKind, from: String, to: String) {
        val name = MoneyCategories.clean(to)
        if (name.isEmpty() || name == from) return
        dao.renameInEntries(kind.name, from, name)
        dao.renameInFixed(kind.name, from, name)
        if (kind == MoneyKind.EXPENSE) {
            dao.renameInRules(from, name)
            dao.allBudgets().firstOrNull { it.category == from }?.let { b ->
                dao.deleteBudget(from)
                dao.upsertBudget(b.copy(category = name))
            }
            val split = dao.entriesWithPart(from).filter { it.kind == kind.name }.mapNotNull { e ->
                val parts = decodeParts(e.parts)
                if (parts.none { it.category == from }) null
                else e.copy(parts = encodeParts(parts.map { if (it.category == from) it.copy(category = name) else it }))
            }
            if (split.isNotEmpty()) dao.restoreEntries(split)
        }
        val mine = dao.allCategories().filter { it.kind == kind.name }
        if (mine.isNotEmpty()) {
            dao.clearCategories(kind.name)
            dao.insertCategories(mine.map { if (it.name == from) it.copy(name = name) else it }.distinctBy { it.name })
        }
    }

    private val recurringLock = Mutex()

    /**
     * [LEDG-09] Writes down the repeating lines set to write themselves that came due up to
     * [today], once each, and returns them. Run when the money tab opens and when an alarm fires.
     */
    suspend fun catchUpRecurring(today: LocalDate): List<MoneyEntry> = recurringLock.withLock {
        val costs = allFixed().filter { it.auto }
        if (costs.isEmpty()) return emptyList()
        val since = today.minusDays(Recurring.CATCH_UP_DAYS + 366)
        val lines = dao.entriesFrom(since.toEpochDay()).map { it.toModel() }
        Recurring.due(costs, lines, today).map { save(it) }
    }

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

    suspend fun allReceivables(): List<Receivable> {
        val byId = owedDao.allPayments().groupBy { it.receivableId }
        return owedDao.all().map { it.toModel(byId[it.id].orEmpty()) }
    }

    /** [RECV-01] Saves what is owed; its payments are kept as they are. */
    suspend fun save(r: Receivable): Receivable =
        if (r.id == 0L) r.copy(id = owedDao.insert(r.toEntity(clock()))) else r.also { owedDao.restore(it.toEntity(clock())) }

    /** [RECV-01] Removes a receivable with its payments and the income lines they wrote. */
    suspend fun delete(r: Receivable) {
        owedDao.paymentsOf(r.id).forEach { p -> p.entryId?.let { dao.deleteEntry(it) } }
        owedDao.deletePaymentsOf(r.id)
        owedDao.delete(r.id)
    }

    /** [RECV-01] Puts a receivable back as it was, payments and income lines included (undo). */
    suspend fun restore(r: Receivable, lines: List<MoneyEntry>) {
        owedDao.restore(r.toEntity(clock()))
        lines.forEach { dao.restoreEntry(it.toEntity(clock())) }
        r.payments.forEach { owedDao.restorePayment(it.copy(receivableId = r.id).toEntity()) }
    }

    /** The income lines a receivable's payments wrote, to keep for an undo. */
    suspend fun linesOf(r: Receivable): List<MoneyEntry> = r.payments.mapNotNull { p -> p.entryId?.let { dao.entry(it)?.toModel() } }

    /**
     * [RECV-01] Money that came back: at most what is left, with an income line when the receivable
     * counts as income. Returns the payment as saved, or null when nothing was left to receive.
     */
    suspend fun receive(r: Receivable, amount: Long, date: LocalDate): ReceivablePayment? {
        val paid = Receivables.clampPayment(r, amount)
        if (paid <= 0) return null
        val entry = Receivables.incomeEntry(r, paid, date)?.let { save(it) }
        val p = ReceivablePayment(receivableId = r.id, date = date, amount = paid, entryId = entry?.id)
        return p.copy(id = owedDao.insertPayment(p.toEntity()))
    }

    /** [RECV-01] Takes a payment back, with its income line. */
    suspend fun delete(p: ReceivablePayment) {
        p.entryId?.let { dao.deleteEntry(it) }
        owedDao.deletePayment(p.id)
    }

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
    suspend fun capture(text: String, app: String, postedAt: Long, fromMessenger: Boolean = false): Outcome = captureLock.withLock {
        // One at a time: the card text and the pay-app push for one payment often land together,
        // and both must not pass the duplicate check before either is saved.
        kept.purge(postedAt - KEEP_MILLIS)
        if (!PaymentParser.looksFinancial(text)) return Outcome.IGNORED
        val arrived = LocalDateTime.ofInstant(Instant.ofEpochMilli(postedAt), ZoneId.systemDefault())
        val result = PaymentParser.parse(text, arrived, rules())
        val parsed = (result as? PaymentParser.Result.Ok)?.parsed
            // Without a time in the text, the time it arrived keeps two same-amount payments apart.
            ?.let { if (it.time == null) it.copy(time = arrived.toLocalTime().withSecond(0).withNano(0)) else it }
        // A chat message ("어제 5,000원 입금했어") is not a bank text: from a messenger only texts that
        // name a card or bank count, and nothing else from it is ever kept.
        if (fromMessenger && (parsed == null || (parsed.issuer == null && parsed.last4 == null))) return Outcome.IGNORED
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
        PaymentParser.toEntry(parsed, arrived.toLocalDate(), MoneySource.NOTIFICATION, category)
            // [LEDG-08] A card the user added picks up its payments by the last four digits.
            ?.let { e -> e.copy(accountId = MoneyAccounts.match(e.cardLast4, allAccounts())) }
            ?.let { save(it) }
        return Outcome.SAVED
    }

    suspend fun dismissCapture(id: Long) = kept.delete(id)

    suspend fun clearCaptures() = kept.clear()

    private val captureLock = Mutex()

    /** Clears kept texts past their 30 days even when no new notification comes. */
    suspend fun purgeCaptures(now: Long = System.currentTimeMillis()) = kept.purge(now - KEEP_MILLIS)

    companion object {
        const val KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
