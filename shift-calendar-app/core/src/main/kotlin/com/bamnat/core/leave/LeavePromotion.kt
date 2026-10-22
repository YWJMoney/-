package com.bamnat.core.leave

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * [LEAVE-03] 연차 사용 촉진 알림. 근로기준법 제61조(원문 2026-10-22 확인, research/1.22-D-연차-원문.md):
 * ① 회사는 소멸 6개월 전을 기준으로 10일 안에 남은 일수를 알리고 쓸 때를 정하라고 서면으로 촉구하고, 2개월
 * 전까지 쓸 때를 정해 알려요. ② 1년 미만의 월 단위 휴가는 최초 1년이 끝나기 3개월 전·1개월 전.
 * The app tells the worker on the same dates, so leave is used, not lost: the company's notices may never come.
 */
object LeavePromotion {
    val AT: LocalTime = LocalTime.of(9, 0)

    /** One notice: [stage] 1 is the first (6 or 3 months ahead), 2 the second (2 or 1). */
    data class Notice(val at: LocalDateTime, val lapse: LocalDate, val firstYear: Boolean, val stage: Int)

    /** Every notice for the annual lots in [lots] that lapse, in time order. */
    fun notices(lots: List<LeaveLot>): List<Notice> =
        lots.filter { it.pool == LeavePool.ANNUAL && it.expires != null && it.days > 0 }
            .map { it.expires!! to (it.kind == LotKind.MONTHLY) }.distinct()
            .flatMap { (lapse, first) ->
                val (a, b) = if (first) 3L to 1L else 6L to 2L
                listOf(Notice(lapse.minusMonths(a).atTime(AT), lapse, first, 1), Notice(lapse.minusMonths(b).atTime(AT), lapse, first, 2))
            }
            .sortedBy { it.at }

    /** The next notice after [now], or null. */
    fun next(lots: List<LeaveLot>, now: LocalDateTime): Notice? = notices(lots).firstOrNull { it.at.isAfter(now) }

    /** The notices due at [at] (several lots can lapse the same day). */
    fun due(lots: List<LeaveLot>, at: LocalDateTime): List<Notice> = notices(lots).filter { it.at == at }

    /**
     * Days that lapse on [lapse] unless more is used, after the planned days on the calendar: what the
     * notice says. Null when nothing would lapse.
     */
    fun lapsing(summary: LeaveSummary, lapse: LocalDate): Double? =
        summary.lots.filter { it.lot.pool == LeavePool.ANNUAL && it.lot.expires == lapse }.sumOf { it.lapsed }.takeIf { it > 1e-9 }

    fun title(days: Double): String = "남은 연차 ${LeaveAccrual.days(days)}"

    fun text(n: Notice): String = "${n.lapse.monthValue}월 ${n.lapse.dayOfMonth}일까지 안 쓰면 없어질 수 있어요. " +
        if (n.stage == 1) "쓸 날을 미리 정해 두면 좋아요." else "달력에서 쉴 날을 길게 눌러 연차를 넣어요."
}
