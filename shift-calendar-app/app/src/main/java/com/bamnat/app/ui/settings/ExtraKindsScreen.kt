package com.bamnat.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.BuildConfig
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.pay.named
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.UndoMessage
import com.bamnat.app.ui.calendar.ExtraKindMark
import com.bamnat.app.ui.calendar.ExtraText
import com.bamnat.app.ui.calendar.TimeBox
import com.bamnat.app.ui.calendar.timePresets
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.MinutesStepper
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.pay.ExtraRuleEditor
import com.bamnat.app.ui.pay.Hint
import com.bamnat.app.ui.pay.Label
import com.bamnat.app.ui.pay.Toggle
import com.bamnat.app.ui.pay.UnitMoney
import com.bamnat.app.ui.pay.payText
import com.bamnat.app.ui.pay.scopeText
import com.bamnat.app.ui.theme.ExtraPalette
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.OffDayTreatment
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkTime
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DayPay
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayUnit
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.WageType
import com.bamnat.app.ui.pay.wageLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * [EXTRA-06] Where company ways the settings cannot hold are sent until the server takes them (M12):
 * the privacy policy's contact (1-1항, 8항), which a test keeps the same.
 */
internal const val REPORT_EMAIL = "yoj0790@naver.com"

/** [EXTRA-04] One kind with how it is paid: saved together and undone together. */
data class KindDraft(val kind: ExtraKind, val rule: ExtraRule = ExtraRule(), val byShift: Map<Long, ExtraRule> = emptyMap()) {
    /** Hours that make sense: a band with work in it, a length above zero. */
    val timingValid: Boolean
        get() = when (val t = kind.timing) {
            is ExtraTiming.Band -> t.minutes > 0
            is ExtraTiming.AfterShift -> t.minutes > 0
            is ExtraTiming.BeforeShift -> t.minutes > 0
            is ExtraTiming.AsShift -> true
        }
    val valid: Boolean get() = kind.name.isNotBlank() && timingValid
}

/** The kind open in the editor: as opened, as it is now, and whether it is new. Kept in the ViewModel so a fold keeps it. */
data class KindEdit(val original: KindDraft, val draft: KindDraft, val isNew: Boolean) {
    val changed: Boolean get() = draft != original
    val canSave: Boolean get() = draft.valid && (isNew || changed)
}

/** [EXTRA-04] One piece of a kind on a real coming day, priced by the rules being edited. */
data class KindPreview(
    val date: LocalDate,
    /** What the day is without the piece: 쉬는 날 or the shift's name. */
    val day: String,
    /** The piece as it would go in, e.g. "야간 대근 · 19:00 – 07:00 (다음날)". */
    val piece: String,
    val amount: Long,
    /** What the law alone gives the same piece, when the company's way differs. */
    val legal: Long?,
    val lines: List<String>,
)

/** [EXTRA-01] A kind to start from; it opens in the editor and is added only when saved. */
data class KindTemplate(val title: String, val detail: String, val kind: ExtraKind)

