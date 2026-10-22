package com.bamnat.core.tier

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * [MON-03] A Play subscription as the phone or the server last saw it. Play keeps a canceled
 * subscription until its [expiry], and a payment that failed keeps it for the grace period; on hold,
 * paused, expired or still pending it gives nothing.
 */
data class PlaySub(
    val state: State,
    val expiry: Instant?,
    val trial: Boolean,
    val autoRenew: Boolean,
    val purchasedAt: Instant,
    /** The server asked Play itself; false when only the phone's Play said so. */
    val verified: Boolean = false,
) {
    enum class State { ACTIVE, GRACE, CANCELED, ON_HOLD, PAUSED, EXPIRED, PENDING }

    fun entitled(now: Instant): Boolean = when (state) {
        State.ACTIVE, State.GRACE, State.CANCELED -> expiry == null || now < expiry
        else -> false
    }
}

/** [MON-09] This phone tied to an admin account in the developer app, as the server last said. */
data class AdminLink(val approved: Boolean, val checkedAt: Instant)

/** Why this phone has 프로 now. */
enum class ProReason(val words: String) {
    NOT_LAUNCHED("정식 출시 전이라 모든 기능이 무료예요"),
    TRIAL("1개월 무료 체험 중이에요"),
    SUBSCRIPTION("프로를 구독 중이에요"),
    GRACE("결제가 안 됐어요. Play에서 결제 수단을 확인해 주세요"),
    ADMIN("관리자 휴대폰이라 프로를 무료로 써요"),
    PASS("보상 이용권으로 프로를 써요"),
    THANKS("테스트에 참여해 주셔서 드린 프로 기간이에요"),
    DAY_PASS("광고를 보고 오늘 하루 프로 기능을 열었어요"),
    NONE("무료로 쓰고 있어요"),
}

/** Everything that can give this phone 프로, as last known on the phone. */
data class ProSources(
    /** [MON-02] The developer app's 정식 출시 switch; off, every feature is open as before. */
    val billingLive: Boolean,
    val subscription: PlaySub? = null,
    /** [MON-08] The end of the stacked reward passes; null when there are none. */
    val passUntil: Instant? = null,
    val admin: AdminLink? = null,
    /** [MON-10] When this app was first installed and when billing was switched on, for the testers' thank-you. */
    val installedAt: Instant? = null,
    val launchedAt: Instant? = null,
    val thanksMonths: Int = 0,
    /** [MON-07] A rewarded ad watched today opens the pro features until midnight. */
    val dayPassUntil: Instant? = null,
)

data class Access(val pro: Boolean, val reason: ProReason, val until: Instant? = null)

/**
 * [MON-02] The one answer to "does this phone have 프로": a Play subscription (trial and grace
 * included), an approved admin phone, reward passes, the testers' thank-you after launch, or a day
 * opened by a rewarded ad, in that order. Before the 정식 출시 switch every feature is open.
 */
object ProAccess {
    /** [MON-09] An admin phone that could not reach the server for a week stops getting 프로 until it does. */
    val ADMIN_CHECK: Duration = Duration.ofDays(7)

    fun resolve(s: ProSources, now: Instant, zone: ZoneId): Access {
        if (!s.billingLive) return Access(true, ProReason.NOT_LAUNCHED)
        s.subscription?.takeIf { it.entitled(now) }?.let { sub ->
            val reason = when {
                sub.state == PlaySub.State.GRACE -> ProReason.GRACE
                sub.trial -> ProReason.TRIAL
                else -> ProReason.SUBSCRIPTION
            }
            return Access(true, reason, sub.expiry)
        }
        s.admin?.takeIf { it.approved && Duration.between(it.checkedAt, now) < ADMIN_CHECK }?.let { return Access(true, ProReason.ADMIN) }
        s.passUntil?.takeIf { now < it }?.let { return Access(true, ProReason.PASS, it) }
        thanksUntil(s.installedAt, s.launchedAt, s.thanksMonths, zone)?.takeIf { now < it }?.let { return Access(true, ProReason.THANKS, it) }
        s.dayPassUntil?.takeIf { now < it }?.let { return Access(true, ProReason.DAY_PASS, it) }
        return Access(false, ProReason.NONE)
    }

    /**
     * [MON-10] Phones installed before the launch get [months] of 프로 from the launch: they tested the
     * app while everything was free. None for a phone installed on or after it.
     */
    fun thanksUntil(installedAt: Instant?, launchedAt: Instant?, months: Int, zone: ZoneId): Instant? {
        if (installedAt == null || launchedAt == null || months <= 0 || !installedAt.isBefore(launchedAt)) return null
        return launchedAt.atZone(zone).plusMonths(months.toLong()).toInstant()
    }
}
