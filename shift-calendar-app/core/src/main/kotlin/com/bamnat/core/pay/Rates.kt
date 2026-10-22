package com.bamnat.core.pay

import java.time.LocalDate
import java.time.LocalTime

/**
 * Legal rates for one period. Numbers live here as data with a source and version, never in
 * the calculation code, so a yearly update is a data change [PAY-02]. A newer signed `remote/v1/rates.json`
 * replaces the built-in years ([RateTable.use], [REMOTE-02]).
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
    /** 국민연금 총 보험료율 (근로자·사업주 합계); null when not yet fixed [PAY-03]. */
    val pensionRate: Double? = null,
    /** 건강보험료율 (합계). */
    val healthRate: Double? = null,
    /** 장기요양보험료율, 소득 대비 (합계). */
    val longTermCareRate: Double? = null,
    /**
     * [REMOTE-02] 고용보험 실업급여 보험료율 (합계, 근로자·사업주 반씩): 고용보험 및 산업재해보상보험의
     * 보험료징수 등에 관한 법률 시행령 제12조 제1항 제2호 1천분의 18 (2025-12-23 시행본 확인).
     */
    val unemploymentRate: Double = 0.018,
)

/** [REMOTE-02] 국민연금 기준소득월액 하한·상한 from [from] (보건복지부 고시, 7월~이듬해 6월). */
data class PensionBase(val from: LocalDate, val min: Long, val max: Long)

object RateTable {
    val builtIn: List<Rates> = listOf(
        Rates(
            effectiveFrom = LocalDate.of(2025, 1, 1),
            version = "2025.1",
            source = "근로기준법 제50·53·56조, 2025년 최저임금 고시(시급 10,030원)",
            minimumHourlyWage = 10_030,
            pensionRate = 0.09, healthRate = 0.0709, longTermCareRate = 0.009182,
        ),
        Rates(
            effectiveFrom = LocalDate.of(2026, 1, 1),
            version = "2026.1",
            source = "근로기준법 제50·53·56조, 2026년 최저임금 고시(시급 10,320원)",
            minimumHourlyWage = 10_320,
            pensionRate = 0.095, healthRate = 0.0719, longTermCareRate = 0.009448,
        ),
        // [PAY-03] Checked 2026-10-15: 최저임금 고용노동부 고시 2026-08-05 (10,700원), 국민연금법 개정
        // (2027년 1천분의 100), 건강보험정책심의위원회 2026-09-08 (7.19% 동결). 장기요양보험료율은
        // 아직 결정 전이라 비워 둠; 결정되면 채운다.
        Rates(
            effectiveFrom = LocalDate.of(2027, 1, 1),
            version = "2027.1",
            source = "근로기준법 제50·53·56조, 2027년 최저임금 고시(시급 10,700원)",
            minimumHourlyWage = 10_700,
            pensionRate = 0.10, healthRate = 0.0719, longTermCareRate = null,
        ),
    )

    /** [PAYR-01] 2026.7: 41만~659만 (research/1.21-급여엔진-리뷰.md 출처). */
    val builtInBases: List<PensionBase> = listOf(
        PensionBase(LocalDate.of(2025, 7, 1), 400_000, 6_370_000),
        PensionBase(LocalDate.of(2026, 7, 1), 410_000, 6_590_000),
    )

    /** [REMOTE-02] The `version` of `remote/v1/rates.json` that holds exactly [builtIn]; a download must be newer. */
    const val BUILT_IN_VERSION = 2026102201L

    private class Table(val rates: List<Rates>, val bases: List<PensionBase>, val version: Long)

    @Volatile private var remote: Table? = null

    /** [REMOTE-02] The years in use: [builtIn] with a newer signed rates file laid over it. */
    val current: List<Rates> get() = remote?.rates ?: builtIn
    val pensionBases: List<PensionBase> get() = remote?.bases ?: builtInBases
    val version: Long get() = remote?.version ?: BUILT_IN_VERSION

