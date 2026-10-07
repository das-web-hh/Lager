package ru.lager.app.ui.win

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

// =====================================================================
//  История (#historyModal): приёмки по партиям, новые сверху
// =====================================================================

/** Партия приёмки: строки одной партии (batchId); запись без партии — своя группа. */
class HistGroup(
    val key: String,
    val batchId: String,
    val date: String,
    val sender: String,
    val order: String,
    val receivedAt: String,
    val rows: List<Arrival>,
) {
    val title: String get() = sender.ifEmpty { if (order.isNotEmpty()) "Заказ $order" else "Без отправителя" }
    val sortKey: Long get() = parseReceivedAt(receivedAt) ?: parseHistDate(date)
    val timeLabel: String
        get() {
            val ms = parseReceivedAt(receivedAt)
            return if (ms != null) SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(java.util.Date(ms)) else date.ifEmpty { "—" }
        }
}

private fun parseReceivedAt(v: String): Long? {
    if (v.length < 19) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(v.take(19))?.time
    }.getOrNull()
}

/** receivedBatchGroups: группы по batchId, новые сверху. */
fun histGroups(list: List<Arrival>): List<HistGroup> {
    val map = LinkedHashMap<String, MutableList<Arrival>>()
    list.forEach { map.getOrPut(it.batchId.ifEmpty { "single:" + it.id }) { ArrayList() }.add(it) }
    return map.map { (key, rows) ->
        val first = rows.first()
        HistGroup(
            key = key,
            batchId = first.batchId,
            date = first.date,
            sender = rows.firstOrNull { it.sender.isNotEmpty() }?.sender.orEmpty(),
            order = rows.firstOrNull { it.order.isNotEmpty() }?.order.orEmpty(),
            receivedAt = rows.firstOrNull { it.receivedAt.isNotEmpty() }?.receivedAt.orEmpty(),
            rows = rows,
        )
    }.sortedByDescending { it.sortKey }
}

/** Сверка по товару: план / факт / брак и пометки (classify из HTML). */
class HistCls(val plan: Int, val fact: Int, val dmg: Int, val tags: List<Pair<String, String>>, val cls: String) {
    val bad: Boolean get() = cls != "ok"
}

fun histClassify(p: NrHistProduct): HistCls {
    val plan = p.plan
    val fact = p.actual
    val dmg = p.damage
    val tags = ArrayList<Pair<String, String>>()
    var cls = "ok"
    if (plan > 0 && fact == 0 && dmg == 0) {
        tags.add("miss" to "Не пришло"); cls = "miss"
    } else if (fact < plan) {
        tags.add("less" to "Недостача −${plan - fact}"); cls = "less"
    } else if (fact > plan) {
        tags.add("more" to if (plan == 0) "Лишний товар +$fact" else "Больше +${fact - plan}"); cls = "more"
    }
    if (dmg > 0) {
        tags.add("dmg" to "Брак $dmg")
        if (cls == "ok") cls = "dmg"
    }
    return HistCls(plan, fact, dmg, tags, cls)
}

/** Данные сверки партии: последняя запись «Истории сканирований», где есть план. */
fun histRecon(batchId: String): List<NrHistProduct>? {
    if (batchId.isEmpty()) return null
    return NrHistoryStore.records
        .lastOrNull { it.batchId == batchId && it.products.any { p -> p.plan > 0 } }
        ?.products
}

object HistBatchState {
    var key by mutableStateOf("")
}

@Composable
fun HistoryWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        ArrivalStore.load(ctx)
        NrHistoryStore.load(ctx)
    }

    val groups by remember { derivedStateOf { histGroups(ArrivalStore.items.toList()) } }

    WindowScaffold("История") {
        if (groups.isEmpty()) {
            ScrollBody { EmptyHint("История приёмок пока пуста") }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups, key = { it.key }) { g ->
                    val warn = histRecon(g.batchId)?.any { histClassify(it).bad } == true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .md3Clickable { HistBatchState.key = g.key; env.nav.push(Win.HistoryBatch) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            g.title,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        if (warn) Text("⚠️", fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(g.timeLabel, fontSize = 12.sp, color = c.onSurfaceVariant)
                    }
                    RowDivider()
                }
            }
        }
    }
}

