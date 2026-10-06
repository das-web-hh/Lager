package ru.lager.app.ui.win

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Ошибка запроса к Gemini. status: HTTP-код, -1 — таймаут, -2 — нет сети. */
class GeminiException(message: String, val status: Int = 0, val retryAfterMs: Long = 0L) : Exception(message)

sealed class GeminiPart {
    data class Text(val text: String) : GeminiPart()
    data class Inline(val mime: String, val base64: String) : GeminiPart()
}

class GeminiReply(val text: String, val input: Long, val output: Long, val total: Long)

class GeminiTotals(val input: Long, val output: Long, val total: Long, val limit: Long)

/**
 * Запросы к Gemini (аналог _requestGeminiChat / recognizeHeader в HTML):
 *  - ключи берутся по кругу, ключ с исчерпанным дневным лимитом пропускается;
 *  - между отправками выдерживается пауза из настроек;
 *  - 400/401/403/404/429 не повторяются, сеть/таймаут/5xx — повторяются.
 */
object GeminiClient {
    val MODELS = listOf("gemini-3.6-flash", "gemini-2.5-pro")
    private val TIMEOUTS_SEC = listOf(30, 60, 90, 120, 180, 300, 600)
    private val PAUSES_SEC = listOf(10, 20, 30, 60, 120, 180, 300)
    private const val RETRY_DELAY_MS = 15_000L
    private const val USAGE_PREFS = "geminiUsage"

    private val queue = Mutex()
    private var nextAt = 0L

    // ---------- настройки ----------

    fun model(ctx: Context): String =
        MODELS[SettingsStore.sp(ctx).getInt("geminiModel", 0).coerceIn(0, MODELS.lastIndex)]

    private fun timeoutMs(ctx: Context): Int =
        TIMEOUTS_SEC[SettingsStore.sp(ctx).getInt("geminiTimeout", 1).coerceIn(0, TIMEOUTS_SEC.lastIndex)] * 1000

    private fun pauseMs(ctx: Context): Long =
        PAUSES_SEC[SettingsStore.sp(ctx).getInt("geminiPause", 1).coerceIn(0, PAUSES_SEC.lastIndex)] * 1000L

    private fun maxAttempts(ctx: Context): Int =
        (SettingsStore.sp(ctx).getInt("geminiAttempts", 2) + 1).coerceIn(1, 5)

    // ---------- ключи и расход токенов за сегодня ----------

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** Расход за сегодня: {"date":"…","keys":{"<ключ>":{"in":…,"out":…,"total":…}}}. Новый день — счётчики обнуляются. */
    @Synchronized
    private fun usageJson(ctx: Context): JSONObject {
        val raw = SettingsStore.str(ctx, USAGE_PREFS, "")
        val saved = runCatching { JSONObject(raw) }.getOrNull()
        return if (saved != null && saved.optString("date") == today()) saved
        else JSONObject().put("date", today()).put("keys", JSONObject())
    }

    @Synchronized
    private fun addUsage(ctx: Context, key: String, input: Long, output: Long, total: Long) {
        val j = usageJson(ctx)
        val keys = j.getJSONObject("keys")
        val k = keys.optJSONObject(key) ?: JSONObject().put("in", 0L).put("out", 0L).put("total", 0L)
        k.put("in", k.optLong("in") + input)
        k.put("out", k.optLong("out") + output)
        k.put("total", k.optLong("total") + total)
        keys.put(key, k)
        SettingsStore.putStr(ctx, USAGE_PREFS, j.toString())
    }

    fun keyUsage(ctx: Context, key: String): Long =
        usageJson(ctx).getJSONObject("keys").optJSONObject(key)?.optLong("total") ?: 0L

