package com.bamnat.core.remote

/**
 * [REMOTE-07] 제조사별 알람 설정 안내 as a remote file (`remote/v1/alarm-guide.json`): the steps that keep an alarm
 * ringing on a maker's battery settings change with each OS update, so they come as data, not code. K made the frame;
 * the words are the L 묶음's (통합 경유). The built-in file has no guides yet, and the app keeps its own text then.
 *
 * ```
 * {"guides": [{"maker": "samsung", "title": "...", "steps": ["...", "..."], "link": "https://..."}]}
 * ```
 */
object AlarmGuides {
    data class Guide(val maker: String, val title: String, val steps: List<String>, val link: String? = null)

    const val BUILT_IN_VERSION = 2026102301L

    @Volatile private var remote: List<Guide>? = null

    private val builtIn: List<Guide> by lazy { RemoteFiles.bundled(RemoteFiles.ALARM_GUIDE)?.let { runCatching { read(it) }.getOrNull() } ?: emptyList() }

    val guides: List<Guide> get() = remote ?: builtIn

    /** The guide for [maker] (Build.MANUFACTURER, any case), or null to use the app's own words. */
    fun forMaker(maker: String): Guide? = guides.firstOrNull { it.maker.equals(maker.trim(), ignoreCase = true) }

    fun use(file: RemoteFile?) {
        remote = file?.takeIf { it.version > BUILT_IN_VERSION }?.let(::read)
    }

    /** Reads strictly: a maker, a title, 1~12 steps of plain text, and only an https link. */
    fun read(f: RemoteFile): List<Guide> {
        fun bad(why: String): Nothing = throw RemoteFileException(why)
        if (f.kind != RemoteFiles.ALARM_GUIDE) bad("kind ${f.kind}")
        return f.data.objs("guides").map { o ->
            val steps = o.list("steps").map { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() && s.length <= 200 } ?: bad("step") }
            if (steps.isEmpty() || steps.size > 12) bad("steps")
            val link = o.strOr("link", "").ifBlank { null }?.also { if (!it.startsWith("https://")) bad("link") }
            Guide(o.str("maker").trim().lowercase().ifEmpty { bad("maker") }, o.str("title").trim().take(60).ifEmpty { bad("title") }, steps, link)
        }
    }
}
