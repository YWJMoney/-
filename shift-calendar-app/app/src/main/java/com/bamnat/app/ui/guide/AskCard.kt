package com.bamnat.app.ui.guide

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.data.GuideStore
import com.bamnat.app.ui.components.CountStepper
import com.bamnat.app.ui.components.FilterChip
import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.guide.Moment
import com.bamnat.core.profile.Answer
import com.bamnat.core.profile.Ask
import com.bamnat.core.profile.AskPlace
import com.bamnat.core.profile.AskScheduler
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.Needs
import com.bamnat.core.profile.Question
import com.bamnat.core.profile.Questions
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [EASY-05] The question card: one fact a feature that is on still waits for, asked where it is first used (the
 * calendar's own card, or above a feature's screen), at most [AskScheduler.PER_DAY] a day. Each answer is one
 * tap, one number, one date or the screen that asks it, and goes where that fact has always been kept.
 */
@Composable
fun AskArea(
    c: AppContainer,
    calendar: String,
    style: WorkStyle?,
    place: AskPlace,
    modifier: Modifier = Modifier,
    moments: Set<Moment> = emptySet(),
    today: LocalDate = LocalDate.now(),
    /** Above a feature's own screen: only what the card itself answers, not "open this screen". */
    inCardOnly: Boolean = false,
) {
    // Not on the example: what it would ask is the example's, not the user's.
    if (calendar == com.bamnat.app.data.PersonalCalendar.EXAMPLE) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val guide by remember { GuideStore.get(context) }.state.collectAsStateWithLifecycle()
    val askStates by c.asks.states.collectAsStateWithLifecycle()
    val settings by c.settingsRepository.settings.collectAsStateWithLifecycle()
    val pay by remember(calendar) { c.scopeOf(calendar).pay.profile }.collectAsStateWithLifecycle()
    var answered by remember { mutableIntStateOf(0) }
    var facts by remember(calendar) { mutableStateOf<Map<FactKey, com.bamnat.core.profile.Fact<*>>>(emptyMap()) }
    val ask by produceState<Ask?>(null, calendar, style, guide, askStates, settings, pay, answered, today, moments, place, inCardOnly) {
        facts = c.profiles.facts(calendar)
        val on = FeatureRegistry.of(style).filter { !guide.isOff(it, calendar) }.map { it.id }
        val missing = Needs.of(facts, on) { key -> daysUntil(key, pay?.period?.payDay, today) }
            .filter { !inCardOnly || Questions.of(it.key, hasPay = pay != null).answer !is Answer.Open }
        value = AskScheduler.pick(c.asks.of(calendar), missing, place, today, moments)
    }
    val current = ask ?: return
    LaunchedEffect(current.missing.key, today) {
        if (current.isNew) c.asks.set(calendar, AskScheduler.shown(c.asks.of(calendar), current, today))
    }
    val scope = rememberCoroutineScope()
    AskCard(
        question = Questions.of(current.missing.key, hasPay = pay != null),
        reward = current.reward,
        onAnswer = { value -> scope.launch { if (c.answers.answer(calendar, current.missing.key, value)) answered++ } },
        onOpen = { feature -> FeatureRegistry.get(feature)?.let(FeatureLinks::open) },
        onLater = { c.asks.set(calendar, AskScheduler.later(c.asks.of(calendar), current.missing.key, today)) },
        modifier = modifier,
        // [EASY-09] A value 모두 처음부터 asks again starts from what it is.
        current = facts[current.missing.key]?.let(com.bamnat.core.profile.FactText::answerOf),
    )
}

/** [EASY-04] When a fact is next needed: the next 급여일 for the pay itself (the 25th while it is not known), the next shift for the alarm. */
internal fun daysUntil(key: FactKey, payDay: Int?, today: LocalDate): Int? = when (key) {
    FactKey.PAY_DAY, FactKey.BASE_PAY, FactKey.WAGE_TYPE -> {
        val day = (payDay ?: 25).coerceAtMost(today.lengthOfMonth())
        val next = if (today.dayOfMonth <= day) today.withDayOfMonth(day)
            else today.plusMonths(1).let { it.withDayOfMonth(day.coerceAtMost(it.lengthOfMonth())) }
        java.time.temporal.ChronoUnit.DAYS.between(today, next).toInt()
    }
    FactKey.ALARM_LEAD -> 1
    else -> null
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AskCard(
    question: Question,
    reward: String,
    onAnswer: (String) -> Unit,
    onOpen: (String) -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
    current: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.HelpOutline, null, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(question.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text(reward, style = MaterialTheme.typography.bodySmall)
                }
            }
            AnswerInput(question, onAnswer, onOpen, current)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text("나중에") }
            }
        }
    }
}

/** [EASY-05][EASY-08] The answer of [question]: chips, a number, a date or the screen that asks it. Also the profile's edit sheet. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AnswerInput(question: Question, onAnswer: (String) -> Unit, onOpen: (String) -> Unit, current: String? = null) {
    when (val a = question.answer) {
        is Answer.Chips -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            a.options.forEach { (label, value) ->
                FilterChip(selected = value == current, onClick = { onAnswer(value) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        is Answer.Number -> {
            var n by rememberSaveable(question.key) { mutableIntStateOf(current?.toIntOrNull()?.coerceIn(a.min, a.max) ?: a.start) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CountStepper(n, { n = it }, question.key.label, range = a.min..a.max, step = a.step, format = { "$it${a.unit}" })
                Spacer(Modifier.weight(1f))
                Button(onClick = { onAnswer("$n") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("이대로") }
            }
        }
        Answer.Date -> {
            var picking by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("날짜 고르기") }
            if (picking) {
                val state = rememberDatePickerState()
                DatePickerDialog(
                    onDismissRequest = { picking = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { onAnswer(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                            picking = false
                        }) { Text("확인") }
                    },
                    dismissButton = { TextButton(onClick = { picking = false }) { Text("취소") } },
                ) { DatePicker(state, showModeToggle = false) }
            }
        }
        is Answer.Open -> Button(onClick = { onOpen(a.feature) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(a.label) }
    }
}
