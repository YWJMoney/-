package com.bamnat.core

import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.profile.Answer
import com.bamnat.core.profile.AskScheduler
import com.bamnat.core.profile.AskState
import com.bamnat.core.profile.ExportCheck
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.FactMeta
import com.bamnat.core.profile.Profile
import com.bamnat.core.profile.Questions
import com.bamnat.core.profile.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** [EASY-05][EASY-07] Every fact has a question that one tap, one number or one date answers, and the check before money leaves. */
class EasyQuestionTest {
    @Test fun `every fact has a question, and every screen it opens is in the registry`() {
        FactKey.entries.forEach { key ->
            listOf(true, false).forEach { hasPay ->
                val q = Questions.of(key, hasPay)
                assertTrue("$key asks", q.text.isNotBlank())
                when (val a = q.answer) {
                    is Answer.Open -> assertNotNull("$key opens ${a.feature}", FeatureRegistry.get(a.feature))
                    is Answer.Chips -> assertTrue(a.options.size in 2..4)
                    is Answer.Number -> assertTrue(a.start in a.min..a.max)
                    Answer.Date -> Unit
                }
            }
        }
    }

    @Test fun `with no pay set up a pay fact opens the pay setup, which asks it`() {
        assertEquals(Answer.Open("pay.calc", "급여 설정하기"), Questions.of(FactKey.PAY_DAY, hasPay = false).answer)
        assertTrue(Questions.of(FactKey.PAY_DAY, hasPay = true).answer is Answer.Number)
        // Not money, or kept without a pay setup: asked as they are.
        assertTrue(Questions.of(FactKey.TAX_MODE, hasPay = false).answer is Answer.Chips)
        assertTrue(Questions.of(FactKey.SLEEP_TARGET, hasPay = false).answer is Answer.Chips)
        assertTrue(Questions.of(FactKey.COMPANY, hasPay = false).answer is Answer.Open)
    }

    @Test fun `모르겠어요 stops asking at once`() {
        val s = AskScheduler.unknown(AskState(current = FactKey.MONTHLY_HOURS.id), FactKey.MONTHLY_HOURS)
        assertTrue(s.parked(FactKey.MONTHLY_HOURS))
        assertEquals(null, s.current)
    }

    @Test fun `before a pay report only the guessed money facts are listed`() {
        val at = Instant.parse("2026-10-23T00:00:00Z")
        val facts = mapOf(
            // Typed: fine.
            FactKey.BASE_PAY to Profile.read(FactKey.BASE_PAY, 2_600_000L, null),
            // Read off a payslip photo: to check.
            FactKey.PAY_ITEMS to Profile.read(FactKey.PAY_ITEMS, listOf("야간"),
                FactMeta(Source.INFERRED, 0.7, at, Profile.fingerprint(listOf("야간")), "명세서 사진")),
            // Still the app's starting 209시간: to check.
            FactKey.MONTHLY_HOURS to Profile.read(FactKey.MONTHLY_HOURS, 209, null, default = 209),
            // A guess, but not one this report reads.
            FactKey.SLEEP_TARGET to Profile.read(FactKey.SLEEP_TARGET, 420, null, default = 420),
        )
        assertEquals(listOf(FactKey.PAY_ITEMS, FactKey.MONTHLY_HOURS), ExportCheck.estimated(facts, "pay.report").map { it.key })
        ExportCheck.EXPORTS.forEach { assertNotNull(it, FeatureRegistry.get(it)) }
    }
}
