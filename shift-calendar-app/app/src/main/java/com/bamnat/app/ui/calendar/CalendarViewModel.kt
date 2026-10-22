package com.bamnat.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.alarm.UpcomingAlarm
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.pay.named
import com.bamnat.app.tier.Tier
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.money.MoneyActions
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
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.pay.DayMinutes
import com.bamnat.core.pay.DayPay
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

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

    /** [MCAL-01] The user's calendars and the one on screen. */
    val calendars: StateFlow<List<com.bamnat.app.data.PersonalCalendar>> = container.calendars.calendars
    val activeCalendar: StateFlow<com.bamnat.app.data.PersonalCalendar> =
        container.calendars.activeCalendar.stateIn(viewModelScope, SharingStarted.Eagerly, container.calendars.current())

    /** [MCAL-04] The list's order, received calendars included. */
    val calendarOrder: StateFlow<List<String>> = container.calendars.order

    fun viewCalendar(id: String) {
        if (id != container.calendars.active.value) container.telemetry.event("calendar_switch", if (id == com.bamnat.app.data.PersonalCalendar.MAIN) "main" else "added")
        container.calendars.setActive(id)
    }

    val snapshot: StateFlow<ScheduleSnapshot?> =
        container.calendars.activeSnapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /**
     * [MCAL-01] An added calendar answers its own kind of work, so the calendar tab shows it as that
     * calendar works; everything else in settings is the app's.
     */
    val settings: StateFlow<AppSettings> = kotlinx.coroutines.flow.combine(container.settingsRepository.settings, activeCalendar) { s, cal ->
        if (cal.isMain) s else s.copy(workStyle = cal.style ?: s.workStyle, rosterMonthly = false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, container.settingsRepository.current.let { s ->
        container.calendars.current().let { cal -> if (cal.isMain) s else s.copy(workStyle = cal.style ?: s.workStyle, rosterMonthly = false) }
    })
    /** [CAL-05] What the calendar on screen is about: shifts, jobs or sales. */
    val focus: StateFlow<CalendarFocus> = settings.map { CalendarFocus.of(it.workStyle) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CalendarFocus.of(settings.value.workStyle))

    /** [CAL-13] An added calendar can be kept from ringing; the main one always rings. */
    val calendarAlarmsOn: StateFlow<Boolean> = activeCalendar.map { it.isMain || it.alarms }
        .stateIn(viewModelScope, SharingStarted.Eagerly, activeCalendar.value.let { it.isMain || it.alarms })

    /** [CAL-13] Turns the alarms of the added calendar on screen on or off, said back with undo. */
    fun setCalendarAlarms(on: Boolean) {
        val cal = activeCalendar.value
        if (cal.isMain) return
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

    /** [CAL-16] 오늘의 흐름 folded to one line by the user; kept on this phone until opened again. */
    private val _flowFolded = MutableStateFlow(runCatching { viewPrefs.getBoolean(K_FLOW_FOLDED, false) }.getOrDefault(false))
    val flowFolded: StateFlow<Boolean> = _flowFolded.asStateFlow()

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
     * without a pay setup or while pay is locked. Heavy enough to run off the main thread.
     */
    fun monthEstimate(profile: PayProfile?, snapshot: ScheduleSnapshot, month: YearMonth): Long? {
        if (profile == null || !com.bamnat.app.tier.FeatureGate.allows(tier.value, com.bamnat.app.tier.Feature.PAY_ENGINE)) return null
        return runCatching { monthPay(profile.named(snapshot.kinds), snapshot, month).total }.getOrNull()
    }

    val memoDates: StateFlow<Set<LocalDate>> =
        memoRepo.datesWithMemos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
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

    /** Pay setup and tier, so a day on the calendar shows what it adds to pay [PAYX-08]. [MCAL-06] The calendar on screen's own. */
    val payProfile: StateFlow<PayProfile?> = container.calendars.active.flatMapLatest { container.calendars.pay(it).profile }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.calendars.pay(container.calendars.active.value).profile.value)
    val tier: StateFlow<Tier> = container.featureGate.tier
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
        return payCalculator.calculate(profile, month, snapshot.resolver::resolve).also { monthPayCache = key to it }
    }

    /** [STAB-01] The last tap on 메모 the day's memo field has acted on. */
    var memoFocusUsed = 0

    /**
     * [EXTRA-02][EXTRA-03] The day's minutes by category and, with a pay setup, its pay and what each
     * piece of extra work adds ([withAmounts], when the user may see amounts). Heavy enough to run
     * off the main thread.
     */
    fun dayInsight(profile: PayProfile?, snapshot: ScheduleSnapshot, date: LocalDate, withAmounts: Boolean): DayInsight {
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

    private val _selected = MutableStateFlow(LocalDate.now())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()

    /** [NAV-01] The month on screen, shared with 돈 and 근무·급여. */
    val shownMonth: StateFlow<YearMonth> = container.shownMonth.month
    fun showMonth(month: YearMonth) = container.shownMonth.show(month)

    private val _paint = MutableStateFlow<PaintTool?>(null)
    val paint: StateFlow<PaintTool?> = _paint.asStateFlow()

    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    fun memosOn(date: LocalDate): Flow<List<Memo>> = memoRepo.memosOn(date)

    /** [MKT-08] What was changed on [date], newest first. */
    fun changesOn(date: LocalDate): Flow<List<com.bamnat.app.data.DayChange>> = schedule.changesOn(date)

    /**
     * [SLP-01][MKT-07] Rest-gap signs for [month]; the days just before it are read too, so a
     * night at the end of last month still counts against the 1st.
     */
    fun restIssues(snapshot: ScheduleSnapshot, month: YearMonth, strict: Boolean = false): List<RestCheck.Issue> {
        val from = month.atDay(1).minusDays(RestCheck.NIGHT_STREAK.toLong())
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(month.atEndOfMonth()) }
            .map(snapshot.resolver::resolve).toList()
        return RestCheck.check(days, strict).filter { YearMonth.from(it.date) == month }
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
            settings.restCheck, settings.strictRest,
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
    fun fellAsleep() {
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
    fun wokeUp(at: LocalDateTime? = null) {
        container.appScope.launch {
            val millis = at?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            val sleep = container.sleepRepository.wokeUp(millis) ?: return@launch
            val minutes = sleep.minutes ?: 0
            val text = if (minutes > SleepDebt.MAX_SLEEP_MINUTES) "16시간이 넘게 열려 있던 기록이라 수면 통계에서 뺐어요"
            else "${HoursFormat.korean(minutes.toInt())} 잤어요"
            _messages.emit(UndoMessage(text) { container.sleepRepository.reopen(sleep.id) })
        }
    }
    fun deleteSleep(id: Long) { container.appScope.launch { container.sleepRepository.delete(id) } }

    /** Rest-gap signs that fall on [date]. */
    fun restIssuesOn(snapshot: ScheduleSnapshot, date: LocalDate, strict: Boolean = false): List<RestCheck.Issue> {
        val days = (RestCheck.NIGHT_STREAK.toLong() downTo 0).map { snapshot.resolver.resolve(date.minusDays(it)) }
        return RestCheck.check(days, strict).filter { it.date == date }
    }

    /** [FEAT-17] Memos that belong to a date, for the list under the calendar and memo-first cells. */
    val datedMemos: StateFlow<List<Memo>> = memoRepo.memos
        .map { list -> list.filter { it.date != null && !it.inTrash && !it.isBlank }.sortedWith(compareBy({ it.date }, { -it.updatedAtMillis })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun logShare() = container.telemetry.event("share_month_image")

    fun select(date: LocalDate) {
        _selected.value = date
    }

    fun setPaint(tool: PaintTool?) {
        _paint.value = tool
        _mark.value = null
        _rangeFrom.value = null
        _lastPaint.value = null
    }

    /** [CAL-09] Leave or 알람 끄기 chosen in quick input; null goes back to the shift stamps. */
    private val _mark = MutableStateFlow<DayMark?>(null)
    val mark: StateFlow<DayMark?> = _mark.asStateFlow()

    fun setMark(mark: DayMark?) {
        if (_paint.value == null) return
        _paint.value = PaintTool.Pick
        _mark.value = mark
        _rangeFrom.value = null
        _lastPaint.value = null
    }

    /** [CAL-09] The first day of a range, held by a long press in quick input; the next tap paints up to it. */
    private val _rangeFrom = MutableStateFlow<LocalDate?>(null)
    val rangeFrom: StateFlow<LocalDate?> = _rangeFrom.asStateFlow()

    /** True while quick input has something to stamp: a shift, a day off, the pattern, leave or 알람 끄기. */
    val canStamp: Boolean get() = _mark.value != null || _paint.value?.let(::stampOf) != null

    /** [CAL-09] A long press in quick input starts a range from [date]. */
    fun startRange(date: LocalDate) {
        if (!canStamp) return
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
        val target = _paint.value?.let(::stampOf) ?: return
        _lastPaint.value = null
        // Stamping the same shift twice undoes it, so a mis-tap is fixed with one more tap.
        // Decided inside the write so quick double taps see each other's result.
        viewModelScope.launch { changeShift(date) { current -> if (current == target) ShiftOverride.None else target } }
    }

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
    fun paintDays(dates: List<LocalDate>) {
        _mark.value?.let { paintMark(dates.distinct(), it); return }
        val tool = _paint.value ?: return
        val target = stampOf(tool) ?: return
        val name = when (tool) {
            is PaintTool.Shift -> snapshot.value?.typesById?.get(tool.shiftTypeId)?.name ?: return
            PaintTool.Off -> "쉬는 날"
            else -> null
        }
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
     * Sets the shift for a day. Choosing what the pattern already gives stores nothing. [CAL-10] Said
     * back with undo, as every change on a day is.
     */
    fun assign(date: LocalDate, override: ShiftOverride, announce: Boolean = true) {
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
    fun assignQuick(day: ResolvedDay, override: ShiftOverride, snapshot: ScheduleSnapshot) {
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

    /** [EXTRA-01] The roster stays a day off and the shift goes in as a 대근 of its hours, said back with undo. */
    fun coverInstead(date: LocalDate, type: ShiftType, snapshot: ScheduleSnapshot) {
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

    fun setCustomTimes(date: LocalDate, start: LocalTime, end: LocalTime) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.updateDay(date) { it.copy(customStart = start, customEnd = end) }
            // [CAL-10] A time picked by mistake is one tap from how it was.
            _messages.emit(UndoMessage("${Fmt.monthDay(date)} 근무 시간을 바꿨어요 (${Fmt.timeRange(start, end)})") { schedule.restoreDay(date, before) })
        }
    }

    fun setBreak(date: LocalDate, minutes: Int?) {
        viewModelScope.launch { schedule.updateDay(date) { it.copy(customBreakMinutes = minutes) } }
    }

    fun clearCustomTimes(date: LocalDate) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.setCustomTimes(date, null, null, null)
            _messages.emit(UndoMessage("${Fmt.monthDay(date)} 근무를 기본 시간으로 되돌렸어요") { schedule.restoreDay(date, before) })
        }
    }

    /** [EXTRA-01] Adds a piece of extra work, said back with undo: "10월 17일에 야간 대근을 넣었어요". */
    fun addExtra(date: LocalDate, entry: ExtraEntry, name: String) {
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
    fun updateExtra(date: LocalDate, index: Int, entry: ExtraEntry) {
        viewModelScope.launch { schedule.updateExtra(date, index, entry) }
    }

    fun removeExtra(date: LocalDate, index: Int, name: String) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.removeExtra(date, index)
            _messages.emit(UndoMessage("$name${Fmt.obj(name)} 뺐어요") { schedule.restoreDay(date, before) })
        }
    }

    /** [EXTRA-01] A one-tap choice from the long-press menu or the day's chips: added, or taken off when the day has it. */
    fun toggleExtra(day: ResolvedDay, choice: ExtraChoice) {
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
    fun setLeave(date: LocalDate, leave: Leave?, announce: Boolean = true, announceInBar: Boolean = false) {
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
    fun markReviewAsked() {
        container.settingsRepository.update { it.copy(reviewDue = false, reviewAsked = true) }
        container.telemetry.event("review_asked")
    }

    fun setAlarmSkipped(date: LocalDate, skipped: Boolean, announce: Boolean = false, announceInBar: Boolean = false) {
        viewModelScope.launch {
            val schedule = this@CalendarViewModel.schedule // [MCAL-01] an undo stays with the calendar it was made in
            val before = schedule.setAlarmSkipped(date, skipped)
            val text = "${Fmt.monthDay(date)} 알람을 ${if (skipped) "껐어요" else "켰어요"}"
            val undo: suspend () -> Unit = { schedule.restoreDay(date, before) }
            if (announceInBar) _lastPaint.value = PaintUndo(text, undo)
            else if (announce) _messages.emit(UndoMessage(text, undo = undo))
        }
    }

    fun resetDay(date: LocalDate) {
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

    fun setDayAlarms(date: LocalDate, alarms: List<DayAlarm>?) {
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

    fun addQuickMemo(date: LocalDate, text: String) {
        viewModelScope.launch {
            val memo = memoRepo.addQuick(date, text) ?: return@launch
            // [SCHED-01] A quick line that names a time is asked about, like a memo written in the editor.
            val found = com.bamnat.core.memo.MemoTime.find(memo, java.time.LocalDateTime.now()) ?: return@launch
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
            _messages.tryEmit(
                if (before == null) UndoMessage("‘${saved.title}’ ${saved.kind.label}${Fmt.obj(saved.kind.label)} 적었어요") { money.delete(saved) }
                else UndoMessage("‘${saved.title}’ 기록을 고쳤어요") { money.save(before) },
            )
        }
    }
    private val rules: StateFlow<Map<String, String>> = container.calendars.active.flatMapLatest { container.calendars.money(it).rules }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    override val categoryRules: Map<String, String> get() = rules.value
    override fun frequentAmounts(kind: com.bamnat.core.money.MoneyKind): List<Long> =
        com.bamnat.core.money.MoneyStats.frequentAmounts(moneyBook.value.entries, kind, java.time.LocalDate.now())

    override fun deleteEntry(entry: MoneyEntry) {
        val money = money
        container.appScope.launch {
            money.delete(entry)
            _messages.tryEmit(UndoMessage("‘${entry.note.ifBlank { entry.category }}’ 기록을 지웠어요") { money.save(entry) })
        }
    }

    /** [CAL-10] A new job can be taken back; a changed one (paid, moved) goes back to what it was. */
    override fun saveJob(job: Job) {
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
        val money = money
        container.appScope.launch {
            val before = money.book.first().sales.firstOrNull { it.date == sales.date }
            money.save(sales)
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
