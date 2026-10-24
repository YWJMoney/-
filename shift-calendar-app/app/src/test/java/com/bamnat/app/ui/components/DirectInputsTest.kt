package com.bamnat.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * [INPUT-01][INPUT-03][INPUT-04] 묶음 Q's shared direct inputs (계획/1.24-Q-개발계획.md 347): the numbers they step to,
 * the words a built rule sends, and the clipboard line that reads only when tapped and does not ask twice.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DirectInputsTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()

    @Test fun `steps land on the grid without float drift`() {
        var v = 0.0
        repeat(10) { v = snap(v + 0.1, 0.1) }
        assertEquals(1.0, v, 0.0)
        assertEquals(1.25, snap(1.0 + 0.25, 0.25), 0.0)
        assertEquals(1.55, snap(1.5 + 0.05, 0.05), 0.0)
        assertEquals("1", decimalText(1.0))
        assertEquals("0.25", decimalText(0.25))
        assertEquals("3.5", decimalText(3.50))
    }

    @Test fun `31 is the month's last day`() {
        assertEquals("매달 말일", dayOfMonthLabel(31))
        assertEquals("매달 25일", dayOfMonthLabel(25))
        assertEquals("매달 31일", dayOfMonthLabel(31, lastIsMonthEnd = false))
    }

    @Test fun `a built rule goes out first, the user's words after, blank lines left out`() {
        assertEquals("대근은 8시간에 기본급의 10%\n명절 근무는 따로", ruleWords(listOf("대근은 8시간에 기본급의 10%"), "  명절 근무는 따로 "))
        assertEquals("토요일 근무는 시간당 50% 더", ruleWords(listOf(" 토요일 근무는 시간당 50% 더"), ""))
        assertEquals("", ruleWords(emptyList(), "  "))
    }

    @Test fun `the clipboard line looks at the description, reads on the tap, and does not offer the same copy twice`() {
        val memory = ClipHintMemory(app)
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertNull("빈 클립보드는 묻지 않아요", memory.newClip())
        clipboard.setPrimaryClip(ClipData.newPlainText("문자", "신한카드(1234)승인 4,500원 10/24 08:10 스타벅스"))
        assertNotNull("새로 복사한 글은 한 줄로 알려요", memory.newClip())
        val stamp = memory.newClip()!!
        assertEquals("신한카드(1234)승인 4,500원 10/24 08:10 스타벅스", memory.read())
        memory.forget(stamp)
        assertNull("한 번 다룬 복사는 다시 묻지 않아요", memory.newClip())
        // What is kept is the copy's time, never its words, and it stays out of the cloud backup.
        val kept = File(app.applicationInfo.dataDir, "shared_prefs/${ClipHintMemory.PREFS}.xml").readText()
        assert("스타벅스" !in kept && "신한카드" !in kept)
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assert(ClipHintMemory.PREFS !in rules)
    }
}
