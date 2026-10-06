package ru.lager.app.ui.win

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lager.app.ui.BarcodeScanIcon

// =====================================================================
//  Каталог (#baseModal) и Товар + Штрихкод (#linkToolModal)
// =====================================================================

@Composable
private fun CatIconCell(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .md3Clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp) }
}

@Composable
private fun CatRow(item: CatItem, lastDate: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    val c = Md3.c
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                item.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
                color = c.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                item.ean.ifEmpty { null },
                item.artikel.ifEmpty { null }?.let { "· $it" },
                "· $lastDate",
            ).joinToString(" ")
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 11.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        CatIconCell("✎", onEdit)
        CatIconCell("🗑", onDelete)
    }
}

@Composable
internal fun CatConfirmDialog(
    title: String,
    text: String,
    yes: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    DialogCard(title = title, onDismiss = onNo) {
        Text(text, fontSize = 14.sp, color = Md3.c.onSurface)
        LongButton(yes, LongKind.Red, onYes)
        SoftButton("Нет", onNo, Modifier.fillMaxWidth())
    }
}

/** openProductEditModal: название, EAN, артикул; EAN не должен повторяться. */
@Composable
private fun CatEditDialog(item: CatItem, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    var ean by remember(item.id) { mutableStateOf(item.ean) }
    var artikel by remember(item.id) { mutableStateOf(item.artikel) }
    var error by remember(item.id) { mutableStateOf("") }

    DialogCard(title = "Редактирование", onDismiss = onDismiss) {
        LabeledInput("Наименование", name, { name = it })
        LabeledInput("Штрихкод (EAN)", ean, { ean = it }, keyboardType = KeyboardType.Number)
        LabeledInput("Артикул", artikel, { artikel = it })
        if (error.isNotEmpty()) StatusLine(error, StatusKind.Err, Modifier.fillMaxWidth())
        LongButton("💾 Сохранить", LongKind.Green, {
            val n = name.trim()
            val e = ean.trim()
            if (n.isEmpty()) {
                error = "Введите наименование товара."
            } else {
                val dup = CatalogStore.duplicateOf(e, item.id)
                if (dup != null) {
                    error = "⚠️ Этот штрихкод уже привязан к товару \"${dup.name}\""
                } else {
                    onSave(n, e, artikel.trim())
                }
            }
        })
        SoftButton("Отмена", onDismiss, Modifier.fillMaxWidth())
    }
}

