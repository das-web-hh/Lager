package ru.lager.app.ui.win

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.Network
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Файл на Google Диске: найденный поиском или уже загруженный. */
data class DriveLink(val name: String, val url: String, val isPdf: Boolean, val kind: String = "")

/**
 * Очередь отправки накладных и фото на Google Диск через Google Apps Script Web App
 * (HTML: _gdEnqueue / _gdFlushOutbox / _uploadOneFileToGoogleDrive / _searchGoogleDriveForCard).
 *
 * Файлы всегда остаются на устройстве. Если Диск не настроен или нет сети, они ждут в очереди
 * (filesDir/gd_queue + gd_outbox_v1.json) и уходят сами: при старте, при появлении сети,
 * каждые 5 минут и сразу после постановки в очередь.
 *
 * Контракт Web App: GET ?action=ping|search&folderId=…&key=WH_…&code=…,
 * POST multipart: action=upload, payload=<JSON с contentBase64>. Ответ {ok:true, file:{…}}.
 */
object DriveOutbox {
    private const val OUTBOX = "gd_outbox_v1.json"
    private const val LINKS = "gd_links.json"
    private const val QUEUE_DIR = "gd_queue"
    private const val UPLOAD_TIMEOUT = 90_000
    private const val SEARCH_TIMEOUT = 15_000

    /** Сколько файлов ждёт отправки. */
    val pending = mutableStateOf(0)

    /** Последний итог отправки для строки статуса в настройках. */
    val status = mutableStateOf("")

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    // ---------- настройки ----------

    class Config(val url: String, val folderId: String, val codeField: Int, val makePublic: Boolean) {
        val ready get() = url.startsWith("http") && folderId.isNotEmpty()
    }

    fun config(ctx: Context): Config = Config(
        url = SettingsStore.str(ctx, "driveUrl").trim(),
        folderId = SettingsStore.str(ctx, "driveFolder").trim(),
        codeField = SettingsStore.sp(ctx).getInt("codeField", 0).coerceIn(0, 3),
        makePublic = SettingsStore.sp(ctx).getBoolean("drivePublic", false),
    )

    // ---------- имена и ключи ----------

    /** _googleDriveSafePart */
    fun safePart(value: String?, max: Int = 80): String =
        (value ?: "").trim().replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_', ' ', '.').take(max).ifEmpty { "file" }

