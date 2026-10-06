package ru.lager.app.ui.win

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

data class NrHistProduct(val name: String, val code: String, val plan: Int, val actual: Int, val damage: Int)

data class NrHistEvent(val t: Long, val type: String, val text: String)

data class NrHistRecord(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val user: String,
    val sender: String,
    val order: String,
    val date: String,
    val sourceFileName: String,
    val batchId: String,
    /** recognized | saved | error | deleted */
    val status: String,
    val products: List<NrHistProduct>,
    val events: List<NrHistEvent>,
)

/**
 * «История сканирований» (в HTML — «История 2», nr_history2_v1): журнал операций по каждой накладной
 * из «Приёма по имени». Хранится на устройстве, отправка в Firebase/Диск здесь не выполняется.
 */
object NrHistoryStore {
    class Snapshot(
        val id: String,
        val user: String,
        val sender: String,
        val order: String,
        val date: String,
        val fileName: String,
        val batchId: String,
        val products: List<NrHistProduct>,
    )

    val records = mutableStateListOf<NrHistRecord>()

    private const val FILE = "nr_history2_v1.json"
    private val io = Executors.newSingleThreadExecutor()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    private fun parse(ctx: Context): List<NrHistRecord> = runCatching {
        val f = file(ctx)
        if (!f.exists()) return@runCatching emptyList()
        val arr = JSONArray(f.readText())
        val out = ArrayList<NrHistRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val products = ArrayList<NrHistProduct>()
            o.optJSONArray("products")?.let { pa ->
                for (j in 0 until pa.length()) pa.optJSONObject(j)?.let { p ->
                    products.add(
                        NrHistProduct(
                            p.optString("name"), p.optString("code"),
                            p.optInt("qtyPlan"), p.optInt("qtyActual"), p.optInt("qtyDamage"),
                        ),
                    )
                }
            }
            val events = ArrayList<NrHistEvent>()
            o.optJSONArray("events")?.let { ea ->
                for (j in 0 until ea.length()) ea.optJSONObject(j)?.let { e ->
                    events.add(NrHistEvent(e.optLong("t"), e.optString("type"), e.optString("text")))
                }
            }
            out.add(
                NrHistRecord(
                    id = o.optString("id"),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                    user = o.optString("user"),
                    sender = o.optString("sender"),
                    order = o.optString("order"),
                    date = o.optString("date"),
                    sourceFileName = o.optString("sourceFileName"),
                    batchId = o.optString("batchId"),
                    status = o.optString("status").ifEmpty { "recognized" },
                    products = products,
                    events = events,
                ),
            )
        }
        out
    }.getOrDefault(emptyList())

    suspend fun load(ctx: Context) {
        if (loaded) return
        val list = withContext(Dispatchers.IO) { parse(ctx) }
        if (!loaded) { records.addAll(list); loaded = true }
    }

    private fun ensure(ctx: Context) {
        if (!loaded) { records.addAll(parse(ctx)); loaded = true }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id).put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)
                    .put("user", r.user).put("sender", r.sender).put("order", r.order).put("date", r.date)
                    .put("sourceFileName", r.sourceFileName).put("batchId", r.batchId).put("status", r.status)
                    .put(
                        "products",
                        JSONArray().also { pa ->
                            r.products.forEach { p ->
                                pa.put(
                                    JSONObject().put("name", p.name).put("code", p.code)
                                        .put("qtyPlan", p.plan).put("qtyActual", p.actual).put("qtyDamage", p.damage),
                                )
                            }
                        },
                    )
                    .put(
                        "events",
                        JSONArray().also { ea ->
                            r.events.forEach { e -> ea.put(JSONObject().put("t", e.t).put("type", e.type).put("text", e.text)) }
                        },
                    ),
            )
        }
        val json = arr.toString()
        val target = file(ctx)
        io.execute {
            runCatching {
                val tmp = File(target.parentFile, "$FILE.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(target)) { target.writeText(json); tmp.delete() }
            }
        }
    }

    /** log() из HTML: создаёт запись или дополняет существующую событием. */
    fun log(ctx: Context, src: Snapshot, type: String, text: String = "") {
        ensure(ctx)
        val now = System.currentTimeMillis()
        val i = records.indexOfFirst { it.id == src.id }
        val old = if (i >= 0) records[i] else null
        val status = when {
            type == "saved" -> "saved"
            type == "deleted" -> if (old?.status == "saved") "saved" else "deleted"
            type == "error" -> if (old?.status == "saved") "saved" else "error"
            old?.status == "saved" || old?.status == "deleted" -> old.status
            else -> "recognized"
        }
        val products = src.products.ifEmpty { old?.products ?: emptyList() }
        val evText = text.ifEmpty {
            when (type) {
                "recognized" -> "В списке позиций: ${products.size}"
                "saved" -> "Партия сохранена: ${products.size} поз."
                "deleted" -> "Удалено из реестра сканирований"
                else -> ""
            }
        }
        val events = (old?.events ?: emptyList()).toMutableList()
        val last = events.lastOrNull()
        if (!(last != null && last.type == type && last.text == evText)) events.add(NrHistEvent(now, type, evText))
        val rec = NrHistRecord(
            id = src.id,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
            user = src.user.ifEmpty { old?.user.orEmpty() },
            sender = src.sender.ifEmpty { old?.sender.orEmpty() },
            order = src.order.ifEmpty { old?.order.orEmpty() },
            date = src.date.ifEmpty { old?.date.orEmpty() },
            sourceFileName = src.fileName.ifEmpty { old?.sourceFileName.orEmpty() },
            batchId = src.batchId.ifEmpty { old?.batchId.orEmpty() },
            status = status,
            products = products,
            events = events,
        )
        if (i >= 0) records[i] = rec else records.add(0, rec)
        save(ctx)
    }

    fun clear(ctx: Context) {
        records.clear()
        save(ctx)
    }
}
