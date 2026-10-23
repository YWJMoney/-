package com.bamnat.app.company

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import com.bamnat.app.AppContainer
import com.bamnat.app.BuildConfig
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.app.share.ReceivedChanges
import com.bamnat.app.share.ShareLinkException
import com.bamnat.app.share.SharedSchedule
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.ops.ServerSwitch
import com.bamnat.core.plaza.BamnatNews
import com.bamnat.core.plaza.PlazaNewsItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * [COMM-10] 광장 소식, widened in 1.23 to 밤낮 소식 [SRV-10]: once a day, one quiet notice with what
 * happened to the user's own things. One ask to `GET /api/digest` brings the plaza news (rules, reports,
 * proposals, 임금표, and what became of 신고 [COMM-11]), new answers to the user's questions [INQ-05] and the
 * notices that began. Received schedules are looked at for changes in the next seven days [SHR-13], a
 * phone calendar that lost its permission is said once [ALRM-01], and the job leaves a line that the
 * phone was alive. No new job: the one 광장 소식 had. Off on 내 활동 (밤낮 소식 알림), or by the developer's switch
 * for the plaza part.
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
        private const val K_CALENDAR_SAID = "calendar_permission_said"
        /** [SRV-10] 2: runs without a network too (the alive line and the calendar check need none). */
        private const val VERSION = 2

        /** Whether this phone has anything in the plaza to hear about. */
        fun takesPart(c: AppContainer): Boolean =
            c.companyStore.sent.value.isNotEmpty() || c.companyStore.applied.value != null || c.settingsRepository.current.plazaRulesAgreed > 0

        /** [SRV-10] Whether the daily job has anything to do on this phone. */
        fun wanted(c: AppContainer): Boolean {
            val s = c.settingsRepository.current
            return (s.plazaNews && (takesPart(c) || c.inquiryStore.asked || c.received.calendars.value.isNotEmpty())) ||
                s.phoneCalendars.isNotEmpty() || s.alarmsEnabled
        }

        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            if (jobs.getPendingJob(ID)?.extras?.getInt("v") == VERSION) return
            jobs.schedule(
                JobInfo.Builder(ID, ComponentName(context, PlazaNewsJob::class.java))
                    .setPeriodic(EVERY, EVERY / 4)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .setExtras(PersistableBundle().apply { putInt("v", VERSION) })
                    .build(),
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(ID)
        }

        /** Everything the daily job does; one notice at most for the news, one for the calendar permission. */
        suspend fun check(c: AppContainer) {
            c.telemetry.event(DiagEvents.ALIVE)
            calendarPermission(c)
            if (!c.settingsRepository.current.plazaNews) return
            val changes = runCatching { receivedChanges(c) }.getOrDefault(emptyList())
            val digest = runCatching { digest(c) }.getOrNull()
            val text = BamnatNews.text(digest?.plaza.orEmpty(), digest?.answers ?: 0, digest?.notices.orEmpty(), changes) ?: return
            val target = when {
                text.onlyAnswers -> NoticeLinks.Target.INQUIRY
                digest?.plaza.isNullOrEmpty() && changes.isNotEmpty() -> NoticeLinks.Target.TODAY
                digest?.plaza.isNullOrEmpty() && (digest?.answers ?: 0) > 0 -> NoticeLinks.Target.INQUIRY
                else -> NoticeLinks.Target.PLAZA
            }
            val n = NotificationCompat.Builder(c.appContext, Notifications.PLAZA_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text.title)
                .setContentText(text.lines.first())
                .setStyle(NotificationCompat.InboxStyle().also { s -> text.lines.forEach(s::addLine) })
                .setContentIntent(Notifications.open(c.appContext, target, 2203))
                .setAutoCancel(true)
                .build()
            Notifications.post(c.appContext, Notifications.PLAZA_ID, n)
            // [OBS-01] 기록 지점 10.
            c.journal.note(com.bamnat.core.journal.JournalKind.NEWS)
        }

        /** What came from the server: the plaza part (empty when it is not asked), answers and notice titles. */
        data class Digest(val plaza: List<PlazaNewsItem>, val answers: Int, val notices: List<String>)

        /**
         * [SRV-10] One ask for the day. Only a phone that took part in the plaza or sent a question asks;
         * [R12] the first ask carries no `since` and the server answers for the last day.
         */
        suspend fun digest(c: AppContainer): Digest? {
            if (BuildConfig.COMPANY_BASE_URL.isBlank()) return null
            val plazaOn = takesPart(c) && !c.remoteConfig.current.paused(ServerSwitch.PLAZA_NEWS)
            if (!plazaOn && !c.inquiryStore.asked) return null
            val prefs = c.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val since = prefs.getString(K_SINCE, null)
            val o = serverCall {
                serverRequest(BuildConfig.COMPANY_BASE_URL, "GET", "/api/digest" + (since?.let { "?since=" + URLEncoder.encode(it, "UTF-8") } ?: ""),
                    device = c.companyStore.device)
            }
            o.optStringOrNull("now")?.let { prefs.edit().putString(K_SINCE, it).apply() }
            val answers = o.optJSONArray("answers").objects().sumOf { it.optInt("count") }
            // The 설정 tab's badge is right the next time the app opens.
            if (answers > 0) runCatching { c.inquiryStore.update(c.inquiryApi.mine(c.companyStore.device)) }
            return Digest(
                plaza = if (plazaOn) o.optJSONArray("plaza").objects().map(::item) else emptyList(),
                answers = if (c.inquiryStore.asked) answers else 0,
                notices = o.optJSONArray("notices").objects().map { it.optString("title") }.filter { it.isNotBlank() },
            )
        }

        internal fun item(i: JSONObject) = PlazaNewsItem(
            kind = i.optString("kind"), company = i.optJSONObject("company")?.optString("name"), dept = i.optStringOrNull("dept"),
            version = if (i.has("version") && !i.isNull("version")) i.optInt("version") else null, trade = i.optStringOrNull("trade"),
            atMillis = runCatching { Instant.parse(i.optString("at")).toEpochMilli() }.getOrDefault(0L),
            result = i.optStringOrNull("result"), post = i.optStringOrNull("post"),
        )

        /**
         * [SHR-13] Each received schedule asked with its tag, so only one that changed downloads; a change
         * in its next seven days becomes a line ("민지 10/25 주간 → 야간").
         */
        suspend fun receivedChanges(c: AppContainer, today: LocalDate = LocalDate.now()): List<String> {
            if (!c.shareApi.enabled) return emptyList()
            c.received.expire()
            val lines = mutableListOf<String>()
            for (cal in c.received.calendars.value.filter { !it.gone }) {
                val got = try {
                    c.shareApi.readIfChanged(cal.id, cal.etag)
                } catch (e: ShareLinkException) {
                    if (e.message == "not_found") c.received.markGone(cal.id, e.removedReason)
                    null
                } catch (e: java.io.IOException) {
                    null
                } ?: continue
                val old = cal.schedule
                c.received.update(cal.id, got)
                val new = runCatching { SharedSchedule.parse(got.schedule) }.getOrNull() ?: continue
                val changed = ReceivedChanges.nextDays(old, new, today)
                if (changed.isNotEmpty()) lines += "${cal.name} " + changed.take(2).joinToString(", ") +
                    (if (changed.size > 2) " 외 ${changed.size - 2}일" else "")
            }
            return lines
        }

        /** [ALRM-01] R9: 휴대폰 캘린더에 쓰기 is on but the permission was taken back: said once, until it is given again. */
        fun calendarPermission(c: AppContainer) {
            val prefs = c.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val on = c.settingsRepository.current.phoneCalendars.isNotEmpty()
            val allowed = com.bamnat.app.calendar.PhoneCalendar(c.appContext).allowed()
            if (!on || allowed) {
                if (prefs.getBoolean(K_CALENDAR_SAID, false)) prefs.edit().putBoolean(K_CALENDAR_SAID, false).apply()
                return
            }
            if (prefs.getBoolean(K_CALENDAR_SAID, false)) return
            prefs.edit().putBoolean(K_CALENDAR_SAID, true).apply()
            val n = NotificationCompat.Builder(c.appContext, Notifications.DAY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("휴대폰 캘린더에 근무를 쓰지 못하고 있어요")
                .setContentText("캘린더 권한이 꺼졌어요. 휴대폰 캘린더의 근무가 옛것일 수 있어요")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "캘린더 권한이 꺼져서 휴대폰 캘린더(삼성 캘린더 등)의 밤낮 근무를 고치지 못해요. 그곳의 근무는 옛것일 수 있어요. " +
                        "눌러서 설정 › 데이터에서 다시 켜 주세요."))
                .setContentIntent(Notifications.open(c.appContext, NoticeLinks.Target.PHONE_CALENDAR, 2204))
                .setAutoCancel(true)
                .build()
            Notifications.post(c.appContext, Notifications.CALENDAR_PERMISSION_ID, n)
        }
    }
}
