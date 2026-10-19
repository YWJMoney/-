package com.bamnat.app.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer

/**
 * [SETUP-12] A first setup left with "나중에 할게요" goes on from 설정 > 내 근무 too, where people look for work
 * settings, not only from the card on the calendar. Draws nothing when no setup is waiting. Going on is the
 * calendar card's way: the draft stops being "later", and the app opens the setup where it stopped.
 */
@Composable
fun SetupResumeCard(modifier: Modifier = Modifier) {
    val container = LocalContext.current.appContainer
    val draft by container.setupDrafts.draft.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
    val d = draft?.takeIf { it.later && settings.workStyle == null } ?: return
    val left = d.steps.size - d.position
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(16.dp)) {
            Text("근무 설정 마저 하기", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.semantics { heading() })
            Text(
                when {
                    d.style == null -> "어떻게 일하는지만 고르면 나머지는 하나씩 물어볼게요."
                    left <= 0 -> "마지막 확인만 남았어요. 고른 답은 그대로 있어요."
                    else -> "${left}단계 남았어요. 고른 답은 그대로 있어요."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(onClick = { container.setupDrafts.save(d.copy(later = false)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("이어서 하기")
            }
        }
    }
}
