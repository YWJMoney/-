package com.bamnat.core.pay

/**
 * [PAYS-04] An amount in the units people say, so a missing or extra zero shows at a glance while typing:
 * 2,508,000 → "250만 8천원", 3,000,000 → "300만원", 10,320 → "1만 320원", 150,000,000 → "1억 5,000만원".
 */
fun wonWords(v: Long): String {
    if (v == 0L) return "0원"
    val sign = if (v < 0) "-" else ""
    var n = kotlin.math.abs(v)
    val eok = n / 100_000_000
    n %= 100_000_000
    val man = n / 10_000
    val rest = n % 10_000
    val parts = buildList {
        if (eok > 0) add("%,d억".format(eok))
        if (man > 0) add("%,d만".format(man))
        if (rest > 0) add(if (rest % 1_000 == 0L) "${rest / 1_000}천" else "%,d".format(rest))
    }
    return sign + parts.joinToString(" ") + "원"
}
