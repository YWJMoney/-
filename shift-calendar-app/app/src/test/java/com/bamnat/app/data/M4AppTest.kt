package com.bamnat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.pay.PayStore
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

/** [FEAT-08][MKT-08][COMM-00] Sealed backups, the day change log and the company settings file. */
@RunWith(AndroidJUnit4::class)
class M4AppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val settings = SettingsRepository(context)
    private val today = LocalDate.of(2026, 10, 17)

    @After fun close() {
        db.close()
        runBlocking { PayStore(context).save(null) }
    }

    @Test fun sealedBackupOpensOnlyWithItsPassword() = runBlocking {
        val backups = BackupManager(db, settings)
        val schedule = ScheduleRepository(db)
        schedule.ensureDefaults()
        val json = backups.export()
        val sealed = backups.seal(json, "밤낮1234".toCharArray(), iterations = 1_000)
        assertTrue(backups.isSealed(sealed))
        assertFalse(backups.isSealed(json))
        assertFalse("주간" in sealed)
        assertEquals(json, backups.unseal(sealed, "밤낮1234".toCharArray()))
        val wrong = runCatching { backups.unseal(sealed, "1234밤낮".toCharArray()) }.exceptionOrNull()
        assertEquals("암호가 맞지 않아요", wrong?.message)
        // Reading it as a plain backup says why instead of "not ours".
        assertEquals("암호가 걸린 백업이에요", runCatching { backups.summarize(sealed) }.exceptionOrNull()?.message)
    }

    @Test fun eachChangeToADayIsLoggedInWords() = runBlocking {
        var now = 1_000L
        val schedule = ScheduleRepository(db, extraName = { if (it == ExtraWork.OVERTIME) "연장" else it.label }, clock = { now })
        schedule.ensureDefaults()
        schedule.setExtraWork(today, ExtraWork.OVERTIME, 120, workDay = true)
        // Stepper taps within a minute become one line.
        now = 2_000L
        schedule.setOvertime(today, 150)
        now = 3_000L
        schedule.setOvertime(today, 180)
        now = 120_000L
        schedule.setOvertime(today, 0)
        now = 130_000L
        schedule.setAlarmSkipped(today, true)
        val log = schedule.changesOn(today).first()
        assertEquals(listOf("알람 끔", "연장 지움", "연장 3시간"), log.map { it.text })
        assertEquals(listOf(130_000L, 120_000L, 3_000L), log.map { it.atMillis })
        assertTrue(schedule.changesOn(today.plusDays(1)).first().isEmpty())
    }

    @Test fun companyFileCarriesTheCompanyNotThePerson() = runBlocking {
        val schedule = ScheduleRepository(db)
        schedule.ensureDefaults()
        val types = schedule.snapshot.first().shiftTypes.associateBy { it.name }
        val day = types.getValue("주간").id
        val night = types.getValue("야간").id
        schedule.setFirstPattern(WorkPattern.Rotation(today, listOf(day, day, night, night, null, null)), today)
        val store = PayStore(context)
        val night30k = PayItem("n", "야간수당", ValueTimeline(listOf(DatedValue(today, 30_000))), Cadence.PER_SHIFT_TYPE, shiftTypeIds = setOf(night))
        store.save(PayProfile(
            Occupation.MANUFACTURING, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(today, 3_123_456))),
            items = listOf(night30k), hireDate = LocalDate.of(2019, 3, 4),
            rules = CompanyRules(annualLeaveDays = 17.0, annualLeaveCarry = 2.5, nightStart = LocalTime.of(23, 0), nightEnd = LocalTime.of(7, 0),
                extraByShift = mapOf(ExtraWork.COVER to mapOf(night to ExtraRule(basis = ExtraBasis.FIXED_PER_TIME, amount = 100_000)))),
        ))
        val share = CompanyShare(schedule, store, settings, today = { today })
        val file = share.export("1공장 A조", withAmounts = false)
        // Nothing personal: no base pay, hire date, leave days or allowance amounts.
        listOf("3123456", "2019-03-04", "30000", "\"annualLeave\": 17").forEach { assertFalse(it, it in file) }
        val summary = share.summarize(file)
        assertEquals("1공장 A조", summary.company)
        assertEquals(6, summary.cycleDays)
        assertEquals(listOf("야간수당"), summary.items)

        // A coworker with their own pay setup and differently numbered shift types.
        val db2 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val theirs = ScheduleRepository(db2)
            theirs.saveShiftType(com.bamnat.core.model.ShiftType(0, "야간", "N", 0xFF000000, LocalTime.of(21, 0), LocalTime.of(6, 0), alarmEnabled = true))
            store.save(PayProfile(Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(today, 2_500_000))),
                rules = CompanyRules(annualLeaveDays = 15.0)))
            val done = CompanyShare(theirs, store, settings, today = { today }).apply(file)
            assertEquals(3, done.addedTypes)
            assertEquals(6, done.cycleDays)
            assertTrue(done.rules)
            val after = theirs.snapshot.first()
            val theirNight = after.shiftTypes.single { it.name == "야간" }
            // Times follow the company; the label and alarm they set stay.
            assertEquals(LocalTime.of(22, 0), theirNight.start)
            assertEquals("N", theirNight.label)
            assertTrue(theirNight.alarmEnabled)
            assertEquals(theirNight.id, after.resolver.resolve(today.plusDays(2)).shiftType?.id)
            val profile = store.profile.value
            assertNotNull(profile)
            profile!!
            assertEquals(2_500_000L, profile.base.on(today).value)
            assertEquals(15.0, profile.rules.annualLeaveDays!!, 0.0)
            assertEquals(LocalTime.of(23, 0), profile.rules.nightStart)
            assertEquals(100_000L, profile.rules.extraRule(ExtraWork.COVER, theirNight.id)?.amount)
            assertEquals(setOf(theirNight.id), profile.items.single { it.name == "야간수당" }.shiftTypeIds)
            assertEquals(0L, profile.items.single().amount.on(today).value)
        } finally {
            db2.close()
        }
        assertTrue(JSONObject(file).has("rotation"))
    }
}
