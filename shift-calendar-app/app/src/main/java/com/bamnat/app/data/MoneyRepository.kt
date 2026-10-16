package com.bamnat.app.data

import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.toEntity
import com.bamnat.app.data.db.toModel
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** Ledger lines, booked jobs and shop sales [LEDG-01][WORK-02][WORK-03]. Everything stays on the phone. */
class MoneyRepository(db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.moneyDao()

    val entries: Flow<List<MoneyEntry>> = dao.observeEntries().map { list -> list.map { it.toModel() } }
    val jobs: Flow<List<Job>> = dao.observeJobs().map { list -> list.map { it.toModel() } }
    val sales: Flow<List<DailySales>> = dao.observeSales().map { list -> list.map { it.toModel() } }
    val book: Flow<MoneyBook> = combine(entries, jobs, sales) { e, j, s -> MoneyBook(e, j, s) }

    /** Inserts a new line or replaces the one with the same id; returns it with its id. */
    suspend fun save(entry: MoneyEntry): MoneyEntry =
        if (entry.id == 0L) entry.copy(id = dao.insertEntry(entry.toEntity(clock())))
        else entry.also { dao.restoreEntry(it.toEntity(clock())) }

    suspend fun delete(entry: MoneyEntry) = dao.deleteEntry(entry.id)

    suspend fun save(job: Job): Job =
        if (job.id == 0L) job.copy(id = dao.insertJob(job.toEntity())) else job.also { dao.restoreJob(it.toEntity()) }

    suspend fun job(id: Long): Job? = dao.job(id)?.toModel()

    suspend fun delete(job: Job) = dao.deleteJob(job.id)

    /** A day with nothing left in it is removed rather than kept as zeros. */
    suspend fun save(sales: DailySales) =
        if (sales.sales == 0L && sales.costs == 0L && sales.note.isBlank()) dao.deleteSales(sales.date.toEpochDay())
        else dao.upsertSales(sales.toEntity())

    suspend fun allJobs(): List<Job> = dao.allJobs().map { it.toModel() }

    suspend fun clearSales(date: LocalDate) = dao.deleteSales(date.toEpochDay())
}