class ExtraKindsViewModel(
    private val container: AppContainer,
    /** [MCAL-06] The calendar whose pay prices these kinds; the kinds themselves are the app's. */
    val calendarId: String = com.bamnat.app.data.PersonalCalendar.MAIN,
) : ViewModel() {
    private val scope = container.scopeOf(calendarId)
    private val payStore = scope.pay
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val profile: StateFlow<PayProfile?> = payStore.profile
    val snapshot: StateFlow<ScheduleSnapshot?> =
        scope.schedule.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val calculator = PayCalculator()

    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    private val _editing = MutableStateFlow<KindEdit?>(null)
    val editing: StateFlow<KindEdit?> = _editing.asStateFlow()


    fun open(kind: ExtraKind) {
        val rules = profile.value?.rules ?: CompanyRules()
        val d = KindDraft(kind, rules.extra[kind.id] ?: ExtraRule(), rules.extraByShift[kind.id].orEmpty())
        _editing.value = KindEdit(d, d, isNew = false)
    }

    fun openNew(template: KindTemplate) {
        val d = KindDraft(template.kind.copy(id = settings.value.extraKinds.nextId()))
        _editing.value = KindEdit(d, d, isNew = true)
        container.telemetry.event("extra_kind_template", template.title)
    }

    fun edit(transform: (KindDraft) -> KindDraft) = _editing.update { it?.copy(draft = transform(it.draft)) }

    fun close() { _editing.value = null }

    /** [EXTRA-04] Saves the kind and how it is paid in one step that one tap undoes. */
    fun saveEditing() {
        val e = _editing.value ?: return
        if (!e.draft.valid) return
        _editing.value = null
        if (!e.isNew && !e.changed) return
        val before = settings.value.extraKinds to profile.value
        val draft = e.draft.copy(kind = e.draft.kind.copy(name = e.draft.kind.name.trim(), short = e.draft.kind.short.trim()))
        container.settingsRepository.update { it.copy(extraKinds = it.extraKinds.put(draft.kind)) }
        viewModelScope.launch {
            savePay { it.withKind(draft.kind.id, draft.rule, draft.byShift) }
            val name = draft.kind.name
            _messages.emit(UndoMessage("‘$name’${Fmt.obj(name)} ${if (e.isNew) "추가했어요" else "저장했어요"}") { restore(before) })
        }
        container.telemetry.event("extra_kind_save", if (e.isNew) "new" else "edit")
    }

    /** A custom kind goes; pieces already entered with it stay as 추가 근무 and are paid by law. */
    fun deleteEditing() {
        val e = _editing.value ?: return
        _editing.value = null
        if (e.isNew || e.original.kind.isBuiltIn) return
        val kind = e.original.kind
        val before = settings.value.extraKinds to profile.value
        container.settingsRepository.update { s -> s.copy(extraKinds = s.extraKinds.copy(kinds = s.extraKinds.kinds.filterNot { it.id == kind.id })) }
        viewModelScope.launch {
            savePay { r -> r.withKind(kind.id, ExtraRule(), emptyMap()).let { if (it.publicHolidayAs == kind.id) it.copy(publicHolidayAs = null) else it } }
            _messages.emit(UndoMessage("‘${kind.name}’${Fmt.obj(kind.name)} 지웠어요") { restore(before) })
        }
        container.telemetry.event("extra_kind_delete")
    }

    fun move(id: Long, by: Int) = container.settingsRepository.update { s ->
        val list = s.extraKinds.kinds.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in list.indices) return@update s
        java.util.Collections.swap(list, i, j)
        s.copy(extraKinds = s.extraKinds.copy(kinds = list))
    }

    fun runUndo(m: UndoMessage) { m.undo?.let { viewModelScope.launch { it() } } }

    fun reported() = container.telemetry.event("extra_kind_report")

    /** [COMM-04] With a company server, a way the app cannot set goes straight to the developer, not by mail. */
    val companyOn: Boolean get() = container.companyApi.enabled

    /** The company the user set up under, to start the report with. */
    val companyName: String
        get() = container.companyStore.applied.value?.company ?: container.companyStore.draft.value?.company.orEmpty()

    private suspend fun savePay(transform: (CompanyRules) -> CompanyRules) {
        val p = payStore.profile.value ?: return
        val rules = transform(p.rules)
        if (rules != p.rules) payStore.save(p.copy(rules = rules))
    }

    private suspend fun restore(before: Pair<ExtraKinds, PayProfile?>) {
        container.settingsRepository.update { it.copy(extraKinds = before.first) }
        // Only rules go back: a pay setup made since stays.
        val p = before.second ?: return
        if (payStore.profile.value != p) payStore.save(p, keepUndo = false)
    }

    /**
     * [EXTRA-04] One piece of [draft] on the next fitting day of the user's own roster, within two
     * months: a day off for work with its own hours, a work day for work next to the shift. What
     * the month's pay gains by the rules being edited, and what the law alone would give.
     */
    fun preview(draft: KindDraft, kinds: ExtraKinds, profile: PayProfile, snapshot: ScheduleSnapshot, today: LocalDate, shiftId: Long?): KindPreview? {
        val draftKinds = kinds.put(draft.kind)
        val base = snapshot.copy(kinds = draftKinds)
        val nearShift = draft.kind.timing is ExtraTiming.AfterShift || draft.kind.timing is ExtraTiming.BeforeShift
        val day = (0L until 62L).asSequence().map { base.resolver.resolve(today.plusDays(it)) }
            .firstOrNull { d -> d.extras.isEmpty() && if (nearShift) d.isWorkDay && d.shiftFrom != null else d.isRestDay }
            ?: return null
        val timed = snapshot.shiftTypes.filter { !it.isOff && it.hasTimes }
        val piece = when (val t = draft.kind.timing) {
            is ExtraTiming.AsShift -> t.shifts(timed).let { covered -> covered.firstOrNull { it.id == shiftId } ?: covered.firstOrNull() }
                ?.let { ExtraText.cover(draft.kind, it) }
                ?: ExtraEntry(draft.kind.id, LocalTime.of(9, 0), LocalTime.of(18, 0), 60, LocalTime.of(12, 0))
            else -> ExtraText.default(draft.kind) ?: return null
        }
        val entry = (snapshot.entries.firstOrNull { it.date == day.date } ?: DayEntry(day.date)).let { it.copy(extras = it.extras + piece) }
        val with = base.copy(entries = snapshot.entries.filterNot { it.date == day.date } + entry)
        val p = profile.copy(rules = profile.rules.withKind(draft.kind.id, draft.rule, draft.byShift)).named(draftKinds)
        val month = p.period.monthOf(day.date)
        fun gain(prof: PayProfile): Pair<Long, Pair<DayPay?, DayPay?>> {
            val a = calculator.calculate(prof, month, with.resolver::resolve)
            val b = calculator.calculate(prof, month, base.resolver::resolve)
            return (a.total - b.total) to (a.day(day.date) to b.day(day.date))
        }
        val (amount, days) = gain(p)
        val legal = if (p.rules.extraRule(draft.kind.id, piece.coveredShiftId) != null) {
            gain(p.copy(rules = p.rules.withKind(draft.kind.id, ExtraRule(), emptyMap()))).first
        } else null
        val lines = changedParts(days.first, days.second).toMutableList()
        val listed = lines.sumOf { it.first }
        // The week's 40 hours can move other days' hours too; say what is not in the day's lines.
        if (amount - listed != 0L) lines += (amount - listed) to "같은 주 다른 날 계산이 바뀐 몫"
        val shown = with.resolver.resolve(day.date).extras.lastOrNull()
        return KindPreview(
            date = day.date,
            day = if (day.isRestDay) "쉬는 날" else day.shiftType?.name ?: "근무일",
            piece = shown?.let { "${ExtraText.name(it)} · ${ExtraText.time(it)}" } ?: draft.kind.name,
            amount = amount,
            legal = legal,
            lines = lines.map { (v, text) -> "${signed(v)} $text" },
        )
    }

    /** [EXTRA-08] 통상시급 and 기본급 this pay month, for the editor to say what an hour of the work comes to. */
    fun money(profile: PayProfile, snapshot: ScheduleSnapshot, today: LocalDate): UnitMoney {
        val month = profile.period.monthOf(today)
        val hourly = calculator.calculate(profile.named(snapshot.kinds), month, snapshot.resolver::resolve).ordinaryHourly
        val base = profile.base.on(month).value
        return when (profile.wageType) {
            WageType.MONTHLY -> UnitMoney(hourly, base, "기본급")
            WageType.HOURLY -> UnitMoney(hourly, base * profile.monthlyHours, "시급 × ${profile.monthlyHours}시간")
            // [PAYS-03] 일당 ÷ 1일 소정근로시간 is the 시급, as the calculator has it.
            WageType.DAILY -> UnitMoney(hourly, Math.round(base * 60.0 / profile.dailyHours.coerceIn(1, 8 * 60)) * profile.monthlyHours,
                "일당의 시급 × ${profile.monthlyHours}시간")
        }
    }

    /** What the piece added to the day, part by part, with each part's formula. */
    private fun changedParts(with: DayPay?, without: DayPay?): List<Pair<Long, String>> {
        val before = without?.parts.orEmpty().groupBy { it.label }.mapValues { (_, v) -> v.sumOf { it.amount } }
        return with?.parts.orEmpty().groupBy { it.label }.mapNotNull { (label, parts) ->
            val diff = parts.sumOf { it.amount } - (before[label] ?: 0)
            if (diff == 0L) null else diff to "$label (${parts.joinToString(" + ") { it.formula }})"
        }
    }
}

