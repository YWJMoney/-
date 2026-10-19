package com.bamnat.app.ui.pay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * [SETUP-04] The words pay slips and the law use, each in one plain sentence with an example in won, so a
 * question that needs one can explain it where it is asked instead of sending the user away.
 */
internal object PayTerms {
    class Term(val name: String, val meaning: String, val example: String? = null)

    val all: List<Term> = listOf(
        Term("통상임금", "매달 정해진 대로 받는 돈이에요. 기본급과 매달 똑같이 받는 수당(직책·자격수당 같은)이 들어가요. 연장·야간·휴일 수당은 이걸로 계산해요.",
            "기본급 250만원 + 직책수당 10만원이면 통상임금은 260만원이에요."),
        Term("통상시급", "통상임금을 한 달 일하는 시간으로 나눈 1시간 값이에요. 가산은 모두 이 값에서 시작해요.",
            "260만원 ÷ 209시간 = 1시간 약 12,440원이에요."),
        Term("소정근로시간", "계약서에 정한, 원래 일하기로 한 시간이에요. 주 40시간이면 주휴를 넣어 한 달 209시간으로 봐요.",
            "하루 8시간 × 주 5일 = 주 40시간 → 한 달 209시간이에요."),
        Term("가산", "연장·야간·휴일에 일하면 시급에 더 얹어 주는 몫이에요. 법은 50%를 더하라고 정해요.",
            "통상시급 1만원으로 밤 10시 넘어 2시간 일하면 2시간 × 1만원 × 50% = 1만원을 더 받아요."),
        Term("주휴수당", "한 주에 15시간 넘게 일하고 정한 날을 다 나오면, 쉬는 하루치를 더 주는 돈이에요. 월급에는 보통 들어 있어요.",
            "시급 1만원, 하루 8시간이면 한 주에 8만원이에요."),
        Term("5인 이상", "직원이 5명 이상인 회사는 연장·야간·휴일 가산을 법으로 줘야 해요. 5명 미만이면 회사가 정한 만큼만 받아요.", null),
        Term("휴일과 휴무일", "휴일은 쉬기로 정해진 날(주휴일·공휴일)이고, 휴무일은 그냥 근무가 없는 날이에요. 휴일에 일하면 휴일 가산이 붙어요.", null),
        Term("차감", "회사가 주는 돈에서 빼는 항목이에요. 기숙사비·식대처럼 명세서 지급 칸에서 빠지는 것만 넣어요. 세금과 4대보험은 넣지 않아도 돼요.",
            "기숙사비 매달 5만원을 넣으면 앱 합계가 5만원 줄어요."),
        Term("추정", "넣지 않은 값을 앱이 짐작해 넣은 부분이에요. 실제 값으로 바꾸면 사라져요.", null),
        Term("확인 필요", "‘모르겠어요’로 둔 답이에요. 흔한 값으로 계산하고, 답에 따라 달라지는 범위를 함께 보여 줘요.", null),
    )

    fun of(name: String): Term = all.first { it.name == name }
}

/** [SETUP-04] "통상임금이 뭐예요?": a small link that opens the term in plain words, where the question is asked. */
@Composable
internal fun TermLink(term: String, modifier: Modifier = Modifier, label: String = "${term}이란?") {
    var open by remember { mutableStateOf(false) }
    // No start padding, so the link lines up with the text above it; the 48 dp height keeps it easy to hit.
    TextButton(
        onClick = { open = true },
        modifier = modifier.heightIn(min = 48.dp).semantics { role = Role.Button },
        contentPadding = PaddingValues(start = 0.dp, end = 12.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.HelpOutline, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
    if (open) TermDialog(PayTerms.of(term)) { open = false }
}

@Composable
internal fun TermDialog(term: PayTerms.Term, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(term.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(term.meaning, style = MaterialTheme.typography.bodyLarge)
                term.example?.let { ex ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Text("예) ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(ex, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("알겠어요") } },
    )
}
