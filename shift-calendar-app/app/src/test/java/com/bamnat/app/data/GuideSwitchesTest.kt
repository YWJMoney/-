package com.bamnat.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.appContainer
import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.guide.GuideChecklist
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** [GUIDE-05] '안내 카드 다시 보기' really shows the card again; '매출 없으면 휴무' turns off before shop days are set. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GuideSwitchesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun restartingTheChecklistShowsItAgainEvenWhenSwitchedOffAndAllTried() {
        val store = GuideStore.get(context)
        val cal = PersonalCalendar.MAIN
        val today = LocalDate.of(2026, 11, 2)
        val switch = FeatureRegistry.get("guide.checklist")!!
        store.setOn(switch, cal, false)
        GuideChecklist.features(WorkStyle.DAY).forEach { store.used(it.id) }
        store.restartChecklist(cal, WorkStyle.DAY, today)
        val s = store.current
        assertFalse(s.isOff(switch, cal))
        assertNotNull(GuideChecklist.visible(s.checklists[cal], WorkStyle.DAY, today, s::isUsed) { s.isOff(it, cal) })
    }

    @Test fun autoCloseTurnsOffBeforeTheShopDaysWereSet() {
        val calendars = context.appContainer.calendars
        val cal = PersonalCalendar.MAIN
        assertNull(calendars.get(cal)?.shop)
        calendars.updateShop(cal) { it.copy(autoClosed = false) }
        assertEquals(false, calendars.get(cal)?.shop?.autoClosed)
    }
}
