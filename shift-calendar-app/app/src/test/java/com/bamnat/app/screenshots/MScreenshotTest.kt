package com.bamnat.app.screenshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [REL-14] 326 (검토 E5): 묶음 M's screenshots, numbers 660~679. Tablets: a 7-inch tablet held upright is as wide as
 * an unfolded fold (Medium width), so it gets the rail and the two-pane calendar on purpose (결정 M-E5); a 10-inch
 * one on its side gets the same with more room.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MScreenshotTest : ScreenshotBase() {
    @Test fun tablet7Portrait() = shoot("660_tablet7_portrait_calendar", TABLET_7, clean())
    @Test fun tablet10Landscape() = shoot("661_tablet10_landscape_calendar", TABLET_10_LAND, clean())

    private companion object {
        const val TABLET_7 = "w600dp-h960dp-port-320dpi"
        const val TABLET_10_LAND = "w1280dp-h800dp-land-240dpi"
    }
}
