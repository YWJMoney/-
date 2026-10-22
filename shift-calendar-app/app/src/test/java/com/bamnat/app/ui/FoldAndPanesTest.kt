package com.bamnat.app.ui

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.layout.FoldingFeature
import com.bamnat.app.company.RateGroup
import com.bamnat.app.company.RateHintStore
import com.bamnat.app.ui.memo.MemoPane
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.RateHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** 1.22 묶음 E 후속: the half-open calendar [DAILY-09], the memo panes [DAILY-15] and whose 시세 is compared [DAILY-16]. */
@RunWith(AndroidJUnit4::class)
class FoldAndPanesTest {
    private fun fold(state: FoldingFeature.State, orientation: FoldingFeature.Orientation, bounds: Rect) = object : FoldingFeature {
        override val bounds = bounds
        override val isSeparating = state == FoldingFeature.State.HALF_OPENED
        override val occlusionType = FoldingFeature.OcclusionType.NONE
        override val orientation = orientation
        override val state = state
    }

    @Test
    fun onlyAHalfOpenFoldWithItsHingeAcrossSplitsTheCalendar() {
        val across = Rect(0, 1100, 1080, 1100)
        assertEquals(Tabletop(1100, 1100), Tabletop.of(listOf(fold(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL, across))))
        // Flat open, or a book held upright (the hinge runs down the screen): the usual layouts.
        assertNull(Tabletop.of(listOf(fold(FoldingFeature.State.FLAT, FoldingFeature.Orientation.HORIZONTAL, across))))
        assertNull(Tabletop.of(listOf(fold(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.VERTICAL, Rect(900, 0, 900, 2000)))))
        assertNull(Tabletop.of(emptyList()))
    }

    @Test
    fun theUpperPartEndsAtTheHingeOrHalvesWhenItCannot() {
        val t = Tabletop(1100, 1140)
        assertEquals(40, t.hinge)
        // The calendar area starts under a 100px bar and is 2000px tall: 1000px above the hinge.
        assertEquals(1000, t.upperHeight(areaTop = 100, height = 2000))
        // A hinge near the area's top or below it: half and half.
        assertEquals(1000, t.upperHeight(areaTop = 1000, height = 2000))
        assertEquals(500, Tabletop(5000, 5000).upperHeight(areaTop = 0, height = 1000))
    }

    @Test
    fun theOpenMemoSurvivesARotationAsOneLine() {
        val saver = MemoPane.Saver
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val pane = MemoPane(42, checklist = true, date = LocalDate.of(2026, 10, 22), n = 3)
        val saved = with(saver) { scope.save(pane) }!!
        assertEquals(pane, saver.restore(saved))
        assertEquals(MemoPane(0, n = 1), saver.restore(with(saver) { scope.save(MemoPane(0, n = 1)) }!!))
        assertNull(saver.restore(with(saver) { scope.save(null) }!!))
    }

    @Test
    fun theStoreKnowsTheUsersJobAndRegionOnlyFromThemselves() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("plaza_rate_hint", Context.MODE_PRIVATE).edit().clear().commit()
        val store = RateHintStore(context)
        // Nothing picked or shared: no region, so no comparison at all.
        assertNull(store.who(Occupation.NURSE))
        store.keep("31", listOf(RateGroup("construction", "31", "형틀목수", "day", 2026, 5, 230_000, 250_000, 280_000)))
        store.pick("31")
        assertEquals(RateHint.Mine("nurse", "31"), store.who(Occupation.NURSE))
        // The 경기 형틀목수 group on the phone is not a nurse's: no hint (the screenshot 496 case).
        assertNull(RateHint.of(store.groups.value, com.bamnat.core.pay.WageType.DAILY, 200_000, store.who(Occupation.NURSE)))
        // 직접 설정: the job comes from a rate the user shared, with its trade.
        assertNull(store.who(Occupation.CUSTOM))
        store.shared("construction", "31", "형틀목수")
        assertEquals(RateHint.Mine("construction", "31", "형틀목수"), store.who(Occupation.CUSTOM))
        assertEquals(-20, RateHint.of(store.groups.value, com.bamnat.core.pay.WageType.DAILY, 200_000, store.who(Occupation.CUSTOM))!!.percent)
        // Kept across a restart; a later look adds to the groups instead of replacing them.
        val again = RateHintStore(context)
        again.keep("11", listOf(RateGroup("nurse", "11", "병동", "day", 2026, 4, 180_000, 200_000, 220_000)))
        assertEquals(2, again.groups.value.size)
        assertEquals("31", again.own.value.picked)
        // Taken back: a nurse does not borrow the region of a rate shared as 형틀목수.
        again.pick("")
        assertNull(again.who(Occupation.NURSE))
    }
}
