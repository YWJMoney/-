package com.bamnat.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.bamnat.app.review.InAppReview
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * [DAILY-09] A fold half open like a laptop (삼성 Flex 모드, 탁상 자세): the hinge runs across the screen, from
 * [hingeTop] to [hingeBottom] in window pixels. The calendar puts the month above it and the day below.
 */
data class Tabletop(val hingeTop: Int, val hingeBottom: Int) {
    /** Window pixels the hinge itself covers (0 on most folds, whose screen bends over it). */
    val hinge: Int get() = (hingeBottom - hingeTop).coerceAtLeast(0)

    /**
     * How tall the upper part of an area that starts [areaTop] pixels down the window and is [height] tall
     * is, so it ends at the hinge. A hinge outside the area, or too near its edge, halves it.
     */
    fun upperHeight(areaTop: Int, height: Int): Int {
        val upper = hingeTop - areaTop
        return if (upper in height / 4..height * 3 / 4) upper else height / 2
    }

    companion object {
        /** The posture from the window's features: a half-open fold whose hinge lies across, or null. */
        fun of(features: List<androidx.window.layout.DisplayFeature>): Tabletop? =
            features.filterIsInstance<FoldingFeature>()
                .firstOrNull { it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL }
                ?.let { Tabletop(it.bounds.top, it.bounds.bottom) }
    }
}

/** The fold's half-open posture where the screen is on, set by [rememberTabletop] at the root. */
val LocalTabletop = compositionLocalOf<Tabletop?> { null }

/** [Tabletop] while the fold is half open across, null on flat phones, open folds and books held upright. */
@Composable
fun rememberTabletop(): Tabletop? {
    val context = LocalContext.current
    val flow = remember(context) {
        val activity = with(InAppReview) { context.findActivity() }
        if (activity == null) emptyFlow()
        else WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { Tabletop.of(it.displayFeatures) }
    }
    val tabletop by flow.collectAsStateWithLifecycle(null)
    return tabletop
}
