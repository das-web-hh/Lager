package ru.lager.app.ui.win

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Предпроверка заказа до Gemini (HTML: _autoReceivePrecheckOrder).
 * Python-сервер читает номер EB из текстового слоя PDF (POST /extract-order, без Gemini и без OCR).
 * Если такой заказ уже есть в истории приёмок, файл на ИИ не отправляется, а прикрепляется к заказу.
 * Сканы и фото без текстового слоя номера не дают: они идут на Gemini как обычно.
 * Любая ошибка предпроверки не блокирует приём, возвращается пустая строка.
 */
internal object OrderPrecheck {
    private const val TIMEOUT_MS = 90_000

    /** Адрес сервера из окна «Автоприём» (поле «Адрес Python-сервера»). Пусто, если не задан. */
    fun serverUrl(ctx: Context): String {
        val v = ctx.getSharedPreferences("lager_auto_receive", Context.MODE_PRIVATE)
            .getString("url", "").orEmpty().trim().trimEnd('/')
        return if ((v.startsWith("http://") || v.startsWith("https://")) && v.length > 8) v else ""
    }

    /** _normalizeOrderNumber: нижний регистр, без пробелов, «-», «_», «/», «\». */
    fun normalize(order: String): String =
        order.trim().lowercase().replace(Regex("[\\s\\-_/\\\\]"), "")

    /** Заглушка «нет номера», которую возвращают Gemini и сервер. */
    fun isNoData(order: String): Boolean =
        Regex("^eb[\\s_-]*no[\\s_-]*data$", RegexOption.IGNORE_CASE).matches(order.trim())

    suspend fun extract(ctx: Context, uri: Uri, name: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val base = serverUrl(ctx)
            if (base.isEmpty()) return@runCatching ""
            // Текстового слоя нет у фото, сервер всё равно ответил бы без номера: не гоним их зря.
            if (!name.lowercase().endsWith(".pdf")) return@runCatching ""
            val bytes = ctx.applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching ""

            val boundary = "----lager" + System.currentTimeMillis().toString(16)
            val safeName = name.replace("\"", "_").ifEmpty { "invoice.pdf" }
            val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
                "Content-Type: application/pdf\r\n\r\n"
            val tail = "\r\n--$boundary--\r\n"

            val conn = URL("$base/extract-order").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.setRequestProperty("Accept", "application/json")
                conn.outputStream.use {
                    it.write(head.toByteArray()); it.write(bytes); it.write(tail.toByteArray())
                }
                if (conn.responseCode !in 200..299) return@runCatching ""
                val payload = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                if (payload.optString("status") != "success") return@runCatching ""
                val order = payload.optString("orderNumber").trim()
                if (isNoData(order)) "" else order
            } finally {
                conn.disconnect()
            }
        }.getOrDefault("")
    }
}
