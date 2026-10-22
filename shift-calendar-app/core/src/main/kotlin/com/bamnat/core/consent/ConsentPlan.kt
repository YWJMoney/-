package com.bamnat.core.consent

/**
 * [ONBOARD-01] What the app may ask for on a phone, each one optional: the app works without any
 * of them, it only does more with them. The keys are stored, so they never change.
 */
enum class ConsentItem(val key: String) {
    /** Notices: 하루 브리핑, 잘 준비, 일정 알림, 근무 알람의 알림창 표시. */
    NOTIFICATIONS("notifications"),
    /** Alarms on the lock screen (Android 14+) and on time (Android 12). */
    ALARMS("alarms"),
    /** [ONBOARD-02] Sleep a watch or another device measured, read through Health Connect. */
    SLEEP("sleep"),
    /** Events in the phone's calendar (삼성 캘린더, Google 캘린더), read only. */
    CALENDAR("calendar"),
    /**
     * [STAT-01] 사용 통계 보내기: an app setting, not an Android permission, with its own yes (개인정보 보호법
     * 제22조). Asked on the first-run pages once those pages offer it (묶음 G); until then 설정 > 개인정보와 권한.
     */
    USAGE_STATS("usage_stats"),
    ;

    companion object {
        fun ofKey(key: String): ConsentItem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * [ONBOARD-01] Which consent pages to show. A new phone sees every item the phone can use, in this
 * order. A phone set up before the consent pages existed is not walked through them: it is asked
 * only about items added since, and only those not allowed yet.
 */
object ConsentPlan {
    /** What the app already asked for, where it is used, before the consent pages existed. */
    val BEFORE_PAGES: Set<ConsentItem> = setOf(ConsentItem.NOTIFICATIONS, ConsentItem.ALARMS, ConsentItem.SLEEP)

    /** The pages for a first start, in order; [available] leaves out what this phone or build cannot use. */
    fun firstStart(available: Set<ConsentItem>): List<ConsentItem> = ConsentItem.entries.filter { it in available }

    /**
     * For a phone that was already set up: [seen] is what it was told about (null when it has never
     * been through the pages, then [BEFORE_PAGES] counts as seen). Returns what to ask in a short
     * prompt, empty when nothing is new.
     */
    fun newSince(seen: Set<ConsentItem>?, available: Set<ConsentItem>, granted: Set<ConsentItem>): List<ConsentItem> {
        val told = seen ?: BEFORE_PAGES
        return ConsentItem.entries.filter { it in available && it !in told && it !in granted }
    }

    /** Stored form of [items], for a set of strings in preferences. */
    fun encode(items: Collection<ConsentItem>): Set<String> = items.mapTo(LinkedHashSet()) { it.key }

    /** Reads [encode]'s output back; a key from a newer version is skipped. */
    fun decode(keys: Collection<String>?): Set<ConsentItem>? = keys?.mapNotNullTo(LinkedHashSet()) { ConsentItem.ofKey(it) }
}

/** [ONBOARD-02] The answer to "워치나 수면을 재는 기기를 쓰세요?". */
enum class SleepDevice {
    /** Asks for Health Connect next. */
    WEARS,
    /** Sleep is recorded by hand; bedtime suggestions from the schedule still work. */
    NONE,
    ;

    /** Whether the Health Connect question follows. */
    val asksHealth: Boolean get() = this == WEARS
}
