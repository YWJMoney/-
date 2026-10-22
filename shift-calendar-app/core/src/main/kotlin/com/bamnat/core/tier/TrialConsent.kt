package com.bamnat.core.tier

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * [MON-06] 전자상거래법 제13조 6항과 시행령 제20조의2: a free trial that turns into a paid subscription
 * needs the user's own consent within the 30 days before it does, asked apart from signing up (공정위
 * 다크패턴 문답 Q5, 2025-02-13). The app asks from 7 days before the trial ends (day 23 of a month's
 * trial, 결정 D17); without consent by the last day the server stops the next payment.
 */
object TrialConsent {
    val ASK_BEFORE: Duration = Duration.ofDays(7)
    val LAST_DAY: Duration = Duration.ofDays(1)
    /** The law's window: consent older than this before the first payment does not count. */
    val LAW_WINDOW: Duration = Duration.ofDays(30)

    enum class Stage {
        /** No trial running. */
        NONE,
        /** A trial, too early to ask. */
        WAIT,
        ASK,
        /** Less than a day left and still no answer: ask again, plainly. */
        LAST_DAY,
        /** Consented, or the user chose to cancel and renewal is off. */
        DONE,
    }

    /** A month's trial bought at [purchasedAt] ends a calendar month later, as Play counts P1M. */
    fun trialEnd(purchasedAt: Instant, zone: ZoneId): Instant = purchasedAt.atZone(zone).plusMonths(1).toInstant()

    fun askFrom(trialEnd: Instant): Instant = trialEnd.minus(ASK_BEFORE)

    fun stage(sub: PlaySub?, trialEnd: Instant?, consentedAt: Instant?, now: Instant): Stage {
        if (sub == null || !sub.trial || trialEnd == null || !now.isBefore(trialEnd)) return Stage.NONE
        if (!sub.autoRenew) return Stage.DONE
        if (consentedAt != null && valid(consentedAt, trialEnd)) return Stage.DONE
        if (now.isBefore(askFrom(trialEnd))) return Stage.WAIT
        return if (Duration.between(now, trialEnd) <= LAST_DAY) Stage.LAST_DAY else Stage.ASK
    }

    /** Consent counts when it was given in the 30 days before the trial turns paid, and before it did. */
    fun valid(consentedAt: Instant, trialEnd: Instant): Boolean =
        consentedAt.isBefore(trialEnd) && !consentedAt.isBefore(trialEnd.minus(LAW_WINDOW))
}
