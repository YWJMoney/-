package com.bamnat.app.ui.guide

import com.bamnat.app.ui.settings.SettingPlace
import com.bamnat.app.ui.settings.SettingsGo
import com.bamnat.app.ui.settings.SettingsIndex
import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.guide.Off
import com.bamnat.core.guide.Place
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GUIDE-01][GUIDE-05][GUIDE-07] Every feature opens somewhere real, every switch has a home, and the words people use find it. */
class FeatureGuideTest {
    /** [GUIDE-01] A feature in settings points at a row settings has; a tool in a card is one the card opens. */
    @Test
    fun everyRouteLeadsSomewhere() {
        val rows = SettingsIndex.entries().mapNotNull { it.place as? SettingPlace.Row }
        for (f in FeatureRegistry.all) {
            val place = f.route.place
            if (place.isSettings) {
                val page = place.settingsPage()
                assertNotNull(f.id, page)
                assertTrue("${f.id}: ${f.route.focus} is a row of ${page!!.title}", rows.any { it.page == page && it.row == f.route.focus })
            } else if (f.route.focus != null) {
                assertTrue("${f.id}: ${f.route.focus}", f.route.focus in TOOL_KEYS)
                assertTrue(f.id, place == Place.STATS || place == Place.MONEY)
            }
        }
        // 기능 켜고 끄기 is found and opened from settings too.
        assertTrue(SettingsIndex.entries().any { (it.place as? SettingPlace.Screen)?.go == SettingsGo.FEATURES })
    }

    /** [GUIDE-05] A switch that was there before has its own branch; no feature copies one. */
    @Test
    fun everySwitchHasAHome() {
        val linked = FeatureRegistry.all.filter { it.off == Off.ELSEWHERE }.map { it.id }.toSet()
        assertEquals(linked, FeatureSwitches.LINKED)
        // Each kind of work has something to turn off, and the guide itself can always be turned off.
        for (style in listOf(WorkStyle.SHIFT, WorkStyle.DAY, WorkStyle.HOURLY, WorkStyle.FIELD, WorkStyle.BUSINESS)) {
            assertTrue(style.name, FeatureRegistry.of(style).count { it.off == Off.CALENDAR } >= 2)
        }
        assertEquals(Off.APP, FeatureRegistry.get("guide.checklist")!!.off)
        assertEquals(Off.APP, FeatureRegistry.get("guide.tips")!!.off)
    }

    /** [GUIDE-07] Everyday words find the feature, and the calendar's own kind of work comes first. */
    @Test
    fun wordsFindTheFeature() {
        fun first(q: String, style: WorkStyle? = null) = FeatureSearch.search(q, style).firstOrNull()?.id
        assertEquals("hourly.weeks", first("주휴", WorkStyle.HOURLY))
        assertEquals("hourly.offers", first("알바 비교"))
        assertEquals("field.quote", first("견적서"))
        assertEquals("shop.cardDue", first("카드 입금"))
        assertEquals("day.leaveNow", first("퇴근 버튼", WorkStyle.DAY))
        assertEquals("shop.autoClose", first("매출 없으면 휴무"))
        assertEquals("trash.records", first("실수로 지움"))
        assertTrue(FeatureSearch.search("ㅋㅋㅋ").isEmpty())
        assertEquals("기능 켜고 끄기", SettingsIndex.search("기능 끄기").firstOrNull()?.title)
        val idea = FeatureSearch.ideaText(" 주식 계좌 ", WorkStyle.HOURLY)
        assertTrue(idea, idea.contains("찾던 기능: 주식 계좌") && idea.contains(WorkStyle.HOURLY.label))
    }
}
