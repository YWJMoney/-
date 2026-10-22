package com.bamnat.core.leave

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/*
 * [LEAVE-02] 연차 자동 발생. 근로기준법 [시행 2026. 10. 8.] [법률 제21533호] 제60조, 국가법령정보센터
 * (lsiSeq=285279) 원문을 2026-10-22에 열어 확인했어요(발췌: 프로젝트 파일 research/1.22-D-연차-원문.md,
 * 묶음 A의 대조 표 research/1.22-원문대조.md 1장).
 * - ② 계속 근로 1년 미만(또는 1년간 80% 미만 출근)은 1개월 개근 시 1일.
 * - ① 1년간 80% 이상 출근하면 15일. 1년 근로를 마친 다음 날 생겨요(대법원 2021. 10. 14. 2021다227100).
 * - ④ 3년 이상은 최초 1년을 넘는 매 2년에 1일을 더하고, 모두 25일까지.
 * - ⑦ 1년간 쓰지 않으면 소멸, 1년 미만의 ② 휴가는 최초 1년의 근로가 끝날 때까지.
 * 5인 미만 사업장(시행령 별표1)과 4주 평균 주 15시간 미만(제18조 제3항)에는 제60조가 적용되지 않아요.
 */

/** [LEAVE-02] What the leave year runs by. */
enum class LeaveBasis(val label: String) {
    /** The law's own: each year of service from the hire date. */
    HIRE("입사일 기준"),
    /**
     * 회계연도 기준: every worker's leave starts on the same date. Not in the law; 고용노동부 lets employers use
     * it if no worker gets less than by the hire date (행정해석, 원문 확인 필요). The first part-year gives
     * 15 × 재직일수 ÷ 365 on the next start.
     */
    FISCAL("회계연도 기준"),
}

/** [LEAVE-02] How the app works out leave; kept with the company rules. */
data class LeavePolicy(
    /** Make the law's leave on its own from the hire date. */
    val auto: Boolean = true,
    val basis: LeaveBasis = LeaveBasis.HIRE,
    /** For [LeaveBasis.FISCAL]: the month the leave year starts, 1 = 1월 1일. */
    val fiscalStartMonth: Int = 1,
    /**
     * Years of service (1 = the first full year) with under 80% attendance: no 15일 for them (제60조 ②
     * gives a day per month fully attended, which the user adds by hand).
     */
    val lowAttendanceYears: Set<Int> = emptySet(),
) {
    fun fiscalStart(onOrBefore: LocalDate): LocalDate {
        val m = fiscalStartMonth.coerceIn(1, 12)
        val thisYear = LocalDate.of(onOrBefore.year, m, 1)
        return if (thisYear.isAfter(onOrBefore)) thisYear.minusYears(1) else thisYear
    }
}

/** Which balance a lot of leave belongs to. */
enum class LeavePool { ANNUAL, COMP }

/** [LEAVE-01] Where a lot of leave comes from. */
enum class LotKind(val label: String) {
    /** ② 1년 미만 1개월 개근 1일. */
    MONTHLY("월 개근 연차"),
    /** ①④ 15일 + 가산. */
    ANNUAL("연차"),
    /** 회계연도 기준 첫해 비례분. */
    PRORATED("회계연도 비례 연차"),
    /** Entered by the user: what the company actually gave for a leave year (replaces the app's). */
    GRANT("받은 연차"),
    /** Moved from a year that ended (이월). */
    CARRY("이월"),
    /** A correction, plus or minus. */
    ADJUST("조정"),
    /** [PAYX-18] 보상휴가 for extra work paid in time off (제57조). */
    COMP("보상휴가"),
}

/** [LEAVE-01] Leave that arises on [on] and can be used until [expires] (null: no end). */
data class LeaveLot(
    val kind: LotKind,
    val days: Double,
    val on: LocalDate,
    val expires: LocalDate?,
    /** The leave year the lot belongs to (its start), to match a [LotKind.GRANT] with the app's own lot. */
    val yearStart: LocalDate? = null,
    val note: String = "",
    /** The stored row it came from; null for lots the app works out. */
    val id: Long? = null,
) {
    val pool: LeavePool get() = if (kind == LotKind.COMP) LeavePool.COMP else LeavePool.ANNUAL
    val auto: Boolean get() = id == null && kind != LotKind.COMP
}

