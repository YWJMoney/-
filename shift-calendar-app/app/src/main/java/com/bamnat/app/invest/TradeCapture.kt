package com.bamnat.app.invest

import androidx.core.app.NotificationCompat
import com.bamnat.app.AppContainer
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.core.invest.Portfolio
import com.bamnat.core.invest.TradeAlertParser
import com.bamnat.core.invest.TradeAlerts

/**
 * [INV-30][INV-31][INV-32] A broker's or exchange's 체결 notification becomes a trade on the user's investor
 * calendar: waiting for 확인 on its screen (결정 D37), or written at once when 알림 오면 바로 적기 is on.
 * With no investor calendar nothing changes: the text goes on to the ledger as before. A trade is never
 * spending, so once taken here it does not also become a ledger line.
 */
object TradeCapture {
    private const val NOTICE_ID = 7990
    private const val REQUEST_OPEN = 3603
    private val BROKER_WORDS = Regex("증권|투자증권|업비트|빗썸|코인원|코빗|고팍스|토스증권|카카오페이증권|키움|미래에셋")

    /** Worth reading: a trading app's notification, or a chat app's 알림톡 that names a broker. */
    fun worthReading(pkg: String, text: String, fromMessenger: Boolean): Boolean = when {
        pkg in TradeAlertParser.COIN_APPS || pkg in TradeAlertParser.STOCK_APPS -> true
        fromMessenger -> BROKER_WORDS.containsMatchIn(text) && text.contains("체결")
        else -> text.contains("체결") && (text.contains("매수") || text.contains("매도"))
    }

    /** True when [text] was a trade taken for an investor calendar. */
    suspend fun take(c: AppContainer, text: String, pkg: String, app: String, postedAt: Long, fromMessenger: Boolean): Boolean {
        if (!worthReading(pkg, text, fromMessenger)) return false
        val alert = TradeAlertParser.parse(text, pkg) ?: return false
        val investors = c.investCalendars()
        if (investors.isEmpty()) return false
        // [CALS-04] An app the user sent to one calendar keeps going there when that one is an investor calendar.
        val chosen = c.autoRecordPrefs.current.appCalendars[pkg]
        val calendarId = investors.firstOrNull { it.id == chosen }?.id ?: investors.first().id
        val repo = c.invest(calendarId)
        val pending = TradeAlerts.of(alert, app, postedAt)
        if (!repo.addAlert(pending)) return true
        if (repo.alertAutoNow() && pending.price > 0) {
            repo.confirmAlert(pending)
            notice(c, calendarId, "${pending.title} ${pending.side.label} 적었어요", pending, "눌러서 고칠 수 있어요")
        } else {
            notice(c, calendarId, "${pending.title} ${pending.side.label} 체결을 찾았어요", pending, "투자 달력에서 확인하면 적어요")
        }
        return true
    }

    private fun notice(c: AppContainer, calendarId: String, title: String, p: TradeAlerts.Pending, hint: String) {
        val context = c.appContext
        runCatching {
            val n = NotificationCompat.Builder(context, Notifications.INVEST_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText("${Portfolio.quantityText(p.quantity)}${p.market.unit}" +
                    (if (p.price > 0) " × ${Portfolio.quantityText(p.price)}" else "") + " · ${p.app} · $hint")
                .setContentIntent(Notifications.open(context, NoticeLinks.Target.INVEST, REQUEST_OPEN, calendarId))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(60 * 60 * 1000L)
                .build()
            Notifications.post(context, NOTICE_ID, n, "invest-capture:$calendarId")
        }
    }
}
