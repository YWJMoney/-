package com.bamnat.app.ui.work

import com.bamnat.app.ui.guide.FeatureCard
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.WorkToolsStore
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.app.ui.money.AmountField
import com.bamnat.app.ui.money.amountDigits
import com.bamnat.app.ui.money.toAmount
import com.bamnat.app.ui.money.wonBrief
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.pay.RateTable
import com.bamnat.core.work.LaborCost
import com.bamnat.core.work.SimpleTaxLimit
import com.bamnat.core.work.Staff
import com.bamnat.core.work.StaffShift
import com.bamnat.core.work.Weather
import com.bamnat.core.work.WeatherNote
import com.bamnat.core.work.WeatherSales
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID

/**
 * [SHOP-09][SHOP-10][SHOP-11] A shop's extra cards on 매출·영업: 간이과세 기준 against this year's sales,
 * 날씨별 매출, and 직원 인건비 with the staff schedule.
 */
@Composable
fun ShopToolCards(calendarId: String, book: MoneyBook, month: YearMonth, today: LocalDate = LocalDate.now()) {
    // [GUIDE-05] Each card can be turned off for its calendar.
    FeatureCard("shop.staff", calendarId) { StaffCostCard(calendarId, book, month) }
    FeatureCard("shop.weather", calendarId) { WeatherSalesCard(calendarId, book, month) }
    FeatureCard("shop.simpleTax", calendarId) { SimpleTaxCard(book, today) }
}

