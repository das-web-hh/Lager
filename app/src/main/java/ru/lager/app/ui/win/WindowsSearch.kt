package ru.lager.app.ui.win

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val R12 = RoundedCornerShape(12.dp)
private val R16 = RoundedCornerShape(16.dp)

// =====================================================================
//  Карточка товара / приёмки (#infoModal)
// =====================================================================

@Composable
private fun CardChip(label: String, value: String, onClick: () -> Unit) {
    val c = Md3.c
    Column {
        Column(
            Modifier
                .fillMaxWidth()
                .md3Clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                label.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = c.onSurfaceVariant,
                letterSpacing = 0.4.sp,
            )
            Text(
                value.ifEmpty { "—" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        RowDivider()
    }
}

/** Фото с поворотом по EXIF и уменьшением, чтобы не держать в памяти полный кадр. */
private fun decodePhoto(path: String, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
    val deg = when (
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (deg == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
}.getOrNull()

@Composable
private fun PhotoPage(path: String, onClick: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodePhoto(path, 1600)?.asImageBitmap() }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text("…", color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp)
        }
    }
}

@Composable
private fun PhotoGallery(photos: List<Attachment>, onOpen: (Attachment) -> Unit) {
    val c = Md3.c
    if (photos.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().height(200.dp).clip(R16).background(c.surfaceLow),
            contentAlignment = Alignment.Center,
        ) {
            Text("📷  Нет фото", color = c.onSurfaceVariant, fontSize = 14.sp)
        }
        return
    }
    val pager = rememberPagerState(pageCount = { photos.size })
    Column {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().height(340.dp).clip(R16),
        ) { i -> PhotoPage(photos[i].path) { onOpen(photos[i]) } }
        if (photos.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(photos.size) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (i == pager.currentPage) c.primary else c.outlineVariant),
                    )
                }
            }
        }
    }
}

