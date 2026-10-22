package com.bamnat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.leave.LeaveRepository
import com.bamnat.app.pay.PayCodec
import com.bamnat.app.pay.PayStore
import com.bamnat.core.leave.EntryKind
import com.bamnat.core.leave.LeaveBasis
import com.bamnat.core.leave.LeavePolicy
import com.bamnat.core.leave.LedgerEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.Leave
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.FlexPeriod
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** [LEAVE-01][LEAVE-06][PAYX-17][PAYX-18][PAYX-21] The 연차 원장 in the database, the backup and the pay setup. */
@RunWith(AndroidJUnit4::class)
class LeaveLedgerAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val dir = File(context.filesDir, "leave-test-pay").apply { deleteRecursively() }
    private val pay = PayStore(context, dir)
    private val schedule = ScheduleRepository(db)
    private val today = LocalDate.of(2026, 10, 22)
    private val repo = LeaveRepository(db, schedule, pay, clock = { 1L }, today = { today })
    private val base = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))))

    @After fun close() {
        db.close()
        dir.deleteRecursively()
    }

    /** The two numbers 1.21 kept in the pay setup become the year's rows once, and again after a 1.21 backup brings them back. */
    @Test fun oldSettingsMoveOnceAndAgainAfterAnOldBackup() = runBlocking {
        schedule.ensureDefaults()
        val day = schedule.snapshot.first().shiftTypes.first { it.name == "주간" }.id
        schedule.savePattern(WorkPattern.Rotation(LocalDate.of(2026, 1, 1), listOf(day)))
        pay.save(base.copy(rules = CompanyRules(annualLeaveDays = 15.0, annualLeaveCarry = 2.0)))
        schedule.setLeave(LocalDate.of(2026, 3, 4), Leave())
        schedule.setLeave(LocalDate.of(2026, 11, 2), Leave(quarters = 2))

        val s = repo.now()
        val rows = repo.all()
        assertEquals(listOf(EntryKind.GRANT, EntryKind.CARRY), rows.map { it.kind })
        assertEquals(LocalDate.of(2026, 1, 1), rows[0].yearStart)
        assertEquals(LocalDate.of(2026, 12, 31), rows[1].expires)
        assertEquals(16.0, s.available, 1e-9)
        assertEquals(0.5, s.planned, 1e-9)
        val rules = pay.profile.value!!.rules
        assertNull(rules.annualLeaveDays)
        assertEquals(0.0, rules.annualLeaveCarry, 1e-9)
        assertEquals(LeaveBasis.FISCAL, rules.leave.basis)

        // Run again: nothing more. A restored 1.21 setup brings the number back: the year's grant is there, so no second one.
        repo.moveOldSettings()
        pay.update { it.copy(rules = it.rules.copy(annualLeaveDays = 15.0)) }
        assertEquals(16.0, repo.summary.first().available, 1e-9)
        assertEquals(2, repo.all().size)
        assertNull(pay.profile.value!!.rules.annualLeaveDays)
    }

    /** The ledger and 보상휴가 extra work come back from a backup; a backup from 1.21 has neither. */
    @Test fun theLedgerAndCompLeaveRoundTripThroughTheBackup() = runBlocking {
        schedule.ensureDefaults()
        val start = LocalDate.of(2026, 1, 1)
        repo.add(LedgerEntry(kind = EntryKind.GRANT, date = start, days = 17.0, expires = start.plusYears(1).minusDays(1), yearStart = start, note = "회사 기준"))
        repo.add(LedgerEntry(kind = EntryKind.ADJUST, date = LocalDate.of(2026, 5, 1), days = -1.0, note = "근로자의 날 대체"))
        repo.add(LedgerEntry(kind = EntryKind.PAYOUT, date = LocalDate.of(2026, 12, 31), days = 3.0))
        schedule.addExtra(LocalDate.of(2026, 10, 18), ExtraEntry(ExtraKind.OVERTIME_ID, LocalTime.of(9, 0), LocalTime.of(17, 0), compLeave = true))
        val before = repo.all()
        val backups = BackupManager(db, SettingsRepository(context))
        val json = backups.export()
        assertEquals(3, JSONObject(json).getJSONArray("leave").length())

        repo.delete(before[0].id)
        backups.restore(json)
        assertEquals(before.map { it.copy(id = 0) }, repo.all().map { it.copy(id = 0) })
        assertTrue(db.dayExtraDao().getAll().single().compLeave)

        // A 1.21 backup has no ledger and no "comp": both come back empty or off.
        val old = JSONObject(json)
        old.remove("leave")
        backups.restore(old.toString())
        assertTrue(repo.all().isEmpty())
        val extra = JSONObject(json)
        extra.getJSONArray("extras").getJSONObject(0).remove("comp")
        backups.restore(extra.toString())
        assertFalse(db.dayExtraDao().getAll().single().compLeave)
    }

    /** The new pay setup fields round trip, and a setup without them writes no keys. */
    @Test fun newPayFieldsRoundTrip() {
        val p = base.copy(
            flexPeriod = FlexPeriod.TWO_WEEKS, flexStart = LocalDate.of(2026, 10, 5),
            rules = CompanyRules(
                leave = LeavePolicy(auto = false, basis = LeaveBasis.FISCAL, fiscalStartMonth = 3, lowAttendanceYears = setOf(2)),
                extra = mapOf(ExtraKind.OVERTIME_ID to ExtraRule(basis = ExtraBasis.TIERED, tiersFromDayStart = true)),
            ),
        )
        assertEquals(p, PayCodec.decode(PayCodec.encode(p)))
        val plain = PayCodec.encode(base).toString()
        listOf("flexStart", "\"leave\"", "tiersFromDay").forEach { assertFalse(it, plain.contains(it)) }
    }
}
