package ru.lager.app.ui.win

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Адрес хранения: склад · ряд · этаж · полка. «--» — часть не задана. */
data class InvAddr(val warehouse: String, val row: String, val floor: String, val shelf: String) {
    /** _inventoryLocationLabel: «--» и пустые части не выводятся. */
    val label: String
        get() = listOf(warehouse, row, floor, shelf).map { it.trim() }
            .filter { it.isNotEmpty() && !it.all { c -> c == '-' } }.joinToString("-")
    val key: String get() = "$warehouse|$row|$floor|$shelf"
}

data class InvItem(
    val key: String,
    val barcode: String,
    val name: String,
    val quantity: Int,
    val lastSeen: Long,
    val eventCount: Int,
    val legacy: Int,
    val expiry: String = "",
    val expiryMode: String = "",
)

data class InvLoc(val addr: InvAddr, val updatedAt: Long, val items: List<InvItem>)

data class InvEvent(val ts: Long, val addr: InvAddr, val barcode: String, val name: String, val expiry: String = "")

class InvAddResult(val name: String, val barcode: String, val known: Boolean)

/**
 * Инвентаризация. Хранится в тех же форматах, что и в HTML
 * (bi_inventory_locations_v1 / bi_inventory_scan_events_v1 / bi_inventory_current_location_v1).
 */
object InventoryStore {
    const val MAX_QTY = 99999
    const val UNKNOWN_NAME = "Не найдено в каталоге"

    val locations = mutableStateListOf<InvLoc>()
    private val events = ArrayList<InvEvent>()
    var current by mutableStateOf<InvAddr?>(null)
        private set

    // Черновик адреса в четырёх кнопках (склад/ряд/этаж/полка)
    var dWarehouse by mutableStateOf("")
    var dRow by mutableStateOf("--")
    var dFloor by mutableStateOf("--")
    var dShelf by mutableStateOf("--")

    // ---------- срок годности (bi_inventory_expiry_settings_v1) ----------

