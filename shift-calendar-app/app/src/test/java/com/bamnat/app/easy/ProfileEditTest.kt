package com.bamnat.app.easy

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.guide.editQuestion
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.core.profile.Answer
import com.bamnat.core.profile.AskScheduler
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.FactText
import com.bamnat.core.profile.Needs
import com.bamnat.core.profile.Profile
import com.bamnat.core.profile.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * [EASY-08] A fact fixed in 내 정보 lands where it has always been kept and reaches the features that read it;
 * [EASY-09] 모두 처음부터 asks every value again without changing one, and is taken back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProfileEditTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val main = PersonalCalendar.MAIN

    @Before fun defaults() = runBlocking {
        val end = System.currentTimeMillis() + 60_000
        while (c.scheduleRepository.shiftTypes.first().isEmpty()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for the first shifts" }
            shadowOf(Looper.getMainLooper()).idle()
        }
        c.payStore.save(PayViewModel(c).sampleProfile(), keepUndo = false)
    }

    @Test fun `a fix in 내 정보 goes to its store and the pay reads it`() = runBlocking {
        val before = c.profiles.facts(main).getValue(FactKey.PAY_DAY)
        assertEquals(c.payStore.profile.value!!.period.payDay?.toString(), FactText.answerOf(before))
        assertTrue(c.answers.answer(main, FactKey.PAY_DAY, "5"))
        assertEquals(5, c.payStore.profile.value!!.period.payDay)
        val after = c.profiles.facts(main).getValue(FactKey.PAY_DAY)
        assertEquals("매달 5일", FactText.of(after))
        assertEquals(Source.TYPED, after.source)
        // The alarm, once set, opens its own screen rather than putting every shift's alarms to one time.
        assertTrue(editQuestion(FactKey.ALARM_LEAD, hasPay = true, known = true).answer is Answer.Open)
        assertFalse(editQuestion(FactKey.ALARM_LEAD, hasPay = true, known = false).answer is Answer.Open)
    }

    @Test fun `모두 처음부터 asks again, keeps every value, and is taken back`() = runBlocking {
        assertTrue(c.answers.answer(main, FactKey.PAY_DAY, "15"))
        c.asks.set(main, AskScheduler.later(c.asks.of(main), FactKey.HIRE_DATE, LocalDate.now()))
        val pay = c.payStore.profile.value
        c.profiles.startOver(main, c.asks)
        assertEquals(pay, c.payStore.profile.value)
        val again = c.profiles.facts(main).getValue(FactKey.PAY_DAY)
        assertEquals(15, again.value)
        assertEquals(Profile.RECHECK, again.why)
        assertTrue(Needs.isMissing(again))
        assertTrue(c.asks.of(main).snoozes.isEmpty())
        assertNotNull(c.profiles.startedOver(main))
        // Said again on the question card, it is the user's own.
        assertTrue(c.answers.answer(main, FactKey.PAY_DAY, "15"))
        assertEquals(Source.TYPED, c.profiles.facts(main).getValue(FactKey.PAY_DAY).source)

        assertTrue(c.profiles.undoStartOver(main, c.asks))
        assertNull(c.profiles.startedOver(main))
        assertEquals(1, c.asks.of(main).snoozes[FactKey.HIRE_DATE.id])
        assertEquals(Source.TYPED, c.profiles.facts(main).getValue(FactKey.PAY_DAY).source)
    }

    @Test fun `what 모두 처음부터 forgot is not in the backup`() = runBlocking {
        c.profiles.startOver(main, c.asks)
        assertTrue(c.profiles.export().keys.none { it.contains("reset") })
    }
}
