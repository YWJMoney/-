package com.bamnat.app.data

import androidx.room.withTransaction
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.InvestSettingEntity
import com.bamnat.app.data.db.toEntity
import com.bamnat.app.data.db.toModel
import com.bamnat.core.invest.AccountType
import com.bamnat.core.invest.CashFlow
import com.bamnat.core.invest.InvestAccount
import com.bamnat.core.invest.InvestAlarm
import com.bamnat.core.invest.InvestBook
import com.bamnat.core.invest.InvestEvent
import com.bamnat.core.invest.InvestSettings
import com.bamnat.core.invest.JournalEntry
import com.bamnat.core.invest.Quote
import com.bamnat.core.invest.ReturnMethod
import com.bamnat.core.invest.Snapshot
import com.bamnat.core.invest.Trade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.WeakHashMap

/**
 * [INV-01..INV-21] One investor calendar's records, in that calendar's own database: the main
 * calendar's (bamnat.db) or an added one's (calendar_<id>.db). Everything stays on the phone.
 */
class InvestRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.investDao()

    private val records = combine(
        dao.observeAccounts(), dao.observeTrades(), dao.observeCash(), dao.observeQuotes(), dao.observeJournal(),
    ) { a, t, c, q, j ->
        InvestBook(a.map { it.toModel() }, t.map { it.toModel() }, c.map { it.toModel() }, q.map { it.toModel() }) to j
    }

    /** [STAB-01] Rows become the book off the main thread. */
    val book: Flow<InvestBook> = combine(
        records, dao.observeEvents(), dao.observeAlarms(), dao.observeSnapshots(), dao.observeSettings(),
    ) { (b, j), e, al, s, st ->
        val settings = SettingsCodec.decode(st.associate { it.key to it.value })
        b.copy(journal = j.map { it.toModel() }, events = e.map { it.toModel() }, alarms = al.map { it.toModel() },
            snapshots = s.map { it.toModel() }, settings = settings)
    }.flowOn(Dispatchers.Default)

    val alarms: Flow<List<InvestAlarm>> = dao.observeAlarms().map { l -> l.map { it.toModel() } }
    val events: Flow<List<InvestEvent>> = dao.observeEvents().map { l -> l.map { it.toModel() } }

    suspend fun now(): InvestBook = book.first()

    /** [INV-02] The account a first record goes into when the user has made none: "일반 계좌". */
    suspend fun ensureAccount(): Long = db.withTransaction {
        dao.allAccounts().firstOrNull { !it.hidden }?.id ?: dao.upsertAccount(InvestAccount(name = AccountType.GENERAL.label).toEntity())
    }

    suspend fun saveAccount(a: InvestAccount): Long = dao.upsertAccount(a.toEntity()).let { if (a.id != 0L) a.id else it }

    /** An account with records is hidden, so its history stays in the totals; an empty one goes. */
    suspend fun deleteAccount(id: Long) = db.withTransaction {
        val used = dao.allTrades().any { it.accountId == id } || dao.allCash().any { it.accountId == id }
        if (used) dao.allAccounts().firstOrNull { it.id == id }?.let { dao.upsertAccount(it.copy(hidden = true)) } else dao.deleteAccount(id)
    }

    suspend fun saveTrade(t: Trade): Long {
        val id = dao.upsertTrade(t.toEntity(clock())).let { if (t.id != 0L) t.id else it }
        setLastAccount(t.accountId)
        return id
    }

    suspend fun deleteTrade(id: Long) = dao.deleteTrade(id)

    suspend fun saveCash(c: CashFlow): Long {
        val id = dao.upsertCash(c.toEntity(clock())).let { if (c.id != 0L) c.id else it }
        setLastAccount(c.accountId)
        return id
    }

    suspend fun deleteCash(id: Long) = dao.deleteCash(id)

    /** [INV-04] Several prices typed at once; a blank one removes that holding's price. */
    suspend fun saveQuotes(quotes: List<Quote>, removed: List<String> = emptyList()) = db.withTransaction {
        quotes.forEach { dao.upsertQuote(it.toEntity()) }
        removed.forEach { dao.deleteQuote(it) }
    }

    suspend fun saveJournal(j: JournalEntry) = if (j.isEmpty) dao.deleteJournal(j.date.toEpochDay()) else dao.upsertJournal(j.toEntity(clock()))

    suspend fun saveEvent(e: InvestEvent): Long = dao.upsertEvent(e.toEntity()).let { if (e.id != 0L) e.id else it }
    suspend fun deleteEvent(id: Long) = dao.deleteEvent(id)

    suspend fun saveAlarm(a: InvestAlarm): Long = dao.upsertAlarm(a.toEntity(clock())).let { if (a.id != 0L) a.id else it }
    suspend fun deleteAlarm(id: Long) = dao.deleteAlarm(id)

    /** [INV-21] Keeps today's value, replacing an earlier one from the same day. */
    suspend fun saveSnapshot(s: Snapshot) = dao.upsertSnapshot(s.toEntity())

    suspend fun saveSettings(change: (InvestSettings) -> InvestSettings) = db.withTransaction {
        val before = SettingsCodec.decode(dao.allSettings().associate { it.key to it.value })
        val after = change(before)
        SettingsCodec.encode(after).forEach { (k, v) -> dao.upsertSetting(InvestSettingEntity(k, v)) }
    }

    private suspend fun setLastAccount(id: Long) {
        dao.upsertSetting(InvestSettingEntity(SettingsCodec.LAST_ACCOUNT, id.toString()))
    }

    /** [INV-11] Settings as key and text, so a key this version does not know is simply kept aside. */
    object SettingsCodec {
        const val LAST_ACCOUNT = "lastAccount"

        fun encode(s: InvestSettings): Map<String, String> = mapOf(
            "goalAmount" to (s.goalAmount?.toString() ?: ""),
            "goalReturn" to (s.goalReturnPercent?.toString() ?: ""),
            "goalDate" to (s.goalDate?.toString() ?: ""),
            "monthlyAdd" to (s.monthlyAdd?.toString() ?: ""),
            // One rule per line; a rule never holds a line break.
            "principles" to s.principles.joinToString("\n") { it.replace('\n', ' ') },
            "usdKrw" to s.usdKrw.toString(),
            "isaLimit" to s.isaLimit.toString(),
            "returnMethod" to s.returnMethod.name,
            "hideAmounts" to s.hideAmounts.toString(),
            LAST_ACCOUNT to (s.lastAccountId?.toString() ?: ""),
        )

        fun decode(m: Map<String, String>): InvestSettings {
            val d = InvestSettings()
            return InvestSettings(
                goalAmount = m["goalAmount"]?.toLongOrNull()?.takeIf { it > 0 },
                goalReturnPercent = m["goalReturn"]?.toDoubleOrNull()?.takeIf { it > 0 },
                goalDate = m["goalDate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                monthlyAdd = m["monthlyAdd"]?.toLongOrNull()?.takeIf { it > 0 },
                principles = m["principles"]?.let { p -> p.split('\n').map { it.trim() }.filter { it.isNotEmpty() } } ?: d.principles,
                usdKrw = m["usdKrw"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: d.usdKrw,
                isaLimit = m["isaLimit"]?.toLongOrNull()?.takeIf { it >= 0 } ?: d.isaLimit,
                returnMethod = ReturnMethod.entries.firstOrNull { it.name == m["returnMethod"] } ?: d.returnMethod,
                hideAmounts = m["hideAmounts"] == "true",
                lastAccountId = m[LAST_ACCOUNT]?.toLongOrNull(),
            )
        }
    }

    companion object {
        private val opened = WeakHashMap<AppDatabase, InvestRepository>()

        /** One repository per open database, so every screen of a calendar reads the same flows. */
        @Synchronized
        fun of(db: AppDatabase): InvestRepository = opened.getOrPut(db) { InvestRepository(db) }
    }
}
