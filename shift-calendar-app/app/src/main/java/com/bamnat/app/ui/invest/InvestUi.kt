package com.bamnat.app.ui.invest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.invest.Market
import com.bamnat.core.invest.Portfolio
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/**
 * [INV-01][INV-17][INV-19] How the investor calendar writes its numbers: gains red with +, losses blue
 * with −, as Korean brokers show them; amounts hidden behind dots when the user asks; dollars with
 * their won beside them.
 */
object InvestFmt {
    const val HIDDEN = "•••••"
    private val symbols = DecimalFormatSymbols(Locale.KOREA)
    private val priceFormat = DecimalFormat("#,##0.########", symbols)
    private val usdFormat = DecimalFormat("#,##0.00", symbols)

    fun won(v: Long, hide: Boolean = false): String = if (hide) HIDDEN else Fmt.won(v)
    fun short(v: Long, hide: Boolean = false): String = if (hide) HIDDEN else Fmt.wonShort(v)

    /** "+125,000원", "−3,200원", "0원". */
    fun signed(v: Long, hide: Boolean = false): String = if (hide) HIDDEN else sign(v) + Fmt.won(abs(v))

    /** "+12만", "−8천" for a calendar cell. */
    fun signedShort(v: Long, hide: Boolean = false): String = if (hide) HIDDEN else sign(v) + Fmt.wonShort(abs(v))

    /** "+3.25%"; "–" when there is nothing to divide by yet. */
    fun percent(rate: Double?): String = rate?.let { r ->
        val p = r * 100
        (if (p > 0.004) "+" else if (p < -0.004) "−" else "") + "%.2f%%".format(Locale.ROOT, abs(p))
    } ?: "–"

    /** A price in its own currency: "72,500원", "$187.20", "0.00052원" for a cheap coin. */
    fun price(p: Double, market: Market): String = if (market.foreign) "$" + usdFormat.format(p) else priceFormat.format(p) + "원"

    /** [INV-19] "$187.20 (약 262,080원)". */
    fun priceWithWon(p: Double, market: Market, fx: Double): String =
        if (market.foreign) price(p, market) + " (약 " + Fmt.won(Math.round(p * fx)) + ")" else price(p, market)

    fun quantity(q: Double): String = Portfolio.quantityText(q)

    /** Typed text for a Double field: no trailing ".0". */
    fun input(v: Double?): String = v?.takeIf { it > 0 }?.let { Portfolio.quantityText(it) } ?: ""

    private fun sign(v: Long) = if (v > 0) "+" else if (v < 0) "−" else ""
}

/** [INV-01] Gain red, loss blue, nothing grey: the colours of Korean brokers. */
@Composable
fun pnlColor(v: Double): Color {
    val cal = LocalCalendarColors.current
    return when {
        v > 0.5 -> cal.sunday
        v < -0.5 -> cal.saturday
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
fun pnlColor(v: Long): Color = pnlColor(v.toDouble())

@Composable
fun rateColor(r: Double?): Color = pnlColor(((r ?: 0.0) * 1_000_000))

/** A number with decimals (quantity, price, rate): digits and one point. */
@Composable
fun DecimalField(
    label: String,
    text: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = text,
        onValueChange = { v -> onChange(cleanDecimal(v)) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = modifier,
    )
}

internal fun cleanDecimal(v: String): String {
    val sb = StringBuilder()
    var dot = false
    for (ch in v.replace(',', '.')) {
        if (ch.isDigit()) sb.append(ch)
        else if (ch == '.' && !dot) { dot = true; sb.append(if (sb.isEmpty()) "0." else ".") }
    }
    return sb.toString().take(18)
}

internal fun String.toDecimal(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() }

/** One-tap tags in a wrapping row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(items: List<T>, selected: (T) -> Boolean, label: (T) -> String, onClick: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item -> FilterChip(selected = selected(item), onClick = { onClick(item) }, label = { Text(label(item)) }) }
    }
}

/** A date picker that answers a [LocalDate]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestDatePicker(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) { DatePicker(state, showModeToggle = false) }
}

/** "09:05" for minutes after midnight; "" without. */
internal fun minuteText(m: Int?): String = m?.let { "%02d:%02d".format(it / 60, it % 60) } ?: ""

/** "0905", "9:05" or "905" typed → minutes; null when it is not a time. */
internal fun parseMinute(s: String): Int? {
    val d = s.filter(Char::isDigit)
    if (d.isEmpty()) return null
    val (h, m) = when (d.length) {
        1, 2 -> d.toInt() to 0
        3 -> d.take(1).toInt() to d.drop(1).toInt()
        else -> d.take(2).toInt() to d.drop(2).take(2).toInt()
    }
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}
