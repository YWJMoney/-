package com.bamnat.core

import com.bamnat.core.example.Browse
import com.bamnat.core.example.BrowseChoice
import com.bamnat.core.example.BrowseState
import com.bamnat.core.example.ExamplePlan
import com.bamnat.core.example.WriteKind
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [EASY-01][EASY-02] Looking around with an example, and the moment the user starts to write. */
class EasyBrowseTest {
    private val today = LocalDate.of(2026, 10, 23)

    @Test fun `every write on the example asks, nothing off it does`() {
        val state = Browse.start(WorkStyle.SHIFT, today)
        assertTrue(state.browsing)
        WriteKind.entries.forEach {
            assertTrue(it.name, Browse.asks(state, onExample = true, kind = it, today = today))
            assertFalse(it.name, Browse.asks(state, onExample = false, kind = it, today = today))
        }
    }

    @Test fun `keep browsing is not asked again that day, except for what goes out`() {
        val state = Browse.after(Browse.start(WorkStyle.DAY, today), BrowseChoice.KEEP_BROWSING, today)
        assertTrue(state.browsing)
        assertFalse(Browse.asks(state, true, WriteKind.MEMO, today))
        assertTrue(Browse.asks(state, true, WriteKind.SHARE, today))
        assertTrue(Browse.asks(state, true, WriteKind.MEMO, today.plusDays(1)))
    }

    @Test fun `starting either way ends looking around`() {
        val state = Browse.start(WorkStyle.HOURLY, today)
        assertEquals(BrowseState(), Browse.after(state, BrowseChoice.START_MINE, today))
        assertEquals(BrowseState(), Browse.after(state, BrowseChoice.KEEP_EXAMPLE, today))
    }

    @Test fun `the card says what and why`() {
        assertTrue(Browse.body(WriteKind.MEMO).startsWith("지금은 예시 달력이라 메모 쓰기를 해도"))
        assertTrue(Browse.body(WriteKind.LEAVE).contains("연차 넣기를"))
        assertTrue(Browse.body(WriteKind.SHARE).contains("공유 만들기를"))
        assertEquals("을", Browse.object_("가져오기 설정"))
    }

    @Test fun `every kind of work has a full example, marked as one`() {
        WorkStyle.entries.forEach { style ->
            val plan = ExamplePlan.of(style, today)
            assertEquals(style, plan.style)
            assertTrue("$style 메모", plan.memos.isNotEmpty())
            plan.memos.forEach { assertTrue(it.title, it.title.contains("예시")) }
            plan.money.forEach { assertTrue(it.note, it.note.contains("예시")) }
            plan.jobs.forEach { assertTrue(it.title, it.title.contains("예시")) }
            plan.trades.forEach { assertTrue(it.name, it.name.contains("예시")) }
            // Nothing in the future past next month, nothing before last month.
            val dates = plan.memos.mapNotNull { it.date } + plan.money.map { it.date } + plan.jobs.map { it.date } +
                plan.sales.map { it.date } + plan.trades.map { it.date } + plan.extras.map { it.date } + plan.leave
            dates.forEach { assertTrue("$style $it", !it.isBefore(today.minusMonths(1).withDayOfMonth(1)) && !it.isAfter(today.plusMonths(1))) }
            // The month's money is never in the future.
            plan.money.forEach { assertFalse("$style ${it.date}", it.date.isAfter(today)) }
        }
        val shift = ExamplePlan.of(WorkStyle.SHIFT, today)
        assertEquals(listOf("주간", "주간", "야간", "야간", "휴무"), shift.rotation)
        assertTrue(shift.rotation.all { n -> shift.shifts.any { it.name == n } })
        assertTrue(ExamplePlan.of(WorkStyle.DAY, today).weekly.filterNotNull().all { n -> ExamplePlan.of(WorkStyle.DAY, today).shifts.any { it.name == n } })
        assertTrue(ExamplePlan.of(WorkStyle.HOURLY, today).pay != null)
        assertTrue(ExamplePlan.of(WorkStyle.FIELD, today).jobs.isNotEmpty())
        assertTrue(ExamplePlan.of(WorkStyle.BUSINESS, today).sales.isNotEmpty())
        assertTrue(ExamplePlan.of(WorkStyle.INVEST, today).trades.isNotEmpty())
    }

    @Test fun `the example fills the month on any day`() {
        // The first of a month and the last: spending stays inside this month, the rotation has a day off to work on.
        listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 2, 28)).forEach { d ->
            val plan = ExamplePlan.of(WorkStyle.SHIFT, d)
            plan.money.forEach { assertEquals(d.withDayOfMonth(1), it.date.withDayOfMonth(1)) }
            assertEquals(2, plan.extras.size)
        }
    }
}
