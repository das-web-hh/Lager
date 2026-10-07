package ru.lager.app.ui.win

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.withContext

// =====================================================================
//  История (#historyModal): приходы по дням, новые сверху
// =====================================================================

private sealed interface HistEntry {
    data class Day(val date: String) : HistEntry
    data class Line(val a: Arrival) : HistEntry
}

@Composable
private fun HistDayDivider(date: String) {
    val c = Md3.c
    Text(
        "Дата приёма: " + date.ifEmpty { "не принимался" },
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = c.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surfaceLow)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun HistLine(a: Arrival, attCount: Int, onAttach: () -> Unit, onDelete: () -> Unit) {
    val c = Md3.c
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                a.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 18.sp,
                color = c.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOf(a.sender, a.order).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 11.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (attCount > 0) {
            Box(
                Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).clip(RoundedCornerShape(10.dp)).md3Clickable(onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) { Text("📎$attCount", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.onSurface) }
        }
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 32.dp)
                .clip(shape)
                .border(1.dp, c.outlineVariant, shape)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text(a.menge.toString(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.onSurface) }
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).md3Clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) { Text("🗑", fontSize = 18.sp) }
    }
}

@Composable
fun HistoryWindow(env: WinEnv) {
    val ctx = LocalContext.current
    var deleting by remember { mutableStateOf<Arrival?>(null) }
    var attBatch by remember { mutableStateOf<String?>(null) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(Unit) {
        ArrivalStore.load(ctx)
        counts = withContext(Dispatchers.IO) { AttachmentStore.counts(ctx) }
    }

    // Сортировка как в renderHistoryList: дата по убыванию, при равной дате — позже добавленные выше.
    val entries by remember {
        derivedStateOf {
            val sorted = ArrivalStore.items
                .withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<Arrival>> { parseHistDate(it.value.date) }
                        .thenByDescending { it.index },
                )
                .map { it.value }
            val out = ArrayList<HistEntry>(sorted.size + 16)
            var prev: String? = null
            sorted.forEach { a ->
                if (a.date != prev) { out.add(HistEntry.Day(a.date)); prev = a.date }
                out.add(HistEntry.Line(a))
            }
            out
        }
    }

    WindowScaffold("История") {
        if (entries.isEmpty()) {
            ScrollBody { EmptyHint("История приёмок пока пуста") }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries) { e ->
                    when (e) {
                        is HistEntry.Day -> HistDayDivider(e.date)
                        is HistEntry.Line -> {
                            HistLine(e.a, counts[e.a.batchId] ?: 0, onAttach = { attBatch = e.a.batchId }, onDelete = { deleting = e.a })
                            RowDivider()
                        }
                    }
                }
            }
        }
    }

    deleting?.let { a ->
        CatConfirmDialog(
            title = "Удалить запись?",
            text = "«${a.name}»",
            yes = "Да, удалить",
            onYes = { ArrivalStore.delete(ctx, a.id); deleting = null },
            onNo = { deleting = null },
        )
    }

    attBatch?.let { id ->
        val files = remember(id) { AttachmentStore.load(ctx, id) }
        DialogCard(
            title = "Вложения партии",
            onDismiss = { attBatch = null },
            actions = { DialogActionCancel("Закрыть") { attBatch = null } },
        ) {
            val c = Md3.c
            files.forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.surfaceHigh)
                        .md3Clickable {
                            if (f.kind == AttachmentStore.KIND_DOC) {
                                val docs = files.filter { it.kind == AttachmentStore.KIND_DOC }
                                attBatch = null
                                InvoiceState.show(env.nav, docs, docs.indexOf(f).coerceAtLeast(0))
                            } else if (!AttachmentStore.open(ctx, f)) {
                                Toast.makeText(ctx, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (f.kind == AttachmentStore.KIND_DOC) "📄" else "📷", fontSize = 16.sp)
                    Text(f.name, fontSize = 13.sp, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}