    /** Суммарный расход по всем сохранённым ключам и сумма их дневных лимитов. */
    fun totals(ctx: Context): GeminiTotals {
        val keys = usageJson(ctx).getJSONObject("keys")
        var i = 0L; var o = 0L; var t = 0L; var limit = 0L
        SettingsStore.geminiKeys(ctx).forEach { gk ->
            limit += gk.limit
            keys.optJSONObject(gk.key)?.let {
                i += it.optLong("in"); o += it.optLong("out"); t += it.optLong("total")
            }
        }
        return GeminiTotals(i, o, t, limit)
    }

    /** Следующий ключ по кругу, у которого не исчерпан дневной лимит. null — ключей нет или все исчерпаны. */
    @Synchronized
    private fun selectKey(ctx: Context): GeminiKey? {
        val list = SettingsStore.geminiKeys(ctx)
        if (list.isEmpty()) return null
        val pointer = SettingsStore.sp(ctx).getInt("geminiKeyPointer", 0).coerceAtLeast(0)
        for (offset in list.indices) {
            val index = (pointer + offset) % list.size
            val k = list[index]
            if (keyUsage(ctx, k.key) >= k.limit) continue
            SettingsStore.sp(ctx).edit().putInt("geminiKeyPointer", (index + 1) % list.size).apply()
            return k
        }
        return null
    }

    // ---------- запрос ----------

    /**
     * @param history предыдущие реплики: пары (роль "user"/"model", текст)
     * @param json    ответ строго JSON (responseMimeType = application/json)
     * @param onStatus сообщения об ожидании очереди и повторах
     */
    suspend fun generate(
        ctx: Context,
        parts: List<GeminiPart>,
        history: List<Pair<String, String>> = emptyList(),
        json: Boolean = false,
        temperature: Double? = null,
        onStatus: (String) -> Unit = {},
    ): GeminiReply {
        val app = ctx.applicationContext
        val key = selectKey(app)
            ?: throw GeminiException(
                if (SettingsStore.geminiKeys(app).isEmpty()) "Сначала добавьте API-ключ Gemini в настройках."
                else "Дневной лимит токенов всех ключей Gemini исчерпан.",
            )
        val body = buildBody(parts, history, json, temperature)
        val modelName = model(app)
        val pause = pauseMs(app)
        val timeout = timeoutMs(app)
        val attempts = maxAttempts(app)

        val raw = queue.withLock {
            val wait = nextAt - System.currentTimeMillis()
            if (wait > 0) countdown(wait, onStatus)
            nextAt = System.currentTimeMillis() + pause
            withRetry(attempts, onStatus) { post(modelName, key.key, body, timeout) }
        }
        return parse(app, key.key, raw)
    }

    private suspend fun countdown(ms: Long, onStatus: (String) -> Unit) {
        var left = ms
        while (left > 0) {
            onStatus("Ожидание очереди Gemini: ещё ${formatWait(left)}.")
            val step = minOf(1000L, left)
            delay(step)
            left -= step
        }
    }

