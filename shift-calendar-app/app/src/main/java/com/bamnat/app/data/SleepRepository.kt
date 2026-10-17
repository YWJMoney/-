package com.bamnat.app.data

import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.SleepLogEntity
import com.bamnat.core.sleep.SleepEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** [SLP-06] The sleep log: two taps, kept on the phone and in the backup file only. */
class SleepRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = db.sleepDao()

    /** The last [days] days of sleep, newest first, plus a sleep still going on. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recent(days: Long = 30): Flow<List<SleepEntry>> =
        dao.observeSince(clock() - days * DAY_MILLIS).map { list -> list.map { it.toModel() } }

    /** "잠들었어요": closes a sleep left open (a missed tap) and starts a new one. */
    suspend fun fellAsleep(): Long {
        val now = clock()
        dao.open()?.let { dao.delete(it.id) }
        return dao.insert(SleepLogEntity(startMillis = now, endMillis = null))
    }

    /** "깼어요": ends the open sleep; null when there was none. */
    suspend fun wokeUp(): Long? {
        val open = dao.open() ?: return null
        dao.finish(open.id, clock())
        return open.id
    }

    suspend fun delete(id: Long) = dao.delete(id)

    private fun SleepLogEntity.toModel() = SleepEntry(id, local(startMillis), endMillis?.let(::local))
    private fun local(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone())

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
