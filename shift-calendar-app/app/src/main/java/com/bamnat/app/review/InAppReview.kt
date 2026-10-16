package com.bamnat.app.review

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview

/**
 * Google Play in-app review [OPS-20]. Asked once, after the first pattern is saved and the
 * calendar fills in. Play limits how often the sheet really shows and never says whether it
 * did, so the app asks once and never again; any failure is silent (e.g. not installed from Play).
 */
object InAppReview {
    suspend fun ask(activity: Activity) {
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            val info = manager.requestReview()
            manager.launchReview(activity, info)
        }
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
