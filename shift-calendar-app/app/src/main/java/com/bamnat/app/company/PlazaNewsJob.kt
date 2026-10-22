package com.bamnat.app.company

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationCompat
import com.bamnat.app.AppContainer
import com.bamnat.app.BuildConfig
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.core.ops.ServerSwitch
import com.bamnat.core.plaza.PlazaNews
import com.bamnat.core.plaza.PlazaNewsItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * [COMM-10] 광장 소식: once a day, what happened to the user's own plaza things (a rule they use has a
 * new version, their report, proposal or 임금표 entry was adopted), in one quiet notice. Only a phone
 * that took part in the plaza asks; on by default, off on 내 활동 or by the developer's switch.
 */
class PlazaNewsJob : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val c = applicationContext.appContainer
        running = c.appScope.launch {
            try {
                runCatching { check(c) }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    companion object {
        private const val ID = 4402
        private val EVERY = TimeUnit.HOURS.toMillis(24)
        private const val PREFS = "plaza_news"
        private const val K_SINCE = "since"

        /** Whether this phone has anything in the plaza to hear about. */
        fun takesPart(c: AppContainer): Boolean =
            c.companyStore.sent.value.isNotEmpty() || c.companyStore.applied.value != null || c.settingsRepository.current.plazaRulesAgreed > 0

        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            if (jobs.getPendingJob(ID) != null) return
            jobs.schedule(
                JobInfo.Builder(ID, ComponentName(context, PlazaNewsJob::class.java))
                    .setPeriodic(EVERY, EVERY / 4)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(ID)
        }

        /** Asks the server for news since the last ask and posts one notice when there is any. */
        suspend fun check(c: AppContainer) {
            val on = c.settingsRepository.current.plazaNews && !c.remoteConfig.current.paused(ServerSwitch.PLAZA_NEWS)
            if (!on || !takesPart(c) || BuildConfig.COMPANY_BASE_URL.isBlank()) return
            val prefs = c.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val since = prefs.getString(K_SINCE, null) ?: Instant.now().minusSeconds(86_400).toString()
            val o = serverCall {
                serverRequest(BuildConfig.COMPANY_BASE_URL, "GET", "/api/plaza/news?since=" + URLEncoder.encode(since, "UTF-8"), device = c.companyStore.device)
            }
            val items = o.optJSONArray("items").objects().map { i ->
                PlazaNewsItem(
                    kind = i.optString("kind"), company = i.optJSONObject("company")?.optString("name"), dept = i.optStringOrNull("dept"),
                    version = if (i.has("version") && !i.isNull("version")) i.optInt("version") else null, trade = i.optStringOrNull("trade"),
                    atMillis = runCatching { Instant.parse(i.optString("at")).toEpochMilli() }.getOrDefault(0L),
                )
            }
            o.optStringOrNull("now")?.let { prefs.edit().putString(K_SINCE, it).apply() }
            val text = PlazaNews.text(items) ?: return
            val n = NotificationCompat.Builder(c.appContext, Notifications.PLAZA_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text.title)
                .setContentText(text.lines.first())
                .setStyle(NotificationCompat.InboxStyle().also { s -> text.lines.forEach(s::addLine) })
                .setContentIntent(Notifications.open(c.appContext, NoticeLinks.Target.PLAZA, 2203))
                .setAutoCancel(true)
                .build()
            Notifications.post(c.appContext, Notifications.PLAZA_ID, n)
        }
    }
}
