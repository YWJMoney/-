package com.bamnat.core.money

/**
 * [SHOP-08] Notifications that are a shop's sales rather than its spending: an order from a delivery
 * app's 사장님 app, or an approval on a card terminal's app. On a shop calendar they go into that day's
 * sales (배달앱 or 카드) instead of the ledger. A bank deposit from a card company is the card sales
 * coming in ([settlement]), not new income. Only the apps listed here are read, by their name or
 * package, and only texts that clearly say an order or approval with an amount: anything unclear is
 * left to the usual payment reading.
 */
object ShopCapture {
    enum class Bucket(val label: String) { CARD("카드"), DELIVERY("배달앱") }

    /** A sale read from a notification: +amount, or −amount for a cancellation. */
    data class Sale(val bucket: Bucket, val amount: Long, val app: String)

    /** 사장님 apps of delivery services, by the name the phone shows. */
    private val DELIVERY_NAMES = listOf("배민사장님", "배달의민족 사장님", "배민 사장님", "쿠팡이츠 스토어", "쿠팡이츠스토어", "요기요 사장님", "요기요사장님", "땡겨요 사장님")
    /** Card terminal and POS apps, by the name the phone shows. */
    private val TERMINAL_NAMES = listOf("토스 플레이스", "토스플레이스", "토스포스", "토스 포스", "페이히어", "캐시노트")

    /** Pieces a package name must all have; app ids are not published, so these follow the makers' names. */
    private val DELIVERY_PACKAGES = listOf(listOf("woowahan", "ceo"), listOf("baemin", "ceo"), listOf("coupang", "eats", "store"), listOf("yogiyo", "owner"), listOf("yogiyo", "ceo"))
    private val TERMINAL_PACKAGES = listOf(listOf("toss", "place"), listOf("toss", "pos"), listOf("payhere"))

    private val AMOUNT = Regex("(\\d{1,3}(?:,\\d{3})+|\\d{3,9})\\s*원")
    private val ORDER = Regex("주문")
    private val ORDER_IN = Regex("접수|수락|완료|들어왔|도착|신규")
    private val APPROVED = Regex("승인|결제\\s*완료|결제됐|결제되었")
    private val CANCEL = Regex("취소|환불")

    /** Which kind of shop app [pkg] / [appName] is; null for any other app. */
    fun source(pkg: String, appName: String): Bucket? {
        val p = pkg.lowercase()
        val n = appName.replace(" ", "")
        return when {
            DELIVERY_NAMES.any { n.contains(it.replace(" ", "")) } || DELIVERY_PACKAGES.any { parts -> parts.all { p.contains(it) } } -> Bucket.DELIVERY
            TERMINAL_NAMES.any { n.contains(it.replace(" ", "")) } || TERMINAL_PACKAGES.any { parts -> parts.all { p.contains(it) } } -> Bucket.CARD
            else -> null
        }
    }

    /** True for a text from a listed app that should be read even though it does not look like a payment. */
    fun looksLikeSale(pkg: String, appName: String, text: String): Boolean = read(text, pkg, appName) != null

    /** The sale in [text] from [pkg] / [appName]; null when the app is not a shop app or the text is not clear. */
    fun read(text: String, pkg: String, appName: String): Sale? {
        val bucket = source(pkg, appName) ?: return null
        val amount = AMOUNT.find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val cancel = CANCEL.containsMatchIn(text)
        val clear = when (bucket) {
            Bucket.DELIVERY -> ORDER.containsMatchIn(text) && (ORDER_IN.containsMatchIn(text) || cancel)
            Bucket.CARD -> APPROVED.containsMatchIn(text) || cancel
        }
        if (!clear) return null
        return Sale(bucket, if (cancel) -amount else amount, appName.ifBlank { pkg })
    }

    /** [sales] of a day with [sale] added to its bucket; a cancellation never takes a bucket below zero. */
    fun add(day: DailySales?, date: java.time.LocalDate, sale: Sale): DailySales {
        val base = day ?: DailySales(date, 0)
        // A day written only as a total keeps that total as cash, so the parts still add up to it.
        val start = if (base.byMethod == 0L && base.sales > 0) base.copy(cash = base.sales) else base
        val next = when (sale.bucket) {
            Bucket.CARD -> start.copy(card = (start.card + sale.amount).coerceAtLeast(0))
            Bucket.DELIVERY -> start.copy(delivery = (start.delivery + sale.amount).coerceAtLeast(0))
        }
        return next.copy(sales = next.byMethod)
    }

    private val CARD_COMPANY = Regex("카드")
    private val SETTLED = Regex("입금|정산")

    /**
     * [SHOP-08] The amount of a card company's deposit (e.g. "신한카드 입금 1,234,000원", "카드대금 정산"),
     * read by the payment parser as a deposit; null for any other text.
     */
    fun settlement(text: String, parsed: PaymentParser.Parsed?): Long? {
        if (parsed == null || parsed.type != PaymentParser.Type.DEPOSIT) return null
        val amount = parsed.amount?.takeIf { it > 0 } ?: return null
        val from = parsed.merchant + " " + text
        return if (CARD_COMPANY.containsMatchIn(from) && SETTLED.containsMatchIn(text)) amount else null
    }
}
