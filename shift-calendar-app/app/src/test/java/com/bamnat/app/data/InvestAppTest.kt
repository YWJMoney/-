package com.bamnat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.appContainer
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.core.invest.AccountType
import com.bamnat.core.invest.AlarmKind
import com.bamnat.core.invest.CashFlow
import com.bamnat.core.invest.CashKind
import com.bamnat.core.invest.DayGrade
import com.bamnat.core.invest.Emotion
import com.bamnat.core.invest.InvestAccount
import com.bamnat.core.invest.InvestAlarm
import com.bamnat.core.invest.InvestAlarms
import com.bamnat.core.invest.JournalEntry
import com.bamnat.core.invest.Market
import com.bamnat.core.invest.Portfolio
import com.bamnat.core.invest.Quote
import com.bamnat.core.invest.ReturnMethod
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.Trade
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate

/** [INV-02][INV-03][INV-07][INV-08][INV-10][INV-11] The investor calendar's records in the database and the backup. */
@RunWith(AndroidJUnit4::class)
class InvestAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val repo = InvestRepository(db)
    private val day = LocalDate.of(2026, 10, 20)

    @After fun close() = db.close()

    @Test fun aFirstRecordMakesTheGeneralAccountAndRemembersIt() = runBlocking {
        val id = repo.ensureAccount()
        assertEquals(id, repo.ensureAccount())
        repo.saveCash(CashFlow(accountId = id, date = day, kind = CashKind.DEPOSIT, amount = 10_000_000))
        val book = repo.now()
        assertEquals("일반 계좌", book.accounts.single().name)
        assertEquals(id, book.settings.lastAccountId)
        assertEquals(10_000_000L, Portfolio.build(book).summary.principal)
    }

    @Test fun tradesCashAndQuotesMakeTheSummary() = runBlocking {
        val acc = repo.saveAccount(InvestAccount(name = "미래 ISA", type = AccountType.ISA))
        repo.saveCash(CashFlow(accountId = acc, date = day, kind = CashKind.DEPOSIT, amount = 1_000_000))
        repo.saveTrade(Trade(accountId = acc, date = day, symbol = "005930", name = "삼성전자", side = Side.BUY, quantity = 10.0, price = 70_000.0,
            fee = 105.0, emotions = setOf(Emotion.CALM), tags = listOf("실적"), followedRules = true))
        repo.saveQuotes(listOf(Quote("005930", 77_000.0, day, "삼성전자", Market.KR_STOCK)))
        val book = repo.now()
        val t = book.trades.single()
        assertEquals(setOf(Emotion.CALM), t.emotions)
        assertEquals(listOf("실적"), t.tags)
        assertEquals(true, t.followedRules)
        val s = Portfolio.build(book).summary
        assertEquals(1_000_000L - 700_105L, s.cash)
        assertEquals(770_000L, s.value)
        assertEquals(1_000_000L + 69_895L, s.totalAssets)
        // A blank price takes the price away again.
        repo.saveQuotes(emptyList(), removed = listOf("005930"))
        assertTrue(repo.now().quotes.isEmpty())
    }

    @Test fun anAccountWithRecordsIsHiddenNotDeleted() = runBlocking {
        val used = repo.saveAccount(InvestAccount(name = "키움"))
        val empty = repo.saveAccount(InvestAccount(name = "빈 계좌"))
        repo.saveCash(CashFlow(accountId = used, date = day, kind = CashKind.DEPOSIT, amount = 500_000))
        repo.deleteAccount(used)
        repo.deleteAccount(empty)
        val accounts = repo.now().accounts
        assertEquals(listOf("키움"), accounts.map { it.name })
        assertTrue(accounts.single().hidden)
    }

    @Test fun settingsKeepTheirMeaning() = runBlocking {
        repo.saveSettings {
            it.copy(goalAmount = 100_000_000, goalReturnPercent = 12.5, goalDate = day.plusYears(3), monthlyAdd = 500_000,
                principles = listOf("손절 -5%", "몰빵 금지"), usdKrw = 1_385.5, returnMethod = ReturnMethod.TIME_WEIGHTED, hideAmounts = true)
        }
        val s = repo.now().settings
        assertEquals(100_000_000L, s.goalAmount)
        assertEquals(12.5, s.goalReturnPercent!!, 1e-9)
        assertEquals(day.plusYears(3), s.goalDate)
        assertEquals(listOf("손절 -5%", "몰빵 금지"), s.principles)
        assertEquals(1_385.5, s.usdKrw, 1e-9)
        assertEquals(ReturnMethod.TIME_WEIGHTED, s.returnMethod)
        assertTrue(s.hideAmounts)
        // Nothing saved yet reads as the defaults.
        val fresh = InvestRepository.SettingsCodec.decode(emptyMap())
        assertNull(fresh.goalAmount)
        assertFalse(fresh.hideAmounts)
        assertEquals(4, fresh.principles.size)
    }

    @Test fun anEmptyJournalIsRemoved() = runBlocking {
        repo.saveJournal(JournalEntry(day, market = "코스피 −1%", checked = setOf(0, 2), grade = DayGrade.OKAY))
        assertEquals(setOf(0, 2), repo.now().journalOn(day)!!.checked)
        repo.saveJournal(JournalEntry(day))
        assertNull(repo.now().journalOn(day))
    }

    @Test fun everythingGoesIntoTheBackupAndComesBack() = runBlocking {
        ScheduleRepository(db).ensureDefaults()
        val acc = repo.saveAccount(InvestAccount(name = "토스", type = AccountType.OVERSEAS, feeRate = 0.1))
        repo.saveCash(CashFlow(accountId = acc, date = day, kind = CashKind.DEPOSIT, amount = 3_000_000))
        repo.saveCash(CashFlow(accountId = acc, date = day.plusDays(9), kind = CashKind.DIVIDEND, amount = 12_000, tax = 1_800, symbol = "AAPL"))
        repo.saveTrade(Trade(accountId = acc, date = day, minute = 23 * 60 + 35, symbol = "AAPL", name = "애플", market = Market.US_STOCK,
            side = Side.BUY, quantity = 1.5, price = 230.25, fxRate = 1_390.0, fee = 0.86, memo = "실적 기대"))
        repo.saveQuotes(listOf(Quote("AAPL", 240.0, day, "애플", Market.US_STOCK)))
        repo.saveJournal(JournalEntry(day, review = "환율 높을 때 샀다", checked = setOf(1), grade = DayGrade.GOOD))
        repo.saveEvent(com.bamnat.core.invest.InvestEvent(date = day.plusDays(10), kind = com.bamnat.core.invest.EventKind.EARNINGS, symbol = "AAPL"))
        repo.saveAlarm(InvestAlarm(kind = AlarmKind.PLAN, minute = 9 * 60 + 10, monthDay = 25, symbol = "AAPL", market = Market.US_STOCK,
            accountId = acc, amount = 300_000, ring = true))
        repo.saveSnapshot(com.bamnat.core.invest.Snapshot(day, 3_010_000, 3_000_000))
        repo.saveSettings { it.copy(goalAmount = 50_000_000, usdKrw = 1_390.0) }
        val before = repo.now()

        val backups = BackupManager(db, SettingsRepository(context))
        val json = backups.export()
        val invest = org.json.JSONObject(json).getJSONObject("invest")
        assertEquals(1, invest.getJSONArray("trades").length())
        // One trade and two cash lines.
        assertEquals(3, backups.summarize(json).moneyLines)

        // Cleared on the phone, then the backup brings all of it back as it was.
        db.investDao().clearTrades(); db.investDao().clearCash(); db.investDao().clearAlarms(); db.investDao().clearSettings()
        backups.restore(json)
        val after = repo.now()
        assertEquals(before.accounts, after.accounts)
        assertEquals(before.trades.map { it.copy(createdAt = 0) }, after.trades.map { it.copy(createdAt = 0) })
        assertEquals(before.cash.map { it.copy(createdAt = 0) }, after.cash.map { it.copy(createdAt = 0) })
        assertEquals(before.quotes, after.quotes)
        assertEquals(before.journal.map { it.copy(updatedAt = 0) }, after.journal.map { it.copy(updatedAt = 0) })
        assertEquals(before.events, after.events)
        assertEquals(before.alarms.map { it.copy(createdAt = 0) }, after.alarms.map { it.copy(createdAt = 0) })
        assertEquals(before.snapshots, after.snapshots)
        assertEquals(before.settings, after.settings)
    }

    @Test fun anOlderBackupWithoutInvestorRecordsLeavesThePhonesOwn() = runBlocking {
        ScheduleRepository(db).ensureDefaults()
        val backups = BackupManager(db, SettingsRepository(context))
        val root = org.json.JSONObject(backups.export())
        // Made with none: the backup says so, and restoring it empties what was written since.
        assertEquals(0, root.getJSONObject("invest").length())
        val acc = repo.ensureAccount()
        repo.saveCash(CashFlow(accountId = acc, date = day, kind = CashKind.DEPOSIT, amount = 1_000))
        backups.restore(root.toString())
        assertEquals(0, repo.now().cash.size)
        // A backup from before "invest" was always written keeps the phone's.
        repo.saveCash(CashFlow(accountId = repo.ensureAccount(), date = day, kind = CashKind.DEPOSIT, amount = 1_000))
        root.remove("invest")
        backups.restore(root.toString())
        assertEquals(1, repo.now().cash.size)
    }

    @Test fun anAlarmKeepsItsDaysAndComesOnTheNextOpenDay() = runBlocking {
        val id = repo.saveAlarm(InvestAlarm(kind = AlarmKind.MARKET_CLOSE, minute = 15 * 60 + 20,
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), label = "마감 전 확인"))
        val a = repo.now().alarms.single()
        assertEquals(id, a.id)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), a.weekdays)
        // 2026-10-20 is a Tuesday: the next one is Thursday the 22nd.
        assertEquals(LocalDate.of(2026, 10, 22).atTime(15, 20), InvestAlarms.next(a, day.atTime(16, 0)))
    }

    @Test fun theNoticeOpensItsTradeAndAPlanIsSavedInOneTap() = runBlocking {
        // 매매 기록 on the notice: the target, its calendar and the alarm reach the screen.
        val intent = com.bamnat.app.NoticeLinks.intent(context, com.bamnat.app.NoticeLinks.Target.INVEST_TRADE, PersonalCalendar.MAIN, payload = "42")
        assertTrue(com.bamnat.app.NoticeLinks.read(intent))
        assertEquals(com.bamnat.app.NoticeLinks.Target.INVEST_TRADE, com.bamnat.app.NoticeLinks.target.value)
        assertEquals("42", com.bamnat.app.NoticeLinks.payload.value)
        assertEquals(PersonalCalendar.MAIN, com.bamnat.app.NoticeLinks.calendar.value)
        com.bamnat.app.NoticeLinks.consume(com.bamnat.app.NoticeLinks.Target.INVEST_TRADE)

        // 바로 기록: the plan's amount at the typed price, in whole shares, with the suggested fee.
        val c = context.appContainer
        val r = InvestRepository.of(c.database)
        val id = r.saveAlarm(InvestAlarm(kind = AlarmKind.PLAN, minute = 9 * 60 + 10, monthDay = 25, symbol = "069500", name = "KODEX 200",
            market = Market.KR_ETF, amount = 300_000))
        r.saveQuotes(listOf(Quote("069500", 35_000.0, day, "KODEX 200", Market.KR_ETF)))
        com.bamnat.app.invest.InvestNotices.saveFromNotice(context, c, PersonalCalendar.MAIN, id)
        val t = r.now().trades.single()
        assertEquals(8.0, t.quantity, 1e-9)
        assertEquals(35_000.0, t.price, 1e-9)
        assertEquals(42.0, t.fee, 1e-9)
        assertEquals(0.0, t.tax, 1e-9)
        assertEquals(listOf("정기 적립"), t.tags)
        assertEquals(id, t.alarmId)
        assertEquals("일반 계좌", r.now().account(t.accountId)?.name)
    }
}
