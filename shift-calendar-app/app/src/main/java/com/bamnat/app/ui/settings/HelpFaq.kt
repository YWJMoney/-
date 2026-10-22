package com.bamnat.app.ui.settings

import com.bamnat.app.NoticeLinks
import com.bamnat.app.company.InquiryKind
import com.bamnat.app.data.PersonalCalendar

/** [UX-05] What a help answer's button opens: a screen, a group of settings at a row, or a tab. */
sealed interface HelpAction {
    data class Go(val go: SettingsGo) : HelpAction
    data class Page(val page: SettingsPage, val row: String? = null) : HelpAction
    data class Tab(val target: NoticeLinks.Target) : HelpAction

    fun run() = when (this) {
        is Go -> SettingsLinks.go(go)
        is Page -> SettingsLinks.open(page, row)
        is Tab -> NoticeLinks.open(target)
    }
}

/**
 * [UX-05] One answer people would otherwise write in for. [tag] names it in a question that follows
 * (the "[알람]" a question starts with); [screens] are the screens it is about, so "이 화면에 대해
 * 문의" puts it first; [ask] is the kind a question about it is, and whether the alarm check helps.
 */
data class HelpTopic(
    val id: String,
    val tag: String,
    val question: String,
    val answer: String,
    val keywords: List<String>,
    val screens: List<String>,
    val action: Pair<String, HelpAction>? = null,
    val ask: InquiryKind = InquiryKind.QUESTION,
    val withCheck: Boolean = false,
)

