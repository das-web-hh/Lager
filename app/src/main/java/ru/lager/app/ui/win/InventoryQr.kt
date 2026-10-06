package ru.lager.app.ui.win

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.print.PrintHelper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR-этикетки мест хранения (renderInventoryQrPreview / printInventoryAddressLabel в HTML).
 * В коде лежит адрес целиком — «склад-ряд-этаж-полка», с «--» для незаданных частей, —
 * поэтому сканер инвентаризации читает его обратно без потерь (InventoryStore.parseAddress).
 */
object InventoryQr {

    /** Текст для QR: все четыре части адреса, чтобы незаданные «--» не терялись. */
    fun payload(a: InvAddr): String = listOf(a.warehouse, a.row, a.floor, a.shelf).joinToString("-")

    fun bitmap(text: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Лист A4 (150 dpi): подпись, QR примерно 5,5 см и крупный адрес — как .inventory-print-label. */
    fun labelBitmap(a: InvAddr): Bitmap {
        val pageW = 1240
        val pageH = 1754
        val page = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(page)
        cv.drawColor(Color.WHITE)

        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(68, 68, 68); textSize = 40f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val loc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 96f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER; letterSpacing = 0.06f
        }
        val label = a.label.ifEmpty { "—" }
        // Длинный адрес уменьшаем, чтобы он помещался в ширину листа.
        val maxW = pageW - 160f
        val measured = loc.measureText(label)
        if (measured > maxW) loc.textSize = loc.textSize * maxW / measured

        val qrSize = 400
        val block = 40 + 36 + qrSize + 36 + loc.textSize
        var y = (pageH - block) / 2f
        y += 40f
        cv.drawText("Адрес хранения", pageW / 2f, y, caption)
        y += 36f
        cv.drawBitmap(bitmap(payload(a), qrSize), (pageW - qrSize) / 2f, y, null)
        y += qrSize + 36f + loc.textSize * 0.8f
        cv.drawText(label, pageW / 2f, y, loc)
        return page
    }

    fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** Открывает системный диалог печати (можно распечатать или сохранить в PDF). */
    fun print(ctx: Context, a: InvAddr): Boolean {
        val activity = findActivity(ctx) ?: return false
        return runCatching {
            PrintHelper(activity).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
                .printBitmap("Адрес хранения ${a.label}", labelBitmap(a))
        }.isSuccess
    }
}