private fun signed(v: Long) = (if (v < 0) "−" else "+") + Fmt.won(kotlin.math.abs(v))

/** [EXTRA-03] The rules with [rule] and [byShift] for kind [id]; by law with nothing on top is no rule at all. */
private fun CompanyRules.withKind(id: Long, rule: ExtraRule, byShift: Map<Long, ExtraRule>): CompanyRules {
    val own = byShift.filterValues { !it.isLegal }
    return copy(
        extra = if (rule.isLegal) extra - id else extra + (id to rule),
        extraByShift = if (own.isEmpty()) extraByShift - id else extraByShift + (id to own),
    )
}

/** Where a kind sits on the clock, in words: "19:00 – 07:00 (다음날) · 휴게 1시간 · 11시간", "근무 끝나고 2시간". */
internal fun timingText(kind: ExtraKind, types: Map<Long, ShiftType>): String = when (val t = kind.timing) {
    is ExtraTiming.Band -> Fmt.timeRange(t.start, t.end) + (if (t.breakMinutes > 0) " · 휴게 ${HoursFormat.korean(t.breakMinutes)}" else "") +
        " · ${HoursFormat.korean(t.minutes)}"
    is ExtraTiming.AfterShift -> "근무 끝나고 ${HoursFormat.korean(t.minutes)}"
    is ExtraTiming.BeforeShift -> "근무 시작 전 ${HoursFormat.korean(t.minutes)}"
    is ExtraTiming.AsShift -> {
        // [EXTRA-07] One shift with its hours, a few by name (모닝·나이트), or whichever is covered.
        val named = types.values.filter { it.id in t.shiftTypeIds }
        when {
            named.size == 1 -> named[0].let { s ->
                "${s.name} 근무 시간 그대로" + if (s.start != null && s.end != null) " · ${Fmt.timeRange(s.start!!, s.end!!)}" else ""
            }
            named.size > 1 -> "${named.joinToString("·") { it.name }} 근무 시간 그대로"
            else -> "대신한 근무 시간 그대로"
        }
    }
}

/**
 * [EXTRA-08] What the law pays [kind] as boxes to tick, to start the company's way from: 연장, 야간
 * and 휴일 at the premiums in force (none ticked under 5 employees), 휴무일 when a day off counts as a holiday.
 */
internal fun lawStart(kind: ExtraKind, profile: PayProfile, today: LocalDate): ExtraRule {
    val rates = RateTable.on(today)
    val r = profile.rules
    fun legal(p: Double) = if (profile.fiveOrMoreEmployees) p else 0.0
    return ExtraRule.lawUnit(
        overtime = r.overtimePremium ?: legal(rates.overtimePremium),
        night = r.nightPremium ?: legal(rates.nightPremium),
        holiday = r.holidayPremiumWithin8h ?: legal(rates.holidayPremiumWithin8h),
        restAsHoliday = kind.offDay == OffDayTreatment.HOLIDAY || r.restDayWork == RestDayWork.HOLIDAY,
    )
}

/** Ready kinds not already there: 조출, 잔업, a 대근 for each shift, 특근, and one from scratch. */
private fun templates(kinds: ExtraKinds, types: List<ShiftType>): List<KindTemplate> {
    val names = kinds.kinds.map { it.name.replace(" ", "") }.toSet()
    val used = kinds.kinds.map { it.colorArgb }.toSet()
    val colors = ExtraPalette.filter { it !in used }.ifEmpty { ExtraPalette }
    var n = 0
    fun kind(name: String, short: String, timing: ExtraTiming) = ExtraKind(0, name, short, colors[n++ % colors.size], timing)
    return buildList {
        add(KindTemplate("조출", "근무 시작 전 1시간", kind("조출", "조", ExtraTiming.BeforeShift(60))))
        add(KindTemplate("잔업", "근무 끝나고 2시간", kind("잔업", "잔", ExtraTiming.AfterShift(120))))
        for (t in types.filter { !it.isOff && it.hasTimes }) {
            if (kinds.kinds.any { (it.timing as? ExtraTiming.AsShift)?.shiftTypeId == t.id }) continue
            val name = "${t.name} 대근".take(ExtraKind.MAX_NAME)
            add(KindTemplate(name, "${Fmt.timeRange(t.start!!, t.end!!)} · ${t.name} 근무 시간 그대로",
                kind(name, t.label.ifBlank { t.name }.take(1) + "대", ExtraTiming.AsShift(t.id))))
        }
        add(KindTemplate("특근", "09:00 – 18:00 · 휴게 1시간",
            kind("특근", "특", ExtraTiming.Band(LocalTime.of(9, 0), LocalTime.of(18, 0), 60, LocalTime.of(12, 0)))))
    }.filter { it.title.replace(" ", "") !in names } +
        KindTemplate("직접 만들기", "이름과 시간을 처음부터 정해요", kind("", "", ExtraTiming.Band(LocalTime.of(9, 0), LocalTime.of(18, 0), 60, LocalTime.of(12, 0))))
}

