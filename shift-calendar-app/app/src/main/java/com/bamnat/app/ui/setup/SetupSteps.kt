package com.bamnat.app.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bamnat.app.ui.appViewModel
import com.bamnat.app.ui.company.CompanyDetailScreen
import com.bamnat.app.ui.company.CompanyDetailViewModel
import com.bamnat.app.ui.company.CompanyFindScreen
import com.bamnat.app.ui.company.CompanyFindViewModel
import com.bamnat.app.ui.components.MinutesStepper
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.app.ui.shifts.ShiftTypeEditorDialog
import com.bamnat.app.ui.work.icon
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.BundleShift
import com.bamnat.core.schedule.ShiftBundles
import com.bamnat.core.setup.CompanyTemplates
import com.bamnat.core.setup.SetupDraft
import com.bamnat.core.setup.SetupSource
import com.bamnat.core.work.WorkStyle
import java.time.LocalTime

// ---- 1. how the user works ----

private data class StyleChoice(val style: WorkStyle, val roster: Boolean, val title: String, val subtitle: String, val icon: ImageVector)

/** Shift work is asked as two answers, since a roster painted every month is set up differently. */
private val STYLE_CHOICES = listOf(
    StyleChoice(WorkStyle.SHIFT, false, "교대 근무 · 순서가 돌아가요", "예: 4조 2교대, 3조 3교대, 24시간 격일제", WorkStyle.SHIFT.icon()),
    StyleChoice(WorkStyle.SHIFT, true, "교대 근무 · 근무표가 매달 나와요", "예: 병원 간호, 매달 짜는 근무표", Icons.Filled.EditCalendar),
    StyleChoice(WorkStyle.DAY, false, "평일 근무", "매주 같은 요일, 같은 시간에 일해요", WorkStyle.DAY.icon()),
    StyleChoice(WorkStyle.HOURLY, false, "아르바이트 · 시급", "일한 시간만큼 시급으로 받아요", WorkStyle.HOURLY.icon()),
    StyleChoice(WorkStyle.FIELD, false, "현장 · 프리랜서", "일감과 받을 돈을 달력에 적어요 · 고르면 바로 시작", WorkStyle.FIELD.icon()),
    StyleChoice(WorkStyle.BUSINESS, false, "자영업", "매일 매출과 경비를 달력에 적어요 · 고르면 바로 시작", WorkStyle.BUSINESS.icon()),
)

@Composable
internal fun StyleStep(
    d: SetupDraft, onPick: (WorkStyle, Boolean) -> Unit, onOpenFile: () -> Unit,
    /** [MCAL-02] The name of a calendar being added; null on the first launch. */
    forCalendar: String? = null,
) {
    if (forCalendar != null) {
        StepHeader("$forCalendar 달력은 어떻게 일하세요?", "처음 시작할 때처럼 하나씩 물어볼게요. 이 달력에만 들어가고 다른 달력은 그대로예요.")
    } else {
        StepHeader("어떻게 일하세요?", "고르면 바로 다음 질문으로 넘어가요. 나중에 설정에서 바꿀 수 있어요.")
    }
    STYLE_CHOICES.forEach { c ->
        ChoiceCard(
            title = c.title,
            subtitle = c.subtitle,
            icon = c.icon,
            selected = d.style == c.style && (c.style != WorkStyle.SHIFT || d.rosterMonthly == c.roster),
            onClick = { onPick(c.style, c.roster) },
        )
    }
    FileCard("파일로 시작하기",
        if (forCalendar != null) "동료가 보낸 회사 설정 파일이 있으면 골라 주세요" else "동료가 보낸 회사 설정 파일이나, 쓰던 백업 파일이 있으면 골라 주세요",
        onOpenFile)
}

/** The answer to "어떻게 일하세요?" as it was given. */
internal fun styleTitle(d: SetupDraft): String =
    STYLE_CHOICES.firstOrNull { it.style == d.style && (it.style != WorkStyle.SHIFT || it.roster == d.rosterMonthly) }?.title
        ?: d.style?.label.orEmpty()

