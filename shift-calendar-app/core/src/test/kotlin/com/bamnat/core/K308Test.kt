package com.bamnat.core

import com.bamnat.core.leave.EntryKind
import com.bamnat.core.leave.HourlyLeave
import com.bamnat.core.leave.LeaveAccrual
import com.bamnat.core.leave.LeaveBasis
import com.bamnat.core.leave.LeaveExport
import com.bamnat.core.leave.LeavePayout
import com.bamnat.core.leave.LeavePlanner
import com.bamnat.core.leave.LeavePolicy
import com.bamnat.core.leave.LeavePromotion
import com.bamnat.core.leave.LeavePromotion.Step
import com.bamnat.core.leave.LeaveSources
import com.bamnat.core.leave.LedgerEntry
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.work.DayWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** 1.23 연차 (row 308): 촉진 기록, 원장 내보내기, 쓰기 최적화, 회계연도 정산, 시간 단위 연차, 단시간 1일 통상임금. */
class K308Test {
    private val today = LocalDate.of(2026, 10, 23)
    private val end = LocalDate.of(2026, 12, 31)
    private val monday = LocalDate.of(2026, 9, 28)
    private val resolver = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(1L, 1L, 1L, 1L, 1L, null, null)), emptyList())

    private fun summary(days: Double = 15.0) = LeaveSources.summary(today, null, emptyList(), resolver::resolve,
        listOf(LedgerEntry(kind = EntryKind.GRANT, date = LocalDate.of(2026, 1, 1), days = days, expires = end)))

    @Test
    fun `the ledger exports as a csv with every line and the totals`() {
        val s = summary()
        val rows = LeaveExport.rows(s)
        assertEquals(LeaveExport.HEADER, rows.first())
        assertEquals(s.lines.size + 1 + 5, rows.size)
        val csv = LeaveExport.csv(s)
        assertTrue(csv.startsWith("﻿"))
        assertTrue(csv.contains("\r\n"))
        assertTrue(csv.contains("쓸 수 있는 연차"))
        assertEquals("연차원장_2026-10-23.csv", LeaveExport.fileName(today))
    }

    @Test
    fun `a note that looks like a formula is kept as text`() {
        val s = LeaveSources.summary(today, null, emptyList(), resolver::resolve,
            listOf(LedgerEntry(kind = EntryKind.GRANT, date = LocalDate.of(2026, 1, 1), days = 15.0, expires = end, note = "=SUM(A1)")))
        assertFalse(LeaveExport.csv(s).contains(",=SUM"))
    }

    @Test
    fun `the planner picks the best value bridges before the lapse, never overlapping`() {
        val a = DayWork.Bridge(listOf(LocalDate.of(2026, 11, 2)), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 3), emptyList()) // 1 → 4
        val b = DayWork.Bridge(listOf(LocalDate.of(2026, 12, 24)), LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 27), listOf("성탄절")) // 1 → 4
        val c = DayWork.Bridge(listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3)), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 4), emptyList()) // overlaps a
        val late = DayWork.Bridge(listOf(LocalDate.of(2027, 1, 4)), LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 4), emptyList()) // after the lapse
        val plan = LeavePlanner.plan(summary(3.0), listOf(c, late, b, a))!!
        assertEquals(end, plan.lapse)
        assertEquals(listOf(a, b), plan.picks)
        assertEquals(2, plan.days)
        assertEquals(8, plan.restDays)
        assertEquals(1.0, plan.leftOver, 1e-9)
        assertTrue(LeavePlanner.payNote(false).contains("월급이 줄지 않아요"))
    }

    @Test
    fun `nothing lapsing means no plan`() {
        assertNull(LeavePlanner.plan(LeaveSources.summary(today, null, emptyList(), resolver::resolve, emptyList()), emptyList()))
    }

    @Test
    fun `a promotion done as article 61 has it counts, a missing step says what is missing`() {
        val first = LeavePromotion.Record(lapse = end, step = Step.FIRST_NOTICE, date = LocalDate.of(2026, 7, 3))
        val plan = LeavePromotion.Record(lapse = end, step = Step.WORKER_PLAN, date = LocalDate.of(2026, 7, 10))
        assertTrue(LeavePromotion.check(end, false, listOf(first, plan)).done)
        // The company named the days itself by 10/31 instead.
        val second = LeavePromotion.Record(lapse = end, step = Step.COMPANY_DESIGNATION, date = LocalDate.of(2026, 10, 30))
        assertTrue(LeavePromotion.check(end, false, listOf(first, second)).done)
        // A late first notice, and nothing after it.
        val late = LeavePromotion.check(end, false, listOf(first.copy(date = LocalDate.of(2026, 8, 1))))
        assertFalse(late.done)
        assertEquals(2, late.missing.size)
        assertTrue(late.text.contains("청구할 수 있어요"))
        // Nothing on record.
        assertEquals(listOf("1차 촉구 기록이 없어요."), LeavePromotion.check(end, false, emptyList()).missing)
        // 1년 미만: 3개월 전부터 10일 안.
        assertTrue(LeavePromotion.check(end, true, listOf(first.copy(date = LocalDate.of(2026, 10, 2)), plan.copy(date = LocalDate.of(2026, 10, 5)))).done)
        assertEquals(Step.WORKER_PLAN, Step.of("WORKER_PLAN"))
        assertNull(Step.of("NOPE"))
    }

    @Test
    fun `the law's days by years of service`() {
        val hire = LocalDate.of(2020, 3, 1)
        assertEquals(0, LeaveAccrual.entitlement(hire, hire.minusDays(1)))
        assertEquals(5, LeaveAccrual.entitlement(hire, LocalDate.of(2020, 8, 1)))
        assertEquals(11, LeaveAccrual.entitlement(hire, LocalDate.of(2021, 2, 28)))
        assertEquals(15, LeaveAccrual.entitlement(hire, LocalDate.of(2021, 3, 1)))
        assertEquals(16, LeaveAccrual.entitlement(hire, LocalDate.of(2023, 3, 1)))
        assertEquals(25, LeaveAccrual.entitlement(hire, LocalDate.of(2070, 3, 1)))
    }

    @Test
    fun `fiscal year counting pays the shortfall against the hire date on leaving`() {
        val hire = LocalDate.of(2024, 7, 1)
        val fiscal = LeavePolicy(basis = LeaveBasis.FISCAL, fiscalStartMonth = 1)
        assertEquals(0.0, LeaveAccrual.fiscalShortfall(hire, LeavePolicy(), LocalDate.of(2026, 7, 2)), 1e-9)
        val short = LeaveAccrual.fiscalShortfall(hire, fiscal, LocalDate.of(2026, 7, 2))
        assertTrue("shortfall $short", short > 0)
        assertEquals(0.0, LeaveAccrual.fiscalShortfall(hire, fiscal, LocalDate.of(2025, 1, 15)), 1e-9)
    }

    @Test
    fun `hourly leave counts against the day's scheduled hours`() {
        assertEquals(0.125, HourlyLeave(today, 60).days(), 1e-9)
        assertEquals(0.25, HourlyLeave(today, 60).days(4 * 60), 1e-9)
        assertEquals(LocalDate.of(2027, 6, 10), HourlyLeave.FROM)
    }

    @Test
    fun `a part-time roster's usual day sets the daily wage`() {
        val short = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(1L, 1L, 1L, null, null, null, null)), emptyList())
        val minutes = LeavePayout.scheduledDay(short::resolve, YearMonth.of(2026, 11))
        assertEquals(resolver.resolve(monday).scheduledMinutes.coerceAtMost(480), minutes)
        val none = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(null)), emptyList())
        assertNull(LeavePayout.scheduledDay(none::resolve, YearMonth.of(2026, 11)))
    }
}
