package com.bamnat.core.imports

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * [DAYW-06] A table out of a file a company's 근태 system exports: an Excel workbook (.xlsx, its first sheet)
 * or a CSV in UTF-8 or the Korean Windows encoding (CP949). Read here without any spreadsheet library: an
 * .xlsx is a zip of XML parts, and only the cells' text is needed. Every cell comes back as text; a number
 * Excel keeps for a date or a time stays a number, and [AttendanceImport] reads it by the column it is in.
 */
object Sheets {
    /** Largest file read, and largest part unpacked from a workbook (a zip that unpacks to much more is refused). */
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_PART = 40 * 1024 * 1024
    const val MAX_ROWS = 2000

    class SheetException(message: String) : Exception(message)

    /** The rows of [bytes]: a workbook when it is a zip, a CSV otherwise. */
    fun read(bytes: ByteArray): List<List<String>> {
        if (bytes.size > MAX_BYTES) throw SheetException("파일이 너무 커요")
        return if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) xlsx(bytes) else csv(decode(bytes))
    }

    // ---- CSV ----

    /** Text of a CSV file: UTF-8 (with or without its mark) when it reads as that, CP949 otherwise. */
    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            val korean = listOf("x-windows-949", "MS949", "windows-949", "EUC-KR").firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }
            String(body, korean ?: Charsets.ISO_8859_1)
        }
    }

    /** Rows of CSV text; the separator is whichever of comma, tab and semicolon the first lines use most. */
    fun csv(text: String): List<List<String>> {
        val head = text.lineSequence().take(5).joinToString("\n")
        val sep = listOf(',', '\t', ';').maxByOrNull { c -> head.count { it == c } } ?: ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length && rows.size < MAX_ROWS) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                !quoted && c == sep -> { row += cell.toString().trim(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString().trim(); cell.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString().trim()
        if (row.any { it.isNotEmpty() } && rows.size < MAX_ROWS) rows += row
        return rows
    }

    // ---- XLSX ----

    /** Rows of a workbook's first sheet, with empty cells in their places. */
    fun xlsx(bytes: ByteArray): List<List<String>> {
        val parts = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (name == "xl/sharedStrings.xml" || name == "xl/workbook.xml" || name == "xl/_rels/workbook.xml.rels" ||
                    (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))) {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_PART) throw SheetException("파일이 너무 커요")
                    }
                    parts[name] = out.toString("UTF-8")
                }
            }
        }
        val strings = parts["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        val sheet = parts[firstSheet(parts)] ?: parts.keys.filter { it.startsWith("xl/worksheets/") }.minOrNull()?.let { parts[it] }
            ?: throw SheetException("엑셀 파일에서 시트를 찾지 못했어요")
        return sheetRows(sheet, strings)
    }

    /** The first sheet in the workbook's order, through its relationship id. */
    private fun firstSheet(parts: Map<String, String>): String? {
        val wb = parts["xl/workbook.xml"] ?: return null
        val rid = Regex("""<sheet\b[^>]*\br:id="([^"]+)"""").find(wb)?.groupValues?.get(1) ?: return null
        val rels = parts["xl/_rels/workbook.xml.rels"] ?: return null
        val rel = Regex("""<Relationship\b[^>]*>""").findAll(rels).map { it.value }.firstOrNull { attr(it, "Id") == rid } ?: return null
        val target = attr(rel, "Target") ?: return null
        return if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
    }

    private fun sharedStrings(xml: String): List<String> =
        Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
            // Rich text keeps its runs in several <t>; phonetic hints (<rPh>) are not the text.
            val body = si.groupValues[1].replace(Regex("""<rPh\b.*?</rPh>""", RegexOption.DOT_MATCHES_ALL), "")
            Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).findAll(body).joinToString("") { unescape(it.groupValues[1]) }
        }.toList()

    private fun sheetRows(xml: String, strings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val rowRe = Regex("""<row\b[^>]*?(?:/>|>(.*?)</row>)""", RegexOption.DOT_MATCHES_ALL)
        val cellRe = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
        for (r in rowRe.findAll(xml)) {
            if (rows.size >= MAX_ROWS) break
            val cells = sortedMapOf<Int, String>()
            var next = 0
            for (c in cellRe.findAll(r.groupValues[1])) {
                val attrs = c.groupValues[1]
                val body = c.groupValues[2]
                val col = attr(attrs, "r")?.let(::columnOf) ?: next
                next = col + 1
                val v = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unescape)
                val text = when (attr(attrs, "t")) {
                    "s" -> v?.trim()?.toIntOrNull()?.let { strings.getOrNull(it) }
                    "inlineStr" -> Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).findAll(body).joinToString("") { unescape(it.groupValues[1]) }
                    else -> v
                }.orEmpty().trim()
                if (col < 200) cells[col] = text
            }
            if (cells.values.any { it.isNotEmpty() }) {
                val width = (cells.keys.maxOrNull() ?: -1) + 1
                rows += List(width) { cells[it].orEmpty() }
            }
        }
        return rows
    }

    /** "C12" → 2 (A is 0). */
    internal fun columnOf(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }

    private fun attr(tag: String, name: String): String? = Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    private fun unescape(s: String): String = s
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault("") }
        .replace(Regex("""&#(\d+);""")) { runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault("") }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
