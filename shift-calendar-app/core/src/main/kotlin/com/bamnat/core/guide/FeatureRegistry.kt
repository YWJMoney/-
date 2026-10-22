package com.bamnat.core.guide

import com.bamnat.core.work.WorkStyle
import com.bamnat.core.work.words

/**
 * [GUIDE-01] Where a feature opens in the app. The app maps each place to its screen (a tab, a screen of
 * its own, or a group of settings with [FeatureRoute.focus] as the row to point at), so a new place
 * fails to compile until it has one.
 */
enum class Place {
    CALENDAR, TODAY, MONEY, AUTO_RECORD, MEMOS, STATS,
    PAY, PATTERN, SHIFT_TYPES, EXTRA_KINDS, WORK_STYLE, CALENDARS, CALENDAR_EDIT, SHOP_DAYS, COMBINED, OWED,
    SLEEP, DUTY_ROSTER, HEALTH_REPORT, TEAMS, YEAR, ATTENDANCE_IMPORT, COMMUTE, CALENDAR_IMPORT, SHARE, RECEIVE,
    DIAGNOSTICS, INQUIRY, FEATURES, PRO,
    SETTINGS_WORK, SETTINGS_ALARM, SETTINGS_REST, SETTINGS_NOTICES, SETTINGS_PAY, SETTINGS_CALENDAR,
    SETTINGS_DATA, SETTINGS_PRIVACY,
    ;

    /** A group of settings: [FeatureRoute.focus] names its row. */
    val isSettings: Boolean get() = name.startsWith("SETTINGS_")
}

/** [GUIDE-01] The deep link of a feature: a place and, inside it, the row or tool to open ("offers", "백업 저장"). */
data class FeatureRoute(val place: Place, val focus: String? = null)

/**
 * [GUIDE-05] Whether a feature can be turned off, and where its switch is kept:
 * [CALENDAR] is the app's own switch for one calendar (a card in 통계 or 돈), [APP] the app's own switch
 * for every calendar (the guide itself, widget lines), [ELSEWHERE] a switch that was there before
 * (매출 없으면 휴무, 출퇴근 자동 기록, 결제 알림 읽기…) which the 기능 켜고 끄기 screen shows instead of copying.
 */
enum class Off { CALENDAR, APP, ELSEWHERE }

/**
 * [GUIDE-03] The good moments for a one-line tip. The app works out which hold now ([GuideMoments]),
 * the guide picks one feature for them.
 */
enum class Moment {
    /** A shop wrote its first 하루 마감. */
    FIRST_DAY_CLOSE,
    /** The pay engine's 급여일, and the three days after. */
    PAYDAY,
    /** A part-timer's week reached 15 hours. */
    HOURLY_15H,
    /** A day worker is still on the clock after the end time. */
    PAST_END_TIME,
    /** A field calendar has its first job. */
    FIRST_JOB,
    /** A field calendar has money still owed. */
    UNPAID_JOB,
    /** A shift worker came off a night shift. */
    AFTER_NIGHT,
    /** An investor calendar has its first trade. */
    FIRST_TRADE,
}

/**
 * [GUIDE-01] One thing the user can do in the app: its [id] (stable, kept in the guide's state), the
 * requirement it came from, its name as the screen says it, what it does in a line, the kinds of work it
 * is for ([styles], empty for every kind), where it is ([where], the way there as the screens name it),
 * its deep link, other words people use for it, whether and how it can be turned off, a feature it
 * belongs to ([under]: off when that one is), and a tip for its good moment.
 */
data class AppFeature(
    val id: String,
    val req: String,
    val name: String,
    val what: String,
    val styles: Set<WorkStyle>,
    val where: String,
    val route: FeatureRoute,
    val keywords: List<String> = emptyList(),
    val off: Off? = null,
    val under: String? = null,
    val moment: Moment? = null,
    val tip: String? = null,
) {
    /** For this kind of work; a main calendar from before kinds of work were asked is a shift calendar. */
    fun fits(style: WorkStyle?): Boolean = styles.isEmpty() || (style ?: WorkStyle.SHIFT) in styles
    val canTurnOff: Boolean get() = off != null
}

/**
 * [GUIDE-01] Every user-facing feature, in the order they are suggested. To add one (or a new kind of
 * work, like 투자): add its [AppFeature] here with a [Place] the app can open, list the new kind in the
 * features it fits, and give the kind its checklist in [GuideChecklist.ORDER]. A new [Place] also needs
 * its screen in the app's `FeatureLinks`; an [Off.CALENDAR] card needs `FeatureCard(id)` around it, and an
 * [Off.ELSEWHERE] switch needs its adapter in the app's `FeatureSwitches`.
 */
object FeatureRegistry {
    private val SHIFT = WorkStyle.SHIFT
    private val DAY = WorkStyle.DAY
    private val HOURLY = WorkStyle.HOURLY
    private val FIELD = WorkStyle.FIELD
    private val SHOP = WorkStyle.BUSINESS
    private val INVEST = WorkStyle.INVEST
    private val PAID = setOf(SHIFT, DAY, HOURLY)
    private val ALL = emptySet<WorkStyle>()

    /** The tab that holds hours and money for [style], as the bottom bar names it. */
    private fun tab(style: WorkStyle) = style.words().tab
    private const val DAY_SHEET = "달력 › 날짜 누르기"

    private fun f(
        id: String, req: String, name: String, what: String, styles: Set<WorkStyle>, where: String, route: FeatureRoute,
        vararg keywords: String, off: Off? = null, under: String? = null, moment: Moment? = null, tip: String? = null,
    ) = AppFeature(id, req, name, what, styles, where, route, keywords.toList(), off, under, moment, tip)

    private fun at(place: Place, focus: String? = null) = FeatureRoute(place, focus)