/** Импорт JSON-бэкапа из HTML (openDbImport / processDbImport). */
@Composable
internal fun CatImportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        CatalogStore.load(ctx)
        ArrivalStore.load(ctx)
    }
    var fileName by remember { mutableStateOf("") }
    var backup by remember { mutableStateOf<CatBackup?>(null) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        backup = null; error = ""; result = ""
        fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "файл"
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
            val parsed = raw?.let { CatalogStore.parseBackup(it) }
            if (parsed == null) error = "Неверный формат файла. Нужен JSON-бэкап из приложения." else backup = parsed
        }
    }

    DialogCard(title = "Импорт базы (.json)", onDismiss = onDismiss) {
        SoftButton(
            if (fileName.isEmpty()) "📂 Выбрать файл" else "📄 $fileName",
            { picker.launch(arrayOf("*/*")) },
            Modifier.fillMaxWidth(),
        )
        backup?.let { b ->
            Text(
                "📦 Товаров в базе: ${b.products.size}\n" +
                    "📋 Записей истории: ${b.arrivals.length()}" +
                    (if (b.exportedAt.isNotEmpty()) "\n📅 Дата экспорта: ${b.exportedAt.take(10)}" else "") +
                    (if (b.version.isNotEmpty()) "\n🔖 Версия: ${b.version}" else ""),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Md3.c.onSurface,
            )
            if (result.isEmpty()) {
                LongButton(if (busy) "Импорт…" else "Импортировать", LongKind.Green, {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            val (db, arr) = CatalogStore.applyBackup(ctx, b)
                            result = "✅ Товаров добавлено: $db, записей истории: $arr"
                            busy = false
                        }
                    }
                })
            }
        }
        if (error.isNotEmpty()) StatusLine("❌ $error", StatusKind.Err, Modifier.fillMaxWidth())
        if (result.isNotEmpty()) StatusLine(result, StatusKind.Ok, Modifier.fillMaxWidth())
        SoftButton("Закрыть", onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
fun CatalogWindow(env: WinEnv) {
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    var edit by remember { mutableStateOf<CatItem?>(null) }
    var delete by remember { mutableStateOf<CatItem?>(null) }
    var deleteStep by remember { mutableStateOf(1) }
    var importOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CatalogStore.load(ctx)
        ArrivalStore.load(ctx)
    }

    // Дата последней приёмки по каждому товару, как lastArr в renderBaseList.
    val lastDates by remember {
        derivedStateOf {
            val m = HashMap<String, Pair<Long, String>>()
            ArrivalStore.items.forEach { a ->
                val k = a.name.trim().lowercase()
                val t = parseHistDate(a.date)
                val cur = m[k]
                if (cur == null || t > cur.first) m[k] = t to a.date
            }
            m.mapValues { it.value.second }
        }
    }

    val shown by remember { derivedStateOf { CatalogStore.sorted(CatalogStore.search(q)) } }
    val total = CatalogStore.items.size

    WindowScaffold(
        title = "Каталог",
        actions = { TopBarTextButton("Импорт") { importOpen = true } },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
                LabeledInput("", q, { q = it }, placeholder = "Поиск по каталогу…")
            }
            if (shown.isEmpty()) {
                ScrollBody { EmptyHint(if (total == 0) "Каталог пуст" else "Ничего не найдено") }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { item ->
                        CatRow(
                            item,
                            lastDate = lastDates[item.name.trim().lowercase()]?.ifEmpty { null } ?: "не принимался",
                            onEdit = { edit = item },
                            onDelete = { deleteStep = 1; delete = item },
                        )
                        RowDivider()
                    }
                }
            }
        }
    }

    if (importOpen) CatImportDialog { importOpen = false }

    edit?.let { item ->
        CatEditDialog(
            item,
            onDismiss = { edit = null },
            onSave = { n, e, a ->
                CatalogStore.update(ctx, item.id, n, e, a)
                edit = null
            },
        )
    }

    // Удаление в два шага, как startDeleteRowFlow в HTML.
    delete?.let { item ->
        if (deleteStep == 1) {
            CatConfirmDialog(
                title = "Удалить товар?",
                text = "Товар будет удалён из каталога.",
                yes = "Да, удалить",
                onYes = { deleteStep = 2 },
                onNo = { delete = null },
            )
        } else {
            CatConfirmDialog(
                title = "Точно удалить?",
                text = "«${item.name}»",
                yes = "Да, удалить",
                onYes = { CatalogStore.remove(ctx, item.id); delete = null },
                onNo = { delete = null },
            )
        }
    }
}

// ---------- Товар + Штрихкод ----------

@Composable
private fun LinkSuggestions(list: List<CatItem>, onPick: (CatItem) -> Unit) {
    val c = Md3.c
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.outlineVariant, shape),
    ) {
        list.forEach { item ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .md3Clickable { onPick(item) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(item.name, fontSize = 14.sp, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (item.ean.isNotEmpty()) "Штрихкод: ${item.ean}" else "Штрихкод не указан",
                    fontSize = 11.sp,
                    color = c.onSurfaceVariant,
                )
            }
            RowDivider()
        }
    }
}

