package ru.lager.app.ui.win

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Превращает файлы с телефона в части запроса Gemini. Всё тяжёлое выполняется в фоновом потоке. */
object GeminiFiles {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_SIDE = 2000
    private const val MAX_TEXT_CHARS = 200_000

    fun isPdf(ctx: Context, uri: Uri, name: String): Boolean =
        (ctx.contentResolver.getType(uri) ?: "").equals("application/pdf", true) || name.lowercase().endsWith(".pdf")

    fun isImage(ctx: Context, uri: Uri, name: String): Boolean =
        (ctx.contentResolver.getType(uri) ?: "").startsWith("image/", true) ||
            Regex("\\.(jpe?g|png|webp|heic|gif)$").containsMatchIn(name.lowercase())

    /** Фото → JPEG не больше 2000 px по длинной стороне, с учётом поворота из EXIF. */
    suspend fun imagePart(ctx: Context, uri: Uri): GeminiPart.Inline = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw GeminiException("Не удалось прочитать изображение.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw GeminiException("Не удалось прочитать изображение.")

        val rotation = runCatching {
            cr.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        val longSide = maxOf(bmp.width, bmp.height)
        val scale = if (longSide > MAX_SIDE) MAX_SIDE.toFloat() / longSide else 1f
        if (rotation != 0f || scale != 1f) {
            val m = Matrix().apply { postRotate(rotation); postScale(scale, scale) }
            val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (t !== bmp) bmp.recycle()
            bmp = t
        }
        toJpeg(bmp)
    }

    /** Первая страница PDF → JPEG (как «Из PDF берём ТОЛЬКО первый лист» в HTML). */
    suspend fun pdfFirstPage(ctx: Context, uri: Uri): GeminiPart.Inline = withContext(Dispatchers.IO) {
        val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw GeminiException("Не удалось открыть PDF.")
        try {
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) throw GeminiException("В PDF нет страниц.")
                renderer.openPage(0).use { page ->
                    val scale = 1700f / page.width
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    toJpeg(bmp)
                }
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException("Не удалось прочитать PDF: ${e.message ?: "файл повреждён"}.")
        } finally {
            runCatching { pfd.close() }
        }
    }

    private fun toJpeg(bmp: Bitmap): GeminiPart.Inline {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return GeminiPart.Inline("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    /** Фото или PDF накладной → одно изображение для распознавания шапки. */
    suspend fun invoiceImage(ctx: Context, uri: Uri, name: String): GeminiPart.Inline = when {
        isPdf(ctx, uri, name) -> pdfFirstPage(ctx, uri)
        isImage(ctx, uri, name) -> imagePart(ctx, uri)
        else -> throw GeminiException("Выберите PDF или фото накладной.")
    }

    /** Вложение чата: фото, PDF, таблица (xlsx/csv) или текстовый файл. */
    suspend fun chatParts(ctx: Context, uri: Uri, name: String): List<GeminiPart> {
        val lower = name.lowercase()
        return when {
            isImage(ctx, uri, name) -> listOf(imagePart(ctx, uri))
            isPdf(ctx, uri, name) -> withContext(Dispatchers.IO) {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw GeminiException("Файл «$name» пустой или не удалось его прочитать.")
                if (bytes.size > MAX_BYTES) throw GeminiException("Файл «$name» больше 8 МБ. Уменьшите файл и повторите.")
                listOf(GeminiPart.Inline("application/pdf", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            }
            lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv") -> withContext(Dispatchers.IO) {
                val rows = try {
                    TableReader.read(ctx, uri, name)
                } catch (e: Exception) {
                    throw GeminiException("Не удалось прочитать таблицу «$name»: ${e.message}.")
                }
                if (rows.isEmpty()) throw GeminiException("Таблица «$name» пустая.")
                val csv = rows.joinToString("\n") { r -> r.joinToString(",") { csvCell(it) } }
                listOf(GeminiPart.Text("Содержимое таблицы «$name»:\n${csv.take(MAX_TEXT_CHARS)}"))
            }
            Regex("\\.(txt|md|json|xml|html?|log)$").containsMatchIn(lower) ||
                (ctx.contentResolver.getType(uri) ?: "").startsWith("text/") -> withContext(Dispatchers.IO) {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw GeminiException("Файл «$name» пустой или не удалось его прочитать.")
                if (bytes.size > MAX_BYTES) throw GeminiException("Файл «$name» больше 8 МБ. Уменьшите файл и повторите.")
                listOf(GeminiPart.Text("Файл «$name»:\n" + String(bytes, Charsets.UTF_8).take(MAX_TEXT_CHARS)))
            }
            else -> throw GeminiException("Формат файла «$name» не поддерживается. Прикрепите фото, PDF, таблицу или текст.")
        }
    }

    private fun csvCell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
