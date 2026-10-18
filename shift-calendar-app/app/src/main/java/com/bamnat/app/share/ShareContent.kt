package com.bamnat.app.share

import com.bamnat.app.AppContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.pay.named
import com.bamnat.core.pay.PayCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

/** [SHR-01] A link's payload from the phone's own data, with only what the sharer ticked. */
object ShareContent {
    suspend fun payload(c: AppContainer, snapshot: ScheduleSnapshot, from: LocalDate, title: String, ttlDays: Int, options: ShareOptions): JSONObject =
        withContext(Dispatchers.Default) {
            val memos = if (options.memos) memos(c, from) else emptyMap()
            val pay = if (options.pay) pay(c, snapshot, from) else emptyList()
            ShareLinkPayload.build(snapshot, from, title, ttlDays, options, memos, pay)
        }

    /** Each day's memos in the link's days, as one line a day, newest first as under the calendar. */
    suspend fun memos(c: AppContainer, from: LocalDate): Map<LocalDate, String> {
        val until = from.plusDays(ShareLinkPayload.DAYS.toLong())
        return c.memoRepository.memos.first()
            .filter { m -> !m.inTrash && !m.isBlank && m.date?.let { !it.isBefore(from) && it.isBefore(until) } == true }
            .sortedByDescending { it.updatedAtMillis }
            .groupBy { it.date!! }
            .mapNotNull { (date, list) -> ShareLinkPayload.memoLine(list)?.let { date to it } }
            .toMap()
    }

    /** The months' pay before tax: the closed figure when a month was closed, else this calendar's estimate. */
    fun pay(c: AppContainer, snapshot: ScheduleSnapshot, from: LocalDate): List<SharedPay> {
        val profile = c.payStore.profile.value ?: return emptyList()
        val closed = c.payStore.closed.value
        val calculator = PayCalculator()
        return ShareLinkPayload.months(from).map { m ->
            closed[m]?.let { SharedPay(m, it.total, "세전 확정") }
                ?: SharedPay(m, calculator.calculate(profile.named(snapshot.kinds), m, snapshot.resolver::resolve).total, "세전 예상")
        }
    }
}
