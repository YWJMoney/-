package com.bamnat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.Receivable
import com.bamnat.core.money.ReceivableKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/** [RECV-01][SLP-16] 미수금 paid back in parts with its income lines, its backup, and sleeps written by hand. */
@RunWith(AndroidJUnit4::class)
class OwedAppTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val money = MoneyRepository(db)
    private val day = LocalDate.of(2026, 11, 3)

    @After fun close() = db.close()

    @Test fun partPaymentsWriteIncomeAndComeBackOut() = runBlocking {
        val r = money.save(Receivable(who = "예시정밀", title = "3일치 일당", amount = 450_000, date = day, kind = ReceivableKind.WORK))
        val first = money.receive(r, 150_000, day.plusDays(5))!!
        val now = money.book.first().receivables.single()
        assertEquals(300_000L, now.left)
        val line = money.entries.first().single()
        assertEquals(MoneyKind.INCOME, line.kind)
        assertEquals(150_000L, line.amount)
        assertEquals(first.entryId, line.id)
        // More than is left is cut to what is left.
        assertEquals(300_000L, money.receive(now, 999_999, day.plusDays(6))!!.amount)
        assertNull(money.receive(money.book.first().receivables.single(), 1, day.plusDays(7)))
        // Taking a payment back takes its income line too.
        money.delete(first)
        assertEquals(listOf(300_000L), money.entries.first().map { it.amount })
        assertEquals(150_000L, money.book.first().receivables.single().left)
    }

    @Test fun aLoanComingBackIsNotIncomeAndDeleteCanBeUndone() = runBlocking {
        val r = money.save(Receivable(who = "민지", amount = 100_000, date = day, kind = ReceivableKind.LOAN, countAsIncome = false))
        money.receive(r, 40_000, day)
        assertTrue(money.entries.first().isEmpty())
        val saved = money.book.first().receivables.single()
        val lines = money.linesOf(saved)
        money.delete(saved)
        assertTrue(money.book.first().receivables.isEmpty())
        money.restore(saved, lines)
        assertEquals(60_000L, money.book.first().receivables.single().left)
    }

    @Test fun owedGoesIntoTheBackupAndComesBack() = runBlocking {
        // A backup needs the shift types, as on a real phone.
        ScheduleRepository(db).ensureDefaults()
        val r = money.save(Receivable(who = "가나다화학", title = "10월 납품", amount = 500_000, date = day, due = day.plusDays(14)))
        money.receive(r, 200_000, day.plusDays(3))
        val backups = BackupManager(db, SettingsRepository(context))
        val json = backups.export()
        assertEquals(1, org.json.JSONObject(json).getJSONArray("owed").length())
        money.delete(money.book.first().receivables.single())
        backups.restore(json)
        val back = money.book.first().receivables.single()
        assertEquals("10월 납품", back.title)
        assertEquals(day.plusDays(14), back.due)
        assertEquals(300_000L, back.left)
    }

    @Test fun sleepsCanBeAddedAndCorrected() = runBlocking {
        val sleep = SleepRepository(db)
        val id = sleep.add(LocalDateTime.of(2026, 11, 3, 8, 0), LocalDateTime.of(2026, 11, 3, 12, 30))
        sleep.edit(id, LocalDateTime.of(2026, 11, 3, 8, 30), LocalDateTime.of(2026, 11, 3, 14, 0))
        val e = db.sleepDao().all().single()
        assertEquals(330L, (e.endMillis!! - e.startMillis) / 60_000)
    }
}