/**
 * [EXTRA-04, EXTRA-07] 연장 휴무일 근무 지정: 잔업·특근·대근 as this person's company has them, on one screen: each kind's name, mark,
 * hours (근무 뒤 2시간, 야간 대근 19~07시 …), what a day off makes of it, and how it is paid, with
 * what one of them comes to on a real coming day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraKindsScreen(vm: ExtraKindsViewModel, onBack: () -> Unit, onOpenPay: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    // [STAB-01] Read at once, on the same frame as the key: a text box one frame behind the keyboard
    // doubles or drops half-built Korean letters and moves the cursor.
    val edit by vm.editing.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val kinds = settings.extraKinds
    var adding by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.message, actionLabel = m.undo?.let { m.actionLabel }, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.runUndo(m)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("연장 휴무일 근무 지정") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // The extended FAB drops its text from semantics, so the icon carries the label for TalkBack.
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, "종류 추가") }, text = { Text("종류 추가") })
        },
    ) { padding ->
        val snap = snapshot
        if (snap == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val types = snap.typesById
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "쉬는 날 대근·특근과 근무 앞뒤 연장 근무를 회사에 맞춰 정해 두세요. 달력에서 날짜를 누르고 한 번에 넣으면 가산 임금까지 계산해요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            itemsIndexed(kinds.kinds, key = { _, k -> k.id }) { i, k ->
                KindRow(
                    kind = k,
                    timing = timingText(k, types),
                    pay = profile?.rules?.payText(k.id),
                    canMoveUp = i > 0,
                    canMoveDown = i < kinds.kinds.lastIndex,
                    onClick = { vm.open(k) },
                    onMoveUp = { vm.move(k.id, -1) },
                    onMoveDown = { vm.move(k.id, 1) },
                )
            }
            item {
                Text(
                    "가산 임금은 종류를 눌러 체크해요. 정하지 않으면 법대로 계산해요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (adding) {
            TemplateDialog(
                templates = remember(kinds, snap.shiftTypes) { templates(kinds, snap.shiftTypes) },
                onPick = { t -> adding = false; vm.openNew(t) },
                onDismiss = { adding = false },
            )
        }
        edit?.let { e ->
            KindEditor(e, vm, kinds, profile, snap, payUnlocked = true, onOpenPay = onOpenPay)
        }
    }
}

@Composable
private fun KindRow(
    kind: ExtraKind,
    timing: String,
    pay: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ExtraKindMark(kind, size = 32.dp, modifier = Modifier.alpha(if (kind.hidden) 0.45f else 1f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(kind.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (kind.hidden) {
                        Spacer(Modifier.width(8.dp))
                        Text("숨김", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                Text(timing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pay != null) {
                    Text("급여 · $pay", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Filled.KeyboardArrowUp, "${kind.name} 위로") }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Filled.KeyboardArrowDown, "${kind.name} 아래로") }
            }
        }
    }
}

@Composable
private fun TemplateDialog(templates: List<KindTemplate>, onPick: (KindTemplate) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("어떤 일을 추가할까요?") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("고른 뒤 이름·시간·급여 방식을 바꿀 수 있어요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                for (t in templates) {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onPick(t) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (t.kind.name.isBlank()) {
                            Box(Modifier.size(28.dp).clip(MaterialTheme.shapes.small).border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                                contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) }
                        } else {
                            ExtraKindMark(t.kind, size = 28.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge)
                            Text(t.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** What 법대로 comes to for [kind], in a line. */