// =====================================================================
//  Приёмка из истории (#historyBatchModal)
// =====================================================================

private fun histTagColor(kind: String): Color = when (kind) {
    "miss", "dmg" -> Color(0xFFD32F2F)
    "less" -> Color(0xFFE67700)
    else -> Color(0xFF1C7ED6)
}

@Composable
private fun HistKv(label: String, value: String) {
    val c = Md3.c
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = c.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.onSurface, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun HistSumBox(label: String, value: Int, bad: Boolean, modifier: Modifier) {
    val c = Md3.c
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(shape).background(if (bad) c.errorContainer else c.surfaceLow).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 11.sp, color = c.onSurfaceVariant)
        Text(value.toString(), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = if (bad) c.error else c.onSurface)
    }
}

@Composable
private fun HistReconItem(p: NrHistProduct, k: HistCls) {
    val c = Md3.c
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, if (k.bad) histTagColor(k.cls).copy(alpha = 0.6f) else c.outlineVariant, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            k.tags.forEach { (kind, text) ->
                Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = histTagColor(kind), modifier = Modifier.padding(top = 3.dp))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("план ${k.plan}", fontSize = 12.sp, color = c.onSurfaceVariant)
            Text("факт ${k.fact}", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.onSurface)
            if (k.dmg > 0) Text("брак ${k.dmg}", fontSize = 12.sp, color = Color(0xFFD32F2F))
        }
    }
}

