package com.bamnat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.pay.PayStore
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Regressions for the full code review of 2026-10-17: notification capture and the backup. */
@RunWith(AndroidJUnit4::class)
class ReviewAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val at = LocalDateTime.of(2026, 10, 17, 12, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @After fun close() = db.close()

    @Test fun oneSecondCopyArrivingAtOnceIsSavedOnce() = runBlocking {
        val money = MoneyRepository(db)
        val text = "[Web발신]\n신한카드(1234)승인 홍*동 12,500원 일시불 10/17 12:30 스타벅스"
        (1..8).map { async(Dispatchers.Default) { money.capture(text, "문자", at) } }.awaitAll()
        assertEquals(1, db.moneyDao().allEntries().size)
    }

    @Test fun chatMessagesAreNotMoney() = runBlocking {
        val money = MoneyRepository(db)
        assertEquals(MoneyRepository.Outcome.IGNORED, money.capture("엄마\n어제 5,000원 입금했어", "카카오톡", at, fromMessenger = true))
        // A card 알림톡 in the same messenger still counts.
        assertEquals(MoneyRepository.Outcome.SAVED, money.capture("KB국민카드\nKB국민카드1234승인 8,000원 일시불 GS25", "카카오톡", at, fromMessenger = true))
        assertEquals(1, db.moneyDao().allEntries().size)
    }

    @Test fun twoPaymentsOfTheSameAmountWithoutTimesAreBothKept() = runBlocking {
        val money = MoneyRepository(db)
        money.capture("4,500원 결제 GS25", "페이", at)
        money.capture("4,500원 결제 이디야", "페이", at + 3 * 60 * 60 * 1000)
        assertEquals(2, db.moneyDao().allEntries().size)
    }

    @Test fun theBackupCarriesThePaySetup() = runBlocking {
        val pay = PayStore(context)
        val profile = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 2_800_000))))
        pay.save(profile)
        val backups = BackupManager(db, SettingsRepository(context), payStore = pay)
        ScheduleRepository(db).ensureDefaults()
        val json = backups.export()
        pay.save(null)
        backups.restore(json)
        assertEquals(2_800_000L, pay.profile.value!!.base.on(java.time.YearMonth.of(2026, 10)).value)
    }
}
