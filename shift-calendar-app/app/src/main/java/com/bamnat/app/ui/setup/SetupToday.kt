package com.bamnat.app.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.core.model.ShiftType
import com.bamnat.core.setup.FollowUp
import com.bamnat.core.setup.SetupDraft
import com.bamnat.core.setup.SetupSource
import com.bamnat.core.setup.TodayQuestion
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** The rotation step [date] falls on with the draft's answer; null before the answer. */
internal fun stepOn(d: SetupDraft, date: LocalDate): Int? {
    val anchor = d.anchor ?: return null
    if (d.rotation.isEmpty()) return null
    return Math.floorMod(ChronoUnit.DAYS.between(anchor, date), d.rotation.size.toLong()).toInt()
}

/** "오늘(10/18)은 야간 둘째 날이에요. 내일부터 휴무 4일이에요." */
internal fun todaySentence(d: SetupDraft, date: LocalDate, today: LocalDate): String? {
    val step = stepOn(d, date) ?: return null
    val steps = d.rotation
    val types = d.types.associateBy { it.id }
    val day = relativeDay(date, today)
    val (pos, len) = TodayQuestion.runPosition(steps, step)
    val name = nameOf(steps[step], types)
    val first = if (len > 1) "$day(${Fmt.shortDate(date)})${Fmt.eun(day)} $name ${ordinal(pos)} 날이에요."
    else "$day(${Fmt.shortDate(date)})${Fmt.eun(day)} $name${Fmt.ieyo(name)}."
    val change = TodayQuestion.nextChange(steps, step) ?: return first
    val (inDays, next) = change
    val nextName = nameOf(steps[next], types)
    val nextLen = TodayQuestion.runPosition(steps, next).second
    val from = relativeDay(date.plusDays(inDays.toLong()), today)
    val second = if (nextLen > 1) "${from}부터 $nextName ${nextLen}일이에요." else "$from${Fmt.eun(from)} $nextName${Fmt.ieyo(nextName)}."
    return "$first $second"
}

/** A chosen answer, so a plain day off (null) can be told from no answer yet. */
private data class Pick(val id: Long?)

/**
 * [SETUP-01] "오늘 무슨 근무예요?": asked by what the user works, not by a step number. A second question
 * comes only when the shift alone does not tell the day, and the month is shown before going on.
 */
@Composable
internal fun TodayStep(vm: SetupViewModel, d: SetupDraft) {
    val todayBased by vm.todayBased.collectAsStateWithLifecycle()
    val today = vm.today
    val date = vm.questionDate(d)
    val early = vm.earlyHours(d)
    val day = relativeDay(date, today)
    StepHeader(
        "$day(${Fmt.shortDate(date)}) 무슨 근무${if (date == today) "예요" else "였어요"}?",
        "근무표에서 ${day} 칸을 보고 골라 주세요. 쉬는 날이었으면 휴무를 고르면 돼요.",
    )
    if (early) EarlyHoursNote(todayBased, today, onToday = { vm.answerForToday(!todayBased) })

    val source = d.source
    // [SETUP-14] These answers survive folding and unfolding.
    var otherTeam by rememberSaveable(source?.anchor) { mutableStateOf(false) }
    val teamAnchor = source?.anchor?.takeIf { source.kind == SetupSource.Kind.FILE }
    if (teamAnchor != null && !otherTeam) {
        TeamQuestion(d, source, teamAnchor, date, today, same = d.anchor == teamAnchor,
            onSame = vm::sameTeam, onOther = {
                otherTeam = true
                vm.clearToday()
            })
    } else {
        TodayPicker(d, date, today, onAnswer = { step -> vm.answerToday(date, step) }, onClear = vm::clearToday)
    }
    // [SETUP-14] A way out for someone who cannot tell (a roster that does not repeat, or a new job): paint the
    // month on the calendar instead, keeping the shifts already set up (I 리뷰 I14).
    if (d.anchor == null) {
        androidx.compose.material3.TextButton(onClick = vm::paintInstead, modifier = Modifier.padding(top = 4.dp)) {
            Text("잘 모르겠어요 · 달력에 직접 칠할게요")
        }
    }

    if (d.anchor != null) {
        todaySentence(d, date, today)?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Text(
            "회사 근무표와 한 달을 견줘 보세요. 다르면 위에서 다시 고르면 돼요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var monthIndex by rememberSaveable { mutableStateOf(YearMonth.from(date).let { it.year * 12 + it.monthValue - 1 }) }
        val month = YearMonth.of(monthIndex / 12, monthIndex % 12 + 1)
        val start = month.atDay(1).minusDays(7)
        SetupMonth(
            month = month,
            snapshot = remember(d.types, d.rotation, d.anchor, month) { ScheduleSnapshot(d.types, d.pattern(start), emptyList()) },
            weekStart = vm.weekStart,
            today = today,
            selected = date,
            onMonth = { monthIndex = it.year * 12 + it.monthValue - 1 },
            first = YearMonth.from(date),
            last = YearMonth.from(date).plusMonths(2),
        )
    }
}

/** [SETUP-01] In the small hours a night shift that began last night is yesterday's work. */
@Composable
private fun EarlyHoursNote(todayBased: Boolean, today: LocalDate, onToday: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bedtime, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                if (todayBased) "오늘(${Fmt.shortDate(today)}) 기준으로 묻고 있어요."
                else "지금은 새벽이라, 어젯밤 시작한 근무는 어제(${Fmt.shortDate(today.minusDays(1))}) 근무로 쳐요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            TextButton(onClick = onToday) { Text(if (todayBased) "어제로 묻기" else "오늘로 답할게요") }
        }
    }
}

