package ru.lager.app.ui.win

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.lager.app.ui.BarcodeScanIcon

// =====================================================================
//  Приём по заданию: план → сканирование → сверка (BARCODE CHECKER в HTML)
// =====================================================================

data class PlanItem(val name: String, val qty: Int, val ean: String)

class ScanRow(val code: String, val qty: Int)

/** type: match | mismatch | extra | less */
class PlanResultRow(
    val type: String,
    val code: String,
    val name: String,
    val actual: Int,
    val expected: Int,
    val plannedEan: String,
    val found: CatItem?,
)

/** Состояние одного прохода «задание → скан → сверка». Живёт, пока открыты окна. */
object PlanStore {
    val items = mutableStateListOf<PlanItem>()
    val scans = mutableStateListOf<ScanRow>()
    val results = mutableStateListOf<PlanResultRow>()
    var status by mutableStateOf("")

    const val SCAN_HINT = "Нажмите кнопку сканирования, чтобы считать штрихкод из локальной базы."

    fun reset() {
        items.clear(); scans.clear(); results.clear(); status = SCAN_HINT
    }

    fun setItems(list: List<PlanItem>) {
        items.clear(); items.addAll(list)
    }

    private fun clean(v: String): String =
        v.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** _parseBarcodePlan: «название | кол-во | EAN», либо «название 5», либо EAN где-то в строке. */
    fun parse(text: String): List<PlanItem> =
        text.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val cols = line.split(Regex("[|;\\t]+")).map { it.trim() }.filter { it.isNotEmpty() }
            var name = cols.getOrNull(0).orEmpty()
            var qty = cols.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            var ean = CatalogStore.normalizeBarcode(cols.getOrNull(2).orEmpty())
            if (qty == 0) {
                Regex("\\s+(\\d+)\\s*$").find(name)?.let {
                    qty = it.groupValues[1].toIntOrNull()?.takeIf { n -> n > 0 } ?: 1
                    name = name.substring(0, it.range.first).trim()
                }
            }
            if (ean.isEmpty()) {
                Regex("\\b\\d{8,14}\\b").find(line)?.let { ean = CatalogStore.normalizeBarcode(it.value) }
            }
            if (name.isEmpty()) null else PlanItem(name, qty.coerceAtLeast(1), ean)
        }

    fun qtyOf(code: String): Int = scans.firstOrNull { it.code == code }?.qty ?: 0

    fun setQty(code: String, qty: Int) {
        val q = qty.coerceIn(1, 99999)
        val i = scans.indexOfFirst { it.code == code }
        if (i >= 0) scans[i] = ScanRow(code, q) else scans.add(ScanRow(code, q))
    }

    fun removeScan(code: String) { scans.removeAll { it.code == code } }

    /** addBarcodeScan: +1 к количеству, статус как в HTML. */
    fun addScan(raw: String): Boolean {
        val code = CatalogStore.normalizeBarcode(raw)
        if (code.isEmpty()) {
            status = "Не удалось прочитать штрихкод."
            return false
        }
        val local = CatalogStore.findByBarcode(code)
        setQty(code, qtyOf(code) + 1)
        val expected = items.firstOrNull { CatalogStore.normalizeBarcode(it.ean) == code }?.qty
        status = when {
            local == null -> "Штрихкод $code не найден в локальной базе — он будет отмечен в сверке."
            expected != null -> "Считано ${qtyOf(code)} из $expected шт. для этого товара."
            else -> "Штрихкод $code найден в базе: ${local.name}."
        }
        return true
    }

    /** buildBarcodeCheckResults */
    fun buildResults(): List<PlanResultRow> {
        val codes = scans.map { it.code }
        val used = HashSet<String>()
        val out = ArrayList<PlanResultRow>()
        items.forEach { p ->
            var code = CatalogStore.normalizeBarcode(p.ean)
            var found = if (code.isNotEmpty()) CatalogStore.findByBarcode(code) else null
            if (code.isEmpty()) {
                val cand = codes.firstOrNull { sc ->
                    sc !in used && CatalogStore.findByBarcode(sc)?.let { clean(it.name) == clean(p.name) } == true
                }
                if (cand != null) { code = cand; found = CatalogStore.findByBarcode(cand) }
            }
            val actual = if (code.isNotEmpty()) qtyOf(code) else 0
            if (code.isNotEmpty() && actual > 0) used.add(code)
            val nameMatches = found != null && clean(p.name) == clean(found.name)
            val type = when {
                actual > 0 && (found == null || !nameMatches) -> "mismatch"
                actual > p.qty -> "extra"
                actual == p.qty && found != null && nameMatches -> "match"
                else -> "less"
            }
            out.add(PlanResultRow(type, code, found?.name ?: p.name, actual, p.qty, p.ean, found))
        }
        codes.forEach { code ->
            if (code in used) return@forEach
            val found = CatalogStore.findByBarcode(code)
            out.add(PlanResultRow("extra", code, found?.name ?: "Не найдено в локальной базе", qtyOf(code), 0, "", found))
        }
        return out
    }

    /** normalizeBarcodeOrderNumber: «123» / «EB123» / «#123» → «EB123». */
    fun normalizeOrder(value: String): String {
        var raw = value.trim().uppercase().replace(Regex("\\s+"), "")
        raw = raw.replace(Regex("^(?:EB)+"), "").replace(Regex("^[-:#]+"), "")
        return if (raw.isEmpty()) "" else "EB$raw"
    }
}

