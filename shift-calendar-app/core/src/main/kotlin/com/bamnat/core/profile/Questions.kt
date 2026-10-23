package com.bamnat.core.profile

import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.pay.WageType

/**
 * [EASY-05] How a missing fact is answered on the question card: one tap of a chip, one number, one date, or,
 * for what takes more than that (근무 순서, 수당 목록, 회사 규칙), the screen that already asks it.
 */
sealed interface Answer {
    /** [options] as (what the chip says, the value written). */
    data class Chips(val options: List<Pair<String, String>>) : Answer
    data class Number(val unit: String, val min: Int, val max: Int, val step: Int, val start: Int) : Answer
    data object Date : Answer
    /** The registry feature whose screen asks it; [label] is the button. */
    data class Open(val feature: String, val label: String) : Answer
}

/** [EASY-05] One question: [text] as it is asked, [answer] how. */
data class Question(val key: FactKey, val text: String, val answer: Answer)

/**
 * [EASY-05] The question for each [FactKey]. With no pay set up yet ([hasPay] false), a pay fact opens the pay
 * setup: its first question asks the kind of pay, so nothing is asked twice.
 */
object Questions {
    private const val PAY_SETUP = "pay.calc"

    fun of(key: FactKey, hasPay: Boolean): Question {
        if (key.money && key.group in PAY_GROUPS && key != FactKey.TAX_MODE && !hasPay) {
            return Question(key, "급여를 알려 주시면 이번 달 예상 급여가 나와요", Answer.Open(PAY_SETUP, "급여 설정하기"))
        }
        return when (key) {
            FactKey.WORK_STYLE -> Question(key, "어떻게 일하세요?", Answer.Open("calendar.edit", "일하는 방식 고르기"))
            FactKey.SHIFTS -> Question(key, "근무 종류와 시간을 알려 주세요", Answer.Open("shift.pattern", "근무 정하기"))
            FactKey.ROTATION -> Question(key, "근무 순서와 시작일을 알려 주세요", Answer.Open("shift.pattern", "근무 순서 정하기"))
            FactKey.EXTRA_KINDS -> Question(key, "회사의 잔업·특근은 어떻게 쳐 주나요?", Answer.Open("extra.kinds", "추가 근무 정하기"))
            FactKey.REST_RULES -> Question(key, "근무표를 어떤 기준으로 점검할까요?", Answer.Open("shift.restRules", "기준 고르기"))
            FactKey.TEAM -> Question(key, "몇 조에서 일하세요?", Answer.Open("shift.teams", "내 조 고르기"))
            FactKey.OVERTIME_CAP -> Question(key, "한 달 시간외는 몇 시간까지인가요?", Answer.Number("시간", 1, 100, 1, 52))
            FactKey.ALARM_LEAD -> Question(key, "근무 몇 분 전에 알람을 울릴까요?", Answer.Chips(listOf(30, 60, 90, 120).map { "${it}분 전" to "$it" }))
            FactKey.WAGE_TYPE -> Question(key, "급여는 어떻게 받으세요?", Answer.Chips(listOf(
                "월급" to WageType.MONTHLY.name, "시급" to WageType.HOURLY.name, "일당" to WageType.DAILY.name)))
            FactKey.BASE_PAY -> Question(key, "기본급이 얼마인가요? 명세서 사진이면 저절로 채워져요", Answer.Open(PAY_SETUP, "명세서·기본급 넣기"))
            FactKey.PAY_ITEMS -> Question(key, "받는 수당이 있나요?", Answer.Open(PAY_SETUP, "수당 넣기"))
            FactKey.PAY_DAY -> Question(key, "급여는 매달 며칠에 들어와요?", Answer.Number("일", 1, 31, 1, 10))
            FactKey.MONTHLY_HOURS -> Question(key, "명세서나 계약서의 월 소정근로시간이 몇 시간인가요?", Answer.Chips(listOf(
                "209시간 (주 40시간)" to "209", "226시간" to "226", "243시간" to "243", "모르겠어요" to UNKNOWN)))
            FactKey.DAILY_HOURS -> Question(key, "하루 정해진 근무는 몇 시간인가요?", Answer.Number("시간", 1, 12, 1, 8))
            FactKey.HOLIDAY_PAY -> Question(key, "쉬는 날 일하면 어떻게 받으세요?", Answer.Chips(listOf(
                "하루치를 더 받고 가산도 받아요" to HolidayPayMode.FULL.name, "가산분만 받아요" to HolidayPayMode.PREMIUM_ONLY.name,
                "모르겠어요" to UNKNOWN)))
            FactKey.NIGHT_OT -> Question(key, "야간·연장 수당을 받으세요?", Answer.Open("shift.premium", "수당 규칙 보기"))
            FactKey.PUBLIC_HOLIDAYS -> Question(key, "공휴일은 쉬는 날이고 일하면 수당을 받나요?", Answer.Open(PAY_SETUP, "공휴일 정하기"))
            FactKey.FIVE_OR_MORE -> Question(key, "회사 직원이 5명 이상인가요?", Answer.Chips(listOf("네, 5명 이상" to "true", "아니요, 4명 이하" to "false")))
            FactKey.HIRE_DATE -> Question(key, "입사일이 언제예요? 연차가 저절로 셈해져요", Answer.Date)
            FactKey.TAX_MODE -> Question(key, "급여에서 세금은 어떻게 떼요?", Answer.Chips(listOf(
                "4대보험" to TaxMode.INSURANCE_4.name, "3.3%" to TaxMode.WITHHOLDING_3_3.name, "안 떼요" to TaxMode.NONE.name)))
            FactKey.TAX_FAMILY -> Question(key, "본인을 포함해 공제받는 가족이 몇 명이에요?", Answer.Number("명", 1, 11, 1, 1))
            FactKey.PREVIOUS_YEAR_PAY -> Question(key, "작년에 받은 총급여가 얼마인가요?", Answer.Open(PAY_SETUP, "작년 급여 넣기"))
            FactKey.SLEEP_TARGET -> Question(key, "하루 몇 시간 자고 싶으세요?", Answer.Chips(listOf(6, 7, 8, 9).map { "${it}시간" to "${it * 60}" }))
            FactKey.COMMUTE -> Question(key, "퇴근해서 집까지 몇 분 걸려요?", Answer.Number("분", 0, 180, 5, 30))
            FactKey.COMPANY -> Question(key, "같은 회사 분들이 나눈 설정을 찾아볼까요?", Answer.Open("plaza.news", "회사 찾기"))
            FactKey.SHOP_RULES -> Question(key, "영업시간과 쉬는 날을 알려 주세요", Answer.Open("shop.days", "영업일 정하기"))
            FactKey.SHOP_MONEY -> Question(key, "카드 수수료와 정산일을 알려 주세요", Answer.Open("shop.cardDue", "정산 정하기"))
            FactKey.FIELD_RULES -> Question(key, "일당으로 세세요, 건별로 세세요?", Answer.Open("field.mode", "고르기"))
        }
    }

    /** A chip that leaves the fact as it is but stops asking: the app keeps its 법대로 가정. */
    const val UNKNOWN = "?"

    private val PAY_GROUPS = setOf(FactGroup.PAY, FactGroup.LEAVE, FactGroup.TAX, FactGroup.COMPANY)
}
