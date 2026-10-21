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
        Release(29, "1.18.0", listOf(
            Line("3조 2교대·4조 2교대처럼 조가 있으면 ‘모든 조 보기’에서 조마다 근무와 그날 쉬는 조를 봐요", "shift.teams"),
            Line("빠른 입력으로 쉬는 날에 근무를 넣으면 대근으로 넣을지 물어봐요"),
            Line("주 근무시간은 달력이 일요일부터 보여도 월요일부터 일요일까지로 세요"),
            Line("달력 아래 하루 카드는 처음엔 꺼져 있어요. ‘화면 꾸미기’에서 카드 순서와 켜고 끄기를 바꿀 수 있어요", "calendar.customize"),
            Line("문의를 보내면 접수 → 확인 → 개발 → 반영 버전까지 진행 상황이 보여요", "help.inquiry"),
        )),
    )

    /** The releases after [seenCode] up to [currentCode], newest first; none for a fresh install (seenCode 0). */
    fun since(seenCode: Int, currentCode: Int): List<Release> =
        if (seenCode <= 0) emptyList() else releases.filter { it.versionCode in (seenCode + 1)..currentCode }.sortedByDescending { it.versionCode }
}
