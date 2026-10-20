package com.bamnat.core.invest

import java.time.DayOfWeek
import java.time.LocalDate

/*
 * [INV-01..INV-21] What an investor's calendar keeps: accounts, money put in and taken out, trades,
 * dividends, prices the user types, the day's journal, dates to watch and alarms (투자자 시장조사,
 * 2026-10-20). Nothing here is fetched: no prices, no brokers. Amounts in won are whole Longs; a
 * trade's quantity and price are Doubles, since coins come in fractions and US shares in cents.
 */

/** [INV-02] The kind of account, which decides the default fees and how its gains are taxed. */
enum class AccountType(val label: String) {
    GENERAL("일반 계좌"),
    ISA("ISA"),
    PENSION("연금저축"),
    IRP("IRP"),
    CRYPTO("코인 거래소"),
    OVERSEAS("해외 주식"),
    OTHER("기타");

    /** [INV-12] Gains inside stay untaxed until the money is drawn as a pension. */
    val taxDeferred: Boolean get() = this == PENSION || this == IRP

    /** The market a new trade in this account starts on. */
    val market: Market get() = when (this) {
        CRYPTO -> Market.CRYPTO
        OVERSEAS -> Market.US_STOCK
        PENSION, IRP -> Market.KR_ETF
        else -> Market.KR_STOCK
    }
}

data class InvestAccount(
    val id: Long = 0,
    val name: String,
    val type: AccountType = AccountType.GENERAL,
    /** 증권사·거래소 name, as the user writes it. */
    val broker: String = "",
    /** [INV-03] The account's own fee in percent of the amount; null uses the market's default. */
    val feeRate: Double? = null,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
)

/** [INV-03] Where a holding trades: its currency, usual fee and the tax on selling it. */
enum class Market(val label: String, val currency: String, val defaultFeeRate: Double, val sellTaxRate: Double) {
    KR_STOCK("국내 주식", "KRW", 0.015, 0.20),
    KR_ETF("국내 ETF", "KRW", 0.015, 0.0),
    US_STOCK("해외 주식", "USD", 0.25, 0.0),
    CRYPTO("코인", "KRW", 0.05, 0.0),
    OTHER("기타", "KRW", 0.0, 0.0);

    val foreign: Boolean get() = currency != "KRW"
    /** Coins trade in fractions; shares in whole units (US fractional shares are typed as decimals anyway). */
    val fractional: Boolean get() = this == CRYPTO || this == US_STOCK
}

enum class Side(val label: String) { BUY("매수"), SELL("매도") }

/** [INV-03][INV-13] How the user felt when trading (Edgewonk-style emotion tags). */
enum class Emotion(val label: String) {
    CALM("차분"),
    CONFIDENT("확신"),
    RUSHED("조급"),
    GREEDY("욕심"),
    ANXIOUS("불안"),
    FEAR("두려움"),
    REVENGE("복수 매매"),
    BORED("지루함");

    companion object {
        fun encode(set: Set<Emotion>): String = entries.filter { it in set }.joinToString(",") { it.name }
        fun decode(raw: String?): Set<Emotion> = raw.orEmpty().split(',').mapNotNull { n -> entries.firstOrNull { it.name == n.trim() } }.toSet()
    }
}

/** [INV-03] Why the trade was made, offered as one-tap tags. */
val REASON_TAGS = listOf("실적", "차트", "뉴스", "배당", "정기 적립", "손절", "익절", "리밸런싱")

/** [INV-03] One buy or sell. Money fields are in the trade's [currency]; [fxRate] turns them into won. */
data class Trade(
    val id: Long = 0,
    val accountId: Long,
    val date: LocalDate,
    val minute: Int? = null,
    val symbol: String,
    val name: String = "",
    val market: Market = Market.KR_STOCK,
    val side: Side,
    val quantity: Double,
    val price: Double,
    val currency: String = market.currency,
    val fxRate: Double = 1.0,
    val fee: Double = 0.0,
    val tax: Double = 0.0,
    val reason: String = "",
    val emotions: Set<Emotion> = emptySet(),
    val tags: List<String> = emptyList(),
    /** [INV-13] Whether the user kept to their rules; null when not said. */
    val followedRules: Boolean? = null,
    val memo: String = "",
    /** [INV-08] The alarm or plan this trade was written from. */
    val alarmId: Long? = null,
    val createdAt: Long = 0,
) {
    /** The holding this trade belongs to: its code, or its name when no code was written. */
    val key: String get() = holdingKey(symbol, name)
    val title: String get() = name.ifBlank { symbol }
    val gross: Double get() = quantity * price
    val grossWon: Double get() = gross * fxRate
    val costsWon: Double get() = (fee + tax) * fxRate
    /** Won that left (a buy) or came in (a sell) after fees and tax. */
    val cashWon: Double get() = if (side == Side.BUY) -(grossWon + costsWon) else grossWon - costsWon
}

