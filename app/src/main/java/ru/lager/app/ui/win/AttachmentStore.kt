package ru.lager.app.ui.win

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Вложение партии: фото товара или накладная (фото/PDF). */
data class Attachment(val batchId: String, val kind: String, val name: String, val path: String)

/**
 * Фото и накладные приёмки. Файлы копируются в filesDir/attachments/<партия>/,
 * индекс лежит в attachments.json. Загрузка на Google Диск подключается отдельно.
 */
object AttachmentStore {
    const val KIND_PHOTO = "photo"
    const val KIND_DOC = "doc"
    const val MAX_PHOTOS = 20
    const val MAX_DOCS = 30

    private fun index(ctx: Context) = File(ctx.applicationContext.filesDir, "attachments.json")

    /** Временный файл + uri для системной камеры. */
    fun newCameraTarget(ctx: Context): Pair<File, Uri> {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "img_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return f to uri
    }

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun load(ctx: Context, batchId: String? = null): List<Attachment> = runCatching {
        val f = index(ctx)
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                Attachment(it.optString("batchId"), it.optString("kind"), it.optString("name"), it.optString("path"))
            }.filter { batchId == null || it.batchId == batchId }
        }
    }.getOrDefault(emptyList())

    /** Копирует выбранные файлы в хранилище партии. Возвращает, сколько сохранено. */
    @Synchronized
    fun save(ctx: Context, batchId: String, photos: List<Uri>, docs: List<Uri>): Int {
        val app = ctx.applicationContext
        val dir = File(app.filesDir, "attachments/$batchId").apply { mkdirs() }
        val added = ArrayList<Attachment>()
        fun copy(kind: String, list: List<Uri>) {
            list.forEachIndexed { i, uri ->
                runCatching {
                    val name = displayName(app, uri)
                    val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { "jpg" }
                    val out = File(dir, "${kind}_${i + 1}_${System.currentTimeMillis().toString(36)}.$ext")
                    app.contentResolver.openInputStream(uri)?.use { inp -> out.outputStream().use { inp.copyTo(it) } }
                    if (out.length() > 0) added.add(Attachment(batchId, kind, name, out.absolutePath)) else out.delete()
                }
            }
        }
        copy(KIND_PHOTO, photos.take(MAX_PHOTOS))
        copy(KIND_DOC, docs.take(MAX_DOCS))
        if (added.isNotEmpty()) {
            val arr = runCatching { JSONArray(index(app).takeIf { it.exists() }?.readText() ?: "[]") }.getOrDefault(JSONArray())
            added.forEach {
                arr.put(JSONObject().put("batchId", it.batchId).put("kind", it.kind).put("name", it.name).put("path", it.path))
            }
            index(app).writeText(arr.toString())
        }
        // чистим временные снимки камеры
        File(app.cacheDir, "camera").listFiles()?.forEach { it.delete() }
        return added.size
    }

    /** Открывает вложение во внешнем просмотрщике. Возвращает false, если подходящего приложения нет. */
    fun open(ctx: Context, a: Attachment): Boolean = runCatching {
        val f = File(a.path)
        if (!f.exists()) return false
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val mime = when (f.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)

    /** Сколько вложений у каждой партии (для значка в истории). */
    fun counts(ctx: Context): Map<String, Int> = load(ctx).groupingBy { it.batchId }.eachCount()
}
