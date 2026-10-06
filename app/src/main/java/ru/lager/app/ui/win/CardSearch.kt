package ru.lager.app.ui.win

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Строка результата поиска на главном экране (элемент currentFilteredArrivals в HTML). */
data class SearchRow(
    /** Товар найден только в инвентаризации (в HTML помечается 📦). */
    val inventoryOnly: Boolean,
    val ean: String,
    val name: String,
    val menge: Int,
    val date: String,
    val batchId: String,
    val storageLocations: List<String> = emptyList(),
)

/** activeCard из HTML: выбранный товар / приёмка. */
data class ActiveCard(
    val ean: String = "",
    val artikel: String = "",
    val name: String = "",
    val date: String = "",
    val batchId: String = "",
    val sender: String = "",
    val order: String = "",
    val storageLocation: String = "",
    val storageLocations: List<String> = emptyList(),
)

/** Общее состояние окон «Карточка товара», «Приёмки товара» и «Фильтр по карточке». */
object CardState {
    var card by mutableStateOf(ActiveCard())
    /** date | sender | order | location */
    var filterKind by mutableStateOf("date")

    /**
     * Собирает карточку как openInfoModal: отправитель, заказ и дата берутся из истории приёмок
     * (сначала точное совпадение по партии, названию и дате, затем без даты), адреса — из инвентаризации.
     */
    fun build(ean: String, name: String, date: String, batchId: String): ActiveCard {
        val list = ArrivalStore.items
        val meta = list.firstOrNull {
            it.batchId == batchId && it.name.equals(name, ignoreCase = true) && (date.isEmpty() || it.date == date)
        } ?: list.firstOrNull { it.batchId == batchId && it.name.equals(name, ignoreCase = true) }
        val locations = SearchEngine.inventoryLocations(ean, name)
        return ActiveCard(
            ean = ean,
            artikel = CatalogStore.findByName(name)?.artikel.orEmpty(),
            name = name,
            date = meta?.date?.takeIf { it.isNotEmpty() } ?: date,
            batchId = batchId,
            sender = meta?.sender.orEmpty(),
            order = meta?.order.orEmpty(),
            storageLocation = locations.joinToString(", "),
            storageLocations = locations,
        )
    }
}

private val cardFamily: Set<Win> = setOf(Win.ProductCard, Win.ProductInfo, Win.CardFilter)

/** Открывает окно семейства «карточка»; уже открытые окна этого семейства сверху стека убираются. */
fun WinNav.showCard(w: Win) {
    while (true) {
        val i = entries.indexOfLast { !it.closing }
        if (i < 0 || entries[i].win !in cardFamily) break
        entries.removeAt(i)
    }
    push(w)
}

fun WinNav.openCard(card: ActiveCard) {
    CardState.card = card
    showCard(Win.ProductCard)
}

/** Поиск по главному экрану (handleSearch из HTML). */
object SearchEngine {

    private class InvEntry(val ean: String, val name: String, val qty: Int, val address: String)

    private fun inventoryEntries(): List<InvEntry> = InventoryStore.locations.flatMap { loc ->
        val address = loc.addr.label
        loc.items.filter { it.quantity > 0 }.map {
            InvEntry(CatalogStore.normalizeBarcode(it.barcode), it.name.trim(), it.quantity, address)
        }
    }

    /** searchMatchesTokens: каждое слово запроса должно встретиться в строке. */
    private fun tokensMatch(haystack: String, query: String): Boolean {
        val h = haystack.lowercase()
        val tokens = query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.isEmpty() || tokens.all { h.contains(it) }
    }

    fun search(query: String): List<SearchRow> {
        val q = query.lowercase().trim()
        if (q.length < 3) return emptyList()
        val nbq = if (q.all { it in '0'..'9' }) CatalogStore.normalizeBarcode(q) else ""
        val inv = inventoryEntries()

        val byName = HashMap<String, CatItem>()
        CatalogStore.items.forEach { byName.putIfAbsent(it.name.trim().lowercase(), it) }

        val found = ArrayList<IndexedValue<Arrival>>()
        ArrivalStore.items.forEachIndexed { idx, arr ->
            val db = byName[arr.name.trim().lowercase()]
            val dbEan = db?.ean.orEmpty()
            val dbEanNorm = CatalogStore.normalizeBarcode(dbEan)
            val byBarcode = nbq.isNotEmpty() && dbEanNorm == nbq
            val byInventory = nbq.isNotEmpty() && inv.any {
                it.ean == nbq && (it.name.equals(arr.name.trim(), ignoreCase = true) || dbEanNorm == nbq)
            }
            val byText = tokensMatch("${arr.name} ${arr.date} $dbEan ${db?.artikel.orEmpty()}", q)
            if (byBarcode || byText || byInventory) found.add(IndexedValue(idx, arr))
        }
        // новые сверху; при одинаковой дате выше добавленное позже
        found.sortWith(
            compareByDescending<IndexedValue<Arrival>> { parseHistDate(it.value.date) }.thenByDescending { it.index },
        )
        val rows = found.map {
            SearchRow(
                inventoryOnly = false,
                ean = byName[it.value.name.trim().lowercase()]?.ean.orEmpty(),
                name = it.value.name,
                menge = it.value.menge,
                date = it.value.date,
                batchId = it.value.batchId,
            )
        }

        // Товары, которые есть только в инвентаризации: одна строка на штрихкод, адреса объединяются.
        val inventoryOnly = ArrayList<SearchRow>()
        if (nbq.isNotEmpty()) {
            val seenNames = rows.mapTo(HashSet()) { it.name.trim().lowercase() }
            val grouped = LinkedHashMap<String, SearchRow>()
            inv.filter { it.ean == nbq }.forEach { e ->
                val key = "${e.ean}|${e.name.lowercase()}"
                val prev = grouped[key]
                val locs = (prev?.storageLocations ?: emptyList()).toMutableList()
                if (e.address.isNotEmpty() && e.address !in locs) locs.add(e.address)
                grouped[key] = SearchRow(
                    inventoryOnly = true,
                    ean = e.ean,
                    name = e.name.ifEmpty { e.ean },
                    menge = (prev?.menge ?: 0) + e.qty,
                    date = "",
                    batchId = "",
                    storageLocations = locs,
                )
            }
            grouped.values.forEach { if (it.name.trim().lowercase() !in seenNames) inventoryOnly.add(it) }
        }
        return inventoryOnly + rows
    }

    /** _inventoryProductLocations: адреса хранения, где товар есть в наличии (по штрихкоду или названию). */
    fun inventoryLocations(ean: String, name: String): List<String> {
        val ne = CatalogStore.normalizeBarcode(ean)
        val nn = name.trim().lowercase()
        val out = LinkedHashSet<String>()
        InventoryStore.locations.forEach { loc ->
            val hit = loc.items.any {
                if (it.quantity <= 0) return@any false
                val ib = CatalogStore.normalizeBarcode(it.barcode)
                (ne.isNotEmpty() && ib.isNotEmpty() && ib == ne) ||
                    (nn.isNotEmpty() && it.name.trim().lowercase() == nn)
            }
            if (hit) out.add(loc.addr.label)
        }
        return out.sortedWith(Comparator { a, b -> naturalCompare(a, b) })
    }

    /** Сравнение с числами внутри строки (А2 перед А10), как localeCompare(numeric). */
    fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.toBigInteger()
                val ny = y.toBigInteger()
                nx.compareTo(ny)
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (c != 0) return c
        }
        return ra.size - rb.size
    }
}