@Composable
private fun FileCard(title: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.FileOpen, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- 2. the company ----

@Composable
internal fun CompanyStep(vm: SetupViewModel, d: SetupDraft, onSearch: (() -> Unit)?, onOpenFile: () -> Unit) {
    StepHeader(
        "어느 회사에서 일하세요?",
        if (onSearch != null) "같은 회사 분들이 나눈 설정이 있으면 그대로 가져와요. 회사 이름은 몰라도 괜찮아요."
        else "회사 이름은 나중에 같은 회사 분들과 설정을 나눌 때만 써요. 비워 둬도 괜찮아요.",
    )
    // Typed here and sent on: the draft would put the cursor back on every letter.
    var name by remember { mutableStateOf(d.companyName) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = name,
        onValueChange = { v ->
            name = v.take(40)
            vm.setCompanyName(name)
        },
        label = { Text("회사 이름 (선택)") },
        placeholder = { Text("예: 예시정밀") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (onSearch != null) {
        OutlinedButton(onClick = onSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Search, null)
            Spacer(Modifier.width(8.dp))
            Text("같은 회사 설정 찾아보기")
        }
    }
    val source = d.source
    if (source != null) {
        SourceCard(source, d, onChange = vm::clearSource)
        return
    }
    Spacer(Modifier.size(4.dp))
    Text("비슷한 근무로 빠르게 시작하기", style = MaterialTheme.typography.titleSmall)
    Text(
        "${CompanyTemplates.BADGE}. 고른 뒤 시간과 순서를 한 번씩 확인해요.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // A roster painted every month goes with the templates that work that way first.
    vm.templates.sortedBy { (it.roster != d.rosterMonthly) && d.style == WorkStyle.SHIFT }.forEach { t ->
        ChoiceCard(title = t.name, subtitle = t.summary, selected = false, onClick = { vm.pickTemplate(t) })
    }
    FileCard("동료가 보낸 회사 설정 파일이 있어요", "근무 시간과 순서를 그대로 가져와요", onOpenFile)
}

/** Where the shifts came from, with a way back to choosing. */
@Composable
private fun SourceCard(source: SetupSource, d: SetupDraft, onChange: () -> Unit) {
    val kind = when (source.kind) {
        SetupSource.Kind.TEMPLATE -> CompanyTemplates.BADGE
        SetupSource.Kind.SERVER -> if (source.adopted) "같은 회사 분들이 확인한 설정" else "같은 회사 분이 나눈 설정 · 확인 중"
        SetupSource.Kind.FILE -> "동료가 보낸 회사 설정 파일"
    }
    val title = when {
        source.kind == SetupSource.Kind.FILE && source.name.isBlank() -> "받은 회사 설정"
        source.kind == SetupSource.Kind.SERVER && source.dept.isNotBlank() -> "${source.name} · ${source.dept}"
        else -> source.name
    }
    ChoiceCard(
        title = title,
        subtitle = kind,
        selected = true,
        onClick = {},
        extra = {
            BadgeStrip(d.workTypes.map { it.id } + d.types.filter { it.isOff }.map { it.id }, d.types.associateBy { it.id },
                Modifier.padding(top = 6.dp))
        },
    )
    TextButton(onClick = onChange) {
        Icon(Icons.Filled.SwapHoriz, null)
        Spacer(Modifier.width(6.dp))
        Text("다른 걸로 바꾸기")
    }
}

/** [COMM-01] 회사 찾기 inside the first setup: a setup is only picked here and saved with the rest. */
@Composable
internal fun CompanySearchDialog(vm: SetupViewModel, initialQuery: String, onDismiss: () -> Unit) {
    var openId by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val id = openId
            if (id == null) {
                val find = appViewModel { CompanyFindViewModel(it) }
                // The name typed so far, once each time the search opens; never over what the user typed.
                LaunchedEffect(find) { if (find.query.value.isBlank()) find.query.value = initialQuery.trim() }
                CompanyFindScreen(
                    vm = find,
                    setup = true,
                    onBack = onDismiss,
                    onOpen = { openId = it },
                    onByHand = { name ->
                        if (name.isNotBlank()) vm.setCompanyName(name)
                        onDismiss()
                    },
                )
            } else {
                val detail = appViewModel(key = "setup-company-$id") { CompanyDetailViewModel(it, id) }
                CompanyDetailScreen(
                    vm = detail,
                    setup = true,
                    onBack = { openId = null },
                    onApplied = { _, _ -> onDismiss() },
                    onByHand = { name ->
                        vm.setCompanyName(name)
                        onDismiss()
                    },
                    onPick = { page, setup ->
                        vm.pickServer(page, setup)
                        onDismiss()
                    },
                )
            }
        }
    }
}

// ---- 3. bundles ----

@Composable
internal fun BundleStep(d: SetupDraft, onPick: (String) -> Unit) {
    StepHeader("하루 근무가 몇 가지예요?", "가장 비슷한 걸 고르세요. 시간은 다음 화면에서 바로 고칠 수 있어요.")
    ShiftBundles.all.forEach { b ->
        ChoiceCard(
            title = b.name,
            subtitle = b.description,
            selected = d.bundle == b.key,
            onClick = { onPick(b.key) },
            extra = { BundleBadges(b.shifts) },
        )
    }
    ChoiceCard(
        title = "직접 만들기",
        subtitle = "근무 이름과 시간을 하나씩 정해요",
        icon = Icons.Filled.Add,
        selected = d.bundle == SetupDraft.CUSTOM,
        onClick = { onPick(SetupDraft.CUSTOM) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BundleBadges(shifts: List<BundleShift>) {
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        shifts.forEach { s -> ShiftBadge(s.type, size = 22.dp, text = s.type.label, outlined = s.type.isOff) }
    }
}

// ---- 4. times ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimesStep(vm: SetupViewModel, d: SetupDraft) {
    StepHeader(
        when (d.style) {
            WorkStyle.HOURLY -> "일하는 시간대가 맞나요?"
            else -> "근무 시간이 맞나요?"
        },
        when (d.style) {
            WorkStyle.HOURLY -> "시각을 누르면 바로 고쳐요. 안 하는 시간대는 연필을 눌러 지우세요."
            else -> "회사 근무표와 다르면 시각을 눌러 바로 고쳐요."
        },
    )
    var editing by remember { mutableStateOf<ShiftType?>(null) }
    d.workTypes.forEach { t -> ShiftTimeCard(t, onChange = vm::saveType, onMore = { editing = t }) }
    OutlinedButton(onClick = { editing = vm.newType() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(Icons.Filled.Add, null)
        Spacer(Modifier.width(6.dp))
        Text("근무 더하기")
    }
    val offs = d.types.filter { it.isOff || !it.hasTimes }
    if (offs.isNotEmpty()) {
        Text("쉬는 날", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            offs.forEach { t ->
                AssistChip(
                    onClick = { editing = t },
                    label = { Text(t.name) },
                    leadingIcon = { ShiftBadge(t, size = 18.dp, outlined = true) },
                )
            }
        }
    }
    d.timeProblems().forEach { p -> Text(p, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

    editing?.let { t ->
        val known = d.types.any { it.id == t.id }
        // The last work shift stays; an off day or one of several shifts can go.
        val canDelete = known && (t.isOff || d.workTypes.size > 1)
        ShiftTypeEditorDialog(
            initial = t,
            onDismiss = { editing = null },
            onSave = {
                vm.saveType(it)
                editing = null
            },
            onDelete = if (canDelete) ({
                vm.removeType(t.id)
                editing = null
            }) else null,
            showAlarms = false,
        )
    }
}

/** One work shift: its two times as big buttons, the break and what is left as work. */
@Composable
private fun ShiftTimeCard(t: ShiftType, onChange: (ShiftType) -> Unit, onMore: () -> Unit) {
    var picking by remember { mutableStateOf<Boolean?>(null) }
    val start = t.start ?: LocalTime.of(9, 0)
    val end = t.end ?: LocalTime.of(18, 0)
    val overnight = !end.isAfter(start)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShiftBadge(t, size = 28.dp, text = t.label.ifBlank { t.name.take(1).ifBlank { "?" } })
                Text(t.name.ifBlank { "이름 없는 근무" }, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp).weight(1f))
                IconButton(onClick = onMore) { Icon(Icons.Filled.Edit, "${t.name} 이름·색 고치기") }
            }
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeBox("시작", start, Modifier.weight(1f)) { picking = true }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                TimeBox(if (overnight) "끝 · 다음 날" else "끝", end, Modifier.weight(1f)) { picking = false }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("쉬는 시간", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                MinutesStepper(
                    minutes = t.breakMinutes,
                    onChange = { onChange(t.copy(breakMinutes = it)) },
                    step = 10,
                    max = 4 * 60,
                    dialogTitle = "${t.name} 쉬는 시간",
                )
            }
            Text(
                "실제 근무 ${HoursFormat.korean(t.workMinutes)}" + if (t.breakMinutes > 0) " (쉬는 시간 빼고)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    picking?.let { isStart ->
        TimePickerDialog(
            title = if (isStart) "${t.name} 시작 시각" else "${t.name} 끝나는 시각",
            initial = if (isStart) start else end,
            onDismiss = { picking = null },
        ) { time ->
            picking = null
            onChange(if (isStart) t.copy(start = time, end = end) else t.copy(start = start, end = time))
        }
    }
}

@Composable
private fun TimeBox(label: String, time: LocalTime, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(com.bamnat.app.ui.Fmt.time(time), style = MaterialTheme.typography.headlineSmall)
        }
    }
}
