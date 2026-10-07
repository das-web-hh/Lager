package ru.lager.app.ui.win

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lager.app.ui.BarcodeScanIcon

// =====================================================================
//  Окна приёма: вручную, по наименованиям, автоприём
// =====================================================================

private val RcvR16 = RoundedCornerShape(16.dp)
private val RcvR14 = RoundedCornerShape(14.dp)
private val RcvR12 = RoundedCornerShape(12.dp)
private val RcvR10 = RoundedCornerShape(10.dp)
private val RcvRed = Color(0xFFE03131)

private fun rcvToday(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

/** «ГГГГ-ММ-ДД» из поля даты → «ДД.ММ.ГГ», как хранит история; при ошибке — сегодня. */
private fun rcvShortDate(iso: String): String {
    val m = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(iso.trim())
        ?: return java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.US).format(java.util.Date())
    val (y, mo, d) = m.destructured
    return d.padStart(2, '0') + "." + mo.padStart(2, '0') + "." + y.takeLast(2)
}

private fun rcvDisplayName(ctx: Context, uri: Uri): String {
    val fromProvider = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment ?: "файл"
}

/** Диалог «Количество» (.qty-overlay): название, число, ✕ и OK. */
@Composable
internal fun RcvQtyDialog(
    title: String,
    name: String,
    initial: Int,
    onDismiss: () -> Unit,
    onOk: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(if (initial > 0) initial.toString() else "") }
    DialogCard(
        title = title,
        onDismiss = onDismiss,
        actions = {
            DialogActionCancel("✕", onDismiss)
            DialogActionConfirm("OK", { onOk(text.toIntOrNull() ?: 0) })
        },
    ) {
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Md3.c.onSurfaceVariant)
        LabeledInput(
            label = "",
            value = text,
            onValueChange = { v -> text = v.filter { ch -> ch.isDigit() }.take(5) },
            placeholder = "0",
            keyboardType = KeyboardType.Number,
        )
    }
}

// =====================================================================
//  1. Приём товаров — вручную (#receiveModal)
// =====================================================================

private class RcvItem(val id: Long, val name: String, val ean: String, qty: Int) {
    var qty by mutableStateOf(qty)
}

@Composable
private fun RcvRoundButton(text: String, onClick: () -> Unit) {
    val c = Md3.c
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(c.surfaceContainer)
            .md3Clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 22.sp, color = c.onSurface) }
}

@Composable
private fun RcvItemRow(item: RcvItem, onEdit: () -> Unit, onRemove: () -> Unit) {
    val c = Md3.c
    val zero = item.qty < 1
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RcvR16)
            .background(c.card)
            .border(1.dp, if (zero) RcvRed else c.outlineVariant, RcvR16)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.5.sp, color = c.onSurface)
            if (item.ean.isNotEmpty()) {
                Text(item.ean, fontSize = 12.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RcvRoundButton("−") { item.qty = maxOf(0, item.qty - 1) }
            Box(
                Modifier
                    .widthIn(min = 40.dp)
                    .height(38.dp)
                    .md3Clickable(onClick = onEdit),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.qty.toString(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (zero) RcvRed else c.onSurface,
                )
            }
            RcvRoundButton("+") { item.qty = minOf(99999, item.qty + 1) }
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .md3Clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Text("✕", fontSize = 15.sp, color = c.onSurfaceVariant) }
    }
}

@Composable
fun ReceiveManualWindow(env: WinEnv) {
    val c = Md3.c
    val items = remember { mutableStateListOf<RcvItem>() }
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RcvItem?>(null) }
    var saving by remember { mutableStateOf(false) }
    var seq by remember { mutableStateOf(0L) }
    var scanning by remember { mutableStateOf(false) }
    var unknownEan by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val rcvCtx = LocalContext.current

    LaunchedEffect(Unit) { CatalogStore.load(rcvCtx) }
    LaunchedEffect(Unit) {
        delay(420)
        runCatching { focus.requestFocus() }
    }

    fun addItem(name: String, ean: String) {
        val same = items.firstOrNull { it.name.equals(name, ignoreCase = true) || (ean.isNotEmpty() && it.ean == ean) }
        if (same != null) {
            same.qty = minOf(99999, same.qty + 1)
        } else {
            seq += 1
            items.add(0, RcvItem(seq, name, ean, 1))
        }
    }

    fun addTyped() {
        val q = query.trim()
        if (q.isEmpty()) return
        val isEan = q.length >= 8 && q.all { ch -> ch.isDigit() }
        val found = if (isEan) CatalogStore.findByBarcode(q) else null
        if (isEan && found == null) {
            unknownEan = q
        } else {
            addItem(found?.name ?: q, if (isEan) q else "")
        }
        query = ""
    }

    // Скан: штрихкод из каталога даёт название товара, иначе «Товар <EAN>».
    fun addScanned(code: String) {
        val found = CatalogStore.findByBarcode(code)
        if (found == null) {
            unknownEan = code
        } else {
            addItem(found.name, code)
            env.info(found.name)
        }
    }

    val totalQty = items.fold(0) { acc, i -> acc + i.qty }
    val ready = items.isNotEmpty() && items.all { it.qty > 0 }
    val typed = query.trim()
    val typedIsEan = typed.length >= 8 && typed.all { ch -> ch.isDigit() }

    BackHandler(enabled = saving) { saving = false }

    if (scanning) {
        BarcodeScannerDialog(
            onResult = { code -> scanning = false; addScanned(code) },
            onDismiss = { scanning = false },
        )
    }

    unknownEan?.let { ean ->
        RcvUnknownNameDialog(
            ean = ean,
            onSkip = { unknownEan = null },
            onSave = { name ->
                CatalogStore.ensure(rcvCtx, name, ean)
                addItem(name, ean)
                env.info("Добавлено в каталог и в приёмку: $name")
                unknownEan = null
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = c.surface, contentColor = c.onSurface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                // шапка (.rv-head)
                Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
                Text(
                    "Принять товар",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
                )

                // поле поиска (.rv-search)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RcvR16)
                            .background(c.card)
                            .border(1.dp, if (focused) c.primary else c.outlineVariant, RcvR16)
                            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (query.isEmpty()) {
                                Text("Название или штрихкод", color = c.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 16.sp)
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(color = c.onSurface, fontSize = 16.sp),
                                cursorBrush = SolidColor(c.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { addTyped() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focus)
                                    .onFocusChanged { focused = it.isFocused },
                            )
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RcvR12)
                                .md3Clickable { scanning = true },
                            contentAlignment = Alignment.Center,
                        ) { BarcodeScanIcon(c.primary, Modifier.size(26.dp)) }
                    }
                    if (typed.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RcvR14)
                                .background(c.card)
                                .border(1.dp, c.outlineVariant, RcvR14)
                                .md3Clickable { addTyped() }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("➕", fontSize = 16.sp)
                            Text(
                                if (typedIsEan) "Штрихкод $typed — добавить" else "Добавить «$typed»",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // список (.rv-body)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                ) {
                    Text(
                        if (items.isEmpty()) "Список пуст — найдите товар выше" else "${items.size} поз.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 10.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { item ->
                            key(item.id) {
                                RcvItemRow(
                                    item = item,
                                    onEdit = { editing = item },
                                    onRemove = { items.remove(item) },
                                )
                            }
                        }
                    }
                }

                // нижняя панель (.recv-footer)
                if (items.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(c.card)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "${items.size} поз. · $totalQty шт",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = c.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .clip(RcvR14)
                                .background(if (ready) c.primary else c.primary.copy(alpha = 0.4f))
                                .md3Clickable(color = c.onPrimary, enabled = ready) { saving = true }
                                .padding(horizontal = 22.dp, vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✅ Принять товар", color = c.onPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Spacer(Modifier.fillMaxWidth().navigationBarsPadding())
                }
            }
        }

        AnimatedVisibility(
            visible = saving,
            enter = slideInHorizontally(tween(380, easing = EmphasizedEasing)) { it },
            exit = slideOutHorizontally(tween(300, easing = EmphasizedEasing)) { it },
        ) {
            RcvSaveScreen(
                env = env,
                items = items,
                onSaved = {
                    items.clear()
                    saving = false
                    env.nav.pop()
                },
            )
        }
    }

    editing?.let { item ->
        RcvQtyDialog(
            title = "Количество",
            name = item.name,
            initial = item.qty,
            onDismiss = { editing = null },
            onOk = { v -> item.qty = v; editing = null },
        )
    }
}

