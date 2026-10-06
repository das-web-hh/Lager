package ru.lager.app.ui.win

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Экспорт (xlsx/json), отправка, сохранение в «Загрузки» и печать. */
object ExportHelper {
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val JSON = "application/json"

    private fun esc(s: String) = buildString {
        s.forEach {
            when {
                it == '&' -> append("&amp;")
                it == '<' -> append("&lt;")
                it == '>' -> append("&gt;")
                it == '"' -> append("&quot;")
                it.code < 32 && it != '\n' && it != '\t' -> {}
                else -> append(it)
            }
        }
    }

    private fun colName(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    private fun sheetName(raw: String, used: Set<String>): String {
        var n = raw.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31).ifEmpty { "Лист" }
        var k = 2
        while (n in used) n = n.take(28) + " " + k++
        return n
    }

    /** Простой .xlsx без внешних библиотек. Целые числа пишутся числами, остальное — текстом. */
    fun xlsx(sheets: List<Pair<String, List<List<String>>>>): ByteArray {
        val out = ByteArrayOutputStream()
        val names = ArrayList<String>()
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            sheets.forEach { names.add(sheetName(it.first, names.toSet())) }
            put(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                    sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } +
                    "</Types>",
            )
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            )
            put(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                    names.mapIndexed { i, n -> """<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") +
                    "</sheets></workbook>",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                    "</Relationships>",
            )
            sheets.forEachIndexed { si, (_, rows) ->
                val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="1" width="40" customWidth="1"/><col min="2" max="8" width="18" customWidth="1"/></cols><sheetData>""")
                rows.forEachIndexed { r, row ->
                    sb.append("""<row r="${r + 1}">""")
                    row.forEachIndexed { c, v ->
                        val ref = colName(c) + (r + 1)
                        if (r > 0 && v.length in 1..9 && v.all { it.isDigit() } && !(v.length > 1 && v.startsWith("0"))) {
                            sb.append("""<c r="$ref"><v>$v</v></c>""")
                        } else if (v.isNotEmpty()) {
                            sb.append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>""")
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
            }
        }
        return out.toByteArray()
    }

    private fun findActivity(ctx: Context): android.app.Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
        return null
    }

    private fun cacheUri(ctx: Context, name: String, bytes: ByteArray): Uri {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }

    /** Системное окно «Поделиться» (WhatsApp, Telegram, почта…). */
    fun share(ctx: Context, name: String, bytes: ByteArray, mime: String): Boolean = runCatching {
        val uri = cacheUri(ctx, name, bytes)
        val send = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** Сохраняет в «Загрузки». Android 10+ — без разрешений; на старых версиях возвращает false. */
    fun saveToDownloads(ctx: Context, name: String, bytes: ByteArray, mime: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            true
        }.getOrDefault(false)
    }

    fun stamp(): String = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())

    /** Печать таблицы через системный диалог печати (можно сохранить как PDF). */
    fun printTable(ctx: Context, title: String, header: List<String>, rows: List<List<String>>, subtitle: String = ""): Boolean {
        val act = findActivity(ctx) ?: return false
        val html = buildString {
            append("<html><head><meta charset='utf-8'><style>body{font-family:sans-serif;font-size:12px}h2{margin:0 0 4px}")
            append("table{border-collapse:collapse;width:100%}td,th{border:1px solid #888;padding:4px 6px;text-align:left}th{background:#eee}")
            append(".n{text-align:right;white-space:nowrap}</style></head><body><h2>${esc(title)}</h2>")
            if (subtitle.isNotEmpty()) append("<div>${esc(subtitle)}</div><br>")
            append("<table><tr>${header.joinToString("") { "<th>${esc(it)}</th>" }}</tr>")
            rows.forEach { r ->
                append("<tr>${r.joinToString("") { v -> "<td${if (v.isNotEmpty() && v.all { it.isDigit() || it == '-' }) " class='n'" else ""}>${esc(v)}</td>" }}</tr>")
            }
            append("</table></body></html>")
        }
        return runCatching {
            val web = WebView(act)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val pm = act.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    pm.print(title, view.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
                }
            }
            web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            true
        }.getOrDefault(false)
    }
}
