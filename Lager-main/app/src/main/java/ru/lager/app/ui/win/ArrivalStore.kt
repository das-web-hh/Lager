package ru.lager.app.ui.win

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Запись прихода (arrivals / my_off_arr6 в HTML). */
data class Arrival(
    val id: String,
    val batchId: String,
    val date: String,
    val name: String,
    val menge: Int,
    val sender: String,
    val order: String,
    val source: String,
    val receivedAt: String,
)

/** parseDate из HTML: «ДД.ММ.ГГ» или «ДД.ММ.ГГГГ» → миллисекунды (0, если не разобрать). */
fun parseHistDate(str: String): Long {
    val p = str.split('.')
    if (p.size < 3) return 0L
    val d = p[0].trim().toIntOrNull() ?: return 0L
    val m = p[1].trim().toIntOrNull() ?: return 0L
    var y = p[2].trim().toIntOrNull() ?: return 0L
    if (p[2].trim().length == 2) y += 2000
    val cal = java.util.Calendar.getInstance()
    cal.clear()
    cal.set(y, m - 1, d)
    return cal.timeInMillis
}

/** История приёмок. Хранится в my_off_arr6.json, тот же формат, что в HTML. */
object ArrivalStore {
    val items = mutableStateListOf<Arrival>()

    private const val FILE = "my_off_arr6.json"
    private val io = Executors.newSingleThreadExecutor()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    private fun parse(ctx: Context): List<Arrival> = runCatching {
        val f = file(ctx)
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Arrival>(arr.length())
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(fromJson(it)) }
            out
        }
    }.getOrDefault(emptyList())

    private fun fromJson(o: JSONObject) = Arrival(
        id = o.optString("id").ifEmpty { java.util.UUID.randomUUID().toString().take(12) },
        batchId = o.optString("batchId"),
        date = o.optString("date"),
        name = o.optString("name"),
        menge = o.optInt("menge", 0),
        sender = o.optString("senderName"),
        order = o.optString("orderNumber"),
        source = o.optString("sourceFileName"),
        receivedAt = o.optString("receivedAt"),
    )

    suspend fun load(ctx: Context) {
        if (loaded) return
        val list = withContext(Dispatchers.IO) { parse(ctx) }
        if (!loaded) { items.addAll(list); loaded = true }
    }

    /** Синхронная подгрузка для правок, которые приходят из обработчиков нажатий. */
    private fun ensure(ctx: Context) {
        if (!loaded) { items.addAll(parse(ctx)); loaded = true }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        items.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id).put("batchId", a.batchId).put("date", a.date)
                    .put("name", a.name).put("menge", a.menge)
                    .put("senderName", a.sender).put("orderNumber", a.order)
                    .put("sourceFileName", a.source).put("receivedAt", a.receivedAt),
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

    /** Дописывает записи (дубликаты по id пропускаются). Возвращает, сколько добавлено. */
    fun merge(ctx: Context, incoming: JSONArray): Int {
        ensure(ctx)
        val ids = items.mapTo(HashSet()) { it.id }
        val fresh = ArrayList<Arrival>()
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            val a = fromJson(o)
            if (o.optString("id").isNotEmpty() && !ids.add(a.id)) continue
            fresh.add(a)
        }
        if (fresh.isNotEmpty()) { items.addAll(fresh); save(ctx) }
        return fresh.size
    }

    /** Добавляет готовые записи прихода (приёмка). */
    fun addAll(ctx: Context, list: List<Arrival>) {
        if (list.isEmpty()) return
        ensure(ctx)
        items.addAll(list)
        save(ctx)
    }

    fun delete(ctx: Context, id: String) {
        ensure(ctx)
        if (items.removeAll { it.id == id }) save(ctx)
    }

    /** При переименовании товара в каталоге имя обновляется и в истории. */
    fun rename(ctx: Context, oldName: String, newName: String) {
        ensure(ctx)
        var changed = false
        for (i in items.indices) {
            if (items[i].name.equals(oldName, ignoreCase = true)) {
                items[i] = items[i].copy(name = newName); changed = true
            }
        }
        if (changed) save(ctx)
    }

    /** При удалении товара из каталога удаляются все его приходы. */
    fun removeByName(ctx: Context, name: String) {
        ensure(ctx)
        if (items.removeAll { it.name.equals(name, ignoreCase = true) }) save(ctx)
    }
}
