package ru.lager.app.ui.win

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class DayStat(val date: String, val count: Int, val menge: Int)

data class InfoStatsData(
    val catalog: Int,
    val arrivals: Int,
    val totalMenge: Long,
    val days: List<DayStat>,
    val photoCount: Int,
    val photoSize: Long,
    val docCount: Int,
    val docSize: Long,
    /** Размер каталога и истории (my_off_db.json + my_off_arr6.json). */
    val dbSize: Long,
) {
    val totalSize: Long get() = dbSize + photoSize + docSize
}

/** Сводка для окна «Инфо» (renderInfoStats в HTML). */
object InfoStats {

    suspend fun compute(ctx: Context): InfoStatsData {
        val app = ctx.applicationContext
        CatalogStore.load(app)
        ArrivalStore.load(app)
        val catalogCount = CatalogStore.items.size
        val arrivals = ArrivalStore.items.toList()

        return withContext(Dispatchers.IO) {
            val perDay = LinkedHashMap<String, IntArray>()
            var total = 0L
            arrivals.forEach { a ->
                val key = a.date.ifBlank { "—" }
                val cell = perDay.getOrPut(key) { IntArray(2) }
                cell[0] += 1
                cell[1] += a.menge
                total += a.menge
            }
            val days = perDay.map { (date, v) -> DayStat(date, v[0], v[1]) }
                .sortedByDescending { parseHistDate(it.date) }

            val files = AttachmentStore.load(app)
            val photos = files.filter { it.kind == AttachmentStore.KIND_PHOTO }
            val docs = files.filter { it.kind == AttachmentStore.KIND_DOC }
            fun size(list: List<Attachment>) = list.sumOf { File(it.path).takeIf { f -> f.exists() }?.length() ?: 0L }

            val dbSize = listOf("my_off_db.json", "my_off_arr6.json")
                .sumOf { File(app.filesDir, it).takeIf { f -> f.exists() }?.length() ?: 0L }

            InfoStatsData(
                catalog = catalogCount,
                arrivals = arrivals.size,
                totalMenge = total,
                days = days,
                photoCount = photos.size,
                photoSize = size(photos),
                docCount = docs.size,
                docSize = size(docs),
                dbSize = dbSize,
            )
        }
    }

    /** fmtBytes из HTML. */
    fun fmtBytes(b: Long): String = when {
        b <= 0L -> "0 Б"
        b < 1024L -> "$b Б"
        b < 1024L * 1024L -> String.format(Locale.US, "%.1f КБ", b / 1024.0)
        else -> String.format(Locale.US, "%.2f МБ", b / (1024.0 * 1024.0))
    }

    fun groupInt(n: Long): String = String.format(Locale("ru", "RU"), "%,d", n)
}
