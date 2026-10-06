package ru.lager.app.ui.win

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/** Напечатанный документ (warehouse_documents_v1 в HTML). */
data class StoredDoc(
    val id: String,
    val type: String,        // bware | full | discrepancy | report
    val title: String,
    val subtitle: String,
    val date: String,        // ДД.ММ.ГГГГ
    val time: String,        // ЧЧ:ММ
    val ts: Long,
    val docNo: String,
    val data: JSONObject?,   // sender, order, ls, date, docNo, items / products
    val driveId: String,
    val driveUrl: String,
    val previewUrl: String,
    val driveError: String,
)

object DocumentStore {
    val items = mutableStateListOf<StoredDoc>()

    private const val FILE = "warehouse_documents_v1.json"
    private const val MAX = 500
    private val io = Executors.newSingleThreadExecutor()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    private fun fromJson(o: JSONObject) = StoredDoc(
        id = o.optString("id").ifEmpty { "d" + System.currentTimeMillis().toString(36) },
        type = o.optString("type", "report"),
        title = o.optString("title", "Документ"),
        subtitle = o.optString("subtitle"),
        date = o.optString("date"),
        time = o.optString("time"),
        ts = o.optLong("ts"),
        docNo = o.optString("docNo"),
        data = o.optJSONObject("data"),
        driveId = o.optString("driveId"),
        driveUrl = o.optString("driveUrl"),
        previewUrl = o.optString("previewUrl"),
        driveError = o.optString("driveError"),
    )

    private fun toJson(d: StoredDoc) = JSONObject().apply {
        put("id", d.id); put("type", d.type); put("title", d.title); put("subtitle", d.subtitle)
        put("date", d.date); put("time", d.time); put("ts", d.ts); put("docNo", d.docNo)
        if (d.data != null) put("data", d.data)
        put("driveId", d.driveId); put("driveUrl", d.driveUrl)
        put("previewUrl", d.previewUrl); put("driveError", d.driveError)
    }

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        val list = runCatching {
            if (!f.exists()) emptyList() else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
            }
        }.getOrDefault(emptyList())
        items.clear(); items.addAll(list)
    }

    private fun persist(ctx: Context) {
        val snapshot = items.toList()
        val f = file(ctx)
        io.execute {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { arr.put(toJson(it)) }
                f.writeText(arr.toString())
            }
        }
    }

    private fun pad(n: Int) = n.toString().padStart(2, '0')
    fun fmtDate(c: Calendar) = "${pad(c.get(Calendar.DAY_OF_MONTH))}.${pad(c.get(Calendar.MONTH) + 1)}.${c.get(Calendar.YEAR)}"

    /** warehouseDocsAdd: новый документ — в начало списка. */
    fun add(
        ctx: Context, type: String, title: String, subtitle: String = "", docNo: String = "",
        data: JSONObject? = null, driveId: String = "", driveUrl: String = "",
        previewUrl: String = "", driveError: String = "",
    ): StoredDoc {
        load(ctx)
        val now = Calendar.getInstance()
        val d = StoredDoc(
            id = "d" + now.timeInMillis.toString(36) + (1000..9999).random(),
            type = type.ifEmpty { "report" }, title = title.ifEmpty { "Документ" }, subtitle = subtitle,
            date = fmtDate(now), time = "${pad(now.get(Calendar.HOUR_OF_DAY))}:${pad(now.get(Calendar.MINUTE))}",
            ts = now.timeInMillis, docNo = docNo, data = data,
            driveId = driveId, driveUrl = driveUrl, previewUrl = previewUrl, driveError = driveError,
        )
        items.add(0, d)
        while (items.size > MAX) items.removeAt(items.size - 1)
        persist(ctx)
        return d
    }

    fun delete(ctx: Context, id: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) { items.removeAt(i); persist(ctx) }
    }
}
