package com.bamnat.core

import com.bamnat.core.leave.LeaveAccrual
import com.bamnat.core.leave.LeaveBasis
import com.bamnat.core.leave.LeavePolicy
import com.bamnat.core.leave.LotKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [LEAVE-02] 근로기준법 제60조 (원문 2026-10-22, research/1.22-D-연차-원문.md): the edges of when leave arises and
 * how much, by hire date and by 회계연도.
 */
class LeaveAccrualTest {
    private val hire = LocalDate.of(2025, 3, 2)

    @Test
    fun `fifteen days arise the day after the first full year, not on it`() {
        val onAnniversaryEve = LeaveAccrual.lots(hire, LeavePolicy(), LocalDate.of(2026, 3, 1))
        assertTrue(onAnniversaryEve.none { it.kind == LotKind.ANNUAL })
        val lots = LeaveAccrual.lots(hire, LeavePolicy(), LocalDate.of(2026, 3, 2))
        val annual = lots.single { it.kind == LotKind.ANNUAL }
        // 대법원 2021다227100: the right arises the day after the year of service ends.
        assertEquals(LocalDate.of(2026, 3, 2), annual.on)
        assertEquals(15.0, annual.days, 0.0)
        assertEquals(LocalDate.of(2027, 3, 1), annual.expires)
    }

    @Test
    fun `the first year gives a day per month, eleven at most, all lapsing when the first year ends`() {
        val monthly = LeaveAccrual.lots(hire, LeavePolicy(), LocalDate.of(2026, 12, 31)).filter { it.kind == LotKind.MONTHLY }
        assertEquals(11, monthly.size)
        assertEquals(LocalDate.of(2025, 4, 2), monthly.first().on)
        assertEquals(LocalDate.of(2026, 2, 2), monthly.last().on)
        assertTrue(monthly.all { it.expires == LocalDate.of(2026, 3, 1) })
    }

    @Test
    fun `a hire on 29 February ends its year on 28 February (민법 제160조)`() {
        val leap = LocalDate.of(2024, 2, 29)
        assertEquals(LocalDate.of(2025, 2, 28), LeaveAccrual.periodEnd(leap, 12))
        val annual = LeaveAccrual.lots(leap, LeavePolicy(), LocalDate.of(2025, 3, 1)).single { it.kind == LotKind.ANNUAL }
        assertEquals(LocalDate.of(2025, 3, 1), annual.on)
        // 1월 31일 + 1개월: the month has no 31st, so the month ends the period.
        assertEquals(LocalDate.of(2026, 2, 28), LeaveAccrual.periodEnd(LocalDate.of(2026, 1, 31), 1))
    }

    @Test
    fun `a year under 80 percent attendance gives no fifteen days`() {
        val lots = LeaveAccrual.lots(hire, LeavePolicy(lowAttendanceYears = setOf(1)), LocalDate.of(2027, 3, 2))
        val (first, second) = lots.filter { it.kind == LotKind.ANNUAL }
        assertEquals(0.0, first.days, 0.0)
        assertTrue(first.note.contains("80%"))
        assertEquals(15.0, second.days, 0.0)
    }

    @Test
    fun `one more day every two years from the third, twenty five at most`() {
        assertEquals(15, LeaveAccrual.yearDays(1))
        assertEquals(15, LeaveAccrual.yearDays(2))
        assertEquals(16, LeaveAccrual.yearDays(3))
        assertEquals(20, LeaveAccrual.yearDays(11))
        assertEquals(25, LeaveAccrual.yearDays(21))
        assertEquals(25, LeaveAccrual.yearDays(23))
        val lots = LeaveAccrual.lots(LocalDate.of(2000, 1, 3), LeavePolicy(), LocalDate.of(2026, 1, 3)).filter { it.kind == LotKind.ANNUAL }
        assertEquals(26, lots.size)
        assertEquals(25.0, lots.last().days, 0.0)
    }

    @Test
    fun `a fiscal year gives the first part year in proportion`() {
        val policy = LeavePolicy(basis = LeaveBasis.FISCAL, fiscalStartMonth = 1)
        val lots = LeaveAccrual.lots(LocalDate.of(2025, 7, 1), policy, LocalDate.of(2027, 1, 1))
        val prorated = lots.single { it.kind == LotKind.PRORATED }
        assertEquals(LocalDate.of(2026, 1, 1), prorated.on)
        // 2025-07-01 ~ 2025-12-31: 184일 ÷ 365 × 15
        assertEquals(7.56, prorated.days, 0.0)
        assertEquals(LocalDate.of(2026, 12, 31), prorated.expires)
        val next = lots.single { it.kind == LotKind.ANNUAL }
        assertEquals(LocalDate.of(2027, 1, 1), next.on)
        assertEquals(15.0, next.days, 0.0)
        // Hired on the start date: a whole year at the next start, no part year.
        val onStart = LeaveAccrual.lots(LocalDate.of(2025, 1, 1), policy, LocalDate.of(2026, 1, 1))
        assertTrue(onStart.none { it.kind == LotKind.PRORATED })
        assertEquals(15.0, onStart.single { it.kind == LotKind.ANNUAL }.days, 0.0)
    }

    @Test
    fun `days read as people write them`() {
        assertEquals("15일", LeaveAccrual.days(15.0))
        assertEquals("0.5일", LeaveAccrual.days(0.5))
        assertEquals("7.56일", LeaveAccrual.days(7.5616))
    }
}
