package com.bamnat.app.data

import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.InvestAccountEntity
import com.bamnat.app.data.db.InvestAlarmEntity
import com.bamnat.app.data.db.InvestCashEntity
import com.bamnat.app.data.db.InvestEventEntity
import com.bamnat.app.data.db.InvestJournalEntity
import com.bamnat.app.data.db.InvestQuoteEntity
import com.bamnat.app.data.db.InvestSettingEntity
import com.bamnat.app.data.db.InvestSnapshotEntity
import com.bamnat.app.data.db.InvestTradeEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * [INV-10] An investor calendar's records in the backup, under one "invest" key of that calendar's
 * part (version 18). A backup without the key leaves the phone's investor records as they are.
 */
object InvestBackup {
    class Rows(
        val accounts: List<InvestAccountEntity>, val trades: List<InvestTradeEntity>, val cash: List<InvestCashEntity>,
        val quotes: List<InvestQuoteEntity>, val journal: List<InvestJournalEntity>, val events: List<InvestEventEntity>,
        val alarms: List<InvestAlarmEntity>, val snapshots: List<InvestSnapshotEntity>, val settings: List<InvestSettingEntity>,
    ) {
        val lines: Int get() = trades.size + cash.size + journal.size
        val isEmpty: Boolean get() = accounts.isEmpty() && trades.isEmpty() && cash.isEmpty() && quotes.isEmpty() && journal.isEmpty() &&
            events.isEmpty() && alarms.isEmpty() && snapshots.isEmpty() && settings.isEmpty()
    }

    /** The calendar's investor records; null when it has none, so other kinds of calendar stay short. */
    suspend fun put(source: AppDatabase): JSONObject? {
        val dao = source.investDao()
        val rows = Rows(dao.allAccounts(), dao.allTrades(), dao.allCash(), dao.allQuotes(), dao.allJournal(), dao.allEvents(),
            dao.allAlarms(), dao.allSnapshots(), dao.allSettings())
        if (rows.isEmpty) return null
        return JSONObject()
            .put("accounts", JSONArray().apply {
                rows.accounts.forEach { a ->
                    put(JSONObject().put("id", a.id).put("name", a.name).put("type", a.type).put("broker", a.broker)
                        .putOpt("fee", a.feeRate).put("order", a.sortOrder).put("hidden", a.hidden))
                }
            })
            .put("trades", JSONArray().apply {
                rows.trades.forEach { t ->
                    put(JSONObject().put("id", t.id).put("account", t.accountId).put("day", t.epochDay).putOpt("minute", t.minuteOfDay)
                        .put("symbol", t.symbol).put("name", t.name).put("market", t.market).put("side", t.side)
                        .put("qty", t.quantity).put("price", t.price).put("currency", t.currency).put("fx", t.fxRate)
                        .put("fee", t.fee).put("tax", t.tax).put("reason", t.reason).put("emotions", t.emotions).put("tags", t.tags)
                        .putOpt("rules", t.followedRules).put("memo", t.memo).putOpt("alarm", t.alarmId).put("created", t.createdAt))
                }
            })
            .put("cash", JSONArray().apply {
                rows.cash.forEach { c ->
                    put(JSONObject().put("id", c.id).put("account", c.accountId).put("day", c.epochDay).putOpt("minute", c.minuteOfDay)
                        .put("kind", c.kind).put("amount", c.amount).put("tax", c.tax).put("symbol", c.symbol).put("memo", c.memo)
                        .put("created", c.createdAt))
                }
            })
            .put("quotes", JSONArray().apply {
                rows.quotes.forEach { q ->
                    put(JSONObject().put("key", q.symbol).put("name", q.name).put("market", q.market).put("price", q.price).put("day", q.epochDay))
                }
            })
            .put("journal", JSONArray().apply {
                rows.journal.forEach { j ->
                    put(JSONObject().put("day", j.epochDay).put("market", j.market).put("review", j.review).put("checked", j.checked)
                        .putOpt("grade", j.grade).put("updated", j.updatedAt))
                }
            })
            .put("events", JSONArray().apply {
                rows.events.forEach { e ->
                    put(JSONObject().put("id", e.id).put("day", e.epochDay).put("kind", e.kind).put("symbol", e.symbol).put("title", e.title)
                        .put("memo", e.memo).put("remind", e.remind))
                }
            })
            .put("alarms", JSONArray().apply {
                rows.alarms.forEach { a ->
                    put(JSONObject().put("id", a.id).put("kind", a.kind).put("label", a.label).put("minute", a.minuteOfDay)
                        .put("weekdays", a.weekdays).putOpt("monthDay", a.monthDay).putOpt("once", a.onceEpochDay).put("skip", a.skipHolidays)
                        .put("symbol", a.symbol).put("name", a.name).put("market", a.market).putOpt("account", a.accountId).put("side", a.side)
                        .putOpt("qty", a.quantity).putOpt("amount", a.amount).putOpt("target", a.targetPrice).put("enabled", a.enabled)
                        .put("ring", a.ring).put("created", a.createdAt))
                }
            })
            .put("snapshots", JSONArray().apply {
                rows.snapshots.forEach { s -> put(JSONObject().put("day", s.epochDay).put("value", s.value).put("principal", s.principal)) }
            })
            .put("settings", JSONObject().apply { rows.settings.forEach { put(it.key, it.value) } })
    }

