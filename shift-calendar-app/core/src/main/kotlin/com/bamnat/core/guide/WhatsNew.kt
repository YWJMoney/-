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
