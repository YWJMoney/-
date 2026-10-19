package com.bamnat.app.ui.pay

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.ValueSource
import java.time.YearMonth

/**
 * [PAYS-11] Why a figure carries 추정, in one line that names the one thing to put in, and where to put it:
 * [fill] opens that value's editor, [checks] the 확인 필요 answers. Neither means there is no place on this
 * screen, so the line says where (the calendar, or 명세서로 맞춰 보기).
 */
internal class EstimateHint(val text: String, val fill: ValueTarget? = null, val checks: Boolean = false) {
    val action: String? get() = when {
        fill != null -> "지금 넣기"
        checks -> "답하기"
        else -> null
    }
}

internal object Estimates {
    const val HOLIDAY = "휴일에 일하면 어떻게 받는지 답하면 확정돼요."
    const val EXTRA_TIMES = "시간 없이 넣은 추가 근무가 있어요. 달력에서 그 근무에 시작·끝 시간을 넣으면 확정돼요."
    const val GENERIC = "앱이 짐작한 값이 들어 있어요. 명세서로 맞춰 보면 확정돼요."

    /** 기본급 (or 시급·일당) when the app guessed it or worked it out from a raise rule; null when it is the user's. */
    private fun base(profile: PayProfile, month: YearMonth): EstimateHint? {
        val name = profile.wageType.baseName()
        return when {
            Unknowns.BASE in profile.estimatedFields -> EstimateHint("${name}을 앱이 짐작했어요. 명세서에 적힌 ${name}을 넣으면 확정돼요.", ValueTarget.Base)
            profile.base.on(month).source == ValueSource.RAISED ->
                EstimateHint("${name}을 인상 규칙으로 짐작했어요. 이번 달 명세서의 ${name}을 넣으면 확정돼요.", ValueTarget.Base)
            else -> null
        }
    }

    /** One value under 내 급여 값: 기본급 when [item] is null, otherwise that allowance. */
    fun forValue(profile: PayProfile, month: YearMonth, item: PayItem?): EstimateHint {
        if (item == null) return base(profile, month) ?: EstimateHint(GENERIC)
        val target = ValueTarget.Item(item.id)
        return when {
            Unknowns.item(item.id) in profile.estimatedFields ->
                EstimateHint("금액을 몰라 합계에서 뺐어요. 명세서에 적힌 ‘${item.name}’ 금액을 넣으면 합계에 들어가요.", target)
            item.amount.on(month).source == ValueSource.RAISED ->
                EstimateHint("인상 규칙으로 짐작한 금액이에요. 이번 달 명세서의 ‘${item.name}’ 금액을 넣으면 확정돼요.", target)
            else -> EstimateHint("앱이 짐작해 넣은 금액이에요. 명세서에 적힌 ‘${item.name}’ 금액을 넣으면 확정돼요.", target)
        }
    }

    /** One line of 항목별 내역: an allowance is its own amount, the rest follow 기본급 or the 휴일 answer. */
    fun forLine(line: PayLine, profile: PayProfile, month: YearMonth): EstimateHint {
        if (line.kind == LineKind.ALLOWANCE) {
            profile.items.firstOrNull { it.name == line.label }?.let { return forValue(profile, month, it) }
        }
        base(profile, month)?.let { return it }
        if (line.kind == LineKind.HOLIDAY && Unknowns.HOLIDAY_PAY_MODE in profile.estimatedFields) return EstimateHint(HOLIDAY, checks = true)
        return EstimateHint(GENERIC)
    }

    /** The month as a whole (추정 포함 on the total): the first thing that, put in, settles the most. */
    fun forMonth(result: MonthPay, profile: PayProfile): EstimateHint {
        base(profile, result.month)?.let { return it }
        profile.items.firstOrNull { item -> result.lines.any { it.estimated && it.kind == LineKind.ALLOWANCE && it.label == item.name } }
            ?.let { return forValue(profile, result.month, it) }
        if (Unknowns.HOLIDAY_PAY_MODE in profile.estimatedFields && result.lines.any { it.estimated && it.kind == LineKind.HOLIDAY }) {
            return EstimateHint(HOLIDAY, checks = true)
        }
        if (result.minutes.any { it.estimated }) return EstimateHint(EXTRA_TIMES)
        return EstimateHint(GENERIC)
    }
}

/** [PAYS-11] The one line, and the way straight to the place that settles it when there is one. */
@Composable
internal fun EstimateDialog(hint: EstimateHint, onDismiss: () -> Unit, onAct: () -> Unit) {
    val action = hint.action
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("짐작한 값이에요") },
        text = { Text(hint.text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            if (action != null) TextButton(onClick = { onDismiss(); onAct() }) { Text(action) }
            else TextButton(onClick = onDismiss) { Text("알겠어요") }
        },
        dismissButton = if (action != null) ({ TextButton(onClick = onDismiss) { Text("닫기") } }) else null,
    )
}
