package com.bamnat.core.pay

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.schedule.ResolvedDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * [JOB-02] 공무원 봉급표 by 직급·호봉 (순번 270), from the law itself (국가법령정보센터, checked 2026-10-22):
 * 「공무원보수규정」(대통령령 제36728호, 시행 2026. 10. 2.) [별표 3] 일반직공무원 등의 봉급표 and [별표 10]
 * 경찰공무원·소방공무원 등의 봉급표, both <개정 2026. 1. 2.>, 월지급액 in 원. A new year's table is one more
 * [Table] with its first day, so the same data can later come from the remote file (순번 38·41).
 */
object PublicPayTable {
    enum class Series(val label: String, val table: String) {
        GENERAL("일반직 공무원", "공무원보수규정 별표 3"),
        POLICE("경찰", "공무원보수규정 별표 10"),
        FIRE("소방", "공무원보수규정 별표 10"),
    }

    /**
     * One 계급. [column] is its column in the law's table. [lowGrade] is 8급 or 8급 상당 이하, whose 봉급기준액 is
     * 60% instead of 55% (공무원수당 등에 관한 규정 제15조 제2항). [standardStep] is the 기준호봉 of the same 규정
     * [별표 12]: the 계급's 10호봉, 9호봉 for 순경·소방사.
     */
    data class Grade(val series: Series, val name: String, val column: Int, val lowGrade: Boolean, val standardStep: Int = 10) {
        val id: String get() = "${series.name}:$name"
    }

    // [PAYX-28] 경장·소방교 are 8급 상당 and 순경·소방사 9급 상당: 공무원보수규정 별표 34 (일반임기제 연봉등급 기준표, 8호 = 8급 =
    // 경장·소방교), checked against 공무원보수 등의 업무지침 (예규 제226호) 별표 2 단가 경장 12,584원 = 2,922,200 × 60% ÷ 209 × 1.5.
    private val generalNames = listOf("1급", "2급", "3급", "4급", "5급", "6급", "7급", "8급", "9급")
    private val policeNames = listOf("치안정감", "치안감", "경무관", "총경", "경정", "경감", "경위", "경사", "경장", "순경")
    private val fireNames = listOf("소방정감", "소방감", "소방준감", "소방정", "소방령", "소방경", "소방위", "소방장", "소방교", "소방사")

    /** Lowest 계급 first, as people look for their own. */
    fun grades(series: Series): List<Grade> = when (series) {
        Series.GENERAL -> generalNames.mapIndexed { i, n -> Grade(series, n, i, lowGrade = i >= 7) }
        Series.POLICE -> policeNames.mapIndexed { i, n -> Grade(series, n, i, lowGrade = i >= 8, standardStep = if (i == 9) 9 else 10) }
        Series.FIRE -> fireNames.mapIndexed { i, n -> Grade(series, n, i, lowGrade = i >= 8, standardStep = if (i == 9) 9 else 10) }
    }.reversed()

    fun grade(id: String): Grade? = Series.entries.flatMap(::grades).firstOrNull { it.id == id }

    /** One year's tables: each column is a 계급, each value the 호봉's 월지급액 (1호봉 first). */
    class Table(val from: LocalDate, val source: String, val general: List<LongArray>, val policeFire: List<LongArray>) {
        fun column(g: Grade): LongArray = if (g.series == Series.GENERAL) general[g.column] else policeFire[g.column]
    }

