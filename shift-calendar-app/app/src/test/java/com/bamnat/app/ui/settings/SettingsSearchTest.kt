package com.bamnat.app.ui.settings

import com.bamnat.app.company.Inquiry
import com.bamnat.app.company.InquiryKind
import com.bamnat.app.company.InquiryMessage
import com.bamnat.app.company.PublicInquiry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** [UX-04][UX-05] The words people use find the setting or the answer, even when it is named another way. */
class SettingsSearchTest {
    private fun first(query: String) = SettingsIndex.search(query).firstOrNull()?.title

    @Test fun everydayWordsFindTheSetting() {
        assertEquals("알람음", first("알람 소리"))
        assertEquals("테마", first("다크 모드"))
        assertEquals("백업 저장", first("백업"))
        assertEquals("근무표 공유", first("공유"))
        assertEquals("한 주의 시작", first("일요일"))
        assertEquals("날짜 칸 아래", first("음력"))
        assertEquals("다시 울림 간격", first("스누즈"))
        assertEquals("워치·휴대폰 수면 기록 함께 보기", first("갤럭시 워치"))
    }

    @Test fun endingsAreDropped() {
        assertEquals("알람 점검", first("알람이 안 울려요"))
        assertEquals("알람", SearchText.stem("알람이"))
        assertEquals("달력", SearchText.stem("달력을"))
        // A word too short to lose an ending keeps it.
        assertEquals("이", SearchText.stem("이"))
    }

    @Test fun theTitleItselfComesFirst() {
        assertEquals("진동", first("진동"))
        assertEquals("근무 주기", first("근무 주기"))
    }

    @Test fun farBehindTheBestIsLeftOut() {
        val titles = SettingsIndex.search("알람 소리").map { it.title }
        assertEquals(listOf("알람음", "근무마다 다른 소리"), titles.take(2))
        assertFalse(titles.toString(), "자동으로 끄기" in titles)
        // One word that many rows share lists them all.
        assertTrue(SettingsIndex.search("알람").size >= 4)
    }

    @Test fun nothingFitsNothing() {
        assertTrue(SettingsIndex.search("ㅋㅋㅋ").isEmpty())
        assertTrue(SettingsIndex.search("   ").isEmpty())
    }

    @Test fun whatTheBuildCannotDoIsNotOffered() {
        val titles = SettingsIndex.entries(board = false, share = false).map { it.title }
        assertFalse("회사 규칙 광장" in titles)
        assertFalse("동료 근무표 받기" in titles)
        assertTrue("근무표 공유" in titles)
    }

    @Test fun everyShortcutIsInTheIndex() {
        val titles = SettingsIndex.entries().map { it.title }.toSet()
        listOf("근무 종류와 시간", "근무 주기", "급여 계산", "근무표 공유", "알람음", "백업 저장", HELP_TITLE).forEach {
            assertTrue(it, it in titles)
        }
    }

    @Test fun helpAnswersTheCommonQuestions() {
        assertEquals("alarm", HelpFaq.search("알람이 안 울려요").first().id)
        assertEquals("share", HelpFaq.search("근무표 보내기").first().id)
        assertEquals("backup", HelpFaq.search("휴대폰 바꾸면 기록").first().id)
        assertEquals("pay", HelpFaq.search("월급이 명세서랑 달라요").first().id)
        assertEquals("widget", HelpFaq.search("위젯").first().id)
    }

    @Test fun helpForAScreenPutsItsAnswersFirst() {
        assertEquals(listOf("alarm", "sound"), HelpFaq.about("설정 > 알람").map { it.id })
        assertTrue(HelpFaq.about("달력").map { it.id }.containsAll(listOf("share", "oneday", "calendars")))
        assertEquals(listOf("import"), HelpFaq.about("캘린더에서 가져오기").map { it.id })
        assertTrue(HelpFaq.about(null).isEmpty())
    }

    @Test fun withoutLinksTheAnswerAboutReceivingIsLeftOut() {
        assertFalse(HelpFaq.topics(share = false).any { it.id == "receive" })
        assertTrue(HelpFaq.topics(share = true).any { it.id == "receive" })
    }

    @Test fun everyAnswerIsPlainAndShort() {
        for (t in HelpFaq.topics()) {
            assertTrue(t.id, t.answer.length <= 220)
            assertTrue(t.id, t.question.isNotBlank() && t.tag.isNotBlank())
        }
        assertEquals(HelpFaq.topics().size, HelpFaq.topics().map { it.id }.toSet().size)
    }

    @Test fun boardMatchesFindOthersQuestions() {
        fun item(id: String, text: String) = PublicInquiry(id, InquiryKind.BUG, "open", text, 0, 0, false, false, "alarm", "알람", Instant.EPOCH)
        val items = listOf(item("1", "야간 알람이 5분 늦게 울려요"), item("2", "달력 색을 바꾸고 싶어요"))
        assertEquals(listOf("1"), boardMatches("알람이 늦어요", items).map { it.id })
    }

    @Test fun standingSaysWhereQuestionsAre() {
        fun inquiry(id: String, status: String, answers: Int) = Inquiry(id, InquiryKind.QUESTION, status, Instant.EPOCH, Instant.EPOCH,
            List(answers) { InquiryMessage(true, "답", Instant.EPOCH) })
        val list = listOf(inquiry("a", "answered", 1), inquiry("b", "open", 0))
        assertEquals("새 답이 온 문의 1개 · 답을 기다리는 문의 1개", standing(list) { it.id == "a" })
        assertEquals("보낸 문의 1개에 모두 답했어요", standing(listOf(inquiry("a", "answered", 1))) { false })
    }
}