    /** _googleDriveBase64Token: base64url без «=» от UTF-8 кода. */
    private fun token(code: String): String =
        Base64.encodeToString(code.trim().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** _googleDriveCardCode: код по выбранному полю (0 EAN, 1 артикул, 2 партия, 3 авто). */
    fun cardCode(cfg: Config, ean: String, artikel: String, batchId: String): String {
        val candidates = when (cfg.codeField) {
            0 -> listOf(ean)
            1 -> listOf(artikel)
            2 -> listOf(batchId)
            else -> listOf(ean, artikel, batchId)
        }
        return candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun keyFor(code: String): String = if (code.isEmpty()) "" else "WH_" + token(code)

    // ---------- файлы очереди ----------

    private fun outboxFile(ctx: Context) = File(ctx.applicationContext.filesDir, OUTBOX)
    private fun linksFile(ctx: Context) = File(ctx.applicationContext.filesDir, LINKS)
    private fun queueDir(ctx: Context) = File(ctx.applicationContext.filesDir, QUEUE_DIR).apply { mkdirs() }

    private fun readArray(f: File): JSONArray =
        runCatching { if (f.exists()) JSONArray(f.readText()) else JSONArray() }.getOrDefault(JSONArray())

    @Synchronized
    private fun loadOutbox(ctx: Context): JSONArray = readArray(outboxFile(ctx))

    @Synchronized
    private fun saveOutbox(ctx: Context, arr: JSONArray) {
        outboxFile(ctx).writeText(arr.toString())
        pending.value = arr.length()
    }

    /** Подтягивает счётчик очереди при старте окна. */
    fun refreshPending(ctx: Context) {
        pending.value = loadOutbox(ctx).length()
    }

    // ---------- снимок → PDF ----------

    private fun isImage(name: String) =
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    /** _invoiceFileToPdf: накладная на Диск всегда в PDF. Фото оборачивается в страницу A4 без обработки. */
    private fun imageToPdf(src: File, outDir: File, baseName: String): File? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4200) sample *= 2
        var bmp = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotate = when (ExifInterface(src.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f
            3 -> 180f
            8 -> 270f
            else -> 0f
        }
        if (rotate != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotate) }, true)
        }
        val portrait = bmp.height >= bmp.width
        val pageW = if (portrait) 595 else 842
        val pageH = if (portrait) 842 else 595
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        val canvas: Canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        val k = minOf(pageW.toFloat() / bmp.width, pageH.toFloat() / bmp.height)
        val dw = bmp.width * k
        val dh = bmp.height * k
        val x = (pageW - dw) / 2f
        val y = (pageH - dh) / 2f
        canvas.drawBitmap(bmp, null, RectF(x, y, x + dw, y + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(page)
        val out = File(outDir, "${System.currentTimeMillis().toString(36)}_$baseName.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        out
    }.getOrNull()

    private fun sha256(f: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    // ---------- постановка в очередь ----------

    /**
     * Вызывается из AttachmentStore.save после копирования файлов партии.
     * Фото уходят как есть, накладные (doc) — в PDF. Один и тот же документ к одной партии дважды не ставится.
     */
    fun enqueue(ctx: Context, batchId: String, items: List<Attachment>, ean: String = "", artikel: String = "") {
        if (items.isEmpty()) return
        val app = ctx.applicationContext
        runCatching {
            val cfg = config(app)
            val code = cardCode(cfg, ean, artikel, batchId).ifEmpty { batchId }
            val key = keyFor(code).ifEmpty { safePart(batchId, 40) }
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val hashPrefs = app.getSharedPreferences("lager_gd_hashes", Context.MODE_PRIVATE)
            val known = hashPrefs.getStringSet(batchId, emptySet()).orEmpty().toMutableSet()
            val dir = queueDir(app)
            val list = loadOutbox(app)
            var photoNo = 0
            var invoiceNo = 0
            items.forEach { a ->
                val src = File(a.path)
                if (!src.exists()) return@forEach
                val isDoc = a.kind == AttachmentStore.KIND_DOC
                var file = src
                var name = a.name
                if (isDoc && isImage(a.name)) {
                    imageToPdf(src, dir, safePart(a.name.substringBeforeLast('.'), 40))?.let {
                        file = it
                        name = a.name.substringBeforeLast('.') + ".pdf"
                    }
                }
                if (isDoc) {
                    val hash = sha256(file)
                    if (hash.isNotEmpty() && hash in known) {
                        if (file != src) file.delete()
                        return@forEach
                    }
                    if (hash.isNotEmpty()) known.add(hash)
                }
                // Файл из attachments остаётся на месте; в очередь кладём отдельную копию.
                val queued = if (file.parentFile == dir) file
                else File(dir, "${System.currentTimeMillis().toString(36)}_${safePart(name, 60)}").also { file.copyTo(it, overwrite = true) }
                val no = if (isDoc) ++invoiceNo else ++photoNo
                val kind = if (isDoc) "invoice" else "photo"
                list.put(
                    JSONObject()
                        .put("id", queued.name).put("path", queued.absolutePath)
                        .put("kind", kind).put("batchId", batchId)
                        .put("key", key).put("code", code)
                        .put("fileName", "${key}_${safePart(date, 24)}_${kind}_${no}_${safePart(name)}")
                        .put("name", name).put("queuedAt", System.currentTimeMillis()),
                )
            }
            hashPrefs.edit().putStringSet(batchId, known).apply()
            saveOutbox(app, list)
        }
        start(app)
        scope.launch { delay(500); flush(app) }
    }

    // ---------- отправка ----------

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun readJson(code: Int, text: String): JSONObject {
        val obj = try {
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: Exception) {
            throw Exception("Web App вернул не JSON. Проверьте публикацию и URL.")
        }
        if (code !in 200..299) throw Exception(obj.optString("error").ifEmpty { "HTTP $code" })
        if (obj.has("ok") && !obj.optBoolean("ok", true)) {
            throw Exception(obj.optString("error").ifEmpty { "Google Apps Script сообщил об ошибке." })
        }
        return obj
    }

    private fun call(conn: HttpURLConnection): JSONObject = try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        readJson(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    } catch (e: java.net.SocketTimeoutException) {
        throw Exception("Превышено время ожидания Google Диска.")
    } finally {
        conn.disconnect()
    }

    private fun upload(cfg: Config, rec: JSONObject, file: File): JSONObject {
        val payload = JSONObject()
            .put("action", "upload")
            .put("folderId", cfg.folderId)
            .put("key", rec.optString("key"))
            .put("code", rec.optString("code"))
            .put("fileName", rec.optString("fileName"))
            .put("mimeType", mimeOf(rec.optString("name")))
            .put("contentBase64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            .put("makePublic", cfg.makePublic)
        val boundary = "----lager" + System.currentTimeMillis().toString(16)
        val body = ByteArrayOutputStream()
        fun field(n: String, v: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
        }
        field("action", "upload")
        field("payload", payload.toString())
        body.write("--$boundary--\r\n".toByteArray())

        val conn = URL(cfg.url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = UPLOAD_TIMEOUT
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val result = call(conn)
        return result.optJSONObject("file") ?: result
    }

    private fun isPdfFile(o: JSONObject): Boolean =
        Regex("pdf", RegexOption.IGNORE_CASE).containsMatchIn(o.optString("mimeType").ifEmpty { o.optString("type") }) ||
            Regex("\\.pdf(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(o.optString("name").ifEmpty { o.optString("url") })

    /** _googleDriveFileUrl */
    private fun fileUrl(o: JSONObject, pdf: Boolean): String {
        val order = if (pdf) listOf("previewUrl", "viewUrl", "webViewLink", "url", "downloadUrl")
        else listOf("viewUrl", "url", "downloadUrl", "previewUrl")
        return order.map { o.optString(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    @Synchronized
    private fun addLink(ctx: Context, batchId: String, kind: String, link: DriveLink) {
        val arr = readArray(linksFile(ctx))
        arr.put(
            JSONObject().put("batchId", batchId).put("kind", kind)
                .put("url", link.url).put("name", link.name).put("isPdf", link.isPdf),
        )
        linksFile(ctx).writeText(arr.toString())
    }

    /** Ссылки на Диск, уже полученные для партии (driveInvoiceUrls / drivePhotoUrls в HTML). */
    fun links(ctx: Context, batchId: String): List<DriveLink> {
        val arr = readArray(linksFile(ctx))
        val out = ArrayList<DriveLink>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("batchId") == batchId) {
                out.add(DriveLink(o.optString("name"), o.optString("url"), o.optBoolean("isPdf"), o.optString("kind")))
            }
        }
        return out
    }

    /** _gdFlushOutbox: отправляет очередь по одному файлу; при ошибке останавливается и повторит позже. */
    suspend fun flush(ctx: Context) {
        val app = ctx.applicationContext
        if (!mutex.tryLock()) return
        try {
            val cfg = config(app)
            if (!cfg.ready) return
            withContext(Dispatchers.IO) {
                var list = loadOutbox(app)
                if (list.length() == 0) return@withContext
                var done = 0
                var lastError: String? = null
                var i = 0
                while (i < list.length()) {
                    val rec = list.optJSONObject(i)
                    if (rec == null) {
                        i++
                        continue
                    }
                    val file = File(rec.optString("path"))
                    try {
                        if (file.exists()) {
                            val uploaded = upload(cfg, rec, file)
                            val isInvoice = rec.optString("kind") == "invoice"
                            val pdf = isInvoice && (isPdfFile(uploaded) || rec.optString("name").lowercase().endsWith(".pdf"))
                            val url = fileUrl(uploaded, pdf)
                            if (url.isNotEmpty()) {
                                addLink(
                                    app, rec.optString("batchId"), rec.optString("kind"),
                                    DriveLink(uploaded.optString("name").ifEmpty { rec.optString("name") }, url, pdf, rec.optString("kind")),
                                )
                            }
                            done++
                        }
                        // удаляем запись и файл очереди
                        val rest = JSONArray()
                        for (j in 0 until list.length()) if (j != i) rest.put(list.get(j))
                        list = rest
                        saveOutbox(app, list)
                        file.delete()
                    } catch (e: Exception) {
                        lastError = e.message ?: "ошибка"
                        break
                    }
                }
                val left = list.length()
                status.value = when {
                    done > 0 -> "Из очереди отправлено на Google Диск: $done" + if (left > 0) " · осталось: $left" else ""
                    lastError != null && left > 0 -> "Google Диск: в очереди $left, отправка не удалась ($lastError). Повторим позже."
                    else -> status.value
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    // ---------- поиск файлов карточки ----------

    /** _searchGoogleDriveForCard: файлы папки с ключом WH_<код>. */
    suspend fun search(ctx: Context, ean: String, artikel: String): List<DriveLink> = withContext(Dispatchers.IO) {
        val cfg = config(ctx)
        if (!cfg.ready) return@withContext emptyList()
        val code = cardCode(cfg, ean, artikel, "")
        val key = keyFor(code)
        if (key.isEmpty()) return@withContext emptyList()
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val sep = if (cfg.url.contains('?')) "&" else "?"
        val url = "${cfg.url}${sep}action=search&folderId=${enc(cfg.folderId)}&key=${enc(key)}&code=${enc(code)}"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = SEARCH_TIMEOUT
        conn.readTimeout = SEARCH_TIMEOUT
        conn.setRequestProperty("Accept", "application/json")
        val res = call(conn)
        val arr = res.optJSONArray("files") ?: res.optJSONArray("items") ?: res.optJSONArray("results")
            ?: res.optJSONObject("data")?.optJSONArray("files") ?: JSONArray()
        val out = ArrayList<DriveLink>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val o = if (item is String) JSONObject().put("url", item).put("name", item) else item as? JSONObject ?: continue
            val pdf = isPdfFile(o)
            val link = fileUrl(o, pdf)
            if (link.isNotEmpty()) out.add(DriveLink(o.optString("name").ifEmpty { "Файл Google Диска" }, link, pdf))
        }
        out
    }

    // ---------- фоновый запуск ----------

    /** При старте приложения: отправка через 4 с, затем каждые 5 минут и при появлении сети. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        refreshPending(app)
        scope.launch {
            delay(4_000)
            while (true) {
                flush(app)
                delay(5 * 60_000L)
            }
        }
        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { delay(1_500); flush(app) }
                }
            })
        }
    }
}
