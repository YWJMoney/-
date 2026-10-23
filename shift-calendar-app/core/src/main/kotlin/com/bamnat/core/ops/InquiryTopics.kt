package com.bamnat.core.ops

/**
 * [INQ-05] I11 What a question is about, by the same table the company server sorts 모두의 문의 with
 * (`INQUIRY_TOPICS` in server/company-worker; a server test keeps the two the same). The form uses it
 * while the user writes, to put the help answers on that topic first.
 */
object InquiryTopics {
    data class Topic(val key: String, val label: String, val pattern: String) {
        val regex = Regex(pattern)
    }

    val TOPICS = listOf(
        Topic("alarm", "알람", """알람|울리|울려|울림|소리|진동|깨워|스누즈|다시\s*울"""),
        Topic("pay", "급여 계산", """급여|월급|시급|일당|연봉|수당|가산|명세서|통상임금|주휴|연차|퇴직|세금|4대\s*보험|계산"""),
        Topic("schedule", "근무표·달력", """근무표|달력|캘린더|교대|패턴|근무\s*유형|대근|휴무|비번|야간|주간|오프|일정|메모"""),
        Topic("share", "공유", """공유|링크|상대방|가족|동기화|받은\s*사람|보낸\s*사람"""),
        Topic("company", "회사 설정", """회사|부서|규칙|투표|토론|채택"""),
        Topic("backup", "백업·기기 이동", """백업|복원|새\s*(폰|휴대폰|기기)|기기\s*변경|옮기|가져오기|내보내기"""),
        Topic("billing", "결제", """결제|구독|프리미엄|환불|구매|광고|유료|무료"""),
        Topic("app", "앱 동작", """멈춰|멈춤|멈추|꺼져|꺼짐|튕|강제\s*종료|오류|에러|버그|안\s*열|안\s*켜|느려|느림|로딩|하얀\s*화면|검은\s*화면|위젯|워치|잠금\s*화면|배터리|업데이트"""),
    )

    const val OTHER = "etc"

    /** The topic with the most matching words, the first on a tie, or [OTHER]. */
    fun of(text: String): String {
        var best = OTHER
        var most = 0
        for (t in TOPICS) {
            val n = t.regex.findAll(text).count()
            if (n > most) {
                best = t.key
                most = n
            }
        }
        return best
    }

    fun label(key: String): String = TOPICS.firstOrNull { it.key == key }?.label ?: "기타"
}
