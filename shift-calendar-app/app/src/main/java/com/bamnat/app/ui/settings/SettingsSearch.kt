package com.bamnat.app.ui.settings

/**
 * [UX-04] Plain-word search for settings and help: the words people use ("다크 모드", "알람이 안 울려요")
 * find the setting even when it is named another way. Korean endings (이·가·을·를·은·는·에서…) are
 * dropped from each word, and a word counts when it holds one of an entry's keywords, so "알람이" finds
 * "알람". No 초성 search.
 */
internal object SearchText {
    private val ENDINGS = listOf(
        "에서는", "으로는", "이에요", "에서", "으로", "이요", "이랑", "하고", "에게", "한테", "까지", "부터",
        "은", "는", "이", "가", "을", "를", "에", "도", "로", "와", "과", "랑", "요", "의", "만",
    )

    /** Lower case, letters and digits only: "근무 종류·시간" and "근무종류 시간" read the same. */
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    fun stem(word: String): String {
        for (e in ENDINGS) if (word.length >= e.length + 2 && word.endsWith(e)) return word.dropLast(e.length)
        return word
    }

    /** The words of [query], endings dropped; one-letter words only when nothing longer is there. */
    fun words(query: String): List<String> {
        val all = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.map(::stem).distinct()
        val long = all.filter { it.length >= 2 }
        return long.ifEmpty { all }
    }

    /**
     * How well [query] fits: the whole query in [title] counts most, then each word found in the title,
     * a keyword or [other] text. Half the words, at least one, must be found; 0 means no fit.
     */
    fun score(query: String, title: String, keywords: List<String>, other: String = ""): Int {
        val q = norm(query)
        if (q.isEmpty()) return 0
        val t = norm(title)
        val keys = keywords.map(::norm).filter { it.isNotEmpty() }
        val o = norm(other)
        var score = when {
            t.startsWith(q) -> 40
            q in t -> 30
            // A keyword that is the whole query beats one that only holds it ("일요일" is 한 주의 시작, not 요일 of 근무 주기).
            keys.any { it == q } -> 25
            keys.any { q in it } -> 20
            keys.any { it.length >= 2 && it in q } -> 16
            else -> 0
        }
        val words = words(query)
        var found = 0
        for (w in words) {
            val n = norm(w)
            if (n.isEmpty()) continue
            val hit = when {
                n in t -> 6
                keys.any { n in it || (it.length >= 2 && it in n) } -> 4
                n in o -> 1
                else -> 0
            }
            if (hit > 0) found++
            score += hit
        }
        val needed = maxOf(1, (words.size + 1) / 2)
        return if (score >= 20 || found >= needed) score else 0
    }

    /**
     * The items that fit, best first: at most [limit], and none far behind the best one ("알람 소리" lists
     * 알람음 and 근무마다 다른 소리, not every row that says 알람).
     */
    fun <T> best(items: List<T>, limit: Int, score: (T) -> Int): List<T> {
        val scored = items.map { it to score(it) }.filter { it.second > 0 }.sortedByDescending { it.second }
        val top = scored.firstOrNull()?.second ?: return emptyList()
        return scored.filter { it.second * BEHIND >= top }.take(limit).map { it.first }
    }

    /** A fit a quarter as good as the best is still shown; less is noise. */
    private const val BEHIND = 4
}

/** [UX-04] Where a found setting is: one row of a group (lit when the group opens), or a screen of its own. */
sealed interface SettingPlace {
    data class Row(val page: SettingsPage, val row: String) : SettingPlace
    data class Screen(val go: SettingsGo) : SettingPlace
}

/** [UX-04] One thing settings can find: its name as the row says it, where it is, and other words for it. */
data class SettingEntry(
    val title: String,
    val where: String,
    val place: SettingPlace,
    val keywords: List<String> = emptyList(),
)

/** [UX-04] Every setting and the screens settings opens, in the words of their rows. */
internal object SettingsIndex {
    private fun row(page: SettingsPage, title: String, vararg keywords: String, row: String = title) =
        SettingEntry(title, page.title, SettingPlace.Row(page, row), keywords.toList())

