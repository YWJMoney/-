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
        // [SCHED-03] 1.23 I: the most common write-in about patterns, a day off by one after a swap or a sick day.
        HelpTopic(
            "realign", "근무 주기", "근무 순서가 하루 밀렸어요",
            "순서가 어긋난 날을 길게 누르고 ‘이 날부터 순서 다시 맞추기’에서 그날이 몇 번째 날인지 골라요. 그날부터 다시 돌고, " +
                "그 전 날은 그대로 남아요. 직접 바꿔 둔 날은 목록으로 보여 패턴대로 돌릴지 골라요.",
            listOf("밀렸", "밀림", "어긋", "하루 밀", "순서 틀", "다시 맞추", "꼬였"),
            listOf("달력", "내 근무"),
            "달력 열기" to HelpAction.Tab(NoticeLinks.Target.TODAY),
        ),
        // [CAL-43] 1.23 I
        HelpTopic(
            "quicktext", "달력", "메모처럼 적어서 근무를 넣을 수 있나요",
            "날짜를 누르고 빠른 메모에 ‘내일 야간 대근 7시’, ‘다음주 월화 연차’, ‘11/3 휴무’처럼 적어요. 넣을 수 있는 말이면 " +
                "‘이렇게 넣을까요?’가 떠서 ‘넣기’를 누를 때만 달력에 들어가요. 그냥 보내면 메모로 남아요.",
            listOf("메모로 넣기", "글로 입력", "말로 입력", "적어서", "한 줄", "빠른 메모"),
            listOf("달력"),
            "달력 열기" to HelpAction.Tab(NoticeLinks.Target.TODAY),
        ),
        HelpTopic(
            "oneday", "달력", "하루만 근무를 바꾸고 싶어요",
            "날짜를 누르고 하루 화면의 ‘근무’에서 다른 근무를 고르면 그날만 바뀌어요. 날짜를 길게 누르면 빠른 설정이 열리고, " +
                "여러 날은 달력 오른쪽 아래 ‘빠른 입력’으로 한 번에 바꿔요.",
            listOf("하루", "바꾸기", "변경", "휴가", "연차", "근무 변경", "날짜", "빠른 입력"),
            listOf("달력"),
        ),
        // [LEAVE-01..06] 1.22 묶음 D.
        HelpTopic(
            "leave", "연차", "남은 연차와 없어지는 날을 보고 싶어요",
            "급여 화면 연차 카드의 ‘연차 화면’에서 봐요. 입사일을 넣으면 제60조대로 세고 달력의 연차·반차를 빼요. " +
                "회사가 준 날·이월은 ‘직접 넣기’로 넣어요. 없어지기 전 알림은 켜 두어도 손해가 없고, 필요 없으면 연차 화면이나 설정 › 알림과 잠에서 꺼요.",
            listOf("연차", "남은 연차", "연차 계산", "소멸", "이월", "입사일", "연차 알림", "촉진", "보상휴가", "연차수당"),
            listOf("급여", "연차"),
            "연차 화면 열기" to HelpAction.Tab(NoticeLinks.Target.LEAVE),
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
                "워치 기록도 설정 › 알림과 잠에서 켜면 함께 봐요.",
            listOf("잠", "수면", "잠들었어요", "깼어요", "워치", "낮잠"),
            listOf("수면", "알림과 잠"),
            "수면 기록 열기" to HelpAction.Go(SettingsGo.SLEEP),
        ),
        // [REST-01] Where the roster check's limits are changed, and how to turn it off.
        HelpTopic(
            "restRules", "근무표 점검", "휴식 점검이 우리 병원 기준과 달라요 · 끄고 싶어요",
            "달력의 휴식 점검 카드에서 ‘기준 바꾸기’를 누르면 휴식 시간, 나이트 뒤 쉬는 날, 연속 나이트, 연속 근무, 한 달 나이트 수를 바꿔요. " +
                "0으로 두면 그 항목은 보지 않아요. 끄려면 같은 화면 맨 위 ‘근무표 점검’을 끄거나 설정 › 알림과 잠 › 휴식 점검을 꺼요. " +
                "알려만 주고 저장은 막지 않아요.",
            listOf("휴식 점검", "근무표 점검", "기준", "나이트", "N-O-D", "나오데", "연속 나이트", "끄기", "병원 기준"),
            listOf("알림과 잠", "달력"),
            "근무표 점검 기준 열기" to HelpAction.Page(SettingsPage.NOTICES, "근무표 점검 기준"),
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
        // [DAILY-01][BACK-01][DAILY-16][INV-34] 1.22: on by default, and how to turn each off (S10).
        HelpTopic(
            "defaultsOff", "끄는 법", "1.22에 새로 켜진 기능을 끄고 싶어요",
            "끄는 법: 위젯 메모 줄은 설정 › 달력 › ‘위젯에 메모 보이기’, 백업 속 사진은 설정 › 데이터 › ‘사진도 백업’, " +
                "급여 화면의 광장 시세 카드는 설정 › 급여와 연차 › ‘광장 시세와 내 단가 견주기’, 투자 월말 요약은 투자 › ⋮ › 목표와 수익률 › ‘월말 요약 알림’에서 꺼요. " +
                "돈·메모 화면의 카드는 각 화면의 ‘화면 꾸미기’에서 숨겨요.",
            listOf("끄기", "끄는 법", "위젯 메모", "사진 백업", "시세", "월말 요약", "화면 꾸미기", "숨기기"),
            listOf("달력", "데이터", "급여", "투자", "돈", "메모"),
            "달력 설정 열기" to HelpAction.Page(SettingsPage.CALENDAR, "위젯에 메모 보이기"),
        ),
        HelpTopic(
            "receiptScan", "영수증", "영수증 사진을 찍으면 어디로 가나요?",
            "영수증 글자는 휴대폰 안에서 읽고 어디로도 보내지 않아요. 금액·날짜·가게를 채운 뒤 저장 전에 확인해 주세요. " +
                "합계를 못 찾으면 가장 큰 금액을 넣고 ‘확인해 주세요’라고 알려 드려요. 사진은 그 기록의 영수증으로 남고, 백업 파일에도 들어가요(끄는 법: 설정 › 데이터 › ‘사진도 백업’).",
            listOf("영수증", "사진", "OCR", "읽기", "금액"),
            listOf("돈"),
            "데이터 설정 열기" to HelpAction.Page(SettingsPage.DATA, "사진도 백업"),
        ),
        // [SIMPLE-02] Where the features that start on are turned off, and why leaving them on is fine.
        HelpTopic(
            "turnOff", "기능", "켜져 있는 알림이나 기능을 끄고 싶어요",
            "알림은 설정 › 알림과 잠에서 하나씩 꺼요. 처음에 켜 둔 하루 브리핑·잘 준비·잠금 화면 오늘 근무는 소리가 없고, " +
                "퇴근길 안전은 야간이 끝날 때 한 번뿐이에요. 모두 이 휴대폰 안에서만 돌아서 켜 둬도 괜찮아요. " +
                "달력·급여 화면의 카드와 안내는 설정 › 기능 켜고 끄기에서 끄고, ‘추천 기능 한 번에 켜기’에서 알림 넷을 한 화면으로 바꿀 수도 있어요.",
            listOf("끄기", "끄고", "알림 끄기", "너무 많", "귀찮", "브리핑", "잠금 화면", "추천", "기본", "켜져"),
            listOf("기능 켜고 끄기", "알림과 잠", "추천 기능"),
            "알림과 잠 열기" to HelpAction.Page(SettingsPage.NOTICES, "하루 브리핑"),
        ),
        // [EASY-01][EASY-02] 1.23 N: the example calendar, and leaving it.
        HelpTopic(
            "browse", "둘러보기", "예시 달력을 그만 보고 내 달력으로 시작하고 싶어요",
            "‘우선 둘러볼게요’로 연 예시 달력은 화면 위 띠의 ‘내 정보로 시작’을 누르면 끝나요. 둘러보다 근무를 적으려 하면 ‘예시는 두고 새 달력에서 시작’도 고를 수 있어요. " +
                "둘러보는 동안 적은 건 이 휴대폰 밖으로 나가지 않고, 알람·백업·공유에도 들어가지 않아요.",
            listOf("둘러보기", "둘러볼게요", "예시 달력", "예시", "체험", "내 정보로 시작", "처음부터", "시작하기"),
            listOf("달력", "내 정보"),
            "달력 열기" to HelpAction.Tab(NoticeLinks.Target.TODAY),
        ),
        // [EASY-05][EASY-08][EASY-09] 1.23 N: the question cards and where every answer is kept.
        HelpTopic(
            "profile", "내 정보", "질문 카드가 떠요 · 답한 정보를 바꾸고 싶어요",
            "질문 카드는 기능에 필요한 정보만 하루 두 개까지 물어요. ‘나중에’는 7일 뒤, ‘모르겠어요’는 법대로 계산해요. " +
                "답한 것은 설정 맨 위 ‘내 정보’에 모여 있고, 누르면 바로 고쳐져 쓰는 기능에 함께 적용돼요. ‘모두 처음부터’는 값을 지우지 않고 다시 물어요.",
            listOf("내 정보", "질문 카드", "질문", "물어봐", "프로필", "입사일", "월급날", "다시 묻기", "모두 처음부터", "정보 바꾸기"),
            listOf("내 정보", "달력"),
            "내 정보 열기" to HelpAction.Go(SettingsGo.PROFILE),
        ),
        // [OBS-01][OBS-02] 1.23 N
        HelpTopic(
            "journal", "밤낮이 한 일", "앱이 알아서 무엇을 했는지 보고 싶어요",
            "설정 맨 위 ‘밤낮이 한 일’에서 알람을 다시 잡은 시각, 자동 백업, 계산 자료 갱신, 결제 알림 기록처럼 밤낮이 알아서 한 일을 날짜별로 봐요. " +
                "금액이나 글 내용은 적지 않고, 이 휴대폰에만 남아요. 달력 위엔 평소보다 많이 한 날만 한 줄로 보여요.",
            listOf("밤낮이 한 일", "한 일", "한 일 기록", "알아서", "자동으로", "백업 됐", "알람 다시", "갱신", "무엇을 했"),
            listOf("밤낮이 한 일", "달력"),
            "밤낮이 한 일 열기" to HelpAction.Go(SettingsGo.JOURNAL),
        ),
        // [COMM-08][COMM-09] 1.22.0: what the plaza asks of a post, and how to stop seeing someone.
        HelpTopic(
            "plaza", "광장", "광장에서 불쾌한 글을 봤어요. 그 사람 글을 안 보고 싶어요",
            "글 옆 ⋮에서 ‘신고’를 누르면 운영자가 확인하고, ‘이 사람 글 안 보기’를 누르면 그 사람 글이 이 휴대폰에서 바로 사라져요. " +
                "가린 사람은 광장 › 내 활동 › ‘가린 사람’에서 다시 볼 수 있어요. 글을 처음 쓸 때 보는 운영 규칙은 언제든 웹에서도 볼 수 있어요.",
            listOf("광장", "차단", "가리기", "안 보기", "신고", "욕설", "운영 규칙"),
            listOf("광장", "회사 규칙 광장"),
            "밤낮 소식 알림 설정" to HelpAction.Page(SettingsPage.NOTICES, "밤낮 소식"),
        ),
        // [STAT-01] 1.22.0: what 사용 통계 sends, and that it stays off without a yes.
        HelpTopic(
            "usageStats", "사용 통계", "사용 통계 보내기는 무엇을 보내나요?",
            "켰을 때만 어떤 기능을 하루에 몇 번 썼는지와 앱 버전을 하루 한 번 보내요. 근무·금액·메모·글·휴대폰 정보는 보내지 않아요. " +
                "설정 › 개인정보·앱 정보 › ‘사용 통계 보내기’에서 보낼 내용을 미리 보고 언제든 끌 수 있고, 꺼도 앱은 똑같이 돼요.",
            listOf("통계", "사용 통계", "보내는 것", "익명", "수집", "끄기"),
            listOf("개인정보"),
            "사용 통계 설정 열기" to HelpAction.Page(SettingsPage.PRIVACY, "사용 통계 보내기"),
        ),
        HelpTopic(
            "privacy", "개인정보", "내 기록은 어디에 저장되나요?",
            "근무, 메모, 돈 기록, 급여는 이 휴대폰에만 저장돼요. 링크 공유, 회사 규칙 광장, 문의처럼 직접 보내는 것만 서버로 가요. " +
                "자세한 내용은 개인정보 처리방침에 있어요.",
            listOf("개인정보", "저장", "서버", "안전", "보안", "어디에"),
            listOf("개인정보"),
            "개인정보 처리방침" to HelpAction.Page(SettingsPage.PRIVACY, "개인정보 처리방침"),
        ),
        // [INPUT-01..04] 1.24 Q: values are tapped, not typed; the company's way is put together; copied words in one tap.
        HelpTopic(
            "directInput", "직접 입력", "숫자나 회사 규칙을 글자로 쳐야 하나요?",
            "아니요. 공수·시각·매달 며칠·시간·비율은 칩을 누르거나 −·+로 고르고, 호봉표는 호봉마다 금액을 넣어요. 회사 계산 방식은 " +
                "‘무엇을·어떻게·얼마’를 골라 담으면 한 문장으로 보여 드리고, 담기지 않는 것만 말로 적어요. 명세서·결제 문자·링크·코드를 복사해 두면 " +
                "그 화면에 ‘복사한 글이 있어요’ 한 줄이 떠요. 누를 때만 읽고, 복사한 글은 저장하지 않아요.",
            listOf("직접 입력", "글자", "타자", "붙여 넣기", "복사한 글", "클립보드", "호봉표", "공수", "규칙 조립", "골라 담기", "회사 방식"),
            listOf("급여"),
            "급여 계산 열기" to HelpAction.Go(SettingsGo.PAY),
        ),
        // ---- 1.24 묶음 S [INQ-06][COMM-12] ----
        HelpTopic(
            "inquiryPublic", "문의", "내 문의를 다른 사람도 보나요?",
            "보낼 때 ‘모두의 문의에 올리기’를 켜 두면 글과 개발자 답이 모두의 문의에 올라가요. 누가 보냈는지는 보이지 않아요. " +
                "끄면 개발자만 봐요. 회사 방식 알려 주기는 늘 개발자만 봐요.",
            listOf("공개", "비공개", "다른 사람", "모두의 문의", "나만", "개발자만"),
            listOf("도움말과 문의"),
        ),
        HelpTopic(
            "plazaEntry", "광장", "같은 회사 분들 규칙은 어디서 보나요?",
            "첫 설정에서 회사 이름을 넣었다면 급여 화면 아래 ‘분들이 올린 규칙 보기’를 누르세요. 회사 이름이 검색된 채 회사 규칙 광장이 열려요. " +
                "같은 부서 설정이 둘이면 다른 점을 한 줄로 보여 줘요.",
            listOf("광장", "같은 회사", "회사 규칙", "동료 설정", "제보"),
            listOf("급여", "회사 규칙 광장"),
        ),
        // ---- 1.24 묶음 P: [ROST-01][ROST-05][ROST-09][ROST-10] ----
        HelpTopic(
            "roster", "한 달 근무", "한 달 근무표를 한 번에 넣고 싶어요",
            "설정 › 내 근무 › ‘한 달 근무 넣기’에서 근무표를 보며 키를 누르면 하루씩 들어가요. D D E E N N O O를 넣고 ‘이 순서 반복’을 누르면 " +
                "달 끝까지 채워요. 듀티표 사진·엑셀·CSV나 카톡 ‘공유’도 같은 달력에 채워지니 노란 칸만 보면 돼요. 반차는 0.5일, 병가처럼 회사마다 다른 휴가는 한 번만 물어요.",
            listOf("듀티표", "한 달 근무", "근무표 넣기", "키패드", "직접 입력", "이 순서 반복", "반차", "병가", "공유", "붙여 넣기", "사진으로"),
            listOf("한 달 근무 넣기", "달력"),
            "한 달 근무 넣기 열기" to HelpAction.Go(SettingsGo.DUTY_ROSTER),
        ),
    )

    /** [INQ-05] I11: the answers each of the server's topics ([com.bamnat.core.ops.InquiryTopics]) starts with. */
    private val BY_TOPIC = mapOf(
        "alarm" to listOf("alarm", "sound", "wake"),
        "pay" to listOf("pay", "takeHome", "extra", "leave", "publicPay"),
        "schedule" to listOf("pattern", "oneday", "calendars", "restRules", "import"),
        "share" to listOf("share", "receive"),
        "company" to listOf("plaza"),
        "backup" to listOf("backup", "phoneCalendar"),
        "app" to listOf("widget", "alarm", "turnOff", "profile", "journal"),
    )

    /**
     * [INQ-05] I11: while a question is written, the answers on its topic (sorted the way the server will
     * sort the question) come first, then the best of the rest.
     */
    fun forQuestion(text: String, topics: List<HelpTopic> = topics(), limit: Int = 2): List<HelpTopic> {
        val key = com.bamnat.core.ops.InquiryTopics.of(text)
        val ids = BY_TOPIC[key].orEmpty()
        val first = search(text, topics.filter { it.id in ids }, limit).ifEmpty { topics.filter { it.id in ids }.take(1) }
        return (first + search(text, topics, limit)).distinctBy { it.id }.take(limit)
    }

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
