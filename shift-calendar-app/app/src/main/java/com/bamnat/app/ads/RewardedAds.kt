package com.bamnat.app.ads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.review.InAppReview.findActivity
import com.bamnat.app.tier.Gate
import com.bamnat.app.tier.Tier
import com.bamnat.core.tier.AdRules
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.time.LocalDate
import com.bamnat.core.time.Today

/**
 * [MON-07] The one rewarded ad (결정 1: D), and only when the user taps for it: what it gives is said
 * before it plays (AdMob 보상형 정책). Watched to the end, the pro features open until midnight, once
 * a day. This is the only file that may use a rewarded ad (AdAndLicenseRulesTest).
 */
@Composable
fun DayPassButton(gate: Gate, modifier: Modifier = Modifier) {
    val c = LocalContext.current.appContainer
    val config by c.remoteConfig.config.collectAsStateWithLifecycle()
    val pro by c.proStore.state.collectAsStateWithLifecycle()
    val ready by c.ads.ready.collectAsStateWithLifecycle()
    val today = Today.date()
    val offer = ready && AdIds.rewarded.isNotBlank() && !gate.preview &&
        AdRules.rewardedOffer(config.billingLive, gate.tier >= Tier.PRO, true, pro.dayPass, today)
    if (!offer) return
    val context = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(
            onClick = {
                val activity = context.findActivity() ?: return@OutlinedButton
                loading = true
                failed = false
                RewardedAd.load(activity, AdIds.rewarded, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        loading = false
                        ad.show(activity) {
                            c.proStore.dayPass(Today.date())
                            c.featureGate.refresh()
                        }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loading = false
                        failed = true
                    }
                })
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (loading) "광고를 불러오는 중…" else "광고 보고 오늘 하루 열기") }
        Text(
            if (failed) "광고를 불러오지 못했어요. 잠시 뒤 다시 해 주세요."
            else "광고 한 편을 끝까지 보면 오늘 밤 12시까지 프로 기능이 열려요. 하루 한 번이에요.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
