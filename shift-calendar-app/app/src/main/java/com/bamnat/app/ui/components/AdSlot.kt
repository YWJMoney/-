package com.bamnat.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.ads.AdIds
import com.bamnat.app.appContainer
import com.bamnat.app.tier.Feature
import com.bamnat.core.tier.AdRules
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import java.time.Instant

/**
 * [REL-02][MON-07] The one place a banner goes (결정 1: A): between the content and the bottom bar
 * (phones, folded) or under the content pane (Fold unfolded), and only on the 근무·급여 and 돈 tabs
 * ([AdRules.banner]). Calendar cells and buttons never sit under it, so it cannot cover anything the
 * user taps. Nothing before the 정식 출시 switch, for 프로, in the first days after install, or in a
 * build without an ad unit id.
 */
@Composable
fun AdSlot(place: AdRules.Place, modifier: Modifier = Modifier) {
    val c = LocalContext.current.appContainer
    val gate by c.featureGate.state.collectAsStateWithLifecycle()
    val config by c.remoteConfig.config.collectAsStateWithLifecycle()
    val ready by c.ads.ready.collectAsStateWithLifecycle()
    val show = ready && AdIds.banner.isNotBlank() && !gate.preview &&
        AdRules.banner(place, config.billingLive, gate.allows(Feature.NO_ADS), true, c.installedAt, Instant.now(), config.adQuietDays)
    if (!show) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth.value.toInt()
        val context = LocalContext.current
        val size = remember(width) { AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, width) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val view = remember(size) {
            AdView(context).apply {
                setAdSize(size)
                adUnitId = AdIds.banner
                loadAd(AdRequest.Builder().build())
            }
        }
        DisposableEffect(view, lifecycle) {
            val watch = LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_PAUSE -> view.pause()
                    Lifecycle.Event.ON_RESUME -> view.resume()
                    else -> Unit
                }
            }
            lifecycle.addObserver(watch)
            onDispose {
                lifecycle.removeObserver(watch)
                view.destroy()
            }
        }
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(size.height.dp))
    }
}
