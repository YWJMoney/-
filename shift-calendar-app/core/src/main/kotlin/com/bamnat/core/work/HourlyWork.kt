package com.bamnat.core.work

import com.bamnat.core.model.Attendance
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.LegalBreak
import com.bamnat.core.schedule.ResolvedDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToLong

/** What a part-timer's calendar shows and exports: times, a day's pay and the 주휴 weeks. */
object HourlyWork {
    /** [HRLY-02] 주휴 weeks of [month] from the days themselves: their hours, and 결근 days as absences. */
    fun weeks(month: YearMonth, weekStart: DayOfWeek, resolve: (LocalDate) -> ResolvedDay): List<HourlyWeek> =
        WorkStats.hourlyWeeks(month, weekStart, { resolve(it).totalMinutes }, { isAbsent(resolve(it)) })

    /** [HRLY-02] The 주휴 week that starts on [start]. */
    fun week(start: LocalDate, resolve: (LocalDate) -> ResolvedDay): HourlyWeek {
        val days = (0L..6L).map { resolve(start.plusDays(it)) }
        return HourlyWeek(start, days.sumOf { it.totalMinutes }, days.count(::isAbsent))
    }

    /**
     * [HRLY-02][HRLY-07] The 주휴 week that ends within the calendar row starting on [rowStart], so each row
     * of the month carries one badge however the 주휴 week is set to start.
     */
    fun weekEndingIn(rowStart: LocalDate, holidayWeekStart: DayOfWeek, resolve: (LocalDate) -> ResolvedDay): HourlyWeek {
        val end = rowStart.plusDays(6).with(TemporalAdjusters.previousOrSame(holidayWeekStart.minus(1)))
        return week(end.minusDays(6), resolve)
    }

    fun isAbsent(day: ResolvedDay): Boolean = day.entry?.attendance == Attendance.ABSENT

    /** [HRLY-03] "18–23" or "9:30–18" for a day with times; null for a day without work on the clock. */
    fun timeRange(day: ResolvedDay): String? {
        val s = day.start ?: return null
        val e = day.end ?: return null
        return "${short(s)}–${short(e)}"
    }

    private fun short(t: LocalTime): String = if (t.minute == 0) "${t.hour}" else "${t.hour}:%02d".format(Locale.ROOT, t.minute)

    /** [HRLY-03] The hourly wage of [date] from [profile]: 시급, or 일당 ÷ its hours; null for a monthly salary. */
    fun hourlyRate(profile: PayProfile?, date: LocalDate): Long? = when (profile?.wageType) {
        WageType.HOURLY -> profile.base.on(date).value
        WageType.DAILY -> (profile.base.on(date).value * 60.0 / profile.dailyHours.coerceIn(1, 8 * 60)).roundToLong()
        else -> null
    }?.takeIf { it > 0 }

    /**
     * [HRLY-03] A day's pay as the calendar shows it: its hours (with the legal break when turned on) × the
     * hourly wage, at the 수습 rate before probation ends [HRLY-06]. Premiums and 주휴 are on the pay screen.
     */
    fun dayPay(day: ResolvedDay, profile: PayProfile?): Long {
        val rate = hourlyRate(profile, day.date) ?: return 0
        val d = if (profile!!.legalBreaks) LegalBreak.apply(day) else day
        val pct = if (profile.onProbation(day.date)) profile.probationPercent / 100.0 else 1.0
        return (d.totalMinutes / 60.0 * rate * pct).roundToLong()
    }

    /** [HRLY-03] "5.2만", "12만", "8,000" for a cell. */
    fun brief(won: Long): String = when {
        won >= 100_000 -> "${won / 10_000}만"
        won >= 10_000 -> "%.1f만".format(Locale.ROOT, won / 10_000.0).replace(".0만", "만")
        else -> "%,d".format(Locale.ROOT, won)
    }

    /** [HRLY-08] 출근·퇴근 now, to the nearest 5 minutes. */
    fun clock(now: LocalTime): LocalTime {
        val minutes = (now.hour * 60 + now.minute + (if (now.second >= 30) 1 else 0) + 2) / 5 * 5
        return LocalTime.of(minutes / 60 % 24, minutes % 60)
    }
}

/**
 * [HRLY-08] 근무 기록 as a CSV file: one row per day with work or an attendance mark, for proof of hours
 * (임금채권 소멸시효 3년). Opens in a spreadsheet; the first cell carries a BOM so Excel reads Korean.
 */
object WorkLogCsv {
    val HEADER = listOf("날짜", "출근", "퇴근", "휴게(분)", "근무 시간", "출결", "일급")

    fun write(days: List<ResolvedDay>, payOf: (ResolvedDay) -> Long): String {
        val sb = StringBuilder("﻿")
        sb.append(HEADER.joinToString(",")).append("\r\n")
        for (d in days) {
            if (d.totalMinutes <= 0 && d.entry?.attendance == null) continue
            val hours = "%.2f".format(Locale.ROOT, d.totalMinutes / 60.0)
            sb.append(listOf(
                d.date.toString(), d.start?.toString().orEmpty(), d.end?.toString().orEmpty(),
                if (d.start != null) d.breakMinutes.toString() else "", hours,
                d.entry?.attendance?.label.orEmpty(), payOf(d).toString(),
            ).joinToString(",")).append("\r\n")
        }
        return sb.toString()
    }

    /** "밤낮_근무기록_2026-10.csv" */
    fun fileName(month: YearMonth): String = "밤낮_근무기록_$month.csv"
}
