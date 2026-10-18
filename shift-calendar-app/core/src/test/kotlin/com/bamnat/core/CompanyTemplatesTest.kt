package com.bamnat.core

import com.bamnat.core.setup.CompanyTemplates
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [COMM-05] The templates offered before a company is found: usual kinds of work, never a company's own. */
class CompanyTemplatesTest {
    @Test fun `every order is a known preset and names the template's own shifts`() {
        CompanyTemplates.all.filter { it.order.isNotEmpty() }.forEach { t ->
            assertNotNull(t.id, CompanyTemplates.preset(t))
            val names = t.shifts.map { it.type.name }.toSet()
            assertEquals(t.id, t.order.length, t.orderNames.size)
            t.orderNames.filterNotNull().forEach { assertTrue("${t.id}: $it", it in names) }
            // Every work day of the order has its shift; only days off may be left plain.
            t.order.filter { it != 'O' && it != 'B' }.forEach { code -> assertTrue("${t.id}: $code", t.shifts.any { it.role.code == code }) }
        }
    }

    @Test fun `templates are generic and complete`() {
        assertEquals(CompanyTemplates.all.size, CompanyTemplates.all.map { it.id }.toSet().size)
        CompanyTemplates.all.forEach { t ->
            assertTrue(t.id, t.styles.isNotEmpty())
            assertTrue(t.id, t.shifts.any { !it.role.isOff })
            t.shifts.forEach { s -> assertTrue("${t.id}: ${s.type.name}", s.type.isOff || s.type.hasTimes) }
            assertTrue(t.id, t.wage in setOf("MONTHLY", "HOURLY", "DAILY"))
        }
        assertEquals("주간 07~19 · 야간 19~07 · 8일 순서", CompanyTemplates.byId("two12")!!.summary)
        assertEquals("데이 07~15 · 이브닝 15~23 · 나이트 23~07 · 매달 근무표", CompanyTemplates.byId("nurse3")!!.summary)
    }

    @Test fun `each kind of work that is set up gets its own templates`() {
        assertTrue(CompanyTemplates.forStyle(WorkStyle.SHIFT).size >= 5)
        assertEquals(listOf("office", "daily"), CompanyTemplates.forStyle(WorkStyle.DAY).map { it.id })
        assertEquals(listOf("parttime", "daily"), CompanyTemplates.forStyle(WorkStyle.HOURLY).map { it.id })
        assertTrue(CompanyTemplates.forStyle(WorkStyle.FIELD).isEmpty())
        assertTrue(CompanyTemplates.forStyle(null).isEmpty())
    }
}
