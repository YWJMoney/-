package com.bamnat.core.invest

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import com.bamnat.core.time.Today

/**
 * [INV-30][INV-32] Trades found in notifications, waiting for the user's 확인 (결정 D37): only the app,
 * side, stock or coin, quantity, price, market and time are kept, never the rest of the text. They sit in
 * the calendar's investor settings as one line each (no new table, 결정 D40), so a backup carries them.
 */
object TradeAlerts {
    /** The investor settings key holding the waiting alerts. */
    const val KEY = "pendingAlerts"
    /** The key for 알림 오면 바로 적기; off means each one waits for 확인. */
    const val AUTO_KEY = "alertAuto"
    /** At most this many wait; the oldest goes first. */
    const val MAX = 30
    /** The same fill posted again (a stacked or repeated notification) within this is one trade. */
    const val SAME_WITHIN_MILLIS = 10L * 60 * 1000
    /** A tag on the saved trade naming the app it came from: "알림:업비트". */
    const val TAG_PREFIX = "알림:"

    data class Pending(
        /** The notification's time, which is also its id among the waiting ones. */
        val at: Long,
        val app: String,
        val side: Side,
        val symbol: String,
        val name: String,
        val quantity: Double,
        val price: Double,
        val market: Market,
    ) {
        val title: String get() = name.ifBlank { symbol }
        val tag: String get() = TAG_PREFIX + app.take(20)

        fun day(zone: ZoneId = Today.zone()): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        fun minute(zone: ZoneId = Today.zone()): Int = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone).let { it.hour * 60 + it.minute }

        /** The trade it becomes once confirmed: fees left at zero (the sheet suggests them when opened). */
        fun toTrade(accountId: Long, fxRate: Double, zone: ZoneId = Today.zone()) = Trade(
            accountId = accountId, date = day(zone), minute = minute(zone), symbol = symbol, name = name, market = market,
            side = side, quantity = quantity, price = price, fxRate = if (market.foreign) fxRate else 1.0, tags = listOf(tag),
        )

        fun toDraft(accountId: Long?, zone: ZoneId = Today.zone()) = TradeDraft(
            symbol = symbol, name = name, market = market, side = side, quantity = quantity, price = price.takeIf { it > 0 },
            accountId = accountId, date = day(zone), tags = listOf(tag), alertAt = at,
        )

        fun sameFill(o: Pending) = side == o.side && symbol == o.symbol && name == o.name && quantity == o.quantity &&
            price == o.price && kotlin.math.abs(at - o.at) <= SAME_WITHIN_MILLIS
    }

    fun of(a: TradeAlertParser.Alert, app: String, at: Long) =
        Pending(at, app, a.side, a.symbol, a.name, a.quantity, a.price, a.market)

    /** The waiting list with [p] added, unless the same fill is there already; null when nothing changes. */
    fun add(list: List<Pending>, p: Pending): List<Pending>? =
        if (list.any { it.sameFill(p) }) null else (list + p).sortedBy { it.at }.takeLast(MAX)

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    fun encode(list: List<Pending>): String = list.joinToString("\n") { p ->
        listOf(p.at.toString(), clean(p.app), p.side.name, clean(p.symbol), clean(p.name), p.quantity.toString(), p.price.toString(), p.market.name)
            .joinToString("\t")
    }

    /** A line this version cannot read is left out, never an error. */
    fun decode(s: String?): List<Pending> = s.orEmpty().split('\n').mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 8) return@mapNotNull null
        Pending(
            at = f[0].toLongOrNull() ?: return@mapNotNull null,
            app = f[1],
            side = Side.entries.firstOrNull { it.name == f[2] } ?: return@mapNotNull null,
            symbol = f[3], name = f[4],
            quantity = f[5].toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null,
            price = f[6].toDoubleOrNull() ?: 0.0,
            market = Market.entries.firstOrNull { it.name == f[7] } ?: Market.OTHER,
        )
    }

    /** The app a saved trade came from, read back from its tag; null when typed by hand. */
    fun sourceOf(t: Trade): String? = t.tags.firstOrNull { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)

    /** A short word for the kind of holding, shown as a badge on each trade: 코인, 주식, ETF, 해외주식. */
    fun badge(m: Market): String? = when (m) {
        Market.CRYPTO -> "코인"
        Market.KR_STOCK -> "주식"
        Market.KR_ETF -> "ETF"
        Market.US_STOCK -> "해외주식"
        Market.OTHER -> null
    }
}