/** «Сохранить» (#receiveSaveModal): дата, номер заказа EB…, фото (до 20), накладные (до 30). */
/** Список выбранных вложений: счётчик и строки с кнопкой «убрать». */
@Composable
private fun RcvAttachList(ctx: Context, list: androidx.compose.runtime.snapshots.SnapshotStateList<Uri>, max: Int) {
    val c = Md3.c
    HintText("${list.size} / $max", Modifier.padding(top = 6.dp))
    list.toList().forEach { u ->
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).clip(RcvR10).background(c.surfaceHigh).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                AttachmentStore.displayName(ctx, u),
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.size(40.dp).md3Clickable { list.remove(u) }, contentAlignment = Alignment.Center) {
                Text("✕", fontSize = 15.sp, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RcvSaveScreen(env: WinEnv, items: List<RcvItem>, onSaved: () -> Unit) {
    val c = Md3.c
    val ctx = LocalContext.current
    var error by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(rcvToday()) }
    var order by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val photos = remember { mutableStateListOf<Uri>() }
    val docs = remember { mutableStateListOf<Uri>() }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraForDocs by remember { mutableStateOf(false) }

    fun addTo(list: MutableList<Uri>, max: Int, uris: List<Uri>) {
        val room = max - list.size
        if (room <= 0) { env.info("Достигнут лимит: $max"); return }
        list.addAll(uris.filter { it !in list }.take(room))
        if (uris.size > room) env.info("Добавлено только $room — лимит $max")
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        addTo(photos, AttachmentStore.MAX_PHOTOS, uris)
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addTo(docs, AttachmentStore.MAX_DOCS, uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) addTo(if (cameraForDocs) docs else photos, if (cameraForDocs) AttachmentStore.MAX_DOCS else AttachmentStore.MAX_PHOTOS, listOf(u))
        else cameraFile?.delete()
    }
    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) cameraUri?.let { camera.launch(it) } else env.info("Нет доступа к камере")
    }
    fun shoot(forDocs: Boolean) {
        val limit = if (forDocs) AttachmentStore.MAX_DOCS else AttachmentStore.MAX_PHOTOS
        if ((if (forDocs) docs.size else photos.size) >= limit) { env.info("Достигнут лимит: $limit"); return }
        val (f, u) = AttachmentStore.newCameraTarget(ctx)
        cameraFile = f; cameraUri = u; cameraForDocs = forDocs
        val has = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (has) camera.launch(u) else camPermission.launch(android.Manifest.permission.CAMERA)
    }

    WindowScaffold("Сохранить") {
        ScrollBody {
            Md3Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LabeledInput(
                        label = "Дата",
                        value = date,
                        onValueChange = { date = it.take(10) },
                        placeholder = "ГГГГ-ММ-ДД",
                    )
                    LabeledInput(
                        label = "Отправитель",
                        value = sender,
                        onValueChange = { sender = it.take(60) },
                        placeholder = "Имя отправителя (необязательно)",
                    )
                    Column {
                        FieldLabel("Номер заказа партии")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier
                                    .clip(RcvR10)
                                    .background(c.primaryContainer)
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Text("EB", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.onPrimaryContainer)
                            }
                            LabeledInput(
                                label = "",
                                value = order,
                                onValueChange = { v -> order = v.filter { ch -> ch.isDigit() }.take(12) },
                                modifier = Modifier.weight(1f),
                                placeholder = "Номер",
                                keyboardType = KeyboardType.Number,
                            )
                        }
                    }
                    Column {
                        FieldLabel("📷 Фото (макс. 20)")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SoftButton("＋ Выбрать фото", { photoPicker.launch("image/*") }, Modifier.weight(1f))
                            SoftButton("📷 Сделать фото", { shoot(false) }, Modifier.weight(1f))
                        }
                        RcvAttachList(ctx, photos, AttachmentStore.MAX_PHOTOS)
                    }
                    Column {
                        FieldLabel("📄 Накладные (фото или PDF, макс. 30)")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SoftButton("＋ Выбрать файлы", { docPicker.launch(arrayOf("image/*", "application/pdf")) }, Modifier.weight(1f))
                            SoftButton("📷 Сканировать", { shoot(true) }, Modifier.weight(1f))
                        }
                        RcvAttachList(ctx, docs, AttachmentStore.MAX_DOCS)
                    }
                    if (error.isNotEmpty()) StatusLine(error, StatusKind.Err, Modifier.fillMaxWidth())
                    LongButton("💾 Сохранить", LongKind.Green, {
                        val ready = items.filter { it.qty > 0 }
                        if (ready.isEmpty()) {
                            error = "Нет товаров с количеством."
                        } else {
                            val shortDate = rcvShortDate(date)
                            val orderNo = if (order.isEmpty()) "" else "EB$order"
                            val batchId = java.util.UUID.randomUUID().toString().take(12)
                            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date())
                            ArrivalStore.addAll(
                                ctx,
                                ready.map {
                                    Arrival(
                                        id = java.util.UUID.randomUUID().toString().take(12),
                                        batchId = batchId,
                                        date = shortDate,
                                        name = it.name,
                                        menge = it.qty,
                                        sender = sender.trim(),
                                        order = orderNo,
                                        source = "",
                                        receivedAt = stamp,
                                    )
                                },
                            )
                            if (photos.isNotEmpty() || docs.isNotEmpty()) {
                                val ph = photos.toList(); val dc = docs.toList()
                                scope.launch(Dispatchers.IO) { AttachmentStore.save(ctx, batchId, ph, dc) }
                            }
                            // Товары с реальными названиями попадают в каталог; заглушки «Товар <EAN>» — нет.
                            ready.forEach {
                                if (it.name != "Товар ${it.ean}") CatalogStore.ensure(ctx, it.name, it.ean)
                            }
                            env.info("Принято: ${ready.size} поз., ${ready.sumOf { it.qty }} шт.")
                            onSaved()
                        }
                    })
                }
            }
        }
    }
}

