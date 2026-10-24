package com.bamnat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bamnat.core.pay.RuleDraft
import com.bamnat.core.pay.RuleDraft.Way
import com.bamnat.core.pay.RuleText

/**
 * [INPUT-03] 회사 규칙 조립기 (research/1.24-리뷰-직접입력.md Q5, N5): what work, how it is paid, how much and the
 * premiums on top, all by taps, read back as one sentence before it is put in. The sentences the user puts in
 * ([added]) are what the screen sends or reads: [RuleText.read] for the pay setup, the words of a message for the
 * developer. Whatever the taps cannot say goes in the free words the screen keeps under this.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleBuilder(added: List<String>, onAdded: (List<String>) -> Unit, modifier: Modifier = Modifier, start: RuleDraft.Target = RuleDraft.Target.COVER) {
    var draft by remember { mutableStateOf(RuleDraft().withTarget(start)) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        added.forEachIndexed { i, s ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    IconButton(onClick = { onAdded(added.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Filled.Close, "이 규칙 빼기") }
                }
            }
        }
        Text("무엇을", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RuleDraft.Target.entries.forEach { t ->
                FilterChip(selected = draft.target == t, onClick = { draft = draft.withTarget(t) }, label = { Text(t.label) })
            }
        }
        if (draft.target.ways.size > 1) {
            Text("어떻게 주나요", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.target.ways.forEach { w -> FilterChip(selected = draft.way == w, onClick = { draft = draft.withWay(w) }, label = { Text(w.label) }) }
            }
        }
        when (draft.way) {
            Way.PER_HOUR, Way.PER_TIME -> QuickDecimalChips(
                value = draft.amount.toDouble(), onChange = { draft = draft.copy(amount = it.toLong()) },
                name = if (draft.way == Way.PER_HOUR) "시간당 금액" else "1회 금액",
                quick = if (draft.way == Way.PER_HOUR) listOf(10_000.0, 15_000.0, 20_000.0) else listOf(50_000.0, 80_000.0, 100_000.0),
                step = if (draft.way == Way.PER_HOUR) 1_000.0 else 10_000.0, range = 1_000.0..2_000_000.0,
                format = { "%,d원".format(it.toLong()) }, valueWidth = 120.dp,
            )
            else -> QuickDecimalChips(
                value = draft.percent, onChange = { draft = draft.copy(percent = it) },
                name = when (draft.way) { Way.BASE_SHARE -> "기본급의 비율"; Way.ORDINARY -> "통상시급의 비율"; else -> "가산율" }, quick = RuleDraft.quick(draft.way),
                step = RuleDraft.step(draft.way), range = 1.0..500.0, unit = "%",
            )
        }
        if (draft.target.extraWork && (draft.way == Way.BASE_SHARE || draft.way == Way.PER_TIME)) {
            StepperRow(if (draft.way == Way.BASE_SHARE) "몇 시간에 그만큼" else "한 번이 몇 시간까지") {
                CountStepper(draft.unitHours, { draft = draft.copy(unitHours = it) }, name = "기준 시간", range = 1..24, format = { "${it}시간" })
            }
        }
        if (draft.asksPremiums) {
            PremiumChoice("${draft.unitHours}시간 넘으면", draft.overPremium, listOf(null, 25, 50, 100), "법대로") { draft = draft.copy(overPremium = it) }
            PremiumChoice("야간", draft.nightPremium, listOf(null, 0, 50, 100), "법대로") { draft = draft.copy(nightPremium = it) }
            PremiumChoice("휴일", draft.holidayPremium, listOf(null, 50, 100), "법대로") { draft = draft.copy(holidayPremium = it) }
        }
        val sentence = draft.sentence()
        val meaning = remember(sentence) { RuleText.read(sentence).firstOrNull()?.change?.summary }
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("“$sentence”", style = MaterialTheme.typography.bodyLarge)
                meaning?.let { Text("앱이 읽은 뜻: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
        }
        AddButton("이 규칙 담기", onClick = { onAdded(added + sentence); draft = RuleDraft().withTarget(draft.target) }, enabled = !draft.empty && sentence !in added)
    }
}

/** One premium on top of extra work: a chip per rate, [none] ("법대로": the sentence leaves it out) for null, "없음" for 0. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PremiumChoice(title: String, value: Int?, choices: List<Int?>, none: String, onPick: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title 가산", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { c ->
                FilterChip(selected = value == c, onClick = { onPick(c) }, label = { Text(when (c) { null -> none; 0 -> "없음"; else -> "$c%" }) })
            }
        }
    }
}

/**
 * [INPUT-03] What a screen sends or reads: the rules put together with [RuleBuilder], one a line, then the user's
 * own words for what the taps could not say. Blank lines are left out.
 */
fun ruleWords(built: List<String>, more: String): String = (built + more).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
