package com.bamnat.app.ui.company

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.core.profile.AskScheduler
import java.time.LocalDate

/**
 * [COMM-12] Ways into 회사 규칙 광장 where the company matters (점검 보고서 중-10: it was only under 설정 › 회사 규칙
 * 광장). Hidden without the company server, as before; nothing here sends anything until the user opens the plaza.
 */
object PlazaEntry {
    @Volatile private var query: String? = null

    /** The plaza opens with [company] already in its search box. */
    fun open(company: String) { query = company }

    internal fun takeQuery(): String = query.orEmpty().also { query = null }

    /** The name the user gave in the first setup, while no coworker's setup is in use; null otherwise. */
    fun knownCompany(c: AppContainer): String? =
        c.companyStore.draft.value?.company?.trim()?.takeIf { it.length >= 2 && c.companyStore.applied.value == null }
}

/**
 * [COMM-12] Above the pay: the user named their company but uses no coworker's rules yet. A tip, so only on a
 * day no question card was shown ([AskScheduler.tipAllowed]); "괜찮아요" puts it away for that name.
 */
@Composable
fun PlazaEntryCard(c: AppContainer, calendar: String, onOpen: () -> Unit, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    if (!c.companyApi.enabled || calendar != PersonalCalendar.MAIN) return
    val draft by c.companyStore.draft.collectAsStateWithLifecycle()
    val applied by c.companyStore.applied.collectAsStateWithLifecycle()
    val closed by c.companyStore.plazaEntryClosed.collectAsStateWithLifecycle()
    val asks by c.asks.states.collectAsStateWithLifecycle()
    val company = draft?.company?.trim()?.takeIf { it.length >= 2 && applied == null } ?: return
    if (closed == company || !AskScheduler.tipAllowed(asks[calendar] ?: c.asks.of(calendar), today)) return
    SoftCard(modifier, highlighted = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Forum, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("‘$company’ 분들이 올린 규칙이 있는지 볼까요?", style = MaterialTheme.typography.titleSmall)
            }
            Text("같은 회사 분들이 맞춘 근무표와 수당 계산을 그대로 가져올 수 있어요. 금액은 보내지도 가져오지도 않아요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { PlazaEntry.open(company); onOpen() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("같은 회사 분들 규칙 보기") }
                TextButton(onClick = { c.companyStore.closePlazaEntry(company) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("괜찮아요") }
            }
        }
    }
}

/** [COMM-12] One line under the pay: the plaza with the user's company typed in, or the plaza itself. */
@Composable
fun PlazaEntryLine(c: AppContainer, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (!c.companyApi.enabled) return
    val draft by c.companyStore.draft.collectAsStateWithLifecycle()
    val applied by c.companyStore.applied.collectAsStateWithLifecycle()
    val company = applied?.company ?: draft?.company?.trim()?.takeIf { it.length >= 2 }
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button) { company?.let(PlazaEntry::open); onOpen() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Forum, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                applied != null -> "‘${applied?.company}’ 규칙 광장 · 토론과 바뀐 기록"
                company != null -> "‘$company’ 분들이 올린 규칙 보기"
                else -> "회사 규칙 광장 · 같은 회사 분들 규칙 보기"
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}
