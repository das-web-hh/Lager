package ru.lager.app.ui.win

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream

/** Читает таблицу из .xlsx или .csv без внешних библиотек: список строк, каждая — список ячеек. */
object TableReader {

    class Unsupported(message: String) : Exception(message)

    fun read(ctx: Context, uri: Uri, fileName: String): List<List<String>> {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Unsupported("не удалось открыть файл")
        val isZip = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val lower = fileName.lowercase()
        val rows = when {
            isZip -> readXlsx(bytes)
            lower.endsWith(".xls") && !lower.endsWith(".xlsx") ->
                throw Unsupported("формат .xls не поддерживается — сохраните как .xlsx или .csv")
            else -> readCsv(bytes)
        }
        val width = rows.maxOfOrNull { it.size } ?: 0
        return rows.map { r -> if (r.size < width) r + List(width - r.size) { "" } else r }
    }

    // ---------- CSV ----------

    private fun readCsv(bytes: ByteArray): List<List<String>> {
        var text = bytes.toString(Charsets.UTF_8)
        if (text.contains('\uFFFD')) text = bytes.toString(charset("windows-1251"))
        text = text.removePrefix("\uFEFF")
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delim = listOf(';', ',', '\t').maxByOrNull { d -> firstLine.count { it == d } }
            ?.takeIf { d -> firstLine.contains(d) } ?: ';'
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == delim -> { row.add(cell.toString().trim()); cell.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString().trim()); cell.clear()
                    rows.add(row); row = ArrayList()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString().trim()); rows.add(row) }
        return rows
    }

    // ---------- XLSX ----------

    private fun readXlsx(bytes: ByteArray): List<List<String>> {
        var shared: ByteArray? = null
        val sheets = sortedMapOf<Int, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name
                if (n == "xl/sharedStrings.xml") shared = zip.readBytes()
                else if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) {
                    val num = n.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: 9999
                    sheets[num] = zip.readBytes()
                }
            }
        }
        val sheet = sheets.values.firstOrNull() ?: throw Unsupported("в файле нет листов")
        val strings = shared?.let { parseShared(it) } ?: emptyList()
        return parseSheet(sheet, strings)
    }

    private fun parseShared(data: ByteArray): List<String> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), "UTF-8")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inSi = false
        var inT = false
        var inPhonetic = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; sb.clear() }
                    "rPh" -> inPhonetic = true
                    "t" -> inT = inSi && !inPhonetic
                }
                XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> inPhonetic = false
                    "si" -> { inSi = false; out.add(sb.toString()) }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return n - 1
    }

    private fun number(v: String): String {
        val t = v.trim()
        if (t.isEmpty()) return ""
        return runCatching {
            val d = BigDecimal(t)
            if (d.scale() <= 0 || d.stripTrailingZeros().scale() <= 0) d.toBigInteger().toString() else d.toPlainString()
        }.getOrDefault(t)
    }

    private fun parseSheet(data: ByteArray, strings: List<String>): List<List<String>> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), "UTF-8")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        var col = 0
        var type = ""
        var inV = false
        var inIs = false
        var inT = false
        val v = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = ArrayList()
                    "c" -> {
                        type = p.getAttributeValue(null, "t").orEmpty()
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref != null) colIndex(ref) else row.size
                        v.clear()
                    }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> inT = inIs
                }
                XmlPullParser.TEXT -> if (inV || inT) v.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "is" -> inIs = false
                    "c" -> {
                        val raw = v.toString()
                        val value = when (type) {
                            "s" -> strings.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty()
                            "inlineStr", "str" -> raw
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            else -> number(raw)
                        }
                        while (row.size < col) row.add("")
                        row.add(value.trim())
                    }
                    "row" -> rows.add(row)
                }
            }
            ev = p.next()
        }
        return rows
    }
}
