package ru.lager.app.ui.win

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.lager.app.ui.BarcodeScanIcon

// ---------- Инвентаризация ----------

private fun invStamp(ts: Long): String =
    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(ts))

@Composable
fun InventoryWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var ean by rememberSaveable { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusErr by remember { mutableStateOf(false) }
    var qtyEdit by remember { mutableStateOf<Pair<InvLoc, InvItem>?>(null) }
    var deleteLoc by remember { mutableStateOf<InvLoc?>(null) }
    var nameAsk by remember { mutableStateOf<String?>(null) }
    var settingsPicker by remember { mutableStateOf(false) }
    var expiryItem by remember { mutableStateOf<Pair<InvLoc, InvItem>?>(null) }
    var qrFor by remember { mutableStateOf<InvLoc?>(null) }
    var rowEdit by remember { mutableStateOf<Pair<InvLoc, InvItem>?>(null) }
    var addrEdit by remember { mutableStateOf<InvLoc?>(null) }
    var mergeAsk by remember { mutableStateOf<Pair<InvLoc, InvAddr>?>(null) }

    LaunchedEffect(Unit) {
        CatalogStore.load(ctx)
        InventoryStore.load(ctx)
    }

    // Адрес и товар приходят с одного сканера: формат кода определяет, что это.
    fun handle(raw: String) {
        val code = raw.trim()
        if (code.isEmpty()) return
        val addr = InventoryStore.parseAddress(code)
        if (addr != null) {
            InventoryStore.setCurrent(ctx, addr)
            status = "Место: ${addr.label}"
            statusErr = false
            ean = ""
            return
        }
        val res = InventoryStore.addProduct(ctx, code)
        if (res == null) {
            status = "Сначала выберите место хранения"
            statusErr = true
            return
        }
        ean = ""
        if (SettingsStore.vibration(ctx) > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        status = "Добавлено: ${res.name} · ${InventoryStore.current?.label.orEmpty()}" +
            (if (InventoryStore.expiryEnabled) " · срок ${InventoryStore.expiryLabelOf(InventoryStore.expiryValue())}" else "") +
            (if (res.known) "" else " · нет в каталоге")
        statusErr = !res.known
        if (!res.known && res.barcode.isNotEmpty()) nameAsk = res.barcode
    }

    val currentKey = InventoryStore.current?.key
    val locs = InventoryStore.locations.toList().sortedWith(
        compareByDescending<InvLoc> { it.addr.key == currentKey }.thenByDescending { it.updatedAt },
    )

    WindowScaffold(
        title = "Инвентаризация",
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("📥 Импорт Excel", { env.nav.push(Win.InventoryImport) }, Modifier.weight(1f), filled = true)
                SoftButton("📊 Экспорт", { env.nav.push(Win.InventoryExport) }, Modifier.weight(1f))
            }
        },
    ) {
        ScrollBody {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddressPart("Склад", InventoryStore.dWarehouse.ifEmpty { "—" }, { picker = "warehouse" }, Modifier.weight(1f))
                AddressPart("Ряд", InventoryStore.dRow, { picker = "row" }, Modifier.weight(1f))
                AddressPart("Этаж", InventoryStore.dFloor, { picker = "floor" }, Modifier.weight(1f))
                AddressPart("Полка", InventoryStore.dShelf, { picker = "shelf" }, Modifier.weight(1f))
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                StatusLine(status, if (statusErr) StatusKind.Warn else StatusKind.Ok, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))
            LabeledInput(
                label = "EAN",
                value = ean,
                onValueChange = { ean = it.filter { ch -> ch.isDigit() } },
                placeholder = "Введите EAN",
                keyboardType = KeyboardType.Number,
                trailing = {
                    Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).md3Clickable { handle(ean) },
                            contentAlignment = Alignment.Center,
                        ) { Text("＋", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.primary) }
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).md3Clickable { scanning = true },
                            contentAlignment = Alignment.Center,
                        ) { BarcodeScanIcon(c.primary, Modifier.size(24.dp)) }
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            ExpiryCard { part ->
                InventoryStore.expiryMode = part
                InventoryStore.expiryEnabled = true
                InventoryStore.saveExpiry(ctx)
                settingsPicker = true
            }
            Spacer(Modifier.height(10.dp))
            if (locs.isEmpty()) {
                EmptyHint("Отсканированные товары появятся здесь")
            } else {
                locs.forEach { loc ->
                    val isCur = loc.addr.key == currentKey
                    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceLow)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (isCur) c.primaryContainer else c.surfaceHigh)
                                .md3Clickable { InventoryStore.setCurrent(ctx, loc.addr); status = "Место: ${loc.addr.label}"; statusErr = false }
                                .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(loc.addr.label.ifEmpty { "—" }, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                                Text(
                                    "${loc.items.size} поз. · ${loc.items.sumOf { it.quantity }} шт. · ${invStamp(loc.updatedAt)}",
                                    fontSize = 11.sp, color = c.onSurfaceVariant,
                                )
                            }
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).md3Clickable { addrEdit = loc },
                                contentAlignment = Alignment.Center,
                            ) { Text("✎", fontSize = 20.sp, color = c.primary) }
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).md3Clickable { qrFor = loc },
                                contentAlignment = Alignment.Center,
                            ) { Text("▦", fontSize = 22.sp, color = c.primary) }
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).md3Clickable { deleteLoc = loc },
                                contentAlignment = Alignment.Center,
                            ) { Text("🗑️", fontSize = 18.sp) }
                        }
                        loc.items.sortedBy { it.name.lowercase() }.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    if (item.barcode.isNotEmpty()) Text(item.barcode, fontSize = 11.sp, color = c.onSurfaceVariant)
                                    val hasExp = item.expiry.isNotEmpty()
                                    Text(
                                        "📅 " + if (hasExp) InventoryStore.expiryLabelOf(item.expiry) else "указать срок",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (hasExp) c.primary else c.onSurfaceVariant,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .md3Clickable { expiryItem = loc to item }
                                            .padding(vertical = 3.dp),
                                    )
                                }
                                Box(
                                    Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).md3Clickable { rowEdit = loc to item },
                                    contentAlignment = Alignment.Center,
                                ) { Text("✎", fontSize = 17.sp, color = c.onSurfaceVariant) }
                                val shape = RoundedCornerShape(8.dp)
                                Box(
                                    Modifier
                                        .defaultMinSize(minWidth = 52.dp, minHeight = 36.dp)
                                        .clip(shape)
                                        .border(1.dp, c.outlineVariant, shape)
                                        .md3Clickable { qtyEdit = loc to item }
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(item.quantity.toString(), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            }
                            RowDivider()
                        }
                    }
                }
            }
            Text(
                "Инвентаризация готова",
                color = c.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

    if (scanning) {
        BarcodeScannerDialog(
            onResult = { code -> scanning = false; handle(code) },
            onDismiss = { scanning = false },
        )
    }

    picker?.let { part ->
        if (part == "warehouse") {
            var text by remember { mutableStateOf(InventoryStore.dWarehouse) }
            var err by remember { mutableStateOf("") }
            val save = {
                if (InventoryStore.setPart(ctx, "warehouse", text)) {
                    picker = null
                } else {
                    err = "Введите название склада"
                }
            }
            DialogCard(
                title = "Склад",
                onDismiss = { picker = null },
                actions = {
                    DialogActionCancel("Отмена") { picker = null }
                    DialogActionConfirm("Сохранить", { save() })
                },
            ) {
                LabeledInput("", text, { text = it }, placeholder = "Название склада")
                if (err.isNotEmpty()) StatusLine(err, StatusKind.Err, Modifier.fillMaxWidth())
                val known = InventoryStore.knownWarehouses()
                if (known.isNotEmpty()) {
                    FieldLabel("Уже используются")
                    known.forEach { w ->
                        Text(
                            w,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.surfaceLow)
                                .md3Clickable { InventoryStore.setPart(ctx, "warehouse", w); picker = null }
                                .padding(12.dp),
                        )
                    }
                }
            }
        } else {
            val title = when (part) { "row" -> "Ряд"; "floor" -> "Этаж"; else -> "Полка" }
            val selected = when (part) { "row" -> InventoryStore.dRow; "floor" -> InventoryStore.dFloor; else -> InventoryStore.dShelf }
            val options = listOf("--") + (1..99).map { it.toString().padStart(2, '0') }
            DialogCard(title = title, onDismiss = { picker = null }) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(options.chunked(5)) { chunk ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            chunk.forEach { v ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (v == selected) c.primary else c.surfaceHigh)
                                        .md3Clickable {
                                            if (InventoryStore.dWarehouse.isEmpty()) {
                                                status = "Сначала выберите склад"; statusErr = true
                                            } else {
                                                InventoryStore.setPart(ctx, part, v)
                                                InventoryStore.current?.let { status = "Место: ${it.label}"; statusErr = false }
                                            }
                                            picker = null
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { Text(v, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (v == selected) c.onPrimary else c.onSurface) }
                            }
                            repeat(5 - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    if (settingsPicker) {
        ExpiryPickerDialog(
            title = "Срок годности",
            initialMode = InventoryStore.expiryMode,
            initialYear = InventoryStore.expiryYear,
            initialMonth = InventoryStore.expiryMonth,
            initialDay = InventoryStore.expiryDay,
            onDismiss = { settingsPicker = false },
            onSave = { mode, y, m, d ->
                InventoryStore.expiryMode = mode
                InventoryStore.expiryYear = y
                InventoryStore.expiryMonth = m
                InventoryStore.expiryDay = d
                InventoryStore.expiryEnabled = true
                InventoryStore.saveExpiry(ctx)
                settingsPicker = false
                status = "Срок применён: ${InventoryStore.expiryLabelOf(InventoryStore.expiryValue())}"
                statusErr = false
            },
        )
    }

    expiryItem?.let { (loc, item) ->
        val p = InventoryStore.parseExpiry(item.expiry)
        val clear: (() -> Unit)? = if (item.expiry.isNotEmpty()) ({
            InventoryStore.setItemExpiry(ctx, loc.addr, item.key, "")
            expiryItem = null
            status = "Срок убран: ${item.name}"; statusErr = false
        }) else null
        ExpiryPickerDialog(
            title = "Срок: ${item.name.take(36)}",
            initialMode = (p?.get(0) as? String) ?: InventoryStore.expiryMode,
            initialYear = (p?.get(1) as? Int) ?: InventoryStore.expiryYear,
            initialMonth = (p?.get(2) as? Int) ?: InventoryStore.expiryMonth,
            initialDay = (p?.get(3) as? Int) ?: InventoryStore.expiryDay,
            onDismiss = { expiryItem = null },
            onClear = clear,
            onSave = { mode, y, m, d ->
                val v = InventoryStore.expiryString(mode, y, m, d)
                InventoryStore.setItemExpiry(ctx, loc.addr, item.key, v)
                expiryItem = null
                status = "Срок применён: ${InventoryStore.expiryLabelOf(v)}"; statusErr = false
            },
        )
    }

    qrFor?.let { loc ->
        val bmp = remember(loc.addr.key) { InventoryQr.bitmap(InventoryQr.payload(loc.addr), 512).asImageBitmap() }
        DialogCard(
            title = "QR-этикетка места",
            onDismiss = { qrFor = null },
            actions = {
                DialogActionCancel("Закрыть") { qrFor = null }
                DialogActionConfirm("🖨 Печать", { if (!InventoryQr.print(ctx, loc.addr)) env.info("Печать недоступна") })
            },
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
                    Image(bmp, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.size(220.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(loc.addr.label.ifEmpty { "—" }, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            }
            HintText("Отсканируйте код в инвентаризации — место выберется автоматически.")
        }
    }

    qtyEdit?.let { (loc, item) ->
        RcvQtyDialog(
            title = "Количество (0 — удалить)",
            name = item.name,
            initial = item.quantity,
            onDismiss = { qtyEdit = null },
            onOk = { v -> InventoryStore.setQuantity(ctx, loc.addr, item.key, v); qtyEdit = null },
        )
    }

    deleteLoc?.let { loc ->
        CatConfirmDialog(
            title = "Удалить место?",
            text = "«${loc.addr.label}» и все товары на нём (${loc.items.size} поз.). История сканирований останется.",
            yes = "Да, удалить",
            onYes = {
                InventoryStore.deleteLocation(ctx, loc.addr)
                if (InventoryStore.current?.key == loc.addr.key) InventoryStore.setCurrent(ctx, null)
                deleteLoc = null
            },
            onNo = { deleteLoc = null },
        )
    }

    addrEdit?.let { loc ->
        InvAddrEditDialog(
            loc = loc,
            onDismiss = { addrEdit = null },
            onSave = { next ->
                if (next.key == loc.addr.key) {
                    addrEdit = null
                } else if (InventoryStore.locations.any { it.addr.key == next.key }) {
                    mergeAsk = loc to next
                } else {
                    InventoryStore.moveLocation(ctx, loc.addr, next)
                    status = "Адрес изменён: ${next.label}"; statusErr = false
                    addrEdit = null
                }
            },
        )
    }

    mergeAsk?.let { (loc, next) ->
        val dest = InventoryStore.locations.firstOrNull { it.addr.key == next.key }
        DialogCard(
            title = "Адрес уже существует",
            onDismiss = { mergeAsk = null },
            actions = {
                DialogActionCancel("Нет") { mergeAsk = null }
                DialogActionConfirm("Да, объединить", {
                    InventoryStore.moveLocation(ctx, loc.addr, next)
                    status = "Адрес изменён: ${next.label}"; statusErr = false
                    mergeAsk = null
                    addrEdit = null
                })
            },
        ) {
            Text("↔", fontSize = 32.sp, color = c.primary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(
                "Адрес ${next.label} уже есть. Объединить товары? (${loc.items.size} + ${dest?.items?.size ?: 0} позиций)",
                fontSize = 14.sp, color = c.onSurfaceVariant,
            )
        }
    }

    rowEdit?.let { (loc, item) ->
        InvRowEditDialog(
            loc = loc,
            item = item,
            onDismiss = { rowEdit = null },
            onSave = { name, barcode, qty, expiry, addr ->
                val err = InventoryStore.editItem(ctx, loc.addr, item.key, name, barcode, qty, expiry, addr)
                if (err == null) {
                    if (InventoryStore.current?.key == loc.addr.key && addr.key != loc.addr.key) {
                        InventoryStore.setCurrent(ctx, addr)
                    }
                    status = "Строка изменена: ${name.trim()}"; statusErr = false
                    rowEdit = null
                }
                err
            },
        )
    }

    nameAsk?.let { barcode ->
        var name by remember(barcode) { mutableStateOf("") }
        DialogCard(
            title = "Товар не найден",
            onDismiss = { nameAsk = null },
            actions = {
                DialogActionCancel("Позже") { nameAsk = null }
                DialogActionConfirm("Сохранить", {
                    if (name.isNotBlank()) InventoryStore.nameBarcode(ctx, barcode, name)
                    nameAsk = null
                })
            },
        ) {
            Text("Введите имя товара, чтобы сохранить его в базе.", fontSize = 14.sp, color = c.onSurfaceVariant)
            Text(barcode, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            LabeledInput("", name, { name = it }, placeholder = "Имя товара")
        }
    }
}

// ---------- Срок годности ----------

@Composable
private fun ExpChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Md3.c
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) c.primaryContainer else c.surfaceHigh)
            .border(1.dp, if (selected) c.primary else c.outlineVariant, shape)
            .md3Clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (selected) c.onPrimaryContainer else c.onSurface,
        )
    }
}

/** Строка «Срок годности»: переключатель и три кнопки — год, месяц, дата (.inventory-expiry-row). */
@Composable
private fun ExpiryCard(onPick: (String) -> Unit) {
    val c = Md3.c
    val ctx = LocalContext.current
    val on = InventoryStore.expiryEnabled
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceLow)
            .border(1.dp, c.outlineVariant, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Срок годности", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(
                if (on) InventoryStore.expiryLabelOf(InventoryStore.expiryValue()) else "не выбран",
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.primary, maxLines = 1,
            )
            Md3Switch(on) { InventoryStore.expiryEnabled = it; InventoryStore.saveExpiry(ctx) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("year", "month", "day").forEach { part ->
                val text = if (!on) {
                    when (part) { "year" -> "1 — год"; "month" -> "2 — месяц"; else -> "3 — дата" }
                } else when (part) {
                    "year" -> "${InventoryStore.expiryYear} г."
                    "month" -> "%02d. %s".format(InventoryStore.expiryMonth, InventoryStore.MONTHS[InventoryStore.expiryMonth - 1])
                    else -> "%02d.%02d.%d".format(InventoryStore.expiryDay, InventoryStore.expiryMonth, InventoryStore.expiryYear)
                }
                ExpChip(text, on && InventoryStore.expiryMode == part, Modifier.weight(1f)) { onPick(part) }
            }
        }
    }
}

