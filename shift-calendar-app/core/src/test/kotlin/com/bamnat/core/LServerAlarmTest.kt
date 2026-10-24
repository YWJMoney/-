package com.bamnat.core

import com.bamnat.core.diag.DiagEvent
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.diag.EventBook
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.ops.AppNotice
import com.bamnat.core.ops.AppNotices
import com.bamnat.core.ops.InquiryTopics
import com.bamnat.core.ops.NoticeAction
import com.bamnat.core.ops.NoticeAudience
import com.bamnat.core.plaza.BamnatNews
import com.bamnat.core.plaza.PlazaNews
import com.bamnat.core.plaza.PlazaNewsItem
import com.bamnat.core.plaza.PlazaRules
import com.bamnat.core.schedule.RestNudge
import com.bamnat.core.schedule.RestRules
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.stats.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 1.23 묶음 L, the parts with no Android code: 밤낮 소식's one notification [SRV-10], the report outcomes in it
 * [COMM-11], the alarm week card [ALRM-01], the inquiry topics shared with the server [INQ-05], who a notice is
 * for [SRV-09], changed rule lines [COMM-11], the rest nudge [SRV-10] and the 18th usage event [STAT-06].
 */
class LServerAlarmTest {
    @Test fun bamnatNewsPutsAnswersFirstAndBundlesTheRest() {
        assertNull(BamnatNews.text(emptyList(), 0, emptyList()))
        val one = BamnatNews.text(emptyList(), 1, emptyList())!!
        assertEquals("밤낮 소식", one.title)
        assertEquals(listOf("문의에 답이 왔어요."), one.lines)
        assertTrue("only an answer opens the inquiry", one.onlyAnswers)

        val plaza = listOf(PlazaNewsItem("flag_result", result = "hidden", atMillis = 2), PlazaNewsItem("unmuted", atMillis = 1))
        val many = BamnatNews.text(plaza, 2, listOf("점검 안내"), listOf("11/3 주간 → 야간"))!!
        assertEquals("밤낮 소식 5개", many.title)
        assertEquals("문의에 답이 2개 왔어요.", many.lines.first())
        assertEquals("11/3 주간 → 야간", many.lines[1])
        assertTrue(many.lines.contains("신고한 글이 가려졌어요. 고마워요."))
        assertEquals("공지: 점검 안내", many.lines.last())
        assertFalse(many.onlyAnswers)

        val crowd = BamnatNews.text(emptyList(), 0, (1..7).map { "공지 $it" })!!
        assertEquals(6, crowd.lines.size)
        assertEquals("그 밖에 2개 더 있어요.", crowd.lines.last())
    }

    @Test fun reportOutcomesHaveTheirOwnWordsAndUnknownOnesAreSkipped() {
        assertEquals("신고한 글을 운영자가 지웠어요.", PlazaNews.line(PlazaNewsItem("flag_result", result = "deleted")))
        assertEquals("신고한 글은 운영자가 확인하고 그대로 두었어요.", PlazaNews.line(PlazaNewsItem("flag_result", result = "kept")))
        assertEquals("가려졌던 내 글이 다시 보여요.", PlazaNews.line(PlazaNewsItem("post_result", result = "shown")))
        assertTrue(PlazaNews.line(PlazaNewsItem("post_result", result = "hidden"))!!.contains("이의 제기"))
        assertNull(PlazaNews.line(PlazaNewsItem("flag_result", result = "later")))
        assertNull(PlazaNews.line(PlazaNewsItem("something_new")))
    }

    @Test fun theAlarmWeekCountsRangLateAndMissed() {
        val day = 24 * 60 * 60_000L
        val now = 10 * day
        val t1 = now - 3 * day
        val t2 = now - 2 * day
        val t3 = now - day
        val old = now - 9 * day
        val events = listOf(
            DiagEvent(t1 - 1000, DiagEvents.ALARM_SCHEDULED, EventBook.triggerDetail(t1)),
            DiagEvent(t1, DiagEvents.ALARM_FIRED, EventBook.triggerDetail(t1, "late_s=5")),
            DiagEvent(t2 - 1000, DiagEvents.ALARM_SCHEDULED, EventBook.triggerDetail(t2)),
            DiagEvent(t2 + 120_000, DiagEvents.ALARM_FIRED, EventBook.triggerDetail(t2, "late_s=120")),
            DiagEvent(t3 - 1000, DiagEvents.ALARM_SCHEDULED, EventBook.triggerDetail(t3)),
            // Older than the week: not counted.
            DiagEvent(old, DiagEvents.ALARM_FIRED, EventBook.triggerDetail(old)),
        )
        val w = EventBook.week(events, now)
        assertEquals(3, w.planned)
        assertEquals(2, w.rang)
        assertEquals(1, w.late)
        assertEquals(listOf(t3), w.missed)
        assertFalse(w.allRang)
        assertEquals(120L, EventBook.lateSeconds("at=1 late_s=120"))
        assertNull(EventBook.lateSeconds("at=1"))
        assertTrue(EventBook.aliveBefore(listOf(DiagEvent(t3 - 3600_000, DiagEvents.ALIVE)), t3))
        assertFalse(EventBook.aliveBefore(listOf(DiagEvent(t3 - 2 * day, DiagEvents.ALIVE)), t3))
    }

