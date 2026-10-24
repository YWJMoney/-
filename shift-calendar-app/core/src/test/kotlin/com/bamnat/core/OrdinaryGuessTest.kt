package com.bamnat.core

import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.OrdinaryGuess
import org.junit.Assert.assertEquals
import org.junit.Test

/** 순번 285: a new allowance's 통상임금 switch starts where the law usually puts it (shift-wage-practices 5장). */
class OrdinaryGuessTest {
    @Test fun monthlyFixedAllowancesAreOrdinary() {
        listOf("교대수당", "근속수당", "자격수당", "면허 수당", "직책수당", "위험근무수당", "식대", "정액급식비", "교통비").forEach {
            assertEquals(it, true, OrdinaryGuess.of(it, Cadence.MONTHLY))
        }
    }

    @Test fun flatRotationNightAllowanceIsOrdinaryButPerNightIsNot() {
        // 대법원 2019다288898: a flat 야간교대수당 for being on the rotation is 통상임금.
        assertEquals(true, OrdinaryGuess.of("야간교대수당", Cadence.MONTHLY))
        assertEquals(false, OrdinaryGuess.of("나이트 수당", Cadence.PER_NIGHT_SHIFT))
        assertEquals(false, OrdinaryGuess.of("야간수당", Cadence.MONTHLY))
    }

    @Test fun pricedWorkAndPerformanceAreNot() {
        listOf("성과급", "인센티브", "출장비 실비", "고정연장수당", "시간외수당", "부양가족수당").forEach {
            assertEquals(it, false, OrdinaryGuess.of(it, Cadence.MONTHLY))
        }
    }

    @Test fun unknownOrPerDayIsLeftToTheUser() {
        assertEquals(null, OrdinaryGuess.of("가족수당", Cadence.MONTHLY))
        assertEquals(null, OrdinaryGuess.of("현장 수당", Cadence.MONTHLY))
        assertEquals(null, OrdinaryGuess.of("교대수당", Cadence.PER_WORK_DAY))
        assertEquals(null, OrdinaryGuess.of("", Cadence.MONTHLY))
    }
}
