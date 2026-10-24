package com.bamnat.core.remote

/** [REMOTE-01] A remote file that does not have the expected shape; the app keeps what it had. */
class RemoteFileException(message: String) : Exception(message)

/**
 * [REMOTE-01] A strict JSON reader for the remote files (core has no JSON library and the Android one is
 * not on the JVM). Objects become [Map] in file order, arrays [List], whole numbers [Long], other numbers
 * [Double]. Anything outside RFC 8259 (comments, trailing commas, a second value) is an error.
 */
object Json {
    fun parse(text: String): Any? {
        val r = Reader(text)
        r.space()
        val v = r.value(0)
        r.space()
        if (r.i != text.length) r.fail("extra text")
        return v
    }

    private class Reader(val s: String) {
        var i = 0

        fun fail(what: String): Nothing = throw RemoteFileException("JSON $what at $i")

        fun space() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(depth: Int): Any? {
            if (depth > 32) fail("too deep")
            if (i >= s.length) fail("end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("value")
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("word")
            i += w.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            space()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                space()
                if (i >= s.length || s[i] != '"') fail("key")
                val k = str()
                if (k in m) fail("same key")
                space()
                if (i >= s.length || s[i] != ':') fail(":")
                i++
                space()
                m[k] = value(depth + 1)
                space()
                when (if (i < s.length) s[i] else ' ') {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> fail("object")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            space()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                space()
                l += value(depth + 1)
                space()
                when (if (i < s.length) s[i] else ' ') {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> fail("array")
                }
            }
        }

        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail("string end")
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u")
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("\\u")
                                b.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("escape")
                        }
                    }
                    c < ' ' -> fail("control character")
                    else -> b.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("number")
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') while (i < s.length && s[i] in '0'..'9') i++ else fail("number")
            var whole = true
            if (i < s.length && s[i] == '.') {
                whole = false
                i++
                if (i >= s.length || s[i] !in '0'..'9') fail("number")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                whole = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') fail("number")
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(start, i)
            return if (whole) t.toLongOrNull() ?: fail("number size") else t.toDouble()
        }
    }
}

/** [REMOTE-01] Typed reads from a parsed object; a missing or wrong-typed required field is an error. */
@Suppress("UNCHECKED_CAST")
class JsonObj(val map: Map<String, Any?>, private val where: String = "") {
    private fun bad(k: String): Nothing = throw RemoteFileException("$where$k")

    fun has(k: String) = map[k] != null
    fun str(k: String): String = map[k] as? String ?: bad(k)
    fun strOr(k: String, d: String): String = if (map[k] == null) d else str(k)
    fun strOrNull(k: String): String? = if (map[k] == null) null else str(k)
    fun long(k: String): Long = map[k] as? Long ?: bad(k)
    fun longOr(k: String, d: Long): Long = if (map[k] == null) d else long(k)
    fun int(k: String): Int = long(k).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else bad(k) }
    fun intOr(k: String, d: Int): Int = if (map[k] == null) d else int(k)
    fun intOrNull(k: String): Int? = if (map[k] == null) null else int(k)
    fun double(k: String): Double = when (val v = map[k]) { is Long -> v.toDouble(); is Double -> v; else -> bad(k) }
    fun doubleOrNull(k: String): Double? = if (map[k] == null) null else double(k)
    fun bool(k: String): Boolean = map[k] as? Boolean ?: bad(k)
    fun boolOr(k: String, d: Boolean): Boolean = if (map[k] == null) d else bool(k)
    fun obj(k: String): JsonObj = JsonObj(map[k] as? Map<String, Any?> ?: bad(k), "$where$k.")
    fun list(k: String): List<Any?> = map[k] as? List<Any?> ?: bad(k)
    fun listOrEmpty(k: String): List<Any?> = if (map[k] == null) emptyList() else list(k)
    fun objs(k: String): List<JsonObj> = listOrEmpty(k).mapIndexed { n, v ->
        JsonObj(v as? Map<String, Any?> ?: bad("$k[$n]"), "$where$k[$n].")
    }
    fun strings(k: String): List<String> = listOrEmpty(k).map { it as? String ?: bad(k) }
}
