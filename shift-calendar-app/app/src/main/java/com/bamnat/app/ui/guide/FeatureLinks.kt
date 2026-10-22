package com.bamnat.app.ui.guide

import com.bamnat.app.AppContainer
import com.bamnat.app.data.CommutePrefs
import com.bamnat.app.data.GuideStore
import com.bamnat.app.money.CaptureService
import com.bamnat.app.ui.settings.SettingsPage
import com.bamnat.core.guide.AppFeature
import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.guide.FeatureRoute
import com.bamnat.core.guide.Off
import com.bamnat.core.guide.Place
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * [GUIDE-01] Opens a feature where it lives, from the checklist, a tip, the help search or 기능 켜고 끄기.
 * The navigation follows [target] for the calendar on screen; a tool inside a card (두 알바 비교하기,
 * 견적서, 직원 근무표) is named by [focus], which that card takes once it is on screen.
 */
object FeatureLinks {
    private val _target = MutableStateFlow<FeatureRoute?>(null)
    val target: StateFlow<FeatureRoute?> = _target.asStateFlow()
    private val _focus = MutableStateFlow<String?>(null)
    val focus: StateFlow<String?> = _focus.asStateFlow()

    /** [GUIDE-06] A feature was just turned off from its own ⋮: the snackbar says where it comes back. */
    private val _turnedOff = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val turnedOff: SharedFlow<String> = _turnedOff.asSharedFlow()

    fun open(feature: AppFeature) = open(feature.route)

    fun open(route: FeatureRoute) {
        _focus.value = route.focus.takeIf { !route.place.isSettings }
        _target.value = route
    }

    /** 설정 › 기능 켜고 끄기. */
    fun openSwitches() = open(FeatureRoute(Place.FEATURES))

    fun consume(route: FeatureRoute) {
        _target.compareAndSet(route, null)
    }

    /** True once, for the card whose tool [key] is: that card opens it. */
    fun take(key: String): Boolean = _focus.compareAndSet(key, null)

    private val _today = MutableStateFlow<String?>(null)

    /** [GUIDE-01] A feature of today's day (지금 퇴근, 하루 마감, 공수): the calendar opens [calendar] on today, its day open. */
    fun today(calendar: String) {
        _today.value = calendar
    }

    /** The calendar a feature of today asked for, once; null when a notice opened today instead. */
    fun takeToday(): String? = _today.getAndUpdate { null }

    fun saidOff(name: String) {
        _turnedOff.tryEmit(name)
    }
}

/** [GUIDE-01] The group of settings a settings place opens; null for a place that is not one. */
fun Place.settingsPage(): SettingsPage? = when (this) {
    Place.SETTINGS_WORK -> SettingsPage.WORK
    Place.SETTINGS_ALARM -> SettingsPage.ALARM
    Place.SETTINGS_REST -> SettingsPage.REST
    Place.SETTINGS_NOTICES -> SettingsPage.NOTICES
    Place.SETTINGS_PAY -> SettingsPage.PAY
    Place.SETTINGS_CALENDAR -> SettingsPage.CALENDAR
    Place.SETTINGS_DATA -> SettingsPage.DATA
    Place.SETTINGS_PRIVACY -> SettingsPage.PRIVACY
    else -> null
}

/** [GUIDE-01] The tools inside a card that [FeatureLinks.focus] can open. */
val TOOL_KEYS = setOf(
    "offers", "contract", "quit", "quote", "staff",
    // [GUIDE-01] The investor tab's sheets and tabs (InvestScreen).
    "invest.cash", "invest.dividend", "invest.trade", "invest.quotes", "invest.goal", "invest.journal", "invest.event",
    "invest.alarms", "invest.stats", "invest.records", "invest.overview",
    // [CUST-01] 화면 꾸미기 over the calendar (CalendarScreen).
    "customize",
)

