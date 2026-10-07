package ru.lager.app.ui.win

import android.content.Context
import android.net.Uri

/** Результат распознавания накладной: поставщик, номер заказа, позиции (название, количество по накладной, EAN). */
class NrInvoiceResult(val sender: String, val order: String, val items: List<Triple<String, Int, String>>)

/**
 * «Приём по имени»: PDF или фото накладной → Gemini → список товаров с плановым количеством.
 * Инструкция та же, что у модуля извлечения накладных в HTML. PDF уходит в Gemini целиком (все страницы).
 */
object NrInvoice {
    private const val PROMPT =
        "Ты модуль универсального извлечения данных из накладных. Сначала проверь рукописные отметки: " +
            "если нет ни одной ✓ или X, включи все напечатанные строки товаров; если отметки есть, исключи строки с X, " +
            "неотмеченные строки со словами ew, europallet, pallet, versand, EB+цифры, palettenfrachtkostenpauschale, " +
            "palletenfrachtkostenpauseschale, versandkosten, landehilfsmittel или post и включи только строки с ясной ✓/подтверждением. " +
            "Одинаковую строку на разных страницах учитывай один раз, приоритет первой странице. На пустом документе верни пустой items. " +
            "senderName бери из шапки/логотипа, но не используй creditor@stroeh.de, stroeh или ströh; иначе no_name. " +
            "orderNumber — только EB и ровно 7 цифр, иначе EB_no_data. Дату можно не заполнять. " +
            "Количество бери из aktuelle Liefermenge; рукописное число рядом с stk/штук важнее, перечёркнутое/X/0 означает 0, число всегда целое. " +
            "ean — только физически видимый штрихкод на строке, Artikel-Nr. не включай. " +
            "Верни только валидный JSON без Markdown: " +
            "{\"senderName\":\"\",\"orderNumber\":\"\",\"date\":\"\",\"items\":[{\"name\":\"\",\"menge\":0,\"unit\":\"\",\"ean\":\"\"}]}."

    fun isRecognizable(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("pdf", "jpg", "jpeg", "png", "webp")

    private fun amount(raw: Any?): Int {
        val m = Regex("-?\\d+(?:\\.\\d+)?").find(raw?.toString().orEmpty().replace(',', '.')) ?: return 0
        return maxOf(0, m.value.toDouble().toInt())
    }

    suspend fun recognize(ctx: Context, uri: Uri, name: String, onStatus: (String) -> Unit = {}): NrInvoiceResult {
        val parts = ArrayList<GeminiPart>()
        parts.add(GeminiPart.Text(PROMPT))
        parts.addAll(GeminiFiles.chatParts(ctx, uri, name))
        val reply = GeminiClient.generate(ctx, parts, json = true, temperature = 0.0, onStatus = onStatus)
        val obj = GeminiClient.parseJsonObject(reply.text)

        fun clean(v: String, bad: String): String = v.trim().let { if (it.isEmpty() || it == bad) "" else it }

        // Одинаковые названия суммируются (buildProducts в HTML).
        val order = LinkedHashMap<String, Triple<String, Int, String>>()
        val arr = obj.optJSONArray("items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val n = (it.optString("name").ifBlank { it.optString("product") }).trim()
                if (n.isEmpty()) continue
                val qty = amount(it.opt("menge") ?: it.opt("quantity") ?: it.opt("qty"))
                val ean = it.optString("ean").trim()
                val key = n.lowercase().replace(Regex("\\s+"), " ")
                val old = order[key]
                order[key] = if (old != null) Triple(old.first, old.second + qty, old.third.ifEmpty { ean }) else Triple(n, qty, ean)
            }
        }
        return NrInvoiceResult(
            sender = clean(obj.optString("senderName"), "no_name"),
            order = clean(obj.optString("orderNumber"), "EB_no_data"),
            items = order.values.toList(),
        )
    }
}
