package ru.lager.app.ui.win

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class NrProdRec(val id: Long, val name: String, val plan: Int, val actual: Int, val damage: Int, val ean: String)

/** Задача реестра «Приёма по имени» в виде, пригодном для записи на диск. */
internal class NrTaskRec(
    val id: Long,
    val histId: String,
    val title: String,
    val sender: String,
    val order: String,
    val saved: Boolean,
    val savedDay: String,
    /** Ready | Queued | Processing | Error (имя NrTaskState) */
    val state: String,
    val error: String,
    /** Копия исходного файла для распознавания и повтора, null если нет. */
    val source: Uri?,
    val docs: List<Uri>,
    val products: List<NrProdRec>,
)

/**
 * Реестр задач «Приёма по имени» на диске (HTML: nrRegistryApi + idb nrtask:<id>).
 * JSON в nr_registry_v1.json, файлы задач в nr_tasks/<id>/. После перезапуска прерванные задачи
 * (в очереди или в обработке) становятся ошибкой с повтором, сохранённые партии прошлых дней уходят из реестра.
 */
internal object NrRegistryStore {
    private const val FILE = "nr_registry_v1.json"
    private const val DIR = "nr_tasks"
    private val lock = Any()

    private fun root(ctx: Context) = File(ctx.applicationContext.filesDir, DIR)
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun dayKey(ts: Long = System.currentTimeMillis()): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        return "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.MONTH) + 1}-${c.get(java.util.Calendar.DAY_OF_MONTH)}"
    }

    /** Копирует исходный файл задачи в постоянное хранилище. Возвращает копию или null. */
    fun copyIn(ctx: Context, id: Long, uri: Uri, name: String): Uri? = runCatching {
        val dir = File(root(ctx), id.toString()).apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "source" }
        val out = File(dir, "src_$safe")
        ctx.applicationContext.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }
            ?: return@runCatching null
        Uri.fromFile(out)
    }.getOrNull()

    /** Приложенные накладные: копируем в папку задачи (один раз на файл), в JSON пишем путь. */
    private fun persistDoc(ctx: Context, id: Long, uri: Uri): String? = runCatching {
        val dir = File(root(ctx), id.toString()).apply { mkdirs() }
        if (uri.scheme == "file") {
            val f = File(uri.path.orEmpty())
            if (f.parentFile?.canonicalPath == dir.canonicalPath) return@runCatching f.absolutePath
        }
        val type = ctx.applicationContext.contentResolver.getType(uri).orEmpty()
        val ext = when {
            type.contains("pdf") -> ".pdf"
            type.contains("png") -> ".png"
            else -> ".jpg"
        }
        val f = File(dir, "doc_" + Integer.toHexString(uri.toString().hashCode()) + ext)
        if (!f.exists()) {
            ctx.applicationContext.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                ?: return@runCatching null
        }
        f.absolutePath
    }.getOrNull()

    fun save(ctx: Context, recs: List<NrTaskRec>) = synchronized(lock) {
        runCatching {
            val arr = JSONArray()
            recs.forEach { r ->
                val docs = JSONArray()
                r.docs.forEach { u -> persistDoc(ctx, r.id, u)?.let { docs.put(it) } }
                arr.put(
                    JSONObject()
                        .put("id", r.id).put("histId", r.histId).put("title", r.title)
                        .put("sender", r.sender).put("order", r.order)
                        .put("saved", r.saved).put("savedDay", r.savedDay)
                        .put("state", r.state).put("error", r.error)
                        .put("source", r.source?.path.orEmpty())
                        .put("docs", docs)
                        .put(
                            "products",
                            JSONArray().also { pa ->
                                r.products.forEach { p ->
                                    pa.put(
                                        JSONObject().put("id", p.id).put("name", p.name).put("plan", p.plan)
                                            .put("actual", p.actual).put("damage", p.damage).put("ean", p.ean),
                                    )
                                }
                            },
                        ),
                )
            }
            val target = file(ctx)
            val tmp = File(target.parentFile, "$FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(target)) { target.writeText(arr.toString()); tmp.delete() }
            // Папки удалённых задач убираем.
            val keep = recs.map { it.id.toString() }.toSet()
            root(ctx).listFiles()?.forEach { if (it.isDirectory && it.name !in keep) it.deleteRecursively() }
        }
    }

    fun load(ctx: Context): List<NrTaskRec> = synchronized(lock) {
        runCatching {
            val f = file(ctx)
            if (!f.exists()) return@runCatching emptyList<NrTaskRec>()
            val arr = JSONArray(f.readText())
            val today = dayKey()
            val out = ArrayList<NrTaskRec>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val saved = o.optBoolean("saved")
                // purgeSavedOld: со сменой дня сохранённые партии уходят из реестра (в истории они уже есть).
                if (saved && o.optString("savedDay") != today) continue
                val srcPath = o.optString("source")
                val src = srcPath.takeIf { it.isNotEmpty() && File(it).exists() }?.let { Uri.fromFile(File(it)) }
                var state = o.optString("state").ifEmpty { "Ready" }
                var error = o.optString("error")
                // recover(): прерванные распознавания помечаем ошибкой.
                if (state == "Processing" || state == "Queued") {
                    state = "Error"
                    error = if (src == null) "Исходный файл не найден. Удалите карточку и отправьте файл заново."
                    else "Распознавание прервано."
                } else if (state == "Error" && src == null && error.isEmpty()) {
                    error = "Исходный файл не найден."
                }
                val docs = ArrayList<Uri>()
                o.optJSONArray("docs")?.let { da ->
                    for (j in 0 until da.length()) {
                        val p = da.optString(j)
                        if (File(p).exists()) docs.add(Uri.fromFile(File(p)))
                    }
                }
                val products = ArrayList<NrProdRec>()
                o.optJSONArray("products")?.let { pa ->
                    for (j in 0 until pa.length()) pa.optJSONObject(j)?.let { p ->
                        products.add(NrProdRec(p.optLong("id"), p.optString("name"), p.optInt("plan"), p.optInt("actual"), p.optInt("damage"), p.optString("ean")))
                    }
                }
                out.add(
                    NrTaskRec(
                        id = o.optLong("id"), histId = o.optString("histId"), title = o.optString("title"),
                        sender = o.optString("sender"), order = o.optString("order"),
                        saved = saved, savedDay = o.optString("savedDay"),
                        state = state, error = error, source = src, docs = docs, products = products,
                    ),
                )
            }
            out
        }.getOrDefault(emptyList())
    }
}