fun holdingKey(symbol: String, name: String): String = symbol.trim().ifEmpty { name.trim() }.uppercase()

/** [INV-02][INV-09] Money put in or taken out, and income that is not a sale. */
enum class CashKind(val label: String) {
    DEPOSIT("입금"),
    WITHDRAW("출금"),
    DIVIDEND("배당"),
    INTEREST("이자"),
    FEE("기타 비용");

    /** How it moves the 원금 (money the user put in). */
    val principalSign: Int get() = when (this) { DEPOSIT -> 1; WITHDRAW -> -1; else -> 0 }
    val isIncome: Boolean get() = this == DIVIDEND || this == INTEREST
}

data class CashFlow(
    val id: Long = 0,
    val accountId: Long,
    val date: LocalDate,
    val minute: Int? = null,
    val kind: CashKind,
    /** Before tax, in won, positive. */
    val amount: Long,
    /** [INV-09] Tax withheld from a dividend or interest. */
    val tax: Long = 0,
    /** [INV-09] The holding a dividend came from. */
    val symbol: String = "",
    val memo: String = "",
    val createdAt: Long = 0,
) {
    /** Won this moved into the account's cash. */
    val cashWon: Long get() = when (kind) {
        CashKind.DEPOSIT -> amount
        CashKind.WITHDRAW, CashKind.FEE -> -amount
        CashKind.DIVIDEND, CashKind.INTEREST -> amount - tax
    }
    val principalWon: Long get() = amount * kind.principalSign
}

/** [INV-04] A price the user typed for a holding, in its currency. */
data class Quote(val key: String, val price: Double, val date: LocalDate, val name: String = "", val market: Market = Market.KR_STOCK)

/** [INV-07] The day's journal: what the market did, looking back, which rules were kept and how the day went. */
data class JournalEntry(
    val date: LocalDate,
    val market: String = "",
    val review: String = "",
    /** Positions in [InvestSettings.principles] that were kept. */
    val checked: Set<Int> = emptySet(),
    val grade: DayGrade? = null,
    val updatedAt: Long = 0,
) {
    val isEmpty: Boolean get() = market.isBlank() && review.isBlank() && checked.isEmpty() && grade == null
}

/** [INV-07] 좋음·보통·나쁨, like a trade-note app's day rating. */
enum class DayGrade(val label: String) { GOOD("좋음"), OKAY("보통"), BAD("나쁨") }

/** [INV-14] Dates the user writes down to watch; nothing is fetched. */
enum class EventKind(val label: String) {
    EX_DIVIDEND("배당락일"),
    DIVIDEND_PAY("배당 지급일"),
    EARNINGS("실적 발표"),
    DISCLOSURE("공시"),
    IPO("청약"),
    OTHER("기타 일정"),
}

data class InvestEvent(
    val id: Long = 0,
    val date: LocalDate,
    val kind: EventKind,
    val symbol: String = "",
    val title: String = "",
    val memo: String = "",
    /** Told about at [InvestAlarms.EVENT_NOTICE_MINUTE] that morning. */
    val remind: Boolean = true,
) {
    val label: String get() = listOf(symbol.trim(), title.trim().ifEmpty { kind.label }).filter { it.isNotEmpty() }.joinToString(" ")
}

/** [INV-08] What an alarm is for, which sets its default time and days. */
enum class AlarmKind(val label: String) {
    MARKET_OPEN("장 시작"),
    MARKET_CLOSE("장 마감"),
    PLAN("정기 매수"),
    TARGET("목표가 확인"),
    CUSTOM("직접 정한 알람"),
}

