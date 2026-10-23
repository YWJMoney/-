package com.bamnat.app.easy

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.core.journal.JournalKind
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayPeriod
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.profile.AskScheduler
import com.bamnat.core.profile.AskState
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.Source
import com.bamnat.core.work.WorkStyle
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
import java.time.LocalDate

/**
 * [EASY-03][EASY-05][OBS-01] The profile reads every fact from the store that holds it, a value changed on any
 * screen reads as typed, the question card's memory and the app's log survive a restart of their stores.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProfileStoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val main = PersonalCalendar.MAIN

    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 60_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Before fun defaults() {
        waitFor("the first shifts") { runBlocking { c.scheduleRepository.shiftTypes.first().isNotEmpty() } }
    }

    @Test fun `every fact has an adapter`() = runBlocking {
        assertEquals(FactKey.entries.toSet(), c.profiles.adapted(main))
    }

    @Test fun `pay facts come from the pay setup, with its own guesses`() = runBlocking {
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.SHIFT) }
        var facts = c.profiles.facts(main)
        assertFalse(facts.getValue(FactKey.BASE_PAY).known)
        assertFalse(facts.getValue(FactKey.HIRE_DATE).known)
        c.payStore.save(PayProfile(Occupation.entries.first(), WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2025, 1, 1), 2_600_000))),
            hireDate = LocalDate.of(2025, 1, 1), period = PayPeriod(payDay = 25), estimatedFields = setOf(Unknowns.MONTHLY_HOURS)))
        facts = c.profiles.facts(main)
        assertEquals(2_600_000L, facts.getValue(FactKey.BASE_PAY).value)
        assertEquals(Source.TYPED, facts.getValue(FactKey.BASE_PAY).source)
        assertEquals(25, facts.getValue(FactKey.PAY_DAY).value)
        assertEquals(Source.INFERRED, facts.getValue(FactKey.MONTHLY_HOURS).source)
        assertTrue(facts.getValue(FactKey.MONTHLY_HOURS).estimated)
        // Shift work: the shop's facts are not this calendar's.
        assertFalse(FactKey.SHOP_RULES in facts)
    }

    @Test fun `marked as read off a payslip, fixed on the pay screen it is typed`() = runBlocking {
        c.payStore.save(PayProfile(Occupation.entries.first(), WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2025, 1, 1), 2_500_000)))))
        c.profiles.mark(main, FactKey.BASE_PAY, Source.INFERRED, 0.8, "명세서 사진")
        var base = c.profiles.facts(main).getValue(FactKey.BASE_PAY)
        assertEquals(Source.INFERRED, base.source)
        assertEquals("명세서 사진", base.why)
        c.payStore.update { it.copy(base = ValueTimeline(listOf(DatedValue(LocalDate.of(2025, 1, 1), 2_550_000)))) }
        base = c.profiles.facts(main).getValue(FactKey.BASE_PAY)
        assertEquals(Source.TYPED, base.source)
        // The meta file holds no amount.
        assertTrue(c.profiles.export().values.none { it.contains("2500000") || it.contains("2550000") })
        // And comes back from a backup.
        val saved = c.profiles.export()
        c.profiles.forget(main)
        c.profiles.import(saved)
        assertEquals(saved, c.profiles.export())
    }

    @Test fun `settings facts read their own defaults`() = runBlocking {
        val facts = c.profiles.facts(main)
        assertEquals(Source.DEFAULT, facts.getValue(FactKey.SLEEP_TARGET).source)
        c.settingsRepository.update { it.copy(sleepTargetMinutes = 8 * 60) }
        assertEquals(Source.TYPED, c.profiles.facts(main).getValue(FactKey.SLEEP_TARGET).source)
    }

    @Test fun `the question card remembers across a restart`() {
        val today = LocalDate.of(2026, 10, 23)
        val state = AskScheduler.later(AskState(day = today, shownToday = 1), FactKey.HIRE_DATE, today)
        c.asks.set(main, state)
        assertEquals(state, com.bamnat.app.data.AskStore(app).of(main))
        assertEquals(state, com.bamnat.app.data.AskStore.decode(com.bamnat.app.data.AskStore.encode(state).toString()))
        c.asks.clear(main)
        assertEquals(AskState(), com.bamnat.app.data.AskStore(app).of(main))
    }

    @Test fun `the log keeps one line per kind a day and comes back`() = runBlocking {
        val store = c.journal
        store.add(com.bamnat.core.journal.JournalEntry(java.time.Instant.now(), JournalKind.ALARMS_PLANNED, detail = "06:30"))
        store.add(com.bamnat.core.journal.JournalEntry(java.time.Instant.now(), JournalKind.ALARMS_PLANNED, detail = "06:10"))
        store.add(com.bamnat.core.journal.JournalEntry(java.time.Instant.now(), JournalKind.BACKUP))
        val again = com.bamnat.app.data.JournalStore(java.io.File(app.noBackupFilesDir, "journal.jsonl"), c.appScope).load()
        assertEquals(2, again.size)
        assertEquals(2, again.first { it.kind == JournalKind.ALARMS_PLANNED }.count)
        assertTrue(java.io.File(app.noBackupFilesDir, "journal.jsonl").exists())
    }
}
