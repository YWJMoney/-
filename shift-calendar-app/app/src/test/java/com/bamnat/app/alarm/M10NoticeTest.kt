package com.bamnat.app.alarm

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.NoticeLinks
import com.bamnat.app.appContainer
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.SettingsRepository
import com.bamnat.app.money.JobReminderScheduler
import com.bamnat.app.sleep.DayNoticeScheduler
import com.bamnat.app.sleep.QuietHold
import com.bamnat.app.sleep.QuietHoldReceiver
import com.bamnat.app.sleep.SleepingNotice
import com.bamnat.core.money.MoneyAlerts
import com.bamnat.core.notify.DayNotices
import com.bamnat.core.sleep.SleepEntry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** [SLP-15] Notices in three groups, quiet unless it matters, opening where they are about, waiting while the user sleeps. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class M10NoticeTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private fun posted(id: Int): Notification? = shadowOf(nm).getNotification(id)

    @After fun tearDown() {
        NoticeLinks.target.value?.let(NoticeLinks::consume)
    }

    @Test fun everyChannelIsMadeAtStartInThreeGroupsAndTheLoudSleepChannelGoes() {
        nm.createNotificationChannel(NotificationChannel("sleep_tips_v1", "수면 알림", NotificationManager.IMPORTANCE_DEFAULT))
        Notifications.createChannels(context)
        // The system settings list groups by id: alarms, day, money.
        assertEquals(listOf("근무 알람", "하루 안내", "돈과 휴가"), nm.notificationChannelGroups.sortedBy { it.id }.map { it.name.toString() })
        val channels = nm.notificationChannels
        assertNull(channels.firstOrNull { it.id == "sleep_tips_v1" })
        // [SCHED-01] 메모 일정 is the ninth, [SHR-12] 근무표 링크 기간 the tenth, [FEAT-18] 잠금 화면 오늘 근무 the eleventh.
        // [INV-08] 투자 알림 the twelfth, [MON-06] 구독 안내 (the trial's question) the thirteenth,
        // [PROV-03] 자동 백업 the fourteenth, [COMM-10] 광장 소식 the fifteenth.
        assertEquals(15, channels.size)
        assertEquals("하루 안내", nm.notificationChannelGroups.first { it.id == nm.getNotificationChannel(Notifications.BACKUP_CHANNEL_ID).group }.name.toString())
        assertEquals("돈과 휴가", nm.notificationChannelGroups.first { it.id == nm.getNotificationChannel(Notifications.PRO_CHANNEL_ID).group }.name.toString())
        assertTrue(channels.all { it.group != null })
        // No two lines read the same in the system settings.
        assertEquals(channels.size, channels.map { it.name.toString() }.toSet().size)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(Notifications.DAY_CHANNEL_ID).importance)
    }

    @Test fun aSnoozeWaitingShowsWhenItRingsAgainAndStopsFromTheNotice() {
        val scheduler = context.appContainer.alarmScheduler
        val ring = RingRequest("주간", java.time.LocalDate.now(), LocalTime.of(7, 0), 60, null, System.currentTimeMillis())
        scheduler.scheduleSnooze(ring, 5)
        val waiting = posted(Notifications.SNOOZE_NOTIFICATION_ID)
        assertNotNull(waiting)
        val at = AlarmScheduler.millisToLocal(scheduler.snoozeAt.value!!)
        assertEquals("%02d:%02d에 다시 울려요".format(at.hour, at.minute), waiting!!.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(waiting.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val stop = waiting.actions.single()
        assertEquals("지금 끄기", stop.title.toString())
        assertFalse(stop.isAuthenticationRequired)
        AlarmActionReceiver().onReceive(context, shadowOf(stop.actionIntent).savedIntent)
        assertNull(scheduler.snoozeAt.value)
        assertNull(posted(Notifications.SNOOZE_NOTIFICATION_ID))
    }

    @Test fun moneyDueTogetherComesAsOneNoticeAndOpensTheMoneyTab() {
        val now = LocalDateTime.now()
        JobReminderScheduler.postMoney(context, listOf(
            MoneyAlerts.Alert(now, "bill-1", "오늘 나갈 돈", "휴대폰 요금 55,000원"),
            MoneyAlerts.Alert(now, "bill-2", "오늘 나갈 돈", "월세 500,000원"),
        ))
        val money = shadowOf(nm).allNotifications.filter { it.channelId == Notifications.MONEY_CHANNEL_ID }
        assertEquals(1, money.size)
        assertEquals("오늘 나갈 돈 2건", money.single().extras.getString(Notification.EXTRA_TITLE))
        val open = shadowOf(money.single().contentIntent).savedIntent
        assertTrue(NoticeLinks.read(open))
        assertEquals(NoticeLinks.Target.MONEY, NoticeLinks.target.value)
    }

    @Test fun aNoticeOpenedAgainFromRecentsDoesNotJumpAgain() {
        val intent = NoticeLinks.intent(context, NoticeLinks.Target.LEAVE)
        assertFalse(NoticeLinks.read(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        assertNull(NoticeLinks.target.value)
        assertTrue(NoticeLinks.read(intent))
        // Followed once: the same intent handed back later says nothing.
        NoticeLinks.consume(NoticeLinks.Target.LEAVE)
        assertFalse(NoticeLinks.read(intent))
    }

    @Test fun onlyTheCommuteNoticeMakesASoundAndItShowsOnTheLockScreen() {
        val now = LocalDateTime.now()
        val briefing = DayNotices.Notice(DayNotices.Kind.BRIEFING, now, now.plusHours(3), "오늘 야간 19:00–07:00", "15:00–16:30 낮잠", "b")
        val commute = DayNotices.Notice(DayNotices.Kind.COMMUTE, now, now.plusHours(2), "야간 수고했어요", "졸리면 운전 전에 쉬어 가요", "c")
        DayNoticeScheduler.post(context, briefing, now)
        DayNoticeScheduler.post(context, commute, now)
        val quiet = posted(Notifications.BRIEFING_ID)!!
        val loud = posted(Notifications.COMMUTE_ID)!!
        assertEquals(Notifications.DAY_CHANNEL_ID, quiet.channelId)
        assertEquals(Notifications.COMMUTE_CHANNEL_ID, loud.channelId)
        assertEquals(Notification.VISIBILITY_PUBLIC, loud.visibility)
        // Gone when it has nothing more to say.
        assertEquals(Duration.ofHours(2).toMillis().toDouble(), loud.timeoutAfter.toDouble(), 5_000.0)
        assertEquals(listOf("잠들었어요"), loud.actions.map { it.title.toString() })
        assertTrue(quiet.actions.isNullOrEmpty())
        // A notice already past its time is not sent.
        DayNoticeScheduler.post(context, briefing.copy(until = now.minusMinutes(1)), now)
    }

    @Test fun whileAsleepTheNoticeStaysAndAfterwardsSaysHowLong() {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        SleepingNotice.show(context, SleepEntry(1, now.minusHours(2), null), now.plusHours(5), now)
        val sleeping = posted(Notifications.SLEEPING_ID)!!
        assertTrue(sleeping.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("깼어요", sleeping.actions.single().title.toString())
        assertTrue(sleeping.extras.getString(Notification.EXTRA_TITLE)!!.endsWith("부터 자는 중"))
        SleepingNotice.woke(context, SleepEntry(1, now.minusHours(7).minusMinutes(30), now), "오늘 19:00 야간")
        val woke = posted(Notifications.SLEEPING_ID)!!
        assertEquals("7시간 30분 잤어요", woke.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("다음: 오늘 19:00 야간", woke.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(woke.flags and Notification.FLAG_ONGOING_EVENT == 0)
        // Left open for 16 hours, it was a missed 깼어요: no notice.
        SleepingNotice.show(context, SleepEntry(2, now.minusHours(17), null), null, now)
        assertNull(posted(Notifications.SLEEPING_ID))
    }

    @Test fun moneyWaitsUntilHalfAnHourAfterWakingAndNotWhenTurnedOff() = runBlocking {
        val c = context.appContainer
        val asleep = c.sleepRepository.fellAsleep().entry
        val now = LocalDateTime.now()
        assertTrue(QuietHold.holdIfAsleep(context, QuietHold.Source.MONEY, now))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        val hold = alarms.scheduledAlarms.single { shadowOf(it.operation).savedIntent.component?.className == QuietHoldReceiver::class.java.name }
        // No shifts to plan from: the user's target sleep from when they lay down, and 30 minutes.
        val expected = asleep.start.plusMinutes(c.settingsRepository.current.sleepTargetMinutes + 30L)
        assertEquals(expected.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toDouble(), hold.triggerAtTime.toDouble(), 60_000.0)

        c.settingsRepository.update { it.copy(quietWhileAsleep = false) }
        assertFalse(QuietHold.holdIfAsleep(context, QuietHold.Source.MONEY, now))
    }

    @Test fun theNewNoticeSwitchesComeBackFromABackupAndOldSleepNoticesCarryOver() = runBlocking {
        val c = context.appContainer
        val on = AppSettings(briefing = true, commuteNotice = false, bedtimeNotice = true, sleepingNotice = false,
            quietWhileAsleep = false, moneyNotices = false, leaveNotice = false)
        c.settingsRepository.update {
            it.copy(briefing = on.briefing, commuteNotice = on.commuteNotice, bedtimeNotice = on.bedtimeNotice, sleepingNotice = on.sleepingNotice,
                quietWhileAsleep = on.quietWhileAsleep, moneyNotices = on.moneyNotices, leaveNotice = on.leaveNotice)
        }
        val backups = BackupManager(c.database, c.settingsRepository, payStore = c.payStore)
        val json = backups.export()
        c.settingsRepository.update { AppSettings() }
        backups.restore(json)
        val back = c.settingsRepository.current
        assertEquals(listOf(true, false, true, false, false, false, false),
            listOf(back.briefing, back.commuteNotice, back.bedtimeNotice, back.sleepingNotice, back.quietWhileAsleep, back.moneyNotices, back.leaveNotice))

        // A 1.10 backup has one switch for the sleep notices: it turns on the briefing and the commute notice.
        val old = JSONObject(json).apply {
            val s = getJSONObject("settings")
            listOf("briefing", "commuteNotice").forEach(s::remove)
            s.put("sleepReminders", true)
        }
        backups.restore(old.toString())
        assertTrue(c.settingsRepository.current.briefing && c.settingsRepository.current.commuteNotice)

        // So does the switch kept on the phone by 1.10.
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("sleep_reminders", true).commit()
        val upgraded = SettingsRepository(context).current
        assertTrue(upgraded.briefing && upgraded.commuteNotice)
        assertFalse(upgraded.bedtimeNotice)
    }
}
