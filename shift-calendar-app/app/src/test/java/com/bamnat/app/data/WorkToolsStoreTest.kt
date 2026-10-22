package com.bamnat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.core.money.Quote
import com.bamnat.core.money.QuoteItem
import com.bamnat.core.money.QuoteVat
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.work.JobOffer
import com.bamnat.core.work.Staff
import com.bamnat.core.work.StaffRoster
import com.bamnat.core.work.StaffShift
import com.bamnat.core.work.Weather
import com.bamnat.core.work.WeatherNote
import com.bamnat.core.work.WorkContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** [HRLY-09][HRLY-10][HRLY-11][FLD-11][SHOP-10][SHOP-11] Each calendar's work tools survive the file and a backup. */
@RunWith(AndroidJUnit4::class)
class WorkToolsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = LocalDate.of(2026, 10, 20)

    private val full = WorkTools(
        offers = listOf(JobOffer("편의점", 10_320, 20.5, 4, holidayPay = false, nightHoursPerWeek = 3.0, taxMode = TaxMode.WITHHOLDING_3_3, commuteMinutes = 40, commuteCost = 2_900)),
        contract = WorkContract(listOf("a.jpg"), 11_000, setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY), day, day.plusMonths(6), "수습 없음"),
        quit = QuitRecord(day, day.plusDays(2), 3, true, LocalDate.of(2025, 1, 2), 3_000_000),
        quotes = listOf(Quote(7, day, "민지", "욕실", listOf(QuoteItem("타일", 10.5, "m²", 15_000)), QuoteVat.ADDED, 14, "자재 포함", 42)),
        weather = mapOf(day to WeatherNote(day, Weather.RAIN, -3), day.plusDays(1) to WeatherNote(day.plusDays(1), Weather.SUNNY)),
        roster = StaffRoster(listOf(Staff("s1", "준호", 10_500, holidayPay = false, insured = true,
            shifts = mapOf(DayOfWeek.MONDAY to StaffShift(LocalTime.of(22, 0), LocalTime.of(6, 30))))), legalBreaks = false),
        contractMemo = 9,
    )

    @Test fun everythingComesBackFromItsJson() {
        val back = WorkToolsStore.decode(JSONObject(WorkToolsStore.encode(full).toString()))
        assertEquals(full, back)
        assertEquals(WorkTools(), WorkToolsStore.decode(JSONObject("{}")))
    }

    @Test fun hoursTypedAsNaNOrInfinityAreSavedAsZero() {
        val root = File(context.filesDir, "tools-nan").apply { deleteRecursively() }
        val store = WorkToolsStore { File(root, it) }
        store.update("a") { WorkTools(offers = listOf(JobOffer("편의점", 10_320, Double.NaN, 4, nightHoursPerWeek = Double.POSITIVE_INFINITY))) }
        val offer = store.current("a").offers.single()
        assertEquals(0.0, offer.hoursPerWeek, 0.0)
        assertEquals(0.0, offer.nightHoursPerWeek, 0.0)
        // What the screens show is what can be saved.
        assertEquals(store.current("a"), WorkToolsStore.decode(WorkToolsStore.encode(store.current("a"))))
        root.deleteRecursively()
    }

    @Test fun calendarsKeepTheirOwnToolsAndBackupsLeaveMissingPhotosOut() {
        val root = File(context.filesDir, "tools-test").apply { deleteRecursively() }
        val store = WorkToolsStore { File(root, it) }
        store.update("a") { full }
        assertEquals(WorkTools(), store.current("b"))
        val exported = store.export("a")!!
        assertNull(store.export("b"))
        store.restore("b", exported)
        // a.jpg is not in b's folder, so it is not listed there.
        assertEquals(full.copy(contract = full.contract.copy(photos = emptyList())), store.current("b"))
        root.deleteRecursively()
    }
}