/** Выбор срока: режим (год / месяц / дата), лента годов, месяцы и календарь. */
@Composable
private fun ExpiryPickerDialog(
    title: String,
    initialMode: String,
    initialYear: Int,
    initialMonth: Int,
    initialDay: Int,
    onDismiss: () -> Unit,
    onSave: (mode: String, year: Int, month: Int, day: Int) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val c = Md3.c
    var mode by remember { mutableStateOf(initialMode) }
    var year by remember { mutableStateOf(initialYear.coerceIn(InventoryStore.MIN_YEAR, InventoryStore.MAX_YEAR)) }
    var month by remember { mutableStateOf(initialMonth.coerceIn(1, 12)) }
    var day by remember { mutableStateOf(initialDay.coerceAtLeast(1)) }
    val maxDay = InventoryStore.daysIn(year, month)
    val effDay = minOf(day, maxDay)
    val years = (InventoryStore.MIN_YEAR..InventoryStore.MAX_YEAR).toList()
    val yearsState = rememberLazyListState(initialFirstVisibleItemIndex = (year - InventoryStore.MIN_YEAR - 2).coerceAtLeast(0))

    DialogCard(
        title = title,
        onDismiss = onDismiss,
        actions = {
            DialogActionCancel("Отмена", onDismiss)
            DialogActionConfirm("Сохранить срок", { onSave(mode, year, month, effDay) })
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("year" to "Год", "month" to "Месяц", "day" to "Дата").forEach { (m, label) ->
                ExpChip(label, mode == m, Modifier.weight(1f)) { mode = m }
            }
        }
        FieldLabel("Год")
        LazyRow(state = yearsState, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(years) { y ->
                ExpChip(y.toString(), y == year, Modifier.width(62.dp)) { year = y }
            }
        }
        if (mode != "year") {
            FieldLabel("Месяц")
            (0 until 12).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { i ->
                        ExpChip(InventoryStore.MONTHS[i], month == i + 1, Modifier.weight(1f)) { month = i + 1 }
                    }
                }
            }
        }
        if (mode == "day") {
            FieldLabel("День")
            Row(Modifier.fillMaxWidth()) {
                listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
                    Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            val offset = java.time.LocalDate.of(year, month, 1).dayOfWeek.value - 1
            val cells = List(offset) { 0 } + (1..maxDay).toList()
            val padded = cells + List((7 - cells.size % 7) % 7) { 0 }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                padded.chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { n ->
                            if (n == 0) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                val sel = n == effDay
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) c.primary else c.surfaceHigh)
                                        .md3Clickable { day = n },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(n.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (sel) c.onPrimary else c.onSurface)
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            InventoryStore.expiryLabelOf(InventoryStore.expiryString(mode, year, month, effDay)),
            fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = c.primary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        if (onClear != null) SoftButton("Убрать срок", onClear, Modifier.fillMaxWidth())
    }
}

