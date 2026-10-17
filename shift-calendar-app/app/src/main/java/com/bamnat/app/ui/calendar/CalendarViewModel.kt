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
import kotlinx.coroutines.flow.Flow
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
    data class Shift(val shiftTypeId: Long) : PaintTool
    /** A day off when the user has no off-type shift. */
    data object Off : PaintTool
    /** Back to the pattern. */
    data object Clear : PaintTool
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

class CalendarViewModel(private val container: AppContainer) : ViewModel(), MoneyActions {
    private val schedule = container.scheduleRepository
    private val memoRepo = container.memoRepository

    val snapshot: StateFlow<ScheduleSnapshot?> =
        schedule.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val memoDates: StateFlow<Set<LocalDate>> =
        memoRepo.datesWithMemos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val upcomingAlarm: StateFlow<UpcomingAlarm?> = container.alarmScheduler.upcoming

    /** [EXTRA-05] Pieces of extra work moved from an older record that still need a look. */
    val extrasToReview: StateFlow<Int> =
        schedule.extrasToReview.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Pay setup and tier, so a day on the calendar shows what it adds to pay [PAYX-08]. */
    val payProfile: StateFlow<PayProfile?> = container.payStore.profile
    val tier: StateFlow<Tier> = container.featureGate.tier
    private val payCalculator = PayCalculator()

    // Moving from day to day in one month reuses that month's result instead of recalculating it.
    private var monthPayCache: Pair<List<Any>, MonthPay>? = null

    private fun monthPay(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): MonthPay = synchronized(this) {
        val key = listOf(profile, snapshot, month)
        monthPayCache?.takeIf { it.first == key }?.second
            ?: payCalculator.calculate(profile, month, snapshot.resolver::resolve).also { monthPayCache = key to it }
    }

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
        SleepPlan.tips(SleepSchedule.of(snapshot.resolver, date, date.plusDays(1), settings.alarmsEnabled,
            settings.sleepTargetMinutes, settings.commuteMinutes), date)

