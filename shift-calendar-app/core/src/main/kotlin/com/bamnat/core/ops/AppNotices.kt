package com.bamnat.core.ops

/**
 * [SRV-04] A notice from the developer app, as `GET /api/config` gives it. Times are epoch millis;
 * [minApp]/[maxApp] are versionCodes, null for an open end.
 */
data class AppNotice(
    val id: String,
    val title: String,
    val body: String,
    val important: Boolean,
    val from: Long,
    val until: Long,
    val minApp: Int? = null,
    val maxApp: Int? = null,
)

/** [SRV-04] The features the developer can pause from the developer app, by the server's names. */
enum class ServerSwitch(val key: String, val label: String) {
    PLAZA_WRITE("PLAZA_WRITE", "광장 글쓰기"),
    COMPANY_REPORT("COMPANY_REPORT", "회사 규칙 보내기"),
    INQUIRY_PHOTOS("INQUIRY_PHOTOS", "문의 사진"),
    SHARE_CREATE("SHARE_CREATE", "근무표 링크 만들기"),
    USAGE_STATS("USAGE_STATS", "사용 통계 보내기"),
    PLAZA_NEWS("PLAZA_NEWS", "광장 소식 알림"),
    ;

    companion object {
        fun of(key: String): ServerSwitch? = entries.firstOrNull { it.key == key }

        /** What the app says where a paused feature would be. */
        fun pausedText(s: ServerSwitch): String = "‘${s.label}’ 기능이 잠시 쉬고 있어요. 곧 다시 열게요."
    }
}

object AppNotices {
    /** The server's list allows 10; more is not what it sends. */
    const val MAX = 10

    /**
     * The notice to show over the calendar: running now, meant for this [versionCode], not closed by
     * the user. An important one first, then the newest.
     */
    fun showing(list: List<AppNotice>, now: Long, versionCode: Int, dismissed: Set<String>): AppNotice? =
        list.asSequence()
            .filter { now >= it.from && now < it.until && it.id !in dismissed }
            .filter { (it.minApp == null || versionCode >= it.minApp) && (it.maxApp == null || versionCode <= it.maxApp) }
            .sortedWith(compareByDescending<AppNotice> { it.important }.thenByDescending { it.from })
            .firstOrNull()

    /** Closed ids that still matter: ids of notices the server no longer sends are forgotten. */
    fun keepDismissed(dismissed: Set<String>, list: List<AppNotice>): Set<String> = dismissed.filterTo(LinkedHashSet()) { id -> list.any { it.id == id } }
}
