package ru.lager.app.ui.win

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Что показывает окно «Накладная» (#invoiceModal): список файлов партии и стартовый номер. */
object InvoiceState {
    var files by mutableStateOf<List<Attachment>>(emptyList())
    var start by mutableStateOf(0)

    /** Открывает просмотр накладных. Пустой список — окно покажет «Накладная не прикреплена». */
    fun show(nav: WinNav, docs: List<Attachment>, start: Int = 0) {
        files = docs
        this.start = start
        nav.push(Win.Invoice)
    }
}

/** Страницы файла накладной: фото — одна картинка (с учётом поворота EXIF), PDF — до 12 страниц. */
private suspend fun loadInvoicePages(path: String): List<Bitmap> = withContext(Dispatchers.IO) {
    val f = File(path)
    if (!f.exists()) return@withContext emptyList<Bitmap>()
    runCatching {
        if (f.extension.equals("pdf", ignoreCase = true)) {
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfRenderer(pfd).use { r ->
                    (0 until minOf(r.pageCount, 12)).map { i ->
                        r.openPage(i).use { page ->
                            val scale = 1400f / page.width
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                }
            } finally {
                runCatching { pfd.close() }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(f.path, opts)
            if (bmp == null) {
                emptyList<Bitmap>()
            } else {
                val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (deg == 0f) {
                    listOf(bmp)
                } else {
                    listOf(Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true))
                }
            }
        }
    }.getOrDefault(emptyList())
}

@Composable
fun InvoiceWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val files = InvoiceState.files
    val n = files.size
    var index by remember { mutableStateOf(InvoiceState.start.coerceIn(0, maxOf(0, n - 1))) }
    val file = files.getOrNull(index)
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(file?.path) {
        pages = emptyList()
        if (file != null) {
            loading = true
            pages = loadInvoicePages(file.path)
            loading = false
        }
    }

    WindowScaffold(
        title = "📄 Накладная" + if (n > 1) "  ${index + 1} / $n" else "",
        footer = {
            if (n > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("‹ Предыдущая", { index = (index - 1 + n) % n }, Modifier.weight(1f))
                    SoftButton("Следующая ›", { index = (index + 1) % n }, Modifier.weight(1f))
                }
            }
            if (file != null) {
                val isPdf = file.name.endsWith(".pdf", ignoreCase = true) || file.path.endsWith(".pdf", ignoreCase = true)
                LongButton(if (isPdf) "⬇️ Открыть PDF" else "⬇️ Открыть в галерее", LongKind.Blue, {
                    if (!AttachmentStore.open(ctx, file)) env.info("Нет приложения для просмотра файла")
                })
            }
            SoftButton("Закрыть", { env.nav.pop() }, Modifier.fillMaxWidth())
        },
    ) {
        when {
            file == null -> {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("📄", fontSize = 56.sp)
                    Text(
                        "Накладная не прикреплена",
                        fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "Для этой приёмки накладная не была загружена. Её можно прикрепить при сохранении приёмки.",
                        fontSize = 13.sp, color = c.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            loading -> EmptyHint("Загрузка…")
            pages.isEmpty() -> EmptyHint("Не удалось показать файл. Откройте его во внешнем приложении.")
            else -> {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(file.name, fontSize = 12.sp, color = c.onSurfaceVariant)
                    pages.forEach { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
