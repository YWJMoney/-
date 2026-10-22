package com.bamnat.core.stats

import com.bamnat.core.diag.DiagEvents

/**
 * The events that "사용 통계 보내기" counts [STAT-01, STAT-03, STAT-04].
 * The server keeps the same list (company-worker `EVENTS`) and a test checks both agree.
 * Only names and daily counts leave the phone: never amounts, memo text, names or a phone id.
 */
enum class UsageEvent(val key: String) {
    OPEN("open"),
    PAY_VIEW("pay_view"),
    PAY_SETUP("pay_setup"),
    PAY_REPORT("pay_report"),
    PAYWALL_VIEW("paywall_view"),
    TRIAL_START("trial_start"),
    SUBSCRIBE("subscribe"),
    PRO_SUB("pro_sub"),
    SHARE_MADE("share_made"),
    SHARE_RECEIVED("share_received"),
    PLAZA_POST("plaza_post"),
    PLAZA_APPLY("plaza_apply"),
    COMPANY_REPORT("company_report"),
    INQUIRY("inquiry"),
    BACKUP("backup"),
    ALARM_RANG("alarm_rang"),
    ICS_EXPORT("ics_export"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun of(key: String): UsageEvent? = byKey[key]

        /**
         * Which counted event an on-device log line is, or null for the many lines that are not counted.
         * New events are logged under their own key; older log names are mapped here.
         */
        fun fromLog(name: String, detail: String): UsageEvent? = when (name) {
            DiagEvents.APP_OPEN -> OPEN
            DiagEvents.SCREEN -> if (detail == "pay") PAY_VIEW else null
            DiagEvents.BACKUP_EXPORT -> BACKUP
            DiagEvents.ICS_EXPORT -> ICS_EXPORT
            DiagEvents.ALARM_FIRED -> ALARM_RANG
            "pay_setup_done" -> PAY_SETUP
            "company_post" -> PLAZA_POST
            "company_apply" -> PLAZA_APPLY
            "share_link_create" -> SHARE_MADE
            "received_add" -> SHARE_RECEIVED
            "plaza_rate" -> if (detail == "applied") PLAZA_APPLY else PLAZA_POST
            else -> byKey[name]
        }
    }
}
