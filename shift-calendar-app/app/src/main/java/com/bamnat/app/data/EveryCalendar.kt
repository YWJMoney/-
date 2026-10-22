package com.bamnat.app.data

import com.bamnat.app.AppContainer
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.FeatureGate
import com.bamnat.app.tier.Tier
import com.bamnat.core.money.AllCalendarsMonth
import com.bamnat.core.money.CalendarMonth
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.work.CombinedView
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.YearMonth

/** [COMN-01][COMN-02][COMN-03] One calendar's days, money and pay, for the views over every calendar. */
data class CalendarParts(
    val cal: PersonalCalendar,
    val style: WorkStyle?,
    val snapshot: ScheduleSnapshot,
    val book: MoneyBook,
    val profile: PayProfile?,
)

/** [COMN-01][COMN-02][COMN-03] Every calendar side by side: 합쳐 보기, the money tab's 모든 달력 합계 and 통계's 전체. */
object EveryCalendar {
    /** Each ready calendar's parts, in the list's order, following every change in any of them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun parts(c: AppContainer): Flow<List<CalendarParts>> = c.calendars.calendars.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { cal ->
            val scope = c.scopeOf(cal.id)
            combine(scope.schedule.snapshot, scope.money.book, scope.pay.profile, scope.settings) { s, b, p, st ->
                CalendarParts(cal, st.workStyle, s, b, p)
            }
        }) { it.toList() }
    }

    /**
     * Every calendar's [month]: its money with its own wage estimate (as its money tab counts it), and
     * its days and hours of work. Heavy for wage calendars; call it off the main thread.
     */
    fun month(parts: List<CalendarParts>, month: YearMonth, tier: Tier): AllCalendarsMonth = AllCalendarsMonth(parts.map { p ->
        val estimate = p.profile?.takeIf { p.style?.usesPayEngine != false && FeatureGate.allows(tier, Feature.PAY_ENGINE) }?.let { profile ->
            runCatching { PayCalculator().calculate(profile, month, p.snapshot.resolver::resolve).total }.getOrNull()
        }
        val days = (1..month.lengthOfMonth()).map { p.snapshot.resolver.resolve(month.atDay(it)) }
        val work = CombinedView.workOf(days, p.book.jobs)
        CalendarMonth(p.cal.id, p.cal.name, p.book.month(month, estimate), work.days, work.minutes)
    })
}
