package com.bamnat.app.diag

import com.bamnat.app.data.SettingsRepository

/** Where usage events go besides the on-device log, e.g. Firebase Analytics once it is set up [MEAS-01]. */
fun interface TelemetrySink {
    fun send(name: String, detail: String)
}

/**
 * The one entry point for usage and reliability events [MEAS-03].
 * Every event is written to the on-device [EventLog], which feeds the diagnostics screen.
 * Remote sinks only receive events when the user turned on "사용 통계 보내기".
 */
class Telemetry(
    val log: EventLog,
    private val settings: SettingsRepository,
    private val sinks: List<TelemetrySink> = emptyList(),
) {
    fun event(name: String, detail: String = "") {
        @Suppress("NAME_SHADOWING") val detail = cleanDetail(detail)
        log.append(name, detail)
        if (sinks.isNotEmpty() && settings.current.shareUsageStats) {
            for (sink in sinks) runCatching { sink.send(name, detail) }
        }
    }

    /** True when there is somewhere to send usage statistics to. */
    val hasRemote: Boolean get() = sinks.isNotEmpty()

    companion object {
        /** Codes, numbers, versions and times: what an event's detail is meant to hold. */
        private val CODE = Regex("[A-Za-z0-9_=,.:;/+#\\- ]{0,160}")

        /**
         * [MEAS-08] 검토 F3: an event's detail keeps only codes and numbers. Anything else (a name of a shift set,
         * a template's title, an error text) becomes its length, so the log, the diagnostics copy and the
         * statistics never carry words a person wrote or a company's name.
         */
        fun cleanDetail(detail: String): String = if (CODE.matches(detail)) detail else "text${detail.length}"
    }
}