/** [UX-05] The answers 도움말 shows before a question is written, and the search over them. */
internal object HelpFaq {
    /** [share]: this build makes and takes links, so the answer about receiving one is offered. */
    fun topics(share: Boolean = true): List<HelpTopic> = listOfNotNull(
        HelpTopic(
            "alarm", "알람", "알람이 안 울리거나 늦게 울려요",
            "‘알람 점검’을 열면 다음 알람 시각, 꺼진 권한, 배터리 최적화를 하나씩 확인하고 그 자리에서 고칠 수 있어요. " +
                "그래도 안 되면 점검 기록을 함께 보내 주세요. 원인을 찾기 쉬워져요.",
            listOf("알람", "안 울려", "안울려", "늦게", "소리 안", "잠금 화면", "꺼졌", "울리지"),
            listOf("알람", "알람 점검"),
            "알람 점검 열기" to HelpAction.Go(SettingsGo.DIAGNOSTICS), ask = InquiryKind.BUG, withCheck = true,
        ),
        HelpTopic(
            "sound", "알람", "알람 소리나 진동을 바꾸고 싶어요",
            "설정 › 알람에서 알람음, 진동, 점점 크게, 다시 울림 간격을 정해요. 근무마다 다른 소리는 ‘근무 종류와 시간’에서 정해요.",
            listOf("알람음", "소리", "벨소리", "진동", "스누즈", "다시 울림", "볼륨"),
            listOf("알람"),
            "알람 설정 열기" to HelpAction.Page(SettingsPage.ALARM, "알람음"),
        ),
        // [PROV-06][PROV-07]
        HelpTopic(
            "wake", "알람", "알람을 끄고 다시 잠들어요",
            "설정 › 알람 › ‘끄기 미션’을 켜면 계산 문제를 풀거나 휴대폰을 흔들어야 꺼져요. ‘일어났는지 확인’을 켜면 끈 뒤 몇 분 있다 묻고, " +
                "답이 없으면 한 번 더 울려요. 울린 지 5분이 지나면 미션 없이도 끌 수 있어요(프로).",
            listOf("다시 잠", "또 잠", "못 일어나", "미션", "흔들기", "계산 문제", "기상"),
            listOf("알람"),
            "끄기 미션 열기" to HelpAction.Page(SettingsPage.ALARM, MISSION_ROW),
        ),
        HelpTopic(
            "share", "근무표 공유", "근무표를 동료·가족에게 보내고 싶어요",
            "달력 맨 위, 달력 이름 옆 ‘공유’를 눌러요. 이번 달을 이미지로 보내거나 링크를 만들어 보내요. " +
                "링크는 받는 사람에게 밤낮 앱이 없어도 브라우저에서 열려요.",
            listOf("공유", "보내기", "링크", "이미지", "카톡", "카카오톡", "가족", "동료"),
            listOf("달력", "공유"),
            "근무표 공유 열기" to HelpAction.Go(SettingsGo.SHARE),
        ),
        HelpTopic(
            "receive", "근무표 공유", "동료 근무표를 내 달력과 같이 보고 싶어요",
            "동료에게 받은 링크를 ‘동료 근무표 받기’에 넣어요. 달력 이름을 누르면 내 달력과 받은 근무표를 바꿔 가며 보고, " +
                "같이 쉬는 날도 보여요. 링크가 아직 없으면 거기서 링크를 부탁하는 메시지를 보낼 수 있어요.",
            listOf("받기", "받은 링크", "바꿔 보기", "같이 쉬는", "동료 근무표", "남편", "아내", "친구"),
            listOf("달력", "공유"),
            "동료 근무표 받기" to HelpAction.Go(SettingsGo.RECEIVE),
        ).takeIf { share },
        HelpTopic(
            "pattern", "근무 주기", "교대 순서(근무 주기)를 바꾸고 싶어요",
            "설정 › 내 근무 › ‘근무 주기’에서 돌아가는 순서와 시작일을 정해요. 달력 위 메뉴의 ‘근무 주기 설정’으로도 열 수 있어요.",
            listOf("주기", "순서", "패턴", "교대", "로테이션", "시작일", "조 바뀜"),
            listOf("달력", "내 근무"),
            "근무 주기 열기" to HelpAction.Go(SettingsGo.PATTERN),
        ),
        HelpTopic(
            "oneday", "달력", "하루만 근무를 바꾸고 싶어요",
            "날짜를 누르고 하루 화면의 ‘근무’에서 다른 근무를 고르면 그날만 바뀌어요. 날짜를 길게 누르면 빠른 설정이 열리고, " +
                "여러 날은 달력 오른쪽 아래 ‘빠른 입력’으로 한 번에 바꿔요.",
            listOf("하루", "바꾸기", "변경", "휴가", "연차", "근무 변경", "날짜", "빠른 입력"),
            listOf("달력"),
        ),
        HelpTopic(
            "extra", "추가 근무", "잔업·특근·대근은 어떻게 적어요?",
            "날짜를 누르고 하루 화면의 ‘추가 근무’에서 종류와 시간을 적어요. 이름과 가산 방식은 설정 › 내 근무 › " +
                "‘연장 휴무일 근무 지정’에서 회사에 맞게 바꿔요.",
            listOf("잔업", "특근", "대근", "연장", "추가 근무", "휴일 근무", "가산"),
            listOf("달력", "내 근무", "급여"),
            "연장·휴무일 근무 지정" to HelpAction.Go(SettingsGo.EXTRA_KINDS),
        ),
        HelpTopic(
            "pay", "급여", "급여가 명세서와 달라요",
            "급여 계산 화면의 ‘명세서로 맞춰 보기’에 명세서 금액을 넣으면 가장 가까운 설정을 찾아 보여 줘요. 바꾸기 전에 먼저 보여 주고, " +
                "되돌릴 수도 있어요. 그래도 다르면 같은 화면의 ‘계산이 이상해요 (오류 제보)’로 알려 주세요. 금액은 빼고 규칙과 시간만 가요.",
            listOf("급여", "월급", "명세서", "계산", "수당", "다르", "틀려", "안 맞"),
            listOf("급여"),
            "급여 계산 열기" to HelpAction.Go(SettingsGo.PAY), ask = InquiryKind.BUG,
        ),
        // [PAYR-04][REMOTE-02] Where 실수령's tax and the year's rates come from, and how they stay current.
        HelpTopic(
            "takeHome", "실수령", "예상 실수령의 세금은 어떻게 계산하나요?",
            "4대보험은 그 달의 해 요율로, 근로소득세는 국세청 간이세액표 그대로예요. ‘○월 한눈에’ 카드에서 가족 수(본인·배우자 포함)와 " +
                "8~20세 자녀 수를 고르세요. 식대 등 비과세가 있으면 명세서와 조금 달라요. 요율·세액표가 바뀌면 앱이 하루 한 번 받아 바꿔요.",
            listOf("실수령", "세금", "소득세", "간이세액표", "부양가족", "자녀", "4대보험", "요율", "세후"),
            listOf("통계", "급여"),
        ),
        HelpTopic(
            "backup", "백업", "휴대폰을 바꿔도 기록을 옮길 수 있나요?",
            "기록은 이 휴대폰에만 있어요. 설정 › 데이터 › ‘백업 저장’으로 파일을 만들어 새 휴대폰에 옮긴 뒤 ‘파일 불러오기’로 열어요. " +
                "근무, 메모, 돈 기록, 급여 설정이 모두 들어가고 암호를 걸 수 있어요. 내가 만든 근무표 링크도 들어가 새 휴대폰에서 같은 링크가 계속 바뀌어요. " +
                "‘매일 자동 백업’은 고른 파일에 매일 덮어써요(프로).",
            listOf("백업", "휴대폰 바꾸기", "기기 변경", "옮기기", "복원", "다시 설치", "지워졌"),
            listOf("데이터"),
            "백업 저장 열기" to HelpAction.Page(SettingsPage.DATA, "백업 저장"),
        ),
        // [FEAT-07][FEAT-15]
        HelpTopic(
            "phoneCalendar", "캘린더", "삼성·구글 캘린더에서도 근무를 보고 싶어요",
            "설정 › 데이터 › ‘휴대폰 캘린더에 근무 쓰기’를 켜면 휴대폰 캘린더 앱에 근무가 보여요. 컴퓨터나 아이폰에서는 공유 › 링크의 " +
                "‘캘린더 구독 주소’를 캘린더에 구독으로 넣어요(프로).",
            listOf("구글 캘린더에", "삼성 캘린더에", "캘린더 연동", "캘린더에 보이게", "구독", "아이폰 캘린더", "동기화"),
            listOf("휴대폰 캘린더"),
            "휴대폰 캘린더 쓰기 열기" to HelpAction.Page(SettingsPage.DATA, "휴대폰 캘린더에 근무 쓰기"),
        ),
        HelpTopic(
            "widget", "위젯", "홈 화면에 근무표를 띄우고 싶어요",
            "홈 화면 빈 곳을 길게 누르고 ‘위젯’에서 밤낮을 찾아요. ‘오늘 근무’, ‘주간 근무’, ‘월간 근무표’ 세 가지가 있어요.",
            listOf("위젯", "홈 화면", "바탕화면", "잠금 화면에"),
            listOf("달력"),
        ),
        HelpTopic(
            "look", "달력 화면", "달력 모양을 바꾸고 싶어요 (다크 모드, 주 시작, 음력)",
            "설정 › 달력 화면에서 테마(밝게·어둡게), 한 주의 시작, 날짜 칸 아래 표시(근무시간·음력), 메모 보기를 바꿔요.",
            listOf("다크", "어둡게", "테마", "일요일", "월요일", "음력", "모양", "글씨"),
            listOf("달력", "달력 화면"),
            "달력 화면 설정" to HelpAction.Page(SettingsPage.CALENDAR),
        ),
        HelpTopic(
            "calendars", "달력 여러 개", "알바·부업 근무를 따로 적고 싶어요",
            "달력 이름을 누르고 ‘새 달력 만들기’로 달력을 하나 더 만들어요. 달력마다 근무와 공유가 따로예요. " +
                "내 달력과 추가 달력 ${PersonalCalendar.FREE_ADDED}개는 무료예요.",
            listOf("알바", "부업", "투잡", "달력 추가", "새 달력", "여러 개", "따로"),
            listOf("달력"),
            "달력 목록 열기" to HelpAction.Go(SettingsGo.CALENDARS),
        ),
        HelpTopic(
            "sleep", "수면", "잠든 시간을 기록하고 싶어요",
            "‘수면 기록’ 화면이나 오늘 하루 화면에서 ‘잠들었어요’, 일어나면 ‘깼어요’를 눌러요. 근무별 평균을 보고 기록을 고칠 수 있어요. " +
                "워치 기록도 설정 › 잠과 휴식에서 켜면 함께 봐요.",
            listOf("잠", "수면", "잠들었어요", "깼어요", "워치", "낮잠"),
            listOf("수면", "잠과 휴식"),
            "수면 기록 열기" to HelpAction.Go(SettingsGo.SLEEP),
        ),
        // [REST-01] Where the roster check's limits are changed, and how to turn it off.
        HelpTopic(
            "restRules", "근무표 점검", "휴식 점검이 우리 병원 기준과 달라요 · 끄고 싶어요",
            "달력의 휴식 점검 카드에서 ‘기준 바꾸기’를 누르면 휴식 시간, 나이트 뒤 쉬는 날, 연속 나이트, 연속 근무, 한 달 나이트 수를 바꿔요. " +
                "0으로 두면 그 항목은 보지 않아요. 끄려면 같은 화면 맨 위 ‘근무표 점검’을 끄거나 설정 › 잠과 휴식 › 휴식 점검을 꺼요. " +
                "알려만 주고 저장은 막지 않아요.",
            listOf("휴식 점검", "근무표 점검", "기준", "나이트", "N-O-D", "나오데", "연속 나이트", "끄기", "병원 기준"),
            listOf("잠과 휴식", "달력"),
            "근무표 점검 기준 열기" to HelpAction.Page(SettingsPage.REST, "근무표 점검 기준"),
        ),
        // [JOB-02][JOB-03] 공무원 봉급표 and the 57-hour cap.
        HelpTopic(
            "publicPay", "공무원 급여", "공무원·경찰·소방 봉급과 시간외를 넣고 싶어요",
            "급여 설정에서 직군을 공무원·경찰·소방으로 고르면 급여 화면에 ‘공무원 봉급표’가 생겨요. 계급과 호봉, 근무 연수를 고르고 " +
                "‘봉급표로 채우기’를 누르면 봉급, 시간외·야간·휴일 수당, 정근수당·명절휴가비가 들어가요. 교대·현업이 아니면 평일 하루 1시간을 빼고 " +
                "한 달 57시간까지 세고, 달력 휴식 점검 카드에 ‘이달 시간외 41/57’처럼 보여요.",
            listOf("공무원", "경찰", "소방", "호봉", "봉급표", "시간외", "57시간", "정근수당", "명절휴가비"),
            listOf("급여"),
            "급여와 연차 설정 열기" to HelpAction.Page(SettingsPage.PAY),
        ),
        HelpTopic(
            "memo", "메모", "메모에 알람을 넣고 싶어요",
            "메모를 쓰고 ‘알람 맞추기’를 누르면 정한 날짜와 시각에 울리는 알람이나 알림 메시지로 알려 줘요. " +
                "메모에 ‘내일 3시’처럼 시각을 적어도 일정으로 넣을지 물어봐요.",
            listOf("메모", "일정", "할 일", "체크리스트", "메모 알람", "약속"),
            listOf("메모"),
            "메모 열기" to HelpAction.Tab(NoticeLinks.Target.MEMOS),
        ),
        HelpTopic(
            "import", "가져오기", "다른 캘린더 앱에 적어 둔 근무를 가져오고 싶어요",
            "설정 › 데이터 › ‘캘린더에서 가져오기’에서 삼성·구글 캘린더 일정을 읽어 근무와 메모로 옮겨요. 옮기기 전에 먼저 보여 줘요.",
            listOf("가져오기", "삼성 캘린더", "구글 캘린더", "옮기기", "일정"),
            listOf("데이터", "가져오기"),
            "캘린더에서 가져오기" to HelpAction.Go(SettingsGo.CALENDAR_IMPORT),
        ),
        // [SIMPLE-02] Where the features that start on are turned off, and why leaving them on is fine.
        HelpTopic(
            "turnOff", "기능", "켜져 있는 알림이나 기능을 끄고 싶어요",
            "설정 › 기능 켜고 끄기에서 모두 끄고 다시 켤 수 있어요. 처음에 켜 둔 하루 브리핑·잘 준비·잠금 화면 오늘 근무는 소리가 없고, " +
                "퇴근길 안전은 야간이 끝날 때 한 번뿐이에요. 모두 이 휴대폰 안에서만 돌아서 켜 둬도 괜찮아요. " +
                "‘추천 기능 한 번에 켜기’에서 한 화면으로 보고 바꿀 수도 있어요.",
            listOf("끄기", "끄고", "알림 끄기", "너무 많", "귀찮", "브리핑", "잠금 화면", "추천", "기본", "켜져"),
            listOf("기능 켜고 끄기", "알림", "추천 기능"),
            "추천 기능 열기" to HelpAction.Go(SettingsGo.RECOMMENDED),
        ),
        HelpTopic(
            "privacy", "개인정보", "내 기록은 어디에 저장되나요?",
            "근무, 메모, 돈 기록, 급여는 이 휴대폰에만 저장돼요. 링크 공유, 회사 규칙 광장, 문의처럼 직접 보내는 것만 서버로 가요. " +
                "자세한 내용은 개인정보 처리방침에 있어요.",
            listOf("개인정보", "저장", "서버", "안전", "보안", "어디에"),
            listOf("개인정보"),
            "개인정보 처리방침" to HelpAction.Page(SettingsPage.PRIVACY, "개인정보 처리방침"),
        ),
    )

    /** The answers that fit [query], best first. */
    fun search(query: String, topics: List<HelpTopic> = topics(), limit: Int = 6): List<HelpTopic> =
        SearchText.best(topics, limit) { SearchText.score(query, it.question, it.keywords + it.tag, it.answer) }

    /** The answers about the screen named [about] ("달력", "설정 > 알람"), in their usual order. */
    fun about(about: String?, topics: List<HelpTopic> = topics()): List<HelpTopic> {
        val a = SearchText.norm(about ?: return emptyList())
        if (a.isEmpty()) return emptyList()
        return topics.filter { t -> t.screens.any { s -> SearchText.norm(s).let { it.isNotEmpty() && it in a } } }
    }
}