    /** [SLP-14] 오늘의 흐름 at [now]. */
    fun todayFlow(snapshot: ScheduleSnapshot, settings: AppSettings, log: List<SleepEntry>, now: LocalDateTime): TodayFlow.Card =
        TodayFlow.of(now, snapshot.resolver, settings.alarmsEnabled, log, settings.sleepTargetMinutes, settings.commuteMinutes,
            settings.restCheck, settings.strictRest)

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
    }

    /** A tap on a calendar cell: selects it, and stamps it when quick input is on. */
    fun onDayTapped(date: LocalDate) {
        _selected.value = date
        val tool = _paint.value ?: return
        val target = when (tool) {
            is PaintTool.Shift -> ShiftOverride.Shift(tool.shiftTypeId)
            PaintTool.Off -> ShiftOverride.Off
            PaintTool.Clear -> ShiftOverride.None
        }
        // Stamping the same shift twice undoes it, so a mis-tap is fixed with one more tap.
        // Decided inside the write so quick double taps see each other's result.
        viewModelScope.launch { changeShift(date) { current -> if (current == target) ShiftOverride.None else target } }
    }

    /** Sets the shift for a day. Choosing what the pattern already gives stores nothing. */
    fun assign(date: LocalDate, override: ShiftOverride) {
        viewModelScope.launch { changeShift(date) { override } }
    }

    /**
     * [DAY-03] The shift chosen from a day's long-press menu, said back with undo. A working shift on
     * a day the roster gives off is most often a 대근, so then the snackbar offers that instead [EXTRA-01].
     */
    fun assignQuick(day: ResolvedDay, override: ShiftOverride, snapshot: ScheduleSnapshot) {
        val date = day.date
        val type = (override as? ShiftOverride.Shift)?.let { snapshot.typesById[it.shiftTypeId] }
        viewModelScope.launch {
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

    private suspend fun changeShift(date: LocalDate, choose: (ShiftOverride) -> ShiftOverride): DayEntry? {
        val pattern = snapshot.value?.resolver?.patternOn(date)
        return schedule.updateDay(date) { entry ->
            val patternId = pattern?.shiftTypeIdOn(date)
            val patternCovers = pattern != null
            val next = when (val o = choose(entry.override)) {
                is ShiftOverride.Shift -> if (patternCovers && patternId == o.shiftTypeId) ShiftOverride.None else o
                ShiftOverride.Off -> if (patternCovers && patternId == null) ShiftOverride.None else o
                ShiftOverride.None -> o
            }
            if (next == entry.override) {
                entry
            } else {
                // Times set for the old shift do not carry over to a different one.
                entry.copy(override = next, customStart = null, customEnd = null, customBreakMinutes = null)
            }
        }
    }

    fun setCustomTimes(date: LocalDate, start: LocalTime, end: LocalTime) {
        viewModelScope.launch {
            schedule.updateDay(date) { it.copy(customStart = start, customEnd = end) }
        }
    }

    fun setBreak(date: LocalDate, minutes: Int?) {
        viewModelScope.launch { schedule.updateDay(date) { it.copy(customBreakMinutes = minutes) } }
    }

    fun clearCustomTimes(date: LocalDate) {
        viewModelScope.launch {
            schedule.setCustomTimes(date, null, null, null)
        }
    }

    /** [EXTRA-01] Adds a piece of extra work, said back with undo: "10월 17일에 야간 대근을 넣었어요". */
    fun addExtra(date: LocalDate, entry: ExtraEntry, name: String) {
        viewModelScope.launch {
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
            val n = schedule.convertShiftSwapCovers(days.map { it.date })
            if (n > 0) _messages.emit(UndoMessage("${n}일을 대근으로 바꿨어요") { days.forEach { schedule.restoreDay(it.date, it) } })
        }
    }

    /** [EXTRA-05] The user keeps those days as they are; not asked again. */
    fun keepSwapCovers() = container.settingsRepository.update { it.copy(swapCoversAnswered = true) }

    /** 연차·반차 for a day; [announce] says it back with undo when it was set without the day open [DAY-03]. */
    fun setLeave(date: LocalDate, leave: Leave?, announce: Boolean = false) {
        viewModelScope.launch {
            val before = schedule.setLeave(date, leave)
            if (!announce) return@launch
            val text = leave?.let { "${Fmt.monthDay(date)}에 ${it.label}${Fmt.obj(it.label)} 넣었어요" }
                ?: before?.leave?.let { "${Fmt.monthDay(date)} ${it.label}${Fmt.obj(it.label)} 뺐어요" } ?: return@launch
            _messages.emit(UndoMessage(text) { schedule.restoreDay(date, before) })
        }
    }

    /** Called once the review sheet was offered; Play decides whether it actually shows [OPS-20]. */
    fun markReviewAsked() {
        container.settingsRepository.update { it.copy(reviewDue = false, reviewAsked = true) }
        container.telemetry.event("review_asked")
    }

    fun setAlarmSkipped(date: LocalDate, skipped: Boolean, announce: Boolean = false) {
        viewModelScope.launch {
            val before = schedule.setAlarmSkipped(date, skipped)
            if (announce) {
                _messages.emit(UndoMessage("${Fmt.monthDay(date)} 알람을 ${if (skipped) "껐어요" else "켰어요"}") { schedule.restoreDay(date, before) })
            }
        }
    }

    fun resetDay(date: LocalDate) {
        viewModelScope.launch {
            val before = schedule.resetDay(date)
            if (before != null && !before.isEmpty) {
                _messages.emit(UndoMessage("${Fmt.monthDay(date)}을 패턴대로 되돌렸어요") {
                    schedule.restoreDay(date, before)
                })
            }
        }
    }

    fun setDayAlarms(date: LocalDate, alarms: List<DayAlarm>?) {
        viewModelScope.launch { schedule.setDayAlarms(date, alarms) }
    }

    fun setAlarmsEnabled(on: Boolean) = container.settingsRepository.update { it.copy(alarmsEnabled = on) }

    fun deleteMemo(memo: Memo) {
        viewModelScope.launch { memoRepo.delete(memo) }
    }

    fun addQuickMemo(date: LocalDate, text: String) {
        viewModelScope.launch { memoRepo.addQuick(date, text) }
    }

    /** [WORK-06] Ledger, jobs and sales for the calendar's day sections and cells. */
    private val money = container.moneyRepository
    val moneyBook: StateFlow<MoneyBook> = money.book.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoneyBook.EMPTY)

    override fun saveEntry(entry: MoneyEntry) { container.appScope.launch { money.save(entry) } }
    private val rules: StateFlow<Map<String, String>> = money.rules.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    override val categoryRules: Map<String, String> get() = rules.value
    override fun frequentAmounts(kind: com.bamnat.core.money.MoneyKind): List<Long> =
        com.bamnat.core.money.MoneyStats.frequentAmounts(moneyBook.value.entries, kind, java.time.LocalDate.now())

    override fun deleteEntry(entry: MoneyEntry) {
        container.appScope.launch {
            money.delete(entry)
            _messages.tryEmit(UndoMessage("‘${entry.note.ifBlank { entry.category }}’ 기록을 지웠어요") { money.save(entry) })
        }
    }

    override fun saveJob(job: Job) { container.appScope.launch { money.save(job) } }

    override fun deleteJob(job: Job) {
        container.appScope.launch {
            money.delete(job)
            _messages.tryEmit(UndoMessage("‘${job.title}’ 일감을 지웠어요") { money.save(job) })
        }
    }

    override fun saveSales(sales: DailySales) { container.appScope.launch { money.save(sales) } }

    fun runUndo(message: UndoMessage) {
        val undo = message.undo ?: return
        container.appScope.launch { undo() }
    }
}