// =====================================================================
//  2. Приём товаров по наименованиям (#nameReceiveModal)
// =====================================================================

private class NrProduct(val id: Long, val name: String, val plan: Int) {
    var actual by mutableStateOf(0)
    var damage by mutableStateOf(0)
    var fresh by mutableStateOf(false)
    var taps by mutableStateOf(0)
    var ean: String = ""
}

private class NrBatch(val id: Long, val title: String) {
    /** Идентификатор записи в «Истории сканирований». */
    val histId: String = "nrh_" + java.util.UUID.randomUUID().toString().take(10)
    val products = mutableStateListOf<NrProduct>()
    var saved by mutableStateOf(false)
    val docs = mutableStateListOf<Uri>()
    var sender by mutableStateOf("")
    var order by mutableStateOf("")
}

private fun reportRows(b: NrBatch): List<List<String>> =
    b.products.map { listOf(it.name, it.plan.toString(), it.actual.toString(), it.damage.toString(), (it.actual - it.plan).toString()) }

private const val NR_EXECUTOR = "Abteilung Wareneingang"

/** nrPrintDiscrepancy: брак, недопоставка, лишний товар вне накладной, перепоставка. */
private fun nrDiscrepancySections(b: NrBatch): List<Triple<String, List<String>, List<List<String>>>> {
    fun sec(title: String, qtyTitle: String, rows: List<NrProduct>, amount: (NrProduct) -> Int, status: (NrProduct) -> String) =
        Triple(
            title,
            listOf("№", "Artikelbezeichnung", qtyTitle, "Status"),
            rows.mapIndexed { i, p -> listOf((i + 1).toString(), p.name, "${amount(p)} Stk.", status(p)) },
        )
    val ps = b.products
    return listOf(
        sec("Angenommene B-Ware (Beschädigte Artikel)", "Menge", ps.filter { it.damage > 0 }, { it.damage }, { "B-Ware (Beschädigt)" }),
        sec(
            "Nicht gelieferte Ware (Fehlmengen)", "Fehlmenge", ps.filter { it.plan > 0 && it.actual < it.plan },
            { it.plan - it.actual }, { "Nicht geliefert (Lieferschein: ${it.plan}, gezählt: ${it.actual})" },
        ),
        sec("Falsche Ware geliefert (nicht laut Lieferschein)", "Menge", ps.filter { it.plan == 0 && it.actual > 0 }, { it.actual }, { "Nicht im Lieferschein" }),
        sec(
            "Zuviel gelieferte Ware (Mehrmengen)", "Mehrmenge", ps.filter { it.plan > 0 && it.actual > it.plan },
            { it.actual - it.plan }, { "Zuviel geliefert (Lieferschein: ${it.plan}, gezählt: ${it.actual})" },
        ),
    ).filter { it.third.isNotEmpty() }
}

private fun nrGuess(headers: List<String>, keywords: List<String>): Int =
    headers.indexOfFirst { h -> keywords.any { h.lowercase().contains(it) } }

/** Отправитель из столбца таблицы (первое непустое значение), если такой столбец есть. */
private fun nrFindSender(table: List<List<String>>): String {
    if (table.size < 2) return ""
    val i = nrGuess(table[0], listOf("отправ", "sender", "absender", "поставщик", "supplier", "lieferant"))
    if (i < 0) return ""
    return table.drop(1).firstNotNullOfOrNull { it.getOrNull(i)?.trim()?.takeIf { v -> v.isNotEmpty() } }.orEmpty()
}

/** Накладная из Excel/CSV: (название, план, EAN). Нужен хотя бы столбец с наименованием. */
private fun nrParseTable(table: List<List<String>>): List<Triple<String, Int, String>>? {
    if (table.size < 2) return null
    val h = table[0]
    val iName = nrGuess(h, listOf("наим", "name", "товар", "продукт", "product", "описание", "bezeichnung"))
    if (iName < 0) return null
    val iQty = nrGuess(h, listOf("план", "plan", "кол", "qty", "menge", "quant"))
    val iEan = nrGuess(h, listOf("ean", "штрих", "barcode"))
    val out = ArrayList<Triple<String, Int, String>>()
    for (r in table.drop(1)) {
        val name = r.getOrNull(iName).orEmpty().trim()
        if (name.isEmpty()) continue
        val qty = if (iQty >= 0) r.getOrNull(iQty).orEmpty().replace(Regex("\\s"), "").takeWhile { it.isDigit() }.toIntOrNull() ?: 0 else 0
        val ean = if (iEan >= 0) r.getOrNull(iEan).orEmpty().trim() else ""
        out.add(Triple(name, qty, ean))
    }
    return out
}

private val NrGreen = Color(0xFF267A45)
private val NrGreenEdge = Color(0xFF69DB7C)
private val NrYellow = Color(0xFF8F641F)
private val NrYellowEdge = Color(0xFFFFD166)

