package com.bamnat.app.ui.calendar

import com.bamnat.core.model.WorkPattern
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.ui.pay.hourlyWeek
import com.bamnat.app.alarm.UpcomingAlarm
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.rules
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.pay.named
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.money.MoneyActions
import com.bamnat.core.example.WriteKind
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.memo.Memo
import com.bamnat.core.model.DayAlarm
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.Leave
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.ExtraStamp
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.pay.DayMinutes
import com.bamnat.core.pay.DayPay
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.NightSummary
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PublicPayTable
import com.bamnat.core.schedule.PatternTimeline
import com.bamnat.core.schedule.RestRules
import com.bamnat.core.schedule.RotationSpeed
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.DaySource
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.RestCheck
import com.bamnat.core.sleep.SleepDebt
import com.bamnat.core.sleep.SleepEntry
import com.bamnat.core.sleep.SleepPlan
import com.bamnat.core.sleep.SleepSchedule
import com.bamnat.core.sleep.TodayFlow
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.schedule.HolidayWork
import com.bamnat.core.schedule.datesBetween
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.asksHolidaysOff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import com.bamnat.app.ui.derive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import com.bamnat.core.time.Today

/** Quick-input tool: tap days to stamp a shift on them. */
sealed interface PaintTool {
    /** [CAL-01] Quick input is on and nothing is chosen yet, so a stray tap paints nothing. */
    data object Pick : PaintTool
    data class Shift(val shiftTypeId: Long) : PaintTool
    /** A day off when the user has no off-type shift. */
    data object Off : PaintTool
    /** Back to the pattern. */
    data object Clear : PaintTool
}

/**
 * [CAL-09] Besides a shift, quick input can stamp a whole day's leave or turn the day's alarm off,
 * over many days at once. Kept apart from [PaintTool], whose shift stamps the first setup shares.
 */
enum class DayMark(val label: String) {
    LEAVE("연차"),
    ALARM_OFF("알람 끄기"),
}

/**
 * [EXTRA-02][EXTRA-03] How the law sorts one day's work, and what it adds to pay, for the day view.
 * Worked out with the user's pay setup, or the law's defaults without one (minutes only, no money).
 */
