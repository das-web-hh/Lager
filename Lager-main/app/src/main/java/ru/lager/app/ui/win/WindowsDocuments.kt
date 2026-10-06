package ru.lager.app.ui.win

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

private fun docIcon(type: String) = when (type) {
    "bware" -> "📄"
    "discrepancy" -> "⚠️"
    "full" -> "✅"
    else -> "📋"
}

private fun dayLabel(date: String): String {
    val now = Calendar.getInstance()
    if (date == DocumentStore.fmtDate(now)) return "$date · Сегодня"
    now.add(Calendar.DAY_OF_MONTH, -1)
    return if (date == DocumentStore.fmtDate(now)) "$date · Вчера" else date
}

@Composable
fun DocumentsWindow(env: WinEnv) {
    val ctx = LocalContext.current
    val c = Md3.c
    LaunchedEffect(Unit) { DocumentStore.load(ctx) }
    var openId by remember { mutableStateOf("") }
    var confirmId by remember { mutableStateOf("") }
    val opened = DocumentStore.items.firstOrNull { it.id == openId }

    // «Назад» из карточки возвращает в список (closeDocumentDetail)
    BackHandler(enabled = opened != null) { openId = "" }
    // подтверждение удаления сбрасывается через 3 с
    LaunchedEffect(confirmId) {
        if (confirmId.isNotEmpty()) { delay(3000); confirmId = "" }
    }

    if (opened != null) {
        DocumentDetail(opened)
        return
    }

    WindowScaffold("Документы") {
        if (DocumentStore.items.isEmpty()) {
            EmptyHint("Здесь появятся напечатанные документы.")
        } else {
            val grouped = DocumentStore.items.groupBy { it.date }
            ScrollBody {
                grouped.forEach { (date, docs) ->
                    SectionLabel(dayLabel(date))
                    Md3Card {
                        docs.forEachIndexed { i, d ->
                            val sub = listOf(
                                d.subtitle, d.time,
                                if (d.driveId.isNotEmpty()) "☁ PDF + QR" else "⚠ не на Диске",
                            ).filter { it.isNotEmpty() }.joinToString(" · ")
                            SettingsRow(
                                icon = docIcon(d.type),
                                title = d.title,
                                sub = sub,
                                onClick = { confirmId = ""; openId = d.id },
                                trailing = {
                                    val sure = confirmId == d.id
                                    SoftButton(
                                        if (sure) "Удалить?" else "🗑",
                                        onClick = {
                                            if (sure) { confirmId = ""; DocumentStore.delete(ctx, d.id) }
                                            else confirmId = d.id
                                        },
                                        danger = sure,
                                    )
                                },
                            )
                            if (i < docs.lastIndex) RowDivider()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

// ---------- Карточка документа ----------

private val DOC_COLS = linkedMapOf(
    "name" to "Товар", "ean" to "EAN", "qty" to "Кол-во",
    "plan" to "План", "fact" to "Факт", "damage" to "Брак",
)

@Composable
private fun DocumentDetail(doc: StoredDoc) {
    val ctx = LocalContext.current
    val c = Md3.c
    WindowScaffold(doc.title) {
        ScrollBody {
            Text(
                listOf(doc.subtitle, doc.date, doc.time).filter { it.isNotEmpty() }.joinToString(" · "),
                fontSize = 13.sp, color = c.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (doc.driveId.isNotEmpty() && doc.driveUrl.isNotEmpty()) {
                LongButton("📄 Открыть PDF", LongKind.Blue, {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(doc.driveUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                })
            } else {
                StatusLine("PDF не загружен на Google Диск", StatusKind.Warn)
            }
            if (doc.driveError.isNotEmpty() && doc.driveId.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                StatusLine(doc.driveError, StatusKind.Err)
            }
            Spacer(Modifier.height(14.dp))
            SectionLabel("Данные документа")
            DocData(doc.data)
        }
    }
}

@Composable
private fun DocData(d: JSONObject?) {
    val c = Md3.c
    if (d == null) { EmptyHint("Данных по документу нет."); return }
    val meta = listOf(
        "Отправитель" to d.optString("sender"), "Заказ" to d.optString("order"),
        "Накладная" to d.optString("ls"), "Дата" to d.optString("date"),
        "№ документа" to d.optString("docNo"),
    ).filter { it.second.isNotEmpty() }
    val rows: JSONArray = d.optJSONArray("items") ?: d.optJSONArray("products") ?: JSONArray()
    if (meta.isEmpty() && rows.length() == 0) { EmptyHint("Данных по документу нет."); return }

    if (meta.isNotEmpty()) {
        Md3Card {
            meta.forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(k, fontSize = 13.sp, color = c.onSurfaceVariant)
                    Text(v, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
                if (i < meta.lastIndex) RowDivider()
            }
        }
    }
    if (rows.length() > 0) {
        val list = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        val cols = DOC_COLS.keys.filter { k -> list.any { it.optString(k).isNotEmpty() } }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                DocCell("№", 36.dp, true)
                cols.forEach { DocCell(DOC_COLS.getValue(it), if (it == "name") 190.dp else 80.dp, true) }
            }
            list.forEachIndexed { i, r ->
                Row {
                    DocCell("${i + 1}", 36.dp, false)
                    cols.forEach { DocCell(r.optString(it), if (it == "name") 190.dp else 80.dp, false) }
                }
            }
        }
    }
}

@Composable
private fun DocCell(text: String, w: androidx.compose.ui.unit.Dp, head: Boolean) {
    val c = Md3.c
    Box(
        Modifier
            .width(w)
            .then(if (head) Modifier.background(c.surfaceLow) else Modifier)
            .border(0.5.dp, c.outlineVariant)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 13.sp, fontWeight = if (head) FontWeight.Bold else FontWeight.Normal)
    }
}
