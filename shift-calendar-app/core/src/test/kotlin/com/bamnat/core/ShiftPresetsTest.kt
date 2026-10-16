package com.bamnat.core

import com.bamnat.core.model.WorkTime
import com.bamnat.core.schedule.PatternPresets
import com.bamnat.core.schedule.ShiftTypeSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Workplace shift sets and the added rotation patterns [MKT-03, MKT-04]. */
class ShiftPresetsTest {
    @Test
    fun `nurse set is day evening night starting 07 15 23 with 8 hours each`() {
        val nurse = ShiftTypeSets.all.first { "D/E/N" in it.name }
        assertEquals(listOf(7, 15, 23), nurse.types.map { it.start!!.hour })
        nurse.types.forEach { assertEquals(480, WorkTime.workMinutes(it.start!!, it.end!!, it.breakMinutes)) }
    }

    @Test
    fun `nurse set maps onto D E N pattern codes`() {
        val types = ShiftTypeSets.all.first { "D/E/N" in it.name }.types.mapIndexed { i, t -> t.copy(id = i + 10L) } + Fixtures.off
        val steps = PatternPresets.toSteps(PatternPresets.all.first { it.codes == "DDEENNOO" }, types)
        assertEquals(listOf(10L, 10L, 11L, 11L, 12L, 12L, Fixtures.off.id, Fixtures.off.id), steps)
    }

    @Test
    fun `24 hour shift is 20 paid hours and fills the X code`() {
        val duty = ShiftTypeSets.all.first { it.name == "24시간 맞교대" }.types.single().copy(id = 7)
        assertEquals(20 * 60, WorkTime.workMinutes(duty.start!!, duty.end!!, duty.breakMinutes))
        val steps = PatternPresets.toSteps(PatternPresets.all.first { it.codes == "XOO" }, listOf(duty, Fixtures.off))
        assertEquals(listOf(7L, Fixtures.off.id, Fixtures.off.id), steps)
    }

    @Test
    fun `only types the user lacks are added`() {
        val set = ShiftTypeSets.all.first { it.name == "공장 3교대" }
        val missing = ShiftTypeSets.missing(set, Fixtures.all)
        assertTrue(missing.none { it.name == "주간" || it.name == "오후" || it.name == "야간" })
    }
}
