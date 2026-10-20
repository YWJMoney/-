package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.NightShift
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.model.isNight
import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayGaps
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.work.ShiftMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.roundToLong

/** [SHFT-01..SHFT-05] Shift work: night kinds, each kind's own premium, the month's codes and swaps, pay against what came in. */
class ShiftWorkTest {
    private val oct1 = LocalDate.of(2026, 10, 1)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun `a shift is night work when its hours reach into 22 to 06`() {
        assertTrue(NightShift.crosses(t(22), t(7)))
        assertTrue(NightShift.crosses(t(15), t(23)))
        assertTrue(NightShift.crosses(t(5), t(14)))
        assertTrue(NightShift.crosses(t(9), t(9)))
        assertFalse(NightShift.crosses(t(14), t(22)))
        assertFalse(NightShift.crosses(t(6), t(14)))
        assertFalse(NightShift.crosses(t(9), t(18)))
        assertTrue(Fixtures.night.isNight)
        assertFalse(Fixtures.day.isNight)
        assertFalse(Fixtures.off.isNight)
    }

    @Test
    fun `a night shift is on from its start until it ends the next morning`() {
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(oct1, listOf(Fixtures.night.id, Fixtures.day.id)), emptyList())
        assertTrue(NightShift.isOn(LocalDateTime.of(2026, 10, 2, 3, 0), r::resolve))
        assertTrue(NightShift.isOn(LocalDateTime.of(2026, 10, 1, 22, 0), r::resolve))
        assertFalse(NightShift.isOn(LocalDateTime.of(2026, 10, 1, 21, 0), r::resolve))
        // 10/2 is a day shift: after the night ended at 07:00, no night.
        assertFalse(NightShift.isOn(LocalDateTime.of(2026, 10, 2, 12, 0), r::resolve))
    }

    private val nurse = PayProfile(
        occupation = Occupation.NURSE,
        wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))),
        items = listOf(PayItem("night", "나이트 수당", ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 30_000))), Cadence.PER_NIGHT_SHIFT)),
    )

    @Test
    fun `a shift kind's own premium is a line of hours worked on it times hourly times percent`() {
        val night = Fixtures.night.copy(premiumPercent = 30)
        val types = Fixtures.all.map { if (it.id == night.id) night else it }
        val r = ScheduleResolver(types, WorkPattern.Rotation(oct1, listOf(night.id, Fixtures.off.id)), emptyList())
        val pay = PayCalculator().calculate(nurse, YearMonth.of(2026, 10), r::resolve) { false }
        val line = pay.lines.single { it.label == "야간 근무 수당 30%" }
        assertEquals(LineKind.ALLOWANCE, line.kind)
        assertEquals(16 * 480, line.minutes)
        assertEquals((16 * 8 * pay.ordinaryHourly * 0.3).roundToLong(), line.amount)
        assertEquals(0.3, line.multiplier, 1e-9)
        assertTrue(line.basis.contains("이 근무 수당 30%"))
        // The day shows its part too, and without a premium nothing changes.
        assertTrue(pay.day(oct1)!!.parts.any { it.label == "야간 근무 수당" })
        val plain = PayCalculator().calculate(nurse, YearMonth.of(2026, 10),
            ScheduleResolver(Fixtures.all, WorkPattern.Rotation(oct1, listOf(Fixtures.night.id, Fixtures.off.id)), emptyList())::resolve) { false }
        assertEquals(plain.total + line.amount, pay.total)
    }

    @Test
    fun `the month counts each code, the nights and who the days were swapped or covered with`() {
        val entries = listOf(
            DayEntry(LocalDate.of(2026, 10, 2), swapWith = "김간호"),
            DayEntry(LocalDate.of(2026, 10, 3), swapWith = "박주임",
                extras = listOf(ExtraEntry(ExtraKind.COVER_ID, start = t(22), end = t(7), coveredShiftId = Fixtures.night.id))),
            DayEntry(LocalDate.of(2026, 10, 6), swapWith = "김간호"),
        )
        val cycle = listOf(Fixtures.day.id, Fixtures.night.id, Fixtures.off.id)
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(oct1, cycle), entries)
        val m = ShiftMonth.of(YearMonth.of(2026, 10), Fixtures.all, r::resolve)
        assertEquals(listOf("주간" to 11, "야간" to 10, "휴무" to 10), m.counts.map { it.type.name to it.days })
        assertEquals(11, m.nights)
        assertEquals(2, m.swapped.size)
        assertEquals(1, m.covers.size)
        assertEquals("박주임", m.covers.single().name)
        assertEquals(listOf("김간호" to 2, "박주임" to 1), m.names)
    }

    @Test
    fun `pay that came in is compared with the month's estimate`() {
        val month = YearMonth.of(2026, 10)
        val payDay = LocalDate.of(2026, 11, 10)
        val entries = listOf(
            MoneyEntry(date = LocalDate.of(2026, 11, 9), kind = MoneyKind.INCOME, amount = 2_900_000, category = MoneyCategories.SALARY),
            MoneyEntry(date = LocalDate.of(2026, 11, 9), kind = MoneyKind.INCOME, amount = 50_000, category = "부수입"),
            MoneyEntry(date = LocalDate.of(2026, 10, 10), kind = MoneyKind.INCOME, amount = 2_800_000, category = MoneyCategories.SALARY),
        )
        val gap = PayGaps.of(month, 3_000_000, payDay, entries)!!
        assertEquals(2_900_000, gap.received)
        assertEquals(-100_000, gap.diff)
        assertEquals("예상보다 100,000원 적게 들어왔어요", gap.text)
        // Without a pay day the month's own 급여 lines count.
        assertEquals(2_800_000, PayGaps.of(month, 2_700_000, null, entries)!!.received)
        assertEquals("예상보다 100,000원 많이 들어왔어요", PayGaps.of(month, 2_700_000, null, entries)!!.text)
        assertEquals("예상과 거의 같게 들어왔어요", PayGaps.of(month, 2_800_500, null, entries)!!.text)
        assertNull(PayGaps.of(YearMonth.of(2026, 12), 3_000_000, LocalDate.of(2027, 1, 10), entries))
    }
}
