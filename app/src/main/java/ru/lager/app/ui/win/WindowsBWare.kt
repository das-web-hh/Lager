package ru.lager.app.ui.win

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================
//  Приём B-Ware (#bwareReceiveModal) и История B-Ware (#bwareHistoryModal)
// =====================================================================

private class BwItem(val id: Long, val name: String) {
    var qty by mutableStateOf(0)
}

private class BwRecord(
    val sender: String,
    val order: String,
    val ls: String,
    val date: String,
    val items: List<Pair<String, Int>>,
    val driverSign: Boolean,
    val createdAt: Long,
    val docNo: String,
)

/** История хранится в SharedPreferences (как my_bware_history_v1 в HTML), не больше 100 записей. */
private object BwStore {
    private const val PREFS = "lager_bware"
    private const val KEY = "my_bware_history_v1"
    val history = mutableStateListOf<BwRecord>()
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val itemsJson = o.optJSONArray("items") ?: JSONArray()
                val items = ArrayList<Pair<String, Int>>()
                for (j in 0 until itemsJson.length()) {
                    val row = itemsJson.getJSONObject(j)
                    items.add(row.optString("name") to row.optInt("qty"))
                }
                history.add(
                    BwRecord(
                        sender = o.optString("sender"),
                        order = o.optString("order"),
                        ls = o.optString("ls"),
                        date = o.optString("date"),
                        items = items,
                        driverSign = o.optBoolean("driverSign", true),
                        createdAt = o.optLong("createdAt"),
                        docNo = o.optString("docNo"),
                    ),
                )
            }
        }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        history.take(100).forEach { r ->
            val items = JSONArray()
            r.items.forEach { (n, q) -> items.put(JSONObject().put("name", n).put("qty", q)) }
            arr.put(
                JSONObject()
                    .put("sender", r.sender)
                    .put("order", r.order)
                    .put("ls", r.ls)
                    .put("date", r.date)
                    .put("items", items)
                    .put("driverSign", r.driverSign)
                    .put("createdAt", r.createdAt)
                    .put("docNo", r.docNo),
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, r: BwRecord) {
        history.add(0, r)
        save(ctx)
    }

    fun remove(ctx: Context, r: BwRecord) {
        history.remove(r)
        save(ctx)
    }
}

private fun bwToday(): String =
    java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US).format(java.util.Date())

private fun bwDocNo(t: Long): String =
    "BW-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(t))

private fun bwClean(v: String): String {
    val t = v.trim()
    return if (Regex("^(не указан|не указано|nicht angegeben|n/a|null|-|—)$", RegexOption.IGNORE_CASE).matches(t)) "" else t
}

private fun bwNormalizeDate(v: String): String {
    val m = Regex("^(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{4})$").find(v.trim()) ?: return ""
    return "${m.groupValues[1].padStart(2, '0')}.${m.groupValues[2].padStart(2, '0')}.${m.groupValues[3]}"
}

/** Строка товара: тап = +1, кнопка с числом открывает ввод, свайп влево — «Удалить». */
@Composable
private fun BwRow(item: BwItem, onQty: () -> Unit, onDelete: () -> Unit) {
    val c = Md3.c
    val scope = rememberCoroutineScope()
    val maxPx = with(LocalDensity.current) { 90.dp.toPx() }
    val off = remember { Animatable(0f) }
    Box(Modifier.fillMaxWidth().background(Color(0xFF8B2D2D))) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .width(90.dp)
                    .fillMaxHeight()
                    .background(Color(0xFFC92A2A))
                    .md3Clickable(color = Color.White) {
                        scope.launch { off.animateTo(0f, tween(180)) }
                        onDelete()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("Удалить", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = off.value }
                .tapFeedback(item.qty)
                .background(c.card)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch { off.animateTo(if (off.value < -maxPx / 2f) -maxPx else 0f, tween(180)) }
                        },
                        onDragCancel = { scope.launch { off.animateTo(0f, tween(180)) } },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            scope.launch { off.snapTo((off.value + dx).coerceIn(-maxPx, 0f)) }
                        },
                    )
                }
                .md3Clickable {
                    if (off.value < -1f) {
                        scope.launch { off.animateTo(0f, tween(180)) }
                    } else {
                        item.qty = minOf(9999, item.qty + 1)
                    }
                }
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 17.sp, color = c.onSurface)
                Text("B-Ware", fontSize = 11.sp, color = c.onSurfaceVariant)
            }
            Box(
                Modifier
                    .defaultMinSize(minWidth = 56.dp, minHeight = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, c.outlineVariant, RoundedCornerShape(8.dp))
                    .md3Clickable(onClick = onQty),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.qty.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
            }
        }
    }
}

