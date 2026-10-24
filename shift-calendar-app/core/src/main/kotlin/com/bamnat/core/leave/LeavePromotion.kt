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

    /** [LEAVE-11] One step of 제61조 as the worker writes down what happened, with its date. */
    enum class Step(val label: String) {
        FIRST_NOTICE("회사의 1차 촉구 (남은 일수를 알리고 쓸 날을 정하라고)"),
        WORKER_PLAN("내가 쓸 날을 정해 회사에 알림"),
        COMPANY_DESIGNATION("회사의 2차 통보 (쓸 날을 회사가 정함)"),
        ;

        companion object {
            fun of(name: String): Step? = entries.firstOrNull { it.name == name }
        }
    }

    /** [LEAVE-11] A step on record for the leave lapsing on [lapse] (the `leave_notices` table). */
    data class Record(val id: Long = 0, val lapse: LocalDate, val step: Step, val date: LocalDate, val note: String = "")

    /** [LEAVE-11] Whether the company's 촉진 for [lapse] was done as 제61조 has it, and what is missing. */
    data class Check(val lapse: LocalDate, val done: Boolean, val missing: List<String>) {
        /** What the 소멸 card says. */
        val text: String
            get() = if (done) "회사가 사용 촉진을 법대로 했다면 못 쓴 연차의 수당은 없을 수 있어요 (근로기준법 제61조)"
            else "회사가 사용 촉진을 법대로 하지 않았다면 없어진 연차만큼 수당을 청구할 수 있어요 (근로기준법 제60조 제5항·제61조). " +
                missing.joinToString(" ")
    }

    /**
     * [LEAVE-11] 제61조 제1항 (and 제2항 for the 1년 미만 lot, [firstYear]): the 1차 촉구 within 10 days from 6 (3) months
     * before [lapse], then either the worker's plan within 10 days of it, or the company's 2차 통보 by 2 (1) months before.
     */
    fun check(lapse: LocalDate, firstYear: Boolean, records: List<Record>): Check {
        val mine = records.filter { it.lapse == lapse }
        val (a, b) = if (firstYear) 3L to 1L else 6L to 2L
        val from = lapse.minusMonths(a)
        val missing = mutableListOf<String>()
        val first = mine.filter { it.step == Step.FIRST_NOTICE }.minByOrNull { it.date }
        if (first == null) missing += "1차 촉구 기록이 없어요."
        else if (first.date.isBefore(from) || first.date.isAfter(from.plusDays(10))) {
            missing += "1차 촉구는 ${from.monthValue}/${from.dayOfMonth}부터 10일 안에 있어야 해요."
        }
        val plan = first?.let { f -> mine.any { it.step == Step.WORKER_PLAN && !it.date.isBefore(f.date) && !it.date.isAfter(f.date.plusDays(10)) } } == true
        val by = lapse.minusMonths(b)
        val second = mine.any { it.step == Step.COMPANY_DESIGNATION && !it.date.isAfter(by) }
        if (first != null && !plan && !second) missing += "내가 날을 정하지 않았다면 회사가 ${by.monthValue}/${by.dayOfMonth}까지 쓸 날을 정해 알려야 해요."
        return Check(lapse, missing.isEmpty(), missing)
    }
}