    val tables: List<Table> = listOf(
        Table(
            LocalDate.of(2026, 1, 1),
            "「공무원보수규정」 별표 3·별표 10 <개정 2026. 1. 2.> (국가법령정보센터, 2026-10-22 확인)",
            general = listOf(
            longArrayOf(4_656_100, 4_819_300, 4_986_600, 5_157_700, 5_333_000, 5_510_400, 5_690_400, 5_871_900, 6_056_100, 6_241_300, 6_426_000, 6_617_100, 6_809_200, 7_001_800, 7_170_100, 7_319_600, 7_452_100, 7_570_100, 7_675_800, 7_770_600, 7_857_800, 7_935_600, 8_001_400),
            longArrayOf(4_191_600, 4_347_100, 4_504_700, 4_663_700, 4_825_100, 4_986_500, 5_150_200, 5_313_600, 5_478_200, 5_642_500, 5_807_700, 5_978_600, 6_150_500, 6_306_200, 6_449_600, 6_581_000, 6_702_000, 6_812_700, 6_914_900, 7_008_100, 7_093_400, 7_171_600, 7_243_100, 7_301_700, 7_357_600),
            longArrayOf(3_781_700, 3_921_600, 4_065_700, 4_210_800, 4_358_300, 4_507_200, 4_658_000, 4_809_000, 4_961_400, 5_113_600, 5_266_200, 5_424_200, 5_571_200, 5_707_600, 5_833_400, 5_950_500, 6_059_400, 6_160_700, 6_254_300, 6_342_100, 6_423_300, 6_498_500, 6_567_800, 6_632_800, 6_685_800, 6_736_700, 6_783_800),
            longArrayOf(3_241_200, 3_373_500, 3_508_000, 3_645_700, 3_785_200, 3_926_200, 4_068_300, 4_211_100, 4_354_500, 4_497_700, 4_642_100, 4_778_100, 4_905_100, 5_023_800, 5_135_500, 5_240_900, 5_338_700, 5_430_100, 5_515_500, 5_595_300, 5_669_800, 5_739_700, 5_805_400, 5_867_300, 5_923_800, 5_971_800, 6_015_900, 6_058_300),
            longArrayOf(2_896_400, 3_013_400, 3_135_000, 3_261_300, 3_390_900, 3_523_000, 3_657_300, 3_793_200, 3_929_600, 4_066_800, 4_195_300, 4_319_000, 4_436_400, 4_545_900, 4_649_400, 4_746_800, 4_838_400, 4_924_800, 5_006_300, 5_082_700, 5_154_500, 5_222_000, 5_285_600, 5_345_200, 5_401_500, 5_454_500, 5_498_400, 5_540_700, 5_579_500, 5_617_200),
            longArrayOf(2_389_500, 2_500_700, 2_615_200, 2_732_400, 2_853_100, 2_977_100, 3_101_500, 3_226_200, 3_351_300, 3_468_700, 3_580_000, 3_689_600, 3_792_700, 3_890_000, 3_983_600, 4_071_300, 4_154_900, 4_234_100, 4_309_100, 4_379_600, 4_447_600, 4_511_500, 4_571_300, 4_628_500, 4_682_800, 4_734_100, 4_782_800, 4_823_700, 4_861_900, 4_899_800, 4_934_800, 4_967_800),
            longArrayOf(2_317_100, 2_367_900, 2_423_800, 2_485_200, 2_567_100, 2_682_600, 2_798_700, 2_915_800, 3_027_100, 3_133_300, 3_233_400, 3_331_900, 3_425_300, 3_514_500, 3_599_900, 3_680_600, 3_758_100, 3_832_100, 3_902_000, 3_968_400, 4_031_800, 4_091_600, 4_149_600, 4_204_500, 4_256_500, 4_306_500, 4_348_700, 4_388_000, 4_426_100, 4_462_400, 4_496_500),
            longArrayOf(2_162_100, 2_195_700, 2_233_800, 2_276_600, 2_331_700, 2_412_900, 2_519_600, 2_622_400, 2_720_300, 2_813_000, 2_902_700, 2_990_300, 3_074_500, 3_155_100, 3_232_400, 3_307_100, 3_376_800, 3_444_300, 3_509_300, 3_571_100, 3_630_200, 3_686_900, 3_740_800, 3_792_900, 3_842_300, 3_890_300, 3_930_200, 3_968_700, 4_005_100, 4_040_300, 4_074_600),
            longArrayOf(2_133_000, 2_147_600, 2_168_000, 2_194_000, 2_226_100, 2_264_600, 2_309_900, 2_367_500, 2_456_700, 2_542_700, 2_624_400, 2_705_900, 2_783_900, 2_859_800, 2_932_300, 3_002_300, 3_070_800, 3_134_500, 3_197_300, 3_257_000, 3_313_400, 3_367_600, 3_419_200, 3_468_800, 3_516_000, 3_558_700, 3_595_600, 3_631_000, 3_665_100, 3_698_400, 3_731_100),
            ),
            policeFire = listOf(
            longArrayOf(4_905_100, 5_068_300, 5_235_600, 5_406_700, 5_582_000, 5_759_400, 5_939_400, 6_120_900, 6_305_100, 6_490_300, 6_675_000, 6_866_100, 7_058_200, 7_250_800, 7_419_100, 7_568_600, 7_701_100, 7_819_100, 7_924_800, 8_019_600, 8_106_800, 8_184_600, 8_250_400),
            longArrayOf(4_563_500, 4_719_000, 4_876_600, 5_035_600, 5_197_000, 5_358_400, 5_522_100, 5_685_500, 5_850_100, 6_014_400, 6_179_600, 6_350_500, 6_522_400, 6_678_100, 6_821_500, 6_952_900, 7_073_900, 7_184_600, 7_286_800, 7_380_000, 7_465_300, 7_543_500, 7_615_000, 7_673_600, 7_729_500),
            longArrayOf(4_167_700, 4_307_600, 4_451_700, 4_596_800, 4_744_300, 4_893_200, 5_044_000, 5_195_000, 5_347_400, 5_499_600, 5_652_200, 5_810_200, 5_957_200, 6_093_600, 6_219_400, 6_336_500, 6_445_400, 6_546_700, 6_640_300, 6_728_100, 6_809_300, 6_884_500, 6_953_800, 7_018_800, 7_071_800, 7_122_700, 7_169_800),
            longArrayOf(3_619_000, 3_751_300, 3_885_800, 4_023_500, 4_163_000, 4_304_000, 4_446_100, 4_588_900, 4_732_300, 4_875_500, 5_019_900, 5_155_900, 5_282_900, 5_401_600, 5_513_300, 5_618_700, 5_716_500, 5_807_900, 5_893_300, 5_973_100, 6_047_600, 6_117_500, 6_183_200, 6_245_100, 6_301_600, 6_349_600, 6_393_700, 6_436_100),
            longArrayOf(3_126_100, 3_243_100, 3_364_700, 3_491_000, 3_620_600, 3_752_700, 3_887_000, 4_022_900, 4_159_300, 4_296_500, 4_425_000, 4_548_700, 4_666_100, 4_775_600, 4_879_100, 4_976_500, 5_068_100, 5_154_500, 5_236_000, 5_312_400, 5_384_200, 5_451_700, 5_515_300, 5_574_900, 5_631_200, 5_684_200, 5_728_100, 5_770_400, 5_809_200, 5_846_900),
            longArrayOf(2_698_600, 2_814_700, 2_933_000, 3_054_900, 3_178_700, 3_305_500, 3_435_100, 3_565_700, 3_697_600, 3_820_800, 3_937_600, 4_051_200, 4_158_400, 4_261_000, 4_357_000, 4_449_500, 4_535_400, 4_618_100, 4_695_400, 4_768_800, 4_837_900, 4_904_700, 4_965_900, 5_025_200, 5_081_100, 5_134_000, 5_183_300, 5_225_600, 5_264_800, 5_303_200, 5_338_600, 5_372_500),
            longArrayOf(2_507_700, 2_567_700, 2_640_000, 2_758_200, 2_879_500, 3_001_800, 3_125_400, 3_249_000, 3_373_300, 3_490_700, 3_600_800, 3_709_400, 3_812_400, 3_909_100, 4_002_700, 4_090_100, 4_173_700, 4_252_700, 4_327_900, 4_399_300, 4_467_200, 4_531_200, 4_592_000, 4_650_300, 4_705_700, 4_756_500, 4_800_000, 4_841_900, 4_881_500, 4_918_600, 4_954_300),
            longArrayOf(2_472_100, 2_493_100, 2_539_000, 2_610_800, 2_710_600, 2_826_000, 2_942_100, 3_059_100, 3_170_300, 3_276_600, 3_376_800, 3_475_200, 3_568_600, 3_657_900, 3_743_200, 3_823_900, 3_901_400, 3_975_500, 4_045_300, 4_111_700, 4_175_200, 4_235_000, 4_293_000, 4_347_900, 4_399_900, 4_449_900, 4_492_000, 4_531_300, 4_569_300, 4_605_800, 4_639_900),
            longArrayOf(2_215_300, 2_245_500, 2_290_400, 2_350_700, 2_427_500, 2_522_200, 2_628_900, 2_731_700, 2_829_500, 2_922_200, 3_012_000, 3_099_500, 3_183_800, 3_264_300, 3_341_600, 3_416_300, 3_486_000, 3_553_600, 3_618_500, 3_680_400, 3_739_400, 3_796_100, 3_850_000, 3_902_200, 3_951_600, 3_999_600, 4_039_500, 4_078_000, 4_114_300, 4_149_600, 4_183_800),
            longArrayOf(2_133_000, 2_155_600, 2_187_500, 2_229_200, 2_281_100, 2_343_900, 2_418_400, 2_505_600, 2_595_100, 2_680_900, 2_762_900, 2_844_100, 2_922_200, 2_998_100, 3_070_500, 3_140_600, 3_209_100, 3_273_000, 3_335_700, 3_395_400, 3_451_600, 3_506_000, 3_557_600, 3_607_200, 3_654_200, 3_697_100, 3_733_900, 3_769_300, 3_803_400, 3_836_800, 3_869_200),
            ),
        ),
    )

