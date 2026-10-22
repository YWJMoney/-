package com.bamnat.core

import com.bamnat.core.tier.AdRules
import com.bamnat.core.tier.AdminLink
import com.bamnat.core.tier.ProAccess
import com.bamnat.core.tier.ProReason
import com.bamnat.core.tier.ProSources
import com.bamnat.core.tier.PlaySub
import com.bamnat.core.tier.TrialConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TierTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2026-12-10T03:00:00Z")
    private fun at(s: String) = Instant.parse(s)
    private fun sub(state: PlaySub.State = PlaySub.State.ACTIVE, expiry: Instant? = at("2027-01-01T00:00:00Z"), trial: Boolean = false, autoRenew: Boolean = true) =
        PlaySub(state, expiry, trial, autoRenew, purchasedAt = at("2026-12-01T00:00:00Z"))
    private fun live(f: ProSources.() -> ProSources = { this }) = ProSources(billingLive = true).f()

    /** [MON-02] Before the 정식 출시 switch every feature stays open, whatever else is known. */
    @Test fun beforeLaunchEverythingIsOpen() {
        val a = ProAccess.resolve(ProSources(billingLive = false), now, zone)
        assertTrue(a.pro)
        assertEquals(ProReason.NOT_LAUNCHED, a.reason)
    }

    @Test fun afterLaunchNothingMeansFree() {
        val a = ProAccess.resolve(live(), now, zone)
        assertFalse(a.pro)
        assertEquals(ProReason.NONE, a.reason)
    }

    /** [MON-03] Active, trial, grace and canceled-but-paid-up give 프로; on hold, paused, expired and pending do not. */
    @Test fun subscriptionStates() {
        assertEquals(ProReason.SUBSCRIPTION, ProAccess.resolve(live { copy(subscription = sub()) }, now, zone).reason)
        assertEquals(ProReason.TRIAL, ProAccess.resolve(live { copy(subscription = sub(trial = true)) }, now, zone).reason)
        assertEquals(ProReason.GRACE, ProAccess.resolve(live { copy(subscription = sub(PlaySub.State.GRACE)) }, now, zone).reason)
        assertTrue(ProAccess.resolve(live { copy(subscription = sub(PlaySub.State.CANCELED, autoRenew = false)) }, now, zone).pro)
        for (s in listOf(PlaySub.State.ON_HOLD, PlaySub.State.PAUSED, PlaySub.State.EXPIRED, PlaySub.State.PENDING)) {
            assertFalse(s.name, ProAccess.resolve(live { copy(subscription = sub(s)) }, now, zone).pro)
        }
        // Paid up to a moment that has passed.
        assertFalse(ProAccess.resolve(live { copy(subscription = sub(expiry = now)) }, now, zone).pro)
        // The phone's Play said so without an expiry (no server check): 프로 while Play lists it.
        assertTrue(ProAccess.resolve(live { copy(subscription = sub(expiry = null)) }, now, zone).pro)
    }

    /** [MON-09] An approved admin phone is 프로 while it reached the server within 7 days. */
    @Test fun adminPhoneNeedsAWeeklyCheck() {
        val fresh = live { copy(admin = AdminLink(approved = true, checkedAt = now.minus(Duration.ofDays(6)))) }
        assertEquals(ProReason.ADMIN, ProAccess.resolve(fresh, now, zone).reason)
        val stale = live { copy(admin = AdminLink(approved = true, checkedAt = now.minus(Duration.ofDays(7)))) }
        assertFalse(ProAccess.resolve(stale, now, zone).pro)
        val waiting = live { copy(admin = AdminLink(approved = false, checkedAt = now)) }
        assertFalse(ProAccess.resolve(waiting, now, zone).pro)
    }

    /** [MON-08] Reward passes count until their stacked end. */
    @Test fun rewardPass() {
        val a = ProAccess.resolve(live { copy(passUntil = at("2027-03-10T00:00:00Z")) }, now, zone)
        assertEquals(ProReason.PASS, a.reason)
        assertEquals(at("2027-03-10T00:00:00Z"), a.until)
        assertFalse(ProAccess.resolve(live { copy(passUntil = now) }, now, zone).pro)
    }

    /** [MON-10] Phones installed before the launch get the thank-you months from the launch; later installs do not. */
    @Test fun testersThankYou() {
        val launch = at("2026-11-20T01:00:00Z")
        val tester = live { copy(installedAt = at("2026-10-22T00:00:00Z"), launchedAt = launch, thanksMonths = 1) }
        val a = ProAccess.resolve(tester, now, zone)
        assertEquals(ProReason.THANKS, a.reason)
        assertEquals(at("2026-12-20T01:00:00Z"), a.until)
        assertFalse(ProAccess.resolve(tester, at("2026-12-20T01:00:00Z"), zone).pro)
        val newcomer = tester.copy(installedAt = launch)
        assertFalse(ProAccess.resolve(newcomer, now, zone).pro)
        assertNull(ProAccess.thanksUntil(at("2026-10-22T00:00:00Z"), launch, 0, zone))
        assertNull(ProAccess.thanksUntil(at("2026-10-22T00:00:00Z"), null, 1, zone))
    }

    /** A paying subscriber is shown as a subscriber, not as a pass or a thank-you. */
    @Test fun subscriptionComesFirst() {
        val all = live {
            copy(subscription = sub(), admin = AdminLink(true, now), passUntil = at("2027-05-01T00:00:00Z"),
                installedAt = at("2026-01-01T00:00:00Z"), launchedAt = at("2026-12-01T00:00:00Z"), thanksMonths = 1,
                dayPassUntil = at("2026-12-10T15:00:00Z"))
        }
        assertEquals(ProReason.SUBSCRIPTION, ProAccess.resolve(all, now, zone).reason)
        assertEquals(ProReason.ADMIN, ProAccess.resolve(all.copy(subscription = null), now, zone).reason)
        assertEquals(ProReason.PASS, ProAccess.resolve(all.copy(subscription = null, admin = null), now, zone).reason)
        assertEquals(ProReason.THANKS, ProAccess.resolve(all.copy(subscription = null, admin = null, passUntil = null), now, zone).reason)
        assertEquals(ProReason.DAY_PASS, ProAccess.resolve(all.copy(subscription = null, admin = null, passUntil = null, thanksMonths = 0), now, zone).reason)
    }

    /** [MON-06] The trial's end, when to ask, and when consent counts (전자상거래법 30일). */
    @Test fun trialConsentStages() {
        val bought = at("2026-11-30T15:30:00Z") // 12-01 00:30 in Seoul
        val end = TrialConsent.trialEnd(bought, zone)
        assertEquals(at("2026-12-31T15:30:00Z"), end)
        val trial = PlaySub(PlaySub.State.ACTIVE, end, trial = true, autoRenew = true, purchasedAt = bought)
        assertEquals(TrialConsent.Stage.WAIT, TrialConsent.stage(trial, end, null, at("2026-12-20T00:00:00Z")))
        // Day 23: seven days before the end.
        assertEquals(TrialConsent.Stage.ASK, TrialConsent.stage(trial, end, null, end.minus(Duration.ofDays(7))))
        assertEquals(TrialConsent.Stage.LAST_DAY, TrialConsent.stage(trial, end, null, end.minus(Duration.ofHours(23))))
        assertEquals(TrialConsent.Stage.DONE, TrialConsent.stage(trial, end, end.minus(Duration.ofDays(3)), end.minus(Duration.ofDays(2))))
        // Renewal turned off: nothing to ask.
        assertEquals(TrialConsent.Stage.DONE, TrialConsent.stage(trial.copy(autoRenew = false), end, null, end.minus(Duration.ofDays(2))))
        assertEquals(TrialConsent.Stage.NONE, TrialConsent.stage(trial.copy(trial = false), end, null, end.minus(Duration.ofDays(2))))
        assertEquals(TrialConsent.Stage.NONE, TrialConsent.stage(trial, end, null, end))
        assertEquals(TrialConsent.Stage.NONE, TrialConsent.stage(null, end, null, end.minus(Duration.ofDays(2))))
        // Consent given more than 30 days before the first payment does not count.
        assertFalse(TrialConsent.valid(end.minus(Duration.ofDays(31)), end))
        assertTrue(TrialConsent.valid(end.minus(Duration.ofDays(30)), end))
        assertFalse(TrialConsent.valid(end, end))
    }

    /** [MON-07][REL-02] The banner only under 근무·급여 and 돈, after three quiet days, never for 프로 or before launch. */
    @Test fun bannerRules() {
        val installed = at("2026-12-07T03:00:00Z")
        fun banner(place: AdRules.Place, live: Boolean = true, pro: Boolean = false, configured: Boolean = true, at: Instant = now) =
            AdRules.banner(place, live, pro, configured, installed, at)
        assertTrue(banner(AdRules.Place.MONEY))
        assertTrue(banner(AdRules.Place.STATS))
        for (p in listOf(AdRules.Place.CALENDAR, AdRules.Place.MEMOS, AdRules.Place.SETTINGS, AdRules.Place.OTHER)) assertFalse(p.name, banner(p))
        assertFalse(banner(AdRules.Place.MONEY, live = false))
        assertFalse(banner(AdRules.Place.MONEY, pro = true))
        assertFalse(banner(AdRules.Place.MONEY, configured = false))
        assertFalse(banner(AdRules.Place.MONEY, at = installed.plus(Duration.ofDays(3)).minusSeconds(1)))
        assertTrue(banner(AdRules.Place.MONEY, at = installed.plus(Duration.ofDays(3))))
    }

    /** [MON-07] A rewarded ad is offered once a day and opens the features until midnight. */
    @Test fun rewardedOncePerDay() {
        val today = LocalDate.of(2026, 12, 10)
        assertTrue(AdRules.rewardedOffer(live = true, pro = false, configured = true, lastDayPass = null, today = today))
        assertFalse(AdRules.rewardedOffer(live = true, pro = false, configured = true, lastDayPass = today, today = today))
        assertTrue(AdRules.rewardedOffer(live = true, pro = false, configured = true, lastDayPass = today.minusDays(1), today = today))
        assertFalse(AdRules.rewardedOffer(live = true, pro = true, configured = true, lastDayPass = null, today = today))
        assertFalse(AdRules.rewardedOffer(live = false, pro = false, configured = true, lastDayPass = null, today = today))
        assertEquals(at("2026-12-10T15:00:00Z"), AdRules.dayPassUntil(today, zone))
    }
}
