package com.bamnat.core.holiday

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/** A date of the Korean lunisolar calendar (음력). [leap] marks a 윤달. */
data class LunarDate(val year: Int, val month: Int, val day: Int, val leap: Boolean = false) {
    /** "8.24", "윤6.1". */
    val short: String get() = (if (leap) "윤" else "") + "$month.$day"

    /** "음력 8월 24일", "음력 윤6월 1일". */
    val label: String get() = "음력 " + (if (leap) "윤" else "") + "${month}월 ${day}일"
}

/**
 * Korean lunisolar calendar [FEAT-05], computed instead of looked up, for 1950~2100.
 *
 * Rules (same as the Chinese calendar, on Korea's UTC+9 clock): a month starts on the day of a
 * new moon; the month holding the winter solstice is the 11th; in a year with 13 months between
 * two such months, the first month without a principal solar term (중기) is the leap month.
 * New moons use Meeus' series (error under a minute), the sun's longitude Meeus' short series
 * (about 0.01°), so a date can only be off when a new moon or 중기 falls within minutes of midnight.
 */
object KoreanLunar {
    private const val SYNODIC = 29.530588861
    private const val KST = 9.0 / 24.0
    private val tables = ConcurrentHashMap<Int, List<MonthStart>>()

    private data class MonthStart(val start: LocalDate, val year: Int, val month: Int, val leap: Boolean)

    fun fromSolar(date: LocalDate): LunarDate {
        val y = if (date.isBefore(month11(date.year))) date.year else date.year + 1
        val months = table(y)
        val m = months.last { !it.start.isAfter(date) }
        return LunarDate(m.year, m.month, (date.toEpochDay() - m.start.toEpochDay()).toInt() + 1, m.leap)
    }

    /** The solar date of a lunar date, or null when that day or 윤달 does not exist. */
    fun toSolar(year: Int, month: Int, day: Int, leap: Boolean = false): LocalDate? {
        // Months 1~10 of [year] are in table(year); months 11 and 12 in table(year + 1).
        val months = table(if (month >= 11) year + 1 else year)
        val i = months.indexOfFirst { it.year == year && it.month == month && it.leap == leap }
        if (i < 0 || i + 1 >= months.size) return null
        val start = months[i].start
        val length = months[i + 1].start.toEpochDay() - start.toEpochDay()
        return if (day in 1..length) start.plusDays(day - 1L) else null
    }

    /** Months from the 11th month of year y-1 up to (not including) the 11th month of year y, plus that one. */
    private fun table(y: Int): List<MonthStart> = tables.getOrPut(y) {
        val a = month11(y - 1)
        val b = month11(y)
        val starts = mutableListOf<LocalDate>()
        var k = lunation(a)
        while (true) {
            val d = newMoonDate(k)
            if (d.isAfter(b)) break
            if (!d.isBefore(a)) starts += d
            k++
        }
        // starts: a ... b (b included, it is the next table's first month).
        val count = starts.size - 1
        val leapIndex = if (count == 13) (1 until count).firstOrNull { !hasPrincipalTerm(starts[it], starts[it + 1]) } else null
        val result = ArrayList<MonthStart>(starts.size)
        var number = 11
        var year = y - 1
        for (i in starts.indices) {
            val leap = i == leapIndex
            if (i > 0 && !leap) {
                number++
                if (number == 13) { number = 1; year = y }
            }
            result += MonthStart(starts[i], year, number, leap)
        }
        result
    }

    /** Start of the lunar month holding the winter solstice of December [year]. */
    private fun month11(year: Int): LocalDate {
        val solstice = localDate(solarTermJd(270.0, year))
        var k = lunation(solstice)
        while (newMoonDate(k).isAfter(solstice)) k--
        while (!newMoonDate(k + 1).isAfter(solstice)) k++
        return newMoonDate(k)
    }

    private fun hasPrincipalTerm(start: LocalDate, next: LocalDate): Boolean =
        floor(sunLongitude(midnightJd(start)) / 30.0) != floor(sunLongitude(midnightJd(next)) / 30.0)

    private fun lunation(date: LocalDate): Long =
        floor((midnightJd(date) - 2451550.09766) / SYNODIC).toLong()

    private fun newMoonDate(k: Long): LocalDate = localDate(newMoonJde(k))

