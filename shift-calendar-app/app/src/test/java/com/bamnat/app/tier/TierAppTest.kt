package com.bamnat.app.tier

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.billing.NoBilling
import com.bamnat.app.billing.Plan
import com.bamnat.app.billing.ProductIds
import com.bamnat.app.data.SettingsRepository
import com.bamnat.app.ui.pro.launchThanksText
import com.bamnat.app.ui.pro.lastDay
import com.bamnat.app.ui.pro.proDisclosures
import com.bamnat.app.ui.pro.trialConsentText
import com.bamnat.app.ui.pro.trialPrice
import com.bamnat.core.tier.PlaySub
import com.bamnat.core.tier.ProReason
import com.bamnat.core.tier.TrialConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [MON-02][MON-03][MON-05][MON-06][MON-08][MON-10] The 프로 plumbing on the phone: the server's launch
 * switch read strictly, the gate before and after it, the preview, what the phone keeps and backs up,
 * the trial's question and its notice, and the words of the subscription screen.
 */
@RunWith(AndroidJUnit4::class)
class TierAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2027-03-10T01:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun stop() = scope.cancel()

    private val monthly = Plan(ProductIds.MONTHLY, "4,990원", 4_990_000_000, "KRW", "P1M", trial = true, offerToken = "m")
    private val yearly = Plan(ProductIds.YEARLY, "39,000원", 39_000_000_000, "KRW", "P1Y", trial = true, offerToken = "y")

    private fun gate(config: LaunchConfig, debug: Boolean = false, edit: ProStore.() -> Unit = {}, preview: Tier? = null): Gate {
        val settings = SettingsRepository(context)
        settings.update { it.copy(previewTier = preview) }
        val remote = RemoteConfigStore(context).apply { save(config) }
        val pro = ProStore(context).apply(edit)
        return FeatureGate(settings, remote, pro, scope, installedAt = { now.minusSeconds(86_400 * 30) }, debug = debug,
            clock = { now }, zone = { zone }).state.value
    }

    private fun trialSub(start: Instant, autoRenew: Boolean = true) = SubRecord(
        product = ProductIds.PRO, basePlan = ProductIds.MONTHLY, state = PlaySub.State.ACTIVE, expiry = start.plusSeconds(86_400L * 31),
        trial = true, autoRenew = autoRenew, purchasedAt = start, verified = false, checkedAt = start,
    )

    // ---- The server's switch ----

    @Test fun theLaunchSwitchIsReadStrictly() {
        try {
            LaunchConfig.parse(JSONObject("""{"launchedAt":null}"""), now)
            fail("billingLive 없는 답은 받지 않아요")
        } catch (_: JSONException) {
        }
        val c = LaunchConfig.parse(JSONObject("""{"billingLive":true,"launchedAt":"2027-03-01T00:00:00Z","thanksMonths":40,
            "freeFeatures":["MONEY_STATS","SOMETHING_NEW"],"adQuietDays":99}"""), now)
        assertTrue(c.billingLive)
        assertEquals(12, c.thanksMonths)
        assertEquals("새 앱의 이름은 건너뛰어요", setOf(Feature.MONEY_STATS), c.freeFeatures)
        assertEquals(30, c.adQuietDays)
        assertEquals(c, LaunchConfig.fromSaved(c.toJson().toString()))
    }

    @Test fun theConfigIsAskedOnceADayAndKeptWhenOffline() = runBlocking {
        val store = RemoteConfigStore(context)
        val api = FakeTierApi(configAnswer = LaunchConfig(billingLive = true))
        assertTrue(store.refresh(api, now))
        assertTrue(store.current.billingLive)
        assertFalse("하루 안에는 다시 묻지 않아요", store.refresh(api, now.plusSeconds(3_600)))
        api.configAnswer = null
        assertFalse(store.refresh(api, now.plusSeconds(90_000)))
        assertTrue("연결이 없으면 마지막 답이 그대로예요", store.current.billingLive)
        assertTrue("시계가 뒤로 가면 다시 물어요", store.due(now.minusSeconds(60)))
        assertTrue("다시 열어도 남아 있어요", RemoteConfigStore(context).current.billingLive)
        assertFalse("서버 주소가 없는 빌드는 묻지 않아요", store.refresh(FakeTierApi(enabled = false), now.plusSeconds(200_000), force = true))
    }

    // ---- The gate ----

    @Test fun theFeatureTableIsTheDecidedOne() {
        // [MON-01] 수익화 확정 2-1: basic pay, 받을 돈 [RECV-02] and the 1.14~1.17 tools are free; Phase 3's 리더 is not sold.
        assertEquals(
            mapOf(
                "NO_ADS" to Tier.PRO, "PAYSLIP_MATCH" to Tier.PRO, "PAY_RULE_IMPORT" to Tier.PRO, "PAY_BULK_EDIT" to Tier.PRO,
                "MONEY_STATS" to Tier.PRO, "MORE_CALENDARS" to Tier.PRO,
                // [PROV-08] 1.20.0: the rest of 수익화 확정 5-2's four pillars.
                "PAY_REPORT" to Tier.PRO, "WORK_COMPARE" to Tier.PRO, "DEVICE_CALENDAR" to Tier.PRO, "ICS_FEED" to Tier.PRO,
                "AUTO_BACKUP" to Tier.PRO, "MORE_LINKS" to Tier.PRO, "ROSTER_PDF" to Tier.PRO, "SURE_ALARM" to Tier.PRO,
                "TEAM_ROSTER" to Tier.LEADER,
            ),
            Feature.entries.associate { it.name to it.minTier },
        )
        assertEquals("예전에 저장된 플러스는 프로", Tier.PRO, Tier.parse("PLUS"))
        assertEquals(1, com.bamnat.app.data.PersonalCalendar.FREE_ADDED)
    }

    @Test fun beforeTheLaunchEverythingIsOpen() {
        val g = gate(LaunchConfig(billingLive = false))
        assertFalse(g.live)
        Feature.entries.forEach { assertTrue(it.name, g.allows(it)) }
        assertEquals(ProReason.NOT_LAUNCHED, g.access.reason)
    }

    @Test fun afterTheLaunchFreeLocksProAndTheServerCanOpenOne() {
        val g = gate(LaunchConfig(billingLive = true, freeFeatures = setOf(Feature.MONEY_STATS)))
        assertTrue(g.live)
        assertEquals(Tier.FREE, g.tier)
        assertTrue(g.locked(Feature.NO_ADS))
        assertTrue(g.locked(Feature.PAYSLIP_MATCH))
        assertTrue("서버가 연 기능은 무료", g.allows(Feature.MONEY_STATS))
    }

    @Test fun aTrialOpensProAndSaysUntilWhen() {
        val start = now.minusSeconds(86_400 * 3)
        val g = gate(LaunchConfig(billingLive = true), edit = { setSub(trialSub(start)) })
        assertEquals(Tier.PRO, g.tier)
        assertEquals(ProReason.TRIAL, g.access.reason)
        assertTrue(g.allows(Feature.NO_ADS))
        assertTrue("리더 기능은 Phase 3", g.locked(Feature.TEAM_ROSTER))
    }

    @Test fun theDayPassLastsTillMidnight() {
        val today = now.atZone(zone).toLocalDate()
        assertEquals(ProReason.DAY_PASS, gate(LaunchConfig(billingLive = true), edit = { dayPass(today) }).access.reason)
        assertEquals(ProReason.NONE, gate(LaunchConfig(billingLive = true), edit = { dayPass(today.minusDays(1)) }).access.reason)
    }

    @Test fun thePreviewCountsBeforeTheLaunchOrInDebugOnly() {
        val before = gate(LaunchConfig(billingLive = false), preview = Tier.FREE)
        assertTrue(before.preview)
        assertTrue(before.locked(Feature.MONEY_STATS))
        assertEquals(ProReason.PREVIEW, before.access.reason)
        assertFalse("출시 뒤 릴리스 빌드에서는 미리 보기가 없어요", gate(LaunchConfig(billingLive = true), preview = Tier.PRO).preview)
        assertTrue(gate(LaunchConfig(billingLive = true), debug = true, preview = Tier.PRO).preview)
    }

    @Test fun theOldTierSettingIsNotAPreview() {
        // 1.18 and before kept "tier" = FREE for everyone; it must not lock anything now.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("tier", "FREE").commit()
        assertNull(SettingsRepository(context).current.previewTier)
    }

    // ---- What the phone keeps ----

    @Test fun theProStateSurvivesAndTheBackupCarriesOnlyTheCode() {
        val store = ProStore(context)
        val start = now.minusSeconds(86_400)
        store.setSub(trialSub(start))
        store.setPasses(Passes(now.plusSeconds(86_400 * 60), 0, listOf(PassItem(2, now, "rule")), 0, AdminState("approved", "관리자"), true, now))
        store.answer("keep", now)
        store.setCode("ABCD-EFGH-JKLM")
        store.thanksSeen()
        val again = ProStore(context).current
        assertEquals(store.current, again)
        assertEquals(now, again.consentAt)

        val backup = store.toBackup()
        assertEquals(setOf("code", "thanksSeen"), backup.keys().asSequence().toSet())

        val other = ProStore(context).apply { setCode("ZZZZ-ZZZZ-ZZZZ") }
        other.fromBackup(backup)
        assertEquals("다른 휴대폰의 코드는 다음 동기화 때 이용권을 옮겨요", "ABCD-EFGH-JKLM", other.current.pendingClaim)
        val same = ProStore(context).apply { claimLater(null); setCode("ABCD-EFGH-JKLM") }
        same.fromBackup(backup)
        assertNull("제 코드는 다시 옮기지 않아요", same.current.pendingClaim)
    }

    @Test fun onlyAPhoneWithAReasonAsksForPassesAndABackupCodeIsClaimedOnce() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<com.bamnat.app.BamnatApp>().container
        val pass = Passes(null, 2, listOf(PassItem(2, now, "idea")), 0, null, true, Instant.now())
        val api = FakeTierApi(passesAnswer = pass)
        c.tierApi = api
        c.syncPasses()
        assertEquals("보낸 것도 이용권도 코드도 없으면 묻지 않아요", emptyList<String>(), api.calls)
        c.proStore.claimLater("ABCD-EFGH-JKLM")
        c.syncPasses()
        assertEquals("옮겨 온 답이 곧 이용권이라 한 번 더 묻지 않아요", listOf("claim ABCD-EFGH-JKLM"), api.calls)
        assertNull(c.proStore.current.pendingClaim)
        assertEquals(2, c.proStore.current.passes?.waitingMonths)
        api.calls.clear()
        c.syncPasses()
        assertEquals("6시간 안에는 다시 묻지 않아요", emptyList<String>(), api.calls)
    }

    // ---- The trial's question [MON-06] ----

    @Test fun theTrialAsksSevenDaysBeforeAndTheNoticeIsSet() {
        val start = Instant.parse("2027-03-01T03:00:00Z")
        val sub = trialSub(start)
        val end = sub.trialEnd(zone)
        assertEquals(Instant.parse("2027-04-01T03:00:00Z"), end)
        assertEquals(TrialConsent.Stage.WAIT, TrialConsent.stage(sub.play(), end, null, Instant.parse("2027-03-20T00:00:00Z")))
        assertEquals(TrialConsent.Stage.ASK, TrialConsent.stage(sub.play(), end, null, Instant.parse("2027-03-26T00:00:00Z")))
        assertEquals(TrialConsent.Stage.LAST_DAY, TrialConsent.stage(sub.play(), end, null, Instant.parse("2027-03-31T12:00:00Z")))
        assertEquals(TrialConsent.Stage.DONE, TrialConsent.stage(sub.play(), end, Instant.parse("2027-03-26T00:00:00Z"), Instant.parse("2027-03-27T00:00:00Z")))

        val am = context.getSystemService(AlarmManager::class.java)
        TrialNotice.reschedule(context, ProState(sub = sub), zone, Instant.parse("2027-03-20T00:00:00Z"))
        assertEquals(TrialConsent.askFrom(end).toEpochMilli(), shadowOf(am).nextScheduledAlarm!!.triggerAtTime)
        TrialNotice.reschedule(context, ProState(sub = sub, trialAnswer = "keep", trialAnswerAt = Instant.parse("2027-03-26T00:00:00Z")), zone,
            Instant.parse("2027-03-26T01:00:00Z"))
        assertNull("답했으면 알림은 지워요", shadowOf(am).nextScheduledAlarm)
    }

    @Test fun theTrialQuestionSaysTheDayAndThePrice() {
        val sub = trialSub(Instant.parse("2027-03-01T03:00:00Z"))
        val price = trialPrice(sub, listOf(yearly, monthly))
        assertEquals("월 4,990원", price)
        assertEquals("월 요금", trialPrice(sub, emptyList()))
        assertEquals(
            "1개월 무료 체험이 4월 1일에 끝나고, 그날부터 월 4,990원씩 자동으로 결제돼요. 계속 쓸까요?\n\n" +
                "해지하려면 Play 스토어 › 결제 및 정기 결제에서 해요. 해지해도 4월 1일까지는 프로를 써요.",
            trialConsentText(LocalDate.of(2027, 4, 1), price),
        )
    }

    // ---- The subscription screen's words [MON-05] ----

    @Test fun theSubscriptionScreenSaysWhatTheLawAsks() {
        val lines = proDisclosures(listOf(monthly, yearly), LocalDate.of(2027, 3, 10))
        val all = lines.joinToString("\n")
        assertTrue(all, all.contains("월 4,990원, 연 39,000원"))
        assertTrue("체험이 끝나는 날과 그 뒤 결제", all.contains("4월 10일부터 고른 요금제의 금액이 결제돼요"))
        assertTrue("7일 전에 따로 물어요", all.contains("끝나기 7일 전에 계속 쓸지 따로 여쭤봐요"))
        assertTrue("해지 방법", all.contains("Play 스토어 › 결제 및 정기 결제"))
        assertTrue("청약철회와 그 제한", all.contains("7일 안에는 청약철회") && all.contains("전자상거래법 제17조 제2항"))
        assertTrue("약관 동의", all.contains("이용약관과 개인정보처리방침"))
        val noTrial = proDisclosures(listOf(monthly.copy(trial = false)), LocalDate.of(2027, 3, 10)).joinToString("\n")
        assertFalse("체험이 없으면 체험 문장도 없어요", noTrial.contains("무료 체험"))
        assertTrue(NoBilling().plans.value.isEmpty())
    }

    @Test fun theThanksSaysTheLastDay() {
        assertEquals(LocalDate.of(2027, 6, 1), lastDay(Instant.parse("2027-06-01T03:00:00Z"), zone))
        assertEquals("자정에 끝나면 그 전날까지", LocalDate.of(2027, 5, 31), lastDay(LocalDate.of(2027, 6, 1).atStartOfDay(zone).toInstant(), zone))
        assertTrue(launchThanksText(LocalDate.of(2027, 5, 31)).contains("2027년 5월 31일까지 프로를 무료로"))
    }
}