@Composable
fun HistoryBatchWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ArrivalStore.load(ctx)
        NrHistoryStore.load(ctx)
    }

    val group = histGroups(ArrivalStore.items.toList()).firstOrNull { it.key == HistBatchState.key }
    if (group == null) {
        WindowScaffold("Приёмка", footer = { SoftButton("Закрыть", { env.nav.pop() }, Modifier.fillMaxWidth()) }) {
            EmptyHint("Приёмка не найдена или уже удалена")
        }
        return
    }
    val recon = histRecon(group.batchId)
    val files = remember(group.batchId, showFiles) { if (group.batchId.isEmpty()) emptyList() else AttachmentStore.load(ctx, group.batchId) }

    fun print() {
        val ok = if (recon != null) {
            val list = recon.map { it to histClassify(it) }
            val head = listOf("№", "Товар", "План", "Факт", "Брак", "Статус")
            fun rows(l: List<Pair<NrHistProduct, HistCls>>) = l.mapIndexed { i, (p, k) ->
                listOf((i + 1).toString(), p.name, k.plan.toString(), k.fact.toString(), k.dmg.toString(), if (k.tags.isEmpty()) "OK" else k.tags.joinToString(", ") { it.second })
            }
            val bad = list.filter { it.second.bad }
            val sections = buildList {
                if (bad.isNotEmpty()) add(Triple("Расхождения (${bad.size})", head, rows(bad)))
                add(Triple("Полная сверка (${list.size})", head, rows(list)))
            }
            ExportHelper.printSections(
                ctx, "Сверка приёмки и расхождения", "Сверка приёмки и расхождения",
                listOf("Отдел: Abteilung Wareneingang", "Отправитель: ${group.title}") +
                    (if (group.order.isNotEmpty()) listOf("Заказ: ${group.order}") else emptyList()) +
                    listOf("Время приёмки: ${group.timeLabel}"),
                sections,
            )
        } else {
            ExportHelper.printTable(
                ctx, group.title, listOf("Наименование", "Количество"),
                group.rows.map { listOf(it.name, it.menge.toString()) },
                listOf(group.order, group.timeLabel).filter { it.isNotBlank() }.joinToString(" · "),
            )
        }
        if (!ok) env.info("Не удалось открыть печать")
    }

    WindowScaffold("Приёмка", footer = { SoftButton("Закрыть", { env.nav.pop() }, Modifier.fillMaxWidth()) }) {
        ScrollBody {
            Md3Card {
                HistKv("Отправитель", group.title)
                if (group.order.isNotEmpty()) HistKv("Заказ", group.order)
                HistKv("Время приёмки", group.timeLabel)
                Spacer8()
                if (recon != null) {
                    val list = recon.map { it to histClassify(it) }
                    val bad = list.count { it.second.bad }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HistSumBox("План", list.sumOf { it.second.plan }, false, Modifier.weight(1f))
                        HistSumBox("Факт", list.sumOf { it.second.fact }, false, Modifier.weight(1f))
                        HistSumBox("Брак", list.sumOf { it.second.dmg }, list.sumOf { it.second.dmg } > 0, Modifier.weight(1f))
                        HistSumBox("Расхожд.", bad, bad > 0, Modifier.weight(1f))
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HistSumBox("Позиций", group.rows.size, false, Modifier.weight(1f))
                        HistSumBox("Принято", group.rows.sumOf { it.menge }, false, Modifier.weight(1f))
                    }
                }
                Spacer8()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(
                        "🖨 Печать" + if (recon != null) " сверки и расхождений" else "",
                        { print() }, Modifier.weight(1f),
                    )
                    SoftButton("📎" + if (files.isNotEmpty()) " ${files.size}" else "", { showFiles = true })
                }
            }

            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recon != null) {
                    val list = recon.map { it to histClassify(it) }
                    val bad = list.filter { it.second.bad }
                    val ok = list.filter { !it.second.bad }
                    if (bad.isNotEmpty()) {
                        SectionLabel("Расхождения (${bad.size})")
                        bad.forEach { (p, k) -> HistReconItem(p, k) }
                    } else {
                        Text("✅ Расхождений нет — всё пришло в нужном количестве.", fontSize = 14.sp, color = c.success, fontWeight = FontWeight.SemiBold)
                    }
                    if (ok.isNotEmpty()) {
                        SectionLabel("Без расхождений (${ok.size})")
                        ok.forEach { (p, k) -> HistReconItem(p, k) }
                    }
                } else {
                    Text(
                        "Для этой приёмки нет данных плана — показан список принятых товаров.",
                        fontSize = 13.sp, color = c.onSurfaceVariant,
                    )
                    SectionLabel("Принятые товары (${group.rows.size})")
                    group.rows.forEach { r ->
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.outlineVariant, shape).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.name.ifEmpty { "—" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface, modifier = Modifier.weight(1f).padding(end = 8.dp))
                            Text(r.menge.toString(), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = c.onSurface)
                        }
                    }
                }
                LongButton("🗑 Удалить приёмку", LongKind.Red, { confirmDelete = true })
            }
        }
    }

    if (confirmDelete) {
        CatConfirmDialog(
            title = "Удалить приёмку?",
            text = "«${group.title}» · ${group.rows.size} поз.",
            yes = "Да, удалить",
            onYes = {
                group.rows.forEach { ArrivalStore.delete(ctx, it.id) }
                confirmDelete = false
                env.nav.pop()
            },
            onNo = { confirmDelete = false },
        )
    }

    if (showFiles) {
        DialogCard(
            title = "Вложения партии",
            onDismiss = { showFiles = false },
            actions = { DialogActionCancel("Закрыть") { showFiles = false } },
        ) {
            if (files.isEmpty()) {
                Text("Файлы и накладная не прикреплены.", fontSize = 14.sp, color = c.onSurfaceVariant)
            }
            files.forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.surfaceHigh)
                        .md3Clickable {
                            if (f.kind == AttachmentStore.KIND_DOC) {
                                val docs = files.filter { it.kind == AttachmentStore.KIND_DOC }
                                showFiles = false
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

@Composable
private fun Spacer8() {
    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
}
