package com.bamnat.app.share

import com.bamnat.app.AppContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.pay.named
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.work.MoneyShare
import com.bamnat.core.work.MoneyShareFigures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

/** [SHR-01] A link's payload from the phone's own data, with only what the sharer ticked. */
object ShareContent {
    suspend fun payload(
        c: AppContainer, snapshot: ScheduleSnapshot, from: LocalDate, title: String, ttlDays: Int?, options: ShareOptions,
        /** [MCAL-03] An added calendar's own kind of work. */
        kind: MoneyShare = kind(c),
    ): JSONObject =
        withContext(Dispatchers.Default) {
            val memos = if (options.memos) memos(c, from) else emptyMap()
            val pay = if (options.pay) money(c, snapshot, from, kind) else emptyList()
            val jobs = if (options.jobs) jobs(c, from) else emptyMap()
            ShareLinkPayload.build(snapshot, from, title, ttlDays, options, memos, pay, kind, jobs)
        }

    /** [SHR-11] The money a link of this user carries: pay for employees, job income for field work, sales for a shop. */
    fun kind(c: AppContainer): MoneyShare = MoneyShare.of(c.settingsRepository.current.workStyle)

    /** [SHR-11] The months' money in the user's own kind; months with nothing to show are left out. */
    suspend fun money(c: AppContainer, snapshot: ScheduleSnapshot, from: LocalDate, kind: MoneyShare): List<SharedPay> =
        withContext(Dispatchers.Default) {
            val months = ShareLinkPayload.months(from)
            val figures = when (kind) {
                MoneyShare.SALES -> MoneyShareFigures.sales(months, c.moneyRepository.sales.first())
                MoneyShare.JOBS -> MoneyShareFigures.jobs(months, c.moneyRepository.jobs.first())
                // With no pay calculation, the salary written in the ledger still lets an employee show it.
                MoneyShare.PAY -> pay(c, snapshot, from).takeIf { it.isNotEmpty() }?.let { return@withContext it }
                    ?: MoneyShareFigures.recordedPay(months, c.moneyRepository.entries.first())
            }
            figures.map { SharedPay(it.month, it.amount, it.label) }
        }

    /** [SHR-11] Each day's booked jobs, every day a job runs, by title and start–end time only. */
    suspend fun jobs(c: AppContainer, from: LocalDate): Map<LocalDate, List<SharedJob>> {
        val until = from.plusDays(ShareLinkPayload.DAYS.toLong())
        val out = LinkedHashMap<LocalDate, MutableList<SharedJob>>()
        c.moneyRepository.jobs.first().sortedWith(compareBy({ it.date }, { it.start })).forEach { j ->
            val start = j.start
            val end = j.end
            val time = if (start != null && end != null) "%02d:%02d-%02d:%02d".format(start.hour, start.minute, end.hour, end.minute) else null
            var d = maxOf(j.date, from)
            while (!d.isAfter(j.lastDate) && d.isBefore(until)) {
                out.getOrPut(d) { mutableListOf() } += SharedJob(j.title, time)
                d = d.plusDays(1)
            }
        }
        return out
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
