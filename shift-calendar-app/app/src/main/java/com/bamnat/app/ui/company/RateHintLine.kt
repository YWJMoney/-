package com.bamnat.app.ui.company

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.company.Plaza
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RateHint
import java.time.YearMonth
import com.bamnat.core.time.Today

/**
 * [DAILY-16] 결정 D36: a card on the pay screen when the user's 일당·시급·월급 is 10% or more off the 광장 시세 of
 * their own job, region and kind of pay. Nothing shows when the phone has no such group. Worked out here;
 * 설정 › 급여와 연차 turns it off.
 */
@Composable
fun RateHintLine(profile: PayProfile?, onOpenRates: () -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current.appContainer
    val settings by c.settingsRepository.settings.collectAsStateWithLifecycle()
    val groups by c.rateHints.groups.collectAsStateWithLifecycle()
    val own by c.rateHints.own.collectAsStateWithLifecycle()
    if (!settings.rateHint || profile == null) return
    val unit = RateHint.unitOf(profile.wageType) ?: return
    val who = c.rateHints.who(profile.occupation, own)
    val hint = RateHint.of(groups, profile.wageType, profile.base.on(Today.month()).value, who) ?: return
    SectionCard(title = "광장 시세와 견주기") {
        Text(hint.text(Plaza.unit(unit), Plaza.region(hint.group.region)), style = MaterialTheme.typography.bodyMedium)
        Text("같은 직군(${Plaza.job(hint.group.job)})·지역·${Plaza.unit(unit)}끼리 휴대폰 안에서만 견줘요. 설정 › 급여와 연차에서 끌 수 있어요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenRates) { Text("광장 시세 보기") }
    }
}