    /**
     * [REMOTE-02] Lays a downloaded rates file over the built-in years: a year in the file replaces the one
     * starting the same day, a new year is added. Older or equal versions, and null, go back to [builtIn].
     */
    fun use(file: com.bamnat.core.remote.RemoteFile?) {
        remote = file?.takeIf { it.version > BUILT_IN_VERSION }?.let { f ->
            val r = RateFile.read(f)
            Table(
                (builtIn.filter { b -> r.rates.none { it.effectiveFrom == b.effectiveFrom } } + r.rates).sortedBy { it.effectiveFrom },
                (builtInBases.filter { b -> r.bases.none { it.from == b.from } } + r.bases).sortedBy { it.from },
                f.version,
            )
        }
    }

    /** The rates in force on [date]; the earliest table before any table starts. */
    fun on(date: LocalDate, table: List<Rates> = current): Rates =
        table.filter { !it.effectiveFrom.isAfter(date) }.maxByOrNull { it.effectiveFrom }
            ?: table.minBy { it.effectiveFrom }
}

/**
 * [REMOTE-02] `remote/v1/rates.json` data: `rates[]` (`from`, `version`, `source`, `minimumHourlyWage`,
 * `pensionRate`, `healthRate`, `longTermCareRate`, `unemploymentRate`; null = 아직 결정 전) and `pensionBases[]`
 * (`from`, `min`, `max`). Premiums and hours are the 근로기준법 ones unless a year gives them.
 */
object RateFile {
    data class Data(val rates: List<Rates>, val bases: List<PensionBase>)

    fun read(f: com.bamnat.core.remote.RemoteFile): Data {
        if (f.kind != com.bamnat.core.remote.RemoteFiles.RATES) throw com.bamnat.core.remote.RemoteFileException("kind ${f.kind}")
        fun date(s: String) = try { LocalDate.parse(s) } catch (e: java.time.format.DateTimeParseException) {
            throw com.bamnat.core.remote.RemoteFileException("date $s")
        }
        fun share(v: Double?, k: String) = v?.also { if (it < 0 || it > 0.3) throw com.bamnat.core.remote.RemoteFileException(k) }
        val d = Rates(LocalDate.MIN, "", "", minimumHourlyWage = 0)
        val rates = f.data.objs("rates").map { o ->
            val wage = o.int("minimumHourlyWage")
            if (wage < 5_000 || wage > 100_000) throw com.bamnat.core.remote.RemoteFileException("minimumHourlyWage $wage")
            Rates(
                effectiveFrom = date(o.str("from")), version = o.str("version"), source = o.strOr("source", ""),
                overtimePremium = o.doubleOrNull("overtimePremium") ?: d.overtimePremium,
                nightPremium = o.doubleOrNull("nightPremium") ?: d.nightPremium,
                holidayPremiumWithin8h = o.doubleOrNull("holidayPremiumWithin8h") ?: d.holidayPremiumWithin8h,
                holidayPremiumOver8h = o.doubleOrNull("holidayPremiumOver8h") ?: d.holidayPremiumOver8h,
                minimumHourlyWage = wage.toLong(),
                pensionRate = share(o.doubleOrNull("pensionRate"), "pensionRate"),
                healthRate = share(o.doubleOrNull("healthRate"), "healthRate"),
                longTermCareRate = share(o.doubleOrNull("longTermCareRate"), "longTermCareRate"),
                unemploymentRate = share(o.doubleOrNull("unemploymentRate"), "unemploymentRate") ?: d.unemploymentRate,
            )
        }
        val bases = f.data.objs("pensionBases").map { o ->
            PensionBase(date(o.str("from")), o.long("min"), o.long("max")).also {
                if (it.min <= 0 || it.max <= it.min) throw com.bamnat.core.remote.RemoteFileException("pensionBases")
            }
        }
        return Data(rates, bases)
    }
}