private val PlanGreen = Color(0xFF2B8A3E)
private val PlanRed = Color(0xFFC92A2A)
private val PlanOrange = Color(0xFFE8590C)
private val PlanYellow = Color(0xFFF08C00)

private fun planTypeColor(type: String): Color = when (type) {
    "match" -> PlanGreen
    "mismatch" -> PlanRed
    "extra" -> PlanOrange
    else -> PlanYellow
}

@Composable
private fun PlanCardRow(accent: Color? = null, content: @Composable RowScope.() -> Unit) {
    val c = Md3.c
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (accent != null) {
            Box(Modifier.width(5.dp).height(38.dp).clip(RoundedCornerShape(3.dp)).background(accent))
        }
        content()
    }
}

@Composable
private fun RowDeleteButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(PlanRed.copy(alpha = 0.12f))
            .md3Clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("×", color = PlanRed, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

// ---------- 1. Задание ----------

@Composable
fun PlanWindow(env: WinEnv) {
    val ctx = LocalContext.current
    val c = Md3.c
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        CatalogStore.load(ctx)
        PlanStore.reset()
    }

    fun startSession() {
        if (PlanStore.items.isEmpty()) {
            env.info("Сначала вставьте список товаров.")
            return
        }
        PlanStore.scans.clear()
        PlanStore.results.clear()
        PlanStore.status = PlanStore.SCAN_HINT
        env.nav.push(Win.PlanSession)
    }

    fun paste() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString() else ""
        if (t.isBlank()) {
            env.info("Буфер обмена пуст.")
        } else {
            PlanStore.setItems(PlanStore.parse(t))
            text = ""
            env.info("Вставлено позиций: ${PlanStore.items.size}")
        }
    }

    WindowScaffold(
        title = "Приём по заданию",
        actions = {
            TopBarTextButton("Вставить") { paste() }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .md3Clickable(color = Color.White) { startSession() },
                contentAlignment = Alignment.Center,
            ) { BarcodeScanIcon(Color.White, Modifier.size(24.dp)) }
        },
        footer = { LongButton("📷 Сканировать товары", LongKind.Blue, { startSession() }) },
    ) {
        ScrollBody {
            LabeledInput(
                "", text,
                { text = it; PlanStore.setItems(PlanStore.parse(it)) },
                placeholder = "Добавить задачу",
                singleLine = false,
                minHeight = 110,
            )
            Spacer(Modifier.height(10.dp))
            if (PlanStore.items.isEmpty()) {
                EmptyHint("Список пока пуст.")
            } else {
                PlanStore.items.toList().forEachIndexed { index, item ->
                    PlanCardRow {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (item.ean.isNotEmpty()) "Штрихкод: ${item.ean}" else "Штрихкод не указан",
                                fontSize = 12.sp,
                                color = c.onSurfaceVariant,
                            )
                        }
                        Text("${item.qty} шт.", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                        RowDeleteButton { if (index < PlanStore.items.size) PlanStore.items.removeAt(index) }
                    }
                }
            }
        }
    }
}

// ---------- 2. Сканирование ----------

