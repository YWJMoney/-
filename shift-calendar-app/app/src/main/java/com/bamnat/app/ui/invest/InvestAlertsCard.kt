package com.bamnat.app.ui.invest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.TradeAlerts

/**
 * [INV-32] 알림으로 찾은 거래: each 체결 notification read on the phone waits here until the user writes
 * it (적기), opens it to fix first (고쳐서 적기) or lets it go (넘기기). Nothing is written without them
 * unless 알림 오면 바로 적기 is on (결정 D37).
 */
@Composable
internal fun FoundTradesCard(vm: InvestViewModel, state: InvestState, open: (InvestSheet) -> Unit) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val auto by vm.alertAuto.collectAsStateWithLifecycle()
    if (alerts.isEmpty()) return
    SectionCard(title = "알림으로 찾은 거래 ${alerts.size}건") {
        Text("증권사·거래소 체결 알림에서 읽었어요. 맞으면 적기, 다르면 고쳐서 적어 주세요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        alerts.sortedByDescending { it.at }.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider()
            FoundTrade(p, state) { action ->
                when (action) {
                    FoundAction.SAVE -> vm.confirmAlert(p)
                    FoundAction.EDIT -> open(InvestSheet.TradeEdit(p.toDraft(state.account ?: state.settings.lastAccountId)))
                    FoundAction.DROP -> vm.dropAlert(p)
                }
            }
        }
        HorizontalDivider()
        AutoRow(auto) { vm.setAlertAuto(it) }
    }
}

private enum class FoundAction { SAVE, EDIT, DROP }

@Composable
private fun FoundTrade(p: TradeAlerts.Pending, state: InvestState, onAction: (FoundAction) -> Unit) {
    val time = java.time.Instant.ofEpochMilli(p.at).atZone(java.time.ZoneId.systemDefault())
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.side.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = if (p.side == Side.BUY) com.bamnat.app.ui.theme.LocalCalendarColors.current.sunday else com.bamnat.app.ui.theme.LocalCalendarColors.current.saturday)
            TradeAlerts.badge(p.market)?.let { MarketBadge(it, p.market) }
            Text(p.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Text(
            listOfNotNull(
                "${InvestFmt.quantity(p.quantity)}${p.market.unit}" + (if (p.price > 0) " × ${InvestFmt.price(p.price, p.market)}" else " · 단가 없음"),
                "${time.monthValue}/${time.dayOfMonth} ${"%02d:%02d".format(time.hour, time.minute)}",
                "${p.app} 알림",
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A price the notification did not say has to be typed first.
            if (p.price > 0) FilledTonalButton(onClick = { onAction(FoundAction.SAVE) }) { Text("적기") }
            OutlinedButton(onClick = { onAction(FoundAction.EDIT) }) { Text("고쳐서 적기") }
            TextButton(onClick = { onAction(FoundAction.DROP) }) { Text("넘기기") }
        }
    }
}

@Composable
internal fun AutoRow(auto: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("알림 오면 바로 적기", style = MaterialTheme.typography.bodyMedium)
            Text("끄면 여기서 확인한 뒤에 적어요. 켜도 단가가 없는 알림은 기다려요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = auto, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = "알림 오면 바로 적기" })
    }
}