    val MONTHS = listOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
    )
    const val MIN_YEAR = 2016
    const val MAX_YEAR = 2036

    var expiryEnabled by mutableStateOf(false)
    var expiryMode by mutableStateOf("day")
    var expiryYear by mutableStateOf(java.time.LocalDate.now().year.coerceIn(MIN_YEAR, MAX_YEAR))
    var expiryMonth by mutableStateOf(java.time.LocalDate.now().monthValue)
    var expiryDay by mutableStateOf(java.time.LocalDate.now().dayOfMonth)

    fun daysIn(year: Int, month: Int): Int =
        java.time.YearMonth.of(year.coerceIn(MIN_YEAR, MAX_YEAR), month.coerceIn(1, 12)).lengthOfMonth()

    /** «ГГГГ», «ГГГГ-ММ» или «ГГГГ-ММ-ДД» — так срок хранится и выгружается (как в HTML). */
    fun expiryString(mode: String, y: Int, m: Int, d: Int): String {
        val yy = y.coerceIn(MIN_YEAR, MAX_YEAR)
        val mm = m.coerceIn(1, 12)
        val dd = d.coerceIn(1, daysIn(yy, mm))
        return when (mode) {
            "day" -> String.format(Locale.US, "%04d-%02d-%02d", yy, mm, dd)
            "month" -> String.format(Locale.US, "%04d-%02d", yy, mm)
            else -> yy.toString()
        }
    }

    fun expiryValue(): String = expiryString(expiryMode, expiryYear, expiryMonth, expiryDay)

    /** Разбор сохранённого срока: (режим, год, месяц, день) или null. */
    fun parseExpiry(v: String): List<Any>? {
        val m = Regex("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?$").find(v.trim()) ?: return null
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toIntOrNull()
        val d = m.groupValues[3].toIntOrNull()
        val mode = if (d != null) "day" else if (mo != null) "month" else "year"
        return listOf(mode, y, mo ?: 1, d ?: 1)
    }

    fun expiryLabelOf(v: String): String {
        val p = parseExpiry(v) ?: return v
        val y = p[1] as Int
        val mo = p[2] as Int
        val d = p[3] as Int
        return when (p[0] as String) {
            "day" -> String.format(Locale.US, "%02d.%02d.%04d", d, mo, y)
            "month" -> String.format(Locale.US, "%02d. %s %04d", mo, MONTHS[(mo - 1).coerceIn(0, 11)], y)
            else -> "$y г."
        }
    }

    fun expiryModeOf(v: String): String = (parseExpiry(v)?.get(0) as? String) ?: ""

    /** Срок из Excel/текста: ГГГГ, ГГГГ-ММ, ГГГГ-ММ-ДД, ДД.ММ.ГГГГ, ММ.ГГГГ, ММ/ГГГГ. Пусто — не распознан. */
    fun normalizeExpiry(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        if (parseExpiry(t) != null) return t
        Regex("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})$").find(t)?.let {
            val d = it.groupValues[1].toInt()
            val mo = it.groupValues[2].toInt()
            val y = it.groupValues[3].toInt()
            if (y in MIN_YEAR..MAX_YEAR && mo in 1..12 && d in 1..daysIn(y, mo)) return expiryString("day", y, mo, d)
        }
        Regex("^(\\d{1,2})[./-](\\d{4})$").find(t)?.let {
            val mo = it.groupValues[1].toInt()
            val y = it.groupValues[2].toInt()
            if (y in MIN_YEAR..MAX_YEAR && mo in 1..12) return expiryString("month", y, mo, 1)
        }
        return ""
    }

    private fun loadExpiry(ctx: Context) {
        runCatching {
            val o = JSONObject(SettingsStore.str(ctx, "invExpiry", "{}"))
            expiryEnabled = o.optBoolean("enabled", false)
            expiryMode = o.optString("mode").takeIf { it in listOf("year", "month", "day") } ?: "day"
            expiryYear = o.optInt("year", expiryYear).coerceIn(MIN_YEAR, MAX_YEAR)
            expiryMonth = o.optInt("month", expiryMonth).coerceIn(1, 12)
            expiryDay = o.optInt("day", expiryDay).coerceIn(1, daysIn(expiryYear, expiryMonth))
        }
    }

    fun saveExpiry(ctx: Context) {
        SettingsStore.putStr(
            ctx, "invExpiry",
            JSONObject().put("enabled", expiryEnabled).put("mode", expiryMode).put("year", expiryYear)
                .put("month", expiryMonth).put("day", expiryDay).toString(),
        )
    }

    private var loaded = false
    private val io = Executors.newSingleThreadExecutor()
    private fun f(ctx: Context, n: String) = File(ctx.applicationContext.filesDir, n)

    // ---------- адреса ----------

    fun normalizePart(value: String, warehouse: Boolean): String? {
        val raw = value.trim()
        if (warehouse) return raw.ifEmpty { null }
        if (raw == "--" || raw == "—" || raw == "–") return "--"
        if (!Regex("^\\d{1,2}$").matches(raw)) return null
        val n = raw.toInt()
        return if (n in 1..99) n.toString().padStart(2, '0') else null
    }

    fun build(w: String, r: String, fl: String, s: String): InvAddr? {
        val nw = normalizePart(w, true) ?: return null
        val nr = normalizePart(r, false) ?: return null
        val nf = normalizePart(fl, false) ?: return null
        val ns = normalizePart(s, false) ?: return null
        return InvAddr(nw, nr, nf, ns)
    }

    /** _parseInventoryAddress: «СКЛАД-РЯД01-ЭТАЖ02-ПОЛКА03», «палатка-01-02-03», «палатка-01----». */
    fun parseAddress(value: String): InvAddr? {
        var raw = value.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("{")) {
            runCatching {
                val o = JSONObject(raw)
                return build(o.optString("warehouse"), o.optString("row"), o.optString("floor"), o.optString("shelf"))
            }
        }
        raw = raw.replace(Regex("[–—−]"), "-").replace(Regex("\\s+"), " ").trim()
        Regex("^(?:склад)?(.+?)-(?:ряд)?(\\d{1,2}|--)-(?:этаж)?(\\d{1,2}|--)-(?:полка)?(\\d{1,2}|--)$", RegexOption.IGNORE_CASE)
            .find(raw)?.let { return build(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4]) }
        Regex("^(.+?)-(\\d{1,2})-+$").find(raw.replace(Regex("\\s+"), ""))
            ?.let { return build(it.groupValues[1], it.groupValues[2], "--", "--") }
        return null
    }

    private fun syncDraft(a: InvAddr?) {
        dWarehouse = a?.warehouse.orEmpty()
        dRow = a?.row ?: "--"
        dFloor = a?.floor ?: "--"
        dShelf = a?.shelf ?: "--"
    }

    fun setCurrent(ctx: Context, a: InvAddr?) {
        current = a
        syncDraft(a)
        saveCurrent(ctx)
    }

    /** Выбрана одна часть адреса. Когда заполнены все четыре — адрес становится текущим. */
    fun setPart(ctx: Context, part: String, value: String): Boolean {
        val n = normalizePart(value, part == "warehouse") ?: return false
        when (part) { "warehouse" -> dWarehouse = n; "row" -> dRow = n; "floor" -> dFloor = n; "shelf" -> dShelf = n }
        build(dWarehouse, dRow, dFloor, dShelf)?.let { current = it; saveCurrent(ctx) }
        return true
    }

    fun knownWarehouses(): List<String> = locations.map { it.addr.warehouse }.distinct().sorted()

    // ---------- загрузка / сохранение ----------

    suspend fun load(ctx: Context) {
        if (loaded) return
        val res = withContext(Dispatchers.IO) { readAll(ctx) }
        if (loaded) return
        loaded = true
        loadExpiry(ctx)
        locations.addAll(res.first)
        events.addAll(res.second)
        current = res.third
        syncDraft(res.third)
    }

    private fun readAll(ctx: Context): Triple<List<InvLoc>, List<InvEvent>, InvAddr?> {
        val locs = ArrayList<InvLoc>()
        runCatching {
            val file = f(ctx, "bi_inventory_locations_v1.json")
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.keys().forEach { k ->
                    val lo = o.optJSONObject(k) ?: return@forEach
                    val addr = build(lo.optString("warehouse"), lo.optString("row"), lo.optString("floor").ifEmpty { "--" }, lo.optString("shelf"))
                        ?: return@forEach
                    val items = ArrayList<InvItem>()
                    lo.optJSONObject("items")?.let { itemsObj ->
                        itemsObj.keys().forEach { ik ->
                            val it = itemsObj.optJSONObject(ik) ?: return@forEach
                            val q = it.optInt("quantity", 0)
                            if (q > 0) items.add(
                                InvItem(
                                    ik, it.optString("barcode"), it.optString("name").ifEmpty { "—" }, q,
                                    it.optLong("lastSeen"), it.optInt("eventCount", 0),
                                    if (it.has("eventCount")) it.optInt("legacyQuantity", 0) else q,
                                    it.optString("expiry"), it.optString("expiryMode"),
                                ),
                            )
                        }
                    }
                    locs.add(InvLoc(addr, lo.optLong("updatedAt"), items))
                }
            }
        }
        val evs = ArrayList<InvEvent>()
        runCatching {
            val file = f(ctx, "bi_inventory_scan_events_v1.json")
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val addr = build(e.optString("warehouse"), e.optString("row"), e.optString("floor").ifEmpty { "--" }, e.optString("shelf"))
                        ?: InvAddr(e.optString("warehouse"), e.optString("row"), e.optString("floor"), e.optString("shelf"))
                    evs.add(InvEvent(e.optLong("timestamp"), addr, e.optString("barcode"), e.optString("name"), e.optString("expiry")))
                }
            }
        }
        val cur = runCatching {
            val file = f(ctx, "bi_inventory_current_location_v1.json")
            if (file.exists()) parseAddress(file.readText()) else null
        }.getOrNull()
        return Triple(locs, evs, cur)
    }

    private fun locationsJson(): String {
        val root = JSONObject()
        locations.forEach { l ->
            val items = JSONObject()
            l.items.forEach {
                items.put(
                    it.key,
                    JSONObject().put("barcode", it.barcode).put("name", it.name).put("quantity", it.quantity)
                        .put("lastSeen", it.lastSeen).put("eventCount", it.eventCount).put("legacyQuantity", it.legacy)
                        .put("expiry", it.expiry).put("expiryMode", it.expiryMode),
                )
            }
            root.put(
                l.addr.key,
                JSONObject().put("warehouse", l.addr.warehouse).put("row", l.addr.row).put("floor", l.addr.floor)
                    .put("shelf", l.addr.shelf).put("updatedAt", l.updatedAt).put("items", items),
            )
        }
        return root.toString()
    }

    private fun eventsJson(): String {
        val arr = JSONArray()
        events.takeLast(100000).forEach {
            arr.put(
                JSONObject().put("timestamp", it.ts).put("address", it.addr.label).put("warehouse", it.addr.warehouse)
                    .put("row", it.addr.row).put("floor", it.addr.floor).put("shelf", it.addr.shelf)
                    .put("barcode", it.barcode).put("name", it.name)
                    .put("expiry", it.expiry).put("expiryMode", expiryModeOf(it.expiry)),
            )
        }
        return arr.toString()
    }

    private fun write(file: File, text: String) = io.execute {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
        }
    }

    private fun saveLocations(ctx: Context) = write(f(ctx, "bi_inventory_locations_v1.json"), locationsJson())
    private fun saveEvents(ctx: Context) = write(f(ctx, "bi_inventory_scan_events_v1.json"), eventsJson())
    private fun saveCurrent(ctx: Context) {
        val file = f(ctx, "bi_inventory_current_location_v1.json")
        val c = current
        if (c == null) io.execute { file.delete() } else {
            write(file, JSONObject().put("warehouse", c.warehouse).put("row", c.row).put("floor", c.floor).put("shelf", c.shelf).toString())
        }
    }

    // ---------- операции ----------

    private fun upsert(addr: InvAddr, now: Long, transform: (InvLoc) -> InvLoc) {
        val i = locations.indexOfFirst { it.addr.key == addr.key }
        if (i >= 0) locations[i] = transform(locations[i]).copy(updatedAt = now)
        else locations.add(transform(InvLoc(addr, now, emptyList())).copy(updatedAt = now))
    }

    /** expiry == null — срок не учитывается; иначе к ключу добавляется |EXP:<срок> (inventoryItemKey в HTML). */
    private fun itemKey(barcode: String, name: String, expiry: String? = null): String {
        val base = if (barcode.isNotEmpty()) "EAN:$barcode" else "NAME:${name.trim().lowercase(Locale.ROOT)}"
        return if (expiry == null) base else "$base|EXP:${expiry.ifEmpty { "без-срока" }}"
    }

    /** addInventoryProductValue: +1 к товару на текущем месте и запись события сканирования. null — нет места. */
    fun addProduct(ctx: Context, raw: String): InvAddResult? {
        val addr = current ?: return null
        val value = raw.trim()
        if (value.isEmpty()) return null
        val product = CatalogStore.findByBarcode(value) ?: CatalogStore.findByName(value)
        val normalized = CatalogStore.normalizeBarcode(value)
        val barcode = when {
            product != null && product.ean.isNotBlank() -> CatalogStore.normalizeBarcode(product.ean)
            Regex("^\\d{3,}$").matches(value) -> normalized
            else -> ""
        }
        val name = product?.name ?: if (barcode.isNotEmpty()) UNKNOWN_NAME else value
        val expiry = if (expiryEnabled) expiryValue() else ""
        val key = itemKey(barcode, name, if (expiryEnabled) expiry else null)
        val now = System.currentTimeMillis()
        upsert(addr, now) { loc ->
            val old = loc.items.firstOrNull { it.key == key }
            val updated = InvItem(
                key, barcode.ifEmpty { old?.barcode.orEmpty() }, name,
                minOf(MAX_QTY, (old?.quantity ?: 0) + 1), now, (old?.eventCount ?: 0) + 1, old?.legacy ?: 0,
                expiry, if (expiryEnabled) expiryMode else "",
            )
            loc.copy(items = loc.items.filter { it.key != key } + updated)
        }
        events.add(InvEvent(now, addr, barcode, name, expiry))
        saveLocations(ctx); saveEvents(ctx)
        return InvAddResult(name, barcode, product != null)
    }

    /** Импорт из Excel: количество добавляется к существующему товару (как «объединение» в HTML). */
    fun addQuantity(ctx: Context, addr: InvAddr, barcode: String, name: String, qty: Int, expiry: String = "") {
        if (qty <= 0) return
        val bc = CatalogStore.normalizeBarcode(barcode)
        val nm = name.ifBlank { if (bc.isNotEmpty()) (CatalogStore.findByBarcode(bc)?.name ?: UNKNOWN_NAME) else return }
        val key = itemKey(bc, nm, expiry.ifEmpty { null })
        val now = System.currentTimeMillis()
        upsert(addr, now) { loc ->
            val old = loc.items.firstOrNull { it.key == key }
            val updated = InvItem(
                key, bc.ifEmpty { old?.barcode.orEmpty() }, nm,
                minOf(MAX_QTY, (old?.quantity ?: 0) + qty), now, old?.eventCount ?: 0, (old?.legacy ?: 0) + qty,
                expiry, expiryModeOf(expiry),
            )
            loc.copy(items = loc.items.filter { it.key != key } + updated)
        }
    }

    fun commitImport(ctx: Context) { saveLocations(ctx) }

    /** Ручная правка количества (0 — удалить позицию). Разница относится к «прежним» остаткам, события не трогаем. */
    fun setQuantity(ctx: Context, addr: InvAddr, itemKey: String, qty: Int) {
        val q = qty.coerceIn(0, MAX_QTY)
        val now = System.currentTimeMillis()
        upsert(addr, now) { loc ->
            loc.copy(
                items = loc.items.mapNotNull {
                    if (it.key != itemKey) it
                    else if (q == 0) null
                    else it.copy(quantity = q, lastSeen = now, legacy = maxOf(0, it.legacy + (q - it.quantity)))
                },
            )
        }
        saveLocations(ctx)
    }

    /**
     * Меняет срок годности позиции (applyInventoryExpiryToLastScanned в HTML): ключ позиции пересчитывается,
     * а если на месте уже есть такая же позиция с этим сроком — количества объединяются.
     */
    fun setItemExpiry(ctx: Context, addr: InvAddr, itemKey: String, expiry: String) {
        val i = locations.indexOfFirst { it.addr.key == addr.key }
        if (i < 0) return
        val loc = locations[i]
        val item = loc.items.firstOrNull { it.key == itemKey } ?: return
        val now = System.currentTimeMillis()
        val base = item.key.substringBefore("|EXP:")
        val newKey = if (expiry.isEmpty()) base else "$base|EXP:$expiry"
        val target = loc.items.firstOrNull { it.key == newKey && it.key != item.key }
        val merged = if (target != null) {
            target.copy(
                quantity = minOf(MAX_QTY, target.quantity + item.quantity),
                lastSeen = maxOf(target.lastSeen, item.lastSeen),
                eventCount = target.eventCount + item.eventCount,
                legacy = target.legacy + item.legacy,
                name = if (target.name == UNKNOWN_NAME) item.name else target.name,
            )
        } else {
            item.copy(key = newKey, expiry = expiry, expiryMode = expiryModeOf(expiry), lastSeen = now)
        }
        locations[i] = loc.copy(
            items = loc.items.filter { it.key != item.key && it.key != newKey } + merged,
            updatedAt = now,
        )
        for (j in events.indices.reversed()) {
            val e = events[j]
            if (e.addr.key == addr.key && e.barcode == item.barcode && e.name == item.name) {
                events[j] = e.copy(expiry = expiry)
                break
            }
        }
        saveLocations(ctx); saveEvents(ctx)
    }

    fun deleteLocation(ctx: Context, addr: InvAddr) {
        locations.removeAll { it.addr.key == addr.key }
        saveLocations(ctx)
    }

    /** Названия новому товару: обновляет позиции с этим штрихкодом и каталог. */
    fun nameBarcode(ctx: Context, barcode: String, name: String) {
        val n = name.trim()
        if (n.isEmpty() || barcode.isEmpty()) return
        CatalogStore.ensure(ctx, n, barcode)
        for (i in locations.indices) {
            val loc = locations[i]
            if (loc.items.any { it.barcode == barcode && it.name == UNKNOWN_NAME }) {
                locations[i] = loc.copy(items = loc.items.map { if (it.barcode == barcode && it.name == UNKNOWN_NAME) it.copy(name = n) else it })
            }
        }
        for (i in events.indices) if (events[i].barcode == barcode && events[i].name == UNKNOWN_NAME) events[i] = events[i].copy(name = n)
        saveLocations(ctx); saveEvents(ctx)
    }

    // ---------- экспорт ----------

    private fun day(ts: Long) = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(ts))
    private fun dateTime(ts: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date(ts))

    /** Даты сканирования (новые сверху) и число сканирований за дату. */
    fun exportDates(): List<Pair<String, Int>> {
        val m = LinkedHashMap<String, Int>()
        events.forEach { if (it.ts > 0) m.merge(day(it.ts), 1, Int::plus) }
        locations.forEach { l -> l.items.forEach { if (it.legacy > 0 && it.lastSeen > 0) m.merge(day(it.lastSeen), 1, Int::plus) } }
        return m.toList().sortedByDescending { parseHistDate(it.first) }
    }

    private class Sum(val addr: InvAddr, val barcode: String, val name: String, val expiry: String, var qty: Int, var first: Long, var last: Long)

    /** Возвращает null, если за выбранные даты нет данных. date == null — все даты. */
    fun buildExport(dates: Set<String>?): ByteArray? {
        fun inRange(ts: Long) = dates == null || day(ts) in dates
        val sums = LinkedHashMap<String, Sum>()
        fun add(addr: InvAddr, barcode: String, name: String, expiry: String, q: Int, ts: Long) {
            val k = "${addr.label}|$barcode|$name|$expiry"
            val s = sums[k]
            if (s == null) sums[k] = Sum(addr, barcode, name, expiry, q, ts, ts)
            else { s.qty += q; s.first = minOf(s.first, ts); s.last = maxOf(s.last, ts) }
        }
        val evs = events.filter { inRange(it.ts) }.sortedBy { it.ts }
        evs.forEach { add(it.addr, it.barcode, it.name, it.expiry, 1, it.ts) }
        locations.forEach { l -> l.items.forEach { if (it.legacy > 0 && inRange(it.lastSeen)) add(l.addr, it.barcode, it.name, it.expiry, it.legacy, it.lastSeen) } }
        val rows = sums.values.filter { it.qty > 0 }.sortedWith(compareBy({ it.addr.label }, { it.name }))
        if (rows.isEmpty()) return null
        val summary = ArrayList<List<String>>()
        summary.add(listOf("Дата сканирования", "Склад", "Ряд", "Этаж", "Полка", "Адрес", "Штрихкод", "Товар", "Срок годности", "Количество", "Первое сканирование", "Последнее сканирование"))
        rows.forEach {
            summary.add(
                listOf(
                    day(it.first), it.addr.warehouse, it.addr.row, it.addr.floor, it.addr.shelf, it.addr.label,
                    it.barcode, it.name, it.expiry, it.qty.toString(), dateTime(it.first), dateTime(it.last),
                ),
            )
        }
        val sheets = arrayListOf<Pair<String, List<List<String>>>>("Инвентаризация" to summary)
        if (evs.isNotEmpty()) {
            val ev = ArrayList<List<String>>()
            ev.add(listOf("Дата и время", "Адрес", "Штрихкод", "Товар", "Срок годности"))
            evs.forEach { ev.add(listOf(dateTime(it.ts), it.addr.label, it.barcode, it.name, it.expiry)) }
            sheets.add("Сканирования" to ev)
        }
        return ExportHelper.xlsx(sheets)
    }
}