/** [SETUP-02] A coworker's file says which 조 its order is: the same 조 lines up as it is. */
@Composable
private fun TeamQuestion(
    d: SetupDraft,
    source: SetupSource,
    anchor: LocalDate,
    date: LocalDate,
    today: LocalDate,
    same: Boolean,
    onSame: () -> Unit,
    onOther: () -> Unit,
) {
    val team = source.team
    val asSent = remember(d.rotation, d.types, anchor, date) { todaySentence(d.copy(anchor = anchor), date, today) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Groups, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    if (team != null) "$team 근무표예요. 같은 $team${Fmt.ieyo(team)}?" else "보낸 분과 같은 조예요?",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            asSent?.let { Text("이 근무표로는 $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ChoiceCard(title = "네, 같은 조예요", selected = same, onClick = onSame)
            ChoiceCard(title = "아니요, 다른 조예요", subtitle = "오늘 근무를 물어볼게요", selected = false, onClick = onOther)
        }
    }
}

/**
 * The shifts of the order, each once; then, only when needed, which day of a run, when the next work
 * is, or how the next days go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayPicker(d: SetupDraft, date: LocalDate, today: LocalDate, onAnswer: (Int) -> Unit, onClear: () -> Unit) {
    val steps = d.rotation
    val types = remember(d.types) { d.types.associateBy { it.id } }
    val answered = stepOn(d, date)
    // A day off picked is a null id, so the saved form is a flag and the id.
    var pickedOn by rememberSaveable(steps) { mutableStateOf(answered != null) }
    var pickedId by rememberSaveable(steps) { mutableStateOf(answered?.let { steps[it] }) }
    val picked = if (pickedOn) Pick(pickedId) else null
    // Work first, then days off, as people say them: 주간, 야간, 휴무.
    val choices = remember(steps) { TodayQuestion.choices(steps).sortedBy { if (d.isOff(it)) 1 else 0 } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { id ->
            val t = id?.let(types::get)
            ShiftAnswer(t, selected = picked == Pick(id)) {
                pickedOn = true
                pickedId = id
                if (TodayQuestion.followUp(steps, id, d::isOff) == null) onAnswer(TodayQuestion.candidates(steps, id).first())
                else onClear()
            }
        }
    }
    val pick = picked ?: return
    val follow = remember(steps, pick) { TodayQuestion.followUp(steps, pick.id, d::isOff) } ?: return
    val name = nameOf(pick.id, types)
    when (follow) {
        is FollowUp.RunDay -> {
            FollowTitle("$name 며칠째${if (date == today) "예요" else "였어요"}?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                follow.steps.forEachIndexed { i, s ->
                    FilterChip(
                        selected = answered == s,
                        onClick = { onAnswer(s) },
                        label = { Text("${ordinal(i + 1)} 날", style = MaterialTheme.typography.titleSmall) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        is FollowUp.NextWork -> {
            FollowTitle("다음 출근이 언제예요?")
            follow.options.forEach { o ->
                val t = o.type?.let(types::get)
                ChoiceCard(
                    title = "${relativeDay(date.plusDays(o.inDays.toLong()), today)} ${nameOf(o.type, types)}",
                    subtitle = t?.let { hoursOf(it) },
                    selected = answered == o.step,
                    onClick = { onAnswer(o.step) },
                )
            }
        }
        is FollowUp.Upcoming -> {
            FollowTitle("${relativeDay(date.plusDays(1), today)}부터는 어떻게 돼요?")
            follow.options.forEach { o ->
                ChoiceCard(
                    title = o.days.joinToString(" · ") { nameOf(it, types) },
                    selected = answered == o.step,
                    onClick = { onAnswer(o.step) },
                    extra = { BadgeStrip(o.days, types, Modifier.padding(top = 6.dp)) },
                )
            }
        }
    }
}

@Composable
private fun FollowTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** A shift as a big answer to tap, with its badge and hours. */
@Composable
private fun ShiftAnswer(type: ShiftType?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        modifier = Modifier.clip(MaterialTheme.shapes.large).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ShiftBadge(type, size = 28.dp, text = type?.label ?: "휴", outlined = type == null || type.isOff)
            Column(Modifier.padding(start = 10.dp)) {
                Text(type?.name ?: "휴무", style = MaterialTheme.typography.titleMedium)
                Text(hoursOf(type), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.CheckCircle, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}