private fun legalText(kind: ExtraKind, profile: PayProfile?, nightLabel: String): String {
    if (profile?.fiveOrMoreEmployees == false) return "5인 미만이라 가산 없이 일한 시간 × 통상시급 100%"
    val night = " · $nightLabel 야간 가산"
    return when (kind.timing) {
        is ExtraTiming.AfterShift, is ExtraTiming.BeforeShift -> "하루 8시간·주 40시간을 넘는 시간은 150%, 안이면 100%$night"
        else -> if (kind.offDay == OffDayTreatment.HOLIDAY) "쉬는 날엔 휴일근로 (${RestDayWork.HOLIDAY.detail})$night"
        else "쉬는 날엔 ${(profile?.rules?.restDayWork ?: RestDayWork.LAW).detail}$night"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun KindEditor(
    edit: KindEdit,
    vm: ExtraKindsViewModel,
    kinds: ExtraKinds,
    profile: PayProfile?,
    snapshot: ScheduleSnapshot,
    payUnlocked: Boolean,
    onOpenPay: () -> Unit,
) {
    val draft = edit.draft
    val kind = draft.kind
    fun setKind(k: ExtraKind) = vm.edit { it.copy(kind = k) }
    fun setTiming(t: ExtraTiming) = setKind(kind.copy(timing = t))
    val close: () -> Unit = { if (edit.changed && draft.valid) vm.saveEditing() else vm.close() }
    var picking by remember { mutableStateOf<String?>(null) }
    var reporting by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var previewShift by remember { mutableStateOf<Long?>(null) }
    val timed = remember(snapshot) { snapshot.shiftTypes.filter { !it.isOff && it.hasTimes } }
    val today = remember { LocalDate.now() }
    val nightLabel = remember(profile) { (profile?.rules ?: CompanyRules()).nightLabel(RateTable.on(today)) }
    val money by produceState<UnitMoney?>(null, profile, snapshot) {
        value = profile?.let { p -> withContext(Dispatchers.Default) { runCatching { vm.money(p, snapshot, today) }.getOrNull() } }
    }
    // One kind stays on, so a day can always be marked.
    val onlyVisible = kinds.kinds.none { !it.hidden && it.id != kind.id }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (edit.isNew) "종류 추가" else "종류 고치기") },
                        navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "닫기") } },
                        actions = { TextButton(onClick = vm::saveEditing, enabled = edit.canSave) { Text("저장") } },
                    )
                },
                bottomBar = {
                    PreviewBar(vm, draft, kinds, profile, snapshot, payUnlocked, today, previewShift, { previewShift = it }, timed)
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExtraKindMark(kind.copy(short = kind.short.ifBlank { kind.name.take(1).ifBlank { "?" } }), size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            value = kind.name,
                            onValueChange = { v ->
                                val name = v.take(ExtraKind.MAX_NAME)
                                // The mark follows the name until the user picks one of their own.
                                val short = if (kind.short.isBlank() || kind.short == kind.name.take(1)) name.take(1) else kind.short
                                setKind(kind.copy(name = name, short = short))
                            },
                            label = { Text("이름 (예: 조출, 야간 대근)") },
                            singleLine = true,
                            isError = kind.name.isBlank(),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = kind.short,
                            onValueChange = { v -> setKind(kind.copy(short = v.take(2))) },
                            label = { Text("표시") },
                            singleLine = true,
                            modifier = Modifier.width(76.dp),
                        )
                    }

                    SectionCard(title = "색") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in ExtraPalette) {
                                val selected = c == kind.colorArgb
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(c.asColor())
                                        .selectable(selected = selected, role = Role.RadioButton) { setKind(kind.copy(colorArgb = c)) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) Icon(Icons.Filled.Check, "고른 색", tint = com.bamnat.app.ui.components.contentColorOn(c.asColor()))
                                }
                            }
                        }
                    }

                    SectionCard(title = "시간") {
                        TimingContent(kind, timed, snapshot, onTiming = ::setTiming, onPick = { picking = it })
                    }

                    // [EXTRA-08] With the company's own premiums the 휴무일 box says what a day off adds instead.
                    if ((kind.timing is ExtraTiming.Band || kind.timing is ExtraTiming.AsShift) && draft.rule.basis != ExtraBasis.UNIT) {
                        SectionCard(title = "쉬는 날에 하면") {
                            val rest = profile?.rules?.restDayWork ?: RestDayWork.LAW
                            OffDayTreatment.entries.forEach { mode ->
                                val selected = kind.offDay == mode
                                Row(
                                    Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton) { setKind(kind.copy(offDay = mode)) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = selected, onClick = null)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            when (mode) {
                                                OffDayTreatment.BY_DAY -> "지금 회사 규칙: ${rest.label} · ${rest.detail}"
                                                OffDayTreatment.HOLIDAY -> "이 일만 ${RestDayWork.HOLIDAY.detail} (단체협약 등)"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Hint("법으로 쉬는 날(휴무일) 근무는 휴일근로가 아니에요. 회사 규칙은 급여 계산 → 회사 규칙에서 한 번에 정해요.")
                        }
                    }

                    SectionCard(title = "급여와 가산") {
                        when {
                            profile == null -> {
                                Hint("급여 계산을 설정하면 ${kind.name.ifBlank { "이 일" }}${Fmt.obj(kind.name.ifBlank { "이 일" })} 회사가 주는 방식대로 계산하고 금액도 미리 볼 수 있어요.")
                                OutlinedButton(onClick = { close(); onOpenPay() }, modifier = Modifier.padding(top = 4.dp)) { Text("급여 계산 설정하기") }
                            }
                            else -> {
                                val start = remember(profile, kind.offDay) { lawStart(kind, profile, today) }
                                val legal = legalText(kind, profile, nightLabel)
                                Label("회사는 ${kind.name.ifBlank { "이 일" }}${Fmt.obj(kind.name.ifBlank { "이 일" })} 어떻게 계산하나요?")
                                ExtraRuleEditor(draft.rule, start, legal, nightLabel, money, profile.fiveOrMoreEmployees) { r ->
                                    vm.edit { d ->
                                        // [EXTRA-08] The 휴무일 box now says what a day off adds, so the kind follows the company's day-off rule.
                                        val k = if (r.basis == ExtraBasis.UNIT && d.rule.basis != ExtraBasis.UNIT) d.kind.copy(offDay = OffDayTreatment.BY_DAY) else d.kind
                                        d.copy(kind = k, rule = r)
                                    }
                                }
                                // [PAYX-11][EXTRA-07] A 대근 for several shifts can pay each its own way.
                                val covered = (kind.timing as? ExtraTiming.AsShift)?.takeIf { it.shiftTypeId == null }?.shifts(timed).orEmpty()
                                val listed = (covered + timed.filter { it.id in draft.byShift }).distinct()
                                if (listed.isNotEmpty() && (covered.size > 1 || draft.byShift.isNotEmpty())) {
                                    PerShiftRules(kind.name.ifBlank { "이 일" }, listed, draft.rule, start, draft.byShift, legal, nightLabel, money,
                                        profile.fiveOrMoreEmployees) { m -> vm.edit { it.copy(byShift = m) } }
                                }
                            }
                        }
                    }

                    SectionCard(title = "그 밖에") {
                        Toggle("근무 1번으로 세기", "식대·야간 수당처럼 근무마다 받는 수당을 이 일에도 받아요", kind.countsAsShift) {
                            setKind(kind.copy(countsAsShift = it))
                        }
                        Toggle(
                            "달력에서 숨기기",
                            if (onlyVisible) "다른 종류를 켜야 숨길 수 있어요. 하나는 남아 있어야 달력에서 넣을 수 있어요"
                            else "안 쓰는 종류는 숨겨요. 이미 넣은 기록과 계산은 그대로예요",
                            kind.hidden,
                        ) { if (!(it && onlyVisible)) setKind(kind.copy(hidden = it)) }
                    }

                    // [EXTRA-06] A company way the choices above cannot hold goes to the developer in words and an example.
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("회사 방식이 여기 없나요?", style = MaterialTheme.typography.titleSmall)
                            Text(if (vm.companyOn) "말과 예시로 적어 주시면 개발자가 직접 읽고 답을 드려요." else "말과 예시로 적어 보내 주세요. 검토해서 계산 방식에 넣어요.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { reporting = true }, modifier = Modifier.padding(top = 8.dp)) {
                                Icon(if (vm.companyOn) Icons.AutoMirrored.Outlined.Send else Icons.Outlined.Email, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("회사 방식 알려 주기")
                            }
                        }
                    }

                    if (!edit.isNew && !kind.isBuiltIn) {
                        TextButton(onClick = { deleting = true }) {
                            Text("이 종류 지우기", color = MaterialTheme.colorScheme.error)
                        }
                    } else if (!edit.isNew) {
                        Hint("기본 종류(잔업·특근·대근)는 지울 수 없어요. 이름을 바꾸거나 숨겨 두세요.")
                    }
                    Spacer(Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }

    val band = kind.timing as? ExtraTiming.Band
    val which = picking
    if (band != null && which != null) {
        TimePickerDialog(
            title = when (which) { "start" -> "시작 시각"; "end" -> "끝 시각"; else -> "휴게 시작" },
            initial = when (which) { "start" -> band.start; "end" -> band.end; else -> band.breakStart ?: band.start.plusHours(4) },
            presets = timePresets(snapshot),
            onDismiss = { picking = null },
        ) { t ->
            picking = null
            val next = when (which) {
                "start" -> band.copy(start = t)
                "end" -> band.copy(end = t)
                else -> band.copy(breakStart = t)
            }
            val span = WorkTime.spanMinutes(next.start, next.end)
            setTiming(next.copy(breakMinutes = next.breakMinutes.coerceAtMost((span - 10).coerceAtLeast(0))))
        }
    }
    if (deleting) {
        val pieces = remember(snapshot, kind.id) { snapshot.entries.sumOf { d -> d.extras.count { it.kindId == kind.id } } }
        ConfirmDialog(
            title = "‘${edit.original.kind.name}’${Fmt.obj(edit.original.kind.name)} 지울까요?",
            message = if (pieces == 0) "달력에 넣은 기록이 없어요. 지운 뒤에도 바로 되돌릴 수 있어요."
            else "이 종류로 넣은 ${pieces}건은 달력에 ‘추가 근무’로 남고 법대로 계산해요. 쓰지 않을 뿐이라면 숨기기를 쓰세요.",
            confirmLabel = "지우기",
            destructive = true,
            onDismiss = { deleting = false },
            onConfirm = { deleting = false; vm.deleteEditing() },
        )
    }
    if (reporting) {
        if (vm.companyOn) {
            com.bamnat.app.ui.company.AskDeveloperDialog(
                company = vm.companyName,
                onDismiss = { reporting = false },
                onSent = { reporting = false; vm.reported() },
                setup = setupText(draft, snapshot, profile),
            )
        } else {
            ReportDialog(draft, snapshot, profile, onDismiss = { reporting = false }, onSent = { reporting = false; vm.reported() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimingContent(kind: ExtraKind, timed: List<ShiftType>, snapshot: ScheduleSnapshot, onTiming: (ExtraTiming) -> Unit, onPick: (String) -> Unit) {
    val t = kind.timing
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = t is ExtraTiming.Band, onClick = {
            if (t !is ExtraTiming.Band) onTiming(ExtraTiming.Band(LocalTime.of(9, 0), LocalTime.of(18, 0), 60, LocalTime.of(12, 0)))
        }, label = { Text("정한 시각") })
        FilterChip(selected = t is ExtraTiming.AfterShift, onClick = {
            if (t !is ExtraTiming.AfterShift) onTiming(ExtraTiming.AfterShift((t as? ExtraTiming.BeforeShift)?.minutes ?: 120))
        }, label = { Text("근무 끝나고") })
        FilterChip(selected = t is ExtraTiming.BeforeShift, onClick = {
            if (t !is ExtraTiming.BeforeShift) onTiming(ExtraTiming.BeforeShift((t as? ExtraTiming.AfterShift)?.minutes ?: 60))
        }, label = { Text("근무 시작 전") })
        FilterChip(selected = t is ExtraTiming.AsShift, onClick = {
            if (t !is ExtraTiming.AsShift) onTiming(ExtraTiming.AsShift())
        }, label = { Text("근무 하나 그대로") })
    }
    when (t) {
        is ExtraTiming.Band -> {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                TimeBox(label = "시작", time = t.start, onClick = { onPick("start") }, modifier = Modifier.weight(1f))
                TimeBox(label = "끝", time = t.end, onClick = { onPick("end") }, modifier = Modifier.weight(1f))
            }
            val span = WorkTime.spanMinutes(t.start, t.end)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("휴게 시간", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                MinutesStepper(
                    minutes = t.breakMinutes,
                    onChange = { b -> onTiming(t.copy(breakMinutes = b, breakStart = if (b == 0) null else t.breakStart)) },
                    step = 10,
                    max = (span - 10).coerceAtLeast(0),
                    dialogTitle = "휴게 시간",
                )
            }
            if (t.breakMinutes > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("휴게 시작", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    FilterChip(selected = t.breakStart == null, onClick = { onTiming(t.copy(breakStart = null)) }, label = { Text("모름") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = t.breakStart != null, onClick = { onPick("break") },
                        label = { Text(t.breakStart?.let(Fmt::time) ?: "시각 정하기") })
                }
            }
            Hint(
                "일하는 시간 ${HoursFormat.korean(t.minutes)}" + (if (WorkTime.isOvernight(t.start, t.end)) " · 끝은 다음 날" else "") +
                    ". 특근·야간 대근처럼 시각이 정해져 있을 때 써요. 휴게 시작을 정하면 야간 시간이 정확해져요.",
            )
            if (t.minutes <= 0) Text("휴게가 일한 시간보다 길어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        is ExtraTiming.AfterShift, is ExtraTiming.BeforeShift -> {
            val minutes = (t as? ExtraTiming.AfterShift)?.minutes ?: (t as ExtraTiming.BeforeShift).minutes
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (t is ExtraTiming.AfterShift) "근무 끝나고" else "근무 시작 전", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                MinutesStepper(
                    minutes = minutes,
                    onChange = { m -> onTiming(if (t is ExtraTiming.AfterShift) ExtraTiming.AfterShift(m.coerceAtLeast(10)) else ExtraTiming.BeforeShift(m.coerceAtLeast(10))) },
                    step = 30,
                    max = 12 * 60,
                    dialogTitle = "${kind.name.ifBlank { "추가 근무" }} 시간",
                )
            }
            Hint(if (t is ExtraTiming.AfterShift) "잔업처럼 근무가 끝나고 이어서 해요. 달력에서 날마다 시간을 바꿀 수 있어요."
            else "조출처럼 근무 시작 전에 해요. 달력에서 날마다 시간을 바꿀 수 있어요.")
        }
        is ExtraTiming.AsShift -> {
            if (timed.isEmpty()) {
                Hint("시간이 있는 근무 종류가 없어요. 근무 종류를 만들면 그 시간이 그대로 들어가요. 지금은 09:00–18:00으로 넣어요.")
            } else {
                // [EXTRA-07] Several shifts at once: one 대근 for 모닝 and 나이트 alike. A shift deleted since is left out.
                val picked = t.shiftTypeIds.filter { id -> timed.any { it.id == id } }.toSet()
                Label("어느 근무를 대신하나요? (여러 개 고를 수 있어요)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = picked.isEmpty(), onClick = { onTiming(ExtraTiming.AsShift()) }, label = { Text("모든 근무") })
                    for (s in timed) {
                        val on = s.id in picked
                        FilterChip(
                            selected = on,
                            onClick = {
                                val next = if (on) picked - s.id else picked + s.id
                                // Every shift, or none left, is the same as any shift.
                                onTiming(ExtraTiming.AsShift(if (next.size >= timed.size) emptySet() else next))
                            },
                            label = { Text("${s.name} ${ExtraText.span(s.start!!, s.end!!)}") },
                            leadingIcon = { ShiftBadge(s, size = 18.dp) },
                        )
                    }
                }
                Hint(
                    when {
                        picked.isEmpty() -> "대근처럼 다른 사람 근무를 대신하면 그 근무 시간이 그대로 들어가요. 달력에서 그날 근무를 골라 넣어요."
                        picked.size == 1 -> "고른 근무 시간이 그대로 들어가요. 달력에서 날마다 시간을 바꿀 수 있어요."
                        else -> "달력에서 ${timed.filter { it.id in picked }.joinToString(" · ") { ExtraText.coverName(kind, it) }}${Fmt.obj(kind.name)} 하나씩 골라 넣어요."
                    },
                )
            }
        }
    }
}

/**
 * [PAYX-11] Pay for the kind by the shift it covered (주간·야간 …). A shift with no way of its own
 * uses the kind's, so only the differences need entering; one of its own starts from the kind's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PerShiftRules(
    name: String,
    types: List<ShiftType>,
    base: ExtraRule,
    start: ExtraRule,
    byShift: Map<Long, ExtraRule>,
    legalText: String,
    nightLabel: String,
    money: UnitMoney?,
    fiveOrMore: Boolean,
    onChange: (Map<Long, ExtraRule>) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    Label("근무마다 다르게 주나요?")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        types.forEach { t ->
            val own = byShift[t.id]
            FilterChip(
                selected = editing == t.id || own != null,
                onClick = { editing = if (editing == t.id) null else t.id },
                label = { Text(if (own != null) "${t.name} · ${own.describe()}" else "${t.name} $name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { ShiftBadge(t, size = 18.dp) },
            )
        }
    }
    Hint("고르지 않은 근무는 위 방식대로 계산해요. 예: 나이트 대근만 야간 가산이 달라요.")
    val id = editing ?: return
    val type = types.firstOrNull { it.id == id } ?: return
    // The kind's own way to start from; 법대로 or a way from before starts from the law's boxes.
    val kindWay = if (base.basis == ExtraBasis.UNIT) base else start.copy(bonus = base.bonus)
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text("${type.name} $name", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
            val own = byShift[id]
            if (own == null) {
                Hint("지금은 위 방식대로 계산해요.")
                OutlinedButton(onClick = { onChange(byShift + (id to kindWay)) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("${type.name}만 따로 정하기")
                }
            } else {
                ExtraRuleEditor(own, kindWay, legalText, nightLabel, money, fiveOrMore,
                    ownTitle = "${type.name}만 따로 계산해요", resetLabel = "위 방식과 같게", keepsBonus = false) { r ->
                    onChange(if (r.basis == ExtraBasis.LEGAL && r.bonus <= 0) byShift - id else byShift + (id to r))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { editing = null }) { Text("닫기") }
            }
        }
    }
}

/** The live amount at the foot of the editor: what one of these comes to on a real coming day [EXTRA-04]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewBar(
    vm: ExtraKindsViewModel,
    draft: KindDraft,
    kinds: ExtraKinds,
    profile: PayProfile?,
    snapshot: ScheduleSnapshot,
    payUnlocked: Boolean,
    today: LocalDate,
    shiftId: Long?,
    onShift: (Long) -> Unit,
    timed: List<ShiftType>,
) {
    if (profile == null || !payUnlocked) return
    val preview by produceState<KindPreview?>(null, draft, kinds, profile, snapshot, shiftId) {
        value = if (!draft.timingValid) null else withContext(Dispatchers.Default) {
            runCatching { vm.preview(draft, kinds, profile, snapshot, today, shiftId) }.getOrNull()
        }
    }
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        val p = preview
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 720.dp)) {
            if (p == null) {
                Text("앞으로 두 달 안에 미리 볼 날이 없어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .clickable(onClickLabel = if (open) "계산 풀이 접기" else "계산 풀이 보기") { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${Fmt.dayTitle(p.date)} ${p.day}에 한 번 하면", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedContent(p.amount, label = "amount") { a ->
                        Text(signed(a), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    p.legal?.let { l ->
                        Text("법대로면 ${signed(l)}" + (p.amount - l).let { d -> if (d != 0L) " · 차이 ${signed(d)}" else "" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, null)
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 6.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val covered = (draft.kind.timing as? ExtraTiming.AsShift)?.takeIf { it.shiftTypeId == null }?.shifts(timed).orEmpty()
                    if (covered.size > 1) {
                        val picked = covered.firstOrNull { it.id == shiftId } ?: covered.first()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (s in covered) FilterChip(selected = picked.id == s.id, onClick = { onShift(s.id) }, label = { Text("${s.name} 대근으로") })
                        }
                    }
                    Text(p.piece, style = MaterialTheme.typography.bodyMedium)
                    p.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "내 기본급과 근무표로 계산한 예상이에요. 그 주에 일한 시간에 따라 달라질 수 있어요." +
                            if (profile.wageType != WageType.MONTHLY) "" else " 기본급은 이미 받는 몫이라 빠져 있어요.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** [EXTRA-06] What the report dialog says about the mail; the privacy policy 1-1항 says the same. */
internal const val REPORT_NOTICE =
    "기본급·이름·연락처는 적지 않아도 돼요. 메일 앱에서 보내면 메일 주소와 이 내용이 운영자에게 가고, 검토가 끝나면 지워요."

/** [EXTRA-06] The company's way in words, with this kind's settings but no amounts, names or contacts. */
@Composable
private fun ReportDialog(draft: KindDraft, snapshot: ScheduleSnapshot, profile: PayProfile?, onDismiss: () -> Unit, onSent: () -> Unit) {
    val context = LocalContext.current
    var company by rememberSaveable { mutableStateOf("") }
    var how by rememberSaveable { mutableStateOf("") }
    var example by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("회사 방식 알려 주기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("설정으로 담을 수 없으면 말로 적어 주세요. 지금 이 종류의 설정도 함께 보내요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(company, { company = it.take(40) }, label = { Text("회사 이름 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(how, { how = it.take(1000) }, label = { Text("회사는 어떻게 계산하나요?") },
                    placeholder = { Text("예: 대근은 8시간에 기본급 10%, 8시간 넘으면 0.5 가산, 야간 0.5 가산") },
                    minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(example, { example = it.take(500) }, label = { Text("예시 (선택)") },
                    placeholder = { Text("예: 근무 8시간 뒤 야간 대근 4시간 → 기본급의 10%") },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                Text(REPORT_NOTICE,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                sendReport(context, "밤낮 회사 방식 제보 · ${draft.kind.name}", reportText(draft, snapshot, profile, company, how, example))
                onSent()
            }, enabled = how.isNotBlank()) { Text("메일로 보내기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** What is sent: the kind's settings and the user's words. Amounts the user typed into rules are left out. */
private fun reportText(draft: KindDraft, snapshot: ScheduleSnapshot, profile: PayProfile?, company: String, how: String, example: String): String = buildString {
    appendLine("[밤낮 회사 방식 제보]")
    appendLine("회사: ${company.trim().ifEmpty { "적지 않음" }}")
    appendLine(setupText(draft, snapshot, profile))
    appendLine()
    appendLine("회사 방식:")
    appendLine(how.trim())
    if (example.isNotBlank()) {
        appendLine()
        appendLine("예시:")
        appendLine(example.trim())
    }
    appendLine()
    append("앱 ${BuildConfig.VERSION_NAME}")
}

/** [INQ-01] This kind's settings in words, without the won amounts typed into its rules. */
private fun setupText(draft: KindDraft, snapshot: ScheduleSnapshot, profile: PayProfile?): String = buildString {
    val k = draft.kind
    appendLine("종류: ${k.name} · ${timingText(k, snapshot.typesById)}")
    if (k.timing is ExtraTiming.Band || k.timing is ExtraTiming.AsShift) appendLine("쉬는 날에 하면: ${k.offDay.label}")
    val night = (profile?.rules ?: CompanyRules()).nightLabel(RateTable.on(LocalDate.now()))
    appendLine("지금 설정: ${draft.rule.shareText(night)}")
    draft.byShift.forEach { (id, r) -> appendLine("  ${snapshot.typesById[id]?.name ?: "근무"}: ${r.shareText(night)}") }
    profile?.let { p ->
        appendLine("사업장: ${if (p.fiveOrMoreEmployees) "5인 이상" else "5인 미만"} · ${p.wageLabel()} · " +
            "쉬는 날 근무 ${p.rules.restDayWork.label}")
    }
}.trimEnd()

/** A rule in words without the won amounts typed into it. */
private fun ExtraRule.shareText(nightLabel: String): String =
    (when (basis) {
        ExtraBasis.FIXED_PER_TIME -> "1회 정액" + (coverMinutes?.let { " (${PayCalculator.hours(it)}까지)" } ?: "")
        ExtraBasis.FIXED_PER_HOUR -> "시간당 정액"
        // [EXTRA-08] The hour's pay and each ticked premium with the hours it is on.
        ExtraBasis.UNIT -> when (unit) {
            PayUnit.PER_HOUR -> "시간당 정액"
            PayUnit.PER_TIME -> "1회 정액" + (coverMinutes?.let { " (${PayCalculator.hours(it)}까지)" } ?: "")
            else -> describeUnit()
        } + ticked.joinToString("") {
            " + ${it.name} ${ExtraRule.percent(it.rate)}(${scopeText(it.scope, nightLabel)}${if (it.offDaysOnly) ", 쉬는 날·휴일만" else ""})"
        }
        else -> describeBase()
    }) +
        (if (minMinutes > 0) ", 최소 ${PayCalculator.hours(minMinutes)} 인정" else "") +
        (if (basis != ExtraBasis.LEGAL && basis != ExtraBasis.UNIT && legalPremiums) " + 연장·휴일 가산" else "") +
        (if (basis != ExtraBasis.LEGAL && basis != ExtraBasis.UNIT && !nightPremium) ", 야간 가산 없음" else "") +
        (if (bonus > 0) " + 1회 수당" else "")

private fun sendReport(context: Context, subject: String, body: String) {
    val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$REPORT_EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    try {
        context.startActivity(mail)
    } catch (e: ActivityNotFoundException) {
        // No mail app: any app that can send text, with the address in the text.
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, "받는 곳: $REPORT_EMAIL\n\n$body")
        }
        context.startActivity(Intent.createChooser(send, "회사 방식 보내기"))
    }
}
