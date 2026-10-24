package com.bamnat.core.pay

import java.time.LocalDate
import java.time.YearMonth

/**
 * [COMM-13] Where "이 금액을 받기 시작한 달" starts after a coworker's setup (점검 보고서 하 '광장': it read as a
 * year ago for no reason). A year back is on purpose ([PAYS-01]: the months already in the calendar come to an
 * amount too, 결정 S-2); a known 입사일 inside that year starts there instead. [why] says it under the stepper.
 */
object PaySince {
    fun start(now: YearMonth, hireDate: LocalDate?): YearMonth {
        val yearBack = now.minusMonths(12)
        val hired = hireDate?.let(YearMonth::from) ?: return yearBack
        return if (hired.isAfter(yearBack)) minOf(hired, now) else yearBack
    }

    fun why(hireDate: LocalDate?, now: YearMonth): String =
        if (hireDate != null && YearMonth.from(hireDate).isAfter(now.minusMonths(12))) "입사한 달부터 달력에 급여가 나와요."
        else "지난 1년 달력에도 급여가 나오도록 1년 전으로 두었어요. 이 금액을 더 늦게 받기 시작했으면 바꿔 주세요."
}
