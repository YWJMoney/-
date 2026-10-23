package com.bamnat.app.data

import com.bamnat.app.AppContainer
import com.bamnat.core.example.BrowseState
import com.bamnat.core.example.ExamplePlan
import com.bamnat.core.invest.InvestAccount
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.Trade
import com.bamnat.core.memo.Memo
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.Leave
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayPeriod
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * [EASY-01] The example calendar of 우선 둘러볼게요: laid from [ExamplePlan] into a calendar of its own
 * ([PersonalCalendar.EXAMPLE]) whose database and files are in no-backup storage, with its alarms off. Its memos
 * are the one part kept in the main database (memos are one store for every calendar), so [remove] deletes
 * them instead of handing them to 내 달력, and [sweep] takes away any left by a backup restored elsewhere.
 */
class ExampleCalendar(private val c: AppContainer, private val today: () -> LocalDate = LocalDate::now) {
    private val book get() = c.calendars
    /** [sweep] on start and [browse] tapped meanwhile take turns, so a new example is never taken for a stale one. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Makes the example of [style] afresh and shows it. */
    suspend fun make(style: WorkStyle): PersonalCalendar = lock.withLock { lay(style) }

    private suspend fun lay(style: WorkStyle): PersonalCalendar {
        removeMemos()
        val cal = book.addExample(style, ExamplePlan.NAME)
        lay(ExamplePlan.of(style, today()))
        return cal
    }

    private suspend fun lay(plan: ExamplePlan) {
        val id = PersonalCalendar.EXAMPLE
        val repo = book.repository(id)
        val ids = plan.shifts.mapIndexed { i, s ->
            s.name to repo.saveShiftType(ShiftType(0, s.name, s.label, s.colorArgb, s.start, s.end, breakMinutes = s.breakMinutes,
                isOff = s.isOff, alarmEnabled = !s.isOff, alarms = listOf(AlarmSpec(60)), sortOrder = i))
        }.toMap()
        if (plan.rotation.isNotEmpty() && plan.rotationStart != null) {
            repo.savePattern(WorkPattern.Rotation(plan.rotationStart!!, plan.rotation.map { ids[it] }))
        } else if (plan.weekly.size == 7) {
            val start = today().withDayOfMonth(1).minusMonths(1)
            repo.savePattern(WorkPattern.Weekly(start, DayOfWeek.entries.associateWith { d -> plan.weekly[d.ordinal]?.let { ids[it] } }))
        }
        plan.swaps.forEach { (date, name) -> ids[name]?.let { repo.setOverride(date, ShiftOverride.Shift(it)) } }
        plan.extras.forEach { e ->
            val special = e.special
            if (special != null) repo.addExtra(e.date, ExtraEntry(ExtraKind.SPECIAL_ID, special.first, special.second, 60, LocalTime.of(12, 0)))
            else if (e.overtimeMinutes > 0) repo.addExtra(e.date, ExtraEntry(ExtraKind.OVERTIME_ID, minutes = e.overtimeMinutes))
        }
        plan.leave.forEach { repo.setLeave(it, Leave()) }
        plan.memos.forEach { m -> c.memoRepository.save(Memo(date = m.date, title = m.title, body = m.body, pinned = m.pinned, calendars = setOf(id))) }
        val money = book.money(id)
        plan.money.forEach { m -> money.save(MoneyEntry(date = m.date, kind = m.kind, amount = m.amount, category = m.category, note = m.note)) }
        plan.jobs.forEach { j -> money.save(Job(date = j.date, title = j.title, amount = j.amount, balancePaid = j.paid)) }
        plan.sales.forEach { s -> money.save(DailySales(s.date, sales = s.sales, costs = s.costs)) }
        if (plan.trades.isNotEmpty()) {
            val invest = InvestRepository.of(book.database(id))
            val account = invest.saveAccount(InvestAccount(name = "예시 계좌"))
            plan.trades.forEachIndexed { i, t ->
                invest.saveTrade(Trade(accountId = account, date = t.date, symbol = "EX$i", name = t.name,
                    side = if (t.buy) Side.BUY else Side.SELL, quantity = t.quantity.toDouble(), price = t.price.toDouble()))
            }
        }
        plan.pay?.let { p ->
            book.pay(id).save(PayProfile(
                occupation = if (p.wageType == WageType.HOURLY) Occupation.PART_TIME else Occupation.entries.first(),
                wageType = p.wageType,
                base = ValueTimeline(listOf(DatedValue(p.hireDate, p.base))),
                hireDate = p.hireDate,
                period = PayPeriod(payDay = p.payDay),
            ), keepUndo = false)
        }
    }

    /** [EASY-01] 우선 둘러볼게요 with [style]: the example is made and the app opens on it. */
    suspend fun browse(style: WorkStyle) = lock.withLock {
        c.browse.set(com.bamnat.core.example.Browse.start(style, today()))
        lay(style)
    }

    /**
     * [EASY-01] 내 정보로 시작 ([keepExample] false) or 예시는 두고 새 달력에서 시작 (true): looking around ends and the
     * first setup opens with the example's kind of work already chosen. A kept example stays as a calendar of its
     * own; 내 달력 is shown for the setup either way.
     */
    suspend fun startMine(keepExample: Boolean) {
        val style = c.browse.current.style ?: book.get(PersonalCalendar.EXAMPLE)?.style
        if (keepExample) c.browse.kept = true else remove()
        book.setActive(PersonalCalendar.MAIN)
        c.browse.set(BrowseState())
        if (c.setupDrafts.draft.value == null && style != null) c.setupDrafts.save(com.bamnat.core.setup.SetupDraft(style = style))
    }

    /**
     * Takes the example away: its memos (never handed to 내 달력), then the calendar and its files. Used by
     * 내 정보로 시작 and when the user removes a kept example.
     */
    suspend fun remove() {
        removeMemos()
        if (book.get(PersonalCalendar.EXAMPLE) != null) book.delete(PersonalCalendar.EXAMPLE)
        else {
            // Files with no entry: left by a start cut short.
            c.appContext.deleteDatabase(CalendarStorage.pathOf(c.appContext, PersonalCalendar.EXAMPLE))
            CalendarStorage.filesOf(c.appContext, PersonalCalendar.EXAMPLE).deleteRecursively()
        }
    }

    /**
     * On start: an example the app is not showing any more (a backup of the calendar list restored on another
     * phone carries its entry but never its database) goes, with any memos of it.
     */
    suspend fun sweep() = lock.withLock {
        val there = book.get(PersonalCalendar.EXAMPLE) != null
        val hasFile = File(CalendarStorage.pathOf(c.appContext, PersonalCalendar.EXAMPLE)).exists()
        if (!(there && hasFile && (c.browse.current.browsing || c.browse.kept))) remove()
    }

    private suspend fun removeMemos() {
        val mine = c.memoRepository.memos.first().filter { m -> m.calendars == setOf(PersonalCalendar.EXAMPLE) }
        if (mine.isNotEmpty()) c.memoRepository.deleteForever(mine.map { it.id })
        // Shared with other calendars as well: only the example's own mark goes.
        c.memoRepository.memos.first().filter { PersonalCalendar.EXAMPLE in it.calendars }
            .let { shared -> shared.groupBy { it.calendars - PersonalCalendar.EXAMPLE }.forEach { (rest, list) -> c.memoRepository.setCalendars(list, rest) } }
    }
}
