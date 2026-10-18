package com.bamnat.app.ui.setup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.company.CompanyPage
import com.bamnat.app.company.SharedSetup
import com.bamnat.app.data.BackupException
import com.bamnat.app.data.BackupSummary
import com.bamnat.app.data.CompanyShare
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.alarm.PlannedAlarm
import com.bamnat.app.ui.calendar.PaintTool
import com.bamnat.app.ui.settings.LockedBackup
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.PatternPreset
import com.bamnat.core.schedule.PatternPresets
import com.bamnat.core.schedule.ShiftBundles
import com.bamnat.core.schedule.ShiftRole
import com.bamnat.core.setup.CompanyTemplate
import com.bamnat.core.setup.CompanyTemplates
import com.bamnat.core.setup.SetupDraft
import com.bamnat.core.setup.SetupSource
import com.bamnat.core.setup.SetupStep
import com.bamnat.core.setup.TodayQuestion
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/**
 * [SETUP-01][SETUP-02] The first setup, one question at a time. Every answer goes to the draft on the
 * phone and nothing reaches the calendar until the last step saves it all at once.
 */
class SetupViewModel(
    private val c: AppContainer,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {
    private val store = c.setupDrafts
    private val current: SetupDraft get() = store.draft.value ?: SetupDraft()

    val draft: StateFlow<SetupDraft> = store.draft.map { it ?: SetupDraft() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, current)

    init {
        // A draft kept from an older build, or cut short, can sit on a step it no longer has.
        val d = current
        if (d.step !in d.steps) store.save(d.copy(step = d.steps.firstOrNull { !d.canLeave(it) } ?: SetupStep.SUMMARY))
    }

    /** The week as the calendar shows it, for the month previews. */
    val weekStart: DayOfWeek get() = c.settingsRepository.current.weekStart

    /** [COMM-01] With a company server the company step searches it; without one it offers templates only. */
    val companySearch: Boolean get() = c.companyApi.enabled

    val today: LocalDate get() = now().toLocalDate()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** From the summary's 고치기: once that answer is in, back to the summary past what is still right. */
    private val _fixing = MutableStateFlow(false)
    val fixing: StateFlow<Boolean> = _fixing.asStateFlow()

    /** [SETUP-01] In the small hours the question is about yesterday; the user can answer for today instead. */
    private val _todayBased = MutableStateFlow(false)
    val todayBased: StateFlow<Boolean> = _todayBased.asStateFlow()

    /** The day "오늘 무슨 근무예요?" asks about: yesterday while last night's shift may still be on. */
    fun questionDate(d: SetupDraft): LocalDate =
        if (_todayBased.value) today else TodayQuestion.questionDate(now(), d.types)

    /** In the small hours, when the question would be about yesterday unless the user says otherwise. */
    fun earlyHours(d: SetupDraft): Boolean = TodayQuestion.questionDate(now(), d.types) != today

    fun answerForToday(on: Boolean) {
        _todayBased.value = on
        update { it.copy(anchor = null, todayConfirmed = false) }
    }

    private fun update(transform: (SetupDraft) -> SetupDraft) {
        val before = current
        val next = transform(before)
        if (next != before) store.save(next)
    }

    // ---- moving between steps ----

    fun next() {
        val d = current
        if (!d.canLeave()) return
        if (d.step == SetupStep.SUMMARY) return save()
        val ahead = d.steps.dropWhile { it != d.step }.drop(1)
        val target = if (_fixing.value) ahead.firstOrNull { it == SetupStep.SUMMARY || !d.canLeave(it) } else ahead.firstOrNull()
        if (target == SetupStep.SUMMARY) _fixing.value = false
        update { it.copy(step = target ?: SetupStep.SUMMARY) }
    }

    /** One step back; while fixing from the summary, straight back to it. */
    fun back() {
        if (_fixing.value) {
            _fixing.value = false
            update { it.copy(step = SetupStep.SUMMARY) }
            return
        }
        update { d -> d.copy(step = d.previous() ?: d.step) }
    }

    fun fix(step: SetupStep) {
        if (step !in current.steps) return
        _fixing.value = true
        update { it.copy(step = step) }
    }

    /** "나중에": the app opens on the calendar with a card to go on from here. */
    fun later() {
        c.telemetry.event("setup_later", current.step.name)
        update { it.copy(later = true) }
    }

    // ---- 1. how the user works ----

    fun pickStyle(style: WorkStyle, roster: Boolean = false) {
        val d = current
        when (style) {
            // Field and shop work are written day by day: nothing more to ask.
            WorkStyle.FIELD, WorkStyle.BUSINESS -> save(SetupDraft(style = style))
            else -> {
                val same = d.style == style && d.rosterMonthly == (roster && style == WorkStyle.SHIFT)
                val start = when {
                    d.style == style -> d.copy(rosterMonthly = roster && style == WorkStyle.SHIFT)
                    else -> fresh(style, roster).copy(companyName = d.companyName)
                }
                // The same answer while fixing changes nothing: straight back to the summary.
                val target = if (same && _fixing.value) SetupStep.SUMMARY else start.steps.getOrElse(1) { SetupStep.SUMMARY }
                _fixing.value = false
                c.telemetry.event("setup_style", style.name + if (roster) "_ROSTER" else "")
                store.save(start.copy(step = target))
            }
        }
    }

    private fun fresh(style: WorkStyle, roster: Boolean): SetupDraft = when (style) {
        WorkStyle.DAY -> SetupDraft(style = style).withShifts(null, ShiftBundles.weekday)
        WorkStyle.HOURLY -> SetupDraft(style = style).withShifts(null, ShiftBundles.hourly)
        else -> SetupDraft(style = style, rosterMonthly = roster)
    }

    // ---- files: a coworker's company setup, or the user's own backup ----

    private val _pendingRestore = MutableStateFlow<Pair<String, BackupSummary>?>(null)
    /** A backup read and checked, waiting for the user to say yes. */
    val pendingRestore: StateFlow<Pair<String, BackupSummary>?> = _pendingRestore.asStateFlow()
    private val _locked = MutableStateFlow<LockedBackup?>(null)
    val locked: StateFlow<LockedBackup?> = _locked.asStateFlow()

    /** [SET-01] One way in for both kinds of file, as in settings. */
    fun readFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                }
                when {
                    CompanyShare.isCompanyFile(text) -> importCompany(text)
                    c.backupManager.isSealed(text) -> _locked.value = LockedBackup(text)
                    else -> _pendingRestore.value = text to c.backupManager.summarize(text)
                }
            }.onFailure { e -> _message.value = (e as? BackupException)?.message ?: "파일을 읽을 수 없어요" }
        }
    }

    fun unlock(password: CharArray) {
        val locked = _locked.value ?: return
        _locked.value = locked.copy(working = true, error = null)
        viewModelScope.launch {
            runCatching {
                val json = withContext(Dispatchers.Default) { c.backupManager.unseal(locked.text, password) }
                json to c.backupManager.summarize(json)
            }.onSuccess {
                _locked.value = null
                _pendingRestore.value = it
            }.onFailure { e ->
                _locked.value = locked.copy(working = false, error = (e as? BackupException)?.message ?: "백업 파일을 읽을 수 없어요")
            }
            password.fill(' ')
        }
    }

    fun cancelFile() {
        _locked.value = null
        _pendingRestore.value = null
    }

    /** The user's own backup: everything comes back as it was and the first setup is not needed. */
    fun confirmRestore() {
        val json = _pendingRestore.value?.first ?: return
        _pendingRestore.value = null
        _saving.value = true
        c.appScope.launch {
            runCatching {
                c.backupManager.restore(json)
                c.telemetry.event("setup_restore")
                // A backup from before the kind of work was asked, without a pattern: the setup goes on.
                if (c.settingsRepository.current.workStyle != null) store.clear()
            }.onFailure { e -> _message.value = (e as? BackupException)?.message ?: "백업을 불러오지 못했어요" }
            _saving.value = false
        }
    }

    /** [COMM-00][SETUP-02] A coworker's company file: its shifts and order, with their 조 when it says one. */
    internal fun importCompany(text: String) {
        val shared = c.companyShare.read(text)
        val d = current
        val style = d.style?.takeIf { it.usesPattern || it == WorkStyle.HOURLY } ?: if (shared.order.isNotEmpty()) WorkStyle.SHIFT else WorkStyle.DAY
        val base = if (d.style == style) d else SetupDraft(style = style, companyName = d.companyName)
        val source = SetupSource(SetupSource.Kind.FILE, text, name = shared.company, team = shared.team, anchor = shared.anchor)
        val withFile = base.withShared(source, shared.types, shared.order)
        _fixing.value = false
        c.telemetry.event("setup_file", if (shared.team != null) "team" else "")
        store.save(withFile.copy(step = SetupStep.TIMES))
    }

    // ---- 2. the company ----

    val templates: List<CompanyTemplate> get() = CompanyTemplates.forStyle(current.style)

    fun pickTemplate(t: CompanyTemplate) {
        val text = c.companyShare.templateText(t)
        // A template says whether its roster comes every month (병원 간호) or its order repeats.
        val roster = when {
            current.style != WorkStyle.SHIFT -> null
            t.roster -> true
            t.order.isNotEmpty() -> false
            else -> null
        }
        useShared(SetupSource(SetupSource.Kind.TEMPLATE, text, id = t.id, name = t.name), text, roster)
        c.telemetry.event("setup_template", t.id)
    }

    /** [COMM-01] A coworkers' setup from the company server, without its 조: today's shift is asked next. */
    fun pickServer(page: CompanyPage, setup: SharedSetup) {
        useShared(
            SetupSource(SetupSource.Kind.SERVER, setup.text, id = page.id, setupId = setup.id, name = page.name, dept = setup.dept,
                version = setup.version, adopted = setup.adopted),
            setup.text,
        )
        c.telemetry.event("company_apply", if (setup.adopted) "adopted" else "pending")
    }

    private fun useShared(source: SetupSource, text: String, roster: Boolean? = null) {
        val shared = try {
            c.companyShare.read(text)
        } catch (e: BackupException) {
            _message.value = e.message ?: "설정을 읽지 못했어요"
            return
        }
        // Only a coworker's own file says which 조 it is; a template or the server does not.
        val base = roster?.let { current.copy(rosterMonthly = it) } ?: current
        val d = base.withShared(source.copy(team = null, anchor = null), shared.types, shared.order)
        // New shifts are a new setup from here on, not a fix of one answer.
        _fixing.value = false
        store.save(d.copy(step = d.steps.dropWhile { it != SetupStep.COMPANY }.getOrElse(1) { SetupStep.SUMMARY }))
    }

    /**
     * The order the shared setup came with, by this draft's shift ids (given in the file's order when
     * it was read, so a shift renamed since still matches); null without one.
     */
    fun sourceOrder(d: SetupDraft): List<Long?>? {
        val text = d.source?.text ?: return null
        val shared = runCatching { c.companyShare.read(text) }.getOrNull() ?: return null
        if (shared.order.isEmpty()) return null
        val ids = shared.types.mapIndexed { i, t -> t.name to -(i + 1L) }.toMap()
        return shared.order.map { name -> name?.let(ids::get) }
    }

    /** Back to choosing: the shifts start again from the kind of work. */
    fun clearSource() {
        val d = current
        val style = d.style ?: return
        _fixing.value = false
        store.save(fresh(style, d.rosterMonthly).copy(companyName = d.companyName, step = SetupStep.COMPANY))
    }

    fun setCompanyName(name: String) = update { it.copy(companyName = name.take(40)) }

    // ---- 3. bundles ----

    fun pickBundle(key: String) {
        // The same answer again keeps the times already changed.
        if (current.bundle == key && current.types.isNotEmpty()) return update { it.copy(step = it.next() ?: SetupStep.SUMMARY) }
        val shifts = ShiftBundles.byKey(key)?.shifts ?: ShiftBundles.custom
        val d = current.withShifts(ShiftBundles.byKey(key)?.key ?: SetupDraft.CUSTOM, shifts)
        _fixing.value = false
        c.telemetry.event("setup_bundle", key)
        store.save(d.copy(step = d.next() ?: SetupStep.SUMMARY))
    }

    // ---- 4. times ----

    fun saveType(type: ShiftType) = update { d ->
        if (d.types.any { it.id == type.id }) {
            d.copy(types = d.types.map { if (it.id == type.id) type else it }, roles = d.roles + (type.id to (d.roles[type.id]?.takeIf { r -> r.isOff == type.isOff } ?: ShiftRole.guess(type))))
        } else {
            d.copy(types = d.types + type.copy(sortOrder = d.types.size), roles = d.roles + (type.id to ShiftRole.guess(type)))
        }
    }

    /** A new work shift to fill in, after the work shifts there are. */
    fun newType(): ShiftType {
        val d = current
        return ShiftType(d.nextTypeId(), "", "", NEW_COLORS[d.types.size % NEW_COLORS.size], LocalTime.of(9, 0), LocalTime.of(18, 0), breakMinutes = 60)
    }

    fun removeType(id: Long) = update { d ->
        d.copy(
            types = d.types.filter { it.id != id }, roles = d.roles - id,
            rotation = d.rotation.map { if (it == id) null else it }, weekly = d.weekly.map { if (it == id) null else it },
            painted = d.painted.filterValues { it != id }, alarmByType = d.alarmByType - id,
        )
    }

    /** "네, 맞아요". */
    fun confirmTimes() {
        update { it.copy(timesConfirmed = true) }
        next()
    }

    // ---- 5. the order ----

    /** The usual orders the shifts can fill, from the bundle or from the parts the shifts play. */
    fun presets(d: SetupDraft): List<PatternPreset> =
        ShiftBundles.byKey(d.bundle)?.presets ?: PatternPresets.forCodes(d.roles.filterKeys { id -> d.workTypes.any { it.id == id } }.values.map { it.code }.toSet())

    fun pickPreset(preset: PatternPreset) = update { d ->
        d.copy(rotation = PatternPresets.toSteps(preset, d.roles), anchor = null, todayConfirmed = false)
    }

    fun setRotation(steps: List<Long?>) = update { it.copy(rotation = steps.take(MAX_STEPS), anchor = null, todayConfirmed = false) }

    fun setWeekly(day: Int, id: Long?) = update { d -> d.copy(weekly = d.weekly.mapIndexed { i, v -> if (i == day) id else v }) }

    fun setNoFixedDays(on: Boolean) = update { it.copy(noFixedDays = on) }

    // [SETUP-02] Painting a monthly roster.
    private val _paintMonth = MutableStateFlow(YearMonth.from(now()))
    val paintMonth: StateFlow<YearMonth> = _paintMonth.asStateFlow()
    private val _paintTool = MutableStateFlow<PaintTool>(PaintTool.Pick)
    val paintTool: StateFlow<PaintTool> = _paintTool.asStateFlow()
    private val _paintUndo = MutableStateFlow<Map<LocalDate, Long?>?>(null)
    val canUndoPaint: StateFlow<Boolean> = _paintUndo.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun showPaintMonth(month: YearMonth) {
        _paintMonth.value = month
    }

    fun setPaintTool(tool: PaintTool) {
        _paintTool.value = if (_paintTool.value == tool) PaintTool.Pick else tool
    }

    fun paint(dates: List<LocalDate>) {
        val tool = _paintTool.value
        if (tool == PaintTool.Pick || dates.isEmpty()) return
        _paintUndo.value = current.painted
        update { d ->
            d.copy(painted = when (tool) {
                is PaintTool.Shift -> d.painted + dates.associateWith { tool.shiftTypeId }
                PaintTool.Off -> d.painted + dates.associateWith { null }
                else -> d.painted - dates.toSet()
            })
        }
    }

    /** A tap on one day: paints it, or takes the paint off when the day already has what is chosen. */
    fun paintTap(date: LocalDate) {
        val d = current
        val same = when (val tool = _paintTool.value) {
            PaintTool.Pick -> {
                _message.value = "위에서 근무를 먼저 고르세요"
                return
            }
            is PaintTool.Shift -> date in d.painted && d.painted[date] == tool.shiftTypeId
            PaintTool.Off -> date in d.painted && d.painted[date] == null
            PaintTool.Clear -> false
        }
        if (!same) return paint(listOf(date))
        _paintUndo.value = d.painted
        update { it.copy(painted = it.painted - date) }
    }

    fun undoPaint() {
        val before = _paintUndo.value ?: return
        _paintUndo.value = null
        update { it.copy(painted = before) }
    }

    // ---- 6. today ----

    /** Today's answer: [step] of the order on [date]. */
    fun answerToday(date: LocalDate, step: Int) = update { it.copy(anchor = TodayQuestion.anchor(date, step), todayConfirmed = false) }

    /** [SETUP-02] Same 조 as the coworker who sent the file: their order lines up as it is. */
    fun sameTeam() = update { d -> d.copy(anchor = d.source?.anchor, todayConfirmed = false) }

    fun clearToday() = update { it.copy(anchor = null, todayConfirmed = false) }

    /** "회사 근무표와 같아요". */
    fun confirmToday() {
        update { it.copy(todayConfirmed = true) }
        next()
    }

    // ---- 7. alarms ----

    fun setAlarm(minutes: Int?) = update { it.copy(alarmMinutes = minutes, alarmByType = emptyMap()) }

    fun setAlarmFor(typeId: Long, minutes: Int?) = update { it.copy(alarmByType = it.alarmByType + (typeId to minutes)) }

    /** "근무마다 다르게": each shift starts from the common choice. */
    fun alarmPerShift(on: Boolean) = update { d ->
        d.copy(alarmByType = if (on) d.workTypes.associate { it.id to d.alarmMinutes } else emptyMap())
    }

    /** The first alarm the saved setup will ring, for the summary. */
    fun firstAlarm(d: SetupDraft): PlannedAlarm? {
        val at = now()
        val snapshot = ScheduleSnapshot(d.typesToSave(), d.pattern(at.toLocalDate()), d.paintedEntries())
        return AlarmPlanner.nextAlarm(snapshot.resolver, at)
    }

    // ---- 8. saving ----

    fun save() = save(current)

    private fun save(d: SetupDraft) {
        if (_saving.value) return
        _saving.value = true
        // The setup screen goes away as soon as the kind of work is saved, so the save outlives it.
        c.appScope.launch {
            runCatching { c.firstSetup.save(d) }
                .onSuccess {
                    c.telemetry.event("setup_done", buildString {
                        append(d.style?.name)
                        d.source?.let { append('_').append(it.kind.name) }
                    })
                }
                .onFailure { _message.value = "저장하지 못했어요. 한 번 더 눌러 주세요" }
            _saving.value = false
        }
    }

    companion object {
        const val MAX_STEPS = 62
        /** Colors for shifts the user adds, after the bundle's. */
        private val NEW_COLORS = listOf(0xFF00A88F, 0xFFE0A100, 0xFFD6457A, 0xFF27AE60, 0xFF8E44AD, 0xFF2D9CDB)
    }
}
