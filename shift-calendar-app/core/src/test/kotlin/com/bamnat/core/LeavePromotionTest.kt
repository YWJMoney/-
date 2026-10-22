package com.bamnat.core

import com.bamnat.core.leave.LeaveAccrual
import com.bamnat.core.leave.LeaveLedger
import com.bamnat.core.leave.LeavePolicy
import com.bamnat.core.leave.LeavePromotion
import com.bamnat.core.leave.LeaveUse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** [LEAVE-03] 근로기준법 제61조: 6개월·2개월 전, the first year's monthly days 3개월·1개월 전, at 09:00. */
class LeavePromotionTest {
    private val hire = LocalDate.of(2025, 3, 2)
    private val lots = LeaveAccrual.lots(hire, LeavePolicy(), LocalDate.of(2026, 3, 2))

    @Test
    fun `notices fall on the dates the law sets`() {
        val at = LeavePromotion.notices(lots).map { it.at }
        assertEquals(listOf(
            LocalDateTime.of(2025, 12, 1, 9, 0), LocalDateTime.of(2026, 2, 1, 9, 0),
            LocalDateTime.of(2026, 9, 1, 9, 0), LocalDateTime.of(2027, 1, 1, 9, 0),
        ), at)
        val next = LeavePromotion.next(lots, LocalDateTime.of(2026, 9, 1, 9, 0))!!
        assertEquals(LocalDateTime.of(2027, 1, 1, 9, 0), next.at)
        assertEquals(2, next.stage)
    }

    @Test
    fun `the notice tells what lapses after the planned days, and nothing when all is planned`() {
        val today = LocalDate.of(2026, 9, 1)
        val s = LeaveLedger.build(today, lots, emptyList(), listOf(LeaveUse(LocalDate.of(2026, 10, 5), 2.0)))
        assertEquals(13.0, LeavePromotion.lapsing(s, LocalDate.of(2027, 3, 1))!!, 1e-9)
        val all = LeaveLedger.build(today, lots, emptyList(), (1..15).map { LeaveUse(LocalDate.of(2026, 10, 1).plusDays(it.toLong()), 1.0) })
        assertNull(LeavePromotion.lapsing(all, LocalDate.of(2027, 3, 1)))
        assertEquals("남은 연차 13일", LeavePromotion.title(13.0))
    }
}
