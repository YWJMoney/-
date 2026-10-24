package com.bamnat.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.setup.ChoiceCard
import com.bamnat.app.ui.setup.STYLE_CHOICES
import com.bamnat.app.ui.setup.StepHeader
import com.bamnat.core.work.WorkStyle

/**
 * [SETUP-15] The very first screen of a new phone: how the user works, before what the app turns on is
 * shown (점검 보고서 중-9: a weekday or part-time worker was told about coffee, naps and the way home after
 * nights first). The answer picks the next screen's notices and starts the first setup with it, so the
 * question is not asked twice. The same cards as the setup's first question.
 */
@Composable
fun FirstStyleScreen(onPick: (FirstStyle) -> Unit, onSkip: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp).semantics { paneTitle = "무슨 일 하세요?" },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepHeader("무슨 일 하세요?", "고른 일에 맞는 기능만 켜고, 다음 질문도 그 일에 맞춰 물어요. 나중에 설정에서 바꿀 수 있어요.")
                STYLE_CHOICES.forEach { c ->
                    ChoiceCard(title = c.title, subtitle = c.subtitle.substringBefore(" · "), icon = c.icon, selected = false,
                        onClick = { onPick(FirstStyle(c.style, c.roster)) })
                }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("백업이나 회사 파일로 시작할게요") }
            }
        }
    }
}

/** [SETUP-15] The answer to "무슨 일 하세요?", kept as a word across a turned phone or a process restart. */
data class FirstStyle(val style: WorkStyle, val roster: Boolean) {
    val key: String get() = style.name + if (roster) ROSTER else ""

    companion object {
        private const val ROSTER = "_ROSTER"
        fun of(key: String?): FirstStyle? {
            val k = key ?: return null
            val style = WorkStyle.entries.firstOrNull { it.name == k.removeSuffix(ROSTER) } ?: return null
            return FirstStyle(style, k.endsWith(ROSTER) && style == WorkStyle.SHIFT)
        }
    }
}