    /** The table in force on [on]; before the first one, the first. */
    fun table(on: LocalDate): Table = tables.lastOrNull { !it.from.isAfter(on) } ?: tables.first()

    fun maxStep(g: Grade, on: LocalDate): Int = table(on).column(g).size

    /** The 봉급 of [g] at [step] on [on], or null for a 호봉 the 계급 does not have. */
    fun salary(g: Grade, step: Int, on: LocalDate): Long? = table(on).column(g).getOrNull(step - 1)

    /** 봉급기준액: the 기준호봉's 봉급 × 55% (8급 상당 이하 60%), 공무원수당 등에 관한 규정 제15조 제2항·제3항. */
    fun standardBase(g: Grade, on: LocalDate): Long =
        PublicServantPay.standardBase(salary(g, g.standardStep, on) ?: 0, g.lowGrade)

    /**
     * 정근수당 (규정 제7조, [별표 2] <개정 2025. 1. 3.>): a share of the 월봉급액 paid in January and July by years
     * of service: under 2 years 10%, under 5 20%, then 5% more a year to 50% from 10 years.
     */
    fun longevityRate(years: Int): Double = when {
        years < 2 -> 0.10
        years < 5 -> 0.20
        years < 10 -> 0.25 + (years - 5) * 0.05
        else -> 0.50
    }

