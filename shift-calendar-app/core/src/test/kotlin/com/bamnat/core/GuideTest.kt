package com.bamnat.core

import com.bamnat.core.guide.ChecklistState
import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.guide.WhatsNew
import com.bamnat.core.guide.GuideChecklist
import com.bamnat.core.guide.GuideMoments
import com.bamnat.core.guide.GuideState
import com.bamnat.core.guide.GuideTips
import com.bamnat.core.guide.Moment
import com.bamnat.core.guide.MomentFacts
import com.bamnat.core.guide.Off
import com.bamnat.core.guide.Place
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class GuideTest {
    private val today = LocalDate.of(2026, 10, 20)

    /** [GUIDE-01] Every feature has a unique id, a name, a line, a way there and words to find it by. */
    @Test
    fun everyFeatureIsWholeAndFindable() {
        val ids = FeatureRegistry.all.map { it.id }
        assertEquals("ids are unique", ids.size, ids.toSet().size)
        for (f in FeatureRegistry.all) {
            assertTrue(f.id, f.name.isNotBlank() && f.what.isNotBlank() && f.where.isNotBlank())
            assertTrue("${f.id} needs keywords", f.keywords.isNotEmpty())
            assertTrue("${f.id}: a settings place names its row", !f.route.place.isSettings || !f.route.focus.isNullOrBlank())
            assertTrue("${f.id}: a tip comes with its moment", (f.tip == null) == (f.moment == null))
            f.under?.let { assertNotNull("${f.id} is under a feature that exists", FeatureRegistry.get(it)) }
            assertTrue(f.id, Regex("[A-Z]+-\\d{2}").matches(f.req))
        }
    }

    /** [GUIDE-08] An update's lines open features that exist, newest release first, one per version. */
    @Test
    fun whatsNewOpensRealFeatures() {
        for (r in WhatsNew.releases) for (line in r.lines) line.featureId?.let { assertNotNull("${r.versionName}: $it", FeatureRegistry.get(it)) }
        val codes = WhatsNew.releases.map { it.versionCode }
        assertEquals(codes.sortedDescending(), codes)
        assertEquals(codes.size, codes.toSet().size)
        // [PROV-08] 1.20.0 names each new 프로 feature it brought.
        val latest = WhatsNew.releases.first { it.versionName == "1.20.0" }.lines.mapNotNull { it.featureId }.toSet()
        assertTrue(latest.containsAll(setOf("pay.report", "stats.compare", "calendar.phone", "share.feed", "data.autoBackup", "alarm.mission", "share.rosterPdf")))
    }

    /** [GUIDE-01] The 1.16 and 1.17 features are all there, each for the right kind of work. */
    @Test
    fun theNewFeaturesAreThere() {
        val needed = listOf(
            "calendar.edit", "shop.autoClose", "hourly.weeks", "field.statement", "memo.sharing", "trash.records",
            "shift.swap", "pay.gap", "day.leaveNow", "day.bridges", "hourly.offers", "hourly.contract", "hourly.quit",
            "field.units", "field.quote", "shop.dayClose", "shop.cardDue", "shop.staff", "calendar.combined", "widget.lines",
            "shift.roster", "shift.health", "day.attendance", "day.commute",
        )
        for (id in needed) assertNotNull(id, FeatureRegistry.get(id))
        assertTrue(FeatureRegistry.get("hourly.offers")!!.fits(WorkStyle.HOURLY))
        assertFalse(FeatureRegistry.get("hourly.offers")!!.fits(WorkStyle.SHIFT))
        // A main calendar from before kinds of work were asked is a shift calendar.
        assertTrue(FeatureRegistry.get("shift.pattern")!!.fits(null))
        assertEquals("주휴수당 주별 확인", FeatureRegistry.get("hourly.weeks")!!.name)
        assertTrue(FeatureRegistry.get("hourly.offers")!!.where.startsWith("출근·시급 › 알바 도구"))
        assertEquals(Place.STATS, FeatureRegistry.get("hourly.offers")!!.route.place)
    }

    /** [GUIDE-02] Each kind of work has 3 to 6 of its own features, in order, none twice. */
    @Test
    fun eachKindOfWorkHasAShortChecklist() {
        for (style in listOf(WorkStyle.SHIFT, WorkStyle.DAY, WorkStyle.HOURLY, WorkStyle.FIELD, WorkStyle.BUSINESS, WorkStyle.INVEST)) {
            assertTrue(style.name, style in GuideChecklist.ORDER)
        }
        for ((style, ids) in GuideChecklist.ORDER) {
            assertTrue(style.name, ids.size in 3..6)
            assertEquals(style.name, ids.size, ids.toSet().size)
            for (id in ids) {
                val f = FeatureRegistry.get(id)
                assertNotNull("$style: $id", f)
                assertTrue("$style: $id fits", f!!.fits(style))
            }
        }
        for (style in WorkStyle.entries) assertTrue(style.name, GuideChecklist.features(style).size in 1..GuideChecklist.MAX)
    }

    /** [GUIDE-02] Shown on the first days, gone when put away, later, over, all tried or the kind changed. */
    @Test
    fun theChecklistComesAndGoesQuietly() {
        val started = GuideChecklist.start(null, WorkStyle.HOURLY, today)
        val none: (String) -> Boolean = { false }
        val items = GuideChecklist.visible(started, WorkStyle.HOURLY, today, none) { false }!!
        assertEquals("pay.calc", items.first().feature.id)
        // Ticks as features are used; all used, the card is done.
        val some = GuideChecklist.visible(started, WorkStyle.HOURLY, today, { it == "pay.calc" }) { false }!!
        assertTrue(some.first().done)
        assertNull(GuideChecklist.visible(started, WorkStyle.HOURLY, today, { true }) { false })
        // Past its days.
        assertNotNull(GuideChecklist.visible(started, WorkStyle.HOURLY, today.plusDays(13), none) { false })
        assertNull(GuideChecklist.visible(started, WorkStyle.HOURLY, today.plusDays(14), none) { false })
        // 나중에 and 닫기.
        assertNull(GuideChecklist.visible(started.copy(laterUntil = today.plusDays(3)), WorkStyle.HOURLY, today.plusDays(1), none) { false })
        assertNotNull(GuideChecklist.visible(started.copy(laterUntil = today.plusDays(3)), WorkStyle.HOURLY, today.plusDays(3), none) { false })
        assertNull(GuideChecklist.visible(started.copy(dismissed = true), WorkStyle.HOURLY, today, none) { false })
        // A feature turned off is left out.
        val withoutTools = GuideChecklist.visible(started, WorkStyle.HOURLY, today, none) { it.id == "hourly.offers" }!!
        assertFalse(withoutTools.any { it.feature.id == "hourly.offers" })
        // The kind changed: a new list from that day.
        val again = GuideChecklist.start(started.copy(dismissed = true), WorkStyle.BUSINESS, today.plusDays(20))
        assertEquals(ChecklistState(WorkStyle.BUSINESS, today.plusDays(20)), again)
        assertEquals(started, GuideChecklist.start(started, WorkStyle.HOURLY, today.plusDays(2)))
    }

    /** [GUIDE-05] A card is off for its calendar only; a tool is off with the card it is on. */
    @Test
    fun switchesAreForTheirCalendar() {
        val tools = FeatureRegistry.get("hourly.tools")!!
        val offers = FeatureRegistry.get("hourly.offers")!!
        val state = GuideState(off = setOf(GuideState.offKey("cal1", "hourly.tools")))
        assertTrue(state.isOff(tools, "cal1"))
        assertTrue(state.isOff(offers, "cal1"))
        assertFalse(state.isOff(tools, "main"))
        val tips = FeatureRegistry.get("guide.tips")!!
        assertEquals(Off.APP, tips.off)
        assertTrue(GuideState(off = setOf(GuideState.offKey(GuideState.ALL, "guide.tips"))).isOff(tips, "anything"))
        // A switch kept elsewhere is never off here.
        assertFalse(GuideState(off = setOf("main/shop.autoClose")).isOff(FeatureRegistry.get("shop.autoClose")!!, "main"))
    }

    /** [GUIDE-03] The moments the product asked for. */
    @Test
    fun goodMomentsFollowTheDay() {
        val at = LocalDateTime.of(2026, 10, 20, 18, 30)
        assertEquals(setOf(Moment.PAST_END_TIME), GuideMoments.of(MomentFacts(WorkStyle.DAY, at, workDay = true, shiftEnd = at.minusMinutes(30))))
        assertTrue(GuideMoments.of(MomentFacts(WorkStyle.DAY, at, workDay = true, shiftEnd = at.minusMinutes(5))).isEmpty())
        assertTrue(GuideMoments.of(MomentFacts(WorkStyle.DAY, at, workDay = false, shiftEnd = at.minusMinutes(30))).isEmpty())
        assertEquals(setOf(Moment.PAYDAY), GuideMoments.of(MomentFacts(WorkStyle.HOURLY, at, payDate = at.toLocalDate().minusDays(1))))
        assertTrue(GuideMoments.of(MomentFacts(WorkStyle.FIELD, at, payDate = at.toLocalDate())).isEmpty())
        assertEquals(setOf(Moment.HOURLY_15H), GuideMoments.of(MomentFacts(WorkStyle.HOURLY, at, weekMinutes = 15 * 60)))
        assertTrue(GuideMoments.of(MomentFacts(WorkStyle.HOURLY, at, weekMinutes = 14 * 60)).isEmpty())
        assertEquals(setOf(Moment.FIRST_DAY_CLOSE), GuideMoments.of(MomentFacts(WorkStyle.BUSINESS, at, salesDays = 1)))
        assertEquals(setOf(Moment.FIRST_JOB, Moment.UNPAID_JOB), GuideMoments.of(MomentFacts(WorkStyle.FIELD, at, jobs = 2, unpaidJobs = 1)))
        assertEquals(setOf(Moment.AFTER_NIGHT), GuideMoments.of(MomentFacts(null, at, afterNight = true)))
        // [GUIDE-03] An investor's first trade brings 투자일지; not for another kind of work.
        assertEquals(setOf(Moment.FIRST_TRADE), GuideMoments.of(MomentFacts(WorkStyle.INVEST, at, trades = 1)))
        assertTrue(GuideMoments.of(MomentFacts(WorkStyle.INVEST, at)).isEmpty())
        assertTrue(GuideMoments.of(MomentFacts(WorkStyle.FIELD, at, trades = 3)).isEmpty())
        assertEquals("invest.journal", GuideTips.pick(GuideState(), "inv", WorkStyle.INVEST, setOf(Moment.FIRST_TRADE), at.toLocalDate())?.feature?.id)
    }

    /** [GUIDE-03] One tip a day, the same all day, never for what was tried, hidden or put away. */
    @Test
    fun oneTipADayAtMost() {
        val first = GuideTips.pick(GuideState(), "main", WorkStyle.BUSINESS, setOf(Moment.FIRST_DAY_CLOSE), today)!!
        assertEquals("shop.cardDue", first.feature.id)
        assertTrue(first.isNew)
        val shown = GuideState(tipDay = today, tipId = "shop.cardDue")
        assertEquals(false, GuideTips.pick(shown, "main", WorkStyle.BUSINESS, setOf(Moment.FIRST_DAY_CLOSE), today)!!.isNew)
        // Closed for today, or another moment later today: nothing more today.
        assertNull(GuideTips.pick(shown.copy(tipClosed = true), "main", WorkStyle.BUSINESS, setOf(Moment.FIRST_DAY_CLOSE), today))
        assertNull(GuideTips.pick(GuideState(tipDay = today, tipId = "pay.gap"), "main", WorkStyle.DAY, setOf(Moment.PAST_END_TIME), today))
        // Tomorrow it may come again, unless it was tried or put away for good.
        assertNotNull(GuideTips.pick(shown, "main", WorkStyle.BUSINESS, setOf(Moment.FIRST_DAY_CLOSE), today.plusDays(1)))
        assertNull(GuideTips.pick(GuideState(used = setOf("shop.cardDue")), "main", WorkStyle.BUSINESS, setOf(Moment.FIRST_DAY_CLOSE), today))
        assertNull(GuideTips.pick(GuideState(never = setOf("shop.cardDue")), "main", WorkStyle.BUSINESS, setOf(Moment.FIRST_DAY_CLOSE), today))
        // Tips off, or the card off for that calendar.
        assertNull(GuideTips.pick(GuideState(off = setOf("*/guide.tips")), "main", WorkStyle.BUSINESS, setOf(Moment.FIRST_DAY_CLOSE), today))
        assertNull(GuideTips.pick(GuideState(off = setOf("main/pay.gap")), "main", WorkStyle.DAY, setOf(Moment.PAYDAY), today))
        assertNotNull(GuideTips.pick(GuideState(off = setOf("other/pay.gap")), "main", WorkStyle.DAY, setOf(Moment.PAYDAY), today))
        // Not for another kind of work.
        assertNull(GuideTips.pick(GuideState(), "main", WorkStyle.SHIFT, setOf(Moment.FIRST_DAY_CLOSE), today))
    }
}
