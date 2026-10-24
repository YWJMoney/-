package com.bamnat.app.review

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview

/**
 * Google Play in-app review [OPS-20]. Asked after the first pattern is saved and the first month-end
 * card ("이번 달 내 기록", CAL-46) has been seen and closed, and once more after the first pay month is
 * closed [DAILY-17]. Play limits how often the sheet
 * really shows and never says whether it did, so each moment asks once and never again; any failure
 * is silent (e.g. not installed from Play). Nothing is asked before or around the sheet.
 */
object InAppReview {
    suspend fun ask(activity: Activity) {
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            val info = manager.requestReview()
            manager.launchReview(activity, info)
        }
    }

    /** [DAILY-17] Which moment is due now: the first (pattern saved) or the second (a pay month closed). */
    enum class Moment { FIRST, PAY }

    /** Days kept between the two asks, so Play's own quota (about a month) does not swallow the second. */
    const val GAP_DAYS = 30

    /**
     * [DAILY-17] The moment to ask at on 내 달력, or null. [SETUP-14] The first waits for a month of use: the
     * pattern saved and the first month-end card closed (G P23 ↔ I N9), not the minute setup ends. The second comes only after the user closed a pay
     * month (a finished job, not a button that asks), at most once, and [GAP_DAYS] after the first; a first
     * asked before 1.22 kept no day, so it counts as long ago.
     */
    fun due(s: com.bamnat.app.data.AppSettings, today: java.time.LocalDate): Moment? = when {
        s.reviewDue && !s.reviewAsked && s.monthRecapSeen != 0 -> Moment.FIRST
        s.payReviewDue && !s.payReviewAsked &&
            (s.reviewAskedDay == 0L || today.toEpochDay() - s.reviewAskedDay >= GAP_DAYS) -> Moment.PAY
        else -> null
    }

    fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
