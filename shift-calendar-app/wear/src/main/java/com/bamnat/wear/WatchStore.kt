package com.bamnat.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.bamnat.core.work.WatchDays
import com.bamnat.core.work.WatchPayload
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import com.bamnat.core.time.Today

/**
 * [SHFT-08][REL-09] The last days the phone sent, kept on the watch so the tile, the complication and the app
 * work away from the phone (and with no internet at all). A newer copy from the phone replaces it.
 */
object WatchStore {
    private const val PREFS = "days"
    private const val KEY = "payload"
    private val _payload = MutableStateFlow<WatchPayload?>(null)
    val payload: StateFlow<WatchPayload?> = _payload.asStateFlow()

    fun load(context: Context): WatchPayload? {
        val p = _payload.value ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let(WatchDays::decode)
        _payload.value = p
        return p
    }

    /** Keeps [text] when it reads and is not older than what is there; tells the tile and complication. */
    fun save(context: Context, text: String) {
        val next = WatchDays.decode(text) ?: return
        val now = load(context)
        // [SHFT-08] Compared by when it was sent (epoch millis), not the phone's local clock.
        if (!WatchDays.replaces(next, now)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text).apply()
        _payload.value = next
        changed(context)
    }

    /** Asks the tile and the complication to draw again, and wakes them at the next midnight (the complication also has its timeline). */
    fun changed(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(ShiftTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, TodayComplicationService::class.java)).requestUpdateAll()
        }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, WeekHoursComplicationService::class.java)).requestUpdateAll()
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = Today.zone()
        val midnight = Today.date(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 5_000
        // [SHFT-08] Still rings in Doze, so the tile does not show yesterday until the watch is picked up.
        am.setAndAllowWhileIdle(AlarmManager.RTC, midnight, PendingIntent.getBroadcast(context, 1,
            Intent(context, MidnightReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    /** Reads the Data Layer's copy (there even when the phone is away) and asks the phone for fresh days. */
    suspend fun refresh(context: Context) {
        val client = Wearable.getDataClient(context)
        val items = await(client.getDataItems(Uri.Builder().scheme("wear").path(WatchDays.PATH).build()))
        if (items != null) {
            try {
                items.forEach { item -> DataMapItem.fromDataItem(item).dataMap.getString(WatchDays.KEY)?.let { save(context, it) } }
            } finally {
                items.release()
            }
        }
        val nodes = await(Wearable.getNodeClient(context).connectedNodes).orEmpty()
        nodes.forEach { node -> await(Wearable.getMessageClient(context).sendMessage(node.id, SYNC_PATH, ByteArray(0))) }
    }

    /** [WEAR-03] Tells the phone to do something (turn today's alarm off, write 출근·퇴근); it sends fresh days back. */
    suspend fun send(context: Context, path: String, text: String): Boolean {
        val nodes = await(Wearable.getNodeClient(context).connectedNodes).orEmpty()
        var sent = false
        nodes.forEach { node -> if (await(Wearable.getMessageClient(context).sendMessage(node.id, path, text.toByteArray())) != null) sent = true }
        return sent
    }

    private suspend fun <T> await(task: Task<T>): T? = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
    }

    const val SYNC_PATH = "/bamnat/sync"
}

/** [SHFT-08] The phone's new days arriving through the Data Layer. */
class DaysListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == WatchDays.PATH) {
                DataMapItem.fromDataItem(e.dataItem).dataMap.getString(WatchDays.KEY)?.let { WatchStore.save(applicationContext, it) }
            }
        }
    }
}

/** [SHFT-08] A new day: the tile and complication show it. */
class MidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = WatchStore.changed(context)
}

/**
 * [SHFT-08] A restart drops the midnight alarm, and a new time or time zone moves midnight: draw again and set
 * it anew.
 */
class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in ACTIONS) WatchStore.changed(context)
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)
    }
}
