package com.bamnat.app.company

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.CompanyShare
import com.bamnat.app.data.ScheduleRepository
import com.bamnat.app.data.SettingsRepository
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.pay.PayStore
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayUnit
import com.bamnat.core.pay.Premium
import com.bamnat.core.pay.PremiumScope
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/**
 * [COMM-01][COMM-02][COMM-03] The company setup a coworker shares: amounts never leave the phone,
 * the receiver starts from today's place in the rotation and types only their own amounts, and the
 * file the app sends is one the company rules server takes. Companies here are made up.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CompanyAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val settings = SettingsRepository(context)
    private val store = PayStore(context)
    private val today = LocalDate.of(2026, 10, 17)

    @After fun close() {
        db.close()
        runBlocking { store.save(null) }
    }

    /** 4조 2교대 주주휴휴야야휴휴 with the 대근 the way 예시정밀 pays it, and the sender's own amounts. */
    private suspend fun senderSetup(): ScheduleRepository {
        val schedule = ScheduleRepository(db)
        schedule.ensureDefaults()
        val types = schedule.snapshot.first().shiftTypes.associateBy { it.name }
        val day = types.getValue("주간").id
        val night = types.getValue("야간").id
        schedule.setFirstPattern(WorkPattern.Rotation(today, listOf(day, day, null, null, night, night, null, null)), today)
        // 8시간에 기본급의 10%, 8시간 넘는 시간 50%, 야간 50% [EXTRA-08].
        val cover = ExtraRule(
            basis = ExtraBasis.UNIT, unit = PayUnit.BASE_SHARE, percentOfBase = 0.1, unitMinutes = 480,
            premiums = listOf(
                Premium("c1", "8시간 넘게", 0.5, true, PremiumScope.Over(480)),
                Premium.builtIn(Premium.NIGHT, 0.5, on = true),
            ),
        )
        store.save(PayProfile(
            Occupation.MANUFACTURING, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(today, 3_123_456))),
            monthlyHours = 180,
            items = listOf(
                PayItem("q", "자격수당", ValueTimeline(listOf(DatedValue(today, 54_321))), Cadence.MONTHLY),
                PayItem("b", "상여", ValueTimeline(), Cadence.BONUS, months = setOf(2, 4, 6, 8, 10, 12), percentOfBase = 1.0),
            ),
            hireDate = LocalDate.of(2019, 3, 4),
            rules = CompanyRules(
                annualLeaveDays = 17.0,
                extra = mapOf(ExtraKind.COVER_ID to cover, ExtraKind.SPECIAL_ID to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 77_777)),
                extraByShift = mapOf(ExtraKind.OVERTIME_ID to mapOf(night to ExtraRule(ExtraBasis.FIXED_PER_HOUR, amount = 12_345))),
            ),
        ))
        return schedule
    }

    @Test fun whatGoesToTheServerHasNoAmount() = runBlocking {
        val share = CompanyShare(senderSetup(), store, settings, today = { today })
        val file = share.exportForServer()
        val text = file.toString()
        listOf("3123456", "54321", "77777", "12345", "2019-03-04").forEach { assertFalse(it, it in text) }
        assertEquals("", file.getString("company"))
        val rules = file.getJSONObject("pay").getJSONObject("rules")
        val extra = rules.getJSONObject("extra")
        extra.keys().forEach { assertEquals(0, extra.getJSONObject(it).optLong("amount")) }
        val byShift = rules.getJSONObject("extraByShift")
        byShift.keys().forEach { s -> byShift.getJSONObject(s).let { r -> r.keys().forEach { assertEquals(0, r.getJSONObject(it).optLong("amount")) } } }
        assertFalse(file.getJSONObject("pay").getBoolean("withAmounts"))
    }

    @Test fun theReceiverStartsFromTodayAndTypesOnlyTheirAmounts() = runBlocking {
        val sender = CompanyShare(senderSetup(), store, settings, today = { today })
        val file = sender.exportForServer().put("company", "예시정밀").toString()
        store.save(null)

        val needs = sender.amountNeeds(file)
        // 상여 is a share of 기본급, so it is not asked; the fixed 특근 and the 잔업 on 야간 by the hour are.
        assertEquals(listOf("item:자격수당", "rule:${ExtraKind.SPECIAL_ID}"), needs.map { it.key })
        assertEquals("1회 금액", needs.last().hint)

        val db2 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val theirs = ScheduleRepository(db2)
            theirs.saveShiftType(ShiftType(0, "야간", "N", 0xFF000000, LocalTime.of(21, 0), LocalTime.of(6, 0), alarmEnabled = true))
            val share = CompanyShare(theirs, store, settings, today = { today })
            // Today this coworker works the first 야간 of the order (step 4).
            val applied = share.apply(file, rotation = true, todayStep = 4)
            assertTrue(applied.pendingPay)
            assertEquals(8, applied.cycleDays)
            val snap = theirs.snapshot.first()
            assertEquals("야간", snap.resolver.resolve(today).shiftType?.name)
            assertEquals("야간", snap.resolver.resolve(today.plusDays(1)).shiftType?.name)
            assertEquals("주간", snap.resolver.resolve(today.plusDays(4)).shiftType?.name)
            // A step past the end of the order wraps around.
            assertEquals(applied.cycleDays, share.apply(file, rotation = true, todayStep = 12).cycleDays)
            assertEquals("야간", theirs.snapshot.first().resolver.resolve(today).shiftType?.name)

            val profile = share.createPay(file, WageType.MONTHLY, 2_508_000, YearMonth.of(2026, 10), mapOf("rule:${ExtraKind.SPECIAL_ID}" to 90_000))
            assertEquals(2_508_000L, profile.base.on(today).value)
            assertEquals(180, profile.monthlyHours)
            assertEquals(90_000L, profile.rules.extra.getValue(ExtraKind.SPECIAL_ID).amount)
            val cover = profile.rules.extra.getValue(ExtraKind.COVER_ID)
            assertEquals(PayUnit.BASE_SHARE, cover.unit)
            assertEquals(setOf("c1", Premium.NIGHT), cover.ticked.map { it.key }.toSet())
            // Left blank: kept as a guess to fill in later, never someone else's amount.
            val license = profile.items.single { it.name == "자격수당" }
            assertEquals(0L, license.amount.on(today).value)
            assertTrue(license.id in profile.estimatedFields)
            val bonus = profile.items.single { it.name == "상여" }
            assertEquals(1.0, bonus.percentOfBase!!, 0.0)
            assertFalse(bonus.id in profile.estimatedFields)
            assertFalse("base" in profile.estimatedFields)
            assertEquals(profile, store.profile.value)
        } finally {
            db2.close()
        }
    }

    @Test fun personalTextIsCaughtBeforeItGoes() {
        assertEquals("amount", CompanyText.personal("대근은 기본급 300만원 기준"))
        assertEquals("amount", CompanyText.personal("수당 50,000"))
        assertEquals("phone", CompanyText.personal("문의 010-1234-5678"))
        assertEquals("email", CompanyText.personal("me@example.com 로 연락"))
        assertNull(CompanyText.personal("8시간에 기본급의 10%, 야간 0.5 가산"))
        assertNull(CompanyText.personal("생산 2팀"))
        assertEquals(CompanyText.norm("예시정밀"), CompanyText.norm("(주) 예시 정밀"))
        assertEquals(CompanyText.norm("예시정밀"), CompanyText.norm("주식회사 예시정밀"))
    }

    /**
     * The app's own file against the real server code (server/company-worker on Node with SQLite):
     * a format drift on either side fails here, not on a user's phone. Skipped where Node is missing.
     */
    @Test fun theServerTakesWhatTheAppSends() = runBlocking {
        val dir = File("../server/company-worker")
        assumeTrue(dir.resolve("test/local-server.mjs").exists())
        assumeTrue(runCatching { ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false))
        val port = ServerSocket(0).use { it.localPort }
        val node = ProcessBuilder("node", "test/local-server.mjs", port.toString()).directory(dir).redirectErrorStream(true).start()
        try {
            val out = node.inputStream.bufferedReader()
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 20_000) {
                val line = out.readLine() ?: break
                if ("company worker on" in line) break
            }
            val api = CompanyClient("http://127.0.0.1:$port")
            val share = CompanyShare(senderSetup(), store, settings, today = { today })
            val sent = api.report("app-test-device-0001", "(주)예시정밀", "생산 2팀", true, "야간 가산은 22시부터예요", share.exportForServer())
            assertEquals("pending", sent.status)
            assertEquals(2, sent.points)
            assertEquals("생산 2팀", sent.dept)

            val hit = api.search("예시 정밀").single()
            val page = api.company(hit.id)
            val setup = page.setups.single()
            assertFalse(setup.adopted)
            assertTrue(setup.lines.isNotEmpty())
            assertTrue(setup.hasPay)
            // What comes back reads as a company file and asks for the same amounts.
            assertEquals(8, share.summarize(setup.text).cycleDays)
            assertEquals(listOf("item:자격수당", "rule:${ExtraKind.SPECIAL_ID}"), share.amountNeeds(setup.text).map { it.key })

            // The same setup again from the same phone counts once.
            assertTrue(api.report("app-test-device-0001", "예시정밀", "생산 2팀", true, "", share.exportForServer()).again)
            val vote = api.vote("app-test-device-0002", page.id, setup.id, ok = true, verified = true)
            assertEquals(2, vote.persons)

            // A way the app cannot set yet waits for the developer and is never scored.
            val ask = api.report("app-test-device-0003", "예시정밀", "", false, "어떻게: 대근 4시간도 기본급 10%", share.exportForServer(), ask = true)
            assertEquals("ask", ask.status)
            assertEquals(0, ask.points)
            assertNotNull(api.status(ask.id))

            api.takeBack("app-test-device-0003", ask.id)
            assertNull(api.status(ask.id))
            assertEquals(1, api.forgetMine("app-test-device-0001"))
            assertNull(api.status(sent.id))
            assertEquals(3, api.terms().threshold)
        } finally {
            node.destroy()
            node.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