@Composable
fun ReceiveNameWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val batches = remember { mutableStateListOf<NrBatch>() }
    var active by remember { mutableStateOf<NrBatch?>(null) }
    var printAsk by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(0) } // 0 старт, 1 список, 2 сверка
    var seq by remember { mutableStateOf(0L) }
    var qtyEdit by remember { mutableStateOf<Pair<NrProduct, Boolean>?>(null) } // true = брак
    var deleteAsk by remember { mutableStateOf<NrProduct?>(null) }
    var addOpen by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { CatalogStore.load(ctx) }

    val histUser = listOfNotNull(env.profile?.firstName, env.profile?.lastName)
        .filter { it.isNotBlank() }.joinToString(" ").ifEmpty { env.profile?.email.orEmpty() }
    fun logHist(b: NrBatch, type: String, text: String = "", batchId: String = "") {
        NrHistoryStore.log(
            ctx,
            NrHistoryStore.Snapshot(
                id = b.histId, user = histUser, sender = b.sender, order = b.order, date = "",
                fileName = b.title, batchId = batchId,
                products = b.products.map { NrHistProduct(it.name, it.ean, it.plan, it.actual, it.damage) },
            ),
            type, text,
        )
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            scope.launch {
                var last: NrBatch? = null
                var note = ""
                var loaded = 0
                uris.forEach { u ->
                    val title = rcvDisplayName(ctx, u)
                    seq += 1
                    val b = NrBatch(seq, title)
                    if (title.substringAfterLast('.', "").lowercase() == "pdf") {
                        note = "Разбор PDF подключим позже — добавляйте товары кнопкой «+»"
                        logHist(b, "error", "Разбор PDF пока не поддерживается")
                    } else {
                        val table = withContext(Dispatchers.IO) {
                            runCatching { TableReader.read(ctx, u, title) }.getOrNull()
                        }
                        val rows = table?.let { nrParseTable(it) }
                        table?.let { b.sender = nrFindSender(it) }
                        if (rows.isNullOrEmpty()) {
                            note = "В «$title» не найден столбец с наименованием — добавляйте товары кнопкой «+»"
                            logHist(b, "error", "Не найден столбец с наименованием")
                        } else {
                            rows.forEach { (name, plan, ean) ->
                                seq += 1
                                b.products.add(NrProduct(seq, name, plan).also { it.ean = ean })
                            }
                            loaded += rows.size
                            logHist(b, "recognized")
                        }
                    }
                    batches.add(b)
                    last = b
                }
                active = last
                screen = 1
                env.info(if (loaded > 0) "Загружено позиций: $loaded" else note)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importUris(uris) }

    // Файлы из Android «Поделиться» → «Приём по имени»
    LaunchedEffect(ShareState.nameQueue) {
        val q = ShareState.nameQueue
        if (q.isNotEmpty()) {
            ShareState.nameQueue = emptyList()
            importUris(q)
        }
    }

    var camUri by remember { mutableStateOf<Uri?>(null) }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val b = active
        if (b != null) {
            val room = AttachmentStore.MAX_DOCS - b.docs.size
            b.docs.addAll(uris.filter { it !in b.docs }.take(maxOf(room, 0)))
            if (uris.size > room) env.info("Лимит файлов: ${AttachmentStore.MAX_DOCS}")
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = camUri
        val b = active
        if (ok && u != null && b != null && b.docs.size < AttachmentStore.MAX_DOCS) b.docs.add(u)
    }
    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) camUri?.let { camera.launch(it) } else env.info("Нет доступа к камере")
    }
    fun shootDoc() {
        val b = active
        if (b == null) { env.info("Сначала откройте партию"); return }
        if (b.docs.size >= AttachmentStore.MAX_DOCS) { env.info("Лимит файлов: ${AttachmentStore.MAX_DOCS}"); return }
        val u = AttachmentStore.newCameraTarget(ctx).second
        camUri = u
        val has = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (has) camera.launch(u) else camPermission.launch(android.Manifest.permission.CAMERA)
    }

    // Партия записывается в историю один раз; количество = факт, если не введён — план (как в HTML).
    fun saveBatch() {
        val b = active ?: return
        if (b.saved) { env.info("Эта партия уже сохранена в истории."); return }
        val rows = b.products.filter { (if (it.actual > 0) it.actual else it.plan) > 0 }
        if (rows.isEmpty()) { env.info("Нет товаров для сохранения."); return }
        val batchId = "name_receive_" + System.currentTimeMillis().toString(36)
        val date = java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.US).format(java.util.Date())
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        var total = 0
        ArrivalStore.addAll(
            ctx,
            rows.map {
                val qty = if (it.actual > 0) it.actual else it.plan
                total += qty
                Arrival(
                    id = java.util.UUID.randomUUID().toString().take(12),
                    batchId = batchId, date = date, name = it.name, menge = qty,
                    sender = b.sender.trim(), order = b.order.trim(), source = b.title, receivedAt = stamp,
                )
            },
        )
        rows.forEach { CatalogStore.ensure(ctx, it.name, it.ean) }
        if (b.docs.isNotEmpty()) {
            val dc = b.docs.toList()
            scope.launch(Dispatchers.IO) { AttachmentStore.save(ctx, batchId, emptyList(), dc) }
        }
        b.saved = true
        logHist(b, "saved", "Партия сохранена: ${rows.size} поз.", batchId)
        env.info("Партия сохранена: ${rows.size} позиций, $total шт.")
    }

    BackHandler(enabled = screen != 0) { screen = if (screen == 2) 1 else 0 }

    val products = active?.products
    val total = products?.size ?: 0
    val done = products?.count { it.actual > 0 } ?: 0
    val pct = if (total == 0) 0 else done * 100 / total

    WindowScaffold(
        title = "Приём товаров",
        actions = {
            if (screen == 1 && total > 0) TopBarTextButton("›") { screen = 2 }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (screen != 0 && total > 0) NrProgress(pct, done, total)
                val list = products
                if (screen == 0 || list == null) {
                    NrStart(
                        batches = batches,
                        onPick = {
                            picker.launch(
                                arrayOf(
                                    "application/pdf",
                                    "application/vnd.ms-excel",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "text/csv",
                                ),
                            )
                        },
                        onOpen = { b -> active = b; screen = 1 },
                        onHistory = { env.nav.push(Win.NrHistory) },
                    )
                } else if (screen == 1) {
                    NrList(
                        products = list,
                        onAdd = { addOpen = true },
                        onQty = { p -> qtyEdit = p to false },
                        onDamage = { p -> qtyEdit = p to true },
                        onDelete = { p -> deleteAsk = p },
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SoftButton("＋ Накладные", { docPicker.launch(arrayOf("image/*", "application/pdf")) }, Modifier.weight(1f))
                        SoftButton("📷 Снять", { shootDoc() }, Modifier.weight(1f))
                    }
                    HintText("📎 ${active?.docs?.size ?: 0} / ${AttachmentStore.MAX_DOCS}", Modifier.padding(horizontal = 16.dp))
                    active?.let { b ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LabeledInput(
                                label = "Отправитель",
                                value = b.sender,
                                onValueChange = { b.sender = it.take(60) },
                                modifier = Modifier.weight(1.4f),
                                placeholder = "Имя",
                            )
                            LabeledInput(
                                label = "№ заказа",
                                value = b.order,
                                onValueChange = { b.order = it.take(20) },
                                modifier = Modifier.weight(1f),
                                placeholder = "EB…",
                            )
                        }
                    }
                    if (printAsk) {
                        DialogCard(
                            title = "Выберите тип печати",
                            onDismiss = { printAsk = false },
                            actions = { DialogActionCancel("Отмена") { printAsk = false } },
                        ) {
                            SoftButton("📄 Полный отчёт", {
                                printAsk = false
                                val b = active
                                if (b != null) {
                                    val rows = b.products.mapIndexed { i, p ->
                                        listOf((i + 1).toString(), p.name, p.plan.toString(), p.actual.toString(), p.damage.toString())
                                    }
                                    val meta = listOf("Исполнитель склада: $NR_EXECUTOR", "Заказчик: STRÖH E-Commerce GmbH", "№ заказа: ${b.order.ifBlank { "-" }}")
                                        .joinToString(" · ")
                                    if (!ExportHelper.printTable(ctx, "АКТ ПРИЕМА ТОВАРОВ (ПОЛНЫЙ)", listOf("№", "Наименование", "План", "Факт", "Брак"), rows, meta)) {
                                        env.info("Не удалось открыть печать")
                                    }
                                }
                            }, Modifier.fillMaxWidth())
                            SoftButton("⚠️ Печать расхождения", {
                                printAsk = false
                                val b = active
                                if (b != null) {
                                    val sections = nrDiscrepancySections(b)
                                    if (sections.isEmpty()) {
                                        env.info("Расхождений нет — печатать нечего.")
                                    } else if (!ExportHelper.printSections(
                                            ctx, "Расхождения при приемке", "MITTEILUNG ÜBER ABWEICHUNGEN BEIM WARENEINGANG",
                                            listOf("Abteilung: $NR_EXECUTOR", "Bestell-Nr.: ${b.order.ifBlank { "-" }}", "Lieferant: ${b.sender.ifBlank { "-" }}"),
                                            sections,
                                        )
                                    ) {
                                        env.info("Не удалось открыть печать")
                                    }
                                }
                            }, Modifier.fillMaxWidth())
                        }
                    }
                    NrReport(
                        products = list, env = env, saved = active?.saved == true, onSave = { saveBatch() },
                        onPrint = { if (active != null) printAsk = true },
                        onExport = {
                            val b = active
                            if (b != null) {
                                val table = listOf(listOf("Наименование", "План", "Факт", "Брак", "Разница")) + reportRows(b)
                                val bytes = ExportHelper.xlsx(listOf("Приёмка" to table))
                                if (!ExportHelper.share(ctx, "Lager_priemka_${ExportHelper.stamp()}.xlsx", bytes, ExportHelper.XLSX)) env.info("Не удалось поделиться файлом")
                            }
                        },
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .shadow(6.dp, CircleShape)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(c.primary)
                    .md3Clickable(color = c.onPrimary) { shootDoc() },
                contentAlignment = Alignment.Center,
            ) { Text("📷", fontSize = 24.sp) }
        }
    }

    if (addOpen) {
        var name by remember { mutableStateOf("") }
        DialogCard(
            title = "Добавить товар",
            onDismiss = { addOpen = false },
            actions = {
                DialogActionCancel("Отмена") { addOpen = false }
                DialogActionConfirm(
                    "Добавить",
                    {
                        val n = name.trim()
                        val list = active?.products
                        if (n.isNotEmpty() && list != null) {
                            seq += 1
                            val p = NrProduct(seq, n, 0)
                            p.fresh = true
                            list.add(p)
                        }
                        addOpen = false
                    },
                )
            },
        ) {
            Text(
                "Введите наименование товара, которого нет в накладной:",
                fontSize = 14.sp,
                color = c.onSurfaceVariant,
            )
            LabeledInput(label = "", value = name, onValueChange = { name = it }, placeholder = "Название товара…")
        }
    }

    deleteAsk?.let { p ->
        DialogCard(
            title = "Удаление товара",
            onDismiss = { deleteAsk = null },
            actions = {
                DialogActionCancel("Нет") { deleteAsk = null }
                DialogActionConfirm("Да", { active?.products?.remove(p); deleteAsk = null }, danger = true)
            },
        ) {
            Text("Удалить позицию «${p.name}»?", fontSize = 14.sp, color = c.onSurface)
        }
    }

    qtyEdit?.let { (p, isDamage) ->
        RcvQtyDialog(
            title = if (isDamage) "Брак" else "Количество",
            name = p.name,
            initial = if (isDamage) p.damage else p.actual,
            onDismiss = { qtyEdit = null },
            onOk = { v ->
                if (isDamage) {
                    p.damage = v
                    if (p.actual < p.damage) p.actual = p.damage
                } else {
                    p.actual = maxOf(v, p.damage)
                }
                qtyEdit = null
            },
        )
    }
}