object LeaveAccrual {
    const val BASE_DAYS = 15
    const val MAX_DAYS = 25
    const val FIRST_YEAR_MONTHS = 11

    /**
     * The last day of [n] months of service from [hire]. 민법 제160조: a period ends the day before the date
     * that matches its start; when the last month has no such date (1월 31일 + 1개월, 2월 29일 + 1년) it
     * ends on that month's last day.
     */
    fun periodEnd(hire: LocalDate, months: Long): LocalDate {
        val target = hire.plusMonths(months)
        return if (target.dayOfMonth != hire.dayOfMonth) target else target.minusDays(1)
    }

    /** ①④ Days for the year of service that ended with year [years] (1 = the first full year). */
    fun yearDays(years: Int): Int = if (years < 1) 0 else (BASE_DAYS + (years - 1) / 2).coerceAtMost(MAX_DAYS)

    /** [LEAVE-02] The lots the law makes from [hire] up to [until], by [policy]. */
    fun lots(hire: LocalDate, policy: LeavePolicy, until: LocalDate): List<LeaveLot> {
        if (until.isBefore(hire)) return emptyList()
        val firstYearEnd = periodEnd(hire, 12)
        val out = mutableListOf<LeaveLot>()
        // ② A day for each month fully attended in the first year, usable until the first year ends (⑦).
        for (m in 1..FIRST_YEAR_MONTHS) {
            val on = periodEnd(hire, m.toLong()).plusDays(1)
            if (on.isAfter(until) || on.isAfter(firstYearEnd)) break
            out += LeaveLot(LotKind.MONTHLY, 1.0, on, firstYearEnd, yearStart = hire, note = "입사 ${m}개월 개근")
        }
        when (policy.basis) {
            LeaveBasis.HIRE -> {
                var n = 1
                while (true) {
                    val on = periodEnd(hire, 12L * n).plusDays(1)
                    if (on.isAfter(until)) break
                    val low = n in policy.lowAttendanceYears
                    val days = if (low) 0 else yearDays(n)
                    out += LeaveLot(LotKind.ANNUAL, days.toDouble(), on, periodEnd(hire, 12L * (n + 1)), yearStart = on,
                        note = if (low) "${n}년차 80% 미만 출근: 개근한 달마다 1일" else "${n}년 근속")
                    n++
                }
            }
            LeaveBasis.FISCAL -> {
                // The first leave-year start after the hire date; a hire on that date has no part-year.
                var start = policy.fiscalStart(hire)
                if (!start.isAfter(hire)) start = start.plusYears(1)
                // A hire on a start date has a whole year done at the next one, so no part-year.
                val offset = if (ChronoUnit.DAYS.between(hire, start) < 365) 0 else 1
                var k = 0
                while (!start.isAfter(until)) {
                    val next = start.plusYears(1)
                    val served = ChronoUnit.DAYS.between(hire, start)
                    val prorated = k == 0 && offset == 0
                    val years = k + offset
                    val low = !prorated && years in policy.lowAttendanceYears
                    val days = when {
                        prorated -> Math.round(BASE_DAYS * served / 365.0 * 100) / 100.0
                        low -> 0.0
                        else -> yearDays(years).toDouble()
                    }
                    out += LeaveLot(if (prorated) LotKind.PRORATED else LotKind.ANNUAL, days, start, next.minusDays(1), yearStart = start,
                        note = when {
                            prorated -> "첫해 ${served}일 ÷ 365 × 15일"
                            low -> "${years}년차 80% 미만 출근: 개근한 달마다 1일"
                            else -> "회계연도 ${start.year} · ${years}년 근속"
                        })
                    start = next
                    k++
                }
            }
        }
        return out
    }

    /** "3.75일", "2일". */
    fun days(value: Double): String {
        val r = (value * 100).roundToLong() / 100.0
        return if (r == Math.floor(r)) "${r.toLong()}일" else "${String.format(java.util.Locale.ROOT, "%.2f", r).trimEnd('0').trimEnd('.')}일"
    }
}
