package com.bamnat.core.consent

/**
 * [ONBOARD-03] A feature the app turns on for a new phone because it helps and does no harm: it runs on
 * the phone only, and is quiet or rare. [okay] is why it is fine to leave on, said beside the switch;
 * [offAt] is where it is turned off. The keys are stored, so they never change. Anything that sends data
 * to a server is never here: that is asked for on its own and never ticked beforehand (D42).
 */
enum class RecommendedFeature(val key: String, val title: String, val what: String, val okay: String, val offAt: String) {
    BRIEFING(
        "briefing", "하루 브리핑", "근무 전에 커피·낮잠·잘 시간을 한 번 알려 줘요",
        "소리 없이 하루 한 번까지예요", "설정 › 알림과 잠",
    ),
    BEDTIME(
        "bedtime", "잘 준비", "일찍 출근하는 전날 밤, 잘 시간 30분 전에 알려 줘요",
        "소리 없이 그런 날에만 와요", "설정 › 알림과 잠",
    ),
    COMMUTE(
        "commute", "퇴근길 안전", "야간이 끝날 때 한 번 소리로 알려 줘요",
        "야간 끝에 한 번뿐이고, 늦어지면 보내지 않아요", "설정 › 알림과 잠",
    ),
    TODAY_SHIFT(
        "todayShift", "잠금 화면 오늘 근무", "오늘과 내일 근무를 잠금 화면과 알림창에 보여 줘요",
        "소리도 진동도 없어요", "설정 › 알림과 잠",
    ),
    ;

    companion object {
        fun ofKey(key: String): RecommendedFeature? = entries.firstOrNull { it.key == key }
    }
}

/**
 * [ONBOARD-03] The one screen of the first start: what is turned on, and what the phone is asked for, in
 * turn, once the user taps to start. [ONBOARD-04] The same screen for a phone set up before 1.22, with
 * its own values, offered once.
 */
object RecommendedPlan {
    /**
     * Features already on for everyone, said in one line so the user knows they are there too. [SET-03] With
     * 1.22's quiet ones that only show on a screen (결정 G4): the widget's memo line and the pay rate check.
     */
    const val ALREADY_ON = "오늘의 흐름, 휴식 점검, 돈·연차 알림, 위젯 메모 줄, 광장 시세 견주기"

    /** Said above the switches: why leaving them on is fine. */
    const val REASSURE = "켜 둬도 괜찮아요. 모두 이 휴대폰 안에서만 돌고, 소리 나는 알림은 근무 알람과 퇴근길 한 번뿐이에요. 언제든 끌 수 있어요."

    /**
     * Where every one of them is turned off again, said under the switches. [SIMPLE-04] The same one place
     * as each [RecommendedFeature.offAt]: the switches themselves, not a list that leads to them.
     */
    const val TURN_OFF = "설정 › 알림과 잠"

    /** What a new phone starts with: every recommended feature on. */
    val NEW_INSTALL: Set<RecommendedFeature> = RecommendedFeature.entries.toSet()

    /**
     * The phone's questions in turn after "추천대로 켜고 시작하기": what this phone can use ([available]),
     * not allowed yet ([granted]), and sleep only when the user said a watch measures it ([watch]).
     * Notices come first: without them no alarm or notice shows at all.
     */
    fun asks(available: Set<ConsentItem>, granted: Set<ConsentItem>, watch: Boolean): List<ConsentItem> =
        shown(available, watch).filter { it !in granted }

    /** The lines under 허용할 것, allowed or not, in the order they are asked. */
    fun shown(available: Set<ConsentItem>, watch: Boolean): List<ConsentItem> =
        ORDER.filter { it in available && (it != ConsentItem.SLEEP || watch) }

    private val ORDER = listOf(ConsentItem.NOTIFICATIONS, ConsentItem.ALARMS, ConsentItem.CALENDAR, ConsentItem.SLEEP)

    /** [ONBOARD-04] Which recommended features a phone set up before has off; empty means nothing to offer. */
    fun offNow(on: Set<RecommendedFeature>): List<RecommendedFeature> = RecommendedFeature.entries.filter { it !in on }

    /**
     * [ONBOARD-04] Whether to offer the screen once after the update: the phone was set up before (not a
     * new install, which has the first-start screen), it was not offered yet, and something is off.
     */
    fun offerOnce(freshInstall: Boolean, offered: Boolean, on: Set<RecommendedFeature>): Boolean =
        !freshInstall && !offered && offNow(on).isNotEmpty()
}
