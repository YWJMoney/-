package com.bamnat.core.guide

/**
 * [GUIDE-08] What an update brought, shown once after the update: each line names what changed in a
 * sentence and, when it is a feature of [FeatureRegistry], opens it. A fresh install sees none of it,
 * since its first days are the checklist's.
 */
object WhatsNew {
    data class Line(val text: String, val featureId: String? = null)
    data class Release(val versionCode: Int, val versionName: String, val lines: List<Line>)

    val releases: List<Release> = listOf(
        // [SIMPLE-03] Grouped by what it gives, each line opening its feature; every bundle's own lines are in
        // 계획/1.22-*-개발계획.md.
        Release(33, "1.22.0", listOf(
            Line("처음 화면은 한 장, 설정은 7묶음으로 줄었어요. 켜 둬도 괜찮은 알림을 한 번에 켜고, 설정 › 알림과 잠에서 언제든 꺼요", "settings.recommended"),
            Line("‘연차 원장’이 생겼어요. 입사일만 넣으면 연차를 법대로 세고, 없어지기 전에 알려 주고, 보상휴가도 쌓아요", "leave.ledger"),
            Line("실수령 세금은 국세청 간이세액표대로, 공무원·경찰·소방 봉급은 계급·호봉으로 채워요. 탄력근무·포괄임금·호봉표도 넣어요", "pay.publicGrade"),
            Line("휴식 점검 기준을 병원·회사에 맞게 바꾸고, N-O-D 같은 순서와 이달 나이트 수당을 미리 봐요", "shift.restRules"),
            Line("듀티표를 사진·엑셀·CSV로 넣고, 헷갈린 칸만 골라 고쳐요. 경찰·소방 교대 순서도 생겼어요", "shift.roster"),
            Line("위젯에 그날 메모 첫 줄과 ‘오늘 모든 조’가 보이고, 돈·메모 화면도 카드 순서를 바꿔요", "widget.memo"),
            Line("영수증을 찍으면 금액·날짜·가게를 채우고, 백업에 사진도 같이 들어가요", "money.receiptScan"),
            Line("광장 소식은 하루 한 번 묶어서 오고, 보기 싫은 사람은 가려요. 사용 통계는 직접 켤 때만 보내요", "plaza.news"),
        )),
        Release(32, "1.21.0", listOf(
            Line("‘모든 조 보기’가 새로워졌어요. 내 조가 몇 번째여도 맞게 돌고, 날짜를 누르면 대근 들어올 수 있는 조를 이유와 함께 찾아요", "shift.teams"),
            Line("달력 위 ⋮ 대신 이름이 붙은 ‘도구’ 버튼과 ‘모든 조’ 버튼이 생겼어요"),
            Line("근무표를 보며 D·E·N·OFF를 누르면 다음 날로 넘어가는 ‘한 달 근무 연달아 넣기’가 생겼어요. 역순·나이트 뒤 쉬는 날도 알려 줘요", "shift.dutyTyping"),
            Line("투자 달력이 증권사·거래소 체결 알림에서 매수·매도를 찾아요. 확인하면 알림 온 앱과 코인·주식 표시까지 적어요", "invest.alerts"),
            Line("회사 광장이 커졌어요. 직군·병원·지역 광장, 글에 맞아요·달라요와 답글, 규칙 고치자는 제안, 내 월급으로 시험, 지역 임금표가 생겼어요"),
            Line("급여 설정에 공무원·경찰·소방이 생겼어요. 공무원수당 규정대로 야간·휴일·시간외 수당을 넣어 드려요", "pay.calc"),
            Line("예상 실수령이 그 달의 요율(2027년 국민연금 등)로 계산돼요"),
        )),
        Release(31, "1.20.0", listOf(
            Line("근무·급여 화면의 ‘급여 리포트’로 한 달이나 1년 급여를 계산 풀이까지 PDF·CSV로 남겨요", "pay.report"),
            Line("‘지난달·작년 비교’ 카드로 근무시간·야간·급여를 지난달, 작년 같은 달과 나란히 봐요", "stats.compare"),
            Line("근무를 휴대폰 캘린더(삼성·구글 캘린더 앱)에 적을 수 있어요", "calendar.phone"),
            Line("근무표 링크를 캘린더 앱이 구독하게 하거나, 링크를 5개까지 따로 만들고 1년까지 열어 둘 수 있어요", "share.feed"),
            Line("고른 파일(구글 드라이브 등)에 암호 건 백업을 매일 자동으로 덮어써요", "data.autoBackup"),
            Line("알람을 끌 때 계산 문제나 흔들기를 하게 하고, 끈 뒤 ‘일어났나요?’를 물을 수 있어요. 5분이 지나면 미션 없이도 꺼요", "alarm.mission"),
            Line("근무표를 A4로 인쇄할 PDF로 만들어요", "share.rosterPdf"),
            Line("모두 프로 기능이지만 정식 출시 전까지는 무료예요", "app.pro"),
        )),
        Release(30, "1.19.0", listOf(
            Line("설정에 ‘프로’가 생겼어요. 정식 출시 뒤 무료와 프로가 어떻게 나뉘는지 미리 볼 수 있어요. 지금은 모든 기능이 무료예요", "app.pro"),
            Line("근무표·알람·급여 계산·연차·메모·위젯·워치는 정식 출시 뒤에도 무료예요"),
            Line("정식 출시 전부터 써 주신 분께는 출시 뒤 프로를 일정 기간 무료로 열어 드려요. 결제 정보는 받지 않아요"),
            Line("‘광고 개인정보 선택’에서 광고가 쓰는 정보와 끄는 방법을 볼 수 있어요", "privacy.adChoices"),
        )),
        Release(29, "1.18.0", listOf(
            Line("3조 2교대·4조 2교대처럼 조가 있으면 ‘모든 조 보기’에서 조마다 근무와 그날 쉬는 조를 봐요", "shift.teams"),
            Line("열두 달을 한 화면에 보는 ‘1년 달력’이 생겼어요. 한 해의 근무 날 수와 휴가도 세요", "calendar.year"),
            Line("빠른 입력으로 쉬는 날에 근무를 넣으면 대근으로 넣을지 물어봐요"),
            Line("주 근무시간은 달력이 일요일부터 보여도 월요일부터 일요일까지로 세요"),
            Line("달력 아래 하루 카드는 처음엔 꺼져 있어요. ‘화면 꾸미기’에서 카드 순서와 켜고 끄기를 바꿀 수 있어요", "calendar.customize"),
            Line("‘앱 잠금’을 켜면 열 때 지문·얼굴·PIN을 물어요. 알람은 그대로 울려요", "privacy.lock"),
            Line("문의를 보내면 접수 → 확인 → 개발 → 반영 버전까지 진행 상황이 보여요", "help.inquiry"),
        )),
    )

    /** The releases after [seenCode] up to [currentCode], newest first; none for a fresh install (seenCode 0). */
    fun since(seenCode: Int, currentCode: Int): List<Release> =
        if (seenCode <= 0) emptyList() else releases.filter { it.versionCode in (seenCode + 1)..currentCode }.sortedByDescending { it.versionCode }
}
