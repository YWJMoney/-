package com.shiftmate.calendar.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where ads go once they are added. The layout already reserves this slot in one place:
 * between the screen content and the bottom navigation (phones, folded) or under the
 * content pane (Fold unfolded). Calendar cells and buttons never sit under it, so turning
 * ads on later cannot cover anything the user taps.
 *
 * To enable: set [ENABLED] to true and replace the body of [AdSlot] with an AdMob
 * `AdView` inside `AndroidView` (adaptive anchored banner, width = slot width).
 */
object AdConfig {
    const val ENABLED = false
    /** Standard anchored banner height. */
    val BANNER_HEIGHT: Dp = 56.dp
}

@Composable
fun AdSlot(modifier: Modifier = Modifier) {
    if (!AdConfig.ENABLED) return
    Box(
        modifier = modifier.fillMaxWidth().height(AdConfig.BANNER_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        // Ad view goes here.
    }
}
