package com.bamnat.app.company

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.bamnat.app.AppContainer
import com.bamnat.app.appContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * [SRV-07][INQ-05][SHR-13] What could not reach a server goes as soon as the phone is online, even with
 * the app closed: the free trial's answer ([com.bamnat.app.tier.ProStore.pendingConsent]), questions in
 * [InquiryStore.outbox] and calendar links with a change they have not got. A one-off job, scheduled
 * only while something waits, so no job runs at start; what is still left is tried again later, each
 * time a little later (the system's exponential backoff from [BACKOFF]).
 */
class OutboxJob : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val c = applicationContext.appContainer
        running = c.appScope.launch {
            val done = runCatching { send(c) }.getOrDefault(false)
            jobFinished(params, !done)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    companion object {
        private const val ID = 4403
        private val BACKOFF = TimeUnit.MINUTES.toMillis(15)

        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            if (jobs.getPendingJob(ID) != null) return
            jobs.schedule(
                JobInfo.Builder(ID, ComponentName(context, OutboxJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(BACKOFF, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build(),
            )
        }

        /** How many things wait to be sent, for 서버 연결 점검. */
        fun waiting(c: AppContainer): Int =
            (if (c.proStore.current.pendingConsent != null) 1 else 0) + c.inquiryStore.outbox.value.size +
                c.calendars.calendars.value.filterNot { it.isExample }.flatMap { com.bamnat.app.share.ShareLinkKey.all(it.id) }.count { c.shareLinksOf(it).unsent.value }

        /** Sends everything waiting; true when nothing is left. */
        suspend fun send(c: AppContainer): Boolean {
            val consent = c.sendConsent()
            val questions = InquiryOutbox.flush(c)
            val links = if (c.shareApi.enabled) c.shareSync.sendUnsent() else true
            return consent && questions && links
        }
    }
}

/**
 * [INQ-05] I9 보내기 대기: a question that could not go (no internet, the server busy) waits on the phone
 * with the request id it was first sent with, so sending it again never makes a second question.
 * Photos do not wait; the form says so.
 */
object InquiryOutbox {
    /** Sends what waits, oldest first; true when nothing is left. */
    suspend fun flush(c: AppContainer): Boolean {
        if (!c.inquiryApi.enabled) return c.inquiryStore.outbox.value.isEmpty()
        for (q in c.inquiryStore.outbox.value) {
            try {
                c.inquiryApi.send(c.companyStore.device, q.kind, q.text, q.info, q.requestId)
                c.inquiryStore.asked = true
                c.inquiryStore.unqueue(q.requestId)
                c.telemetry.event(com.bamnat.core.stats.UsageEvent.INQUIRY.key)
            } catch (e: CompanyException) {
                if (e.retryable) return false
                // Refused for what it says (a phone number, too long): it would be refused again, so it stays for the user to fix.
                c.inquiryStore.refused(q.requestId, e.code)
            }
        }
        return true
    }
}