// ---------- Импорт Excel в инвентаризацию ----------

private fun invGuess(headers: List<String>, keys: List<String>): Int =
    headers.indexOfFirst { h -> keys.any { h.lowercase().contains(it) } }.let { if (it < 0) 0 else it + 1 }

@Composable
fun InventoryImportWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf("") }
    var table by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var iName by remember { mutableStateOf(0) }
    var iQty by remember { mutableStateOf(0) }
    var iEan by remember { mutableStateOf(0) }
    var iAddr by remember { mutableStateOf(0) }
    var iExp by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        CatalogStore.load(ctx)
        InventoryStore.load(ctx)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val title = AttachmentStore.displayName(ctx, uri)
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { TableReader.read(ctx, uri, title) } }
                val t = r.getOrNull()
                if (t == null || t.size < 2) {
                    error = r.exceptionOrNull()?.message ?: "В файле нет данных"
                    table = emptyList()
                } else {
                    error = ""
                    fileName = title
                    table = t
                    val h = t[0]
                    iName = invGuess(h, listOf("наим", "name", "товар", "продукт", "bezeichnung", "описание"))
                    iQty = invGuess(h, listOf("кол", "qty", "menge", "quant", "факт"))
                    iEan = invGuess(h, listOf("ean", "штрих", "barcode"))
                    iAddr = invGuess(h, listOf("адрес", "address", "место", "lager"))
                    iExp = invGuess(h, listOf("срок", "годн", "expir", "haltbar", "mhd"))
                }
            }
        }
    }

    val headers = table.firstOrNull().orEmpty()
    val options = listOf("— не выбрано —") + headers.mapIndexed { i, h -> h.trim().ifEmpty { "Столбец ${i + 1}" } }
    val ready = table.size >= 2 && (iName > 0 || iEan > 0) && iQty > 0

    fun cell(row: List<String>, idx: Int) = if (idx > 0) row.getOrNull(idx - 1).orEmpty().trim() else ""

    WindowScaffold(
        title = "📥 Импорт Excel в строки",
        footer = {
            LongButton("📥 Добавить в сканированные строки", LongKind.Blue, {
                val cur = InventoryStore.current
                var added = 0
                var skipped = 0
                table.drop(1).forEach { r ->
                    val qty = cell(r, iQty).replace(Regex("\\s"), "").replace(',', '.').toDoubleOrNull()?.toInt() ?: 0
                    val addr = (if (iAddr > 0) InventoryStore.parseAddress(cell(r, iAddr)) else null) ?: cur
                    if (qty <= 0 || addr == null || (cell(r, iName).isEmpty() && cell(r, iEan).isEmpty())) {
                        skipped++
                    } else {
                        InventoryStore.addQuantity(ctx, addr, cell(r, iEan), cell(r, iName), qty, InventoryStore.normalizeExpiry(cell(r, iExp)))
                        added++
                    }
                }
                InventoryStore.commitImport(ctx)
                if (added == 0 && cur == null && iAddr == 0) env.info("Выберите место хранения в инвентаризации или столбец с адресом")
                else {
                    env.info("Добавлено строк: $added" + if (skipped > 0) " · пропущено: $skipped" else "")
                    if (added > 0) env.nav.pop()
                }
            }, enabled = ready)
        },
    ) {
        ScrollBody {
            HintText("Строки добавятся в выбранное место. Одинаковые товары на одном месте объединяются в одно количество.")
            Spacer(Modifier.height(12.dp))
            ExcelFileDrop(if (fileName.isEmpty()) "Выбрать файл Excel (.xlsx, .csv)" else fileName) {
                picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/comma-separated-values", "application/vnd.ms-excel", "text/plain"))
            }
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                StatusLine(error, StatusKind.Err, Modifier.fillMaxWidth())
            }
            if (table.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                HintText("Обнаруженные столбцы появятся после выбора файла.")
            } else {
                Text(
                    "НАСТРОЙКА СТОЛБЦОВ",
                    color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        Triple("Наименование", iName, { v: Int -> iName = v }),
                        Triple("Количество", iQty, { v: Int -> iQty = v }),
                        Triple("Штрихкод", iEan, { v: Int -> iEan = v }),
                        Triple("Адрес (необяз.)", iAddr, { v: Int -> iAddr = v }),
                        Triple("Срок годности (необяз.)", iExp, { v: Int -> iExp = v }),
                    ).forEach { (label, sel, set) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                label.uppercase(), color = c.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(110.dp),
                            )
                            SelectField("", options, sel, set, Modifier.weight(1f))
                        }
                    }
                }
                Text(
                    "ПРЕДПРОСМОТР · строк: ${table.size - 1}",
                    color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                )
                Md3Card {
                    table.drop(1).take(5).forEach { r ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(cell(r, iName).ifEmpty { cell(r, iEan).ifEmpty { "—" } }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(cell(r, iEan), cell(r, iAddr), cell(r, iExp).let { if (it.isEmpty()) "" else "срок: $it" }, "кол-во: " + cell(r, iQty).ifEmpty { "—" }).filter { it.isNotEmpty() }.joinToString(" · "),
                                fontSize = 11.sp, color = c.onSurfaceVariant,
                            )
                        }
                        RowDivider()
                    }
                }
                if (!ready) {
                    Spacer(Modifier.height(10.dp))
                    HintText("Выберите столбец количества и столбец наименования или штрихкода.")
                }
            }
        }
    }
}

