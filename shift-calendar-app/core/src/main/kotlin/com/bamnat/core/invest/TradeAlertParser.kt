package com.bamnat.core.invest

/**
 * [INV-30][INV-31] A buy or sell read from a broker's or exchange's notification: side, the stock or coin,
 * quantity and price, and whether it is a coin or a share. Brokers do not publish their wording, but the
 * 체결 통보 they all send carries the same parts (한국투자증권 안내: 매수·매도 구분, 종목명, 종목코드,
 * 체결수량, 체결단가), so this reads those parts wherever they sit in the text instead of one app's
 * layout. Anything it is not sure of is not a trade: the user confirms every one before it is written
 * (결정 D37), and a text with no 체결 word, no side or no quantity is dropped.
 */
object TradeAlertParser {
    data class Alert(
        val side: Side,
        /** The code (005930, AAPL, BTC) when the text has one, else empty. */
        val symbol: String,
        /** The name as written (삼성전자, 비트코인), else empty. */
        val name: String,
        val quantity: Double,
        val price: Double,
        val market: Market,
    ) {
        val title: String get() = name.ifBlank { symbol }
    }

    /** Exchanges whose every trade is a coin. */
    val COIN_APPS = setOf(
        "com.dunamu.exchange.upbit", "com.btckorea.bithumb", "coinone.co.kr.official", "com.korbit.exchange", "kr.co.gopax",
    )

    /** Securities apps (MTS), so a notification from them is looked at for a trade. */
    val STOCK_APPS = setOf(
        "com.kiwoom.heromts", "com.kiwoom.smartopen", "com.miraeasset.trade", "com.samsungpop.android.mpop", "com.wooriwm.txsmart",
        "com.nh.mobilenh", "com.truefriend.neosmarta", "com.truefriend.ministock", "viva.republica.toss", "com.kbsec.mts.iplustar",
        "com.shinhaninvest.nsmts", "com.hanabank.ebk.channel.android.hananbank", "com.hanaw.hanaw", "com.daishin.cybos", "com.kakaopay.securities",
        "com.ebestsec.mts", "com.meritzsec.mts",
    )

    private val FILLED = Regex("체결|매매\\s*완료|거래\\s*완료|주문\\s*완료|구매\\s*완료|판매\\s*완료|filled", RegexOption.IGNORE_CASE)
    private val NOT_A_FILL = Regex("미체결|체결\\s*(실패|취소)|주문\\s*(취소|거부|접수)|정정|예약|알림\\s*설정|가격\\s*도달|도달했|목표가")
    private val BUY = Regex("매수|구매|샀|buy", RegexOption.IGNORE_CASE)
    private val SELL = Regex("매도|판매|팔았|sell", RegexOption.IGNORE_CASE)
    private const val NUM = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
    private val QTY = listOf(
        Regex("(?:체결\\s*)?수량\\s*[:：]?\\s*$NUM"),
        Regex("$NUM\\s*(주|개|株)(?![가-힣])"),
        Regex("$NUM\\s*([A-Z]{2,10})(?![A-Za-z])"),
    )
    private val PRICE = listOf(
        Regex("(?:체결\\s*)?(?:단가|가격|평균가|체결가)\\s*[:：]?\\s*\\$?\\s*$NUM\\s*(원|KRW|USD|달러|\\$)?", RegexOption.IGNORE_CASE),
        Regex("[@＠]\\s*\\$?\\s*$NUM"),
        Regex("\\$\\s*$NUM"),
        Regex("$NUM\\s*(원|달러|USD)"),
    )
    private val CODE_KR = Regex("(?<![\\d])(\\d{6})(?![\\d])")
    private val CODE_COIN = Regex("(?:KRW|BTC|USDT)[-/]([A-Z0-9]{2,10})|([A-Z0-9]{2,10})[-/]KRW")
    private val BRACKET = Regex("[\\[【(（]([^\\]】)）]{1,30})[\\]】)）]")
    private val NAMED = Regex("(?:종목(?:명)?|코인)\\s*[:：]?\\s*([가-힣A-Za-z0-9&.\\- ]{1,30}?)(?=\\s*(?:\\(|\\[|,|/|\\n|수량|\\d|매수|매도|$))")
    private val BEFORE_CODE = Regex("([가-힣A-Za-z&.]{2,20})\\s*[(（]\\s*(?:\\d{6}|[A-Z]{1,10})\\s*[)）]")
    private val BEFORE_SIDE = Regex("([가-힣A-Za-z0-9&.]{2,20})\\s*(?:\\(\\w+\\))?\\s*(?:을|를)?\\s*(?:매수|매도|구매|판매)")
    private val TAGS = setOf("매수", "매도", "구매", "판매", "체결", "알림", "주문", "체결통보", "국내", "해외", "주식", "토스증권", "업비트", "빗썸", "알림톡")