/** Полоса «Готово: N% · x/y позиций» (.nr-receive-progress). */
@Composable
private fun NrProgress(pct: Int, done: Int, total: Int) {
    val c = Md3.c
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surfaceLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(c.outlineVariant),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(pct / 100f)
                    .background(Color(0xFF2B8A3E)),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Готово: $pct%", fontSize = 11.sp, color = c.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Text("$done/$total позиций", fontSize = 11.sp, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun NrStart(
    batches: List<NrBatch>,
    onPick: () -> Unit,
    onOpen: (NrBatch) -> Unit,
    onHistory: () -> Unit,
) {
    val c = Md3.c
    val dash = c.outline
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 90.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RcvR16)
                .background(c.surfaceLow)
                .drawBehind {
                    drawRoundRect(
                        color = dash,
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
                        ),
                    )
                }
                .md3Clickable(onClick = onPick)
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📁", fontSize = 40.sp)
            Text("Выбрать файл", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = c.onSurface, modifier = Modifier.padding(top = 6.dp))
            Text("Excel или PDF", fontSize = 13.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }

        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Текущая сессия приёма",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = c.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    batches.size.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = c.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RcvR10)
                        .background(c.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (batches.isEmpty()) {
                Text(
                    "Здесь появятся успешно распознанные накладные.",
                    fontSize = 13.sp,
                    color = c.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RcvR14)
                        .border(1.dp, c.outlineVariant, RcvR14)
                        .padding(horizontal = 14.dp, vertical = 16.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    batches.forEach { b ->
                        val total = b.products.size
                        val matched = b.products.count { it.actual == it.plan && it.damage == 0 && it.actual > 0 }
                        val complete = total > 0 && matched == total
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RcvR14)
                                .background(c.card)
                                .border(1.dp, c.outlineVariant, RcvR14)
                                .md3Clickable { onOpen(b) }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                        ) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    b.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = c.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (complete) "Завершено" else "В работе",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (complete) c.success else c.warning,
                                    modifier = Modifier
                                        .clip(RcvR10)
                                        .background(if (complete) c.successContainer else c.warningContainer)
                                        .padding(horizontal = 7.dp, vertical = 4.dp),
                                )
                            }
                            Text(
                                "$total поз. · совпало $matched",
                                fontSize = 11.sp,
                                color = c.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clip(RcvR14)
                .background(c.card)
                .border(1.dp, c.outlineVariant, RcvR14)
                .md3Clickable(onClick = onHistory)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("🕒", fontSize = 18.sp)
            Text("История сканирований", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
        }
    }
}

