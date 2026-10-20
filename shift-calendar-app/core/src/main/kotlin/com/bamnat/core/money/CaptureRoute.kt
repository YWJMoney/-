package com.bamnat.core.money

/**
 * [CALS-04] Which calendar a payment notification is written into, so each calendar's money stays
 * its own. In order: the calendar that has the card or account by its last four digits; the one
 * whose card or account names the issuer (only when a single calendar does); the calendar chosen
 * for the app that sent it; and otherwise the calendar chosen for payment notifications.
 */
object CaptureRoute {
    /** One calendar and its cards, accounts and pay balances. */
    data class Place(val calendar: String, val accounts: List<MoneyAccount>)

    fun pick(
        last4: String?,
        issuer: String?,
        pkg: String,
        places: List<Place>,
        appCalendars: Map<String, String>,
        default: String,
    ): String {
        val ids = places.map { it.calendar }.toSet()
        val fallback = default.takeIf { it in ids } ?: places.firstOrNull()?.calendar ?: default
        val l4 = last4?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        if (l4 != null) {
            val holders = places.filter { p -> p.accounts.any { !it.hidden && it.last4 == l4 } }.map { it.calendar }
            if (holders.isNotEmpty()) return if (fallback in holders) fallback else holders.first()
        }
        val key = issuer?.let(::issuerKey)?.takeIf { it.length >= 2 }
        if (key != null) {
            val named = places.filter { p -> p.accounts.any { !it.hidden && it.name.replace(" ", "").contains(key, ignoreCase = true) } }
            if (named.size == 1) return named.single().calendar
        }
        appCalendars[pkg]?.takeIf { it in ids }?.let { return it }
        return fallback
    }

    /** "신한카드" → "신한", "KB국민은행" → "KB국민": the part an account's name carries. */
    fun issuerKey(issuer: String): String =
        issuer.replace(" ", "").replace(Regex("(체크카드|신용카드|카드|은행|뱅크|증권|페이|저축은행)$"), "")
}