    private fun num(s: String) = s.replace(",", "").toDoubleOrNull()

    /** Null unless [text] reads as one filled trade; [pkg] is the app it came from. */
    fun parse(text: String, pkg: String = ""): Alert? {
        val t = text.replace(' ', ' ')
        if (!FILLED.containsMatchIn(t) || NOT_A_FILL.containsMatchIn(t)) return null
        val buy = BUY.containsMatchIn(t)
        val sell = SELL.containsMatchIn(t)
        if (buy == sell) return null
        val coinApp = pkg in COIN_APPS
        var unitWord: String? = null
        val qty = QTY.firstNotNullOfOrNull { re ->
            re.find(t)?.let { m ->
                if (m.groupValues.size > 2) unitWord = m.groupValues[2]
                num(m.groupValues[1])?.takeIf { it > 0 }
            }
        } ?: return null
        var priceUnit: String? = null
        val price = PRICE.firstNotNullOfOrNull { re ->
            re.findAll(t).firstNotNullOfOrNull { m ->
                // A quantity is never its own price ("10주 … 10원" aside).
                if (m.value.contains("수량")) return@firstNotNullOfOrNull null
                priceUnit = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: if (m.value.contains('$')) "$" else null
                num(m.groupValues[1])?.takeIf { it > 0 }
            }
        } ?: 0.0
        val coinCode = CODE_COIN.find(t)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
            ?: unitWord?.takeIf { it.length >= 2 && it.all { c -> c.isUpperCase() || c.isDigit() } && it != "KRW" && it != "USD" }
        val usd = priceUnit?.let { it.equals("USD", true) || it == "달러" || it == "$" } == true
        val market = when {
            coinApp || coinCode != null || unitWord == "개" -> Market.CRYPTO
            usd -> Market.US_STOCK
            else -> Market.KR_STOCK
        }
        val code = when (market) {
            Market.CRYPTO -> coinCode.orEmpty()
            Market.KR_STOCK -> CODE_KR.find(t)?.groupValues?.get(1).orEmpty()
            else -> Regex("\\b([A-Z]{1,5})\\b").findAll(t).map { it.groupValues[1] }.firstOrNull { it !in setOf("USD", "KRW", "ETF", "BUY", "SELL") }.orEmpty()
        }
        val name = (NAMED.find(t)?.groupValues?.get(1)
            ?: BEFORE_CODE.findAll(t).map { it.groupValues[1] }.firstOrNull { it !in TAGS }
            ?: BRACKET.findAll(t).map { it.groupValues[1].trim() }.firstOrNull { it !in TAGS && !it.any(Char::isDigit) && !TAGS.any { tag -> it.startsWith(tag) } }
            ?: BEFORE_SIDE.find(t)?.groupValues?.get(1)?.takeIf { it !in TAGS })
            ?.trim()?.removeSuffix("이")?.takeIf { it.isNotBlank() && it != code }.orEmpty()
        if (code.isEmpty() && name.isEmpty()) return null
        return Alert(if (buy) Side.BUY else Side.SELL, code, name, qty, price, market)
    }
}
