package com.bamnat.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.bamnat.core.invest.AccountType
import com.bamnat.core.invest.AlarmKind
import com.bamnat.core.invest.CashFlow
import com.bamnat.core.invest.CashKind
import com.bamnat.core.invest.DayGrade
import com.bamnat.core.invest.Emotion
import com.bamnat.core.invest.EventKind
import com.bamnat.core.invest.InvestAccount
import com.bamnat.core.invest.InvestAlarm
import com.bamnat.core.invest.InvestEvent
import com.bamnat.core.invest.JournalEntry
import com.bamnat.core.invest.JournalRules
import com.bamnat.core.invest.Market
import com.bamnat.core.invest.Quote
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.Snapshot
import com.bamnat.core.invest.Trade
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalDate

/*
 * [INV-01..INV-21] The investor calendar's tables, version 18. Every calendar's database has them
 * (like the money tables); only an investor calendar fills them. Enums are stored by name, so a
 * name this version does not know reads as the default instead of failing.
 */

@Entity(tableName = "invest_accounts")
data class InvestAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val broker: String,
    val feeRate: Double?,
    val sortOrder: Int,
    val hidden: Boolean,
)

@Entity(tableName = "invest_trades", indices = [Index("epochDay")])
data class InvestTradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val epochDay: Long,
    val minuteOfDay: Int?,
    val symbol: String,
    val name: String,
    val market: String,
    val side: String,
    val quantity: Double,
    val price: Double,
    val currency: String,
    val fxRate: Double,
    val fee: Double,
    val tax: Double,
    val reason: String,
    val emotions: String,
    val tags: String,
    /** 1 kept, 0 broken, null not said. */
    val followedRules: Int?,
    val memo: String,
    val alarmId: Long?,
    val createdAt: Long,
)

@Entity(tableName = "invest_cash", indices = [Index("epochDay")])
data class InvestCashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val epochDay: Long,
    val minuteOfDay: Int?,
    val kind: String,
    val amount: Long,
    val tax: Long,
    val symbol: String,
    val memo: String,
    val createdAt: Long,
)

@Entity(tableName = "invest_quotes")
data class InvestQuoteEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val market: String,
    val price: Double,
    val epochDay: Long,
)

@Entity(tableName = "invest_journal")
data class InvestJournalEntity(
    @PrimaryKey val epochDay: Long,
    val market: String,
    val review: String,
    /** [INV-07] The kept rules' text, each after a line break ([JournalRules]); older rows hold positions, "0,2". */
    val checked: String,
    val grade: String?,
    val updatedAt: Long,
)

@Entity(tableName = "invest_events", indices = [Index("epochDay")])
data class InvestEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val kind: String,
    val symbol: String,
    val title: String,
    val memo: String,
    val remind: Boolean,
)

@Entity(tableName = "invest_alarms")
data class InvestAlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val label: String,
    val minuteOfDay: Int,
    /** "MONDAY,FRIDAY". */
    val weekdays: String,
    val monthDay: Int?,
    val onceEpochDay: Long?,
    val skipHolidays: Boolean,
    val symbol: String,
    val name: String,
    val market: String,
    val accountId: Long?,
    val side: String,
    val quantity: Double?,
    val amount: Long?,
    val targetPrice: Double?,
    val enabled: Boolean,
    val ring: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "invest_snapshots")
data class InvestSnapshotEntity(
    @PrimaryKey val epochDay: Long,
    val value: Long,
    val principal: Long,
)

/** [INV-11] The calendar's own investor settings, one value per key. */
@Entity(tableName = "invest_settings")
data class InvestSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface InvestDao {
    @Query("SELECT * FROM invest_accounts ORDER BY sortOrder, id") fun observeAccounts(): Flow<List<InvestAccountEntity>>
    @Query("SELECT * FROM invest_trades ORDER BY epochDay, id") fun observeTrades(): Flow<List<InvestTradeEntity>>
    @Query("SELECT * FROM invest_cash ORDER BY epochDay, id") fun observeCash(): Flow<List<InvestCashEntity>>
    @Query("SELECT * FROM invest_quotes") fun observeQuotes(): Flow<List<InvestQuoteEntity>>
    @Query("SELECT * FROM invest_journal ORDER BY epochDay") fun observeJournal(): Flow<List<InvestJournalEntity>>
    @Query("SELECT * FROM invest_events ORDER BY epochDay, id") fun observeEvents(): Flow<List<InvestEventEntity>>
    @Query("SELECT * FROM invest_alarms ORDER BY minuteOfDay, id") fun observeAlarms(): Flow<List<InvestAlarmEntity>>
    @Query("SELECT * FROM invest_snapshots ORDER BY epochDay") fun observeSnapshots(): Flow<List<InvestSnapshotEntity>>
    @Query("SELECT * FROM invest_settings") fun observeSettings(): Flow<List<InvestSettingEntity>>

