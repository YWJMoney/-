package com.bamnat.app.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
    var opening by rememberSaveable { mutableStateOf(amountDigits(rules.openingCash)) }
    val update = { t: (ShopMoney) -> ShopMoney -> calendars.updateShopMoney(calendarId, t) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("카드 매출 입금", style = MaterialTheme.typography.titleMedium)
            Text("카드 매출은 수수료를 떼고 며칠 뒤에 들어와요. 달력에 입금 예정일이 보이고, 들어오면 입금 확인을 눌러요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // [INPUT-02] The fee is tapped or stepped by 0.05%, saved as it changes; nothing to type.
            Text("카드 수수료율", style = MaterialTheme.typography.labelLarge)
            com.bamnat.app.ui.components.QuickDecimalChips(
                value = rules.cardFeeBp / 100.0, onChange = { p -> update { it.copy(cardFeeBp = (p * 100).roundToInt()) } },
                name = "카드 수수료율", quick = listOf(0.5, 1.1, 1.5, 2.0), step = 0.05, range = 0.0..10.0, unit = "%",
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
