package com.bamnat.app.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.decodeShiftMissions
import com.bamnat.app.data.encodeShiftMissions
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Tier
import com.bamnat.core.alarm.AlarmMission
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * [PROV-06] 끄기 미션: which alarms ask for one, kept for a locked reboot, and 끄기 opening it instead of
 * turning the alarm off; [PROV-07] 일어났는지 확인: asked once after 끄기, rung again without an answer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SureAlarmTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private var running: ServiceController<AlarmService>? = null

    private fun ring(type: Long = 3, label: String? = null) =
        RingRequest("주간", LocalDate.now(), LocalTime.of(7, 0), 60, null, System.currentTimeMillis(), label = label, shiftTypeId = type)

    private fun start(ring: RingRequest): AlarmService {
        val controller = Robolectric.buildService(AlarmService::class.java,
            ring.putInto(Intent(app, AlarmService::class.java).setAction(AlarmService.ACTION_START))).create().startCommand(0, 1)
        running = controller
        return controller.get()
    }

    private fun AlarmService.dismissNow() {
        onStartCommand(Intent(app, AlarmService::class.java).setAction(AlarmService.ACTION_DISMISS), 0, 2)
    }

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifications.createChannels(app)
        WakeCheck.awake(app)
    }

    @After fun tearDown() = runBlocking {
        running?.get()?.onDestroy()
        WakeCheck.awake(app)
        c.settingsRepository.update { it.copy(alarmMission = "none", shiftMissions = emptyMap(), wakeCheckMinutes = 0, previewTier = null) }
        c.featureGate.state.first { it.allows(Feature.SURE_ALARM) }
        Unit
    }

    @Test fun withoutProEveryAlarmRingsAsBefore() = runBlocking {
        val s = AppSettings(alarmMission = "math", shiftMissions = mapOf(3L to "shake"), wakeCheckMinutes = 5)
        val open = BootAlarms.RingSettings.of(s, sure = true)
        assertEquals(AlarmMission.MATH, open.mission)
        assertEquals(AlarmMission.SHAKE, open.missionFor(ring(type = 3)))
        assertEquals(AlarmMission.MATH, open.missionFor(ring(type = 4)))
        assertEquals(5, open.wakeCheckMinutes)
        val closed = BootAlarms.RingSettings.of(s)
        assertEquals(AlarmMission.NONE, closed.missionFor(ring(type = 3)))
        assertEquals(0, closed.wakeCheckMinutes)
        assertEquals("from a broken file", 30, BootAlarms.RingSettings.of(s.copy(wakeCheckMinutes = 90), sure = true).wakeCheckMinutes)
        // The app's own settings follow the gate.
        c.settingsRepository.update { it.copy(alarmMission = "math", wakeCheckMinutes = 5) }
        assertEquals(AlarmMission.MATH, c.ringSettings().mission)
        c.settingsRepository.update { it.copy(previewTier = Tier.FREE) }
        c.featureGate.state.first { it.locked(Feature.SURE_ALARM) }
        assertEquals(AlarmMission.NONE, c.ringSettings().mission)
        assertEquals(0, c.ringSettings().wakeCheckMinutes)
    }

    @Test fun theLockedRebootCopyKeepsTheMissions() {
        val settings = BootAlarms.RingSettings(snoozeMinutes = 7, mission = AlarmMission.SHAKE,
            shiftMissions = mapOf(3L to AlarmMission.MATH, 8L to AlarmMission.NONE), wakeCheckMinutes = 10)
        BootAlarms.save(app, emptyList(), settings)
        assertEquals(settings, BootAlarms.settings(app))
        assertEquals(mapOf(3L to "math", 8L to "none"), decodeShiftMissions(encodeShiftMissions(mapOf(8L to "none", 3L to "math"))))
        assertEquals("junk is left out", mapOf(2L to "shake"), decodeShiftMissions("x=math,2=shake,,=,5"))
        BootAlarms.save(app, emptyList(), BootAlarms.RingSettings())
    }

    @Test fun aRingKeepsItsShiftAndWhetherItIsTheSecondRing() {
        val r = ring(type = 12).copy(recheck = true)
        assertEquals(r, RingRequest.decode(r.encode()))
        assertEquals(r, RingRequest.fromIntent(r.putInto(Intent())))
        assertEquals("일어났나요?", r.title)
        assertEquals("주간 근무 알람", ring().title)
        assertEquals("끈 뒤 답이 없어 다시 울려요", AlarmService.describe(r))
        // A copy saved by an older version.
        val old = org.json.JSONObject(ring().encode()).apply { remove("ring_type"); remove("ring_recheck") }.toString()
        assertEquals(0L, RingRequest.decode(old)!!.shiftTypeId)
        assertFalse(RingRequest.decode(old)!!.recheck)
    }

    @Test fun withAMissionTheNotificationsTurnOffOpensIt() {
        c.settingsRepository.update { it.copy(alarmMission = "math") }
        val off = shadowOf(start(ring())).lastForegroundNotification.actions[1]
        assertEquals("끄기", off.title.toString())
        assertFalse(off.isAuthenticationRequired)
        assertTrue(shadowOf(off.actionIntent).isActivityIntent)
        val opened = shadowOf(off.actionIntent).savedIntent
        assertEquals(AlarmActivity::class.java.name, opened.component?.className)
        assertTrue(opened.getBooleanExtra(AlarmActivity.EXTRA_MISSION, false))
        assertNotNull(AlarmService.ringingSince.value)
        // The app's banner asks for it too, and does not turn the alarm off.
        AlarmService.askToDismiss(app)
        assertTrue(shadowOf(app).nextStartedActivity.getBooleanExtra(AlarmActivity.EXTRA_MISSION, false))
        assertNotNull(AlarmService.ringing.value)
    }

    @Test fun aShiftWithoutAMissionTurnsOffAtOnce() {
        c.settingsRepository.update { it.copy(alarmMission = "math", shiftMissions = mapOf(3L to "none")) }
        val off = shadowOf(start(ring(type = 3))).lastForegroundNotification.actions[1]
        assertTrue(shadowOf(off.actionIntent).isBroadcastIntent)
    }

    @Test fun afterTurningOffItAsksAndRingsAgainOnce() {
        c.settingsRepository.update { it.copy(wakeCheckMinutes = 5) }
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val before = alarms.scheduledAlarms.size
        val service = start(ring())
        service.dismissNow()
        assertNull(AlarmService.ringing.value)
        assertNotNull("asks later", WakeCheck.pendingRing(app))
        assertEquals(before + 1, alarms.scheduledAlarms.size)
        val ask = alarms.scheduledAlarms.last()
        assertTrue(ask.triggerAtTime - System.currentTimeMillis() in 4 * 60_000L..5 * 60_000L)

        WakeCheck.ask(app)
        val question = nm.allNotifications.single { it.channelId == Notifications.STATUS_CHANNEL_ID && it.extras.getString("android.title") == "일어났나요?" }
        assertEquals("일어났어요", question.actions.single().title.toString())

        WakeCheck.ringAgain(app)
        val again = RingRequest.fromIntent(shadowOf(app).nextStartedService)!!
        assertTrue(again.recheck)
        assertEquals(3L, again.shiftTypeId)
        assertNull("only once", WakeCheck.pendingRing(app))
        assertTrue(nm.allNotifications.none { it.extras.getString("android.title") == "일어났나요?" })

        // The second ring, turned off, asks nothing more.
        service.onDestroy()
        val second = start(again)
        second.dismissNow()
        assertNull(WakeCheck.pendingRing(app))
    }

    @Test fun anAnswerOrANewAlarmEndsIt() {
        c.settingsRepository.update { it.copy(wakeCheckMinutes = 3) }
        start(ring()).dismissNow()
        WakeCheck.ask(app)
        WakeCheckReceiver().onReceive(app, Intent(WakeCheck.ACTION_AWAKE))
        assertNull(WakeCheck.pendingRing(app))
        assertTrue(nm.allNotifications.none { it.extras.getString("android.title") == "일어났나요?" })
        WakeCheck.ringAgain(app)
        assertNull("no ring after 일어났어요", shadowOf(app).nextStartedService)

        running?.get()?.onDestroy()
        start(ring()).dismissNow()
        assertNotNull(WakeCheck.pendingRing(app))
        running?.get()?.onDestroy()
        start(ring(type = 4))
        assertNull("the next alarm takes over", WakeCheck.pendingRing(app))
    }

    @Test fun aMemoScheduleOrALockedPhoneIsNotAsked() {
        c.settingsRepository.update { it.copy(wakeCheckMinutes = 5) }
        start(ring(type = 0, label = "치과")).dismissNow()
        assertNull(WakeCheck.pendingRing(app))

        running?.get()?.onDestroy()
        start(ring()).dismissNow()
        val users = shadowOf(app.getSystemService(UserManager::class.java))
        try {
            users.setUserUnlocked(false)
            WakeCheckReceiver().onReceive(app, Intent(WakeCheck.ACTION_RING))
            assertNotNull("left for after the unlock", WakeCheck.pendingRing(app))
        } finally {
            users.setUserUnlocked(true)
        }
    }

    @Test fun theSettingsGoWithABackup() = runBlocking {
        c.settingsRepository.update { it.copy(alarmMission = "shake", shiftMissions = mapOf(3L to "math"), wakeCheckMinutes = 10) }
        val json = c.backupManager.export()
        c.settingsRepository.update { it.copy(alarmMission = "none", shiftMissions = emptyMap(), wakeCheckMinutes = 0) }
        c.backupManager.restore(json)
        val back = c.settingsRepository.current
        assertEquals("shake", back.alarmMission)
        assertEquals(mapOf(3L to "math"), back.shiftMissions)
        assertEquals(10, back.wakeCheckMinutes)
    }
}
