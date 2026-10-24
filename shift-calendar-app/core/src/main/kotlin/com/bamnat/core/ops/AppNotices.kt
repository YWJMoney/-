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
    /** [SRV-09] Who it is for; null for everyone. Chosen on the phone: nothing about it goes to the server. */
    val audience: NoticeAudience? = null,
    /** [SRV-09] What its button opens; null for none. */
    val action: NoticeAction? = null,
)

/**
 * [SRV-09] Who a notice is for: phone makers (lower case, as Build.MANUFACTURER), an Android version
 * range (SDK), ways of working ([com.bamnat.core.work.WorkStyle] names) and features in use. Every set
 * part must fit; an empty part leaves it open.
 */
data class NoticeAudience(
    val makers: Set<String> = emptySet(),
    val minSdk: Int? = null,
    val maxSdk: Int? = null,
    val workTypes: Set<String> = emptySet(),
    val features: Set<String> = emptySet(),
) {
    /** This phone, as [fits] reads it: no part of it ever leaves the phone. */
    data class Phone(val maker: String, val sdk: Int, val workType: String?, val features: Set<String>)

    fun fits(p: Phone): Boolean =
        (makers.isEmpty() || p.maker.trim().lowercase() in makers) &&
            (minSdk == null || p.sdk >= minSdk) && (maxSdk == null || p.sdk <= maxSdk) &&
            (workTypes.isEmpty() || p.workType in workTypes) &&
            (features.isEmpty() || features.any { it in p.features })
}

/** [SRV-09] A notice's button: a place in the app ([kind] as the server names it) or an https page. */
data class NoticeAction(val kind: String, val label: String? = null, val url: String? = null) {
    /** The button's words: the developer's, else what the place is called. */
    val words: String get() = label ?: LABELS[kind] ?: "열기"

    companion object {
        val KINDS = listOf("ALARM_CHECK", "BACKUP", "PRO", "INQUIRY", "SHARE", "PLAZA", "WHATS_NEW", "URL")
        private val LABELS = mapOf(
            "ALARM_CHECK" to "알람 점검 열기", "BACKUP" to "백업 열기", "PRO" to "프로 보기", "INQUIRY" to "문의하기",
            "SHARE" to "근무표 공유", "PLAZA" to "광장 열기", "WHATS_NEW" to "새 기능 보기", "URL" to "자세히 보기",
        )
    }
}

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
    fun showing(list: List<AppNotice>, now: Long, versionCode: Int, dismissed: Set<String>, phone: NoticeAudience.Phone? = null): AppNotice? =
        list.asSequence()
            .filter { now >= it.from && now < it.until && it.id !in dismissed }
            .filter { (it.minApp == null || versionCode >= it.minApp) && (it.maxApp == null || versionCode <= it.maxApp) }
            // [SRV-09] A notice for some phones only: one with an audience shows only where it fits.
            .filter { it.audience == null || (phone != null && it.audience.fits(phone)) }
            .sortedWith(compareByDescending<AppNotice> { it.important }.thenByDescending { it.from })
            .firstOrNull()

    /** Closed ids that still matter: ids of notices the server no longer sends are forgotten. */
    fun keepDismissed(dismissed: Set<String>, list: List<AppNotice>): Set<String> = dismissed.filterTo(LinkedHashSet()) { id -> list.any { it.id == id } }
}