    /** Julian Ephemeris Day of new moon number [k] (k = 0 near 2000-01-06), Meeus ch. 49. */
    internal fun newMoonJde(k: Long): Double {
        val t = k / 1236.85
        val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t
        var jde = 2451550.09766 + SYNODIC * k + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t4
        val e = 1 - 0.002516 * t - 0.0000074 * t2
        val m = rad(2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t3)
        val mp = rad(201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4)
        val f = rad(160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4)
        val om = rad(124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3)
        jde += -0.40720 * sin(mp) + 0.17241 * e * sin(m) + 0.01608 * sin(2 * mp) + 0.01039 * sin(2 * f) +
            0.00739 * e * sin(mp - m) - 0.00514 * e * sin(mp + m) + 0.00208 * e * e * sin(2 * m) -
            0.00111 * sin(mp - 2 * f) - 0.00057 * sin(mp + 2 * f) + 0.00056 * e * sin(2 * mp + m) -
            0.00042 * sin(3 * mp) + 0.00042 * e * sin(m + 2 * f) + 0.00038 * e * sin(m - 2 * f) -
            0.00024 * e * sin(2 * mp - m) - 0.00017 * sin(om) - 0.00007 * sin(mp + 2 * m) +
            0.00004 * sin(2 * mp - 2 * f) + 0.00004 * sin(3 * m) + 0.00003 * sin(mp + m - 2 * f) +
            0.00003 * sin(2 * mp + 2 * f) - 0.00003 * sin(mp + m + 2 * f) + 0.00003 * sin(mp - m + 2 * f) -
            0.00002 * sin(mp - m - 2 * f) - 0.00002 * sin(3 * mp + m) + 0.00002 * sin(4 * mp)
        // Planetary arguments (Meeus table 49.b).
        val a = doubleArrayOf(
            299.77 + 0.107408 * k - 0.009173 * t2, 251.88 + 0.016321 * k, 251.83 + 26.651886 * k,
            349.42 + 36.412478 * k, 84.66 + 18.206239 * k, 141.74 + 53.303771 * k, 207.14 + 2.453732 * k,
            154.84 + 7.306860 * k, 34.52 + 27.261239 * k, 207.19 + 0.121824 * k, 291.34 + 1.844379 * k,
            161.72 + 24.198154 * k, 239.56 + 25.513099 * k, 331.55 + 3.592518 * k,
        )
        val c = doubleArrayOf(325.0, 165.0, 164.0, 126.0, 110.0, 62.0, 60.0, 56.0, 47.0, 42.0, 40.0, 37.0, 35.0, 23.0)
        for (i in a.indices) jde += c[i] * 0.000001 * sin(rad(a[i]))
        return jde
    }

    /** Apparent longitude of the sun in degrees at Julian Day [jd], Meeus ch. 25 (low accuracy). */
    internal fun sunLongitude(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = rad(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) + (0.019993 - 0.000101 * t) * sin(2 * m) + 0.000289 * sin(3 * m)
        val omega = rad(125.04 - 1934.136 * t)
        val lon = l0 + c - 0.00569 - 0.00478 * sin(omega)
        return ((lon % 360) + 360) % 360
    }

    /** JD when the sun reaches [longitude] near the matching month of [year]. */
    private fun solarTermJd(longitude: Double, year: Int): Double {
        // Start near the right day: 0° is about 20 March.
        var jd = midnightJd(LocalDate.of(year, 3, 20)) + ((longitude / 360.0) * 365.2422)
        repeat(6) {
            var diff = longitude - sunLongitude(jd)
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            jd += diff / 360.0 * 365.2422
        }
        return jd
    }

    /** JD (UT) of 00:00 KST on [date]. */
    private fun midnightJd(date: LocalDate): Double = date.toEpochDay() + 2440587.5 - KST

    /** KST calendar date of a Julian Ephemeris Day. */
    private fun localDate(jde: Double): LocalDate {
        val ut = jde - deltaT(jde) / 86400.0
        return LocalDate.ofEpochDay(floor(ut + KST + 0.5).roundToLong() - 2440588)
    }

    /** TT − UT in seconds (Espenak–Meeus polynomials), good to a few seconds for 1950~2100. */
    private fun deltaT(jde: Double): Double {
        val y = 2000.0 + (jde - 2451544.5) / 365.2425
        return when {
            y < 1961 -> { val t = y - 1950; 29.07 + 0.407 * t - t * t / 233 + t * t * t / 2547 }
            y < 1986 -> { val t = y - 1975; 45.45 + 1.067 * t - t * t / 260 - t * t * t / 718 }
            y < 2005 -> { val t = y - 2000; 63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t + 0.000651814 * t * t * t * t + 0.00002373599 * t * t * t * t * t }
            y < 2050 -> { val t = y - 2000; 62.92 + 0.32217 * t + 0.005589 * t * t }
            else -> { val u = (y - 1820) / 100; -20 + 32 * u * u - 0.5628 * (2150 - y) }
        }
    }

    private fun rad(deg: Double) = Math.toRadians(deg)
}
