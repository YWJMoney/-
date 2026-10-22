package com.bamnat.app.ui.pro

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bamnat.app.ads.DayPassButton
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Gate
import com.bamnat.app.ui.settings.SettingsGo
import com.bamnat.app.ui.settings.SettingsLinks

/**
 * [MON-04] A pro feature for someone without 프로: drawn with the user's own numbers, then blurred
 * (Android 12+; a cover below it), with a lock, the way to try 프로 and, once a day, a rewarded ad.
 * Nothing under it can be tapped or read out. With 프로, or before the launch, it is just [content].
 */
@Composable
fun ProLock(feature: Feature, gate: Gate, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (gate.allows(feature)) {
        Box(modifier) { content() }
        return
    }
    Box(modifier.heightIn(min = MIN_HEIGHT)) {
        Box(Modifier.then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(10.dp) else Modifier).clearAndSetSemantics { }) { content() }
        Box(
            Modifier.matchParentSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.35f else 0.9f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "프로 알아보기") { openPro() },
        )
        LockPanel(feature, gate, Modifier.align(Alignment.Center).padding(12.dp))
    }
}

@Composable
private fun LockPanel(feature: Feature, gate: Gate, modifier: Modifier) {
    Surface(modifier.widthIn(max = 360.dp), shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(feature.label, style = MaterialTheme.typography.titleSmall)
            }
            Text("프로에서 쓸 수 있어요. 흐리게 보이는 건 내 기록으로 만든 화면이에요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = ::openPro, modifier = Modifier.fillMaxWidth()) { Text(proButtonText()) }
            DayPassButton(gate)
        }
    }
}

/** "1개월 무료로 써 보기" while Play offers this account the trial, otherwise "프로 알아보기". */
@Composable
internal fun proButtonText(): String {
    val plans = com.bamnat.app.billing.rememberPlans()
    return if (plans.any { it.trial }) "1개월 무료로 써 보기" else "프로 알아보기"
}

fun openPro() = SettingsLinks.go(SettingsGo.PRO)

private val MIN_HEIGHT = 200.dp