    /** 정근수당 가산금 (같은 [별표 2] 2호), a month: 3만 원 under 5 years up to 10만 원 from 20, plus 1만·3만 원 from 20·25 years. */
    fun longevityExtra(years: Int): Long = when {
        years >= 25 -> 100_000 + 30_000
        years >= 20 -> 100_000 + 10_000
        years >= 15 -> 80_000
        years >= 10 -> 60_000
        years >= 5 -> 50_000
        else -> 30_000
    }

    /** 명절휴가비 (규정 제18조의3): 60% of the 월봉급액 on 설날 and 추석. */
    const val FESTIVAL_SHARE = 0.60

    /** Whole years of service from [start] to [on] (규정 제7조 제4항 counts on the first of each month). */
    fun years(start: LocalDate?, on: LocalDate): Int = start?.let { ChronoUnit.YEARS.between(it, on.withDayOfMonth(1)).toInt().coerceAtLeast(0) } ?: 0

    /**
     * [JOB-03] The month's 시간외근무시간 (순번 278) by 규정 제15조 제5항 from the extra work on the calendar:
     * - 현업·교대 ([onSite]): every minute of extra work.
     * - 그 밖: Saturdays and 공휴일 (일요일 included) count as worked, other days lose 1 hour, and a day under
     *   1 hour adds nothing.
     * Minutes are added up and the hour under is dropped. Only non-현업 have the 57-hour cap of 제15조 제4항.
     */
    data class Overtime(val hours: Int, val cap: Int?) {
        /** Hours that pay: the cap's worth at most. */
        val paidHours: Int get() = cap?.let { minOf(hours, it) } ?: hours
        /** "41/57" or "41시간". */
        val text: String get() = cap?.let { "$hours/$it" } ?: "${hours}시간"
        val over: Boolean get() = cap != null && hours > cap
    }

    const val MONTHLY_CAP = 57

    fun overtime(days: List<ResolvedDay>, onSite: Boolean): Overtime = overtime(days.associate { it.date to it.extras.sumOf { e -> e.minutes } }, onSite)

    fun overtime(minutesByDay: Map<LocalDate, Int>, onSite: Boolean): Overtime {
        val total = minutesByDay.entries.sumOf { (date, m) ->
            when {
                onSite -> m
                m < 60 -> 0
                restDay(date) -> m
                else -> m - 60
            }
        }
        return Overtime(total / 60, if (onSite) null else MONTHLY_CAP)
    }

    /** 토요일 and 공휴일 (관공서의 공휴일에 관한 규정: 일요일 and the days it names). */
    fun restDay(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY || KoreanHolidays.nameOf(date) != null
}