/**
 * [INV-08] An alarm that comes with a way to write the trade. It repeats on [weekdays], or on
 * [monthDay] of each month (moved to the next open day when that is a weekend or holiday), or once
 * on [once]. A plan ([AlarmKind.PLAN]) also says what to buy and how much.
 */
data class InvestAlarm(
    val id: Long = 0,
    val kind: AlarmKind,
    val label: String = "",
    val minute: Int,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthDay: Int? = null,
    val once: LocalDate? = null,
    /** Weekly alarms skip public holidays, when the market is closed. */
    val skipHolidays: Boolean = true,
    val symbol: String = "",
    val name: String = "",
    val market: Market = Market.KR_STOCK,
    val accountId: Long? = null,
    val side: Side = Side.BUY,
    val quantity: Double? = null,
    /** Won to spend each time, for a plan by amount. */
    val amount: Long? = null,
    val targetPrice: Double? = null,
    val enabled: Boolean = true,
    /** Rings like the shift alarm; otherwise a notice only. */
    val ring: Boolean = false,
    val createdAt: Long = 0,
) {
    val key: String get() = holdingKey(symbol, name)
    val title: String get() = label.ifBlank {
        listOf(name.ifBlank { symbol }.trim(), kind.label).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/** [INV-21] What everything was worth on a day the user looked (cash + holdings at the prices then). */
data class Snapshot(val date: LocalDate, val value: Long, val principal: Long)

/** [INV-05] Which return the summary shows first. */
enum class ReturnMethod(val label: String, val explain: String) {
    SIMPLE("원금 대비", "지금 총자산에서 넣은 돈(입금−출금)을 뺀 만큼을 넣은 돈으로 나눠요. 가장 쉬운 수익률이에요."),
    MONEY_WEIGHTED("금액가중", "돈을 언제 얼마나 넣고 뺐는지까지 따져요. 내 계좌가 실제로 번 비율에 가까워요."),
    TIME_WEIGHTED("시간가중", "입금·출금의 영향을 빼고 고른 종목의 성과만 봐요. 현재가를 적은 날이 많을수록 정확해요."),
}

/** [INV-11][INV-12][INV-17] The calendar's own choices, kept in its database. */
data class InvestSettings(
    val goalAmount: Long? = null,
    val goalReturnPercent: Double? = null,
    val goalDate: LocalDate? = null,
    /** What the user plans to add each month, for "언제 닿나". */
    val monthlyAdd: Long? = null,
    val principles: List<String> = DEFAULT_PRINCIPLES,
    /** [INV-19] Won per dollar, typed by the user, for every US holding. */
    val usdKrw: Double = DEFAULT_USD_KRW,
    /** [INV-12] ISA 비과세 한도: 200만 원 (서민형 400만 원); changeable as the law changes. */
    val isaLimit: Long = InvestTaxes.ISA_LIMIT,
    val returnMethod: ReturnMethod = ReturnMethod.SIMPLE,
    val hideAmounts: Boolean = false,
    /** The account a new trade starts in: the last one used. */
    val lastAccountId: Long? = null,
) {
    companion object {
        val DEFAULT_PRINCIPLES = listOf("손절가를 지켰어요", "한 종목에 몰빵하지 않았어요", "계획한 종목만 샀어요", "뉴스 보고 바로 사지 않았어요")
        const val DEFAULT_USD_KRW = 1_400.0
    }
}

/** Everything one investor calendar holds, as the screens read it. */
data class InvestBook(
    val accounts: List<InvestAccount> = emptyList(),
    val trades: List<Trade> = emptyList(),
    val cash: List<CashFlow> = emptyList(),
    val quotes: List<Quote> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val events: List<InvestEvent> = emptyList(),
    val alarms: List<InvestAlarm> = emptyList(),
    val snapshots: List<Snapshot> = emptyList(),
    val settings: InvestSettings = InvestSettings(),
) {
    val isEmpty: Boolean get() = trades.isEmpty() && cash.isEmpty()
    val activeAccounts: List<InvestAccount> get() = accounts.filter { !it.hidden }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    fun account(id: Long?): InvestAccount? = accounts.firstOrNull { it.id == id }
    fun quote(key: String): Quote? = quotes.firstOrNull { it.key == key }
    fun journalOn(date: LocalDate): JournalEntry? = journal.firstOrNull { it.date == date }

    companion object {
        val EMPTY = InvestBook()
    }
}