@Composable
fun LinkToolWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var ean by remember { mutableStateOf("") }
    var showDrop by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var hintOk by remember { mutableStateOf(false) }
    var relink by remember { mutableStateOf<CatItem?>(null) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { CatalogStore.load(ctx) }

    // Подсказки по названию появляются после третьей буквы, максимум 12.
    val suggestions by remember {
        derivedStateOf {
            if (!showDrop || name.trim().length < 3) emptyList() else CatalogStore.search(name).take(12)
        }
    }

    fun pick(item: CatItem) {
        name = item.name
        showDrop = false
        if (item.ean.isNotEmpty()) {
            ean = item.ean
            hint = "Товар уже есть в базе. Текущий штрихкод: ${item.ean}"
            hintOk = true
        } else {
            hint = "Товар есть в базе, но штрихкод пока не указан."
            hintOk = false
        }
    }

    // linkEanSearch: введённый штрихкод уже привязан к товару — подставляем название.
    fun onEan(raw: String) {
        ean = raw
        val t = raw.trim()
        if (t.isEmpty()) { hint = ""; return }
        val n = CatalogStore.normalizeBarcode(t)
        if (n.length < 6) return
        val found = CatalogStore.findByBarcode(n)
        if (found != null) {
            name = found.name
            showDrop = false
            hint = "Штрихкод найден в базе: «${found.name}»"
            hintOk = true
        } else {
            hint = "Такого штрихкода в базе нет — можно сохранить как новый."
            hintOk = false
        }
    }

    fun finish(n: String, e: String) {
        CatalogStore.link(ctx, n, e)
        env.info("Товар «$n» привязан к штрихкоду $e.")
        env.nav.pop()
    }

    fun save() {
        val n = name.trim()
        val e = CatalogStore.normalizeBarcode(ean)
        if (n.isEmpty()) { env.info("Введите наименование товара."); return }
        if (e.isEmpty()) { env.info("Введите или отсканируйте штрихкод."); return }
        val other = CatalogStore.findByBarcode(e)
        if (other != null && !other.name.trim().equals(n, ignoreCase = true)) {
            relink = other
        } else {
            finish(n, e)
        }
    }

    WindowScaffold(
        title = "Товар + Штрихкод",
        footer = { LongButton("💾 Сохранить", LongKind.Green, { save() }) },
    ) {
        ScrollBody {
            LabeledInput(
                "Наименование товара", name, { name = it; showDrop = true },
                placeholder = "Начните вводить название…",
                modifier = Modifier.padding(top = 2.dp),
            )
            if (suggestions.isNotEmpty()) LinkSuggestions(suggestions) { pick(it) }
            Spacer(Modifier.height(14.dp))
            LabeledInput(
                "Штрихкод (EAN)", ean, { onEan(it) },
                placeholder = "Введите код или отсканируйте",
                keyboardType = KeyboardType.Number,
                trailing = {
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .md3Clickable { scanning = true },
                        contentAlignment = Alignment.Center,
                    ) { BarcodeScanIcon(c.primary, Modifier.size(24.dp)) }
                },
            )
            Spacer(Modifier.height(10.dp))
            if (hint.isNotEmpty()) {
                if (hintOk) StatusLine(hint, StatusKind.Ok, Modifier.fillMaxWidth()) else HintText(hint)
            } else {
                HintText("Выберите товар из каталога и привяжите к нему штрихкод.")
            }
        }
    }

    if (scanning) {
        BarcodeScannerDialog(
            onResult = { code -> scanning = false; onEan(code) },
            onDismiss = { scanning = false },
        )
    }

    // Штрихкод уже у другого товара — спрашиваем, чтобы случайно не потерять связь.
    relink?.let { other ->
        CatConfirmDialog(
            title = "Перепривязать штрихкод?",
            text = "Штрихкод уже привязан к товару «${other.name}». Перепривязать его к «${name.trim()}»?",
            yes = "Перепривязать",
            onYes = {
                relink = null
                finish(name.trim(), CatalogStore.normalizeBarcode(ean))
            },
            onNo = { relink = null },
        )
    }
}