    @Query("SELECT * FROM invest_accounts") suspend fun allAccounts(): List<InvestAccountEntity>
    @Query("SELECT * FROM invest_trades") suspend fun allTrades(): List<InvestTradeEntity>
    @Query("SELECT * FROM invest_cash") suspend fun allCash(): List<InvestCashEntity>
    @Query("SELECT * FROM invest_quotes") suspend fun allQuotes(): List<InvestQuoteEntity>
    @Query("SELECT * FROM invest_journal") suspend fun allJournal(): List<InvestJournalEntity>
    @Query("SELECT * FROM invest_events") suspend fun allEvents(): List<InvestEventEntity>
    @Query("SELECT * FROM invest_alarms") suspend fun allAlarms(): List<InvestAlarmEntity>
    @Query("SELECT * FROM invest_snapshots") suspend fun allSnapshots(): List<InvestSnapshotEntity>
    @Query("SELECT * FROM invest_settings") suspend fun allSettings(): List<InvestSettingEntity>

    @Upsert suspend fun upsertAccount(e: InvestAccountEntity): Long
    @Upsert suspend fun upsertTrade(e: InvestTradeEntity): Long
    @Upsert suspend fun upsertCash(e: InvestCashEntity): Long
    @Upsert suspend fun upsertQuote(e: InvestQuoteEntity)
    @Upsert suspend fun upsertJournal(e: InvestJournalEntity)
    @Upsert suspend fun upsertEvent(e: InvestEventEntity): Long
    @Upsert suspend fun upsertAlarm(e: InvestAlarmEntity): Long
    @Upsert suspend fun upsertSnapshot(e: InvestSnapshotEntity)
    @Upsert suspend fun upsertSetting(e: InvestSettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreAccounts(list: List<InvestAccountEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreTrades(list: List<InvestTradeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreCash(list: List<InvestCashEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreQuotes(list: List<InvestQuoteEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreJournal(list: List<InvestJournalEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreEvents(list: List<InvestEventEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreAlarms(list: List<InvestAlarmEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreSnapshots(list: List<InvestSnapshotEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreSettings(list: List<InvestSettingEntity>)

    @Query("DELETE FROM invest_accounts WHERE id = :id") suspend fun deleteAccount(id: Long)
    @Query("DELETE FROM invest_trades WHERE id = :id") suspend fun deleteTrade(id: Long)
    @Query("DELETE FROM invest_cash WHERE id = :id") suspend fun deleteCash(id: Long)
    @Query("DELETE FROM invest_quotes WHERE symbol = :key") suspend fun deleteQuote(key: String)
    @Query("DELETE FROM invest_journal WHERE epochDay = :epochDay") suspend fun deleteJournal(epochDay: Long)
    @Query("DELETE FROM invest_events WHERE id = :id") suspend fun deleteEvent(id: Long)
    @Query("DELETE FROM invest_alarms WHERE id = :id") suspend fun deleteAlarm(id: Long)

    @Query("DELETE FROM invest_accounts") suspend fun clearAccounts()
    @Query("DELETE FROM invest_trades") suspend fun clearTrades()
    @Query("DELETE FROM invest_cash") suspend fun clearCash()
    @Query("DELETE FROM invest_quotes") suspend fun clearQuotes()
    @Query("DELETE FROM invest_journal") suspend fun clearJournal()
    @Query("DELETE FROM invest_events") suspend fun clearEvents()
    @Query("DELETE FROM invest_alarms") suspend fun clearAlarms()
    @Query("DELETE FROM invest_snapshots") suspend fun clearSnapshots()
    @Query("DELETE FROM invest_settings") suspend fun clearSettings()
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback

fun InvestAccountEntity.toModel() = InvestAccount(id, name, enumOr(type, AccountType.OTHER), broker, feeRate, sortOrder, hidden)
fun InvestAccount.toEntity() = InvestAccountEntity(id, name.trim(), type.name, broker.trim(), feeRate, sortOrder, hidden)

fun InvestTradeEntity.toModel() = Trade(
    id = id, accountId = accountId, date = LocalDate.ofEpochDay(epochDay), minute = minuteOfDay, symbol = symbol, name = name,
    market = enumOr(market, Market.OTHER), side = enumOr(side, Side.BUY), quantity = quantity, price = price, currency = currency,
    fxRate = fxRate, fee = fee, tax = tax, reason = reason, emotions = Emotion.decode(emotions),
    tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    followedRules = followedRules?.let { it == 1 }, memo = memo, alarmId = alarmId, createdAt = createdAt,
)

fun Trade.toEntity(now: Long = System.currentTimeMillis()) = InvestTradeEntity(
    id = id, accountId = accountId, epochDay = date.toEpochDay(), minuteOfDay = minute, symbol = symbol.trim(), name = name.trim(),
    market = market.name, side = side.name, quantity = quantity, price = price, currency = currency, fxRate = fxRate, fee = fee, tax = tax,
    reason = reason.trim(), emotions = Emotion.encode(emotions), tags = tags.joinToString(","),
    followedRules = followedRules?.let { if (it) 1 else 0 }, memo = memo.trim(), alarmId = alarmId,
    createdAt = if (createdAt > 0) createdAt else now,
)

fun InvestCashEntity.toModel() = CashFlow(id, accountId, LocalDate.ofEpochDay(epochDay), minuteOfDay, enumOr(kind, CashKind.DEPOSIT), amount, tax, symbol, memo, createdAt)
fun CashFlow.toEntity(now: Long = System.currentTimeMillis()) = InvestCashEntity(
    id, accountId, date.toEpochDay(), minute, kind.name, amount, tax, symbol.trim(), memo.trim(), if (createdAt > 0) createdAt else now,
)

fun InvestQuoteEntity.toModel() = Quote(symbol, price, LocalDate.ofEpochDay(epochDay), name, enumOr(market, Market.OTHER))
fun Quote.toEntity() = InvestQuoteEntity(key, name, market.name, price, date.toEpochDay())

/** [INV-07] [principles] are the rules as they stand, which an older row's positions point into. */
fun InvestJournalEntity.toModel(principles: List<String>) = JournalEntry(
    LocalDate.ofEpochDay(epochDay), market, review, JournalRules.decode(checked, principles),
    grade?.let { g -> DayGrade.entries.firstOrNull { it.name == g } }, updatedAt,
)
fun JournalEntry.toEntity(now: Long = System.currentTimeMillis()) =
    InvestJournalEntity(date.toEpochDay(), market, review, JournalRules.encode(checked), grade?.name, now)

fun InvestEventEntity.toModel() = InvestEvent(id, LocalDate.ofEpochDay(epochDay), enumOr(kind, EventKind.OTHER), symbol, title, memo, remind)
fun InvestEvent.toEntity() = InvestEventEntity(id, date.toEpochDay(), kind.name, symbol.trim(), title.trim(), memo.trim(), remind)

fun InvestAlarmEntity.toModel() = InvestAlarm(
    id = id, kind = enumOr(kind, AlarmKind.CUSTOM), label = label, minute = minuteOfDay.coerceIn(0, 24 * 60 - 1),
    weekdays = weekdays.split(',').mapNotNull { n -> DayOfWeek.entries.firstOrNull { it.name == n.trim() } }.toSet(),
    monthDay = monthDay?.coerceIn(1, 31), once = onceEpochDay?.let(LocalDate::ofEpochDay), skipHolidays = skipHolidays,
    symbol = symbol, name = name, market = enumOr(market, Market.KR_STOCK), accountId = accountId, side = enumOr(side, Side.BUY),
    quantity = quantity, amount = amount, targetPrice = targetPrice, enabled = enabled, ring = ring, createdAt = createdAt,
)
fun InvestAlarm.toEntity(now: Long = System.currentTimeMillis()) = InvestAlarmEntity(
    id = id, kind = kind.name, label = label.trim(), minuteOfDay = minute, weekdays = DayOfWeek.entries.filter { it in weekdays }.joinToString(",") { it.name },
    monthDay = monthDay, onceEpochDay = once?.toEpochDay(), skipHolidays = skipHolidays, symbol = symbol.trim(), name = name.trim(),
    market = market.name, accountId = accountId, side = side.name, quantity = quantity, amount = amount, targetPrice = targetPrice,
    enabled = enabled, ring = ring, createdAt = if (createdAt > 0) createdAt else now,
)

fun InvestSnapshotEntity.toModel() = Snapshot(LocalDate.ofEpochDay(epochDay), value, principal)
fun Snapshot.toEntity() = InvestSnapshotEntity(date.toEpochDay(), value, principal)