@Composable
private fun NrList(
    products: List<NrProduct>,
    onAdd: () -> Unit,
    onQty: (NrProduct) -> Unit,
    onDamage: (NrProduct) -> Unit,
    onDelete: (NrProduct) -> Unit,
) {
    val c = Md3.c
    var query by remember { mutableStateOf("") }
    val matches = remember(query, products.size) {
        val words = query.trim().lowercase().split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) emptyList() else products.filter { p ->
            val n = p.name.lowercase()
            words.all { w -> n.contains(w) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surfaceLow)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Отпр: -", fontSize = 11.sp, color = c.onSurfaceVariant, maxLines = 1)
            Text("№: -", fontSize = 11.sp, color = c.onSurfaceVariant, maxLines = 1)
            Text("Дата: -", fontSize = 11.sp, color = c.onSurfaceVariant, maxLines = 1)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LabeledInput(
                    label = "",
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "Поиск товара…",
                )
                Box(
                    Modifier
                        .width(40.dp)
                        .height(46.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xFF2B8A3E))
                        .md3Clickable(color = Color.White, onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Text("+", color = Color.White, fontSize = 22.sp) }
            }
            if (query.isNotBlank()) {
                Column(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RcvR10)
                        .background(c.card)
                        .border(1.dp, c.outlineVariant, RcvR10)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (matches.isEmpty()) {
                        Text("Совпадений не найдено", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                    } else {
                        matches.forEach { p ->
                            Text(
                                p.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .md3Clickable { p.taps += 1; query = "" }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                            RowDivider()
                        }
                    }
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 90.dp),
        ) {
            if (products.isEmpty()) {
                EmptyHint("Список пуст — добавьте товар кнопкой «+»")
            }
            products.forEachIndexed { i, p ->
                key(p.id) {
                    NrRow(
                        p = p,
                        zebra = i % 2 == 1,
                        onQty = { onQty(p) },
                        onDamage = { onDamage(p) },
                        onDelete = { onDelete(p) },
                    )
                }
            }
        }
    }
}

