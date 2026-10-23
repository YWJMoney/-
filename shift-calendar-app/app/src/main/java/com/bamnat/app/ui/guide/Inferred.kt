package com.bamnat.app.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.ui.components.FormSheet
import kotlinx.coroutines.launch
import com.bamnat.core.profile.Fact
import com.bamnat.core.profile.Source
import kotlin.math.roundToInt

/** [EASY-06] Where a fact came from, in a few words: "명세서 사진에서 읽음 · 80%". */
fun Fact<*>.origin(): String = when (source) {
    Source.TYPED -> "직접 넣었어요"
    Source.SHARED -> "${why ?: "같은 회사 분들의 설정"}에서 받았어요"
    Source.INFERRED -> if (why == null) "급여 설정에서 ‘대략·모르겠어요’로 넣었어요"
        else if (why == com.bamnat.core.profile.Profile.RECHECK) "‘모두 처음부터’로 다시 확인하는 값이에요"
        else "${why}에서 읽었어요 · ${(confidence * 100).roundToInt()}% 확신"
    Source.DEFAULT -> "몰라서 법대로 가정했어요"
}

/** [EASY-06] The small '추정' mark beside a guessed value; a tap says where it came from. */
@Composable
fun InferredBadge(fact: Fact<*>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!fact.estimated) return
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer,
        modifier = modifier.heightIn(min = 24.dp).semantics { role = Role.Button; contentDescription = "${fact.key.label} 추정, 어디서 왔는지 보기" },
    ) {
        Text("추정", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** [EASY-06] "어디서 왔어요?": the source, how sure, and the two ways on (맞아요 makes it the user's own). */
@Composable
fun FactOriginSheet(fact: Fact<*>, onConfirm: () -> Unit, onFix: () -> Unit, onDismiss: () -> Unit) {
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text("${fact.key.label}은 어디서 왔어요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fact.origin(), style = MaterialTheme.typography.bodyLarge)
                Text("맞으면 그대로 두고, 다르면 고쳐 주세요. 고친 값은 직접 넣은 값이 되고 다시 추정하지 않아요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("맞아요") }
            }
        },
        confirmButton = { TextButton(onClick = onFix) { Text("고치기") } },
    )
}

/**
 * [EASY-07] "추정 n개 확인" before something with money in it leaves the phone: each guessed value, 맞아요 or 고치기,
 * and 이대로 보내기 for the rest.
 */
@Composable
fun ExportCheckSheet(
    facts: List<Fact<*>>,
    onConfirm: (Fact<*>) -> Unit,
    onFix: () -> Unit,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
) {
    FormSheet(
        onDismissRequest = onDismiss,
        title = { Text("추정 ${facts.size}개 확인") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("아래 값은 직접 넣지 않아 추정으로 계산했어요. 보내기 전에 맞는지 한 번만 봐 주세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                facts.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.key.label, style = MaterialTheme.typography.titleSmall)
                            Text(f.origin(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onConfirm(f) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("맞아요") }
                    }
                }
                TextButton(onClick = onFix, modifier = Modifier.heightIn(min = 48.dp)) { Text("급여 설정에서 고치기") }
            }
        },
        confirmButton = { Button(onClick = onGo, modifier = Modifier.heightIn(min = 48.dp)) { Text("이대로 보내기") } },
    )
}

/**
 * [EASY-06] Under the pay setup's summary: the values it is worked out from that are still guesses, each with its
 * '추정' mark and "어디서 왔어요?". Nothing guessed, nothing shown.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EstimatedFacts(calendar: String, onFix: () -> Unit, modifier: Modifier = Modifier) {
    val c = androidx.compose.ui.platform.LocalContext.current.appContainer
    val pay by androidx.compose.runtime.remember(calendar) { c.scopeOf(calendar).pay.profile }.collectAsStateWithLifecycle()
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val facts by androidx.compose.runtime.produceState(emptyList<Fact<*>>(), calendar, pay, tick) {
        value = runCatching { c.profiles.facts(calendar).values.filter { it.estimated } }.getOrDefault(emptyList())
    }
    if (facts.isEmpty()) return
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Fact<*>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("추정으로 계산한 값 ${facts.size}개", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            facts.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.key.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp))
                    InferredBadge(f, onClick = { open = f })
                }
            }
        }
    }
    open?.let { f ->
        FactOriginSheet(f,
            onConfirm = { open = null; scope.launch { c.profiles.mark(calendar, f.key, Source.TYPED, why = "확인함"); tick++ } },
            onFix = { open = null; onFix() },
            onDismiss = { open = null })
    }
}