@Composable
fun ProductCardWindow(env: WinEnv) {
    val ctx = LocalContext.current
    val card = CardState.card
    val c = Md3.c
    val files = remember(card.batchId) {
        if (card.batchId.isEmpty()) emptyList() else AttachmentStore.load(ctx, card.batchId)
    }
    val photos = files.filter { it.kind == AttachmentStore.KIND_PHOTO }
    val docs = files.filter { it.kind == AttachmentStore.KIND_DOC }

    fun openFile(a: Attachment) {
        if (!AttachmentStore.open(ctx, a)) env.info("Нет приложения для просмотра файла")
    }

    fun filter(kind: String) {
        CardState.filterKind = kind
        env.nav.push(Win.CardFilter)
    }

    WindowScaffold(
        card.name.ifEmpty { "–" },
        footer = {
            LongButton("📄 Накладная", LongKind.Blue, {
                if (docs.isEmpty()) env.info("Накладная не прикреплена") else openFile(docs.first())
            })
            SoftButton("Закрыть", { env.nav.pop() }, Modifier.fillMaxWidth())
        },
    ) {
        ScrollBody {
            Text(
                card.date.ifEmpty { "—" } + (if (card.ean.isNotEmpty()) " · EAN: ${card.ean}" else ""),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Md3Card {
                CardChip("Отправитель", card.sender) { filter("sender") }
                CardChip("Номер заказа", card.order) { filter("order") }
                CardChip("Дата приёма", card.date) { filter("date") }
                CardChip("Адрес хранения", card.storageLocation) { filter("location") }
            }
            Spacer(Modifier.height(14.dp))
            PhotoGallery(photos) { openFile(it) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// =====================================================================
//  Приёмки товара (#productInfoModal)
// =====================================================================

@Composable
private fun QtyBadge(qty: Int) {
    val c = Md3.c
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(c.primary).padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(qty.toString(), color = c.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun ProductInfoWindow(env: WinEnv) {
    val c = Md3.c
    val card = CardState.card
    val db = remember(card.name) { CatalogStore.findByName(card.name) }
    val meta = listOfNotNull(
        db?.ean?.takeIf { it.isNotEmpty() }?.let { "EAN: $it" },
        db?.artikel?.takeIf { it.isNotEmpty() }?.let { "Арт: $it" },
    ).joinToString(" · ").ifEmpty { "Нет данных" }
    val arrivals = remember(card.name) {
        ArrivalStore.items.withIndex()
            .filter { it.value.name.equals(card.name, ignoreCase = true) }
            .sortedWith(compareByDescending<IndexedValue<Arrival>> { parseHistDate(it.value.date) }.thenByDescending { it.index })
            .map { it.value }
    }

    WindowScaffold(card.name.ifEmpty { "–" }) {
        Column(Modifier.fillMaxSize()) {
            Text(
                meta,
                fontSize = 12.sp,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            )
            Text(
                "ИСТОРИЯ ПРИЁМОК",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = c.onSurfaceVariant,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            )
            if (arrivals.isEmpty()) {
                EmptyHint("Приёмки для этого товара не найдены")
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(arrivals) { a ->
                        Md3Card(Modifier.clip(R16).md3Clickable {
                            env.nav.openCard(CardState.build(db?.ean.orEmpty(), a.name, a.date, a.batchId))
                        }) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.date.ifEmpty { "—" }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
                                    if (a.batchId.isNotEmpty()) {
                                        Text(
                                            "Партия: ${a.batchId.takeLast(6)}",
                                            fontSize = 11.sp,
                                            color = c.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }
                                QtyBadge(a.menge)
                                Text("›", fontSize = 20.sp, color = c.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
//  Товары по данным карточки (#cardFilterModal): дата / отправитель / заказ / адрес
// =====================================================================

private class FilterRow(
    val name: String,
    val menge: Int,
    val date: String,
    val sender: String,
    val order: String,
    val batchId: String,
    val storageLocation: String,
)

/** _sameCardFilterDate: даты сравниваются как даты, иначе как строки. */
private fun sameFilterDate(left: String, right: String): Boolean {
    val a = left.trim()
    val b = right.trim()
    if (a.isEmpty() || b.isEmpty()) return false
    val pa = parseHistDate(a)
    val pb = parseHistDate(b)
    return if (pa != 0L && pb != 0L) pa == pb else a.equals(b, ignoreCase = true)
}

private fun filterLabel(kind: String) = when (kind) {
    "date" -> "Дата"
    "sender" -> "Отправитель"
    "location" -> "Адрес хранения"
    else -> "Номер заказа"
}

private fun filterValue(kind: String, card: ActiveCard) = when (kind) {
    "date" -> card.date
    "sender" -> card.sender
    "location" -> card.storageLocation
    else -> card.order
}.trim()

private fun filterRows(kind: String, card: ActiveCard): List<FilterRow> {
    if (kind == "location") {
        val wanted = (if (card.storageLocations.isNotEmpty()) card.storageLocations else listOf(card.storageLocation))
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return InventoryStore.locations.flatMap { loc ->
            val label = loc.addr.label.trim()
            if (label.lowercase() !in wanted) {
                emptyList()
            } else {
                loc.items.filter { it.quantity > 0 && it.name.isNotBlank() }
                    .map { FilterRow(it.name.trim(), it.quantity, "", "", "", "", label) }
            }
        }
    }
    val value = filterValue(kind, card)
    if (value.isEmpty()) return emptyList()
    return ArrivalStore.items
        .filter { a ->
            when (kind) {
                "date" -> sameFilterDate(a.date, value)
                "sender" -> a.sender.trim().equals(value, ignoreCase = true)
                else -> a.order.trim().equals(value, ignoreCase = true)
            }
        }
        .filter { it.name.isNotBlank() }
        .map { FilterRow(it.name.trim(), it.menge, it.date, it.sender, it.order, it.batchId, "") }
}

@Composable
fun CardFilterWindow(env: WinEnv) {
    val c = Md3.c
    val kind = CardState.filterKind
    val card = CardState.card
    val label = filterLabel(kind)
    val value = filterValue(kind, card)
    val rows = remember(kind, card) { filterRows(kind, card) }

    fun pick(r: FilterRow) {
        val db = CatalogStore.findByName(r.name)
        if (r.batchId.isEmpty()) {
            // строка из инвентаризации (или приход без партии): показываем историю этого товара
            CardState.card = ActiveCard(
                ean = db?.ean.orEmpty(),
                artikel = db?.artikel.orEmpty(),
                name = r.name,
                storageLocation = r.storageLocation,
                storageLocations = if (r.storageLocation.isEmpty()) emptyList() else listOf(r.storageLocation),
            )
            env.nav.showCard(Win.ProductInfo)
        } else {
            env.nav.openCard(CardState.build(db?.ean.orEmpty(), r.name, r.date, r.batchId))
        }
    }

    WindowScaffold(label) {
        Column(Modifier.fillMaxSize()) {
            Text(
                value.ifEmpty { "–" },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            Text(
                if (rows.isEmpty()) "Нет данных." else "${rows.size} ${if (rows.size == 1) "товар" else "товаров"}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            )
            if (rows.isEmpty()) {
                EmptyHint(if (value.isNotEmpty()) "Нет данных." else "$label: –")
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows) { r ->
                        Md3Card(Modifier.clip(R16).md3Clickable { pick(r) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                                    val meta = listOf(r.date, r.sender, r.order, r.storageLocation)
                                        .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
                                    if (meta.isNotEmpty()) {
                                        Text(
                                            meta,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = c.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 3.dp),
                                        )
                                    }
                                }
                                Text(
                                    r.menge.toString(),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = c.primary,
                                    textAlign = TextAlign.End,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
