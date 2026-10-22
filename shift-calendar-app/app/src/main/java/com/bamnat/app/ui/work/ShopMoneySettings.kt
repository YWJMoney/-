package com.bamnat.app.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.ui.money.AmountField
import com.bamnat.app.ui.money.amountDigits
import com.bamnat.app.ui.money.toAmount
import com.bamnat.core.work.ShopMoney
import kotlin.math.roundToInt

/**
 * [SHOP-02][SHOP-03] The shop's money settings beside its days: the card fee (기본 1.5%), the
 * business days until card money comes in (기본 2일) and the cash the till opens with. Saved as
 * they are changed, for this calendar only.
 */
@Composable
fun ShopMoneySettings(calendarId: String) {
    val calendars = LocalContext.current.appContainer.calendars
    val list by calendars.calendars.collectAsStateWithLifecycle()
    val rules = list.firstOrNull { it.id == calendarId }?.shopMoney ?: ShopMoney()
    var fee by rememberSaveable { mutableStateOf(rules.feeLabel.removeSuffix("%")) }
    var opening by rememberSaveable { mutableStateOf(amountDigits(rules.openingCash)) }
    val update = { t: (ShopMoney) -> ShopMoney -> calendars.updateShopMoney(calendarId, t) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("카드 매출 입금", style = MaterialTheme.typography.titleMedium)
            Text("카드 매출은 수수료를 떼고 며칠 뒤에 들어와요. 달력에 입금 예정일이 보이고, 들어오면 입금 확인을 눌러요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = fee,
                onValueChange = { v ->
                    fee = v.filter { it.isDigit() || it == '.' }.take(5)
                    fee.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }?.let { p -> update { it.copy(cardFeeBp = (p * 100).roundToInt()) } }
                },
                label = { Text("카드 수수료율") }, suffix = { Text("%") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("입금까지 영업일", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(enabled = rules.settleDays > 0, onClick = { update { it.copy(settleDays = it.settleDays - 1) } }) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
                Text("${rules.settleDays}일", style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = rules.settleDays < 10, onClick = { update { it.copy(settleDays = it.settleDays + 1) } }) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
            }
            Text("주말과 공휴일은 빼고 세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AmountField("시작 시재 (문 열 때 돈통 현금)", opening, { v -> opening = v; update { it.copy(openingCash = v.toAmount()) } })
        }
    }
}