    private fun screen(where: String, title: String, go: SettingsGo, vararg keywords: String) =
        SettingEntry(title, where, SettingPlace.Screen(go), keywords.toList())

    private val W = SettingsPage.WORK
    private val A = SettingsPage.ALARM
    private val R = SettingsPage.REST
    private val N = SettingsPage.NOTICES
    private val P = SettingsPage.PAY
    private val C = SettingsPage.CALENDAR
    private val D = SettingsPage.DATA
    private val V = SettingsPage.PRIVACY
    private val I = SettingsPage.ABOUT

    /** [board]: the build has 회사 규칙 광장; [share]: it can make and take links. */
    fun entries(board: Boolean = true, share: Boolean = true): List<SettingEntry> = listOfNotNull(
        screen(W.title, "일하는 방식", SettingsGo.WORK_STYLE, "근무 형태", "교대", "일근", "시급", "현장", "프리랜서", "자영업", "가게"),
        screen(W.title, "근무 주기", SettingsGo.PATTERN, "순서", "패턴", "로테이션", "주주야야", "시작일", "요일", "교대 순서"),
        screen(W.title, "근무 종류와 시간", SettingsGo.SHIFT_TYPES, "주간", "야간", "근무 시간", "출근 시간", "색", "아이콘", "근무 전 알람", "휴게"),
        screen(W.title, "연장 휴무일 근무 지정", SettingsGo.EXTRA_KINDS, "잔업", "특근", "대근", "연장", "휴일 근무", "가산"),
        // [SHFT-06][SHFT-07][DAYW-06][DAYW-07]
        screen(W.title, "듀티표 사진으로 넣기", SettingsGo.DUTY_ROSTER, "듀티표", "근무표 사진", "글자 인식", "사진으로 입력", "간호사", "한 달 입력"),
        screen(W.title, "연간 건강 지표", SettingsGo.HEALTH_REPORT, "야간 비율", "연속 야간", "건강", "1년", "연간 통계"),
        screen(W.title, "근태 엑셀 가져오기", SettingsGo.ATTENDANCE_IMPORT, "엑셀", "CSV", "근태", "출퇴근 기록", "회사 시스템", "xlsx"),
        screen(W.title, "출퇴근 자동 기록", SettingsGo.COMMUTE, "위치", "지오펜스", "자동 출근", "자동 퇴근", "GPS", "회사 위치"),
        row(A, "알람 사용", "알람 끄기", "알람 켜기", "모두 끄기", "알람 전체"),
        row(A, "알람음", "알람 소리", "벨소리", "무음", row = "알람음"),
        row(A, "근무마다 다른 소리", "근무별 알람음", "야간 소리"),
        row(A, "진동", "바이브"),
        row(A, "점점 크게", "볼륨", "소리 크기"),
        row(A, "다시 울림 간격", "스누즈", "미루기", "다시 울림"),
        row(A, "자동으로 끄기", "울리는 시간", "알람 길이"),
        screen(A.title, "알람 점검", SettingsGo.DIAGNOSTICS, "알람이 안 울려요", "늦게 울려요", "원인", "진단", "점검"),
        row(R, "오늘의 흐름 보기", "잘 시간", "수면 제안", "카페인", "낮잠", "오늘 카드"),
        row(R, "목표 수면 시간", "잠", "수면 목표"),
        row(R, "퇴근 후 잠들기까지", "출퇴근", "씻는 시간", "퇴근"),
        row(R, "워치·휴대폰 수면 기록 함께 보기", "헬스 커넥트", "갤럭시 워치", "삼성 헬스", "워치", "수면 측정"),
        screen(R.title, "수면 기록 보기", SettingsGo.SLEEP, "수면 기록", "잠든 시간", "잠들었어요", "깼어요"),
        row(R, "휴식 점검", "11시간", "연속 야간", "휴식"),
        row(R, "한 주 근무 경고", "64시간", "근무시간 경고", "주 52시간"),
        row(R, "최근 3개월 한 주 평균 경고", "평균", "3개월"),
        row(N, "하루 브리핑", "커피", "낮잠 알림", "알림"),
        row(N, "퇴근길 안전", "운전", "졸음"),
        row(N, "잘 준비", "취침 알림", "잘 시간 알림"),
        row(N, "자는 중 표시", "자는 중", "알림창"),
        row(N, "나갈 돈·받을 돈", "고정비", "미수금", "돈 알림", "받을 돈"),
        row(N, "연차 소멸 안내", "연차", "휴가 알림"),
        row(N, "자는 동안 미루기", "방해 금지", "조용히"),
        screen(P.title, "급여 계산", SettingsGo.PAY, "월급", "기본급", "수당", "연차", "급여일", "시급", "명세서", "통상임금"),
        row(P, "회사 설정 보내기", "동료", "회사 설정 파일", "같은 회사"),
        row(P, "회사 설정 불러오기", "동료에게 받은 파일"),
        screen("설정", "회사 규칙 광장", SettingsGo.BOARD, "회사 규칙", "투표", "토론").takeIf { board },
        row(P, "회사 찾기", "회사 설정", "같은 회사", "회사 근무표", "회사 계산").takeIf { board },
        screen(C.title, "근무표 공유", SettingsGo.SHARE, "공유", "링크", "이미지", "보내기", "카톡", "가족", "동료에게"),
        screen(C.title, "동료 근무표 받기", SettingsGo.RECEIVE, "받은 링크", "받은 근무표", "바꿔 보기", "같이 쉬는 날", "동료 달력").takeIf { share },
        screen(C.title, "달력 목록", SettingsGo.CALENDARS, "새 달력", "달력 추가", "달력 여러 개", "알바", "부업", "순서", "달력 이름"),
        row(C, "테마", "다크 모드", "어둡게", "밝게", "야간 모드", "검은 화면"),
        row(C, "휴대폰 배경색에 맞추기", "색", "배경화면", "앱 색"),
        row(C, "한 주의 시작", "일요일", "월요일", "주 시작"),
        row(C, "날짜 칸 아래", "음력", "근무시간 표시", "날짜 밑"),
        row(C, "메모 보기", "달력 메모", "메모 표시"),
        row(C, "날짜를 누르면", "미리보기", "하루 화면", "그 주만 보기", "바로 크게"),
        row(C, "하루 화면 순서", "순서 바꾸기", "하루 화면"),
        row(D, "백업 저장", "백업", "휴대폰 바꾸기", "옮기기", "암호", "저장"),
        row(D, "파일 불러오기", "복원", "백업 불러오기", "되돌리기"),
        screen(D.title, "캘린더에서 가져오기", SettingsGo.CALENDAR_IMPORT, "삼성 캘린더", "구글 캘린더", "가져오기", "일정"),
        row(D, "캘린더 앱으로 내보내기", "ics", "구글 캘린더", "내보내기"),
        row(V, "개인정보 처리방침", "개인정보", "처리방침"),
        row(V, "알림 허용", "알림 권한", "권한", row = "알림"),
        row(V, "잠금 화면에 알람 띄우기", "전체 화면", "권한", "잠금 화면"),
        row(V, "결제 알림 읽기", "카드 알림", "은행 알림", "문자", "자동 기록", "가계부"),
        screen(I.title, "문의하기", SettingsGo.INQUIRY, "질문", "건의", "제안", "오류", "버그", "고객센터", "도움말", "메일", "신고"),
        screen(I.title, "자주 묻는 질문", SettingsGo.INQUIRY, "도움말", "사용법", "어떻게", "FAQ"),
        row(I, "앱 버전", "버전", "업데이트", row = "버전"),
    )

    /** The best fits for [query], best first. */
    fun search(query: String, entries: List<SettingEntry> = entries(), limit: Int = 12): List<SettingEntry> =
        SearchText.best(entries, limit) { SearchText.score(query, it.title, it.keywords, it.where) }
}
