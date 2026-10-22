package com.bamnat.app.money

import android.content.Context
import com.bamnat.core.money.ShopCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * [DAILY-13] Sales read from notices that wait for 적기 or 버리기 while 확인하고 적기 is on (결정 D7-3), one
 * file per shop calendar. They are not in the backup, like payment lines that wait: only what the user
 * confirmed is a record.
 */
class ShopPendingStore private constructor(private val dir: File) {
    private val flows = HashMap<String, MutableStateFlow<List<ShopCapture.Pending>>>()

    private fun file(calendarId: String) = File(dir, "shop_pending_${calendarId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}.txt")

    @Synchronized
    private fun flowOf(calendarId: String): MutableStateFlow<List<ShopCapture.Pending>> = flows.getOrPut(calendarId) {
        MutableStateFlow(runCatching { file(calendarId).readLines() }.getOrDefault(emptyList()).mapNotNull(ShopCapture.Pending::decode))
    }

    fun pending(calendarId: String): StateFlow<List<ShopCapture.Pending>> = flowOf(calendarId).asStateFlow()

    @Synchronized
    fun add(calendarId: String, p: ShopCapture.Pending) = write(calendarId, ShopCapture.Pending.add(flowOf(calendarId).value, p))

    @Synchronized
    fun remove(calendarId: String, keys: Collection<String>) = write(calendarId, flowOf(calendarId).value.filter { it.key !in keys })

    private fun write(calendarId: String, list: List<ShopCapture.Pending>) {
        flowOf(calendarId).value = list
        runCatching {
            dir.mkdirs()
            if (list.isEmpty()) file(calendarId).delete() else file(calendarId).writeText(list.joinToString("\n") { it.encode() })
        }
    }

    companion object {
        @Volatile private var instance: ShopPendingStore? = null

        fun get(context: Context): ShopPendingStore = instance ?: synchronized(this) {
            instance ?: ShopPendingStore(File(context.applicationContext.noBackupFilesDir, "shop_pending")).also { instance = it }
        }
    }
}