    val all: List<AppFeature> = listOf(
        // The guide itself [GUIDE-02][GUIDE-03].
        f("guide.checklist", "GUIDE-02", "이 달력으로 할 수 있는 것", "새 달력이나 근무 유형을 바꾼 달력에서 처음 며칠 쓸 만한 기능을 골라 보여 줘요",
            ALL, "달력 맨 위 카드", at(Place.FEATURES), "처음", "시작", "체크리스트", "안내 카드", "할 수 있는 것", off = Off.APP),
        f("guide.tips", "GUIDE-03", "알맞은 때 한 줄 안내", "급여일·첫 하루 마감처럼 알맞은 때 쓸 만한 기능을 하루 한 번까지 한 줄로 알려 줘요",
            ALL, "달력 맨 위 한 줄", at(Place.FEATURES), "팁", "안내", "알려 주기", "추천", off = Off.APP),

        // Shift work.
        f("shift.pattern", "SET-01", "근무 주기", "주주야야휴휴처럼 돌아가는 순서와 시작일을 정하면 달력이 채워져요",
            setOf(SHIFT), "설정 › 내 근무 › 근무 주기", at(Place.PATTERN), "순서", "패턴", "로테이션", "교대 순서", "시작일"),
        f("shift.premium", "SHFT-01", "근무 종류별 수당", "근무 종류마다 ‘이 근무 수당 n%’와 야간 여부를 정해 급여에 더해요",
            setOf(SHIFT), "설정 › 내 근무 › 근무 종류와 시간", at(Place.SHIFT_TYPES), "근무 코드", "야간 수당", "가산율", "수당"),
        f("alarm.check", "REL-13", "알람 점검", "다음 알람, 꺼진 권한, 배터리 최적화를 하나씩 확인하고 그 자리에서 고쳐요",
            PAID, "설정 › 알람 › 알람 점검", at(Place.DIAGNOSTICS), "알람이 안 울려요", "늦게 울려요", "시험 알람", "권한"),
        // [PROV-06][PROV-07] 확실한 알람 (프로, 1.20.0).
        f("alarm.mission", "PROV-06", "끄기 미션", "알람을 끌 때 계산 문제를 풀거나 휴대폰을 흔들어야 꺼져요. 근무마다 다르게 고를 수 있어요",
            PAID, "설정 › 알람 › 끄기 미션", at(Place.SETTINGS_ALARM, "끄기 미션"), "미션", "계산 문제", "흔들기", "못 일어나요", "다시 잠들어요"),
        f("alarm.wakeCheck", "PROV-07", "일어났는지 확인", "알람을 끄고 몇 분 뒤 ‘일어났나요?’를 묻고, 답이 없으면 한 번 더 울려요",
            PAID, "설정 › 알람 › 일어났는지 확인", at(Place.SETTINGS_ALARM, "일어났는지 확인"), "일어났나요", "기상 확인", "또 잠들", "두 번 울림"),
        f("share.link", "SHR-01", "근무표 공유", "이번 달을 이미지로 보내거나 앱 없이 열리는 링크를 만들어요",
            ALL, "달력 › 공유 (좁은 화면은 ⋮ › 근무표 공유)", at(Place.SHARE), "공유", "링크", "카톡", "가족", "동료", "보내기"),
        f("share.receive", "UX-03", "동료 근무표 받기", "받은 링크를 넣고 내 달력과 바꿔 보며 같이 쉬는 날을 찾아요",
            ALL, "달력 › ⋮ › 동료 근무표 받기", at(Place.RECEIVE), "받은 링크", "바꿔 보기", "같이 쉬는 날", "남편", "아내"),
        // [PROV-04][FEAT-15][PROV-05] 프로, 1.20.0.
        f("share.moreLinks", "PROV-04", "공유 링크 여러 개", "달력마다 링크를 5개까지 따로 만들어 보낼 것과 기간을 다르게 하고, 180일·1년도 골라요",
            ALL, "달력 › 공유 › 링크 › + 새 링크", at(Place.SHARE), "링크 여러 개", "링크 추가", "1년 링크", "180일", "기간 늘리기"),
        f("share.feed", "FEAT-15", "캘린더 구독 주소", "근무표 링크를 구글·아이폰·아웃룩 캘린더가 구독하게 해서 바뀐 근무가 따라오게 해요",
            ALL, "달력 › 공유 › 링크 › 캘린더 구독 주소", at(Place.SHARE), "구독", "ICS", "아이폰 캘린더", "아웃룩", "캘린더 구독"),
        f("share.rosterPdf", "PROV-05", "근무표 PDF", "한 달이나 1년 근무표를 A4로 인쇄할 수 있는 PDF로 만들어요",
            ALL, "달력 › 공유 › ○월 PDF·○년 PDF", at(Place.SHARE), "PDF", "인쇄", "출력", "A4", "근무표 뽑기"),
        f("shift.firstCard", "SHFT-02", "이번 달 근무 카드", "근무 코드별 횟수, 야간 횟수, 예상 급여를 한 카드로 보여 줘요",
            setOf(SHIFT), "${tab(SHIFT)} › ○월 근무", at(Place.STATS), "코드별 횟수", "야간 횟수", "근무 횟수", off = Off.CALENDAR),
        f("shift.swap", "SHFT-03", "근무 교환·대근 기록", "날짜마다 누구와 바꿨는지 적고 달마다 모아 봐요",
            setOf(SHIFT), "$DAY_SHEET › 바꾼 사람", at(Place.TODAY), "교환", "대근", "바꿈", "근무 바꾸기", "맞교대"),
        f("shift.swapCard", "SHFT-03", "교환·대근 모아 보기", "이번 달 바꾼 근무와 대근을 사람별로 모아 보여 줘요",
            setOf(SHIFT), "${tab(SHIFT)} › 교환·대근", at(Place.STATS), "교환 모아", "대근 횟수", off = Off.CALENDAR),
        f("shift.roster", "SHFT-06", "듀티표 사진으로 넣기", "듀티표 사진이나 붙여 넣은 글자에서 내 줄을 찾아 한 달을 넣어요",
            setOf(SHIFT), "설정 › 내 근무 › 듀티표 사진으로 넣기", at(Place.DUTY_ROSTER), "듀티표", "근무표 사진", "글자 인식", "간호사", "OCR"),
        f("shift.teams", "CREW-01", "모든 조 보기", "3조 2교대·4조 2교대처럼 조가 있는 근무에서 모든 조의 근무를 날짜별로 보고, 쉬는 조를 찾아 대근을 부탁해요",
            setOf(SHIFT), "달력 › 모든 조 (또는 도구 › 모든 조 보기)", at(Place.TEAMS), "모든 조", "전체 조", "조별", "다른 조", "A조", "B조", "대근 구하기", "쉬는 조"),
        f("shift.dutyTyping", "NURS-01", "한 달 근무 연달아 넣기", "근무표를 보며 D·E·N·OFF를 누르면 다음 날로 넘어가며 한 달을 넣고, 역순·나이트 뒤 쉬는 날·연속 근무를 알려 줘요",
            setOf(SHIFT), "달력 › 도구 › 한 달 근무 연달아 넣기 (빠른 입력 › 한 달 연달아)", at(Place.CALENDAR),
            "연달아", "키패드", "한 달 입력", "듀티 입력", "간호사", "나이트", "역순", "근무표 점검"),
        f("shift.health", "SHFT-07", "연간 건강 지표", "야간 비율, 가장 길게 이어진 야간, 11시간 못 쉰 날을 1년으로 봐요",
            setOf(SHIFT), "${tab(SHIFT)} › ○년 건강 지표", at(Place.HEALTH_REPORT), "야간 비율", "연속 야간", "건강", "휴식",
            off = Off.CALENDAR, moment = Moment.AFTER_NIGHT,
            tip = "야간 근무 수고했어요. ‘연간 건강 지표’에서 야간 비율과 쉰 시간을 볼 수 있어요"),
        f("shift.nightDark", "SHFT-05", "야간 근무 중 어둡게", "야간 근무 시간에는 화면을 저절로 어둡게 바꿔요",
            setOf(SHIFT), "설정 › 달력 화면 › 테마", at(Place.SETTINGS_CALENDAR, "테마"), "다크 모드", "어둡게", "야간 모드", "눈부심"),
        f("sleep.log", "SLP-16", "수면 기록", "잠든 시간과 깬 시간을 적고 근무별 평균을 봐요",
            setOf(SHIFT), "달력 › 오늘의 흐름 › 수면 기록", at(Place.SLEEP), "잠", "수면", "잠들었어요", "깼어요", "낮잠"),
        f("today.flow", "SLP-14", "오늘의 흐름", "달력 맨 위에 지금 할 일 하나와 잘 시간·다음 근무를 보여 줘요",
            PAID, "설정 › 잠과 휴식 › 오늘의 흐름 보기", at(Place.SETTINGS_REST, "오늘의 흐름 보기"), "잘 시간", "카페인", "낮잠", "오늘 카드",
            off = Off.ELSEWHERE),

        // Pay, for every kind of work paid by the hour or the month.
        f("pay.calc", "PAY-01", "급여 계산", "근무와 시간으로 이번 달 예상 급여를 계산하고 명세서와 맞춰 봐요",
            PAID, "${tab(SHIFT)} › 이번 달 급여", at(Place.PAY), "월급", "급여", "시급", "주휴수당", "명세서", "수당", "기본급"),
        f("pay.gap", "SHFT-04", "실제 입금 차이", "급여일에 들어온 급여와 계산한 급여의 차이를 알려 줘요",
            PAID, "${tab(SHIFT)} › ○월 급여 들어온 돈", at(Place.STATS), "입금", "차이", "들어온 돈", "실수령", "세후",
            off = Off.CALENDAR, moment = Moment.PAYDAY,
            tip = "오늘은 급여일이에요. 들어온 급여를 적으면 계산과 ‘실제 입금 차이’를 알려 드려요"),
        // [PROV-01][PROV-02] 프로, 1.20.0.
        f("pay.report", "PROV-01", "급여 리포트", "한 달이나 1년 급여를 계산 풀이와 날짜별 근무까지 PDF로 남기거나 CSV로 엑셀에서 열어요",
            PAID, "${tab(SHIFT)} › 이번 달 급여 › 급여 리포트", at(Place.PAY), "리포트", "급여 PDF", "CSV", "엑셀", "근거", "급여 내역"),
        f("stats.compare", "PROV-02", "지난달·작년 비교", "근무시간·야간·휴일·추가 근무·급여를 지난달, 작년 같은 달과 나란히 보고 올해를 달마다 봐요",
            PAID, "${tab(SHIFT)} › 지난달·작년 비교", at(Place.STATS), "비교", "지난달", "작년", "올해 근무", "연간 리포트"),
        f("extra.kinds", "EXTRA-04", "잔업·특근·대근", "추가 근무의 이름과 가산 방식을 회사에 맞게 정하고 날짜마다 적어요",
            setOf(SHIFT, DAY), "설정 › 내 근무 › 연장 휴무일 근무 지정", at(Place.EXTRA_KINDS), "잔업", "특근", "대근", "연장", "야근", "휴일 근무"),

        // Day work.
        f("day.firstCard", "DAYW-02", "이번 달 한눈에", "이번 달 야근 시간, 연차 사용·잔여, 예상 실수령을 한 카드로 보여 줘요",
            setOf(DAY), "${tab(DAY)} › ○월 한눈에", at(Place.STATS), "야근 시간", "연차 잔여", "실수령", off = Off.CALENDAR),
        f("day.leaveNow", "DAYW-03", "지금 퇴근", "누르면 퇴근 시각까지 남은 시간을 야근으로 적어요. 위젯에도 있어요",
            setOf(DAY), "$DAY_SHEET › 지금 퇴근", at(Place.TODAY), "퇴근", "야근 적기", "칼퇴", "퇴근 버튼",
            moment = Moment.PAST_END_TIME, tip = "퇴근 시간이 지났어요. ‘지금 퇴근’을 누르면 더 일한 시간이 야근으로 적혀요"),
        f("day.overtimeCap", "DAYW-05", "월 시간외 상한", "한 달 시간외 상한을 정하면 남은 시간과 넘었을 때 알려 줘요",
            setOf(DAY), "${tab(DAY)} › 시간외 상한", at(Place.STATS), "시간외", "상한", "야근 한도", "초과", off = Off.CALENDAR),
        f("day.bridges", "DAYW-04", "징검다리 연휴", "연차 하루이틀로 길게 쉬는 날을 12개월 안에서 찾아 줘요",
            setOf(DAY), "${tab(DAY)} › 징검다리 연휴", at(Place.STATS), "연휴", "연차 추천", "샌드위치", "휴가 계획", off = Off.CALENDAR),
        f("day.attendance", "DAYW-06", "근태 엑셀 가져오기", "회사 근태 시스템의 엑셀·CSV를 읽어 늦은 출근·야근·연차를 적어요",
            setOf(DAY), "설정 › 내 근무 › 근태 엑셀 가져오기", at(Place.ATTENDANCE_IMPORT), "엑셀", "CSV", "근태", "xlsx", "출퇴근 기록"),
        f("day.commute", "DAYW-07", "출퇴근 자동 기록", "회사에 도착하고 떠날 때를 휴대폰이 알아채 출퇴근을 적어요. 위치는 휴대폰 안에만 있어요",
            setOf(DAY), "설정 › 내 근무 › 출퇴근 자동 기록", at(Place.COMMUTE), "위치", "자동 출근", "자동 퇴근", "GPS", "지오펜스",
            off = Off.ELSEWHERE),

        // Part-time work.
        f("hourly.clock", "HRLY-08", "출근·퇴근 버튼", "누르면 실제 시각으로 적고, 근무 기록을 CSV로 내보내요",
            setOf(HOURLY), "$DAY_SHEET › 출근·퇴근", at(Place.TODAY), "출근", "퇴근", "출퇴근 기록", "근무 기록", "CSV"),
        f("hourly.attendance", "HRLY-01", "지각·조퇴·결근 한 번에", "하루 화면에서 한 번 눌러 지각·조퇴·결근을 적고, 대타는 대근으로 적어요",
            setOf(HOURLY), "$DAY_SHEET › 지각·조퇴·결근", at(Place.TODAY), "지각", "조퇴", "결근", "대타", "출결"),
        f("hourly.weeks", "HRLY-02", "주휴수당 주별 확인", "주마다 근무시간과 15시간을 채웠는지, 주휴 시간이 얼마인지 보여 줘요",
            setOf(HOURLY), "${tab(HOURLY)} › 주휴수당", at(Place.STATS), "주휴", "주휴수당", "15시간", "개근",
            off = Off.CALENDAR, moment = Moment.HOURLY_15H,
            tip = "이번 주 15시간을 채웠어요. ‘주휴수당 주별 확인’에서 주휴수당을 받을 수 있는지 봐요"),
        f("hourly.takeHome", "HRLY-04", "세금 방식과 예상 실수령", "없음·3.3%·4대보험 중에 골라 손에 쥐는 돈을 보여 줘요",
            setOf(HOURLY), "${tab(HOURLY)} › 이번 달 급여 › 세금", at(Place.PAY), "세금", "3.3%", "4대보험", "실수령", "세후"),
        f("hourly.breaks", "HRLY-05", "휴게시간 자동 차감", "4시간 일하면 30분, 8시간이면 1시간 법정 휴게를 저절로 빼요",
            setOf(HOURLY), "${tab(HOURLY)} › 이번 달 급여 › 휴게시간", at(Place.PAY), "휴게", "쉬는 시간", "브레이크"),
        f("hourly.tools", "HRLY-09", "알바 도구 카드", "두 알바 비교, 근로계약서, 그만두는 날 정리를 한 카드에 모아요",
            setOf(HOURLY), "${tab(HOURLY)} › 알바 도구", at(Place.STATS), "알바 도구", off = Off.CALENDAR),
        f("hourly.offers", "HRLY-09", "두 알바 비교하기", "시급·주휴·세금·교통비까지 따져 시간당 실제로 남는 돈을 나란히 봐요",
            setOf(HOURLY), "${tab(HOURLY)} › 알바 도구 › 두 알바 비교하기", at(Place.STATS, "offers"), "비교", "이직", "어느 알바", "계산기",
            under = "hourly.tools"),
        f("hourly.contract", "HRLY-10", "근로계약서 보관", "계약서 사진과 시급·근무 요일·계약 기간을 보관하고 끝나기 7일 전에 알려 줘요",
            setOf(HOURLY), "${tab(HOURLY)} › 알바 도구 › 근로계약서", at(Place.STATS, "contract"), "계약서", "근로계약", "계약 기간", "사진",
            under = "hourly.tools"),
        f("hourly.quit", "HRLY-11", "그만두는 날 정리", "마지막 주 주휴, 남은 연차수당, 퇴직금을 확인하고 그날 뒤 근무를 멈춰요",
            setOf(HOURLY), "${tab(HOURLY)} › 알바 도구 › 그만두는 날 정리", at(Place.STATS, "quit"), "퇴사", "그만두기", "퇴직금", "마지막 주",
            under = "hourly.tools"),

        // Field work.
        f("field.mode", "FLD-01", "일당형·건별형 고르기", "현장 달력을 공수(일당)로 셀지 건별 금액으로 셀지 정해요",
            setOf(FIELD), "달력 › ⋮ › 고치기 › 세는 방식", at(Place.CALENDAR_EDIT), "일당", "공수", "건별", "세는 방식"),
        f("field.units", "FLD-02", "공수 버튼", "날짜에 1·0.5·1.5 공수를 한 번에 적고 일당×공수로 계산해요",
            setOf(FIELD), "$DAY_SHEET › 공수", at(Place.TODAY), "공수", "반공", "일당", "품"),
        f("field.status", "FLD-03", "일감 상태", "가계약 → 확정 → 완료 → 입금, 취소까지 일감마다 상태를 바꿔요",
            setOf(FIELD), "$DAY_SHEET › 일감 › 상태", at(Place.TODAY), "가계약", "확정", "완료", "입금", "취소"),
        f("field.due", "FLD-09", "입금 약속일과 미수 알림", "일감마다 입금 약속일을 정하면 지나도 안 들어왔을 때 알려 줘요",
            setOf(FIELD), "$DAY_SHEET › 일감 › 입금 약속일", at(Place.TODAY), "약속일", "미수", "안 들어온 돈", "외상",
            moment = Moment.FIRST_JOB, tip = "일감을 적었어요. ‘입금 약속일’을 정해 두면 늦을 때 알려 드려요"),
        f("field.deposit", "FLD-06", "입금 알림을 미수 일감에 잇기", "은행 입금 알림이 오면 받을 돈이 남은 일감에 바로 맞춰요",
            setOf(FIELD), "돈 › 입금 확인", at(Place.MONEY), "입금 확인", "미수금", "입금 매칭", off = Off.CALENDAR,
            moment = Moment.UNPAID_JOB, tip = "받을 돈이 남은 일감이 있어요. 입금 알림이 오면 ‘입금 확인’에서 한 번에 맞출 수 있어요"),
        f("field.statement", "FLD-05", "거래내역서 보내기", "거래처마다 한 달 일감을 정산 요청용 이미지로 만들어 보내요",
            setOf(FIELD), "${tab(FIELD)} › 거래처별 › 거래처 누르기", at(Place.STATS), "거래명세서", "정산", "거래처", "내역서"),
        f("field.year", "FLD-07", "연간 3.3%·경비 합계와 출역일", "종합소득세 신고에 쓸 올해 번 돈·뗀 3.3%·경비와 출역일을 모아 봐요",
            setOf(FIELD), "${tab(FIELD)} › 출역일", at(Place.STATS), "종합소득세", "5월", "출역일", "퇴직공제", "경비", off = Off.CALENDAR),
        f("field.dailyTax", "FLD-13", "일용직 세금", "이번 달 일당에서 떼는 일용근로소득세를 한 번에·날마다 받을 때로 보여 줘요",
            setOf(FIELD), "${tab(FIELD)} › 일용직 세금", at(Place.STATS), "원천징수", "일용근로", "세금", "2.7%", off = Off.CALENDAR),
        f("field.quote", "FLD-11", "견적서", "품목·수량·단가로 견적서 사진을 만들어 보내고, 좋다고 하면 일감으로 저장해요",
            setOf(FIELD), "${tab(FIELD)} › 견적서 › 새 견적서", at(Place.STATS, "quote"), "견적", "견적서", "부가세", "단가", off = Off.CALENDAR),
        f("field.tradeWage", "FLD-12", "공종별 평균 일당", "대한건설협회 시중노임단가를 보고 일당을 골라 넣어요",
            setOf(FIELD), "$DAY_SHEET › 공수 › 직접 › 평균 일당 보기", at(Place.TODAY), "노임단가", "평균 일당", "공종", "단가표"),
        f("field.receipt", "FLD-08", "영수증 사진", "경비를 적을 때 증빙과 영수증 사진을 함께 남겨요",
            setOf(FIELD, SHOP), "돈 › 지출 적기 › 증빙", at(Place.MONEY), "영수증", "증빙", "사진", "경비"),

        // Shops.
        f("shop.days", "TYPE-03", "영업일·정기휴무", "영업시간, 매주 쉬는 요일, 매달 n째 요일, 공휴일 휴무를 달력에 바로 반영해요",
            setOf(SHOP), "설정 › 내 근무 › 영업일·정기휴무", at(Place.SHOP_DAYS), "영업시간", "정기휴무", "휴무일", "쉬는 요일"),
        f("shop.dayClose", "SHOP-01", "하루 마감", "카드·현금·계좌·배달앱으로 나눠 매출을 적고 합계를 봐요",
            setOf(SHOP), "$DAY_SHEET › 매출 적기", at(Place.TODAY), "마감", "매출 적기", "정산", "카드 매출", "현금 매출"),
        f("shop.cardDue", "SHOP-02", "카드 입금 예정", "수수료를 뗀 카드 매출이 언제 들어오는지 달력에 보이고 입금을 확인해요",
            setOf(SHOP), "설정 › 내 근무 › 영업일·정기휴무 › 카드 매출 입금", at(Place.SHOP_DAYS), "카드 입금", "수수료", "정산일", "카드사",
            moment = Moment.FIRST_DAY_CLOSE, tip = "첫 하루 마감을 했어요. 카드 매출이 언제 얼마 들어오는지 ‘카드 입금 예정’으로 볼 수 있어요"),
        f("shop.autoClose", "TYPE-04", "매출 없으면 휴무", "지난 영업일에 매출이 없으면 앱을 열 때 휴무로 바꿔요. 나중에 매출을 적으면 다시 영업",
            setOf(SHOP), "설정 › 내 근무 › 영업일·정기휴무", at(Place.SHOP_DAYS), "자동 휴무", "매출 없는 날", "휴무 자동", off = Off.ELSEWHERE),
        f("shop.cash", "SHOP-03", "현금 시재 맞추기", "있어야 할 현금과 센 현금의 차이를 알려 줘요",
            setOf(SHOP), "$DAY_SHEET › 매출 적기 › 현금 시재", at(Place.TODAY), "시재", "현금", "돈통", "차이"),
        f("shop.split", "SHOP-01", "결제 수단별 매출", "이번 달 매출을 카드·현금·계좌·배달앱으로 나눠 보여 줘요",
            setOf(SHOP), "${tab(SHOP)} › 결제 수단별", at(Place.STATS), "결제 수단", "카드 비율", "배달앱", off = Off.CALENDAR),
        f("shop.target", "SHOP-05", "목표 매출", "한 달 목표를 정하면 남은 영업일로 나눈 하루 목표를 보여 줘요",
            setOf(SHOP), "${tab(SHOP)} › 목표 매출", at(Place.STATS), "목표", "하루 목표", "매출 목표", off = Off.CALENDAR),
        f("money.incomeTax", "LEDG-26", "종합소득세 참고 정리", "올해 수입·적은 경비·떼인 3.3%를 모으고 5월 신고 기간을 알려 줘요(참고용, 세무 조언 아님)",
            setOf(FIELD, SHOP), "${tab(FIELD)} › ○년 종합소득세 참고", at(Place.STATS), "종합소득세", "5월", "3.3%", "경비", "신고", "세금", off = Off.CALENDAR),
        f("shop.vat", "SHOP-04", "부가세 예상", "반기 매출과 경비로 대략의 부가세를 보고 신고 달에 알려 줘요",
            setOf(SHOP), "${tab(SHOP)} › 부가세 예상", at(Place.STATS), "부가세", "세금", "신고", "1월", "7월", off = Off.CALENDAR),
        f("shop.staff", "SHOP-11", "직원 인건비", "직원·시급·주휴·4대보험으로 근무표를 짜고 월 인건비와 인건비율을 봐요",
            setOf(SHOP), "${tab(SHOP)} › 직원 인건비 › 근무표 짜기", at(Place.STATS, "staff"), "직원", "인건비", "알바생", "근무표 짜기",
            off = Off.CALENDAR),
        f("shop.weather", "SHOP-10", "날씨별 매출", "하루 마감에 날씨를 고르면 비 오는 날과 맑은 날 평균 매출을 견줘요",
            setOf(SHOP), "${tab(SHOP)} › 날씨별 매출", at(Place.STATS), "날씨", "비", "맑음", off = Off.CALENDAR),
        f("shop.simpleTax", "SHOP-09", "간이과세 기준", "올해 매출과 연말 예상을 간이과세 기준(1억 400만 원)과 견줘요",
            setOf(SHOP), "${tab(SHOP)} › 간이과세 기준", at(Place.STATS), "간이과세", "일반과세", "연 매출", off = Off.CALENDAR),
        f("shop.capture", "SHOP-08", "카드 단말기·배달앱 알림을 매출로", "결제 알림 읽기를 켜면 단말기·배달앱 알림이 매출로, 정산 입금이 카드 입금으로 적혀요",
            setOf(SHOP), "돈 › ⋮ › 자동 기록", at(Place.AUTO_RECORD), "단말기", "배달의민족", "쿠팡이츠", "포스", "매출 알림"),

        // Investing [INV-01..INV-21]: the 투자·수익 tab's tabs (한눈에·종목·기록·통계·일지·알람) and sheets.
        f("invest.deposit", "INV-02", "원금 입금·출금", "계좌에 넣고 뺀 돈을 적으면 원금이 정해지고 수익률·현금이 바로 맞아요",
            setOf(INVEST), "${tab(INVEST)} › 한눈에 › 입금 적기", at(Place.STATS, "invest.cash"), "원금", "입금", "출금", "계좌", "현금"),
        f("invest.trade", "INV-03", "매매 기록", "종목·수량·단가·수수료와 이유·마음을 적어요. 수수료·거래세는 알아서 제안해요",
            setOf(INVEST), "${tab(INVEST)} › 한눈에 › 매수·매도 (달력 날짜를 길게 눌러도)", at(Place.STATS, "invest.trade"),
            "매수", "매도", "매매", "주식 기록", "거래", "코인"),
        f("invest.alerts", "INV-30", "체결 알림으로 매매 적기", "증권사·거래소 체결 알림을 휴대폰 안에서 읽어 매수·매도를 찾고, 확인하면 알림 온 앱·코인/주식 표시와 함께 적어요",
            setOf(INVEST), "${tab(INVEST)} › 한눈에 › 알림으로 찾은 거래", at(Place.STATS, "invest.overview"),
            "체결 알림", "자동 기록", "업비트", "증권사 알림", "코인", "바로 적기"),
        f("invest.quotes", "INV-04", "현재가 적기", "보유 종목의 현재가를 적으면 평가손익과 총자산이 보여요",
            setOf(INVEST), "${tab(INVEST)} › 종목 › 현재가", at(Place.STATS, "invest.quotes"), "현재가", "평가손익", "보유 종목", "평단", "평균단가"),
        f("invest.returns", "INV-05", "수익률 세 가지", "원금 대비·금액가중·시간가중 수익률을 설명과 함께 봐요",
            setOf(INVEST), "${tab(INVEST)} › 한눈에", at(Place.STATS, "invest.overview"), "수익률", "시간가중", "금액가중", "원금 대비"),
        f("invest.history", "INV-21", "총자산 흐름", "현재가가 다 있는 날의 총자산을 모아 흐름 그래프로 보여 줘요",
            setOf(INVEST), "${tab(INVEST)} › 한눈에 › 총자산 흐름", at(Place.STATS, "invest.overview"), "총자산", "그래프", "자산 추이",
            off = Off.CALENDAR),
        f("invest.stats", "INV-06", "일·월·연 손익과 승률", "기간별 손익, 승률, 종목별 손익, 월별 막대를 봐요",
            setOf(INVEST), "${tab(INVEST)} › 통계", at(Place.STATS, "invest.stats"), "손익", "승률", "월별", "종목별", "통계"),
        f("invest.mind", "INV-13", "마음과 원칙별 손익", "감정 태그·원칙 지킴에 따른 손익, 손익비, 연속 수익·손실일을 봐요",
            setOf(INVEST), "${tab(INVEST)} › 통계 › 마음과 원칙에 따라", at(Place.STATS, "invest.stats"), "감정", "원칙", "손익비", "복기",
            off = Off.CALENDAR),
        f("invest.weekdays", "INV-18", "요일별 손익", "요일마다 손익과 평균 보유일을 봐요",
            setOf(INVEST), "${tab(INVEST)} › 통계 › 요일별 손익", at(Place.STATS, "invest.stats"), "요일", "보유일", off = Off.CALENDAR),
        f("invest.tax", "INV-12", "투자 세금 미리 보기", "해외주식 양도세, 배당·금융소득, ISA·연금 계좌를 대략 계산해요",
            setOf(INVEST), "${tab(INVEST)} › 통계 › 세금 미리 보기", at(Place.STATS, "invest.stats"), "양도세", "250만", "금융소득", "ISA", "세금",
            off = Off.CALENDAR),
        f("invest.journal", "INV-07", "투자일지", "날마다 시장 메모·복기·원칙 체크·하루 평가를 남겨요",
            setOf(INVEST), "${tab(INVEST)} › 일지", at(Place.STATS, "invest.journal"), "일지", "복기", "매매일지", "원칙 체크", "메모",
            moment = Moment.FIRST_TRADE, tip = "첫 매매를 적었어요. ‘투자일지’에 이유와 복기를 남기면 나중에 마음과 원칙별 손익을 볼 수 있어요"),
        f("invest.alarms", "INV-08", "투자 알람", "장 시작·마감, 정기 매수일, 목표가 확인 알람을 맞추고 알림에서 바로 기록해요",
            setOf(INVEST), "${tab(INVEST)} › 알람", at(Place.STATS, "invest.alarms"), "장 시작", "장 마감", "정기 매수", "적립식", "목표가"),
        f("invest.dividend", "INV-09", "배당·이자 기록", "세전 금액과 원천징수, 실수령을 적고 종목별 배당 합계를 봐요",
            setOf(INVEST), "${tab(INVEST)} › 기록 › 배당", at(Place.STATS, "invest.dividend"), "배당", "이자", "배당금", "원천징수"),
        f("invest.goal", "INV-11", "투자 목표", "총자산·수익률 목표와 날짜를 정하면 진행률과 매달 넣을 돈을 알려 줘요",
            setOf(INVEST), "${tab(INVEST)} › ⋮ › 목표와 수익률", at(Place.STATS, "invest.goal"), "목표", "총자산 목표", "매달 넣을 돈", "진행률",
            off = Off.CALENDAR),
        f("invest.events", "INV-14", "투자 일정", "배당락·실적 발표·공시·청약 날을 적으면 그날 아침 알려 줘요",
            setOf(INVEST), "${tab(INVEST)} › 한눈에 › 다가오는 일", at(Place.STATS, "invest.event"), "배당락", "실적 발표", "공시", "청약", "일정",
            off = Off.CALENDAR),
        f("invest.accounts", "INV-15", "계좌별 보기", "계좌를 골라 그 계좌만의 수익률과 보유 종목을 봐요",
            setOf(INVEST), "${tab(INVEST)} › 맨 위 계좌 칩", at(Place.STATS, "invest.overview"), "계좌별", "ISA", "연금저축", "증권사"),
        f("invest.hide", "INV-17", "금액 가리기", "남이 볼 때 금액을 가리고 수익률만 보여요",
            setOf(INVEST), "${tab(INVEST)} › 눈 아이콘", at(Place.STATS, "invest.overview"), "가리기", "숨기기", "금액 숨김", "개인정보"),
        f("invest.csv", "INV-10", "투자 기록 CSV", "매매·입출금·보유·일지를 CSV로 내보내요",
            setOf(INVEST), "${tab(INVEST)} › ⋮ › CSV로 내보내기", at(Place.STATS, "invest.overview"), "CSV", "엑셀", "내보내기"),

        // Money and records, for every kind of work.
        f("money.auto", "LEDG-16", "결제 알림 자동 기록", "카드·은행 결제 알림을 읽어 가계부에 저절로 적어요",
            ALL, "돈 › ⋮ › 자동 기록", at(Place.AUTO_RECORD), "가계부", "카드 알림", "문자", "자동 기록", "결제 알림", off = Off.ELSEWHERE),
        f("money.confirm", "COMN-04", "확인하고 적기", "결제 알림을 바로 적지 않고 확인 대기함에 두었다가 확인한 것만 적어요",
            ALL, "돈 › ⋮ › 자동 기록 › 확인하고 적기", at(Place.AUTO_RECORD), "확인 대기함", "바로 적기", "검토"),
        f("money.rules", "LEDG-21", "자동 분류 규칙", "가게마다 배운 분류를 보고 바꾸거나, ‘이 말이 들어가면’ 규칙을 더해요",
            ALL, "돈 › ⋮ › 자동 기록 › 자동 분류 규칙", at(Place.AUTO_RECORD), "분류", "카테고리", "기타", "규칙"),
        f("money.otherPlaces", "LEDG-23", "기타로 남은 곳 정리", "기타로 남은 가게를 모아 한 번에 분류해요",
            ALL, "돈 › 요약 › 기타로 남은 곳", at(Place.MONEY), "기타", "미분류", "분류 정리", off = Off.CALENDAR),
        f("money.repeat", "LEDG-24", "매달 나가는 돈 알아보기", "석 달 넘게 비슷하게 나간 돈을 반복 결제로 등록하자고 제안해요",
            ALL, "돈 › 요약 › 매달 나가는 돈 같아요", at(Place.MONEY), "반복 결제", "구독", "고정비", "정기 결제", off = Off.CALENDAR),
        f("money.owed", "RECV-02", "받을 돈", "빌려준 돈, 외상, 못 받은 급여를 적고 받으면 표시해요",
            ALL, "돈 › 받을 돈", at(Place.OWED), "빌려준 돈", "외상", "미수금", "갚을 돈"),

        // Calendars, memos and data.
        f("calendars.multi", "MCAL-01", "달력 여러 개", "알바·부업처럼 일을 달력마다 따로 적어요",
            ALL, "달력 › 달력 이름 › 새 달력 만들기", at(Place.CALENDARS), "달력 추가", "새 달력", "투잡", "부업", "알바"),
        f("calendar.edit", "CALS-01", "달력 고치기", "달력마다 이름·색·근무 유형·알람·결제 알림·공유를 한곳에서 바꿔요",
            ALL, "달력 › ⋮ › ‘달력 이름’ 고치기", at(Place.CALENDAR_EDIT), "달력 수정", "이름 바꾸기", "색 바꾸기", "근무 유형"),
        f("calendar.customize", "CUST-01", "화면 꾸미기", "달력과 근무·급여 화면의 카드를 켜고 끄고 순서를 바꿔요. 미리보기로 바로 보여요",
            ALL, "달력 › ⋮ › 화면 꾸미기", at(Place.CALENDAR, "customize"), "배치", "순서", "카드 숨기기", "하루 카드", "레이아웃", "편집"),
        f("help.inquiry", "INQ-02", "문의와 진행 상황", "불편한 점·제안을 보내면 접수부터 반영 버전까지 단계가 보이고, 답을 평가할 수 있어요",
            ALL, "설정 › 도움말과 문의", at(Place.INQUIRY), "문의", "건의", "제보", "버그", "진행 상황", "답변"),
        f("calendar.year", "CAL-22", "1년 달력", "열두 달을 한 화면에 근무 색으로 보고, 한 해의 근무 종류별 날 수와 휴가를 세요",
            ALL, "달력 › ⋮ › 1년 달력", at(Place.YEAR), "연간 달력", "1년", "한 해", "열두 달", "휴가 계획", "연간 근무"),
        f("privacy.lock", "LOCK-01", "앱 잠금", "열 때 지문·얼굴·PIN을 물어 급여·메모를 지켜요. 알람은 그대로 울려요",
            ALL, "설정 › 개인정보와 권한 › 앱 잠금", at(Place.SETTINGS_PRIVACY, "앱 잠금"), "잠금", "비밀번호", "지문", "생체 인증", "PIN", "숨기기"),
        f("calendar.combined", "COMN-01", "모든 달력 합쳐 보기", "모든 달력을 한 달력에 색 막대로 보고 겹치는 시간을 알려 줘요",
            ALL, "달력 › 달력 이름 › 모든 달력 합쳐 보기", at(Place.COMBINED), "합쳐 보기", "겹치는", "한눈에", "모든 달력"),
        f("stats.all", "COMN-03", "통계 전체 보기", "모든 달력의 총수입·총지출·순저축을 한 번에 봐요",
            ALL, "통계 탭 › 전체", at(Place.STATS), "전체", "모든 달력 통계", "총수입"),
        f("memo.sharing", "MEMO-02", "메모 공유", "메모를 달력마다 따로 두거나, 몇 달력끼리 함께 봐요",
            ALL, "설정 › 달력 화면 › 메모 공유", at(Place.SETTINGS_CALENDAR, "메모 공유"), "공용 메모장", "메모 같이", "달력별 메모"),
        f("memo.repeat", "NOTE-05", "반복 메모", "매달 25일, 매주 화요일처럼 돌아오는 메모를 적어요",
            ALL, "메모 › 메모 쓰기 › 반복", at(Place.MEMOS), "반복", "매달", "매주", "정기"),
        f("trash.records", "UNDO-01", "최근 지운 기록", "근무 유형을 바꾸거나 지운 기록·달력을 7일 동안 되돌려요",
            ALL, "설정 › 데이터 › 최근 지운 기록", at(Place.SETTINGS_DATA, "최근 지운 기록"), "되돌리기", "휴지통", "복구", "실수로 지움"),
        f("data.backup", "REL-04", "백업 저장", "근무·메모·돈 기록을 파일로 만들어 새 휴대폰으로 옮겨요",
            ALL, "설정 › 데이터 › 백업 저장", at(Place.SETTINGS_DATA, "백업 저장"), "백업", "옮기기", "휴대폰 바꾸기", "복원"),
        f("data.autoBackup", "PROV-03", "매일 자동 백업", "고른 파일(구글 드라이브 등)에 암호 건 백업을 하루 한 번 덮어써요. 3일 넘게 못 하면 알려 줘요",
            ALL, "설정 › 데이터 › 매일 자동 백업", at(Place.SETTINGS_DATA, "매일 자동 백업"), "자동 백업", "매일 백업", "구글 드라이브", "클라우드"),
        f("calendar.phone", "FEAT-07", "휴대폰 캘린더에 근무 쓰기", "휴대폰 안 ‘밤낮’ 캘린더에 근무를 적어 삼성·구글 캘린더 앱에서도 보여요. 끄면 그 캘린더를 지워요",
            ALL, "설정 › 데이터 › 휴대폰 캘린더에 근무 쓰기", at(Place.SETTINGS_DATA, "휴대폰 캘린더에 근무 쓰기"), "구글 캘린더", "삼성 캘린더", "캘린더 연동", "캘린더에 쓰기"),
        f("app.pro", "MON-04", "프로", "광고 없이 쓰고, 명세서 대조·급여 리포트·휴대폰 캘린더 쓰기·매일 자동 백업·끄기 미션·돈 통계·달력 여러 개를 열어요. 1개월 무료로 써 볼 수 있어요",
            ALL, "설정 › 프로", at(Place.PRO), "구독", "광고 없애기", "무료 체험", "유료", "결제", "이용권", "복구 코드"),
        f("privacy.adChoices", "MON-07", "광고 개인정보 선택", "광고가 쓰는 정보를 보고, 법이 묻는 지역에서는 동의를 다시 골라요",
            ALL, "설정 › 개인정보와 권한 › 광고 개인정보 선택", at(Place.SETTINGS_PRIVACY, "광고 개인정보 선택"), "광고", "맞춤 광고", "광고 ID", "동의"),
        f("lock.today", "FEAT-18", "잠금 화면 오늘 근무", "오늘·내일 근무를 소리 없는 알림으로 잠금 화면에 늘 보여 줘요",
            PAID, "설정 › 알림 › 잠금 화면 오늘 근무", at(Place.SETTINGS_NOTICES, "잠금 화면 오늘 근무"), "잠금 화면", "상시 알림", off = Off.ELSEWHERE),
        f("widget.lines", "COMN-05", "위젯 근무 유형 줄", "오늘 위젯에 알바 주 시간·주휴, 일근 남은 연차·야근, 현장 이번 주 공수를 한 줄 더 보여 줘요",
            setOf(DAY, HOURLY, FIELD), "홈 화면 › 밤낮 오늘 위젯", at(Place.FEATURES), "위젯", "홈 화면", off = Off.APP),
        // 1.22 묶음 E: on by default (S10); each says where it turns off.
        f("widget.memo", "DAILY-01", "위젯에 메모 줄", "오늘·이번 주 위젯에 그날 메모 첫 줄이 보여요. 날짜를 누르면 그날이 열려요",
            ALL, "설정 › 달력 › 위젯에 메모 보이기", at(Place.SETTINGS_CALENDAR, "위젯에 메모 보이기"), "위젯", "메모", "홈 화면", "끄기"),
        f("widget.teams", "DAILY-03", "오늘 모든 조 위젯", "홈 화면에서 조마다 오늘·내일 근무를 봐요. 내 조는 굵게 보여요",
            setOf(SHIFT), "홈 화면 › 위젯 › 밤낮 오늘 모든 조", at(Place.TEAMS), "위젯", "모든 조", "조별 근무", "홈 화면"),
        f("money.receiptScan", "LEDG-25", "영수증 사진 읽기", "영수증을 찍으면 금액·날짜·시간·가게를 채워요. 휴대폰 안에서만 읽고 저장 전에 확인해요",
            ALL, "돈 › 돈 적기 › 지출 › 영수증 사진 읽기", at(Place.MONEY), "영수증", "사진", "OCR", "자동 입력"),
        f("money.owedCsv", "LEDG-27", "받을 돈 CSV", "받을 돈 목록을 남은 돈·약속일·상태까지 엑셀에서 여는 CSV로 보내요",
            ALL, "돈 › 받을 돈 › 내보내기", at(Place.OWED), "CSV", "엑셀", "받을 돈", "내보내기", under = "money.owed"),
        f("data.backupPhotos", "BACK-01", "사진도 백업", "영수증·계약서 사진을 백업 파일에 같이 넣어 새 휴대폰에서도 보여요",
            ALL, "설정 › 데이터 › 사진도 백업", at(Place.SETTINGS_DATA, "사진도 백업"), "사진", "백업", "영수증", "계약서", under = "data.backup"),
        f("invest.monthSummary", "INV-34", "월말 투자 요약 알림", "달 마지막 날 저녁 8시에 그달 매수·매도와 실현 손익을 알려 줘요. 금액 가리기를 따르고 거래 없는 달은 조용해요",
            setOf(INVEST), "${tab(INVEST)} › ⋮ › 목표와 수익률 › 월말 요약 알림", at(Place.STATS, "invest.goal"), "월말", "요약", "알림", "한 달 정리"),
        f("money.customize", "DAILY-15", "돈·메모 화면 꾸미기", "돈 관리 요약 카드와 메모 위 줄의 순서와 켜고 끄기를 바꿔요",
            ALL, "돈 › ⋮ › 화면 꾸미기, 메모 › 화면 꾸미기", at(Place.MONEY), "화면 꾸미기", "순서", "카드 숨기기", "필터 접기", under = "calendar.customize"),
        f("pay.rateHint", "DAILY-16", "광장 시세와 견주기", "광장 임금표에서 본 지역 시세와 내 일당·시급이 10% 넘게 다르면 급여 화면에 한 줄 보여요. 휴대폰 안에서만 견줘요",
            setOf(SHIFT, DAY, HOURLY, FIELD), "설정 › 급여 › 광장 시세와 내 단가 견주기", at(Place.SETTINGS_PAY, "광장 시세와 내 단가 견주기"), "시세", "일당", "시급", "비교"),
    )

    private val byId: Map<String, AppFeature> = all.associateBy { it.id }

    fun get(id: String): AppFeature? = byId[id]

    /** The features of [style], in the registry's order. */
    fun of(style: WorkStyle?): List<AppFeature> = all.filter { it.fits(style) }
}