/** Строка товара: тап = +1, свайп влево = «Брак» и «Удалить». */
@Composable
private fun NrRow(
    p: NrProduct,
    zebra: Boolean,
    onQty: () -> Unit,
    onDamage: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Md3.c
    val scope = rememberCoroutineScope()
    val maxPx = with(LocalDensity.current) { 110.dp.toPx() }
    val off = remember { Animatable(0f) }

    LaunchedEffect(p.fresh) {
        if (p.fresh) {
            delay(2400)
            p.fresh = false
        }
    }

    val state = if (p.actual == 0) 0 else if (p.actual == p.plan) 1 else 2
    val bg = when (state) {
        1 -> NrGreen
        2 -> NrYellow
        else -> if (zebra) c.surfaceLow else c.card
    }
    val fg = if (state == 0) c.onSurface else Color.White
    val sub = if (state == 0) c.onSurfaceVariant else Color.White.copy(alpha = 0.8f)
    val edge = when (state) {
        1 -> NrGreenEdge
        2 -> NrYellowEdge
        else -> Color.Transparent
    }

    Box(Modifier.fillMaxWidth().background(Color(0xFF8B2D2D))) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .width(55.dp)
                    .fillMaxHeight()
                    .background(Color(0xFFD97706))
                    .md3Clickable(color = Color.White) {
                        scope.launch { off.animateTo(0f, tween(180)) }
                        onDamage()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("Брак: ${p.damage}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            }
            Box(
                Modifier
                    .width(55.dp)
                    .fillMaxHeight()
                    .background(Color(0xFFC92A2A))
                    .md3Clickable(color = Color.White) {
                        scope.launch { off.animateTo(0f, tween(180)) }
                        onDelete()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("Удалить", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = off.value }
                .newProductPulse(p.fresh)
                .tapFeedback(p.taps)
                .background(bg)
                .drawBehind {
                    if (edge != Color.Transparent) {
                        drawRect(edge, size = Size(4.dp.toPx(), size.height))
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch { off.animateTo(if (off.value < -maxPx / 2f) -maxPx else 0f, tween(180)) }
                        },
                        onDragCancel = {
                            scope.launch { off.animateTo(0f, tween(180)) }
                        },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            scope.launch { off.snapTo((off.value + dx).coerceIn(-maxPx, 0f)) }
                        },
                    )
                }
                .md3Clickable(color = fg) {
                    if (off.value < -1f) {
                        scope.launch { off.animateTo(0f, tween(180)) }
                    } else {
                        p.actual = minOf(99999, p.actual + 1)
                        if (p.actual < p.damage) p.actual = p.damage
                        p.taps += 1
                    }
                }
                .heightIn(min = 52.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("Пл: ${p.plan}", color = sub, fontSize = 11.sp)
            }
            Box(
                Modifier
                    .defaultMinSize(minWidth = 44.dp, minHeight = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (state == 0) Color.Transparent else Color.White.copy(alpha = 0.16f))
                    .border(1.dp, if (state == 0) c.outlineVariant else Color.White.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .md3Clickable(color = fg, onClick = onQty),
                contentAlignment = Alignment.Center,
            ) {
                Text(p.actual.toString(), color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NrReport(products: List<NrProduct>, env: WinEnv, saved: Boolean, onSave: () -> Unit, onPrint: () -> Unit, onExport: () -> Unit) {
    val c = Md3.c
    var filter by remember { mutableStateOf(0) }
    val matched = products.count { it.actual == it.plan && it.damage == 0 }
    val mismatch = products.size - matched
    val shown = if (filter == 1) products.filter { it.actual == 0 && it.damage == 0 } else products
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 90.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("🖨️ Печать", onPrint, Modifier.weight(1f))
            SoftButton("📊 Экспорт", onExport, Modifier.weight(1f))
            SoftButton(if (saved) "✓ Сохранено" else "💾 Сохранить", onSave, Modifier.weight(1f), filled = !saved)
        }
        Text(
            "Совпало: $matched · Расхождений: $mismatch",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
        )
        SegmentedPill(listOf("Все", "Не сверялось"), filter, { filter = it })
        Spacer(Modifier.height(10.dp))
        if (shown.isEmpty()) {
            Text(
                "Нет товаров для отображения",
                fontSize = 12.sp,
                color = c.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            )
        } else {
            Md3Card {
                shown.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                            Text("План: ${p.plan}", fontSize = 10.sp, color = c.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (p.damage > 0) {
                                Text("Брак: ${p.damage}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RcvRed)
                            }
                            Text("Факт: ${p.actual}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
                        }
                    }
                    RowDivider()
                }
            }
        }
        Text(
            "Приёмка по имени · Lager",
            fontSize = 11.sp,
            color = c.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

// =====================================================================
//  3. Автоприём (#autoReceiveModal)
// =====================================================================

private enum class ArStatus { Waiting, Processing, Success, Error }

private class ArFile(val name: String, val path: String, val uri: Uri, val status: ArStatus = ArStatus.Waiting)

private fun arAllowed(name: String, mime: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext == "pdf" || ext == "png" || ext == "jpg" || ext == "jpeg") return true
    val m = mime.lowercase()
    return m == "application/pdf" || m == "image/png" || m == "image/jpeg"
}

private fun arIcon(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "📄"
    "xlsx", "xls", "csv" -> "📊"
    "jpg", "jpeg", "png", "webp", "gif" -> "🖼️"
    else -> "📎"
}

private fun arFolderName(tree: Uri?): String? {
    if (tree == null) return null
    return runCatching {
        val id = DocumentsContract.getTreeDocumentId(tree)
        id.substringAfterLast(':').substringAfterLast('/').ifEmpty { id }
    }.getOrNull()
}

private fun arListFolder(ctx: Context, tree: Uri): List<ArFile> {
    val out = ArrayList<ArFile>()
    fun walk(docId: String, path: String, depth: Int) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        runCatching {
            ctx.contentResolver.query(children, cols, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getString(0) ?: continue
                    val name = cur.getString(1) ?: continue
                    val mime = cur.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 4) walk(id, "$path$name/", depth + 1)
                    } else if (arAllowed(name, mime)) {
                        out.add(ArFile(name, path + name, DocumentsContract.buildDocumentUriUsingTree(tree, id)))
                    }
                }
            }
        }
    }
    walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
    return out.sortedBy { it.path.lowercase() }
}

private fun arNormalizeUrl(raw: String): String? {
    val v = raw.trim().trimEnd('/')
    return if ((v.startsWith("http://") || v.startsWith("https://")) && v.length > 8) v else null
}

private fun arPing(base: String): Boolean = runCatching {
    val conn = java.net.URL("$base/get-config").openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 4000
    conn.readTimeout = 4000
    conn.setRequestProperty("Accept", "application/json")
    try {
        conn.responseCode in 200..299
    } finally {
        conn.disconnect()
    }
}.getOrDefault(false)

private fun arFilesWord(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> "файлов"
        m10 == 1 -> "файл"
        m10 in 2..4 -> "файла"
        else -> "файлов"
    }
}

@Composable
private fun ArChip(
    label: String,
    count: Int,
    selected: Boolean,
    dot: Color?,
    onClick: () -> Unit,
) {
    val c = Md3.c
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .heightIn(min = 38.dp)
            .clip(shape)
            .background(if (selected) c.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else c.outlineVariant, shape)
            .md3Clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) c.onSecondaryContainer else c.onSurfaceVariant,
        )
        Text(count.toString(), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = c.onSurface)
    }
}

