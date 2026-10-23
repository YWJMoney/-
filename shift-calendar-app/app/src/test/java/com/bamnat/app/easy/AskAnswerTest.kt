package com.bamnat.app.easy

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.profile.ExportCheck
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.Questions
import com.bamnat.core.profile.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [EASY-05][EASY-06][EASY-07] An answer on the question card lands in the store that has always kept that fact
 * and reads as the user's own; a payslip's values read as 추정 until checked; the check before a report lists them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AskAnswerTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val main = PersonalCalendar.MAIN

    @Before fun defaults() {
        val end = System.currentTimeMillis() + 60_000
        while (runBlocking { c.scheduleRepository.shiftTypes.first().isEmpty() }) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for the first shifts" }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private suspend fun samplePay() = c.payStore.save(PayViewModel(c).sampleProfile().copy(estimatedFields = setOf(Unknowns.MONTHLY_HOURS)), keepUndo = false)

    @Test fun `an answer goes to its own store and reads as typed`() = runBlocking {
        samplePay()
        assertTrue(c.answers.answer(main, FactKey.PAY_DAY, "15"))
        assertEquals(15, c.payStore.profile.value!!.period.payDay)
        assertEquals(Source.TYPED, c.profiles.facts(main).getValue(FactKey.PAY_DAY).source)
        // A 모르겠어요 mark in the pay setup goes with the answer.
        assertTrue(c.answers.answer(main, FactKey.MONTHLY_HOURS, "226"))
        assertEquals(226, c.payStore.profile.value!!.monthlyHours)
        assertFalse(Unknowns.MONTHLY_HOURS in c.payStore.profile.value!!.estimatedFields)
        // The alarm lead sets every working shift's alarm.
        assertTrue(c.answers.answer(main, FactKey.ALARM_LEAD, "90"))
        assertTrue(c.scheduleRepository.shiftTypes.first().filter { it.hasTimes && !it.isOff }.all { t -> t.alarmEnabled && t.alarms.first().offsetMinutes == 90 })
    }

    @Test fun `모르겠어요 writes nothing and stops asking`() = runBlocking {
        samplePay()
        val before = c.payStore.profile.value
        assertTrue(c.answers.answer(main, FactKey.HOLIDAY_PAY, Questions.UNKNOWN))
        assertEquals(before, c.payStore.profile.value)
        assertTrue(c.asks.of(main).parked(FactKey.HOLIDAY_PAY))
    }

    @Test fun `a payslip fills 추정, the setup's own answers are the user's, and the report check lists the rest`() = runBlocking {
        c.profiles.forget(main)
        val vm = PayViewModel(c)
        val profile = vm.sampleProfile()
        val job = vm.saveSetup(profile, fromSlip = true)
        // The view model runs on the main thread, which this test holds: let it run until the save is done.
        val end = System.currentTimeMillis() + 30_000
        while (!job.isCompleted) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for the pay setup to save" }
            shadowOf(Looper.getMainLooper()).idle()
        }
        val facts = c.profiles.facts(main)
        assertEquals(Source.INFERRED, facts.getValue(FactKey.BASE_PAY).source)
        assertEquals("명세서 사진", facts.getValue(FactKey.BASE_PAY).why)
        // 5인 이상 is the law's starting value, but the setup asked it.
        assertEquals(Source.TYPED, facts.getValue(FactKey.FIVE_OR_MORE).source)
        val toCheck = ExportCheck.estimated(facts, "pay.report").map { it.key }
        assertTrue(FactKey.BASE_PAY in toCheck)
        assertFalse(FactKey.FIVE_OR_MORE in toCheck)
        // Checked once, it is the user's own.
        c.profiles.mark(main, FactKey.BASE_PAY, Source.TYPED, why = "확인함")
        assertFalse(FactKey.BASE_PAY in ExportCheck.estimated(c.profiles.facts(main), "pay.report").map { it.key })
    }
}