/**
 * [GUIDE-05] One switch per feature that can be off, read and set in one place. The app's own switches
 * are in [GuideStore]; a switch that was there before is used as it is, never copied: 오늘의 흐름 and 잠금
 * 화면 오늘 근무 in settings, 출퇴근 자동 기록, a shop's 매출 없으면 휴무, 결제 알림 읽기 and 연차 사용 촉진 알림.
 */
object FeatureSwitches {
    /** The features of [Off.ELSEWHERE], each with its own branch below. */
    val LINKED = setOf("today.flow", "lock.today", "day.commute", "shop.autoClose", "money.auto", "shift.restRules", "privacy.usageStats", "plaza.news", "leave.promotion")

    fun isOn(c: AppContainer, feature: AppFeature, calendar: String): Boolean = when (feature.off) {
        null -> true
        Off.CALENDAR, Off.APP -> !GuideStore.get(c.appContext).current.isOff(feature, calendar)
        Off.ELSEWHERE -> when (feature.id) {
            "today.flow" -> c.settingsRepository.current.sleepTips
            "lock.today" -> c.settingsRepository.current.todayShiftNotice
            "day.commute" -> CommutePrefs.get(c.appContext).current.enabled
            "shop.autoClose" -> c.calendars.get(calendar)?.shop?.autoClosed ?: true
            "money.auto" -> CaptureService.isOn(c.appContext) && CaptureService.hasAccess(c.appContext)
            "shift.restRules" -> c.settingsRepository.current.restCheck
            "privacy.usageStats" -> c.settingsRepository.current.shareUsageStats
            "plaza.news" -> c.settingsRepository.current.plazaNews
            // [LEAVE-03] The notice switch in settings and on the 연차 screen.
            "leave.promotion" -> c.settingsRepository.current.leaveNotice
            else -> true
        }
    }

    /** The feature is off here, or under a feature that is: it is not shown. */
    fun hidden(c: AppContainer, id: String, calendar: String): Boolean =
        FeatureRegistry.get(id)?.let { f -> !isOn(c, f, calendar) && f.off != Off.ELSEWHERE } ?: false

    /**
     * Turns [feature] on or off. Some can only be turned on where they live (a permission to give, a place to
     * pick): that screen opens instead, and false comes back.
     */
    fun set(c: AppContainer, feature: AppFeature, calendar: String, on: Boolean): Boolean {
        when (feature.off) {
            null -> return false
            Off.CALENDAR, Off.APP -> {
                GuideStore.get(c.appContext).setOn(feature, calendar, on)
                if (feature.id == "widget.lines") c.appScope.launch { com.bamnat.app.widget.WidgetRefresher.refresh(c.appContext) }
            }
            Off.ELSEWHERE -> when (feature.id) {
                "today.flow" -> c.settingsRepository.update { it.copy(sleepTips = on) }
                "lock.today" -> if (on) { FeatureLinks.open(feature); return false } else c.settingsRepository.update { it.copy(todayShiftNotice = false) }
                "day.commute" -> if (on) { FeatureLinks.open(feature); return false } else com.bamnat.app.commute.CommuteRecorder.turnOff(c.appContext)
                "shop.autoClose" -> c.calendars.updateShop(calendar) { it.copy(autoClosed = on) }
                "money.auto" -> if (on) { FeatureLinks.open(feature); return false } else CaptureService.setOn(c.appContext, false)
                "shift.restRules" -> c.settingsRepository.update { it.copy(restCheck = on) }
                // A yes to sending is given where what goes is shown.
                "privacy.usageStats" -> if (on) { FeatureLinks.open(feature); return false } else c.settingsRepository.update { it.copy(shareUsageStats = false) }
                "plaza.news" -> c.settingsRepository.update { it.copy(plazaNews = on) }
                "leave.promotion" -> c.settingsRepository.update { it.copy(leaveNotice = on) }
                else -> { FeatureLinks.open(feature); return false }
            }
        }
        return true
    }
}
