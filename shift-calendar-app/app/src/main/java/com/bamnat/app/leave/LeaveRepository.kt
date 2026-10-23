package com.bamnat.app.leave

import com.bamnat.app.data.ScheduleRepository
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.toEntity
import com.bamnat.app.data.db.toModel
import com.bamnat.app.pay.PayStore
import com.bamnat.core.leave.EntryKind
import com.bamnat.core.leave.LeaveBasis
import com.bamnat.core.leave.LeavePromotion
import com.bamnat.core.leave.LeaveSources
import com.bamnat.core.leave.LeaveSummary
import com.bamnat.core.leave.LedgerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * [LEAVE-01] One calendar's 연차 원장: the stored rows in its own database (version 19), and the ledger worked out
 * with its schedule and pay setup ([LeaveSources]).
 */
class LeaveRepository(
    private val db: AppDatabase,
    private val schedule: ScheduleRepository,
    private val pay: PayStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val dao = db.leaveLedgerDao()

    val entries: Flow<List<LedgerEntry>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toModel() } }

    /** The ledger on today, following the calendar, the pay setup and the stored rows. */
    val summary: Flow<LeaveSummary> = combine(entries, schedule.snapshot, pay.profile) { stored, snap, profile ->
        LeaveSources.summary(today(), profile, snap.entries, snap.resolver::resolve, stored)
    }.onStart { moveOldSettings() }.flowOn(Dispatchers.Default)

    /** [summary] read once, e.g. by a notice. */
    suspend fun now(): LeaveSummary = withContext(Dispatchers.Default) {
        moveOldSettings()
        val snap = schedule.snapshot.first()
        LeaveSources.summary(today(), pay.profile.value, snap.entries, snap.resolver::resolve, all())
    }

    suspend fun all(): List<LedgerEntry> = dao.all().mapNotNull { it.toModel() }

    suspend fun add(entry: LedgerEntry): Long = dao.insert(entry.copy(id = 0).toEntity(clock()))

    suspend fun update(entry: LedgerEntry) = dao.update(entry.toEntity(clock()))

    suspend fun delete(id: Long) = dao.delete(id)

    private val notices = db.leaveNoticeDao()

    /** [LEAVE-11] What the worker kept of the company's 사용 촉진 (제61조): the notices and plans, by lapse date. */
    val promotion: Flow<List<LeavePromotion.Record>> = notices.observeAll().map { rows -> rows.mapNotNull { it.toModel() } }

    suspend fun addNotice(record: LeavePromotion.Record): Long = notices.insert(record.copy(id = 0).toEntity(clock()))

    suspend fun deleteNotice(id: Long) = notices.delete(id)

    /**
     * [LEAVE-01] Up to 1.21 the 연차 of the year and the 이월 were two numbers in the pay setup, counted from 1월 1일
     * to 12월 31일. They become the ledger's rows for that year and leave the pay setup, so this runs again only
     * when a 1.21 backup or company file brings them back. The setup then counts by 회계연도 from January so the
     * app's own lots line up with them.
     */
    suspend fun moveOldSettings() = withContext(Dispatchers.IO) { moving.withLock {
        val rules = pay.profile.value?.rules ?: return@withLock
        val granted = rules.annualLeaveDays ?: return@withLock
        val year = today().year
        val start = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year, 12, 31)
        if (dao.all().none { it.kind == EntryKind.GRANT.name && it.yearStartEpochDay == start.toEpochDay() }) {
            dao.insert(LedgerEntry(kind = EntryKind.GRANT, date = start, days = granted, expires = end, yearStart = start,
                note = "1.21까지 급여 설정에 넣은 올해 연차").toEntity(clock()))
            if (rules.annualLeaveCarry > 0) {
                dao.insert(LedgerEntry(kind = EntryKind.CARRY, date = start, days = rules.annualLeaveCarry, expires = end,
                    note = "1.21까지 급여 설정에 넣은 이월").toEntity(clock()))
            }
        }
        pay.update { p ->
            p.copy(rules = p.rules.copy(annualLeaveDays = null, annualLeaveCarry = 0.0,
                leave = p.rules.leave.copy(basis = LeaveBasis.FISCAL, fiscalStartMonth = 1)))
        }
    } }

    private val moving = Mutex()
}
