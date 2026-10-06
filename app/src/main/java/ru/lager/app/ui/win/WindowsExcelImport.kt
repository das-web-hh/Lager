package ru.lager.app.ui.win

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================
//  Импорт из Excel (#importModal): .xlsx / .csv, настройка столбцов
// =====================================================================

private fun exColLetter(i: Int): String {
    var n = i
    val sb = StringBuilder()
    do {
        sb.insert(0, ('A' + n % 26))
        n = n / 26 - 1
    } while (n >= 0)
    return sb.toString()
}

private fun exGuess(headers: List<String>, keywords: List<String>): Int =
    headers.indexOfFirst { h -> keywords.any { h.lowercase().contains(it) } }

/** Дата в формате ДД.ММ.ГГ, как в HTML; без даты — сегодняшняя. */
private fun exDate(raw: String, fallback: String): String {
    val m = Regex("(\\d{1,4})[.\\-/](\\d{1,2})[.\\-/](\\d{2,4})").find(raw) ?: return fallback
    val (a, b, c) = m.destructured
    return if (a.length == 4) {
        c.padStart(2, '0') + "." + b.padStart(2, '0') + "." + a.takeLast(2)
    } else {
        a.padStart(2, '0') + "." + b.padStart(2, '0') + "." + c.takeLast(2)
    }
}

@Composable
private fun ExColumnPicker(
    label: String,
    headers: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val c = Md3.c
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface, modifier = Modifier.width(112.dp))
        Box(
            Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 40.dp)
                .clip(shape)
                .border(1.dp, c.outlineVariant, shape)
                .md3Clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                if (selected < 0) "— не используется —" else "${exColLetter(selected)}: ${headers[selected]}",
                fontSize = 13.sp,
                color = if (selected < 0) c.onSurfaceVariant else c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (open) {
        DialogCard(title = label, onDismiss = { open = false }) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                val options = listOf(-1) + headers.indices.toList()
                options.forEach { idx ->
                    Text(
                        if (idx < 0) "— не используется —" else "${exColLetter(idx)}: ${headers[idx]}",
                        fontSize = 14.sp,
                        fontWeight = if (idx == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (idx == selected) c.primary else c.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .md3Clickable { onSelect(idx); open = false }
                            .padding(horizontal = 6.dp, vertical = 12.dp),
                    )
                    RowDivider()
                }
            }
            SoftButton("Закрыть", { open = false }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun CatExcelImportWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var fileName by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf<List<String>>(emptyList()) }
    var rows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var iName by remember { mutableStateOf(-1) }
    var iQty by remember { mutableStateOf(-1) }
    var iArt by remember { mutableStateOf(-1) }
    var iEan by remember { mutableStateOf(-1) }
    var iDate by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf("") }
    var statusOk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun fail(t: String) { status = t; statusOk = false }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = ""
        headers = emptyList(); rows = emptyList()
        fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "файл"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { TableReader.read(ctx, uri, fileName) } }
            result.onFailure { fail("❌ Не удалось прочитать файл: ${it.message ?: "ошибка"}") }
            result.onSuccess { table ->
                if (table.isEmpty()) {
                    fail("❌ Не удалось прочитать файл: файл пуст")
                } else {
                    val h = table[0].mapIndexed { i, s -> s.ifEmpty { "Столбец ${i + 1}" } }
                    headers = h
                    rows = table.drop(1).filter { r -> r.any { it.isNotEmpty() } }
                    iName = exGuess(h, listOf("наим", "name", "продукт", "товар", "product"))
                    iQty = exGuess(h, listOf("кол", "qty", "menge", "quant", "количеств"))
                    iArt = exGuess(h, listOf("артикул", "article", "art", "artikel"))
                    iEan = exGuess(h, listOf("ean", "штрих", "barcode", "bcode"))
                    iDate = exGuess(h, listOf("дат", "date", "datum"))
                }
            }
        }
    }

    fun cell(row: List<String>, i: Int): String = if (i >= 0) row.getOrNull(i).orEmpty().trim() else ""

    fun doImport() {
        if (busy || rows.isEmpty()) return
        if (iName < 0) { fail("⚠️ Укажите столбец «Наименование»"); return }
        val today = java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.US).format(java.util.Date())
        val list = rows.mapNotNull { r ->
            val name = cell(r, iName)
            if (name.isEmpty()) null
            else CatalogStore.ExcelRow(
                name = name,
                qty = cell(r, iQty).replace(Regex("\\s"), "").takeWhile { it.isDigit() }.toIntOrNull() ?: 1,
                artikel = cell(r, iArt),
                ean = cell(r, iEan),
                date = exDate(cell(r, iDate), today),
            )
        }
        if (list.isEmpty()) { fail("⚠️ Нет данных для импорта. Проверьте настройки столбцов."); return }
        busy = true
        scope.launch {
            CatalogStore.load(ctx)
            val (count, updated) = CatalogStore.importRows(ctx, list)
            status = "✅ Импортировано: $count позиций, обновлено записей в базе: $updated"
            statusOk = true
            busy = false
            headers = emptyList(); rows = emptyList(); fileName = ""
        }
    }

    WindowScaffold(
        title = "Импорт из Excel",
        footer = {
            if (rows.isNotEmpty()) {
                LongButton(if (busy) "Импорт…" else "📊 Импортировать в базу", LongKind.Green, { doImport() })
            }
        },
    ) {
        ScrollBody {
            SoftButton(
                if (fileName.isEmpty()) "📂 Выбрать файл Excel (.xlsx, .csv)" else "📄 $fileName",
                { picker.launch(arrayOf("*/*")) },
                Modifier.fillMaxWidth(),
            )
            if (status.isNotEmpty()) {
                StatusLine(
                    status,
                    if (statusOk) StatusKind.Ok else StatusKind.Err,
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            if (headers.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("ОБНАРУЖЕННЫЕ СТОЛБЦЫ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant)
                Text(
                    headers.mapIndexed { i, h -> "${exColLetter(i)} — $h" }.joinToString("   "),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = c.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text("НАСТРОЙКА СТОЛБЦОВ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant)
                ExColumnPicker("Наименование", headers, iName) { iName = it }
                ExColumnPicker("Количество", headers, iQty) { iQty = it }
                ExColumnPicker("Артикул", headers, iArt) { iArt = it }
                ExColumnPicker("Штрихкод", headers, iEan) { iEan = it }
                ExColumnPicker("Дата", headers, iDate) { iDate = it }
                Spacer(Modifier.height(12.dp))
                rows.firstOrNull()?.let { r ->
                    fun v(i: Int) = if (i >= 0) cell(r, i).ifEmpty { "—" } else "—"
                    Text("ПРЕДПРОСМОТР (ПЕРВАЯ СТРОКА)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant)
                    Text(
                        "Наименование: ${v(iName)}\nКоличество: ${v(iQty)}\nАртикул: ${v(iArt)}\nШтрихкод: ${v(iEan)}\nДата: ${v(iDate)}",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = c.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text("Строк в файле: ${rows.size}", fontSize = 12.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