@Composable
private fun BwSuggestions(names: List<String>, onPick: (String) -> Unit) {
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
        if (names.isEmpty()) {
            Text("Совпадений не найдено", fontSize = 13.sp, color = c.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
        }
        names.forEach { n ->
            Text(
                n, fontSize = 14.sp, color = c.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .md3Clickable { onPick(n) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            )
            RowDivider()
        }
    }
}

@Composable
fun BWareWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    var sender by remember { mutableStateOf("") }
    var order by remember { mutableStateOf("") }
    var ls by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var driver by remember { mutableStateOf(true) }
    var product by remember { mutableStateOf("") }
    var seq by remember { mutableStateOf(0L) }
    var statusText by remember { mutableStateOf("") }
    var statusKind by remember { mutableStateOf(StatusKind.Err) }
    var scanning by remember { mutableStateOf(false) }
    var camUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    var qtyEdit by remember { mutableStateOf<BwItem?>(null) }
    val items = remember { mutableStateListOf<BwItem>() }

    LaunchedEffect(Unit) {
        BwStore.load(ctx)
        CatalogStore.load(ctx)
    }

    // Подсказки из каталога: с третьей буквы, до 10 штук, как bwareSearch в HTML.
    val suggestions by remember {
        derivedStateOf {
            if (product.trim().length < 3) emptyList()
            else CatalogStore.search(product).map { it.name }.distinct().take(10)
        }
    }

    fun fail(text: String) { statusText = text; statusKind = StatusKind.Err }

    fun applyRecognized(o: JSONObject) {
        val sender2 = bwClean(o.optString("lieferant"))
        val order2 = bwClean(o.optString("bestell_nr"))
        val ls2 = bwClean(o.optString("lieferschein_nr"))
        val date2 = bwNormalizeDate(o.optString("datum")).ifEmpty { bwToday() }
        val check = bwClean(o.optString("pruefung"))
        sender = sender2; order = order2; ls = ls2; date = date2
        val notes = ArrayList<String>()
        if (sender2.isEmpty()) notes.add("поставщик не найден")
        if (order2.isEmpty() && ls2.isEmpty()) notes.add("номер заказа и накладной не найдены — введите вручную")
        else if (order2.isEmpty()) notes.add("номер заказа не указан на накладной")
        if (check.isNotEmpty()) notes.add(check)
        if (notes.isEmpty()) {
            statusText = "Данные накладной распознаны. Проверьте поля."
            statusKind = StatusKind.Ok
        } else {
            statusText = "Данные распознаны. Проверьте: ${notes.joinToString("; ")}."
            statusKind = StatusKind.Warn
        }
    }

    /** Фото или PDF накладной → Gemini → поля шапки (recognizeHeader в HTML). */
    fun scan(uri: Uri, name: String) {
        if (scanning) return
        scanning = true
        statusText = "Gemini распознаёт данные накладной…"
        statusKind = StatusKind.Warn
        scope.launch {
            try {
                val image = GeminiFiles.invoiceImage(ctx, uri, name)
                val prompt = SettingsStore.bwarePrompt(ctx).replace(Regex("\\{\\{\\s*DATE\\s*\\}\\}"), bwToday())
                val reply = GeminiClient.generate(
                    ctx, listOf(GeminiPart.Text(prompt), image), json = true, temperature = 0.0,
                    onStatus = { statusText = it; statusKind = StatusKind.Warn },
                )
                applyRecognized(GeminiClient.parseJsonObject(reply.text))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: GeminiException) {
                fail(e.message ?: "Не удалось распознать накладную.")
            } catch (e: Exception) {
                fail(e.message ?: "Не удалось распознать накладную.")
            } finally {
                scanning = false
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = camUri
        if (ok && u != null) scan(u, "snapshot.jpg")
    }
    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) camUri?.let { camera.launch(it) } else env.info("Нет доступа к камере")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scan(uri, AttachmentStore.displayName(ctx, uri))
    }
    fun shoot() {
        if (scanning) { env.info("Идёт распознавание…"); return }
        val u = AttachmentStore.newCameraTarget(ctx).second
        camUri = u
        val has = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (has) camera.launch(u) else camPermission.launch(android.Manifest.permission.CAMERA)
    }
    fun pickFile() {
        if (scanning) { env.info("Идёт распознавание…"); return }
        picker.launch(arrayOf("application/pdf", "image/*"))
    }

    fun addProduct(value: String = product) {
        val name = value.trim()
        if (name.isEmpty()) { fail("Введите название товара."); return }
        val same = items.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
        if (same != null) {
            same.qty = minOf(9999, same.qty + 1)
        } else {
            seq += 1
            items.add(BwItem(seq, name))
        }
        product = ""
        statusText = ""
    }

    fun next() {
        val s = sender.trim()
        val o = order.trim()
        val l = ls.trim()
        if (s.isEmpty() || (o.isEmpty() && l.isEmpty())) {
            fail("Заполните отправителя и номер заказа или накладной.")
            return
        }
        val ready = items.filter { it.qty > 0 }.map { it.name to it.qty }
        if (ready.isEmpty()) {
            fail("Добавьте товар и укажите количество.")
            return
        }
        val now = System.currentTimeMillis()
        BwStore.add(
            ctx,
            BwRecord(
                sender = s, order = o, ls = l,
                date = date.trim().ifEmpty { bwToday() },
                items = ready, driverSign = driver,
                createdAt = now, docNo = bwDocNo(now),
            ),
        )
        statusText = "Документ добавлен в историю. Печать PDF с QR — в разработке."
        statusKind = StatusKind.Ok
    }

    WindowScaffold(
        title = "Приём B-Ware",
        actions = {
            TopBarTextButton("История") { env.nav.push(Win.BWareHistory) }
            TopBarTextButton("Далее") { next() }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .weight(1f)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    LabeledInput("Absender / Отправитель", sender, { sender = it }, placeholder = "Поставщик")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabeledInput("Bestell-Nr. / Номер заказа", order, { order = it }, Modifier.weight(1f), placeholder = "Номер заказа")
                        LabeledInput("Lieferschein-Nr. / Накладная", ls, { ls = it }, Modifier.weight(1f), placeholder = "LS-Nr.")
                    }
                    Spacer(Modifier.height(10.dp))
                    LabeledInput(
                        "Datum", date, { date = it.take(10) },
                        placeholder = "ДД.ММ.ГГГГ", keyboardType = KeyboardType.Number,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(if (scanning) "⏳ Читаю…" else "📷 Снимок", { shoot() }, Modifier.weight(1f))
                        SoftButton("📄 PDF из памяти", { pickFile() }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    SoftButton("📸 Фото товара", { env.info("Камера — в разработке") }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                    Md3Card {
                        SettingsRow(
                            icon = "✍️",
                            title = "Подпись водителя и № LKW",
                            sub = if (driver) "Включено · в документе есть поля" else "Выключено · полей в документе нет",
                            onClick = { driver = !driver },
                            trailing = { Md3Switch(driver) { driver = it } },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledInput(
                            "", product, { product = it }, Modifier.weight(1f),
                            placeholder = "Введите название товара…",
                        )
                        SquareIconButton({ addProduct(product) }) {
                            Text("➔", color = c.onPrimaryContainer, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (product.trim().length >= 3) {
                        BwSuggestions(suggestions) { addProduct(it) }
                    }
                }
                if (items.isEmpty()) {
                    Text(
                        "Добавленные товары появятся здесь.",
                        fontSize = 13.sp,
                        color = c.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 30.dp),
                    )
                } else {
                    items.forEach { item ->
                        key(item.id) {
                            BwRow(item, onQty = { qtyEdit = item }, onDelete = { items.remove(item) })
                            RowDivider()
                        }
                    }
                }
                if (statusText.isNotEmpty()) {
                    StatusLine(
                        statusText,
                        statusKind,
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }

    qtyEdit?.let { item ->
        RcvQtyDialog(
            title = "Количество",
            name = item.name,
            initial = item.qty,
            onDismiss = { qtyEdit = null },
            onOk = { v -> item.qty = minOf(9999, v); qtyEdit = null },
        )
    }
}

@Composable
fun BWareHistoryWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { BwStore.load(ctx) }
    val history = BwStore.history

    WindowScaffold("История B-Ware") {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (history.isEmpty()) {
                Text(
                    "Здесь появятся созданные документы B-Ware.",
                    fontSize = 13.sp,
                    color = c.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 42.dp),
                )
            }
            history.toList().forEach { r ->
                key(r.createdAt, r.docNo) {
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(c.card)
                            .border(1.dp, c.outlineVariant, shape),
                    ) {
                        Column(Modifier.padding(start = 13.dp, end = 42.dp, top = 12.dp, bottom = 12.dp)) {
                            Text(
                                r.sender.ifEmpty { "Без отправителя" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append("Заказ: ").append(r.order.ifEmpty { "—" })
                                    if (r.ls.isNotEmpty()) append("   Накладная: ").append(r.ls)
                                    append("   Дата: ").append(r.date.ifEmpty { "—" })
                                },
                                fontSize = 11.sp,
                                color = c.onSurfaceVariant,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                            Text(
                                if (r.items.isEmpty()) "Товары не указаны"
                                else r.items.joinToString("\n") { (n, q) -> "$n — $q шт." },
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = c.onSurface,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                                Text(
                                    "🖨 Печать партии",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1C7ED6))
                                        .md3Clickable(color = Color.White) { env.info("Печать — в разработке") }
                                        .padding(horizontal = 11.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 9.dp, end = 9.dp)
                                .width(28.dp)
                                .height(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .md3Clickable { BwStore.remove(ctx, r) },
                            contentAlignment = Alignment.Center,
                        ) { Text("🗑", fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}