    @Test fun inquiryTopicsTakeTheMostMatchedWords() {
        assertEquals("alarm", InquiryTopics.of("알람이 안 울려요. 소리가 안 나요"))
        assertEquals("pay", InquiryTopics.of("야간 수당 계산이 명세서와 달라요"))
        assertEquals(InquiryTopics.OTHER, InquiryTopics.of("안녕하세요"))
        assertEquals("알람", InquiryTopics.label("alarm"))
        assertEquals("기타", InquiryTopics.label("nope"))
        assertEquals("every key is distinct", InquiryTopics.TOPICS.size, InquiryTopics.TOPICS.map { it.key }.toSet().size)
    }

    @Test fun aNoticeForSomePhonesShowsOnlyWhereItFits() {
        val samsung = NoticeAudience.Phone("samsung", 34, "SHIFT", setOf("ALARM"))
        val pixel = NoticeAudience.Phone("Google", 30, "DAY", emptySet())
        val a = NoticeAudience(makers = setOf("samsung"), minSdk = 33, features = setOf("ALARM", "SHARE"))
        assertTrue(a.fits(samsung))
        assertFalse(a.fits(pixel))
        assertFalse(NoticeAudience(maxSdk = 29).fits(pixel))
        assertTrue(NoticeAudience(workTypes = setOf("DAY")).fits(pixel))
        assertTrue("no audience is everyone", NoticeAudience().fits(pixel))

        val now = 1_000L
        val forSamsung = AppNotice("s", "삼성 알람 안내", "본문", important = false, from = 0, until = 2_000, audience = a, action = NoticeAction("ALARM_CHECK"))
        assertEquals("s", AppNotices.showing(listOf(forSamsung), now, 34, emptySet(), samsung)?.id)
        assertNull(AppNotices.showing(listOf(forSamsung), now, 34, emptySet(), pixel))
        assertNull("without the phone a targeted notice does not show", AppNotices.showing(listOf(forSamsung), now, 34, emptySet()))
        assertEquals("알람 점검 열기", forSamsung.action!!.words)
        assertEquals("여기", NoticeAction("URL", label = "여기").words)
        assertTrue(NoticeAction.KINDS.contains("WHATS_NEW"))
    }

    @Test fun changedRuleLinesAreOnlyForThoseWhoAgreedBefore() {
        assertEquals(PlazaRules.LINES.size, PlazaRules.SINCE.size)
        assertTrue("never agreed: nothing to mark", PlazaRules.changedSince(0).isEmpty())
        assertTrue("agreed to this version: nothing changed", PlazaRules.changedSince(PlazaRules.VERSION).isEmpty())
    }

    @Test fun theRestNudgeSaysTheAlarmsWorkBreaksARule() {
        val n = Fixtures.night; val d = Fixtures.day; val o = Fixtures.off
        val monday = LocalDate.of(2026, 11, 2)
        // N N N N: the fourth night in a row breaks the three-night limit.
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(n, n, n, n, o, d).map { it.id }), emptyList())
        val days = (0L until 6).map { r.resolve(monday.plusDays(it)) }
        val fourth = monday.plusDays(3)
        val line = RestNudge.alarmLine(RestNudge.on(days, fourth, RestRules()))
        assertTrue(line, line!!.startsWith("휴식 점검: "))
        assertNull(RestNudge.alarmLine(RestNudge.on(days, monday, RestRules())))
        assertTrue("off is off", RestNudge.on(days, fourth, RestRules(on = false)).isEmpty())
        assertEquals("휴식 점검 3건", RestNudge.monthNote(3))
        assertNull(RestNudge.monthNote(0))
    }

    @Test fun noticeOpenIsTheEighteenthUsageEvent() {
        assertEquals(18, UsageEvent.entries.size)
        assertTrue(UsageEvent.entries.any { it.key == "notice_open" })
    }
}
