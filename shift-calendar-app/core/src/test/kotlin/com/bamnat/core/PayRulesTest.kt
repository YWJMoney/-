package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.BreakPay
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CustomRule
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.Festival
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayUnit
import com.bamnat.core.pay.PayslipMatcher
import com.bamnat.core.pay.PayslipReader
import com.bamnat.core.pay.Premium
import com.bamnat.core.pay.PremiumScope
import com.bamnat.core.pay.PremiumTarget
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.RuleChange
import com.bamnat.core.pay.RuleDayKind
import com.bamnat.core.pay.RulePay
import com.bamnat.core.pay.RulePieces
import com.bamnat.core.pay.RuleText
import com.bamnat.core.pay.RuleWhen
import com.bamnat.core.pay.SlipRole
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * [PAYS-05][PAYS-06] Rules in the user's own words, the general rules they become, and payslips read from text.
 * 통상시급 12,000원 (2,508,000원 ÷ 209) throughout; amounts are worked out by hand in each case.
 */
class PayRulesTest {
    private val nov = YearMonth.of(2026, 11)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private fun wage(v: Long) = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), v)))
    private val salary = PayProfile(occupation = Occupation.CUSTOM, wageType = WageType.MONTHLY, base = wage(2_508_000))

    private val office = ShiftType(10, "사무", "사", 0xFF1E88E5, t(9), t(18), 60, t(12))
    private val off = ShiftType(99, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)

    /** 월~토 사무 from Monday 9/28; Sundays off. November 2026 has 4 Saturdays (7, 14, 21, 28). */
    private fun sixDays(vararg e: DayEntry) =
        ScheduleResolver(listOf(office, off), WorkPattern.Rotation(LocalDate.of(2026, 9, 28), List(6) { office.id } + listOf(off.id)), e.toList())

    private fun pay(p: PayProfile, r: ScheduleResolver) = PayCalculator().calculate(p, nov, r::resolve)
    private fun added(p: PayProfile, r: ScheduleResolver) = pay(p, r).total - pay(salary, r).total
    private fun with(vararg rules: CustomRule) = salary.copy(customRules = rules.toList())

    // ---- the general rules ----

    @Test
    fun `R1 Saturday work earns 50 percent of 통상시급 on top, 4 Saturdays of 8 hours`() {
        val rule = CustomRule("sat", "토요일 가산", RuleWhen(daysOfWeek = setOf(DayOfWeek.SATURDAY)), RulePay.OfHourly(0.5))
        // 4일 × 8시간 × 12,000 × 50% = 192,000.
        val m = pay(with(rule), sixDays())
        assertEquals(192_000, m.total - pay(salary, sixDays()).total)
        val line = m.lines.single { it.label == "토요일 가산 (회사 규칙)" }
        assertEquals(LineKind.ALLOWANCE, line.kind)
        assertEquals(32 * 60, line.minutes)
        assertTrue(line.formula, line.formula.startsWith("토요일: 통상시급의 50%: 4일, 32시간"))
        assertEquals(48_000, m.day(LocalDate.of(2026, 11, 7))!!.parts.single { it.label == "토요일 가산" }.amount)
        // Off, it pays nothing.
        assertEquals(0, added(with(rule.copy(on = false)), sixDays()))
    }

    @Test
    fun `R2 a clock window counts the minutes inside it, the break where it is`() {
        // 사무 09~18, 휴게 12~13: 11~14시 window holds 11~12 and 13~14 = 2시간 a day.
        val rule = CustomRule("w", "점심 앞뒤", RuleWhen(from = t(11), to = t(14)), RulePay.PerHour(1_000))
        // 25 working days in November (월~토) × 2시간 × 1,000 = 50,000.
        assertEquals(50_000, added(with(rule), sixDays()))
    }

    @Test
    fun `R3 once a day on Sundays, only when worked`() {
        val rule = CustomRule("sun", "일요일 수당", RuleWhen(daysOfWeek = setOf(DayOfWeek.SUNDAY)), RulePay.PerTime(20_000))
        assertEquals(0, added(with(rule), sixDays()))
        // 11/8 일요일 특근 09~13.
        val r = sixDays(DayEntry(LocalDate.of(2026, 11, 8), extras = listOf(ExtraEntry(ExtraKind.SPECIAL_ID, t(9), t(13)))))
        assertEquals(20_000, pay(with(rule), r).total - pay(salary, r).total)
        // Extra work only: the shifts of the week do not count, the 특근 does.
        val extraOnly = CustomRule("x", "특근 수당", RuleWhen(pieces = RulePieces.EXTRA, extraKinds = setOf(ExtraKind.SPECIAL_ID)), RulePay.PerTime(5_000))
        assertEquals(5_000, pay(with(extraOnly), r).total - pay(salary, r).total)
    }

    @Test
    fun `R4 hours beyond some in a day, and a share of 기본급`() {
        // 11/2 잔업 18~21 (3시간): the day is 11시간, 8시간 넘는 3시간 × 3,000 = 9,000.
        val r = sixDays(DayEntry(LocalDate.of(2026, 11, 2), extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, t(18), t(21)))))
        val over = CustomRule("o", "8시간 초과", RuleWhen(overMinutes = 480), RulePay.PerHour(3_000))
        assertEquals(9_000, pay(with(over), r).total - pay(salary, r).total)
        // 8시간에 기본급의 1%: 3시간 → 2,508,000 × 1% × 3 ÷ 8 = 9,405.
        val share = CustomRule("b", "기본급 몫", RuleWhen(overMinutes = 480), RulePay.OfBase(0.01, 480))
        assertEquals(9_405, pay(with(share), r).total - pay(salary, r).total)
        // At least 10 hours worked that day: once.
        val long = CustomRule("l", "장시간 식대", RuleWhen(minMinutes = 600), RulePay.PerTime(8_000))
        assertEquals(8_000, pay(with(long), r).total - pay(salary, r).total)
    }

    @Test
    fun `R5 a holiday rule follows the paid holidays of the month`() {
        // No 공휴일 in November 2026; 주휴일 일요일 makes Sundays paid holidays.
        val rule = CustomRule("h", "휴일 수당", RuleWhen(dayKind = RuleDayKind.HOLIDAY), RulePay.PerTime(10_000))
        val r = sixDays(DayEntry(LocalDate.of(2026, 11, 8), extras = listOf(ExtraEntry(ExtraKind.SPECIAL_ID, t(9), t(13)))))
        assertEquals(0, pay(with(rule), r).total - pay(salary, r).total)
        val sunday = with(rule).copy(rules = salary.rules.copy(weeklyHoliday = com.bamnat.core.pay.WeeklyHoliday.Fixed(DayOfWeek.SUNDAY)))
        val base = salary.copy(rules = sunday.rules)
        assertEquals(10_000, pay(sunday, r).total - pay(base, r).total)
    }

    @Test
    fun `R6 PAYS-07 a monthly amount and a share of 기본급 are paid once, whatever the work`() {
        val fixed = CustomRule("m", "자격수당", RuleWhen(), RulePay.Monthly(50_000))
        assertEquals(50_000, added(with(fixed), sixDays()))
        val m = pay(with(fixed), sixDays())
        assertEquals("매달 50,000원", m.lines.single { it.label == "자격수당 (회사 규칙)" }.formula)
        // Paid for the month, not on any one day.
        assertTrue(m.days.none { d -> d.parts.any { it.label == "자격수당" } })
        // 2,508,000 × 5% = 125,400.
        val share = CustomRule("s", "근속수당", RuleWhen(), RulePay.OfBaseMonthly(0.05))
        assertEquals(125_400, added(with(share), sixDays()))
        assertEquals("매달 기본급의 5%", share.describe())
    }

    @Test
    fun `R7 PAYS-07 a deduction takes its amount off, day by day or once a month`() {
        // 식대 3,000원 off each of November's 25 working days: −75,000.
        val meal = CustomRule("d", "식대", RuleWhen(dayKind = RuleDayKind.WORK_DAY), RulePay.PerTime(3_000), deduct = true)
        val m = pay(with(meal), sixDays())
        assertEquals(-75_000, m.total - pay(salary, sixDays()).total)
        assertEquals(-75_000, m.lines.single { it.label == "식대 (차감)" }.amount)
        assertEquals(-3_000, m.day(LocalDate.of(2026, 11, 2))!!.parts.single { it.label == "식대" }.amount)
        val dorm = CustomRule("r", "기숙사비", RuleWhen(), RulePay.Monthly(50_000), deduct = true)
        assertEquals(-50_000, added(with(dorm), sixDays()))
        assertEquals("매달 50,000원 차감", dorm.describe())
        assertEquals(0, added(with(dorm.copy(on = false)), sixDays()))
        // [PAYS-09] Told to the developer without the amount.
        assertEquals("매달 정액 차감", dorm.describeWithoutAmount())
        assertEquals("근무일: 1회 정액 차감", meal.describeWithoutAmount())
        assertFalse("원" in meal.describeWithoutAmount())
    }

    @Test
    fun `R8 PAYS-07 a payslip line named like an item the user made is not missing from the app`() {
        val p = with(CustomRule("m", "자격수당", RuleWhen(), RulePay.Monthly(50_000)))
        val total = pay(p, sixDays()).total
        val match = PayslipMatcher.match(p, nov, sixDays()::resolve, com.bamnat.core.pay.Payslip(total, allowances = mapOf("자격 수당" to 50_000)))
        assertTrue(match.unmatched.isEmpty())
        assertTrue(match.matched)
    }

    // ---- reading rules ----

    @Test
    fun `T6 PAYS-07 money taken off reads as a deduction, taxes and 지각 left out`() {
        val f = RuleText.read(
            """
            기숙사비 매달 5만원 차감
            식대 근무일마다 3,000원 공제
            조합비 기본급의 1% 공제
            국민연금 135,000원 공제
            지각 1회 1만원 차감
            """.trimIndent(),
        )
        assertEquals(f.joinToString(" | ") { "${it.sourceLine} → ${it.change.summary}" }, 3, f.size)
        val rules = f.map { (it.change as RuleChange.Custom).rule }
        assertTrue(rules.all { it.deduct })
        assertEquals(RulePay.Monthly(50_000), rules[0].pay)
        assertEquals("기숙사비", rules[0].name)
        assertEquals(RulePay.PerTime(3_000), rules[1].pay)
        assertEquals(RuleDayKind.WORK_DAY, rules[1].whenever.dayKind)
        assertEquals("식대", rules[1].name)
        assertEquals(RulePay.OfBaseMonthly(0.01), rules[2].pay)
        assertEquals("조합비", rules[2].name)
        // Applied: 50,000 + 25 × 3,000 + 2,508,000 × 1% off.
        val p = RuleText.apply(salary, f.map { it.change }, nov)
        assertEquals(-(50_000L + 75_000 + 25_080), added(p, sixDays()))
    }

    @Test
    fun `T1 the user's own company way reads as the rule ExtraPremiumTest works out`() {
        val f = RuleText.read("대근은 8시간에 기본급의 10%, 8시간 넘으면 50% 가산, 야간 50% 가산")
        val extra = f.single().change as RuleChange.Extra
        assertEquals(ExtraKind.COVER_ID, extra.kind)
        val rule = extra.rule
        assertEquals(ExtraBasis.UNIT, rule.basis)
        assertEquals(PayUnit.BASE_SHARE, rule.unit)
        assertEquals(0.1, rule.percentOfBase, 1e-9)
        assertEquals(480, rule.unitMinutes)
        val ot = rule.premium(Premium.OVERTIME)!!
        assertEquals(PremiumScope.Over(480), ot.scope)
        assertTrue(ot.on)
        assertEquals(0.5, ot.rate, 1e-9)
        assertTrue(rule.premium(Premium.NIGHT)!!.on)
        // Not named, so as the law has it: 휴일 ticked at 50%, 휴무일 not.
        assertTrue(rule.premium(Premium.HOLIDAY)!!.on)
        assertFalse(rule.premium(Premium.REST)!!.on)

        // Applied, it pays the 11-hour night cover of ExtraPremiumTest U2: 501,600원.
        val day = ShiftType(1, "주간", "주", 0xFF1E88E5, t(7), t(19), 60, t(12))
        val night = ShiftType(2, "야간", "야", 0xFF5E35B1, t(19), t(7), 60, t(2))
        val offType = ShiftType(3, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
        val four = listOf(day.id, day.id, offType.id, offType.id, night.id, night.id, offType.id, offType.id)
        fun roster(vararg e: DayEntry) = ScheduleResolver(listOf(day, night, offType), WorkPattern.Rotation(LocalDate.of(2026, 11, 1), four), e.toList())
        val p = RuleText.apply(salary, f.map { it.change }, nov)
        val cover = DayEntry(LocalDate.of(2026, 11, 3), extras = listOf(ExtraEntry(ExtraKind.COVER_ID, t(19), t(7), 60, t(2), coveredShiftId = night.id)))
        assertEquals(501_600, PayCalculator().calculate(p, nov, roster(cover)::resolve).total - PayCalculator().calculate(p, nov, roster()::resolve).total)
    }

    @Test
    fun `T2 premiums, night hours, monthly hours and how the company counts`() {
        val text = """
            연장 150%, 야간 가산 50%
            야간은 23시부터 7시까지
            월 소정근로시간 180시간
            5인 미만 사업장
            휴게시간도 근무시간으로 인정
            공휴일 근무는 대근처럼 계산
            쉬는 날 근무는 휴일근로로 쳐줌
            근무표 시간은 모두 기본근무
        """.trimIndent()
        val changes = RuleText.read(text).map { it.change }
        assertEquals(
            listOf(
                RuleChange.Premium(PremiumTarget.OVERTIME, 0.5),
                RuleChange.Premium(PremiumTarget.NIGHT, 0.5),
                RuleChange.NightWindow(t(23), t(7)),
                RuleChange.MonthlyHours(180),
                RuleChange.FiveOrMore(false),
                RuleChange.Breaks(BreakPay.AS_WORK),
                RuleChange.PublicHolidayAs(ExtraKind.COVER_ID, "대근"),
                RuleChange.RestDay(RestDayWork.HOLIDAY),
                RuleChange.ScheduleRegular(true),
            ),
            changes,
        )
        val asked = salary.copy(estimatedFields = setOf(Unknowns.MONTHLY_HOURS, Unknowns.FIVE_OR_MORE, Unknowns.BREAK_PAY))
        val p = RuleText.apply(asked, changes, nov)
        assertEquals(180, p.monthlyHours)
        assertFalse(p.fiveOrMoreEmployees)
        assertEquals(t(23), p.rules.nightStart)
        assertEquals(ExtraKind.COVER_ID, p.rules.publicHolidayAs)
        assertTrue(p.scheduleIsRegular)
        // What the sentences settle leaves 확인 필요.
        assertTrue(p.estimatedFields.isEmpty())
    }

    @Test
    fun `T3 pay, allowances, bonuses and raises`() {
        val text = """
            기본급 2,508,000원
            자격수당 월 5만원
            식대 20만원
            나이트 수당 1회 3만원
            상여금 연 800% 짝수달 지급
            명절상여 50만원 설·추석
            매년 3월 3.5% 인상
        """.trimIndent()
        val changes = RuleText.read(text).map { it.change }
        assertEquals(RuleChange.Base(WageType.MONTHLY, 2_508_000), changes[0])
        assertEquals(RuleChange.Allowance("자격수당", 50_000, Cadence.MONTHLY), changes[1])
        assertEquals(RuleChange.Allowance("식대", 200_000, Cadence.MONTHLY), changes[2])
        assertEquals(Cadence.PER_NIGHT_SHIFT, (changes[3] as RuleChange.Allowance).cadence)
        assertEquals(RuleChange.Bonus("상여금", 800.0, null, setOf(2, 4, 6, 8, 10, 12)), changes[4])
        assertEquals(RuleChange.Bonus("명절상여", null, 500_000, emptySet(), setOf(Festival.SEOLLAL, Festival.CHUSEOK)), changes[5])
        assertEquals(RuleChange.Raise(3, 3.5), changes[6])
        val p = RuleText.apply(salary.copy(items = emptyList()), changes, nov)
        // 상여 800% over 6 months: 기본급의 133.3%씩; 자격수당 in 통상임금, 식대 not.
        val bonus = p.items.single { it.name == "상여금" }
        assertEquals(8.0 / 6, bonus.percentOfBase!!, 1e-9)
        assertTrue(p.items.single { it.name == "자격수당" }.ordinary)
        assertFalse(p.items.single { it.name == "식대" }.ordinary)
        assertEquals(3, p.base.raise!!.month)
    }

    @Test
    fun `T4 rules of the company's own become general rules`() {
        val types = listOf(office.copy(name = "야간", id = 20))
        val f = RuleText.read(
            """
            토요일 근무는 50% 더 줌
            19시~23시 근무는 25% 가산
            일요일 근무하면 1회 2만원
            12시간 넘는 시간은 시간당 3,000원
            야간 근무때 1만원 더
            """.trimIndent(),
            shiftTypes = types,
        )
        val rules = f.map { (it.change as RuleChange.Custom).rule }
        assertEquals(RuleWhen(daysOfWeek = setOf(DayOfWeek.SATURDAY)), rules[0].whenever)
        assertEquals(RulePay.OfHourly(0.5), rules[0].pay)
        assertEquals("토요일 가산", rules[0].name)
        assertEquals(RuleWhen(from = t(19), to = t(23)), rules[1].whenever)
        assertEquals(RulePay.OfHourly(0.25), rules[1].pay)
        assertEquals(RulePay.PerTime(20_000), rules[2].pay)
        assertEquals(RuleWhen(overMinutes = 720), rules[3].whenever)
        assertEquals(RulePay.PerHour(3_000), rules[3].pay)
        assertEquals(setOf(20L), rules[4].whenever.shiftTypeIds)
        assertEquals(RulePay.PerTime(10_000), rules[4].pay)
        // Applied, Saturday pays as R1.
        val p = RuleText.apply(salary, f.take(1).map { it.change }, nov)
        assertEquals(192_000, added(p, sixDays()))
    }

    @Test
    fun `T5 payslip amounts are not rules, so they are never counted twice`() {
        assertTrue(RuleText.read("연장근로수당 523,000원\n야간근로수당 120,000\n지급합계 3,500,000원\n국민연금 135,000").isEmpty())
        // A night window alone is not a rule of the company's own.
        assertEquals(listOf(RuleChange.NightWindow(t(22), t(6))), RuleText.read("야간 시간 22:00~06:00").map { it.change })
        assertEquals(t(22) to t(6), RuleText.clockRange("밤 10시부터 새벽 6시까지"))
        assertNull(RuleText.clockRange("8-12"))
    }

    // ---- reading payslips ----

    private val slip = """
        2026년 11월분 급여명세서
        성명 민지   부서 생산1팀
        지급내역
        기본급 2,508,000   연장근로수당 288,000
        식대 200,000
        지급합계 2,996,000
        공제내역
        국민연금 112,860  건강보험 88,900
        소득세 50,000
        공제합계 251,760
        차인지급액 2,744,240
        계산방법
        연장근로수당: 16시간 × 12,000원 × 1.5 = 288,000원
    """.trimIndent()

    @Test
    fun `P1 a payslip in rows reads every line, the hours from its 계산 방법`() {
        val r = PayslipReader.read(slip)
        assertEquals(nov, r.month)
        assertEquals(2_996_000L, r.gross)
        assertEquals(2_508_000L, r.amount(SlipRole.BASE))
        assertEquals(288_000L, r.amount(SlipRole.OVERTIME))
        assertEquals(16 * 60, r.minutes(SlipRole.OVERTIME))
        assertEquals(mapOf("식대" to 200_000L), r.allowances)
        assertEquals(251_760L, r.amount(SlipRole.DEDUCTION_TOTAL))
        assertEquals(2_744_240L, r.amount(SlipRole.NET))
        assertTrue(r.consistent)
        // The 계산 방법 line adds hours to the line read from the table, not a second line.
        assertEquals(1, r.lines.count { it.role == SlipRole.OVERTIME })
    }

    @Test
    fun `P2 a photo read column by column pairs names and amounts in order`() {
        val r = PayslipReader.read("기본급\n연장근로수당\n야간근로수당\n지급총액\n2,508,000\n288,000\n60,000\n2,856,000")
        assertEquals(2_508_000L, r.amount(SlipRole.BASE))
        assertEquals(288_000L, r.amount(SlipRole.OVERTIME))
        assertEquals(60_000L, r.amount(SlipRole.NIGHT))
        assertEquals(2_856_000L, r.gross)
        assertTrue(r.consistent)
    }

    @Test
    fun `P3 hours on their own line join the amount, and a 합계 is the gross when nothing else says`() {
        val r = PayslipReader.read("기본급 2,508,000원\n연장근로시간 16.0\n연장근로수당 288,000원\n합계 2,796,000원")
        assertEquals(16 * 60, r.minutes(SlipRole.OVERTIME))
        assertEquals(2_796_000L, r.gross)
        val p = r.payslip()!!
        assertEquals(288_000L, p.overtimePay)
        assertEquals(960, p.overtimeMinutes)
    }

    @Test
    fun `P4 a read payslip goes straight into matching and finds 209 hours`() {
        val weekdays = ScheduleResolver(listOf(office, off), WorkPattern.Rotation(LocalDate.of(2026, 9, 28), List(5) { office.id } + List(2) { off.id }),
            listOf(3, 4, 5, 6, 10, 11, 12, 13).map { DayEntry(LocalDate.of(2026, 11, it), extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120))) })
        val read = PayslipReader.read(slip)
        val asked = salary.copy(monthlyHours = 226, estimatedFields = setOf(Unknowns.MONTHLY_HOURS))
        val m = PayslipMatcher.match(asked, nov, weekdays::resolve, read.payslip()!!)
        assertEquals(12_000L, m.payslipHourly)
        assertEquals(209, m.profile.monthlyHours)
        // 식대 is not in the app yet: named, and the rest matches to the won.
        assertEquals(mapOf("식대" to 200_000L), m.unmatched)
        assertEquals(200_000L, m.gap)
        assertNotNull(m.hints.firstOrNull { it.key == "unmatched:식대" })
    }

    @Test
    fun `W1 amounts read in the units people say`() {
        assertEquals("250만 8천원", com.bamnat.core.pay.wonWords(2_508_000))
        assertEquals("300만원", com.bamnat.core.pay.wonWords(3_000_000))
        assertEquals("1만 320원", com.bamnat.core.pay.wonWords(10_320))
        assertEquals("1억 5,000만원", com.bamnat.core.pay.wonWords(150_000_000))
        assertEquals("5천원", com.bamnat.core.pay.wonWords(5_000))
    }

    @Test
    fun `P5 lines recognised a column at a time go back on their rows`() {
        val text = com.bamnat.core.pay.OcrRows.join(listOf(
            com.bamnat.core.pay.OcrLine("기본급", 10, 100, 80, 120),
            com.bamnat.core.pay.OcrLine("연장근로수당", 10, 130, 120, 150),
            com.bamnat.core.pay.OcrLine("2,508,000", 300, 102, 380, 121),
            com.bamnat.core.pay.OcrLine("288,000", 300, 131, 370, 149),
        ))
        assertEquals("기본급  2,508,000\n연장근로수당  288,000", text)
        assertEquals(288_000L, PayslipReader.read(text).amount(SlipRole.OVERTIME))
    }
}