data class DayInsight(
    /** The day's minutes by category, the week's 40 hours included. */
    val minutes: DayMinutes? = null,
    /** What the day adds to pay; null without a pay setup. */
    val pay: DayPay? = null,
    /** What each piece of extra work adds to the month's pay (the month with it less the month without it), by index. */
    val extraAmounts: Map<Int, Long> = emptyMap(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CalendarViewModel(private val container: AppContainer) : ViewModel(), MoneyActions {
    /** [MCAL-01] The calendar on the calendar tab; each write goes to the one on screen when it started. */
    private val schedule: com.bamnat.app.data.ScheduleRepository get() = container.calendars.activeRepository()
    private val memoRepo = container.memoRepository

    /** [EASY-02] A write on the example calendar asks first (WriteGate); anywhere else it runs at once. */
    private fun gate(kind: WriteKind, block: () -> Unit) = com.bamnat.app.ui.guide.WriteGate.request(container, kind, proceed = block)

    /** [MCAL-01] The user's calendars and the one on screen. */
    val calendars: StateFlow<List<com.bamnat.app.data.PersonalCalendar>> = container.calendars.calendars
    val activeCalendar: StateFlow<com.bamnat.app.data.PersonalCalendar> =
        com.bamnat.app.ui.DerivedStateFlow({ container.calendars.current() }, container.calendars.activeCalendar)

    /** [MCAL-04] The list's order, received calendars included. */
    val calendarOrder: StateFlow<List<String>> = container.calendars.order

    fun viewCalendar(id: String) {
        if (id != container.calendars.active.value) container.telemetry.event("calendar_switch", if (id == com.bamnat.app.data.PersonalCalendar.MAIN) "main" else "added")
        container.calendars.setActive(id)
    }

    val snapshot: StateFlow<ScheduleSnapshot?> =
        kotlinx.coroutines.flow.combine(container.calendars.activeSnapshot,
            container.calendars.active.flatMapLatest { container.calendars.pay(it).profile }) { s, p ->
            // [HRLY-05] Hours on the calendar take the legal break off, as pay does.
            if (p?.legalBreaks == true) s.copy(legalBreaks = true) else s
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /**
     * [MCAL-01] An added calendar answers its own kind of work, so the calendar tab shows it as that
     * calendar works; everything else in settings is the app's.
     */
    val settings: StateFlow<AppSettings> = com.bamnat.app.ui.DerivedStateFlow(
        { calendarSettings(container.settingsRepository.current, container.calendars.current()) },
        kotlinx.coroutines.flow.combine(container.settingsRepository.settings, activeCalendar, ::calendarSettings),
    )
    private fun calendarSettings(s: AppSettings, cal: com.bamnat.app.data.PersonalCalendar) =
        if (cal.isMain) s else s.copy(workStyle = cal.style ?: s.workStyle, rosterMonthly = false)
    /** [CAL-05] What the calendar on screen is about: shifts, jobs or sales. */
    val focus: StateFlow<CalendarFocus> = settings.derive { CalendarFocus.of(it.workStyle) }

    /** [CAL-13] An added calendar can be kept from ringing; the main one always rings. */
    val calendarAlarmsOn: StateFlow<Boolean> = activeCalendar.derive { it.isMain || it.alarms }

    /** [CAL-13] Turns the alarms of the added calendar on screen on or off, said back with undo. */
    fun setCalendarAlarms(on: Boolean) = gate(WriteKind.ALARM) {
        val cal = activeCalendar.value
        if (cal.isMain) return@gate
        container.calendars.setAlarms(cal.id, on)
        _messages.tryEmit(UndoMessage("‘${cal.name}’ 알람을 ${if (on) "켰어요" else "껐어요"}") { container.calendars.setAlarms(cal.id, !on) })
    }

    private val viewPrefs = container.appContext.getSharedPreferences(VIEW_PREFS, android.content.Context.MODE_PRIVATE)

    /** [CAL-08] The month as a list instead of the grid; the user's choice, kept on this phone. */
    private val _listView = MutableStateFlow(runCatching { viewPrefs.getBoolean(K_LIST, false) }.getOrDefault(false))
    val listView: StateFlow<Boolean> = _listView.asStateFlow()

    fun setListView(on: Boolean) {
        _listView.value = on
        viewPrefs.edit().putBoolean(K_LIST, on).apply()
    }

    /**
     * [CAL-16] 오늘의 흐름 folded to one line by the user; kept on this phone until opened again. Null until
     * the user folds or opens it: [CAL-21] then a phone too short to show the month under it starts folded.
     */
    private val _flowFolded = MutableStateFlow(runCatching {
        if (viewPrefs.contains(K_FLOW_FOLDED)) viewPrefs.getBoolean(K_FLOW_FOLDED, false) else null
    }.getOrNull())
    val flowFolded: StateFlow<Boolean?> = _flowFolded.asStateFlow()

    fun setFlowFolded(folded: Boolean) {
        _flowFolded.value = folded
        viewPrefs.edit().putBoolean(K_FLOW_FOLDED, folded).apply()
    }

    /** [CAL-17] A tip said once in the snackbar, closed with 알겠어요; nothing to undo. */
    fun tip(text: String) {
        _messages.tryEmit(UndoMessage(text, actionLabel = "알겠어요") {})
    }

    /** [CAL-07] Months whose holidays the user works on purpose, per calendar: not asked again. */
    private val _holidaysKept = MutableStateFlow(runCatching { viewPrefs.getStringSet(K_HOLIDAYS, emptySet())!!.toSet() }.getOrDefault(emptySet()))

    /**
     * [CAL-07] Public holidays the weekly pattern turns into work this month, for work by weekday
     * only: asked once a month whether those are days off.
     */
    fun holidayWork(snapshot: ScheduleSnapshot, month: YearMonth): List<LocalDate> {
        if (!settings.value.workStyle.asksHolidaysOff || holidayKey(month) in _holidaysKept.value) return emptyList()
        return HolidayWork.patternDays(snapshot.resolver, month)
    }
    val holidaysKept: StateFlow<Set<String>> = _holidaysKept.asStateFlow()

    private fun holidayKey(month: YearMonth) = "${activeCalendar.value.id}:$month"

    /** [CAL-07] The holidays stay working days; the card goes away for this month. */
    fun keepHolidayWork(month: YearMonth) {
        val key = holidayKey(month)
        _holidaysKept.value = _holidaysKept.value + key
        viewPrefs.edit().putStringSet(K_HOLIDAYS, _holidaysKept.value).apply()
        _messages.tryEmit(UndoMessage("${month.monthValue}월 공휴일은 그대로 일해요") {
            _holidaysKept.value = _holidaysKept.value - key
            viewPrefs.edit().putStringSet(K_HOLIDAYS, _holidaysKept.value).apply()
        })
    }

    /** [CAL-07] The holidays become days off in one write, said back with undo. */
    fun restHolidays(dates: List<LocalDate>) {
        if (dates.isEmpty()) return
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.updateDays(dates.distinct()) { e ->
                e.copy(override = ShiftOverride.Off, customStart = null, customEnd = null, customBreakMinutes = null)
            }
            _messages.emit(UndoMessage("공휴일 ${before.size}일을 쉬는 날로 바꿨어요") {
                before.forEach { (d, was) -> schedule.restoreDay(d, was) }
            })
        }
    }

    /**
     * [CAL-06] The month's pay as the pay screen counts it, for the summary over the calendar; null
     * without a pay setup. [MON-01] Free for everyone. Heavy enough to run off the main thread.
     */
    fun monthEstimate(profile: PayProfile?, snapshot: ScheduleSnapshot, month: YearMonth): Long? {
        if (profile == null) return null
        return runCatching { monthPay(profile.named(snapshot.kinds), snapshot, month).total }.getOrNull()
    }

    val memoDates: StateFlow<Set<LocalDate>> =
        // [NOTE-01] Only the memos that show on the calendar on screen.
        container.calendars.active.flatMapLatest { memoRepo.datesWithMemos(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val upcomingAlarm: StateFlow<UpcomingAlarm?> = container.alarmScheduler.upcoming

    /** [SETUP-01] A first setup left for later, to go on from the calendar. */
    val setupDraft: StateFlow<com.bamnat.core.setup.SetupDraft?> = container.setupDrafts.draft

    fun resumeSetup() {
        container.setupDrafts.draft.value?.let { container.setupDrafts.save(it.copy(later = false)) }
    }

    /** [EXTRA-05] Pieces of extra work moved from an older record that still need a look. */
    val extrasToReview: StateFlow<Int> =
        container.calendars.active.flatMapLatest { container.calendars.repository(it).extrasToReview }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Pay setup, so a day on the calendar shows what it adds to pay [PAYX-08]. [MCAL-06] The calendar on screen's own. */
    val payProfile: StateFlow<PayProfile?> = com.bamnat.app.ui.DerivedStateFlow(
        { container.calendars.pay(container.calendars.active.value).profile.value },
        container.calendars.active.flatMapLatest { container.calendars.pay(it).profile },
    )
    private val payCalculator = PayCalculator()

    // Moving from day to day in one month reuses that month's result instead of recalculating it.
    @Volatile private var monthPayCache: Pair<List<Any>, MonthPay>? = null

    /**
     * [STAB-01] No lock around the calculation: a run left over from a day already passed held it, and every
     * newer run waited on it with a whole background thread, until saves and taps waited too.
     */
    private fun monthPay(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): MonthPay {
        val key = listOf(profile, snapshot, month)
        monthPayCache?.takeIf { it.first == key }?.let { return it.second }
        val p = profile.hourlyWeek(settings.value)
        return payCalculator.calculate(p, month, snapshot.resolver::resolve).also { monthPayCache = key to it }
    }

    /** [STAB-01] The last tap on 메모 the day's memo field has acted on. */
    var memoFocusUsed = 0

    /**
     * [EXTRA-02][EXTRA-03] The day's minutes by category and, with a pay setup, its pay and what each
     * piece of extra work adds ([withAmounts], when the user may see amounts). Heavy enough to run
     * off the main thread.
     */
    fun dayInsight(profile: PayProfile?, snapshot: ScheduleSnapshot, date: LocalDate, withAmounts: Boolean): DayInsight {
        // [CAL-41] I 리뷰 P20: the same day of the same calendar asked again (the sheet reopened, a fold, the
        // settings flow ticking) is answered from the last result, not by re-working the month per extra.
        val key = InsightKey(profile, snapshot, date, withAmounts, settings.value.weekStart)
        insightCache?.let { (k, v) -> if (k.sameAs(key)) return v }
        return dayInsightNow(profile, snapshot, date, withAmounts).also { insightCache = key to it }
    }

    private class InsightKey(val profile: PayProfile?, val snapshot: ScheduleSnapshot, val date: LocalDate, val amounts: Boolean, val weekStart: java.time.DayOfWeek) {
        fun sameAs(o: InsightKey) = profile == o.profile && snapshot === o.snapshot && date == o.date && amounts == o.amounts && weekStart == o.weekStart
    }

    @Volatile private var insightCache: Pair<InsightKey, DayInsight>? = null

    private fun dayInsightNow(profile: PayProfile?, snapshot: ScheduleSnapshot, date: LocalDate, withAmounts: Boolean): DayInsight {
        val p = (profile ?: lawOnly(settings.value)).named(snapshot.kinds)
        val month = p.period.monthOf(date)
        val pay = monthPay(p, snapshot, month)
        val day = pay.day(date)
        val extras = snapshot.resolver.entryOn(date)?.extras.orEmpty()
        val amounts = if (profile == null || !withAmounts || extras.isEmpty()) emptyMap() else extras.indices.associateWith { i ->
            val without = snapshot.copy(entries = snapshot.entries.map { e ->
                if (e.date == date) e.copy(extras = e.extras.filterIndexed { j, _ -> j != i }) else e
            })
            pay.total - payCalculator.calculate(p, month, without.resolver::resolve).total
        }
        return DayInsight(day?.minutes, day?.takeIf { profile != null }, amounts)
    }

    /** The law's defaults with no money, to sort minutes before the pay setup is done. */
    private fun lawOnly(s: AppSettings) = PayProfile(Occupation.MANUFACTURING, WageType.MONTHLY, ValueTimeline(), weekStart = s.weekStart)

    private val _selected = MutableStateFlow(Today.date())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()

    /** [NAV-01] The month on screen, shared with 돈 and 근무·급여. */
    val shownMonth: StateFlow<YearMonth> = container.shownMonth.month
    fun showMonth(month: YearMonth) = container.shownMonth.show(month)

    private val _paint = MutableStateFlow<PaintTool?>(null)
    val paint: StateFlow<PaintTool?> = _paint.asStateFlow()

    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    fun memosOn(date: LocalDate): Flow<List<Memo>> = container.calendars.active.flatMapLatest { memoRepo.memosOn(date, it) }

    /** [MKT-08] What was changed on [date], newest first. */
    fun changesOn(date: LocalDate): Flow<List<com.bamnat.app.data.DayChange>> = schedule.changesOn(date)

    /**
     * [SLP-01][MKT-07] Rest-gap signs for [month]; the days just before it are read too, so a
     * night at the end of last month still counts against the 1st. [REST-01] By the user's [rules] as the
     * month's pattern turns [REST-03].
     */
    fun restIssues(snapshot: ScheduleSnapshot, month: YearMonth, rules: RestRules): List<RestCheck.Issue> {
        val r = rulesFor(snapshot, month.atDay(1), rules)
        val from = RestCheck.lookbackFrom(month.atDay(1), r)
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(month.atEndOfMonth()) }
            .map(snapshot.resolver::resolve).toList()
        return RestCheck.check(days, r).filter { YearMonth.from(it.date) == month }
    }

    /** [REST-03] How the pattern on [date] turns, for the rules sheet and the checks. */
    fun rotation(snapshot: ScheduleSnapshot, date: LocalDate): RotationSpeed =
        RotationSpeed.of(PatternTimeline.on(snapshot.patterns, date) ?: PatternTimeline.current(snapshot.patterns, date), snapshot.shiftTypes)

    /** [REST-03] [rules] as they fit the pattern on [date]. */
    fun rulesFor(snapshot: ScheduleSnapshot, date: LocalDate, rules: RestRules): RestRules = rotation(snapshot, date).adjust(rules)

    /** [REST-01] The rules sheet's answer: the limits, and 점검 끄기 as the 휴식 점검 switch. */
    fun setRestRules(rules: RestRules) = container.settingsRepository.update {
        it.copy(restRules = rules.copy(on = true).encode(), restCheck = rules.on, strictRest = false)
    }

    /**
     * [NURS-05][JOB-03] The line over the month's rest signs: the nights and what they pay (with a pay setup), and a
     * 공무원's 시간외 counted by 공무원수당 등에 관한 규정 제15조 when a 봉급표 계급 is picked. Off the main thread.
     */
    data class MonthNights(val nights: Int, val nightPay: Long?, val overtime: PublicPayTable.Overtime?)

    fun monthNights(profile: PayProfile?, snapshot: ScheduleSnapshot, month: YearMonth): MonthNights {
        val days = (1..month.lengthOfMonth()).map { snapshot.resolver.resolve(month.atDay(it)) }
        val nights = RestCheck.nightDates(days).size
        val pay = if (nights == 0 || profile == null) null else runCatching { monthPay(profile.named(snapshot.kinds), snapshot, month) }.getOrNull()
        return MonthNights(nights, pay?.let(NightSummary::allowance), profile?.publicGrade?.let { PublicPayTable.overtime(days, it.onSite) })
    }

    /**
     * [SLP-03][SLP-14] Sleep times for [date] from the same plan as 오늘의 흐름: the work on the clock,
     * the alarms that will ring and the way home.
     */
    fun sleepTips(snapshot: ScheduleSnapshot, date: LocalDate, settings: AppSettings): List<SleepPlan.Tip> =
        SleepPlan.tips(SleepSchedule.of(snapshot.resolver, date, date.plusDays(1), settings.alarmsEnabled && calendarAlarmsOn.value,
            settings.sleepTargetMinutes, settings.commuteMinutes), date)

    /** [SLP-14] 오늘의 흐름 at [now]. */
    fun todayFlow(snapshot: ScheduleSnapshot, settings: AppSettings, log: List<SleepEntry>, now: LocalDateTime): TodayFlow.Card =
        // [CAL-13] An added calendar kept from ringing shows no alarm in the day's flow.
        TodayFlow.of(now, snapshot.resolver, settings.alarmsEnabled && calendarAlarmsOn.value, log, settings.sleepTargetMinutes, settings.commuteMinutes,
            settings.restCheck, settings.strictRest, rules = rulesFor(snapshot, now.toLocalDate(), settings.rules),
            // [SLP-16] The way-home card speaks from the user's own sleeps after night work.
            afterNightMinutes = com.bamnat.core.sleep.SleepInsights.afterNightAverage(log, snapshot.resolver, now.toLocalDate()))

    /** [SLP-14] The two numbers the sleep times rest on, changed from the card's ⓘ. */
    fun setSleepNumbers(targetMinutes: Int, commuteMinutes: Int) {
        container.settingsRepository.update { it.copy(sleepTargetMinutes = targetMinutes, commuteMinutes = commuteMinutes) }
    }

    /** [SLP-14] Takes 오늘의 흐름 off the calendar, with a way back. */
    fun hideTodayFlow() {
        container.settingsRepository.update { it.copy(sleepTips = false) }
        _messages.tryEmit(UndoMessage("오늘의 흐름을 숨겼어요. 설정에서 다시 켤 수 있어요") {
            container.settingsRepository.update { it.copy(sleepTips = true) }
        })
    }

    /** [SLP-06] The sleep log, with [SLP-08] sleep measured by a watch or the phone merged in. */
    val sleepLog: StateFlow<List<com.bamnat.core.sleep.SleepEntry>> =
        kotlinx.coroutines.flow.combine(container.sleepRepository.recent(), container.healthSleep.sleeps) { manual, measured ->
            com.bamnat.core.sleep.SleepMerge.merge(manual, measured)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val healthSleepState: StateFlow<com.bamnat.app.sleep.HealthSleep.State> = container.healthSleep.state
    /** [SLP-08] Reads Health Connect again while the day view is on screen. */
    fun refreshHealthSleep(enabled: Boolean) { viewModelScope.launch { container.healthSleep.refresh(enabled) } }
    /** [SLP-06][SLP-14] "잠들었어요", said back with a way to take it back. */
    fun fellAsleep() = gate(WriteKind.SLEEP) {
        container.appScope.launch {
            val asleep = container.sleepRepository.fellAsleep()
            val at = Fmt.time(asleep.entry.start.toLocalTime())
            _messages.emit(
                if (asleep.started) UndoMessage("${at}부터 자는 중으로 적었어요") { container.sleepRepository.delete(asleep.entry.id) }
                else UndoMessage("이미 ${at}부터 자는 중이에요"),
            )
        }
    }

    /** [SLP-06][SLP-14] "깼어요" now, or at [at] when the user says later when they woke. */
    fun wokeUp(at: LocalDateTime? = null) = gate(WriteKind.SLEEP) {
        container.appScope.launch {
            val millis = at?.atZone(Today.zone())?.toInstant()?.toEpochMilli()
            val sleep = container.sleepRepository.wokeUp(millis) ?: return@launch
            val minutes = sleep.minutes ?: 0
            val text = if (minutes > SleepDebt.MAX_SLEEP_MINUTES) "16시간이 넘게 열려 있던 기록이라 수면 통계에서 뺐어요"
            else "${HoursFormat.korean(minutes.toInt())} 잤어요"
            _messages.emit(UndoMessage(text) { container.sleepRepository.reopen(sleep.id) })
        }
    }
    fun deleteSleep(id: Long) { container.appScope.launch { container.sleepRepository.delete(id) } }

    /** Rest-gap signs that fall on [date]. */
    fun restIssuesOn(snapshot: ScheduleSnapshot, date: LocalDate, rules: RestRules): List<RestCheck.Issue> {
        val r = rulesFor(snapshot, date, rules)
        val days = generateSequence(RestCheck.lookbackFrom(date, r)) { it.plusDays(1) }.takeWhile { !it.isAfter(date) }
            .map(snapshot.resolver::resolve).toList()
        return RestCheck.check(days, r).filter { it.date == date }
    }

    /** [FEAT-17] Memos that belong to a date, for the list under the calendar and memo-first cells. */
    val datedMemos: StateFlow<List<Memo>> = container.calendars.active.flatMapLatest { memoRepo.memosFor(it) } // [MEMO-01]
        // [NOTE-05] A repeating memo is on each day it comes back on.
        .map { list -> com.bamnat.app.data.MemoRepository.repeatWindow().let { (from, to) -> com.bamnat.core.memo.MemoRepeat.expand(list, from, to) } }
        .map { list -> list.filter { it.date != null && !it.inTrash && !it.isBlank }.sortedWith(compareBy({ it.date }, { -it.updatedAtMillis })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun logShare() = container.telemetry.event("share_month_image")

    fun select(date: LocalDate) {
        _selected.value = date
    }

    fun setPaint(tool: PaintTool?) {
        if (tool == null || tool != _paint.value) coverAnswer = null // [CAL-19] asked again with another shift
        _paint.value = tool
        _mark.value = null
        _stamp.value = null
        _rangeFrom.value = null
        _lastPaint.value = null
    }

    /** [CAL-38] An extra-work stamp chosen in quick input (잔업 2시간, 특근 09–18, 야간 대근); null goes back to shifts. */
    private val _stamp = MutableStateFlow<ExtraChoice?>(null)
    val stamp: StateFlow<ExtraChoice?> = _stamp.asStateFlow()

    fun setStamp(choice: ExtraChoice?) {
        if (_paint.value == null) return
        _paint.value = PaintTool.Pick
        _mark.value = null
        _stamp.value = choice
        _rangeFrom.value = null
        _lastPaint.value = null
    }

    /**
     * [CAL-38] [choice] on each of [dates] it fits ([ExtraStamp]), in one write with one undo. A single tap on a
     * day that already has the same piece takes it off, like stamping a shift twice.
     */
    private fun paintExtra(dates: List<LocalDate>, choice: ExtraChoice, tapped: Boolean) {
        viewModelScope.launch {
            val resolver = currentSnapshot().resolver
            val max = com.bamnat.app.data.ScheduleRepository.MAX_EXTRAS
            val days = dates.distinct().map(resolver::resolve)
            val skips = days.associate { it.date to ExtraStamp.skip(choice.kind, choice.entry, it, max) }
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            if (tapped && skips.values.single() == ExtraStamp.Skip.SAME) {
                val day = days.single()
                val i = ExtraText.indexOf(day, choice)
                if (i >= 0) {
                    val before = schedule.removeExtra(day.date, i)
                    _lastPaint.value = PaintUndo("${Fmt.monthDay(day.date)} ${choice.name}${Fmt.obj(choice.name)} 뺐어요") { schedule.restoreDay(day.date, before) }
                }
                return@launch
            }
            val fits = skips.filterValues { it == null }.keys.toList()
            val left = ExtraStamp.skippedText(skips.values.filterNotNull().groupingBy { it }.eachCount())
            if (fits.isEmpty()) {
                _lastPaint.value = PaintUndo("${choice.name}${Fmt.obj(choice.name)} 넣을 날이 없어요" + if (left.isNotEmpty()) " · $left" else "", null)
                return@launch
            }
            val before = schedule.updateDays(fits) { e -> e.copy(extras = e.extras + choice.entry) }
            val what = if (before.size == 1) Fmt.monthDay(before.keys.first()) + "에" else "${before.size}일에"
            _lastPaint.value = PaintUndo(
                "$what ${choice.name}${Fmt.obj(choice.name)} 넣었어요" + if (left.isNotEmpty()) " · $left 빼고" else "",
                // Only the pieces this stamp added go back: other changes to those days since stay.
                undo = {
                    schedule.updateDays(before.keys.toList()) { now ->
                        val i = now.extras.lastIndexOf(choice.entry)
                        if (i < 0) now else now.copy(extras = now.extras.filterIndexed { j, _ -> j != i })
                    }
                },
            )
        }
    }

    /** [CAL-09] Leave or 알람 끄기 chosen in quick input; null goes back to the shift stamps. */
    private val _mark = MutableStateFlow<DayMark?>(null)
    val mark: StateFlow<DayMark?> = _mark.asStateFlow()

    fun setMark(mark: DayMark?) {
        if (_paint.value == null) return
        _paint.value = PaintTool.Pick
        _stamp.value = null
        _mark.value = mark
        _rangeFrom.value = null
        _lastPaint.value = null
    }

    /** [CAL-09] The first day of a range, held by a long press in quick input; the next tap paints up to it. */
    private val _rangeFrom = MutableStateFlow<LocalDate?>(null)
    val rangeFrom: StateFlow<LocalDate?> = _rangeFrom.asStateFlow()

    /** True while quick input has something to stamp: a shift, a day off, the pattern, leave or 알람 끄기. */
    val canStamp: Boolean get() = _mark.value != null || _stamp.value != null || _paint.value?.let(::stampOf) != null

    /** [CAL-09] A long press in quick input starts a range from [date]. */
    fun startRange(date: LocalDate) = gate(WriteKind.DAYS) {
        if (!canStamp) return@gate
        _selected.value = date
        _rangeFrom.value = date
        _lastPaint.value = PaintUndo("${Fmt.monthDay(date)}부터 · 끝 날짜를 누르세요", null)
    }

    /** A tap on a calendar cell: selects it, and stamps it when quick input has a stamp chosen. */
    fun onDayTapped(date: LocalDate) {
        _selected.value = date
        // [CAL-09] The second tap of a range paints every day between, both ends included.
        _rangeFrom.value?.let { from ->
            _rangeFrom.value = null
            paintDays(datesBetween(from, date))
            return
        }
        _mark.value?.let { mark ->
            _lastPaint.value = null
            toggleMark(date, mark)
            return
        }
        _stamp.value?.let { choice ->
            _lastPaint.value = null
            paintExtra(listOf(date), choice, tapped = true)
            return
        }
        val target = _paint.value?.let(::stampOf) ?: return
        _lastPaint.value = null
        // [CAL-19] A working shift on a day off is asked about first: most often it is a 대근.
        if (askCover(listOf(date))) return
        // Stamping the same shift twice undoes it, so a mis-tap is fixed with one more tap.
        // Decided inside the write so quick double taps see each other's result.
        viewModelScope.launch { changeShift(date) { current -> if (current == target) ShiftOverride.None else target } }
    }

    /**
     * [CAL-19] Quick input with a working shift chosen, put on days the roster gives off: "근무일이
     * 아니에요, 대근으로 넣을까요?" before anything is written, so a 대근 is never counted as the roster.
     */
    data class CoverAsk(val type: ShiftType, val offDays: List<LocalDate>, val workDays: List<LocalDate>)

    private val _coverAsk = MutableStateFlow<CoverAsk?>(null)
    val coverAsk: StateFlow<CoverAsk?> = _coverAsk.asStateFlow()

    /** [CAL-19] The answer kept for the rest of this quick input when the user ticks "다시 묻지 않기". */
    private var coverAnswer: Boolean? = null

    /** [CAL-19] A day off (by the roster or marked off by hand) that a working [type] would turn into a working day. */
    private fun offDayFor(day: ResolvedDay, type: ShiftType): Boolean =
        !day.isWorkDay && day.leave == null && type.hasTimes && !type.isOff && day.coveredShift == null

    /** [CAL-19] True when [dates] hold a day off for the chosen shift and the question was asked or answered for them. */
    private fun askCover(dates: List<LocalDate>): Boolean {
        val tool = _paint.value as? PaintTool.Shift ?: return false
        val snap = snapshot.value ?: return false
        val type = snap.typesById[tool.shiftTypeId] ?: return false
        val (off, work) = dates.distinct().partition { offDayFor(snap.resolver.resolve(it), type) }
        if (off.isEmpty()) return false
        val ask = CoverAsk(type, off, work)
        coverAnswer?.let { answerCover(ask, asCover = it, keep = true); return true }
        _coverAsk.value = ask
        return true
    }

    /** [CAL-19] The answer: [asCover] keeps the days off and adds a 대근 of the shift's hours; else the days become working days. */
    fun answerCover(ask: CoverAsk, asCover: Boolean, keep: Boolean) {
        _coverAsk.value = null
        if (keep) coverAnswer = asCover
        val snap = snapshot.value ?: return
        if (!asCover) {
            stampShift(ask.offDays + ask.workDays, ShiftOverride.Shift(ask.type.id), ask.type.name)
            return
        }
        val kind = coverKind(snap, ask.type)
        val cover = ExtraText.cover(kind, ask.type)
        val coverName = ExtraText.coverName(kind, ask.type)
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val work = schedule.updateDays(ask.workDays) { entry -> nextShift(entry, ShiftOverride.Shift(ask.type.id)) }
            val off = schedule.updateDays(ask.offDays) { entry ->
                if (entry.extras.size >= com.bamnat.app.data.ScheduleRepository.MAX_EXTRAS) entry else entry.copy(extras = entry.extras + cover)
            }
            val what = if (ask.offDays.size == 1) Fmt.monthDay(ask.offDays.first()) + "에" else "쉬는 날 ${ask.offDays.size}일에"
            val rest = if (work.isNotEmpty()) " · 근무일 ${work.size}일은 ${ask.type.name}${Fmt.ro(ask.type.name)}" else ""
            _lastPaint.value = PaintUndo("$what $coverName${Fmt.obj(coverName)} 넣었어요$rest") {
                schedule.updateDays((work.keys + off.keys).toList()) { now ->
                    val was = work[now.date] ?: off[now.date] ?: DayEntry(now.date)
                    now.copy(override = was.override, customStart = was.customStart, customEnd = was.customEnd,
                        customBreakMinutes = was.customBreakMinutes, extras = was.extras)
                }
            }
        }
    }

    fun dismissCover() { _coverAsk.value = null }

    /** [CAL-09] One tap with a mark: on, or off again when the day has it. */
    private fun toggleMark(date: LocalDate, mark: DayMark) = viewModelScope.launch {
        val day = currentSnapshot().resolver.resolve(date)
        when (mark) {
            DayMark.LEAVE -> when {
                day.leave != null -> setLeave(date, null, announceInBar = true)
                takesLeave(day) -> setLeave(date, FULL_LEAVE, announceInBar = true)
                else -> _lastPaint.value = PaintUndo("${Fmt.monthDay(date)}은 근무가 없어 연차를 넣지 않았어요", null)
            }
            DayMark.ALARM_OFF -> when {
                day.alarmSkipped -> setAlarmSkipped(date, false, announceInBar = true)
                ringsOn(day) -> setAlarmSkipped(date, true, announceInBar = true)
                else -> _lastPaint.value = PaintUndo("${Fmt.monthDay(date)}은 울릴 알람이 없어요", null)
            }
        }
    }

    /** The calendar on screen as it is now; read once when nothing on screen holds it yet. */
    private suspend fun currentSnapshot(): ScheduleSnapshot = snapshot.value ?: container.calendars.activeSnapshot.first()

    /** [CAL-09] Leave goes on a working day with hours, never on a day off. */
    private fun takesLeave(day: ResolvedDay) = day.leave == null && day.isWorkDay && day.shiftType?.hasTimes == true

    /** [CAL-09] A day whose work would ring an alarm. */
    private fun ringsOn(day: ResolvedDay) = !day.alarmSkipped && (day.isWorkDay || day.coveredShift != null)

    /**
     * [CAL-01] The last drag in quick input, said back in the bar itself: a snackbar would cover the
     * bar and the chips under the finger. [CAL-09] Without [undo] it is only a hint, such as where a
     * range starts.
     */
    data class PaintUndo(val text: String, val undo: (suspend () -> Unit)?)

    private val _lastPaint = MutableStateFlow<PaintUndo?>(null)
    val lastPaint: StateFlow<PaintUndo?> = _lastPaint.asStateFlow()

    /**
     * [CAL-01] Quick input dragged over [dates]: every one gets the chosen stamp (a drag never takes
     * one off), in one write, said back with undo.
     */
    fun paintDays(dates: List<LocalDate>) = gate(WriteKind.DAYS) {
        _mark.value?.let { paintMark(dates.distinct(), it); return@gate }
        _stamp.value?.let { paintExtra(dates, it, tapped = false); return@gate }
        val tool = _paint.value ?: return@gate
        val target = stampOf(tool) ?: return@gate
        val name = when (tool) {
            is PaintTool.Shift -> snapshot.value?.typesById?.get(tool.shiftTypeId)?.name ?: return@gate
            PaintTool.Off -> "쉬는 날"
            else -> null
        }
        if (askCover(dates)) return@gate // [CAL-19]
        stampShift(dates, target, name)
    }

    /** [CAL-01] Every one of [dates] gets [target] in one write, said back in the bar with undo. */
    private fun stampShift(dates: List<LocalDate>, target: ShiftOverride, name: String?) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.updateDays(dates.distinct()) { entry -> nextShift(entry, target) }
            val what = if (before.size == 1) Fmt.monthDay(before.keys.first()) + "을" else "${before.size}일을"
            _lastPaint.value = PaintUndo(
                if (name == null) "$what 패턴대로 되돌렸어요" else "$what $name${Fmt.ro(name)} 칠했어요",
                // Only the shift goes back: a memo or alarm changed on those days since stays.
                undo = {
                    schedule.updateDays(before.keys.toList()) { now ->
                        val was = before[now.date] ?: DayEntry(now.date)
                        now.copy(override = was.override, customStart = was.customStart, customEnd = was.customEnd, customBreakMinutes = was.customBreakMinutes)
                    }
                },
            )
        }
    }

    /** [NURS-01] A month typed at once: each day its own shift, in one write, said back with one undo. */
    fun applyDuty(changes: Map<LocalDate, ShiftOverride>) = gate(WriteKind.SHIFT) {
        if (changes.isEmpty()) return@gate
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule
            val before = schedule.updateDays(changes.keys.toList()) { entry -> changes[entry.date]?.let { nextShift(entry, it) } ?: entry }
            _lastPaint.value = PaintUndo(
                "${changes.size}일 근무를 넣었어요",
                undo = {
                    schedule.updateDays(before.keys.toList()) { now ->
                        val was = before[now.date] ?: DayEntry(now.date)
                        now.copy(override = was.override, customStart = was.customStart, customEnd = was.customEnd, customBreakMinutes = was.customBreakMinutes)
                    }
                },
            )
            // The bar says it and holds 되돌리기, so it is opened if it was not.
            if (_paint.value == null) _paint.value = PaintTool.Pick
        }
    }

    /** [CAL-01] Takes the last drag back; once, so a second tap cannot undo something else. */
    fun undoLastPaint() {
        val last = _lastPaint.value ?: return
        val undo = last.undo ?: return
        _lastPaint.value = null
        viewModelScope.launch { undo() }
    }

    /**
     * [CAL-09] Leave or 알람 끄기 over many days: only the days it fits get it (leave on working days,
     * 알람 끄기 where an alarm would ring), in one write, and undo takes back only that.
     */
    private fun paintMark(dates: List<LocalDate>, mark: DayMark) {
        viewModelScope.launch {
            val resolver = currentSnapshot().resolver
            val fits = dates.map(resolver::resolve).filter { if (mark == DayMark.LEAVE) takesLeave(it) else ringsOn(it) }.map { it.date }
            if (fits.isEmpty()) {
                _lastPaint.value = PaintUndo(if (mark == DayMark.LEAVE) "연차를 넣을 근무일이 없어요" else "알람이 울릴 날이 없어요", null)
                return@launch
            }
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.updateDays(fits) { e ->
                if (mark == DayMark.LEAVE) e.copy(leave = FULL_LEAVE) else e.copy(alarmSkipped = true)
            }
            val one = before.size == 1
            val skipped = dates.size - fits.size
            // "근무일 4일에 연차를 넣었어요 · 근무 없는 1일은 빼고": the count never reads as a date.
            val done = when (mark) {
                DayMark.LEAVE -> if (one) "${Fmt.monthDay(before.keys.first())}에 연차를 넣었어요" else "근무일 ${before.size}일에 연차를 넣었어요"
                DayMark.ALARM_OFF -> if (one) "${Fmt.monthDay(before.keys.first())} 알람을 껐어요" else "${before.size}일 알람을 껐어요"
            }
            val left = if (mark == DayMark.LEAVE) "근무 없는" else "알람 없는"
            _lastPaint.value = PaintUndo(
                done + if (skipped > 0) " · $left ${skipped}일은 빼고" else "",
                undo = {
                    schedule.updateDays(before.keys.toList()) { now ->
                        val was = before[now.date] ?: DayEntry(now.date)
                        if (mark == DayMark.LEAVE) now.copy(leave = was.leave) else now.copy(alarmSkipped = was.alarmSkipped)
                    }
                },
            )
        }
    }

    private fun stampOf(tool: PaintTool): ShiftOverride? = when (tool) {
        PaintTool.Pick -> null
        is PaintTool.Shift -> ShiftOverride.Shift(tool.shiftTypeId)
        PaintTool.Off -> ShiftOverride.Off
        PaintTool.Clear -> ShiftOverride.None
    }

    /**
     * [SCHED-03] Restarts the rotation on [date] at [step] (0-based) from that day on, keeping every day before,
     * and sets the days in [clear] back to the pattern. Said back with one undo for both.
     */
    fun realignFrom(date: LocalDate, step: Int, clear: List<LocalDate>) {
        val snap = snapshot.value ?: return
        val rotation = snap.resolver.patternOn(date) as? WorkPattern.Rotation ?: return
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val beforePatterns = snap.patterns
            schedule.changePatternFrom(PatternTimeline.realign(rotation, date, step), date)
            val beforeDays = if (clear.isEmpty()) emptyMap() else schedule.updateDays(clear) { it.copy(override = ShiftOverride.None) }
            val cleared = if (clear.isEmpty()) "" else " · 직접 바꾼 ${clear.size}일은 패턴대로"
            _messages.emit(
                UndoMessage("${Fmt.monthDay(date)}부터 ${step + 1}번째 날로 다시 맞췄어요$cleared") {
                    schedule.restorePatterns(beforePatterns)
                    beforeDays.forEach { (d, e) -> schedule.restoreDay(d, e) }
                },
            )
        }
    }

    /**
     * Sets the shift for a day. Choosing what the pattern already gives stores nothing. [CAL-10] Said
     * back with undo, as every change on a day is.
     */
    fun assign(date: LocalDate, override: ShiftOverride, announce: Boolean = true) = gate(WriteKind.SHIFT) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = changeShift(date) { override }
            if (!announce) return@launch
            val name = (override as? ShiftOverride.Shift)?.let { snapshot.value?.typesById?.get(it.shiftTypeId)?.name }
                ?: if (override == ShiftOverride.Off) "쉬는 날" else null
            _messages.emit(
                UndoMessage(if (name == null) "${Fmt.monthDay(date)}을 패턴대로 되돌렸어요" else "${Fmt.monthDay(date)}을 $name${Fmt.ro(name)} 바꿨어요") {
                    schedule.restoreDay(date, before)
                },
            )
        }
    }

    /**
     * [DAY-03] The shift chosen from a day's long-press menu, said back with undo. A working shift on
     * a day the roster gives off is most often a 대근, so then the snackbar offers that instead [EXTRA-01].
     */
    fun assignQuick(day: ResolvedDay, override: ShiftOverride, snapshot: ScheduleSnapshot) = gate(WriteKind.SHIFT) {
        val date = day.date
        val type = (override as? ShiftOverride.Shift)?.let { snapshot.typesById[it.shiftTypeId] }
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = changeShift(date) { override }
            val name = type?.name ?: if (override == ShiftOverride.None) "패턴" else "쉬는 날"
            val restore: suspend () -> Unit = { schedule.restoreDay(date, before) }
            val cover = type?.takeIf { isRosterRestDay(day, it) }?.let { coverKind(snapshot, it) }
            _messages.emit(
                when {
                    cover != null -> UndoMessage("원래 쉬는 날이에요. 쉬는 날 일했다면 대근으로 넣어 주세요", "대근으로") {
                        schedule.restoreDay(date, before)
                        schedule.addExtra(date, ExtraText.cover(cover, type))
                    }
                    override == ShiftOverride.None -> UndoMessage("${Fmt.monthDay(date)}을 패턴대로 되돌렸어요", undo = restore)
                    else -> UndoMessage("${Fmt.monthDay(date)}을 $name${Fmt.ro(name)} 바꿨어요", undo = restore)
                },
            )
        }
    }

    /** [EXTRA-01] True when [type] would turn a day the roster gives off into a working day: likely a 대근. */
    fun isRosterRestDay(day: ResolvedDay, type: ShiftType): Boolean =
        day.source == DaySource.PATTERN && !day.isWorkDay && day.leave == null && type.hasTimes && !type.isOff

    /** The kind for a 대근 of [type] [EXTRA-07]. */
    fun coverKind(snapshot: ScheduleSnapshot, type: ShiftType): ExtraKind = ExtraText.coverKind(snapshot.kinds, snapshot.shiftTypes, type)

    /** [CAL-46] The month's "내 기록" card is not shown again. */
    fun closeMonthRecap(month: java.time.YearMonth) {
        val key = com.bamnat.core.work.MonthRecap.key(month)
        container.settingsRepository.update { s -> if (s.monthRecapSeen >= key) s else s.copy(monthRecapSeen = key) }
    }

    /**
     * [ALRM-03] The one alarm suggestion for now, from when alarms were turned off and the sleep log, on the main
     * calendar only (its alarms are the ones that ring). Worked out on the phone; nothing changes until a tap.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val alarmSuggestion: StateFlow<com.bamnat.core.alarm.AlarmSuggest.Suggestion?> =
        combine(snapshot, container.settingsRepository.settings, sleepLog, activeCalendar) { snap, s, sleeps, cal ->
            if (snap == null || !s.alarmSuggest || !cal.isMain) null else SuggestInput(snap, s, sleeps)
        }.mapLatest { input ->
            input ?: return@mapLatest null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { findAlarmSuggestion(input.snap, input.settings, input.sleeps) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class SuggestInput(val snap: ScheduleSnapshot, val settings: AppSettings, val sleeps: List<com.bamnat.core.sleep.SleepEntry>)

    /** Reads the event log: off the main thread. */
    internal fun findAlarmSuggestion(
        snap: ScheduleSnapshot, settings: AppSettings, sleeps: List<com.bamnat.core.sleep.SleepEntry>, today: LocalDate = Today.date(),
    ): com.bamnat.core.alarm.AlarmSuggest.Suggestion? {
        val suggest = com.bamnat.core.alarm.AlarmSuggest
        val days = snap.resolver.resolveRange(today.minusDays(suggest.LOOK_BACK_DAYS), today.minusDays(1))
            .filter { !it.hasCustomTimes && !it.alarmSkipped && it.shiftType?.activeAlarms?.isNotEmpty() == true }
            .map { it.date to it.shiftType!! }
        if (days.isEmpty()) return null
        val offs = suggest.offsOf(container.telemetry.log.read(), Today.zone())
        return suggest.find(today, snap.shiftTypes, days, offs, sleeps, settings.sleepTargetMinutes,
            settings.bedtimeNotice, com.bamnat.core.alarm.AlarmSuggest.Memory.decode(settings.alarmSuggestMemory))
    }

    /**
     * [ALRM-03] 적용: the suggested change on that one kind of shift (or 잘 시간 알림 on), said back with ten seconds
     * to take it back. Either way the card rests; it is the user's answer.
     */
    fun applyAlarmSuggestion(s: com.bamnat.core.alarm.AlarmSuggest.Suggestion) {
        container.appScope.launch {
            val repo = container.scheduleRepository
            val text: String
            val undo: suspend () -> Unit
            val moved = s.moved
            if (moved != null) {
                val before = repo.shiftTypes.first().firstOrNull { it.id == s.type.id } ?: return@launch
                repo.saveShiftType(before.copy(alarms = moved.alarms))
                text = "${s.type.name} 알람을 옮겼어요"
                undo = { repo.saveShiftType(before); suggestAnswered(s, "undone") }
            } else {
                val was = container.settingsRepository.current.bedtimeNotice
                container.settingsRepository.update { it.copy(bedtimeNotice = true) }
                text = "잘 시간 30분 전에 알려 드릴게요"
                undo = { container.settingsRepository.update { it.copy(bedtimeNotice = was) }; suggestAnswered(s, "undone") }
            }
            suggestAnswered(s, "applied")
            _messages.emit(UndoMessage(text, undo = undo, long = true))
        }
    }

    /** [ALRM-03] 아니요 (rests [com.bamnat.core.alarm.AlarmSuggest.QUIET_DAYS] days) or 다시 보지 않기. */
    fun declineAlarmSuggestion(s: com.bamnat.core.alarm.AlarmSuggest.Suggestion, forGood: Boolean) {
        suggestAnswered(s, if (forGood) "never" else "no", forGood)
    }

    private fun suggestAnswered(s: com.bamnat.core.alarm.AlarmSuggest.Suggestion, answer: String, forGood: Boolean = false) {
        container.settingsRepository.update { st ->
            st.copy(alarmSuggestMemory = com.bamnat.core.alarm.AlarmSuggest.Memory.decode(st.alarmSuggestMemory)
                .answered(s.key, Today.date(), forGood).encode())
        }
        // Only the kind and the answer: no shift name, no times.
        container.telemetry.event(com.bamnat.core.diag.DiagEvents.ALARM_SUGGEST, "${s.kind.key} $answer")
    }

    /**
     * [CAL-43] What a typed line proposed ([com.bamnat.core.schedule.QuickText]), written on its days with one
     * undo for all of them. A 대근 copies the covered shift's hours; extra work with a start and a length is
     * that span; otherwise the kind's usual piece.
     */
    fun applyQuickText(p: com.bamnat.core.schedule.QuickText.Proposal, snapshot: ScheduleSnapshot) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = p.dates.associateWith { snapshot.resolver.entryOn(it) }
            when (val a = p.act) {
                is com.bamnat.core.schedule.QuickText.Act.Shift -> p.dates.forEach { d -> changeShift(d) { ShiftOverride.Shift(a.shiftTypeId) } }
                com.bamnat.core.schedule.QuickText.Act.Off -> p.dates.forEach { d -> changeShift(d) { ShiftOverride.Off } }
                is com.bamnat.core.schedule.QuickText.Act.Leave -> p.dates.forEach { d -> schedule.setLeave(d, Leave(LeaveKind.ANNUAL, a.quarters)) }
                is com.bamnat.core.schedule.QuickText.Act.Extra -> {
                    val entry = quickExtra(a, snapshot) ?: return@launch
                    p.dates.forEach { d -> schedule.addExtra(d, entry) }
                }
            }
            _messages.emit(UndoMessage("${p.text} 넣었어요") { before.forEach { (d, e) -> schedule.restoreDay(d, e) } })
        }
    }

    private fun quickExtra(a: com.bamnat.core.schedule.QuickText.Act.Extra, snapshot: ScheduleSnapshot): ExtraEntry? {
        val covered = a.coveredShiftId?.let { snapshot.typesById[it] }?.takeIf { it.hasTimes }
        if (covered != null) {
            val e = ExtraText.cover(snapshot.kinds.of(a.kindId), covered)
            return a.start?.let { s -> e.copy(start = s, end = s.plusMinutes(covered.durationMinutes.toLong())) } ?: e
        }
        val kind = snapshot.kinds.of(a.kindId)
        val start = a.start
        val minutes = a.minutes
        return when {
            start != null && minutes != null -> ExtraEntry(kind.id, start, start.plusMinutes(minutes.toLong()))
            minutes != null -> ExtraEntry(kind.id, minutes = minutes)
            else -> ExtraText.default(kind)?.let { d -> if (start != null && d.start != null && d.end != null)
                d.copy(start = start, end = start.plusMinutes(com.bamnat.core.model.WorkTime.spanMinutes(d.start!!, d.end!!).toLong())) else d }
        }
    }

    /** [EXTRA-01] The roster stays a day off and the shift goes in as a 대근 of its hours, said back with undo. */
    fun coverInstead(date: LocalDate, type: ShiftType, snapshot: ScheduleSnapshot) = gate(WriteKind.SHIFT) {
        val kind = coverKind(snapshot, type)
        addExtra(date, ExtraText.cover(kind, type), ExtraText.coverName(kind, type))
    }

    private suspend fun changeShift(date: LocalDate, choose: (ShiftOverride) -> ShiftOverride): DayEntry? =
        schedule.updateDay(date) { entry -> nextShift(entry, choose(entry.override)) }

    /** The day with [chosen] as its shift; choosing what the pattern already gives stores nothing. */
    private fun nextShift(entry: DayEntry, chosen: ShiftOverride): DayEntry {
        val pattern = snapshot.value?.resolver?.patternOn(entry.date)
        val patternId = pattern?.shiftTypeIdOn(entry.date)
        val patternCovers = pattern != null
        val next = when (chosen) {
            is ShiftOverride.Shift -> if (patternCovers && patternId == chosen.shiftTypeId) ShiftOverride.None else chosen
            ShiftOverride.Off -> if (patternCovers && patternId == null) ShiftOverride.None else chosen
            ShiftOverride.None -> chosen
        }
        // Times set for the old shift do not carry over to a different one.
        return if (next == entry.override) entry else entry.copy(override = next, customStart = null, customEnd = null, customBreakMinutes = null)
    }

    fun setCustomTimes(date: LocalDate, start: LocalTime, end: LocalTime) = gate(WriteKind.DAY_TIME) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.updateDay(date) { it.copy(customStart = start, customEnd = end) }
            // [CAL-10] A time picked by mistake is one tap from how it was.
            _messages.emit(UndoMessage("${Fmt.monthDay(date)} 근무 시간을 바꿨어요 (${Fmt.timeRange(start, end)})") { schedule.restoreDay(date, before) })
        }
    }

    fun setBreak(date: LocalDate, minutes: Int?) = gate(WriteKind.DAY_TIME) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.updateDay(date) { it.copy(customBreakMinutes = minutes) }
            if (before?.customBreakMinutes == minutes) return@launch
            // [DAYW-10] Like the times, a break changed by mistake is one tap from how it was.
            val said = minutes?.let { "휴게 시간을 ${HoursFormat.korean(it)}으로 바꿨어요" } ?: "휴게 시간을 기본으로 되돌렸어요"
            _messages.emit(UndoMessage("${Fmt.monthDay(date)} $said") { schedule.restoreDay(date, before) })
        }
    }

    /**
     * [DAYW-11] Start, end and break of one day from the one time window. [DAYW-08] Always the planned shift,
     * before leave took its part: the resolver takes the leave off again from what is saved here.
     */
    fun setDayTimes(date: LocalDate, start: LocalTime, end: LocalTime, breakMinutes: Int, type: ShiftType?) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            // The shift's own times are not "this day only": the day keeps following the shift type.
            val own = type?.takeIf { it.hasTimes && it.start == start && it.end == end }
            val before = schedule.setCustomTimes(
                date,
                start.takeIf { own == null },
                end.takeIf { own == null },
                breakMinutes.takeIf { own == null || it != own.breakMinutes },
            )
            _messages.emit(UndoMessage("${Fmt.monthDay(date)} 근무 시간을 바꿨어요 (${Fmt.timeRange(start, end)})") { schedule.restoreDay(date, before) })
        }
    }

    fun clearCustomTimes(date: LocalDate) = gate(WriteKind.DAY_TIME) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.setCustomTimes(date, null, null, null)
            _messages.emit(UndoMessage("${Fmt.monthDay(date)} 근무를 기본 시간으로 되돌렸어요") { schedule.restoreDay(date, before) })
        }
    }

    /** [GUIDE-04] The guide ticks a feature once it is used; only on this phone. */
    private fun guideUsed(id: String) = com.bamnat.app.data.GuideStore.get(container.appContext).used(id)

    /** [SHFT-03] Who the day was swapped with (or covered for, on a 대근 day); blank clears it. Said back with undo. */
    fun setSwapWith(date: LocalDate, name: String) = gate(WriteKind.SHIFT) {
        if (name.isNotBlank()) guideUsed("shift.swap")
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val clean = name.trim().take(MAX_SWAP_NAME)
            val before = schedule.updateDay(date) { it.copy(swapWith = clean) }
            if ((before?.swapWith ?: "") == clean) return@launch
            val text = if (clean.isEmpty()) "${Fmt.monthDay(date)} 교환 기록을 지웠어요" else "${Fmt.monthDay(date)}에 ‘$clean’${Fmt.ro(clean)} 교환을 적었어요"
            _messages.emit(UndoMessage(text) { schedule.restoreDay(date, before) })
        }
    }

    /**
     * [DAYW-03] 지금 퇴근 on [day]: past the end it puts 잔업 from the end to now (replacing a 잔업 already
     * there), before the end the day ends now. Said back with undo.
     */
    fun leaveNow(day: ResolvedDay, now: LocalDateTime = Today.dateTime()) = gate(WriteKind.DAY_TIME) {
        guideUsed("day.leaveNow")
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val kindName = snapshot.value?.kinds?.name(com.bamnat.core.model.ExtraKind.OVERTIME_ID) ?: "잔업"
            val (before, text) = LeaveNowWriter.write(schedule, day, now, kindName)
            _messages.emit(if (text == null) UndoMessage("퇴근 시각이에요. 따로 적을 게 없어요") else UndoMessage(text) { schedule.restoreDay(day.date, before) })
        }
    }

    /** [EXTRA-01] Adds a piece of extra work, said back with undo: "10월 17일에 야간 대근을 넣었어요". */
    /** [HRLY-01][HRLY-08] Changes one day of the calendar on screen (출결, 출근·퇴근), said back with undo. */
    fun changeDay(date: LocalDate, said: String, feature: String? = null, transform: (com.bamnat.core.model.DayEntry) -> com.bamnat.core.model.DayEntry) = gate(WriteKind.DAY_TIME) {
        feature?.let(::guideUsed)
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.updateDay(date, transform)
            _messages.emit(UndoMessage(said) { schedule.restoreDay(date, before) })
        }
    }

    fun addExtra(date: LocalDate, entry: ExtraEntry, name: String) = gate(WriteKind.DAY_TIME) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.addExtra(date, entry)
            if ((before?.extras?.size ?: 0) >= com.bamnat.app.data.ScheduleRepository.MAX_EXTRAS) {
                _messages.emit(UndoMessage("하루에 추가 근무는 ${com.bamnat.app.data.ScheduleRepository.MAX_EXTRAS}건까지 넣을 수 있어요"))
                return@launch
            }
            _messages.emit(UndoMessage("${Fmt.monthDay(date)}에 $name${Fmt.obj(name)} 넣었어요") { schedule.restoreDay(date, before) })
        }
    }

    /** [EXTRA-01] Changes the piece at [index] in place, as the user edits it. */
    fun updateExtra(date: LocalDate, index: Int, entry: ExtraEntry) = gate(WriteKind.DAY_TIME) {
        viewModelScope.launch { schedule.updateExtra(date, index, entry) }
    }

    fun removeExtra(date: LocalDate, index: Int, name: String) = gate(WriteKind.DAY_TIME) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.removeExtra(date, index)
            _messages.emit(UndoMessage("$name${Fmt.obj(name)} 뺐어요") { schedule.restoreDay(date, before) })
        }
    }

    /** [EXTRA-01] A one-tap choice from the long-press menu or the day's chips: added, or taken off when the day has it. */
    fun toggleExtra(day: ResolvedDay, choice: ExtraChoice) = gate(WriteKind.DAY_TIME) {
        val i = ExtraText.indexOf(day, choice)
        if (i >= 0) removeExtra(day.date, i, ExtraText.name(day.extras[i])) else addExtra(day.date, choice.entry, choice.name)
    }

    /** [EXTRA-05] The day's moved pieces are as the user meant them. */
    fun markExtrasReviewed(date: LocalDate) {
        viewModelScope.launch { schedule.markExtrasReviewed(date) }
    }

    fun markAllExtrasReviewed() {
        viewModelScope.launch { schedule.markAllExtrasReviewed() }
    }

    /**
     * [EXTRA-05] Days off once changed into a shift and marked 대근 become days off with a 대근 of that
     * shift's hours; undo puts every day back as it was.
     */
    fun convertSwapCovers(days: List<DayEntry>) {
        container.settingsRepository.update { it.copy(swapCoversAnswered = true) }
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val n = schedule.convertShiftSwapCovers(days.map { it.date })
            if (n > 0) _messages.emit(UndoMessage("${n}일을 대근으로 바꿨어요") { days.forEach { schedule.restoreDay(it.date, it) } })
        }
    }

    /** [EXTRA-05] The user keeps those days as they are; not asked again. */
    fun keepSwapCovers() = container.settingsRepository.update { it.copy(swapCoversAnswered = true) }

    /** 연차·반차 for a day; [announce] says it back with undo when it was set without the day open [DAY-03]. */
    /** [CAL-10] Every change says itself back with undo; [announceInBar] says it in quick input's bar instead. */
    fun setLeave(date: LocalDate, leave: Leave?, announce: Boolean = true, announceInBar: Boolean = false) = gate(WriteKind.LEAVE) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.setLeave(date, leave)
            if (!announce && !announceInBar) return@launch
            val was = before?.leave
            val text = when {
                leave != null && was != null && was.quarters == leave.quarters ->
                    "${Fmt.monthDay(date)} 휴가를 ${leave.kind.label}${Fmt.ro(leave.kind.label)} 바꿨어요"
                leave != null -> "${Fmt.monthDay(date)}에 ${leave.label}${Fmt.obj(leave.label)} 넣었어요"
                was != null -> "${Fmt.monthDay(date)} ${was.label}${Fmt.obj(was.label)} 뺐어요"
                else -> return@launch
            }
            val undo: suspend () -> Unit = { schedule.restoreDay(date, before) }
            if (announceInBar) _lastPaint.value = PaintUndo(text, undo) else _messages.emit(UndoMessage(text, undo = undo))
        }
    }

    /** Called once the review sheet was offered; Play decides whether it actually shows [OPS-20]. */
    fun markReviewAsked(moment: com.bamnat.app.review.InAppReview.Moment = com.bamnat.app.review.InAppReview.Moment.FIRST) {
        val today = Today.date().toEpochDay()
        container.settingsRepository.update {
            if (moment == com.bamnat.app.review.InAppReview.Moment.PAY) it.copy(payReviewDue = false, payReviewAsked = true, reviewAskedDay = today)
            else it.copy(reviewDue = false, reviewAsked = true, reviewAskedDay = today)
        }
        container.telemetry.event("review_asked")
    }

    fun setAlarmSkipped(date: LocalDate, skipped: Boolean, announce: Boolean = false, announceInBar: Boolean = false) = gate(WriteKind.ALARM) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.setAlarmSkipped(date, skipped)
            val text = "${Fmt.monthDay(date)} 알람을 ${if (skipped) "껐어요" else "켰어요"}"
            val undo: suspend () -> Unit = { schedule.restoreDay(date, before) }
            if (announceInBar) _lastPaint.value = PaintUndo(text, undo)
            else if (announce) _messages.emit(UndoMessage(text, undo = undo))
        }
    }

    fun resetDay(date: LocalDate) = gate(WriteKind.SHIFT) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.resetDay(date)
            if (before != null && !before.isEmpty) {
                _messages.emit(UndoMessage("${Fmt.monthDay(date)}을 패턴대로 되돌렸어요") {
                    schedule.restoreDay(date, before)
                })
            }
        }
    }

    fun setDayAlarms(date: LocalDate, alarms: List<DayAlarm>?) = gate(WriteKind.ALARM) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.setDayAlarms(date, alarms)
            // [CAL-10] Hand-set alarms replace the shift's; a wrong save is one tap from how it was.
            val text = when {
                alarms == null -> "${Fmt.monthDay(date)} 알람을 근무 알람으로 되돌렸어요"
                alarms.isEmpty() -> "${Fmt.monthDay(date)}은 알람이 울리지 않아요"
                else -> "${Fmt.monthDay(date)} 알람 ${alarms.size}개를 정했어요"
            }
            _messages.emit(UndoMessage(text) { schedule.restoreDay(date, before) })
        }
    }

    /** [CUST-01] 화면 꾸미기 changes the app's own settings, not the calendar's view of them. */
    fun updateSettings(change: (com.bamnat.app.data.AppSettings) -> com.bamnat.app.data.AppSettings) = container.settingsRepository.update(change)

    fun setAlarmsEnabled(on: Boolean) = container.settingsRepository.update { it.copy(alarmsEnabled = on) }

    /** [DAY-04] The day view's groups in the user's order; empty goes back to the default. */
    fun setDayGroupOrder(order: List<String>) = container.settingsRepository.update { it.copy(dayGroupOrder = order) }

    /** [CAL-03] What a tap on a date does on a phone. */
    fun setPhoneDayView(view: com.bamnat.app.data.PhoneDayView) = container.settingsRepository.update { it.copy(phoneDayView = view) }

    fun deleteMemo(memo: Memo) {
        viewModelScope.launch { memoRepo.delete(memo) }
    }

    private val _openMemo = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** [SCHED-01] A memo to open in the editor, from the snackbar's 일정으로 넣기. */
    val openMemo: SharedFlow<Long> = _openMemo.asSharedFlow()

    fun addQuickMemo(date: LocalDate, text: String) = gate(WriteKind.MEMO) {
        viewModelScope.launch {
            // [NOTE-01] A quick memo belongs to the calendar it was written on.
            val memo = memoRepo.addQuick(date, text, setOf(container.calendars.active.value)) ?: return@launch
            // [SCHED-01] A quick line that names a time is asked about, like a memo written in the editor.
            val found = com.bamnat.core.memo.MemoTime.find(memo, Today.dateTime()) ?: return@launch
            _messages.tryEmit(UndoMessage("‘${found.phrase}’, 일정으로 넣을까요?", actionLabel = "일정으로 넣기") {
                _openMemo.tryEmit(memo.id)
            })
        }
    }

    /**
     * [WORK-06] Ledger, jobs and sales for the calendar's day sections and cells. [MCAL-06] The calendar
     * on screen's own; each action keeps to the calendar it started on, its undo too.
     */
    private val money get() = container.calendars.money(container.calendars.active.value)
    val moneyBook: StateFlow<MoneyBook> = container.calendars.active.flatMapLatest { container.calendars.money(it).book }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoneyBook.EMPTY)

    /** [CAL-10] A new line can be taken back; a changed one goes back to what it was. */
    override fun saveEntry(entry: MoneyEntry) {
        val money = money
        container.appScope.launch {
            val before = if (entry.id == 0L) null else money.book.first().entries.firstOrNull { it.id == entry.id }
            val saved = money.save(entry)
            // [LEDG-18] When the category changed, offer the other lines from this place instead.
            val same = before?.let { money.samePlace(saved, it.category) }.orEmpty()
            if (same.isNotEmpty()) {
                _messages.tryEmit(UndoMessage("같은 곳 기록 ${same.size}건도 ‘${saved.category}’(으)로 바꿀까요?", actionLabel = "바꾸기") {
                    same.forEach { money.save(com.bamnat.core.money.Ledger.recategorize(it, saved.category)) }
                    _messages.tryEmit(UndoMessage("${same.size}건을 ‘${saved.category}’(으)로 옮겼어요") { money.saveAll(same) })
                })
                return@launch
            }
            _messages.tryEmit(
                if (before == null) UndoMessage("‘${saved.title}’ ${saved.kind.label}${Fmt.obj(saved.kind.label)} 적었어요") { money.delete(saved) }
                else UndoMessage("‘${saved.title}’ 기록을 고쳤어요") { money.save(before) },
            )
        }
    }
    // Read from the database, so it keeps its own collector (Eagerly): a derived value would have nothing to read.
    private val rules: StateFlow<Map<String, String>> = container.calendars.active.flatMapLatest { container.calendars.money(it).rules }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    override val categoryRules: Map<String, String> get() = rules.value
    override fun frequentAmounts(kind: com.bamnat.core.money.MoneyKind): List<Long> =
        com.bamnat.core.money.MoneyStats.frequentAmounts(moneyBook.value.entries, kind, Today.date())

    override fun deleteEntry(entry: MoneyEntry) {
        val money = money
        container.appScope.launch {
            money.delete(entry)
            _messages.tryEmit(UndoMessage("‘${entry.note.ifBlank { entry.category }}’ 기록을 지웠어요") { money.save(entry) })
        }
    }

    /** [CAL-10] A new job can be taken back; a changed one (paid, moved) goes back to what it was. */
    override fun saveJob(job: Job) {
        if (job.units != null) guideUsed("field.units")
        if (job.due != null) guideUsed("field.due")
        if (job.status != com.bamnat.core.money.JobStatus.CONFIRMED) guideUsed("field.status")
        val money = money
        container.appScope.launch {
            val before = if (job.id == 0L) null else money.job(job.id)
            val saved = money.save(job)
            _messages.tryEmit(
                if (before == null) UndoMessage("${Fmt.monthDay(saved.date)}에 ‘${saved.title}’ 일감을 적었어요") { money.delete(saved) }
                else UndoMessage("‘${saved.title}’ 일감을 고쳤어요") { money.save(before) },
            )
        }
    }

    override fun deleteJob(job: Job) {
        val money = money
        container.appScope.launch {
            money.delete(job)
            _messages.tryEmit(UndoMessage("‘${job.title}’ 일감을 지웠어요") { money.save(job) })
        }
    }

    /** [CAL-10] The day's sales as they were come back with undo, or go away when there were none. */
    override fun saveSales(sales: DailySales) {
        guideUsed("shop.dayClose")
        if (sales.cashCounted != null) guideUsed("shop.cash")
        val money = money
        val calendar = container.calendars.active.value
        container.appScope.launch {
            val before = money.book.first().sales.firstOrNull { it.date == sales.date }
            money.save(sales)
            // [TYPE-04] A day closed by itself for having no sales opens again once sales are written.
            com.bamnat.app.ui.work.ShopKeeper.salesSaved(container, calendar, sales.date, sales.sales)
            val text = when {
                sales.sales == 0L && sales.costs == 0L && sales.note.isBlank() -> "${Fmt.monthDay(sales.date)} 매출을 지웠어요"
                before == null -> "${Fmt.monthDay(sales.date)} 매출 ${Fmt.won(sales.sales)}을 적었어요"
                else -> "${Fmt.monthDay(sales.date)} 매출을 ${Fmt.won(sales.sales)}으로 고쳤어요"
            }
            _messages.tryEmit(UndoMessage(text) { money.save(before ?: DailySales(sales.date, 0, 0)) })
        }
    }

    fun runUndo(message: UndoMessage) {
        val undo = message.undo ?: return
        container.appScope.launch { undo() }
    }

    private companion object {
        const val VIEW_PREFS = "calendar_view"
        const val K_LIST = "list"
        const val K_FLOW_FOLDED = "flow_folded"
        const val K_HOLIDAYS = "holidays_kept"
        val FULL_LEAVE = Leave(LeaveKind.ANNUAL, 4)
    }
}
