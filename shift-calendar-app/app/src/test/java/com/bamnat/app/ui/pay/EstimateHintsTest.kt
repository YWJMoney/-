package com.bamnat.app.ui.pay

import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** [PAYS-11] A 추정 badge names the one thing to put in, and where it goes. */
class EstimateHintsTest {
    private val month = YearMonth.of(2026, 10)
    private val since = LocalDate.of(2026, 1, 1)
    private val night = PayItem("night", "나이트 수당", ValueTimeline(listOf(DatedValue(since, 30_000))), Cadence.MONTHLY)
    private val profile = PayProfile(
        Occupation.MANUFACTURING, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(since, 3_000_000))), items = listOf(night),
    )

    @Test fun guessedBaseOpensTheBaseEditor() {
        val hint = Estimates.forLine(PayLine(LineKind.NIGHT, "야간근로 가산", 1, "", estimated = true),
            profile.copy(estimatedFields = setOf(Unknowns.BASE)), month)
        assertEquals(ValueTarget.Base, hint.fill)
        assertTrue(hint.text, "명세서에 적힌 기본급" in hint.text)
        assertEquals("지금 넣기", hint.action)
    }

    @Test fun anAllowanceLineIsItsOwnAmount() {
        val hint = Estimates.forLine(PayLine(LineKind.ALLOWANCE, "나이트 수당", 30_000, "", estimated = true),
            profile.copy(estimatedFields = setOf(night.id)), month)
        assertEquals(ValueTarget.Item("night"), hint.fill)
        assertTrue(hint.text, "‘나이트 수당’" in hint.text)
    }

    @Test fun anUnknownAmountSaysItIsLeftOut() {
        val hint = Estimates.forValue(profile.copy(estimatedFields = setOf(Unknowns.item(night.id))), month, night)
        assertTrue(hint.text, "합계에서 뺐어요" in hint.text)
    }

    @Test fun theHolidayAnswerGoesToTheChecks() {
        val hint = Estimates.forLine(PayLine(LineKind.HOLIDAY, "휴일근로 (8시간 이내)", 1, "", estimated = true),
            profile.copy(estimatedFields = setOf(Unknowns.HOLIDAY_PAY_MODE)), month)
        assertTrue(hint.checks)
        assertEquals("답하기", hint.action)
    }

    @Test fun hourlyPayNamesTheHourlyRate() {
        val hint = Estimates.forValue(profile.copy(wageType = WageType.HOURLY, estimatedFields = setOf(Unknowns.BASE)), month, null)
        assertTrue(hint.text, hint.text.startsWith("시급을"))
    }
}
