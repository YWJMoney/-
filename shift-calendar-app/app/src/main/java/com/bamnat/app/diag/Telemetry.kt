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
        log.append(name, detail)
        if (sinks.isNotEmpty() && settings.current.shareUsageStats) {
            for (sink in sinks) runCatching { sink.send(name, detail) }
        }
    }

    /** True when there is somewhere to send usage statistics to. */
    val hasRemote: Boolean get() = sinks.isNotEmpty()
}
