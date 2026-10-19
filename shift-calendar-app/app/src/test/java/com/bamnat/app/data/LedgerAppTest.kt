package com.bamnat.app.data

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.core.money.AccountType
import com.bamnat.core.money.CategoryEdit
import com.bamnat.core.money.EntryPart
import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.MoneyAccount
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import com.bamnat.core.money.Repeat
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * [LEDG-06]..[LEDG-14] The ledger on the phone: cards picking up their payments, repeating lines
 * writing themselves once, categories renamed everywhere, accounts put away, several lines at once,
 * and all of it through a backup, an added calendar's too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LedgerAppTest {
    private val context = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val money = MoneyRepository(db)
    private val day = LocalDate.of(2026, 11, 3)

    @After fun close() = db.close()

    /** [LEDG-08] A card the user added picks up the payments its texts tell of. */
    @Test fun aCardPicksUpItsPayments() = runBlocking {
        val card = money.save(MoneyAccount(name = "신한 Deep", type = AccountType.CREDIT, last4 = "1234", payDay = 14))
        val posted = LocalDate.of(2026, 10, 15).atTime(13, 21).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        money.capture("신한카드(1234)승인 홍*동 120,000원(3개월)10/15 13:20 라마바전자 누적1,234,567원", "신한카드", posted)
        val e = money.entries.first().single()
        assertEquals(card.id, e.accountId)
        assertEquals(3, e.installments)
        // The bill of November takes one third of it, the remainder first.
        val book = money.book.first()
        assertEquals(40_000L, com.bamnat.core.money.CardCycle.bill(book.accounts.single(), java.time.YearMonth.of(2026, 11), book.entries, book.accounts).amount)
    }

    /** [LEDG-09] A repeating line set to write itself is written once on its day, and not before it was set up. */
    @Test fun repeatingLinesWriteThemselvesOnce() = runBlocking {
        money.save(FixedCost(name = "월급", amount = 2_500_000, day = 25, kind = MoneyKind.INCOME, category = "급여", auto = true, start = LocalDate.of(2026, 10, 1)))
        money.save(FixedCost(name = "헬스장", amount = 30_000, day = 5, repeat = Repeat.WEEK, category = "생활", auto = true, start = LocalDate.of(2026, 10, 26)))
        money.save(FixedCost(name = "월세", amount = 500_000, day = 25))
        val written = money.catchUpRecurring(day)
        assertEquals(listOf("월급", "헬스장"), written.map { it.merchant })
        assertEquals(listOf(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 30)), written.map { it.date })
        assertTrue(written.all { it.source == MoneySource.FIXED && it.fixedId != null })
        assertTrue(money.catchUpRecurring(day).isEmpty())
        assertEquals(2, money.entries.first().size)
    }

    /** [LEDG-14] A renamed category takes its lines, split parts, budget, learned places and repeating lines along. */
    @Test fun aRenamedCategoryMovesEverywhere() = runBlocking {
        money.save(MoneyEntry(date = day, kind = MoneyKind.EXPENSE, amount = 9_000, category = "카페", merchant = "사아자커피"))
        money.save(MoneyEntry(date = day, kind = MoneyKind.EXPENSE, amount = 30_000, category = "식비", parts = listOf(EntryPart("카페", 5_000))))
        money.save(FixedCost(name = "원두 구독", amount = 20_000, day = 1, category = "카페"))
        money.setBudget("카페", 50_000)
        money.saveCategories(MoneyKind.EXPENSE, listOf(CategoryEdit(MoneyKind.EXPENSE, "식비", 0), CategoryEdit(MoneyKind.EXPENSE, "카페", 1)))
        money.renameCategory(MoneyKind.EXPENSE, "카페", "커피")
        val book = money.book.first()
        assertEquals(setOf("커피", "식비"), book.entries.map { it.category }.toSet())
        assertEquals(listOf(EntryPart("커피", 5_000)), book.entries.single { it.category == "식비" }.parts)
        assertEquals("커피", book.fixed.single().category)
        assertEquals(listOf("커피"), book.budgets.map { it.category })
        assertEquals(listOf("식비", "커피"), book.categories.map { it.name })
        assertEquals("커피", book.categoriesOf(MoneyKind.EXPENSE)[1])
        // A budget set to 0 is gone.
        money.setBudget("커피", 0)
        assertTrue(money.book.first().budgets.isEmpty())
    }

    /** [LEDG-08] An account with lines is put away, one without is removed and no card pays from it any more. */
    @Test fun accountsArePutAwayOrRemoved() = runBlocking {
        val bank = money.save(MoneyAccount(name = "국민 통장", type = AccountType.BANK, opening = 1_000_000))
        val wallet = money.save(MoneyAccount(name = "현금", type = AccountType.CASH))
        val check = money.save(MoneyAccount(name = "체크카드", type = AccountType.CHECK, payFromId = bank.id))
        assertEquals(listOf(0, 1, 2), listOf(bank, wallet, check).map { it.order })
        money.save(MoneyEntry(date = day, kind = MoneyKind.EXPENSE, amount = 9_000, category = "식비", accountId = wallet.id))
        assertFalse(money.remove(wallet))
        assertTrue(money.book.first().account(wallet.id)!!.hidden)
        assertTrue(money.remove(bank))
        assertNull(money.book.first().account(check.id)!!.payFromId)
    }

    /** [LEDG-07] Lines picked together are changed or removed together, and come back with undo. */
    @Test fun severalLinesAtOnce() = runBlocking {
        val a = money.save(MoneyEntry(date = day, kind = MoneyKind.EXPENSE, amount = 9_000, category = "식비"))
        val b = money.save(MoneyEntry(date = day, kind = MoneyKind.EXPENSE, amount = 4_000, category = "기타"))
        money.saveAll(listOf(a, b).map { com.bamnat.core.money.Ledger.recategorize(it, "카페") })
        assertEquals(listOf("카페", "카페"), money.entries.first().map { it.category })
        val before = money.entries.first()
        money.deleteAll(before)
        assertTrue(money.entries.first().isEmpty())
        money.saveAll(before)
        assertEquals(before.toSet(), money.entries.first().toSet())
    }

    /** [LEDG-06][LEDG-08][LEDG-11][LEDG-14] Accounts, budgets, categories and the lines' new parts go through a backup. */
    @Test fun theLedgerGoesThroughABackup() = runBlocking {
        ScheduleRepository(db).ensureDefaults()
        val card = money.save(MoneyAccount(name = "신한 Deep", type = AccountType.CREDIT, last4 = "1234", payDay = 14, closeDay = 31))
        val line = money.save(
            MoneyEntry(
                date = day, kind = MoneyKind.EXPENSE, amount = 30_000, category = "식비", accountId = card.id, memo = "민지랑",
                tags = listOf("여행"), parts = listOf(EntryPart("카페", 5_000)), installments = 2,
            ),
        )
        money.save(FixedCost(name = "헬스장", amount = 30_000, day = 5, repeat = Repeat.WEEK, auto = true, accountId = card.id, start = day))
        money.setBudget("", 1_000_000)
        money.saveCategories(MoneyKind.EXPENSE, listOf(CategoryEdit(MoneyKind.EXPENSE, "운동", 0), CategoryEdit(MoneyKind.EXPENSE, "기타", 1, hidden = true)))
        val before = money.book.first()
        val backups = BackupManager(db, SettingsRepository(context))
        val json = backups.export()
        assertEquals(1, org.json.JSONObject(json).getJSONArray("accounts").length())

        money.deleteAll(listOf(line))
        money.remove(money.book.first().accounts.single())
        money.setBudget("", 0)
        money.saveCategories(MoneyKind.EXPENSE, emptyList())
        backups.restore(json)
        val back = money.book.first()
        assertEquals(before.entries, back.entries)
        assertEquals(before.fixed, back.fixed)
        assertEquals(before.accounts, back.accounts)
        assertEquals(before.budgets, back.budgets)
        assertEquals(before.categories, back.categories)
    }

    /** [MCAL-06][LEDG-08] An added calendar's accounts and budgets are its own, in the backup too. */
    @Test fun anAddedCalendarKeepsItsOwnAccounts() = runBlocking {
        val c = context.container
        waitFor("the first shifts") { runBlocking { c.scheduleRepository.shiftTypes.first().isNotEmpty() } }
        val id = c.calendars.begin("알바").also { c.calendars.finishSetup(it, WorkStyle.HOURLY) }
        c.calendars.repository(id).ensureDefaults()
        val added = c.calendars.money(id)
        added.save(MoneyAccount(name = "알바 통장", type = AccountType.BANK))
        added.setBudget("식비", 200_000)
        assertTrue(c.moneyRepository.book.first().accounts.isEmpty())
        // The notices see every calendar's cards, each with an id of its own.
        c.moneyRepository.save(MoneyAccount(name = "현금", type = AccountType.CASH))
        assertEquals(2, c.calendars.everyMoneyNow().accounts.map { it.id }.distinct().size)
        val json = c.backupManager.export()

        c.calendars.delete(id)
        c.backupManager.restore(json)
        assertEquals(listOf("알바 통장"), c.calendars.money(id).book.first().accounts.map { it.name })
        assertEquals(200_000L, c.calendars.money(id).book.first().budgets.single().amount)
        assertEquals(listOf("현금"), c.moneyRepository.book.first().accounts.map { it.name })
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 60_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }
}
