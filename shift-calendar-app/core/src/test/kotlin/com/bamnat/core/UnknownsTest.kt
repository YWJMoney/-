package com.bamnat.core

import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.AnnualPay
import com.bamnat.core.pay.BreakPay
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayRange
import com.bamnat.core.pay.PaySetupQuestions
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.SetupFact
import com.bamnat.core.pay.SetupQuestion
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * [PAYS-01] The 확인 필요 keys: which setup questions apply to a person, what 모르겠어요 calculates with, the
 * other answers a range runs, and keys written by older versions.
 */
class UnknownsTest {
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private fun wage(v: Long) = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), v)))
    private val monday = LocalDate.of(2026, 11, 2)
    private val off = ShiftType(99, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private val office = ShiftType(10, "사무", "사", 0xFF000000, t(9), t(18), 60, t(12))
    private val weekdays = WorkPattern.Weekly.fromSteps(monday, List(5) { office.id } + listOf(null, null))

    private fun profile(occupation: Occupation = Occupation.CUSTOM, type: WageType = WageType.MONTHLY, base: Long = 2_508_000) =
        PayProfile(occupation = occupation, wageType = type, base = wage(base))

    private val fixedOt = PayItem("f", "고정 연장수당", wage(300_000), Cadence.MONTHLY, fixedOvertimeMinutes = 20 * 60)
    private val nightPay = PayItem("n", "나이트 수당", wage(30_000), Cadence.PER_NIGHT_SHIFT)
    private val meals = PayItem("m", "식대", wage(200_000), Cadence.MONTHLY)

    private fun asked(p: PayProfile, types: List<ShiftType>, pattern: WorkPattern?) =
        PaySetupQuestions.applicable(p, types, pattern)

    // ---- 어떤 질문을 묻나 ----

    @Test
    fun `a 2-shift nurse is asked about long shifts, holidays, days off and breaks, not supervisory approval`() {
        val day = ShiftType(1, "데이", "D", 0xFF000000, t(7), t(19), 60, t(12))
        val night = ShiftType(2, "나이트", "N", 0xFF000000, t(19), t(7), 60, t(2))
        val pattern = WorkPattern.Rotation(monday, listOf(day.id, day.id, off.id, off.id, night.id, night.id, off.id, off.id))
        val q = asked(profile(Occupation.NURSE).copy(items = listOf(nightPay, meals)), listOf(day, night, off), pattern)
        assertEquals(
            listOf(
                SetupQuestion.FIVE_OR_MORE, SetupQuestion.SCHEDULE_IS_REGULAR, SetupQuestion.PUBLIC_HOLIDAYS, SetupQuestion.REST_DAY_WORK,
                SetupQuestion.BREAK_PAY, SetupQuestion.COVERS, SetupQuestion.MONTHLY_HOURS,
            ),
            q.map { it.question },
        )
        // 11시간 근무, and 4 of them in 7 days of the cycle: 44시간.
        assertEquals(listOf(SetupFact.SHIFT_OVER_8H, SetupFact.WEEK_OVER_40H), q.single { it.question == SetupQuestion.SCHEDULE_IS_REGULAR }.facts)
        assertEquals(listOf(SetupFact.SHIFT_WORK), q.single { it.question == SetupQuestion.PUBLIC_HOLIDAYS }.facts)
        // The 나이트 수당 may stand in for the night premium; 식대 never does.
        val covers = q.single { it.question == SetupQuestion.COVERS }
        assertEquals(Unknowns.covers("n"), covers.key)
        assertEquals("n", covers.itemId)
        assertEquals(listOf(SetupFact.NIGHT_ALLOWANCE), covers.facts)
        assertEquals(listOf(Unknowns.FIVE_OR_MORE, Unknowns.SCHEDULE_IS_REGULAR, Unknowns.PUBLIC_HOLIDAYS, Unknowns.REST_DAY_WORK,
            Unknowns.BREAK_PAY, "covers:n", Unknowns.MONTHLY_HOURS), q.map { it.key })
    }

    @Test
    fun `a 24-hour guard is asked about supervisory approval`() {
        val guard = ShiftType(30, "당직", "당", 0xFF000000, t(9), t(9), 480, t(18))
        val pattern = WorkPattern.Rotation(monday, listOf(guard.id, off.id))
        val q = asked(profile(Occupation.SECURITY), listOf(guard, off), pattern).single { it.question == SetupQuestion.SUPERVISORY }
        assertEquals(Unknowns.SUPERVISORY, q.key)
        assertEquals(listOf(SetupFact.SHIFT_20H, SetupFact.SECURITY_WORK), q.facts)
        // The roster alone is enough, and so is the job before any roster.
        assertTrue(asked(profile(), listOf(guard, off), pattern).any { it.question == SetupQuestion.SUPERVISORY })
        assertEquals(listOf(SetupFact.SECURITY_WORK),
            asked(profile(Occupation.SECURITY), emptyList(), null).single { it.question == SetupQuestion.SUPERVISORY }.facts)
        // A shift type the pattern does not use does not count.
        val unused = WorkPattern.Rotation(monday, listOf(office.id, off.id))
        assertTrue(asked(profile(), listOf(guard, office, off), unused).none { it.question == SetupQuestion.SUPERVISORY })
    }

    @Test
    fun `an office week is asked only what can change its pay`() {
        assertEquals(
            listOf(SetupQuestion.FIVE_OR_MORE, SetupQuestion.REST_DAY_WORK, SetupQuestion.BREAK_PAY, SetupQuestion.MONTHLY_HOURS),
            asked(profile(), listOf(office, off), weekdays).map { it.question },
        )
        // 8시간 with a 30-minute break, paid by the hour: no 휴게 and no 월 소정근로시간 question.
        val short = ShiftType(11, "사무", "사", 0xFF000000, t(9), t(17, 30), 30, t(12))
        val shortWeek = WorkPattern.Weekly.fromSteps(monday, List(5) { short.id } + listOf(null, null))
        assertEquals(listOf(SetupQuestion.FIVE_OR_MORE, SetupQuestion.REST_DAY_WORK),
            asked(profile(Occupation.PART_TIME, WageType.HOURLY, 12_000), listOf(short, off), shortWeek).map { it.question })
        // Under 5 the 공휴일 question is whether the company gives them at all.
        assertEquals(listOf(SetupFact.UNDER_FIVE),
            asked(profile().copy(fiveOrMoreEmployees = false), listOf(office, off), weekdays).single { it.question == SetupQuestion.PUBLIC_HOLIDAYS }.facts)
        // Saturdays too: weekend work and a 48-hour week.
        val six = asked(profile(), listOf(office, off), WorkPattern.Weekly.fromSteps(monday, List(6) { office.id } + listOf(null)))
        assertEquals(listOf(SetupFact.SHIFT_WORK), six.single { it.question == SetupQuestion.PUBLIC_HOLIDAYS }.facts)
        assertEquals(listOf(SetupFact.WEEK_OVER_40H), six.single { it.question == SetupQuestion.SCHEDULE_IS_REGULAR }.facts)
        // Five days on, two off from a Wednesday works every weekend: shift work.
        val fromWednesday = WorkPattern.Rotation(monday.plusDays(2), List(5) { office.id } + listOf(off.id, off.id))
        assertTrue(asked(profile(), listOf(office, off), fromWednesday).any { it.question == SetupQuestion.PUBLIC_HOLIDAYS })
        // Pay worked out from a 연봉 says so where the 월 소정근로시간 is asked.
        assertEquals(listOf(SetupFact.MONTHLY_PAY, SetupFact.ANNUAL_PAY),
            asked(profile().copy(annual = AnnualPay(30_096_000)), listOf(office, off), weekdays).single { it.question == SetupQuestion.MONTHLY_HOURS }.facts)
        // A 고정 연장수당 asks what it stands in for.
        val fixed = asked(profile().copy(items = listOf(fixedOt, meals)), listOf(office, off), weekdays).single { it.question == SetupQuestion.COVERS }
        assertEquals(listOf(SetupFact.FIXED_OVERTIME), fixed.facts)
        assertEquals("f", fixed.itemId)
    }

    @Test
    fun `before a roster exists every shift type counts and the roster questions are asked`() {
        assertEquals(
            listOf(SetupQuestion.FIVE_OR_MORE, SetupQuestion.PUBLIC_HOLIDAYS, SetupQuestion.REST_DAY_WORK, SetupQuestion.BREAK_PAY,
                SetupQuestion.MONTHLY_HOURS),
            asked(profile(), listOf(office, off), null).map { it.question },
        )
    }

    // ---- 모르겠어요 ----

    @Test
    fun `an unknown answer calculates with the assumed value and adds the key`() {
        val bonus = PayItem("b", "상여금", wage(0), Cadence.BONUS, months = setOf(6, 12), percentOfBase = 1.0)
        val answered = profile().copy(
            fiveOrMoreEmployees = false, scheduleIsRegular = true, monthlyHours = 243, holidayPayMode = HolidayPayMode.PREMIUM_ONLY,
            rules = CompanyRules(supervisoryApproved = true, publicHolidaysPaid = false, holidaysUnderFive = true,
                restDayWork = RestDayWork.HOLIDAY, breakPay = BreakPay.AS_WORK),
            items = listOf(fixedOt.copy(covers = setOf(LineKind.OVERTIME, LineKind.NIGHT)), bonus),
        )
        fun mark(key: String, roster: Int? = null) = Unknowns.markUnknown(answered, key, roster)
        assertTrue(mark(Unknowns.FIVE_OR_MORE).fiveOrMoreEmployees)
        assertFalse(mark(Unknowns.SUPERVISORY).rules.supervisoryApproved)
        assertFalse(mark(Unknowns.SCHEDULE_IS_REGULAR).scheduleIsRegular)
        mark(Unknowns.PUBLIC_HOLIDAYS).rules.let {
            assertTrue(it.publicHolidaysPaid)
            assertFalse(it.holidaysUnderFive)
        }
        assertEquals(RestDayWork.LAW, mark(Unknowns.REST_DAY_WORK).rules.restDayWork)
        assertEquals(BreakPay.UNPAID, mark(Unknowns.BREAK_PAY).rules.breakPay)
        assertEquals(209, mark(Unknowns.MONTHLY_HOURS).monthlyHours)
        assertEquals(146, mark(Unknowns.MONTHLY_HOURS, 146).monthlyHours)
        assertEquals(HolidayPayMode.FULL, mark(Unknowns.HOLIDAY_PAY_MODE).holidayPayMode)
        // 금액 모름 clears the amount, and a bonus's share of 기본급 with it.
        mark(Unknowns.item("b")).items.single { it.id == "b" }.let {
            assertEquals(0, it.amount.on(YearMonth.of(2026, 12)).value)
            assertNull(it.percentOfBase)
        }
        mark(Unknowns.covers("f")).items.single { it.id == "f" }.let {
            assertNull(it.covers)
            assertEquals(setOf(LineKind.OVERTIME), it.coveredKinds)
        }
        // Only that setting changes; the key comes in, and an answer takes it out again.
        assertEquals(answered.copy(fiveOrMoreEmployees = true, estimatedFields = setOf(Unknowns.FIVE_OR_MORE)), mark(Unknowns.FIVE_OR_MORE))
        assertTrue(Unknowns.answered(mark(Unknowns.SUPERVISORY), Unknowns.SUPERVISORY).estimatedFields.isEmpty())
    }

    @Test
    fun `each unknown says what is assumed and offers the other answers`() {
        val p = profile().copy(items = listOf(fixedOt, nightPay, meals))
        fun d(key: String, profile: PayProfile = p, roster: Int? = null) = Unknowns.describe(key, profile, roster)!!
        fun labels(key: String, profile: PayProfile = p, roster: Int? = null) = d(key, profile, roster).answers.map { it.label }

        assertEquals("5인 이상으로 계산해요", d(Unknowns.FIVE_OR_MORE).assumed)
        assertEquals(listOf("5인 미만"), labels(Unknowns.FIVE_OR_MORE))
        assertFalse(d(Unknowns.FIVE_OR_MORE).answers.single().apply(p).fiveOrMoreEmployees)
        assertEquals("승인 없음으로 계산해요", d(Unknowns.SUPERVISORY).assumed)
        assertTrue(d(Unknowns.SUPERVISORY).answers.single().apply(p).rules.supervisoryApproved)
        assertEquals("8시간 넘는 시간은 법대로 연장으로 계산해요", d(Unknowns.SCHEDULE_IS_REGULAR).assumed)
        assertEquals(listOf("모두 기본 근무"), labels(Unknowns.SCHEDULE_IS_REGULAR))
        // 공휴일: 휴일 대체 at 5인 이상, given or not below that.
        assertEquals(listOf("휴일 대체 (평일처럼)"), labels(Unknowns.PUBLIC_HOLIDAYS))
        assertFalse(d(Unknowns.PUBLIC_HOLIDAYS).answers.single().apply(p).rules.publicHolidaysPaid)
        val small = p.copy(fiveOrMoreEmployees = false)
        assertEquals("공휴일은 평일처럼 계산해요 (5인 미만)", d(Unknowns.PUBLIC_HOLIDAYS, small).assumed)
        assertTrue(d(Unknowns.PUBLIC_HOLIDAYS, small).answers.single().apply(small).rules.holidaysUnderFive)
        assertEquals("쉬는 날 근무는 법대로 계산해요", d(Unknowns.REST_DAY_WORK).assumed)
        assertEquals(listOf("휴일근로로", "연장근로로"), labels(Unknowns.REST_DAY_WORK))
        assertEquals("휴게는 무급으로 계산해요", d(Unknowns.BREAK_PAY).assumed)
        assertEquals(listOf("유급", "근무시간"), labels(Unknowns.BREAK_PAY))
        // 월 소정근로시간: the usual values and the roster's; nothing to run for hourly pay.
        assertEquals("209시간으로 계산해요", d(Unknowns.MONTHLY_HOURS).assumed)
        assertEquals(listOf("226시간", "243시간", "146시간"), labels(Unknowns.MONTHLY_HOURS, roster = 146))
        assertFalse(d(Unknowns.MONTHLY_HOURS, profile(type = WageType.HOURLY, base = 12_000)).movesRange)
        // Amounts are listed but move nothing.
        assertEquals("기본급 (대략)", d(Unknowns.BASE).label)
        assertEquals("일당 (대략)", d(Unknowns.BASE, profile(type = WageType.DAILY, base = 150_000)).label)
        assertFalse(d(Unknowns.BASE).movesRange)
        d(Unknowns.item("m")).let {
            assertEquals("식대 금액", it.label)
            assertEquals("금액을 몰라 합계에서 뺐어요", it.assumed)
            assertFalse(it.movesRange)
            assertNull(it.question)
        }
        // What an allowance stands in for.
        d(Unknowns.covers("f")).let {
            assertEquals("고정 연장수당이 대신하는 가산", it.label)
            assertEquals("고정 연장수당은 연장 가산을 대신하는 것으로 계산해요", it.assumed)
            assertEquals(listOf("연장·야간 대신", "연장·야간·휴일 대신"), it.answers.map { a -> a.label })
            assertEquals(setOf(LineKind.OVERTIME, LineKind.NIGHT), it.answers.first().apply(p).items.single { i -> i.id == "f" }.coveredKinds)
            assertEquals(SetupQuestion.COVERS, it.question)
        }
        assertEquals("나이트 수당은 따로 받는 수당으로 계산해요", d(Unknowns.covers("n")).assumed)
        assertEquals(listOf("야간 대신"), labels(Unknowns.covers("n")))
        assertTrue(labels(Unknowns.covers("m")).isEmpty())
        // Keys this version does not know, and allowances since deleted, are nothing.
        assertNull(Unknowns.describe("someFutureKey", p))
        assertNull(Unknowns.describe(Unknowns.item("gone"), p))
        // A 모르겠어요 answer stores the question's key; the per-allowance one adds the allowance's id.
        assertEquals(Unknowns.SUPERVISORY, SetupQuestion.SUPERVISORY.key)
        assertTrue(Unknowns.covers("f").startsWith(SetupQuestion.COVERS.key))
    }

    @Test
    fun `covers only ever means the premiums an allowance can stand in for`() {
        assertEquals(setOf(LineKind.OVERTIME), fixedOt.coveredKinds)
        assertTrue(nightPay.coveredKinds.isEmpty())
        assertTrue(nightPay.isNightAllowance)
        assertFalse(meals.isNightAllowance)
        assertEquals(setOf(LineKind.NIGHT), meals.copy(covers = setOf(LineKind.BASE, LineKind.EXTRA, LineKind.NIGHT)).coveredKinds)
        assertTrue(fixedOt.copy(covers = emptySet()).coveredKinds.isEmpty())
    }

    // ---- 이전 버전의 키 ----

    @Test
    fun `keys from older versions stay harmless`() {
        val p = profile().copy(
            items = listOf(nightPay),
            estimatedFields = setOf(Unknowns.HOLIDAY_PAY_MODE, Unknowns.BASE, "n", "someFutureKey", Unknowns.item("gone")),
        )
        val list = Unknowns.of(p)
        assertEquals(listOf(Unknowns.BASE, Unknowns.HOLIDAY_PAY_MODE, "n"), list.map { it.key })
        list.single { it.key == Unknowns.HOLIDAY_PAY_MODE }.let {
            assertNull(it.question)
            assertEquals("100% + 가산으로 계산해요", it.assumed)
            assertEquals(listOf("가산만"), it.answers.map { a -> a.label })
        }
        assertEquals("나이트 수당 금액 (추정)", list.single { it.key == "n" }.label)
        // A range over them: only 휴일 급여 방식 moves October (10/5·10/9 근무, 2 × 8시간 × 12,000 less).
        val r = ScheduleResolver(listOf(office, off),
            WorkPattern.Rotation(LocalDate.of(2026, 9, 28), List(5) { office.id } + listOf(off.id, off.id)), emptyList())
        val range = PayRange.of(p, YearMonth.of(2026, 10), r::resolve)
        assertEquals(list.map { it.key }, range.effects.map { it.key })
        assertEquals(-192_000L, range.low - range.total)
        assertEquals(range.total, range.high)
        // The keys are carried along untouched.
        assertEquals(p.estimatedFields, Unknowns.answered(p, "notThere").estimatedFields)
    }
}
