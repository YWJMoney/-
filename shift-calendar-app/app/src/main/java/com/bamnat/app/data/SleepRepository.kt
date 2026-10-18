package com.bamnat.app.data

import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.data.db.SleepLogEntity
import com.bamnat.core.sleep.SleepDebt
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

    /**
     * "잠들었어요": starts a sleep, and returns it. A sleep already open stays as it is (a second tap
     * changes nothing) [SLP-14]; one open for more than 16 hours, a missed "깼어요", is closed where
     * it stands: too long to count, but kept rather than deleted.
     */
    suspend fun fellAsleep(): Asleep {
        val now = clock()
        dao.open()?.let { open ->
            if (now - open.startMillis <= STALE_MILLIS) return Asleep(open.toModel(), started = false)
            dao.finish(open.id, now)
        }
        val id = dao.insert(SleepLogEntity(startMillis = now, endMillis = null))
        return Asleep(SleepEntry(id, local(now), null), started = true)
    }

    /** The open sleep after "잠들었어요", and whether this tap started it. */
    data class Asleep(val entry: SleepEntry, val started: Boolean)

    /**
     * "깼어요": ends the open sleep now, or at [atMillis] when the user says later when they woke
     * [SLP-14]; returns the finished sleep, or null when none was open.
     */
    suspend fun wokeUp(atMillis: Long? = null): SleepEntry? {
        val open = dao.open() ?: return null
        val now = clock()
        val end = (atMillis ?: now).coerceIn(open.startMillis, now)
        dao.finish(open.id, end)
        return SleepEntry(open.id, local(open.startMillis), local(end))
    }

    /** Undoes a "깼어요": the sleep is open again. */
    suspend fun reopen(id: Long) = dao.reopen(id)

    suspend fun delete(id: Long) = dao.delete(id)

    private fun SleepLogEntity.toModel() = SleepEntry(id, local(startMillis), endMillis?.let(::local))
    private fun local(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone())

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val STALE_MILLIS = SleepDebt.MAX_SLEEP_MINUTES * 60 * 1000L
    }
}