@Composable
fun AutoReceiveWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("lager_auto_receive", Context.MODE_PRIVATE) }

    var treeUri by remember { mutableStateOf(prefs.getString("tree", null)?.let { Uri.parse(it) }) }
    var files by remember { mutableStateOf<List<ArFile>>(emptyList()) }
    var filter by remember { mutableStateOf(0) } // 0 все, 1 ждут, 2 обработка, 3 готово, 4 ошибки
    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var serverText by remember { mutableStateOf("") }
    var serverKind by remember { mutableStateOf(0) } // 0 нейтрально, 1 успех, 2 ошибка

    fun refresh() {
        val t = treeUri
        scope.launch {
            files = withContext(Dispatchers.IO) {
                val shared = ShareState.saved(ctx).filter { arAllowed(it.name, "") }
                    .map { ArFile(it.name, "Share Target/" + it.name, Uri.fromFile(it)) }
                (if (t != null) arListFolder(ctx, t) else emptyList()) + shared
            }
        }
    }

    LaunchedEffect(treeUri, ShareState.version) { refresh() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            prefs.edit().putString("tree", uri.toString()).apply()
            treeUri = uri
        }
    }

    val waiting = files.count { it.status == ArStatus.Waiting }
    val processing = files.count { it.status == ArStatus.Processing }
    val success = files.count { it.status == ArStatus.Success }
    val errors = files.count { it.status == ArStatus.Error }
    val effectiveFilter = if (filter == 4 && errors == 0) 0 else filter
    val visible = when (effectiveFilter) {
        1 -> files.filter { it.status == ArStatus.Waiting }
        2 -> files.filter { it.status == ArStatus.Processing }
        3 -> files.filter { it.status == ArStatus.Success }
        4 -> files.filter { it.status == ArStatus.Error }
        else -> files
    }

    Surface(Modifier.fillMaxSize(), color = c.surface, contentColor = c.onSurface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // шапка (.md3-appbar)
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .md3Clickable { folderPicker.launch(null) },
                ) {
                    Text("Автоприем", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface, maxLines = 1)
                    Text(
                        arFolderName(treeUri) ?: "Папка не выбрана",
                        fontSize = 12.sp,
                        color = c.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).md3Clickable { folderPicker.launch(null) },
                    contentAlignment = Alignment.Center,
                ) { Text("📁", fontSize = 22.sp) }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).md3Clickable { refresh() },
                    contentAlignment = Alignment.Center,
                ) { Text("🔄", fontSize = 22.sp) }
            }

            // дневной расход токенов (.gemini-limit-bar)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(c.primary.copy(alpha = 0.12f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(0f).background(c.primary))
            }

            // фильтры (.md3-chips)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArChip("Все", files.size, effectiveFilter == 0, null) { filter = 0 }
                ArChip("Ожидают", waiting, effectiveFilter == 1, c.warning) { filter = 1 }
                ArChip("Обработка", processing, effectiveFilter == 2, c.primary) { filter = 2 }
                ArChip("Готово", success, effectiveFilter == 3, c.success) { filter = 3 }
                if (errors > 0) ArChip("Ошибки", errors, effectiveFilter == 4, c.error) { filter = 4 }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // «Выбрать папку»
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RcvR16)
                        .background(c.primaryContainer)
                        .md3Clickable(color = c.onPrimaryContainer) { folderPicker.launch(null) }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("📁", fontSize = 18.sp)
                    Text("Выбрать папку", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onPrimaryContainer)
                }

                // адрес Termux/Python-сервера
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RcvR12)
                        .background(c.card)
                        .border(1.dp, c.outlineVariant, RcvR12)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        "Адрес Termux/Python-сервера",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = c.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        LabeledInput(
                            label = "",
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.weight(1f),
                            placeholder = "http://localhost:8050",
                            keyboardType = KeyboardType.Uri,
                        )
                        SoftButton(
                            "Сохранить",
                            {
                                val u = arNormalizeUrl(url)
                                if (u == null) {
                                    serverText = "Введите полный адрес, например http://localhost:8050"
                                    serverKind = 2
                                } else {
                                    prefs.edit().putString("url", u).apply()
                                    url = u
                                    serverText = "Адрес сохранён: $u"
                                    serverKind = 1
                                }
                            },
                            filled = true,
                        )
                        SoftButton(
                            "Проверить",
                            {
                                val u = arNormalizeUrl(url)
                                if (u == null) {
                                    serverText = "Введите полный адрес, например http://localhost:8050"
                                    serverKind = 2
                                } else {
                                    serverText = "Проверка соединения…"
                                    serverKind = 0
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { arPing(u) }
                                        serverText = if (ok) "Сервер отвечает: $u" else "Нет ответа от $u"
                                        serverKind = if (ok) 1 else 2
                                    }
                                }
                            },
                        )
                    }
                    if (serverText.isNotEmpty()) {
                        Text(
                            serverText,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = when (serverKind) {
                                1 -> c.success
                                2 -> c.error
                                else -> c.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                Text(
                    "${files.size} ${arFilesWord(files.size)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                )

                if (visible.isEmpty()) {
                    Text(
                        when {
                            treeUri == null && files.isEmpty() -> "Выберите папку или отправьте файлы через «Поделиться»."
                            files.isEmpty() -> "В выбранной папке пока нет файлов."
                            else -> "Нет файлов с таким статусом."
                        },
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = c.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 30.dp),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visible.forEach { f ->
                            val label = when (f.status) {
                                ArStatus.Success -> "Обработано"
                                ArStatus.Processing -> "Обрабатывается"
                                ArStatus.Error -> "Ошибка обработки"
                                ArStatus.Waiting -> "Ожидает обработки"
                            }
                            val metaColor = when (f.status) {
                                ArStatus.Success -> c.success
                                ArStatus.Processing -> c.primary
                                ArStatus.Error -> c.error
                                ArStatus.Waiting -> c.warning
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp)
                                    .clip(RcvR16)
                                    .background(c.card)
                                    .border(1.dp, c.outlineVariant, RcvR16)
                                    .then(
                                        if (f.status == ArStatus.Error) {
                                            Modifier.md3Clickable { env.info("Повтор обработки — в разработке") }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(arIcon(f.name), fontSize = 20.sp)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        f.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = c.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(label, fontSize = 12.sp, color = metaColor, modifier = Modifier.padding(top = 3.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


// ---------- Товар не найден (#receiveUnknownNameModal) ----------

@Composable
private fun RcvUnknownNameDialog(ean: String, onSkip: () -> Unit, onSave: (String) -> Unit) {
    val c = Md3.c
    var name by remember(ean) { mutableStateOf("") }
    var error by remember(ean) { mutableStateOf("") }
    // Подсказки из каталога: от трёх букв, не больше 8 (receiveUnknownNameInputChanged).
    val suggestions = remember(name) {
        if (name.trim().length < 3) emptyList() else CatalogStore.search(name).take(8)
    }
    val save = {
        val n = name.trim()
        if (n.isEmpty()) {
            error = "Введите название товара"
        } else {
            onSave(n)
        }
    }
    DialogCard(
        title = "Товар не найден",
        onDismiss = onSkip,
        actions = {
            DialogActionCancel("Пропустить", onSkip)
            DialogActionConfirm("Добавить в приёмку", { save() })
        },
    ) {
        Text(
            "Введите название товара. После сохранения он появится в каталоге и будет добавлен в приёмку.",
            fontSize = 14.sp, color = c.onSurfaceVariant,
        )
        Text("EAN: $ean", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        LabeledInput("", name, { name = it; error = "" }, placeholder = "Название товара")
        suggestions.forEach { item ->
            Text(
                item.name + if (item.ean.isNotEmpty()) " · ${item.ean}" else "",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surfaceLow)
                    .md3Clickable { name = item.name; error = "" }
                    .padding(12.dp),
            )
        }
        if (error.isNotEmpty()) StatusLine(error, StatusKind.Err, Modifier.fillMaxWidth())
    }
}