@Composable
private fun ExcelFileDrop(text: String, onClick: () -> Unit) {
    val c = Md3.c
    val dash = c.outline
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceLow)
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
                    ),
                )
            }
            .md3Clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("📂", fontSize = 22.sp)
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVariant)
    }
}

// ---------- Экспорт ----------

@Composable
fun ExportExcelWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val selected = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { ArrivalStore.load(ctx) }
    val groups = ArrivalStore.items.groupBy { it.date }.toList()
        .sortedByDescending { parseHistDate(it.first) }

    fun build(): Pair<String, ByteArray>? {
        val rows = ArrivalStore.items.filter { it.date in selected }
            .sortedWith(compareBy({ parseHistDate(it.date) }, { it.receivedAt }))
        if (rows.isEmpty()) { env.info("Выберите хотя бы одну дату"); return null }
        val table = ArrayList<List<String>>()
        table.add(listOf("Наименование", "Количество", "Дата", "Отправитель", "Номер заказа"))
        rows.forEach { table.add(listOf(it.name, it.menge.toString(), it.date, it.sender, it.order)) }
        return "Lager_prihody_${ExportHelper.stamp()}.xlsx" to ExportHelper.xlsx(listOf("Приходы" to table))
    }

    WindowScaffold(
        title = "Экспорт в Excel",
        footer = {
            LongButton("📤 Поделиться (WhatsApp, Telegram, Email…)", LongKind.Green, {
                build()?.let { (n, b) -> if (!ExportHelper.share(ctx, n, b, ExportHelper.XLSX)) env.info("Не удалось поделиться файлом") }
            })
            LongButton("💾 Сохранить в память телефона", LongKind.Blue, {
                build()?.let { (n, b) ->
                    if (ExportHelper.saveToDownloads(ctx, n, b, ExportHelper.XLSX)) env.info("Сохранено в «Загрузки»: $n")
                    else env.info("Сохранение недоступно на этой версии Android — используйте «Поделиться»")
                }
            })
        },
    ) {
        if (groups.isEmpty()) {
            ScrollBody { EmptyHint("Нет дат для экспорта") }
        } else {
            ScrollBody {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HintText("Выберите даты приёма", Modifier.weight(1f))
                    val all = selected.size == groups.size
                    Text(
                        if (all) "Снять все" else "Выбрать все",
                        color = c.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).md3Clickable {
                            selected.clear(); if (!all) selected.addAll(groups.map { it.first })
                        }.padding(8.dp),
                    )
                }
                groups.forEach { (date, list) ->
                    val on = date in selected
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) c.primaryContainer else c.surfaceLow)
                            .md3Clickable { if (on) selected.remove(date) else selected.add(date) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (on) "☑" else "☐", fontSize = 18.sp)
                        Text(
                            date.ifEmpty { "Без даты" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        Text("${list.size} поз. · ${list.sumOf { it.menge }} шт.", fontSize = 12.sp, color = c.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun InventoryExportWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val selected = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { InventoryStore.load(ctx) }
    val dates = InventoryStore.locations.size.let { InventoryStore.exportDates() }

    fun build(): Pair<String, ByteArray>? {
        if (selected.isEmpty()) { env.info("Выберите хотя бы одну дату"); return null }
        val bytes = InventoryStore.buildExport(selected.toSet())
        if (bytes == null) { env.info("За выбранные даты нет данных"); return null }
        return "Lager_inventory_${ExportHelper.stamp()}.xlsx" to bytes
    }

    WindowScaffold(
        title = "Экспорт",
        footer = {
            LongButton("📤 Поделиться (WhatsApp, Telegram, Email…)", LongKind.Green, {
                build()?.let { (n, b) -> if (!ExportHelper.share(ctx, n, b, ExportHelper.XLSX)) env.info("Не удалось поделиться файлом") }
            })
            LongButton("💾 Сохранить в память телефона", LongKind.Blue, {
                build()?.let { (n, b) ->
                    if (ExportHelper.saveToDownloads(ctx, n, b, ExportHelper.XLSX)) env.info("Сохранено в «Загрузки»: $n")
                    else env.info("Сохранение недоступно на этой версии Android — используйте «Поделиться»")
                }
            })
        },
    ) {
        ScrollBody {
            HintText("Выберите дату сканирования. Один файл: лист «Инвентаризация» (итоги по местам) и лист «Сканирования».")
            if (dates.isEmpty()) {
                EmptyHint("Нет дат для экспорта")
            } else {
                Spacer(Modifier.height(8.dp))
                dates.forEach { (date, n) ->
                    val on = date in selected
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) c.primaryContainer else c.surfaceLow)
                            .md3Clickable { if (on) selected.remove(date) else selected.add(date) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (on) "☑" else "☐", fontSize = 18.sp)
                        Text(date, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        Text("$n сканир.", fontSize = 12.sp, color = c.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------- Лист «Импорт / Экспорт» ----------

@Composable
private fun SheetAction(icon: String, text: String, onClick: () -> Unit) {
    val c = Md3.c
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surfaceLow)
            .md3Clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(icon, fontSize = 22.sp)
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportSheet(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var dbImportOpen by remember { mutableStateOf(false) }
    val closeSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { env.nav.pop() }
    }
    if (dbImportOpen) CatImportDialog(onDismiss = { dbImportOpen = false })
    ModalBottomSheet(
        onDismissRequest = { env.nav.pop() },
        sheetState = sheetState,
        containerColor = c.card,
        contentColor = c.onSurface,
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Импорт / Экспорт", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(34.dp).clip(CircleShape).md3Clickable { closeSheet() },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = c.onSurfaceVariant) }
            }
            SheetAction("📊", "Импорт из Excel") { env.nav.replaceTop(Win.ExcelImport) }
            SheetAction("📂", "Импорт базы (.json)") { dbImportOpen = true }
            SheetAction("📊", "Экспорт в Excel") { env.nav.replaceTop(Win.ExportExcel) }
            SheetAction("📍", "Экспорт инвентаризации в Excel") { env.nav.replaceTop(Win.InventoryExport) }
            SheetAction("💾", "Экспорт базы (.json)") {
                // Формат как в HTML (buildBackupPayload): database + arrivals, чтобы файл читался в обе стороны.
                val files = ctx.applicationContext.filesDir
                fun raw(n: String) = java.io.File(files, n).takeIf { it.exists() }?.readText()?.ifBlank { null } ?: "[]"
                fun arr(n: String) = runCatching { org.json.JSONArray(raw(n)) }.getOrDefault(org.json.JSONArray())
                val json = org.json.JSONObject()
                    .put("database", arr("my_off_db.json"))
                    .put("arrivals", arr("my_off_arr6.json"))
                    .put("exportedAt", java.time.Instant.now().toString())
                    .put("version", "6.0")
                    .toString(2)
                if (!ExportHelper.share(ctx, "warehouse_backup_${ExportHelper.stamp()}.json", json.toByteArray(), ExportHelper.JSON)) env.info("Не удалось поделиться файлом")
            }
        }
    }
}


// ---------- Изменить строку (#inventoryEditModal) ----------

@Composable
private fun InvRowEditDialog(
    loc: InvLoc,
    item: InvItem,
    onDismiss: () -> Unit,
    onSave: (name: String, barcode: String, qty: Int, expiry: String, addr: InvAddr) -> String?,
) {
    var name by remember(item.key) { mutableStateOf(item.name) }
    var barcode by remember(item.key) { mutableStateOf(item.barcode) }
    var qty by remember(item.key) { mutableStateOf(item.quantity.toString()) }
    var expiry by remember(item.key) { mutableStateOf(item.expiry) }
    var warehouse by remember(item.key) { mutableStateOf(loc.addr.warehouse) }
    var row by remember(item.key) { mutableStateOf(loc.addr.row) }
    var floor by remember(item.key) { mutableStateOf(loc.addr.floor) }
    var shelf by remember(item.key) { mutableStateOf(loc.addr.shelf) }
    var error by remember(item.key) { mutableStateOf("") }

    val save = {
        val addr = InventoryStore.build(warehouse, row, floor, shelf)
        if (addr == null) {
            error = "Заполните склад, ряд, этаж и полку"
        } else {
            val q = (qty.trim().toIntOrNull() ?: 0).coerceIn(0, InventoryStore.MAX_QTY)
            error = onSave(name, barcode, q, expiry, addr).orEmpty()
        }
    }

    DialogCard(
        title = "Изменить строку",
        onDismiss = onDismiss,
        actions = {
            DialogActionCancel("Отмена", onDismiss)
            DialogActionConfirm("Сохранить", { save() })
        },
    ) {
        LabeledInput("Имя товара", name, { name = it; error = "" })
        LabeledInput("Штрихкод", barcode, { barcode = it; error = "" }, keyboardType = KeyboardType.Number)
        LabeledInput("Количество", qty, { v -> qty = v.filter { it.isDigit() }.take(5); error = "" }, keyboardType = KeyboardType.Number)
        LabeledInput("Срок годности", expiry, { expiry = it; error = "" }, placeholder = "ГГГГ, ГГГГ-ММ или ГГГГ-ММ-ДД")
        FieldLabel("Место хранения")
        LabeledInput("Склад", warehouse, { warehouse = it; error = "" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledInput("Ряд", row, { row = it; error = "" }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
            LabeledInput("Этаж", floor, { floor = it; error = "" }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
            LabeledInput("Полка", shelf, { shelf = it; error = "" }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
        }
        HintText("«--» — часть адреса не задана.")
        if (error.isNotEmpty()) StatusLine(error, StatusKind.Err, Modifier.fillMaxWidth())
    }
}


// ---------- Изменить адрес (#inventoryAddressEditModal) ----------

@Composable
private fun InvAddrEditDialog(loc: InvLoc, onDismiss: () -> Unit, onSave: (InvAddr) -> Unit) {
    val key = loc.addr.key
    var warehouse by remember(key) { mutableStateOf(loc.addr.warehouse) }
    var row by remember(key) { mutableStateOf(loc.addr.row) }
    var floor by remember(key) { mutableStateOf(loc.addr.floor) }
    var shelf by remember(key) { mutableStateOf(loc.addr.shelf) }
    var error by remember(key) { mutableStateOf("") }

    val save = {
        val addr = InventoryStore.build(warehouse, row, floor, shelf)
        if (addr == null) {
            error = "Заполните склад, ряд, этаж и полку"
        } else {
            onSave(addr)
        }
    }

    DialogCard(
        title = "Изменить адрес",
        onDismiss = onDismiss,
        actions = {
            DialogActionCancel("Отмена", onDismiss)
            DialogActionConfirm("Сохранить", { save() })
        },
    ) {
        LabeledInput("Склад", warehouse, { warehouse = it; error = "" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledInput("Ряд", row, { row = it; error = "" }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
            LabeledInput("Этаж", floor, { floor = it; error = "" }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
            LabeledInput("Полка", shelf, { shelf = it; error = "" }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
        }
        HintText("Ряд, этаж и полка — числа 1–99 или «--». Все товары места переедут на новый адрес.")
        if (error.isNotEmpty()) StatusLine(error, StatusKind.Err, Modifier.fillMaxWidth())
    }
}