/** [SHOP-09] 간이과세 기준 대비 올해 매출: progress, where the year is heading and a warning near or over 1억 400만 원. */
@Composable
private fun SimpleTaxCard(book: MoneyBook, today: LocalDate) {
    val y = remember(book.sales, today) { SimpleTaxLimit.of(today, book.sales) }
    if (y.sales <= 0) return
    val cal = LocalCalendarColors.current
    val warn = y.level != SimpleTaxLimit.Level.UNDER
    SectionCard(title = "간이과세 기준 (${y.year}년)") {
        Text("${Fmt.won(y.sales)} / ${Fmt.won(SimpleTaxLimit.LIMIT)} · ${y.percent}%", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(
            progress = { (y.percent / 100f).coerceIn(0f, 1f) },
            color = if (warn) cal.warning else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        y.projected?.let { Text("이대로면 올해 약 ${wonBrief(it)}", style = MaterialTheme.typography.bodyMedium) }
        Text(
            when (y.level) {
                SimpleTaxLimit.Level.OVER -> "올해 매출이 기준을 넘었어요. 간이과세자라면 내년 7월부터 일반과세자가 돼요."
                SimpleTaxLimit.Level.HEADING_OVER -> "이대로 가면 올해 기준을 넘어요. 간이과세자라면 내년 7월부터 일반과세로 바뀔 수 있어요."
                SimpleTaxLimit.Level.NEAR -> "기준의 ${SimpleTaxLimit.NEAR_PERCENT}%를 넘었어요. 남은 금액 ${Fmt.won(y.left)}."
                SimpleTaxLimit.Level.UNDER -> "기준까지 ${Fmt.won(y.left)} 남았어요."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (warn) cal.warning else MaterialTheme.colorScheme.onSurface,
        )
        Hint(
            "간이과세는 한 해(1~12월) 매출이 부가세 포함 1억 400만 원 미만일 때 받을 수 있어요(2024년 7월부터, 부동산임대업 등은 4,800만 원). " +
                "4,800만 원 미만이면 그해 부가세를 내지 않아요. 앱에 적은 매출로만 센 참고값이에요.",
            Modifier.padding(top = 6.dp),
        )
    }
}

internal val Weather.icon: ImageVector
    get() = when (this) {
        Weather.SUNNY -> Icons.Outlined.WbSunny
        Weather.CLOUDY -> Icons.Outlined.Cloud
        Weather.RAIN -> Icons.Outlined.Umbrella
        Weather.SNOW -> Icons.Outlined.AcUnit
    }

/** [SHOP-10] "맑음 · 18°C" */
internal fun WeatherNote.words(): String = weather.label + (temperature?.let { " · ${it}°C" } ?: "")

/**
 * [SHOP-10] 날씨 고르기 in 하루 마감: 맑음·흐림·비·눈 (a second tap clears it) and the temperature if wanted.
 * The choice is held by the caller and saved with the day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherPicker(weather: Weather?, temperature: String, onWeather: (Weather?) -> Unit, onTemperature: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("날씨 (선택)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Weather.entries.forEach { w ->
                FilterChip(selected = weather == w, onClick = { onWeather(if (weather == w) null else w) }, label = { Text(w.label) },
                    leadingIcon = { Icon(w.icon, null, Modifier.size(18.dp)) })
            }
        }
        if (weather != null) {
            OutlinedTextField(
                value = temperature,
                onValueChange = { v -> onTemperature(v.filterIndexed { i, ch -> ch.isDigit() || (i == 0 && ch == '-') }.take(3)) },
                label = { Text("기온 (선택)") }, suffix = { Text("°C") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** [SHOP-10] Saves the day's weather for [calendarId]; null clears it. */
fun saveWeather(store: WorkToolsStore, calendarId: String, date: LocalDate, weather: Weather?, temperature: String) {
    store.update(calendarId) { t ->
        val next = if (weather == null) t.weather - date
        else t.weather + (date to WeatherNote(date, weather, temperature.toIntOrNull()?.coerceIn(-40, 50)))
        t.copy(weather = next)
    }
}

/** [SHOP-10] The day's weather under its sales on the day sheet, when one was picked. */
@Composable
fun WeatherLine(calendarId: String, date: LocalDate) {
    val context = LocalContext.current
    val store = remember { WorkToolsStore.get(context) }
    val tools by store.of(calendarId).collectAsStateWithLifecycle()
    val note = tools.weather[date] ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(note.weather.icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(note.words(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [SHOP-10] 날씨별 매출: average sales by weather over the last three months up to the month shown. */
@Composable
private fun WeatherSalesCard(calendarId: String, book: MoneyBook, month: YearMonth) {
    val context = LocalContext.current
    val store = remember { WorkToolsStore.get(context) }
    val tools by store.of(calendarId).collectAsStateWithLifecycle()
    val from = month.minusMonths(2).atDay(1)
    val rows = remember(tools.weather, book.sales, month) { WeatherSales.compare(tools.weather.values, book.sales, from, month.atEndOfMonth()) }
    SectionCard(title = "날씨별 매출 (${from.monthValue}~${month.monthValue}월)") {
        if (rows.isEmpty()) {
            Hint("하루 마감에서 날씨를 고르면 비 오는 날과 맑은 날의 평균 매출을 견줘 드려요.")
            return@SectionCard
        }
        val max = rows.maxOf { it.average }.coerceAtLeast(1)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(r.weather.icon, null, Modifier.size(18.dp))
                Text("${r.weather.label} ${r.days}일", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp).padding(start = 6.dp))
                LinearProgressIndicator(progress = { r.average.toFloat() / max }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(wonBrief(r.average), style = MaterialTheme.typography.bodyMedium)
            }
        }
        WeatherSales.rainAgainstSun(rows)?.let { pct ->
            Text(
                when {
                    pct < 0 -> "비 오는 날은 맑은 날보다 ${-pct}% 덜 팔렸어요"
                    pct > 0 -> "비 오는 날은 맑은 날보다 ${pct}% 더 팔렸어요"
                    else -> "비 오는 날과 맑은 날 매출이 비슷해요"
                },
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Hint("매출이 있는 날 가운데 날씨를 고른 날만 셌어요.")
    }
}

/**
 * [SHOP-11] 직원 인건비: the month's staff cost from the schedule — pay, 주휴수당 and the 4대보험 employer
 * share — and 인건비율 against the month's sales, with 근무표 짜기.
 */
@Composable
private fun StaffCostCard(calendarId: String, book: MoneyBook, month: YearMonth) {
    val context = LocalContext.current
    val store = remember { WorkToolsStore.get(context) }
    val tools by store.of(calendarId).collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    // [GUIDE-01] Opened from the guide or the help search.
    com.bamnat.app.ui.guide.FocusedTool("staff", "shop.staff") { open = true }
    val roster = tools.roster
    val cost = remember(roster, month) { LaborCost.month(roster, month) }
    val sales = remember(book.sales, month) { book.sales.filter { YearMonth.from(it.date) == month }.sumOf { it.sales } }
    SectionCard(title = "직원 인건비 (${month.monthValue}월)", trailing = { TextButton(onClick = { open = true }) { Text(if (roster.staff.isEmpty()) "근무표 짜기" else "근무표") } }) {
        if (roster.staff.isEmpty()) {
            Hint("알바생 이름·시급·요일별 시간을 적으면 주휴수당과 4대보험까지 한 달 인건비를 계산하고, 매출 대비 인건비율을 보여 드려요.")
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("인건비", Fmt.won(cost.total), Modifier.weight(1f), caption = "${roster.staff.size}명 · ${cost.minutes / 60}시간")
            val ratio = cost.ratio(sales)
            StatTile("인건비율", ratio?.let { "$it%" } ?: "-", Modifier.weight(1f), caption = if (sales > 0) "매출 ${wonBrief(sales)} 대비" else "매출을 적으면 나와요",
                warning = (ratio ?: 0) >= 35)
        }
        Column(Modifier.padding(top = 8.dp)) {
            cost.lines.forEach { l ->
                ValueLine(l.staff.name.ifBlank { "이름 없음" }, Fmt.won(l.total))
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ValueLine("급여", Fmt.won(cost.lines.sumOf { it.base }))
            ValueLine("주휴수당", Fmt.won(cost.lines.sumOf { it.holidayPay }))
            if (cost.insurance > 0) ValueLine("4대보험 (사장님 몫)", Fmt.won(cost.insurance))
        }
        Hint("근무표대로 셌어요. 야간·휴일 가산과 산재보험은 빠져 있어요. 참고용이에요.", Modifier.padding(top = 6.dp))
    }
    if (open) StaffScreen(calendarId, month) { open = false }
}

private val DEFAULT_SHIFT = StaffShift(LocalTime.of(9, 0), LocalTime.of(13, 0))

/**
 * [SHOP-11] 직원 근무표: the week at a glance (who works when), then each staff member with 시급, 주휴수당
 * 대상, 4대보험 and the hours of each weekday; the month's cost follows the same [LaborCost] as the card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffScreen(calendarId: String, month: YearMonth, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { WorkToolsStore.get(context) }
    var roster by remember { mutableStateOf(store.current(calendarId).roster) }
    val minimum = RateTable.on(month.atDay(1)).minimumHourlyWage.toLong()
    fun save() { store.update(calendarId) { it.copy(roster = roster) }; onClose() }
    ToolScreen("직원 근무표", onClose, action = "저장", onAction = ::save) {
        if (roster.staff.isNotEmpty()) {
            SectionCard(title = "한 주 근무표") {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Column {
                        Text("", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp).padding(vertical = 4.dp))
                        roster.staff.forEach { s ->
                            Text(s.name.ifBlank { "이름 없음" }, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                modifier = Modifier.width(64.dp).padding(vertical = 6.dp))
                        }
                    }
                    DayOfWeek.entries.forEach { d ->
                        Column {
                            Text(Fmt.dayOfWeekShort(d), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp).padding(vertical = 4.dp))
                            roster.staff.forEach { s ->
                                val sh = s.shifts[d]
                                Text(sh?.let { "${short(it.start)}-${short(it.end)}" } ?: "·", style = MaterialTheme.typography.bodySmall,
                                    color = if (sh == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.width(56.dp).padding(vertical = 6.dp), maxLines = 1)
                            }
                        }
                    }
                }
            }
            val cost = LaborCost.month(roster, month)
            SectionCard(title = "${month.monthValue}월 인건비") {
                cost.lines.forEach { l ->
                    ValueLine("${l.staff.name.ifBlank { "이름 없음" }} · ${l.workMinutes / 60}시간", Fmt.won(l.total))
                    Hint("급여 ${Fmt.won(l.base)} + 주휴 ${Fmt.won(l.holidayPay)}" + if (l.insurance > 0) " + 4대보험 ${Fmt.won(l.insurance)}" else "")
                }
                ValueLine("합계", Fmt.won(cost.total), strong = true)
            }
        }
        SwitchLine("휴게시간 빼고 세기", roster.legalBreaks, { roster = roster.copy(legalBreaks = it) }, "4시간이면 30분, 8시간이면 1시간을 빼요(근로기준법 제54조)")
        roster.staff.forEachIndexed { i, s ->
            key(s.id) {
                StaffEditor(s, minimum, onChange = { n -> roster = roster.copy(staff = roster.staff.toMutableList().also { it[i] = n }) },
                    onRemove = { roster = roster.copy(staff = roster.staff.filterIndexed { k, _ -> k != i }) })
            }
        }
        if (roster.staff.size < 30) OutlinedButton(onClick = {
            roster = roster.copy(staff = roster.staff + Staff(UUID.randomUUID().toString(), "", minimum))
        }) { Text("직원 더하기") }
        Hint("주휴수당은 한 주 15시간 이상이면 한 주 시간 ÷ 40 × 8시간만큼 줘요. 4대보험 사장님 몫은 국민연금 4.75%, 건강보험 3.595%, 장기요양, 고용보험 1.15%(150명 미만)로 셌어요.")
    }
}

private fun short(t: LocalTime): String = if (t.minute == 0) "${t.hour}" else "${t.hour}:%02d".format(t.minute)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffEditor(s: Staff, minimum: Long, onChange: (Staff) -> Unit, onRemove: () -> Unit) {
    var picking by remember { mutableStateOf<Pair<DayOfWeek, Boolean>?>(null) }
    SectionCard(title = s.name.ifBlank { "새 직원" }, trailing = { IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "직원 빼기") } }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(s.name, { onChange(s.copy(name = it.take(12))) }, label = { Text("이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            AmountField("시급", amountDigits(s.hourlyWage), { onChange(s.copy(hourlyWage = it.toAmount())) })
            if (s.hourlyWage in 1 until minimum) Text("최저임금 ${Fmt.won(minimum)}보다 적어요", style = MaterialTheme.typography.bodySmall, color = LocalCalendarColors.current.warning)
            SwitchLine("주휴수당 주기", s.holidayPay, { onChange(s.copy(holidayPay = it)) }, "한 주 15시간이 안 되면 저절로 빠져요")
            SwitchLine("4대보험 가입", s.insured, { onChange(s.copy(insured = it)) }, "사장님이 내는 몫을 인건비에 더해요")
            Text("일하는 요일", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(selected = d in s.shifts, label = { Text(Fmt.dayOfWeekShort(d)) }, onClick = {
                        val next = if (d in s.shifts) s.shifts - d else s.shifts + (d to (s.shifts.values.lastOrNull() ?: DEFAULT_SHIFT))
                        onChange(s.copy(shifts = next))
                    })
                }
            }
            DayOfWeek.entries.filter { it in s.shifts }.forEach { d ->
                val sh = s.shifts.getValue(d)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.dayOfWeekShort(d), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(28.dp))
                    TextButton(onClick = { picking = d to true }) { Text(Fmt.time(sh.start)) }
                    Text("~")
                    TextButton(onClick = { picking = d to false }) { Text(Fmt.time(sh.end)) }
                    Text(com.bamnat.core.hours.HoursFormat.korean(sh.spanMinutes.toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (s.shifts.isNotEmpty()) Hint("한 주 ${com.bamnat.core.hours.HoursFormat.korean(s.weeklyMinutes(false).toInt())} (휴게 빼기 전)")
        }
    }
    picking?.let { (day, isStart) ->
        val sh = s.shifts[day] ?: DEFAULT_SHIFT
        TimePickerDialog(title = "${Fmt.dayOfWeekShort(day)}요일 ${if (isStart) "시작" else "끝"}", initial = if (isStart) sh.start else sh.end,
            onDismiss = { picking = null }) { t ->
            val next = if (isStart) sh.copy(start = t) else sh.copy(end = t)
            if (next.start != next.end) onChange(s.copy(shifts = s.shifts + (day to next)))
            picking = null
        }
    }
}
