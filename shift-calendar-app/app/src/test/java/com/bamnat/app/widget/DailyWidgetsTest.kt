package com.bamnat.app.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.BackupPhotos
import com.bamnat.app.data.MoneyRepository
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.data.SettingsRepository
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.review.InAppReview
import com.bamnat.core.memo.Memo
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftType
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.schedule.DaySource
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 1.22 묶음 E on the phone: widgets, the photo backup, the second review moment and the D7 checks. */
@RunWith(AndroidJUnit4::class)
class DailyWidgetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val today = LocalDate.of(2026, 10, 22)

    @After fun close() = db.close()

    /** [DAILY-01] The pinned memo's title first, then how many more; blank and binned memos are left out. */
    @Test fun widgetMemoLineIsTheFirstMemosTitle() {
        assertNull(WidgetData.memoLine(emptyList()))
        val list = listOf(
            Memo(id = 1, title = "", body = "장보기\n우유", createdAtMillis = 1),
            Memo(id = 2, title = "치과 3시", pinned = true, createdAtMillis = 2),
            Memo(id = 3, title = "지운 것", createdAtMillis = 3, deletedAtMillis = 5),
        )
        assertEquals("치과 3시 외 1", WidgetData.memoLine(list))
        assertEquals("장보기", WidgetData.memoLine(list.take(1)))
        assertTrue(AppSettings().widgetMemo)
    }

    private val work = ShiftType(id = 1, name = "주간", label = "주", colorArgb = 0xFF2F80ED, start = LocalTime.of(10, 0), end = LocalTime.of(18, 0))

    private fun day(date: LocalDate, type: ShiftType?, entry: DayEntry? = null) = ResolvedDay(
        date = date, shiftType = type, source = DaySource.PATTERN, start = entry?.customStart ?: type?.start, end = entry?.customEnd ?: type?.end,
        breakMinutes = 0, scheduledMinutes = if (type?.isOff == false) 480 else 0, alarmSkipped = false, entry = entry,
    )

    /** [DAILY-11] 출근 before clocking in, 퇴근 once in, nothing for other kinds of work or an empty day. */
    @Test fun hourlyWidgetOffersInThenOut() {
        val now = LocalDateTime.of(today, LocalTime.of(9, 55))
        val off = { day(today.minusDays(1), null) }
        assertEquals(false, HourlyClockAction.offered(WorkStyle.HOURLY, day(today, work), now, off))
        val inAt = day(today, work, DayEntry(today, customStart = LocalTime.of(9, 55)))
        assertEquals(true, HourlyClockAction.offered(WorkStyle.HOURLY, inAt, now.plusHours(8), off))
        assertNull(HourlyClockAction.offered(WorkStyle.SHIFT, day(today, work), now, off))
        assertNull(HourlyClockAction.offered(WorkStyle.HOURLY, day(today, null), now, off))
    }

    /** [BACK-01] A receipt a record points at goes into the backup and comes back on a new phone; switched off, it stays out. */
    @Test fun receiptPhotosTravelWithTheBackup() = runBlocking<Unit> {
        val root = File(context.cacheDir, "photos-test").apply { deleteRecursively() }
        val receipts = File(root, "receipts").apply { mkdirs() }
        val contracts = File(root, "contracts")
        File(receipts, "r1.jpg").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(receipts, "loose.jpg").writeBytes(byteArrayOf(9))
        com.bamnat.app.data.ScheduleRepository(db).ensureDefaults()
        MoneyRepository(db).save(MoneyEntry(date = today, kind = MoneyKind.EXPENSE, amount = 5_000, category = "식비", receipt = "r1.jpg"))
        val settings = SettingsRepository(context)
        val photos = BackupPhotos(receipts) { File(contracts, it) }
        val backups = BackupManager(db, settings, photos = photos, staging = File(root, "staging"))
        assertEquals(1, backups.photoSize().count)
        // [REL-15] The file of format 3 carries the photo as a file beside the backup's text.
        fun file() = java.io.ByteArrayOutputStream().also { runBlocking { backups.exportFile(it) } }.toByteArray()
        val bytes = file()
        val json = backups.read(bytes.inputStream())
        val summary = backups.summarize(json)
        assertEquals(1, summary.photos)
        // Only the photo a record uses goes in.
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("loose.jpg"))

        File(receipts, "r1.jpg").delete()
        backups.restore(json)
        assertTrue(File(receipts, "r1.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))

        settings.update { it.copy(backupPhotos = false) }
        assertEquals(0, backups.summarize(backups.read(file().inputStream())).photos)
        root.deleteRecursively()
    }

    /** [DAILY-15][DAILY-16][BACK-01] The new settings are on by default and travel with the backup. */
    @Test fun newSettingsComeBackFromTheBackup() = runBlocking<Unit> {
        com.bamnat.app.data.ScheduleRepository(db).ensureDefaults()
        val settings = SettingsRepository(context)
        val d = AppSettings()
        assertTrue(d.backupPhotos && d.rateHint && d.widgetMemo)
        settings.update { it.copy(moneyLayout = "recent,-hero", memoLayout = "search,-fold", rateHint = false, widgetMemo = false) }
        val json = BackupManager(db, settings).export()
        settings.update { it.copy(moneyLayout = "", memoLayout = "", rateHint = true, widgetMemo = true) }
        BackupManager(db, settings).restore(json)
        val s = settings.current
        assertEquals("recent,-hero", s.moneyLayout)
        assertEquals("search,-fold", s.memoLayout)
        assertFalse(s.rateHint)
        assertFalse(s.widgetMemo)
    }

    /** [DAILY-17] The first moment, then a pay month closed, a month or more later, once. */
    @Test fun theSecondReviewMomentComesOnceAndNotTooSoon() {
        val day = today.toEpochDay()
        // [SETUP-14] G P23: not the minute setup ends, but once the first month-end card was closed.
        assertNull(InAppReview.due(AppSettings(reviewDue = true), today))
        assertEquals(InAppReview.Moment.FIRST, InAppReview.due(AppSettings(reviewDue = true, monthRecapSeen = 202610), today))
        assertNull(InAppReview.due(AppSettings(reviewAsked = true), today))
        val asked = AppSettings(reviewAsked = true, reviewAskedDay = day - 10, payReviewDue = true)
        assertNull(InAppReview.due(asked, today))
        assertEquals(InAppReview.Moment.PAY, InAppReview.due(asked.copy(reviewAskedDay = day - InAppReview.GAP_DAYS), today))
        // Asked before 1.22 kept no day: long ago.
        assertEquals(InAppReview.Moment.PAY, InAppReview.due(asked.copy(reviewAskedDay = 0), today))
        assertNull(InAppReview.due(asked.copy(reviewAskedDay = 0, payReviewAsked = true), today))
    }

    /** [INV-34] 월말 요약 is set for 20:00 on the month's last day, and only while there is an investor calendar. */
    @Test fun monthSummaryIsSetForTheLastEvening() {
        val am = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        val now = LocalDateTime.of(2026, 10, 22, 9, 0)
        com.bamnat.app.invest.InvestMonthNotice.schedule(context, on = true, now = now)
        val at = java.time.LocalDateTime.of(2026, 10, 31, 20, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(am.scheduledAlarms.any { it.triggerAtTime == at })
        com.bamnat.app.invest.InvestMonthNotice.schedule(context, on = false, now = now)
        assertFalse(am.scheduledAlarms.any { it.triggerAtTime == at })
    }

    /** [DAILY-12] 결정 D7-2: the field worker's widget line says 공수 and whether money is owed, never an amount. */
    @Test fun fieldWidgetShowsNoAmounts() {
        val code = File("src/main/java/com/bamnat/app/widget/WidgetStyleLines.kt").readText()
        val line = code.substringAfter("internal fun FieldWeekLine").substringBefore("\n}\n")
        assertFalse(line.contains("Fmt.won") || line.contains("w.unpaid)") || line.contains("원\""))
        assertTrue(line.contains("받을 돈 있어요"))
    }

    /** [DAILY-14] 결정 D7-4: one added calendar stays free. */
    @Test fun oneAddedCalendarStaysFree() {
        assertEquals(1, PersonalCalendar.FREE_ADDED)
    }
}
