package com.bamnat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** [LEDG-02][LEDG-04] Payment notifications into the ledger: saved once, cancelled, kept, learned. */
@RunWith(AndroidJUnit4::class)
class MoneyCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val repo = MoneyRepository(db)
    private val posted = LocalDate.of(2026, 10, 15).atTime(13, 21).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @After fun close() = db.close()

    @Test fun paymentIsSavedOnceAndWaitsForAGlance() = runBlocking {
        val text = "신한카드(1234)승인 홍*동 12,500원(일시불)10/15 13:20 스타벅스 누적1,234,567원"
        assertEquals(MoneyRepository.Outcome.SAVED, repo.capture(text, "신한카드", posted))
        // The pay app tells the same payment again a few minutes later.
        assertEquals(MoneyRepository.Outcome.DUPLICATE, repo.capture("신한카드(1234)승인 12,500원 10/15 13:24 스타벅스", "신한 SOL페이", posted + 180_000))
        val e = repo.entries.first().single()
        assertEquals(12_500L, e.amount)
        assertEquals("스타벅스", e.merchant)
        assertEquals(MoneySource.NOTIFICATION, e.source)
        assertEquals(false, e.reviewed)
        repo.markAllReviewed()
        assertEquals(true, repo.entries.first().single().reviewed)
    }

    @Test fun cancellationTakesTheMoneyBack() = runBlocking {
        repo.capture("신한카드(1234)승인 12,500원 10/15 13:20 스타벅스", "신한카드", posted)
        repo.capture("신한카드(1234)취소 12,500원 10/15 14:00 스타벅스", "신한카드", posted + 3_600_000)
        val month = repo.book.first().month(java.time.YearMonth.of(2026, 10))
        assertEquals(0L, month.expenses)
    }

    @Test fun unreadableIsKeptAndChatIsNot() = runBlocking {
        assertEquals(MoneyRepository.Outcome.IGNORED, repo.capture("엄마: 저녁 먹었어?", "카카오톡", posted))
        assertEquals(MoneyRepository.Outcome.KEPT, repo.capture("우리(5678)승인 USD 12.99 10/12 08:00 NETFLIX.COM", "우리카드", posted))
        assertEquals(MoneyRepository.Outcome.DUPLICATE, repo.capture("우리(5678)승인 USD 12.99 10/12 08:00 NETFLIX.COM", "우리카드", posted))
        assertEquals(1, repo.captures.first().size)
        // Thirty days later the text is gone.
        repo.capture("아무 알림", "앱", posted + MoneyRepository.KEEP_MILLIS + 1)
        assertEquals(0, repo.captures.first().size)
    }

    @Test fun movingAPlaceToAnotherCategoryIsRemembered() = runBlocking {
        repo.capture("신한카드(1234)승인 9,000원 10/15 12:00 메가커피", "신한카드", posted)
        val e = repo.entries.first().single()
        assertEquals("카페", e.category)
        repo.save(e.copy(category = "식비"))
        assertEquals(mapOf("메가커피" to "식비"), repo.rules())
        repo.capture("신한카드(1234)승인 4,000원 10/16 09:00 메가커피", "신한카드", posted + 86_400_000)
        assertEquals("식비", repo.entries.first().first { it.amount == 4_000L }.category)
    }

    @Test fun theOtherLinesFromAPlaceCanFollow() = runBlocking {
        repo.capture("신한카드(1234)승인 9,000원 10/14 12:00 행운상회 역삼점", "신한카드", posted)
        repo.capture("신한카드(1234)승인 7,000원 10/15 12:00 행운상회 강남점", "신한카드", posted + 86_400_000)
        val first = repo.entries.first().first { it.amount == 9_000L }
        assertEquals("기타", first.category)
        val moved = repo.save(first.copy(category = "식비"))
        assertEquals(listOf(7_000L), repo.samePlace(moved, "기타").map { it.amount })
        // A rule learned at one branch covers the others from now on.
        repo.capture("신한카드(1234)승인 5,000원 10/16 12:00 행운상회 신촌점", "신한카드", posted + 2 * 86_400_000)
        assertEquals("식비", repo.entries.first().first { it.amount == 5_000L }.category)
    }

    @Test fun adsAndBillNoticesAreDropped() = runBlocking {
        assertEquals(MoneyRepository.Outcome.IGNORED, repo.capture("[광고] 신한카드 50,000원 이상 결제 시 캐시백", "신한카드", posted))
        assertEquals(MoneyRepository.Outcome.IGNORED, repo.capture("신한카드 10월 결제예정금액 1,234,560원", "신한카드", posted))
        assertEquals(0, repo.captures.first().size)
    }

    @Test fun manualLineCountsAsAPlace() = runBlocking {
        repo.save(MoneyEntry(date = LocalDate.of(2026, 10, 15), kind = MoneyKind.EXPENSE, amount = 4_500, category = "카페", merchant = "동네카페"))
        assertEquals("동네카페", repo.entries.first().single().title)
    }
}
