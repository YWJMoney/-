package com.bamnat.app.ui.settings

import com.bamnat.app.NoticeLinks
import com.bamnat.app.company.InquiryKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * [UX-04] Screens a setting, a search result or a help answer can open in one tap. The ones the calendar
 * opens itself (its share sheet, a received link) go through [NoticeLinks]; the rest the navigation opens.
 */
enum class SettingsGo {
    WORK_STYLE, PATTERN, SHIFT_TYPES, EXTRA_KINDS, PAY, CALENDARS, DIAGNOSTICS, INQUIRY, BOARD,
    SHARE, RECEIVE, SLEEP, CALENDAR_IMPORT,
    /** [SHFT-06][SHFT-07][DAYW-06][DAYW-07] */
    DUTY_ROSTER, HEALTH_REPORT, ATTENDANCE_IMPORT, COMMUTE,
    /** [GUIDE-05] 기능 켜고 끄기. */
    FEATURES,
    /** [MON-04] 프로. */
    PRO,
    /** [ONBOARD-04] 추천 기능 한 번에 켜기. */
    RECOMMENDED,
    /** [EASY-08][OBS-02] 내 정보 and 밤낮이 한 일. */
    PROFILE, JOURNAL,
}

/** [UX-04] Where a link inside the app goes: one group of settings (and the row to point at), or a screen. */
sealed interface SettingsLink {
    data class Page(val page: SettingsPage, val row: String? = null) : SettingsLink
    data class Go(val go: SettingsGo) : SettingsLink
}

/**
 * [UX-04] Opens settings on the right group and row, or a screen, from anywhere in the app: a help
 * answer, a search result, a screen's own "설정" button. The navigation follows [target] and the
 * settings screen takes a [SettingsLink.Page] itself, so it opens on that group with the row lit.
 */
object SettingsLinks {
    private val _target = MutableStateFlow<SettingsLink?>(null)
    val target: StateFlow<SettingsLink?> = _target.asStateFlow()

    fun open(page: SettingsPage, row: String? = null) {
        _target.value = SettingsLink.Page(page, row)
    }

    fun go(go: SettingsGo) {
        when (go) {
            // The calendar opens these itself, on the user's own calendar.
            SettingsGo.SHARE -> NoticeLinks.open(NoticeLinks.Target.SHARE)
            SettingsGo.RECEIVE -> NoticeLinks.open(NoticeLinks.Target.RECEIVE)
            SettingsGo.SLEEP -> NoticeLinks.open(NoticeLinks.Target.SLEEP)
            SettingsGo.CALENDAR_IMPORT -> NoticeLinks.open(NoticeLinks.Target.CALENDAR_IMPORT)
            SettingsGo.DUTY_ROSTER -> NoticeLinks.open(NoticeLinks.Target.DUTY_ROSTER)
            SettingsGo.HEALTH_REPORT -> NoticeLinks.open(NoticeLinks.Target.HEALTH_REPORT)
            SettingsGo.ATTENDANCE_IMPORT -> NoticeLinks.open(NoticeLinks.Target.ATTENDANCE_IMPORT)
            SettingsGo.COMMUTE -> NoticeLinks.open(NoticeLinks.Target.COMMUTE)
            else -> _target.value = SettingsLink.Go(go)
        }
    }

    fun consume(link: SettingsLink) {
        _target.compareAndSet(link, null)
    }
}

/**
 * [UX-05] What 문의하기 starts with when a screen opens it: the screen named at the start of the text
 * (shown and editable before it is sent), the kind, and whether the alarm check is ticked. Nothing the
 * screen does not already show goes in.
 */
data class InquiryStart(
    val about: String? = null,
    val kind: InquiryKind? = null,
    val withCheck: Boolean = false,
    /** Words already typed somewhere else (a help search with no answer), to go on from. */
    val text: String = "",
    /** Opens on the help answers instead of the form. */
    val help: Boolean = false,
)

/** [UX-05] 문의하기 from any screen: "이 화면에 대해 문의", an error, a search that found nothing. */
object InquiryLinks {
    private val _pending = MutableStateFlow<InquiryStart?>(null)
    /** A start not taken yet: a new inquiry screen takes it as it opens, one already open as it comes. */
    val pending: StateFlow<InquiryStart?> = _pending.asStateFlow()

    fun ask(start: InquiryStart = InquiryStart()) {
        _pending.value = start
        SettingsLinks.go(SettingsGo.INQUIRY)
    }

    /** What the inquiry screen was asked to start with, once. */
    fun take(): InquiryStart? = _pending.getAndUpdate { null }
}
