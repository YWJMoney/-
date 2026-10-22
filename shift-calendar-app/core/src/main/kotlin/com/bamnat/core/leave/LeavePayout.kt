package com.bamnat.core.leave

import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.won
import java.time.YearMonth
import kotlin.math.roundToLong

/*
 * [LEAVE-05] 연차 정산 (미사용 연차수당). 근로기준법 제60조 제5항: 휴가에는 취업규칙 등에서 정한 통상임금 또는
 * 평균임금을 줘요. Unused leave is paid at the 1일 통상임금 of the last month it could be used (노무 해설, 공식
 * 원문 확인 필요), so the app says "약". 시행령 제33조: paid on the pay day after the leave year ends.
 */
object LeavePayout {
    /** One day of 통상임금 in [month]: 통상시급 × the day's 소정근로시간 (8시간, or the 일당's hours). */
    fun dailyWage(profile: PayProfile, month: YearMonth): Long {
        val dayMinutes = profile.dailyHours.coerceIn(60, 8 * 60)
        return when (profile.wageType) {
            WageType.MONTHLY -> (PayCalculator.ordinaryWage(profile, month).total.toDouble() / profile.monthlyHours.coerceAtLeast(1) * dayMinutes / 60.0).roundToLong()
            WageType.HOURLY -> (profile.base.on(month).value * dayMinutes / 60.0).roundToLong()
            WageType.DAILY -> profile.base.on(month).value
        }
    }

    data class Estimate(val days: Double, val daily: Long, val amount: Long) {
        val formula: String get() = "${LeaveAccrual.days(days)} × 1일 통상임금 ${won(daily)}"
    }

    fun estimate(days: Double, profile: PayProfile, month: YearMonth): Estimate {
        val daily = dailyWage(profile, month)
        val d = days.coerceAtLeast(0.0)
        return Estimate(d, daily, (d * daily).roundToLong())
    }
}
