package com.bamnat.core

import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.NightSummary
import com.bamnat.core.pay.PayLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth

/** [NURS-05] 이달 나이트 개수와 나이트 수당 (순번 277): what the month's pay already holds for nights. */
class NightSummaryTest {
    private fun pay(vararg lines: PayLine) = MonthPay(YearMonth.of(2026, 11), lines.toList(), 0, "", emptyList(), emptyList())

    @Test fun nightPayIsTheNightLines() {
        val p = pay(
            PayLine(LineKind.BASE, "기본급", 3_000_000, ""),
            PayLine(LineKind.NIGHT, "야간 가산", 120_000, ""),
            PayLine(LineKind.ALLOWANCE, "나이트 수당", 210_000, ""),
            PayLine(LineKind.ALLOWANCE, "식대", 200_000, ""),
        )
        assertEquals(330_000L, NightSummary.allowance(p))
        assertNull(NightSummary.allowance(pay(PayLine(LineKind.BASE, "기본급", 3_000_000, ""))))
        assertEquals("이달 나이트 7개 · 나이트 수당 약 330,000원", NightSummary.line(7, 330_000))
        assertEquals("이달 나이트 7개", NightSummary.line(7, null))
    }
}
