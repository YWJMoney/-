package com.bamnat.app.alarm

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.pay.PayCodec
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayPeriod
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.LocalTime

/** [REL-12] Alarm buttons from the lock screen, [PAYX-14][PAYX-15] the new pay settings saved. */
@RunWith(AndroidJUnit4::class)
class M7AppTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun theRingingNotificationButtonsWorkWithoutUnlocking() {
        val ring = RingRequest("주간", LocalDate.now(), LocalTime.of(7, 0), 60, null, System.currentTimeMillis())
        val service = Robolectric.buildService(AlarmService::class.java,
            ring.putInto(Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_START))).create().startCommand(0, 1).get()
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(2, notification.actions.size)
        for (action in notification.actions) {
            assertFalse(action.isAuthenticationRequired)
            assertTrue(shadowOf(action.actionIntent).isBroadcastIntent)
        }
        // 끄기 goes through the receiver to the ringing service.
        val dismiss = shadowOf(notification.actions[1].actionIntent).savedIntent
        AlarmActionReceiver().onReceive(context, dismiss)
        val sent = shadowOf(context).nextStartedService
        assertNotNull(sent)
        assertEquals(AlarmService.ACTION_DISMISS, sent.action)
        service.onDestroy()
    }

    @Test fun payPeriodAndHolidayRuleSurviveSaving() {
        val p = PayProfile(
            Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))),
            period = PayPeriod(endDay = 20, payDay = 25),
        ).let { it.copy(rules = it.rules.copy(holidayByShiftStart = true)) }
        val back = PayCodec.decode(JSONObject(PayCodec.encode(p).toString()))
        assertEquals(p.period, back.period)
        assertTrue(back.rules.holidayByShiftStart)
        // Old files without the fields keep the calendar month and the clock rule.
        val old = PayCodec.encode(p).apply { remove("period"); getJSONObject("rules").remove("holidayByStart") }
        val fromOld = PayCodec.decode(old)
        assertEquals(PayPeriod(), fromOld.period)
        assertFalse(fromOld.rules.holidayByShiftStart)
        // Out-of-range values from a shared file fall back safely.
        assertEquals(PayPeriod(), PayCodec.periodFrom(JSONObject().put("endDay", 45).put("payDay", 0)))
    }
}