    /** Read before anything changes, so a broken part leaves the phone as it was. */
    fun read(o: JSONObject): Rows = Rows(
        accounts = o.objects("accounts").map { a ->
            InvestAccountEntity(a.getLong("id"), a.optString("name"), a.optString("type", "GENERAL"), a.optString("broker"),
                a.optDoubleOrNull("fee"), a.optInt("order"), a.optBoolean("hidden"))
        },
        trades = o.objects("trades").map { t ->
            InvestTradeEntity(
                id = t.getLong("id"), accountId = t.getLong("account"), epochDay = t.getLong("day"), minuteOfDay = t.optIntOrNull("minute"),
                symbol = t.optString("symbol"), name = t.optString("name"), market = t.optString("market", "KR_STOCK"), side = t.optString("side", "BUY"),
                quantity = t.getDouble("qty"), price = t.getDouble("price"), currency = t.optString("currency", "KRW"), fxRate = t.optDouble("fx", 1.0),
                fee = t.optDouble("fee", 0.0), tax = t.optDouble("tax", 0.0), reason = t.optString("reason"), emotions = t.optString("emotions"),
                tags = t.optString("tags"), followedRules = t.optIntOrNull("rules"), memo = t.optString("memo"), alarmId = t.optLongOrNull("alarm"),
                createdAt = t.optLong("created"),
            )
        },
        cash = o.objects("cash").map { c ->
            InvestCashEntity(c.getLong("id"), c.getLong("account"), c.getLong("day"), c.optIntOrNull("minute"), c.optString("kind", "DEPOSIT"),
                c.getLong("amount"), c.optLong("tax"), c.optString("symbol"), c.optString("memo"), c.optLong("created"))
        },
        quotes = o.objects("quotes").map { q ->
            InvestQuoteEntity(q.getString("key"), q.optString("name"), q.optString("market", "KR_STOCK"), q.getDouble("price"), q.getLong("day"))
        },
        journal = o.objects("journal").map { j ->
            InvestJournalEntity(j.getLong("day"), j.optString("market"), j.optString("review"), j.optString("checked"),
                if (j.has("grade") && !j.isNull("grade")) j.getString("grade") else null, j.optLong("updated"))
        },
        events = o.objects("events").map { e ->
            InvestEventEntity(e.getLong("id"), e.getLong("day"), e.optString("kind", "OTHER"), e.optString("symbol"), e.optString("title"),
                e.optString("memo"), e.optBoolean("remind", true))
        },
        alarms = o.objects("alarms").map { a ->
            InvestAlarmEntity(
                id = a.getLong("id"), kind = a.optString("kind", "CUSTOM"), label = a.optString("label"), minuteOfDay = a.getInt("minute"),
                weekdays = a.optString("weekdays"), monthDay = a.optIntOrNull("monthDay"), onceEpochDay = a.optLongOrNull("once"),
                skipHolidays = a.optBoolean("skip", true), symbol = a.optString("symbol"), name = a.optString("name"),
                market = a.optString("market", "KR_STOCK"), accountId = a.optLongOrNull("account"), side = a.optString("side", "BUY"),
                quantity = a.optDoubleOrNull("qty"), amount = a.optLongOrNull("amount"), targetPrice = a.optDoubleOrNull("target"),
                enabled = a.optBoolean("enabled", true), ring = a.optBoolean("ring"), createdAt = a.optLong("created"),
            )
        },
        snapshots = o.objects("snapshots").map { s -> InvestSnapshotEntity(s.getLong("day"), s.getLong("value"), s.getLong("principal")) },
        settings = o.optJSONObject("settings")?.let { s -> s.keys().asSequence().map { k -> InvestSettingEntity(k, s.optString(k)) }.toList() }.orEmpty(),
    )

    /** Replaces the calendar's investor records with the backup's; call inside its transaction. */
    suspend fun write(target: AppDatabase, rows: Rows) {
        val dao = target.investDao()
        dao.clearAccounts(); dao.restoreAccounts(rows.accounts)
        dao.clearTrades(); dao.restoreTrades(rows.trades)
        dao.clearCash(); dao.restoreCash(rows.cash)
        dao.clearQuotes(); dao.restoreQuotes(rows.quotes)
        dao.clearJournal(); dao.restoreJournal(rows.journal)
        dao.clearEvents(); dao.restoreEvents(rows.events)
        dao.clearAlarms(); dao.restoreAlarms(rows.alarms)
        dao.clearSnapshots(); dao.restoreSnapshots(rows.snapshots)
        dao.clearSettings(); dao.restoreSettings(rows.settings)
    }

    private fun JSONObject.objects(key: String): List<JSONObject> =
        optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

    private fun JSONObject.optIntOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
    private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)
}
