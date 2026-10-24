package com.bamnat.core

import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.PatternPresets
import com.bamnat.core.schedule.RestRules
import com.bamnat.core.schedule.RotationSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

/** [REST-03] How fast a pattern turns (순번 105), and the roster checks that change with it. */
class RotationSpeedTest {
    @Test fun presets() {
        assertEquals(RotationSpeed.FAST, RotationSpeed.ofCodes("DDNNOO"))
        assertEquals(RotationSpeed.FAST, RotationSpeed.ofCodes("DNOO"))
        assertEquals(RotationSpeed.FAST, RotationSpeed.ofCodes("DDDEEENNNOOO"))
        assertEquals(RotationSpeed.SLOW, RotationSpeed.ofCodes("DDDDDOOEEEEEOONNNNNOO"))
        assertEquals(RotationSpeed.DUTY_24H, RotationSpeed.ofCodes("XB"))
        assertEquals(RotationSpeed.DUTY_24H, RotationSpeed.ofCodes("XBXBOOOO"))
        assertEquals(RotationSpeed.FIXED_NIGHT, RotationSpeed.ofCodes("NNNNNOO"))
        assertEquals(RotationSpeed.DAY_ONLY, RotationSpeed.ofCodes("DDDDDOO"))
        assertEquals(RotationSpeed.NONE, RotationSpeed.ofCodes("OO"))
        // The cycle is read round: N at the end and start of DNNOODN is a run of three.
        assertEquals(RotationSpeed.FAST, RotationSpeed.ofCodes("NDDOON"))
        assertEquals(RotationSpeed.SLOW, RotationSpeed.ofCodes("NNDOONN"))
        PatternPresets.all.forEach { assertNotEquals(it.name, RotationSpeed.NONE, RotationSpeed.ofCodes(it.codes)) }
        // Each night with its 비번 the day after: 경찰 주야비휴, 소방 3조 2교대 21일.
        assertEquals(RotationSpeed.NIGHT_DUTY, RotationSpeed.ofCodes("DNBO"))
        assertEquals(RotationSpeed.NIGHT_DUTY, RotationSpeed.ofCodes("DDDDDOONBNBNBNBNBNBNB"))
        assertEquals(RotationSpeed.FAST, RotationSpeed.ofCodes("DDNNBOOO"))
    }

    @Test fun fromTheUsersOwnShifts() {
        val f = Fixtures
        val p = WorkPattern.Rotation(LocalDate.of(2026, 11, 2), listOf(f.day.id, f.evening.id, f.night.id, f.off.id))
        assertEquals(RotationSpeed.FAST, RotationSpeed.of(p, f.all))
        assertEquals('E', RotationSpeed.code(f.evening))
        assertEquals('N', RotationSpeed.code(f.night))
        assertEquals('O', RotationSpeed.code(null))
        assertEquals('B', RotationSpeed.code(f.off.copy(name = "비번", label = "비")))
        val nights = WorkPattern.Rotation(LocalDate.of(2026, 11, 2), listOf(f.night.id, f.night.id, f.off.id))
        assertEquals(RotationSpeed.FIXED_NIGHT, RotationSpeed.of(nights, f.all))
        assertEquals(RotationSpeed.NONE, RotationSpeed.of(null, f.all))
    }

    @Test fun rulesFollowTheSpeed() {
        val r = RestRules()
        assertEquals(r, RotationSpeed.FAST.adjust(r))
        assertEquals(0, RotationSpeed.FIXED_NIGHT.adjust(r).maxNightsInRow)
        assertEquals(0, RotationSpeed.FIXED_NIGHT.adjust(r).maxNightsPerMonth)
        assertEquals(r.minRestHours, RotationSpeed.FIXED_NIGHT.adjust(r).minRestHours)
        assertEquals(1, RotationSpeed.DUTY_24H.adjust(r).offsAfterNights)
        assertEquals(r.copy(offsAfterNights = 1), RotationSpeed.NIGHT_DUTY.adjust(r))
    }
}
