package ru.lager.app.ui.win

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Разобранный JSON-бэкап из HTML: {database:[...], arrivals:[...], exportedAt, version}. */
class CatBackup(
    val products: List<Triple<String, String, String>>,
    val arrivals: JSONArray,
    val version: String,
    val exportedAt: String,
)

/** Товар каталога. Поля как в HTML (my_off_db): name, ean, artikel. id нужен только для списка. */
data class CatItem(val id: Long, val name: String, val ean: String, val artikel: String)

/**
 * Общая база товаров (аналог `database` / my_off_db в HTML).
 * Хранится файлом в памяти приложения, формат JSON-массива совместим с HTML.
 * Её же используют «Каталог», «Товар + Штрихкод», а дальше — поиск подсказок и сканер.
 */
object CatalogStore {
    val items = mutableStateListOf<CatItem>()

    private const val FILE = "my_off_db.json"
    private val seq = AtomicLong()
    private val io = Executors.newSingleThreadExecutor()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    suspend fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val f = file(ctx)
                if (!f.exists()) {
                    emptyList()
                } else {
                    val arr = JSONArray(f.readText())
                    val out = ArrayList<CatItem>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("name").trim()
                        if (name.isEmpty()) continue
                        val ean = o.optString("ean").ifEmpty { o.optString("barcode") }.trim()
                        out.add(CatItem(seq.incrementAndGet(), name, ean, o.optString("artikel").trim()))
                    }
                    out
                }
            }.getOrDefault(emptyList())
        }
        items.addAll(list)
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        items.forEach { p ->
            arr.put(JSONObject().put("name", p.name).put("ean", p.ean).put("artikel", p.artikel))
        }
        val json = arr.toString()
        val target = file(ctx)
        io.execute {
            runCatching {
                val tmp = File(target.parentFile, "$FILE.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(target)) {
                    target.writeText(json)
                    tmp.delete()
                }
            }
        }
    }

    // ---------- поиск ----------

    /**
     * Приёмка: неизвестный товар попадает в каталог, у известного пустой EAN дописывается.
     * Существующие данные не перезаписываются.
     */
    fun ensure(ctx: Context, name: String, ean: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val byBarcode = if (ean.isNotEmpty()) findByBarcode(ean) else null
        val existing = byBarcode ?: findByName(n)
        if (existing == null) {
            items.add(CatItem(seq.incrementAndGet(), n, ean, ""))
        } else if (existing.ean.isEmpty() && ean.isNotEmpty()) {
            val i = items.indexOfFirst { it.id == existing.id }
            if (i >= 0) items[i] = items[i].copy(ean = ean)
        } else {
            return
        }
        save(ctx)
    }

    /** normalizeBarcodeValue из HTML: цифры как есть, иначе верхний регистр без пробелов и дефисов. */
    fun normalizeBarcode(value: String): String {
        val raw = value.trim()
        if (raw.isNotEmpty() && raw.all { it in '0'..'9' }) return raw
        return raw.uppercase(Locale.ROOT).replace(Regex("[\\s-]+"), "")
    }

    /** searchMatchesTokens: все слова запроса должны входить в строку. */
    private fun matches(haystack: String, query: String): Boolean {
        val h = haystack.lowercase()
        val tokens = query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.isEmpty() || tokens.all { h.contains(it) }
    }

    /** Фильтр начинает работать с трёх букв, как в HTML. */
    fun search(query: String): List<CatItem> {
        if (query.trim().length < 3) return items.toList()
        return items.filter { matches("${it.name} ${it.ean} ${it.artikel}", query) }
    }

    fun sorted(list: List<CatItem>): List<CatItem> =
        list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun findByBarcode(code: String): CatItem? {
        val n = normalizeBarcode(code)
        if (n.isEmpty()) return null
        return items.firstOrNull { normalizeBarcode(it.ean) == n }
    }

    fun findByName(name: String): CatItem? {
        val n = name.trim()
        return items.firstOrNull { it.name.trim().equals(n, ignoreCase = true) }
    }

    /** checkBarcodeDuplicate: товар с таким же EAN, кроме редактируемого. */
    fun duplicateOf(ean: String, excludeId: Long): CatItem? {
        if (ean.isEmpty()) return null
        return items.firstOrNull { it.id != excludeId && it.ean == ean }
    }

    // ---------- изменения ----------

    fun update(ctx: Context, id: Long, name: String, ean: String, artikel: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = items[i].name
        items[i] = items[i].copy(name = name, ean = ean, artikel = artikel)
        save(ctx)
        if (!old.equals(name, ignoreCase = true)) ArrivalStore.rename(ctx, old, name)
    }

    /** «Стереть весь каталог»: история приёмок не затрагивается. */
    fun clear(ctx: Context) {
        items.clear()
        save(ctx)
    }

    fun remove(ctx: Context, id: Long) {
        val name = items.firstOrNull { it.id == id }?.name
        items.removeAll { it.id == id }
        save(ctx)
        if (name != null) ArrivalStore.removeByName(ctx, name)
    }

    /**
     * saveLinkTool из HTML: если штрихкод был у другого товара, он у него снимается;
     * существующему товару с тем же названием проставляется штрихкод, иначе создаётся новый.
     */
    fun link(ctx: Context, name: String, ean: String) {
        val other = findByBarcode(ean)
        if (other != null && !other.name.trim().equals(name.trim(), ignoreCase = true)) {
            val j = items.indexOfFirst { it.id == other.id }
            if (j >= 0) items[j] = items[j].copy(ean = "")
        }
        val i = items.indexOfFirst { it.name.trim().equals(name.trim(), ignoreCase = true) }
        if (i < 0) {
            items.add(CatItem(seq.incrementAndGet(), name.trim(), ean, ""))
        } else {
            items[i] = items[i].copy(ean = ean)
        }
        save(ctx)
    }

    // ---------- импорт JSON-бэкапа (processDbImport) ----------

    fun parseBackup(raw: String): CatBackup? = runCatching {
        val root = JSONObject(raw)
        val db = root.optJSONArray("database") ?: return@runCatching null
        val out = ArrayList<Triple<String, String, String>>(db.length())
        for (i in 0 until db.length()) {
            val o = db.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val ean = o.optString("ean").ifEmpty { o.optString("ean2") }.trim()
            out.add(Triple(name, ean, o.optString("artikel").trim()))
        }
        CatBackup(
            products = out,
            arrivals = root.optJSONArray("arrivals") ?: JSONArray(),
            version = root.optString("version"),
            exportedAt = root.optString("exportedAt"),
        )
    }.getOrNull()

    /**
     * Новые товары добавляются; у существующих (по названию) пустые EAN и артикул дописываются.
     * Записи прихода пока только сохраняются в my_off_arr6.json для будущей «Истории»,
     * дубликаты по id отбрасываются. Возвращает (товаров добавлено, записей прихода добавлено).
     */
    suspend fun applyBackup(ctx: Context, b: CatBackup): Pair<Int, Int> {
        val index = HashMap<String, Int>(items.size * 2)
        items.forEachIndexed { i, p -> index[p.name.trim().lowercase()] = i }
        var added = 0
        val fresh = ArrayList<CatItem>()
        b.products.forEach { (name, ean, artikel) ->
            val key = name.lowercase()
            val i = index[key]
            if (i == null) {
                fresh.add(CatItem(seq.incrementAndGet(), name, ean, artikel))
                index[key] = -1
                added++
            } else if (i >= 0) {
                val cur = items[i]
                items[i] = cur.copy(
                    ean = cur.ean.ifEmpty { ean },
                    artikel = cur.artikel.ifEmpty { artikel },
                )
            }
        }
        items.addAll(fresh)
        save(ctx)

        val addedArr = mergeArrivals(ctx, b.arrivals)
        return added to addedArr
    }

    /** Дописывает записи прихода в my_off_arr6.json (дубликаты по id пропускаются). */
    fun mergeArrivals(ctx: Context, incoming: JSONArray): Int = ArrivalStore.merge(ctx, incoming)

    // ---------- импорт из Excel/CSV (processExcelImport) ----------

    class ExcelRow(val name: String, val qty: Int, val artikel: String, val ean: String, val date: String)

    /** Возвращает (позиций импортировано, существующих товаров дополнено). */
    suspend fun importRows(ctx: Context, rows: List<ExcelRow>): Pair<Int, Int> {
        val index = HashMap<String, Int>(items.size * 2)
        items.forEachIndexed { i, p -> index[p.name.trim().lowercase()] = i }
        val batchId = java.util.UUID.randomUUID().toString().take(12)
        val fresh = ArrayList<CatItem>()
        val arrivals = JSONArray()
        var updated = 0
        rows.forEach { r ->
            val key = r.name.lowercase()
            val i = index[key]
            if (i == null) {
                fresh.add(CatItem(seq.incrementAndGet(), r.name, r.ean, r.artikel))
                index[key] = -1
            } else {
                if (i >= 0) {
                    val cur = items[i]
                    items[i] = cur.copy(ean = cur.ean.ifEmpty { r.ean }, artikel = cur.artikel.ifEmpty { r.artikel })
                }
                updated++
            }
            arrivals.put(
                JSONObject()
                    .put("id", java.util.UUID.randomUUID().toString().take(12))
                    .put("batchId", batchId)
                    .put("date", r.date)
                    .put("name", r.name)
                    .put("menge", r.qty),
            )
        }
        items.addAll(fresh)
        save(ctx)
        mergeArrivals(ctx, arrivals)
        return rows.size to updated
    }
}
