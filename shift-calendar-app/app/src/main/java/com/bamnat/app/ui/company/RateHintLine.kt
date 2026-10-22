package com.bamnat.app.ui.company

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.company.Plaza
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RateHint
import java.time.YearMonth

/**
 * [DAILY-16] 결정 D36: one line on the pay screen when the user's 일당·시급 is 10% or more off the 광장 시세
 * they last looked at. Worked out here; 설정 › 급여 turns it off.
 */
@Composable
fun RateHintLine(profile: PayProfile?, onOpenRates: () -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current.appContainer
    val settings by c.settingsRepository.settings.collectAsStateWithLifecycle()
    val groups by c.rateHints.groups.collectAsStateWithLifecycle()
    if (!settings.rateHint || profile == null) return
    val unit = RateHint.unitOf(profile.wageType) ?: return
    val hint = RateHint.of(groups, profile.wageType, profile.base.on(YearMonth.now()).value) ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            Text(hint.text(Plaza.unit(unit)), style = MaterialTheme.typography.bodyMedium)
            Text("휴대폰 안에서만 견줘요. 설정 › 급여에서 끌 수 있어요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onOpenRates) { Text("광장 시세 보기") }
        }
    }
}
