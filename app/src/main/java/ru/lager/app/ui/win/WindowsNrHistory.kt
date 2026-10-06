package ru.lager.app.ui.win

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================
//  История сканирований (nrHistory2Modal): операции по накладным «Приёма по имени»
// =====================================================================

private fun nrhFmt(ts: Long): String =
    SimpleDateFormat("dd.MM.yyyy, HH:mm:ss", Locale.getDefault()).format(Date(ts))

private fun nrhStatusLabel(s: String) = when (s) {
    "saved" -> "Готово"
    "error" -> "Ошибка"
    "deleted" -> "Удалено"
    else -> "В работе"
}

private fun nrhEventLabel(t: String) = when (t) {
    "recognized" -> "Распознавание"
    "saved" -> "Сохранение партии"
    "print" -> "Печать"
    "export" -> "Экспорт"
    "error" -> "Ошибка"
    "deleted" -> "Удаление"
    else -> t
}

@Composable
private fun NrHistCard(r: NrHistRecord) {
    val c = Md3.c
    var open by remember(r.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val chip = when (r.status) {
        "saved" -> Color(0xFF2B8A3E)
        "error" -> Color(0xFFC92A2A)
        "deleted" -> c.onSurfaceVariant
        else -> c.primary
    }
    val plan = r.products.sumOf { it.plan }
    val fact = r.products.sumOf { it.actual }
    val dmg = r.products.sumOf { it.damage }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.outlineVariant, shape)
            .md3Clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                r.sender.ifEmpty { "Поставщик не указан" },
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                nrhStatusLabel(r.status),
                color = chip,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(chip.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        Text(
            "${nrhFmt(r.createdAt)} · ${r.order.ifEmpty { "без номера" }}" + (if (r.user.isNotEmpty()) " · ${r.user}" else ""),
            fontSize = 12.sp,
            color = c.onSurfaceVariant,
        )
        Text(
            "📄 ${r.sourceFileName} · 📦 ${r.products.size} поз. · план $plan / факт $fact / брак $dmg",
            fontSize = 12.sp,
            color = c.onSurfaceVariant,
        )
        if (open) {
            Spacer(Modifier.height(6.dp))
            if (r.products.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Товар", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                    listOf("Пл", "Факт", "Брак").forEach {
                        Text(it, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant, modifier = Modifier.width(44.dp))
                    }
                }
                r.products.forEach { p ->
                    RowDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${p.plan}", fontSize = 12.sp, modifier = Modifier.width(44.dp))
                        Text("${p.actual}", fontSize = 12.sp, modifier = Modifier.width(44.dp))
                        Text("${p.damage}", fontSize = 12.sp, modifier = Modifier.width(44.dp))
                    }
                }
            }
            Text("Операции", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
            r.events.forEach { e ->
                Text(
                    "${nrhFmt(e.t)} — ${nrhEventLabel(e.type)}" + (if (e.text.isNotEmpty()) ": ${e.text}" else ""),
                    fontSize = 12.sp,
                    color = c.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun NrHistoryWindow(env: WinEnv) {
    val ctx = LocalContext.current
    var askClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { NrHistoryStore.load(ctx) }

    WindowScaffold(
        title = "История сканирований",
        actions = {
            if (NrHistoryStore.records.isNotEmpty()) TopBarTextButton("Очистить") { askClear = true }
        },
    ) {
        ScrollBody {
            if (NrHistoryStore.records.isEmpty()) {
                EmptyHint("Операций пока нет. Они появятся после распознавания накладной.")
            } else {
                NrHistoryStore.records.toList().forEach { NrHistCard(it) }
            }
        }
    }

    if (askClear) {
        CatConfirmDialog(
            title = "Очистить историю сканирований?",
            text = "Журнал операций на этом устройстве будет удалён. Сохранённые партии в истории приёмок останутся.",
            yes = "Да, очистить",
            onYes = { NrHistoryStore.clear(ctx); askClear = false },
            onNo = { askClear = false },
        )
    }
}
