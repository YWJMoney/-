package com.bamnat.core.example

import com.bamnat.core.work.WorkStyle
import java.time.LocalDate

/**
 * [EASY-02] The moments someone looking around starts to write: each one, on the example calendar, asks
 * "내 정보로 시작할까요?" first. Reading (a date tapped, a month turned, a tab, settings, 화면 꾸미기) never asks.
 * The places that write are listed in research/1.23-N-설계.md 3장.
 */
enum class WriteKind(val what: String) {
    DAYS("여러 날 고르기"),
    SHIFT("근무 바꾸기"),
    DAY_TIME("하루 시간·잔업·특근"),
    LEAVE("연차 넣기"),
    MEMO("메모 쓰기"),
    ALARM("알람 바꾸기"),
    PATTERN("근무 순서·종류 고치기"),
    PAY("급여 설정"),
    MONEY("돈 적기"),
    IMPORT("가져오기"),
    SHARE("공유 만들기"),
    SLEEP("잠 기록"),
    CALENDAR("달력 고치기"),
    ;

    /** What leaves the phone or another person sees: shown even after "계속 둘러보기", with a '예시' mark. */
    val goesOut: Boolean get() = this == SHARE
}

/** [EASY-01] The three ways on from the card. */
enum class BrowseChoice(val label: String) {
    /** The example goes, the first setup starts with the kind of work already picked. */
    START_MINE("내 정보로 시작"),
    /** The example stays as a calendar of its own (no alarms, not backed up); the first setup is for the user's calendar. */
    KEEP_EXAMPLE("예시는 두고 새 달력에서 시작"),
    /** The action goes ahead on the example, which keeps its '예시' band. */
    KEEP_BROWSING("계속 둘러보기"),
}

/**
 * [EASY-01] Looking around with an example calendar before telling the app anything. [style] is the kind of
 * work the example shows; [keepBrowsingOn] the day "계속 둘러보기" was chosen, so the card is not asked again
 * that day.
 */
data class BrowseState(
    val browsing: Boolean = false,
    val style: WorkStyle? = null,
    val since: LocalDate? = null,
    val keepBrowsingOn: LocalDate? = null,
)

object Browse {
    /** [EASY-02] Whether [kind] on the example calendar asks first, today. */
    fun asks(state: BrowseState, onExample: Boolean, kind: WriteKind, today: LocalDate): Boolean = when {
        !onExample -> false
        kind.goesOut -> true
        else -> state.keepBrowsingOn != today
    }

    /** The state after [choice]. Only "계속 둘러보기" keeps looking around. */
    fun after(state: BrowseState, choice: BrowseChoice, today: LocalDate): BrowseState = when (choice) {
        BrowseChoice.KEEP_BROWSING -> state.copy(keepBrowsingOn = today)
        BrowseChoice.START_MINE, BrowseChoice.KEEP_EXAMPLE -> BrowseState()
    }

    /** Starting to look around: the example of [style], from [today]. */
    fun start(style: WorkStyle, today: LocalDate): BrowseState = BrowseState(browsing = true, style = style, since = today)

    const val TITLE = "내 정보로 시작할까요?"

    /** What the card says under [TITLE], naming the action that brought it up ("무엇을 · 왜"). */
    fun body(kind: WriteKind): String =
        "지금은 예시 달력이라 ${kind.what}${object_(kind.what)} 해도 내 기록이 되지 않아요. 내 정보로 시작하면 예시는 지우고, 고른 일하는 방식으로 이어서 설정해요."

    /** 을 after a final consonant, 를 after a vowel. */
    internal fun object_(word: String): String {
        val c = word.lastOrNull() ?: return "을"
        if (c !in '가'..'힣') return "을"
        return if ((c - '가') % 28 == 0) "를" else "을"
    }
}