    private suspend fun <T> withRetry(attempts: Int, onStatus: (String) -> Unit, task: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return task()
            } catch (e: GeminiException) {
                val st = e.status
                val fatal = st == 400 || st == 401 || st == 403 || st == 404 || st == 429
                val retryable = st == -1 || st == -2 || st >= 500
                if (fatal || !retryable || attempt >= attempts) throw e
                val waitMs = maxOf(RETRY_DELAY_MS, e.retryAfterMs).coerceAtMost(60_000L)
                onStatus("Сбой (${e.message}). Повтор $attempt/${attempts - 1} через ${formatWait(waitMs)}…")
                delay(waitMs)
            }
        }
    }

    private suspend fun post(model: String, key: String, body: String, timeoutMs: Int): String =
        withContext(Dispatchers.IO) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = timeoutMs
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", key)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) throw httpError(code, text, c.getHeaderField("Retry-After"))
                text
            } catch (e: SocketTimeoutException) {
                throw GeminiException(
                    "Превышено максимальное время ожидания ответа Gemini (${timeoutMs / 1000} секунд).", status = -1,
                )
            } catch (e: java.io.IOException) {
                throw GeminiException("Сеть недоступна.", status = -2)
            } finally {
                c.disconnect()
            }
        }

    private fun httpError(code: Int, text: String, retryHeader: String?): GeminiException {
        val raw = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: text.trim().ifEmpty { "HTTP $code" }
        var retryMs = retryHeader?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
        if (retryMs == 0L) {
            Regex("retry(?: in|after)[^0-9]*(\\d+(?:\\.\\d+)?)\\s*s", RegexOption.IGNORE_CASE).find(text)
                ?.groupValues?.get(1)?.toDoubleOrNull()?.let { retryMs = (it * 1000).toLong() }
        }
        val quota = code == 429 || Regex("quota|rate.?limit|exceeded.*limit|limit.*exceeded", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val msg = when {
            raw.contains("API key", true) || raw.contains("API_KEY", true) ->
                "Gemini отклонил API-ключ. Проверьте ключ в настройках."
            quota -> "Лимит Gemini исчерпан. Повторите позже" +
                (if (retryMs > 0) " (через ${formatWait(retryMs)})." else ".")
            else -> raw
        }
        return GeminiException(msg, status = code, retryAfterMs = retryMs)
    }

    private fun buildBody(
        parts: List<GeminiPart>,
        history: List<Pair<String, String>>,
        json: Boolean,
        temperature: Double?,
    ): String {
        val contents = JSONArray()
        history.forEach { (role, text) ->
            contents.put(
                JSONObject().put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text))),
            )
        }
        val ps = JSONArray()
        parts.forEach { p ->
            when (p) {
                is GeminiPart.Text -> ps.put(JSONObject().put("text", p.text))
                is GeminiPart.Inline -> ps.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", p.mime).put("data", p.base64),
                    ),
                )
            }
        }
        if (ps.length() == 0) ps.put(JSONObject().put("text", "Ответь коротко."))
        contents.put(JSONObject().put("role", "user").put("parts", ps))
        val gen = JSONObject().put("maxOutputTokens", 8192)
        if (temperature != null) gen.put("temperature", temperature)
        if (json) gen.put("responseMimeType", "application/json")
        return JSONObject().put("contents", contents).put("generationConfig", gen).toString()
    }

    private fun parse(ctx: Context, key: String, raw: String): GeminiReply {
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw GeminiException("Gemini вернул ответ в неизвестном формате.")
        val usage = root.optJSONObject("usageMetadata")
        val input = usage?.optLong("promptTokenCount") ?: 0L
        val output = usage?.optLong("candidatesTokenCount") ?: 0L
        val total = usage?.optLong("totalTokenCount")?.takeIf { it > 0 } ?: (input + output)
        if (total > 0) addUsage(ctx, key, input, output, total)
        val arr = root.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (arr != null) for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i)?.optString("text").orEmpty()
                if (t.isNotEmpty()) { if (isNotEmpty()) append('\n'); append(t) }
            }
        }.trim()
        if (text.isEmpty()) {
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw GeminiException(if (reason.isNotEmpty()) "Gemini заблокировал запрос: $reason." else "Gemini не вернул текстовый ответ.")
        }
        return GeminiReply(text, input, output, total)
    }

    /** Достаёт JSON-объект из ответа, даже если модель обернула его в ```json … ```. */
    fun parseJsonObject(text: String): JSONObject {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        return runCatching { JSONObject(cleaned) }.getOrNull() ?: run {
            val s = cleaned.indexOf('{')
            val e = cleaned.lastIndexOf('}')
            if (s >= 0 && e > s) runCatching { JSONObject(cleaned.substring(s, e + 1)) }.getOrNull() else null
        } ?: throw GeminiException("Gemini вернул ответ не в формате JSON.")
    }

    fun formatWait(ms: Long): String {
        val sec = maxOf(1L, (ms + 999) / 1000)
        return if (sec >= 60) {
            val m = sec / 60
            val r = sec % 60
            if (r > 0) "$m мин $r сек" else "$m мин"
        } else "$sec сек"
    }
}
