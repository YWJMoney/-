package com.bamnat.core.pay

import java.time.LocalDate
import java.time.LocalTime

/**
 * Legal rates for one period. Numbers live here as data with a source and version, never in
 * the calculation code, so a yearly update is a data change [PAY-02]. Later releases replace
 * [RateTable.builtIn] with a signed JSON file fetched weekly.
 */
data class Rates(
    val effectiveFrom: LocalDate,
    val version: String,
    val source: String,
    /** 근로기준법 제56조 야간근로: 22:00~06:00. */
    val nightStart: LocalTime = LocalTime.of(22, 0),
    val nightEnd: LocalTime = LocalTime.of(6, 0),
    /** Premiums on top of 100%: 연장 50%, 야간 50%, 휴일 8시간 이내 50%, 8시간 초과 100%. */
    val overtimePremium: Double = 0.5,
    val nightPremium: Double = 0.5,
    val holidayPremiumWithin8h: Double = 0.5,
    val holidayPremiumOver8h: Double = 1.0,
    val dailyLimitMinutes: Int = 8 * 60,
    val weeklyLimitMinutes: Int = 40 * 60,
    /** 최저임금 시급(원). */
    val minimumHourlyWage: Long,
    /** 월 소정근로시간 기본값: 주 40시간 + 주휴 8시간 기준 209시간. */
    val defaultMonthlyHours: Int = 209,
)

object RateTable {
    val builtIn: List<Rates> = listOf(
        Rates(
            effectiveFrom = LocalDate.of(2025, 1, 1),
            version = "2025.1",
            source = "근로기준법 제50·53·56조, 2025년 최저임금 고시(시급 10,030원)",
            minimumHourlyWage = 10_030,
        ),
        Rates(
            effectiveFrom = LocalDate.of(2026, 1, 1),
            version = "2026.1",
            source = "근로기준법 제50·53·56조, 2026년 최저임금 고시(시급 10,320원)",
            minimumHourlyWage = 10_320,
        ),
    )

    /** The rates in force on [date]; the earliest table before any table starts. */
    fun on(date: LocalDate, table: List<Rates> = builtIn): Rates =
        table.filter { !it.effectiveFrom.isAfter(date) }.maxByOrNull { it.effectiveFrom }
            ?: table.minBy { it.effectiveFrom }
}