@Composable
private fun QtyButton(label: String, onClick: () -> Unit) {
    val c = Md3.c
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(c.surfaceLow)
            .border(1.dp, c.outlineVariant, CircleShape)
            .md3Clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun PlanSessionWindow(env: WinEnv) {
    val ctx = LocalContext.current
    val c = Md3.c
    var scanning by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { CatalogStore.load(ctx) }

    fun finish() {
        PlanStore.results.clear()
        PlanStore.results.addAll(PlanStore.buildResults())
        PlanStore.status = "Сверка завершена."
        env.nav.push(Win.PlanResult)
    }

    WindowScaffold(
        title = "Приём по заданию",
        actions = {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .md3Clickable(color = Color.White) { scanning = true },
                contentAlignment = Alignment.Center,
            ) { BarcodeScanIcon(Color.White, Modifier.size(24.dp)) }
            TopBarTextButton("Далее") { finish() }
        },
        footer = {
            LongButton("📷 Сканировать", LongKind.Blue, { scanning = true })
            LongButton("Далее", LongKind.Green, { finish() })
        },
    ) {
        ScrollBody {
            if (PlanStore.scans.isEmpty()) {
                EmptyHint("Список отсканированных товаров пуст")
            } else {
                PlanStore.scans.toList().forEach { row ->
                    val planned = PlanStore.items.firstOrNull { CatalogStore.normalizeBarcode(it.ean) == row.code }
                    val local = CatalogStore.findByBarcode(row.code)
                    val name = local?.name ?: planned?.name ?: "Товар по штрихкоду"
                    PlanCardRow {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("Штрихкод: ${row.code}", fontSize = 12.sp, color = c.onSurfaceVariant)
                        }
                        QtyButton("−") {
                            if (row.qty > 1) {
                                PlanStore.setQty(row.code, row.qty - 1)
                                PlanStore.status = planned?.let { "Количество изменено: ${row.qty - 1} из ${it.qty} шт." }
                                    ?: "Количество для товара изменено: ${row.qty - 1} шт."
                            }
                        }
                        Text("${row.qty}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp))
                        QtyButton("+") {
                            PlanStore.setQty(row.code, row.qty + 1)
                            PlanStore.status = planned?.let { "Количество изменено: ${row.qty + 1} из ${it.qty} шт." }
                                ?: "Количество для товара изменено: ${row.qty + 1} шт."
                        }
                        RowDeleteButton {
                            PlanStore.removeScan(row.code)
                            PlanStore.status = "Строка удалена: ${row.code}."
                        }
                    }
                }
            }
            Text(
                PlanStore.status,
                color = c.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

    if (scanning) {
        BarcodeScannerDialog(
            onResult = { code -> scanning = false; PlanStore.addScan(code) },
            onDismiss = { scanning = false },
        )
    }
}

// ---------- 3. Результат сверки ----------

@Composable
fun PlanResultWindow(env: WinEnv) {
    val ctx = LocalContext.current
    val c = Md3.c
    var askOrder by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun save() {
        val orderNo = PlanStore.normalizeOrder(order)
        if (orderNo.isEmpty()) { error = "Введите номер заказа."; return }
        val rows = PlanStore.results.filter { it.actual > 0 && it.found != null }
        if (rows.isEmpty()) { error = "Нет отсканированных товаров из локальной базы для сохранения."; return }
        val batchId = "barcode_" + java.util.UUID.randomUUID().toString().take(12)
        val date = java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.US).format(java.util.Date())
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        ArrivalStore.addAll(
            ctx,
            rows.map {
                Arrival(
                    id = java.util.UUID.randomUUID().toString().take(12),
                    batchId = batchId,
                    date = date,
                    name = it.found!!.name,
                    menge = it.actual,
                    sender = "",
                    order = orderNo,
                    source = "barcode scanner",
                    receivedAt = stamp,
                )
            },
        )
        env.info("Принято: ${rows.size} поз., ${rows.sumOf { it.actual }} шт. · $orderNo")
        askOrder = false
        repeat(3) { env.nav.pop() }
    }

    val open = { order = ""; error = ""; askOrder = true }
    WindowScaffold(
        title = "Результат сверки",
        actions = { TopBarTextButton("💾") { open() } },
        footer = { LongButton("Сохранить", LongKind.Green, { open() }) },
    ) {
        ScrollBody {
            val labels = listOf("match" to "Совпало", "mismatch" to "Не совпало", "extra" to "Лишнее", "less" to "Мало")
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { (type, label) ->
                    val col = planTypeColor(type)
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(col.copy(alpha = 0.14f))
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${PlanStore.results.count { it.type == type }}", color = col, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text(label, color = col, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (PlanStore.results.isEmpty()) {
                EmptyHint("Список для сверки пуст.")
            } else {
                PlanStore.results.toList().forEach { r ->
                    val col = planTypeColor(r.type)
                    val scanCol = when {
                        r.actual == 0 -> c.onSurfaceVariant
                        r.type == "match" -> PlanGreen
                        r.type == "less" -> PlanYellow
                        else -> PlanRed
                    }
                    PlanCardRow(accent = col) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(
                                r.code.ifEmpty { r.plannedEan.ifEmpty { "—" } },
                                fontSize = 12.sp,
                                color = c.onSurfaceVariant,
                            )
                        }
                        Text("${r.actual}/${r.expected}", color = scanCol, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }

    if (askOrder) {
        DialogCard(
            title = "Номер заказа",
            onDismiss = { askOrder = false },
            actions = { DialogActionConfirm("Сохранить", { save() }) },
        ) {
            LabeledInput("", order, { order = it; error = "" }, keyboardType = KeyboardType.Number)
            if (error.isNotEmpty()) StatusLine(error, StatusKind.Err, Modifier.fillMaxWidth())
        }
    }
}
