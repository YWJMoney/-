package com.bamnat.app.ui.pay

import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.WageType
import kotlin.math.roundToLong

/** [PAYS-03] How the user is paid, in a word: "월급제", "연봉제", "시급제", "일당제". */
internal fun PayProfile.wageLabel(): String = when (wageType) {
    WageType.MONTHLY -> if (annual != null) "연봉제" else "월급제"
    WageType.HOURLY -> "시급제"
    WageType.DAILY -> "일당제"
}

/** [PAYS-03] What [PayProfile.base] is: "기본급", "시급", "일당". */
internal fun WageType.baseName(): String = when (this) {
    WageType.MONTHLY -> "기본급"
    WageType.HOURLY -> "시급"
    WageType.DAILY -> "일당"
}

/** [PAYS-03] The hourly rate the base comes to: the 시급 itself, or 일당 ÷ 1일 소정근로시간. */
internal fun PayProfile.baseHourly(base: Long): Long = when (wageType) {
    WageType.MONTHLY -> 0
    WageType.HOURLY -> base
    WageType.DAILY -> (base * 60.0 / dailyHours.coerceIn(1, 8 * 60)).roundToLong()
}

/** "312만" for won amounts in a range, to one decimal under 100만: "8.3만". */
internal fun manwon(v: Long): String {
    val m = v / 10_000.0
    return when {
        kotlin.math.abs(m) >= 100 -> "%,d만".format(m.roundToLong())
        kotlin.math.abs(m) >= 1 -> ("%.1f".format(m)).removeSuffix(".0") + "만"
        else -> "%,d".format(v)
    }
}

/** "약 312만~341만 원", or one figure when [low] and [high] meet. */
internal fun rangeText(low: Long, high: Long): String =
    if (low == high) won(low) else "약 ${manwon(low)}~${manwon(high)} 원"
