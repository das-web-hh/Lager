package ru.lager.app.ui.win

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------- Задачи (tasksModal) ----------

@Composable
fun TasksWindow() {
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) { TaskStore.refresh() }

    WindowScaffold(
        "Задачи",
        actions = { TopBarTextButton("Обновить") { scope.launch { TaskStore.refresh() } } },
    ) {
        val tasks = TaskStore.tasks
        ScrollBody {
            when {
                tasks.isEmpty() && TaskStore.loading -> EmptyHint("Загрузка…")
                tasks.isEmpty() && TaskStore.failed -> EmptyHint("Не удалось получить задачи")
                tasks.isEmpty() -> EmptyHint("Нет задач")
                else -> Md3Card {
                    tasks.forEachIndexed { i, t ->
                        TaskRow(t, busy = t.id in busy) {
                            busy.add(t.id)
                            scope.launch {
                                TaskStore.markDone(t.id)
                                busy.remove(t.id)
                            }
                        }
                        if (i < tasks.lastIndex) RowDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: WhTask, busy: Boolean, onDone: () -> Unit) {
    val c = Md3.c
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                t.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = c.onSurface,
                modifier = Modifier.weight(1f),
            )
            val bg = if (t.done) Color(0xFF2B8A3E) else Color(0xFF1C7ED6)
            Box(
                Modifier
                    .alpha(if (busy) 0.5f else 1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .md3Clickable(color = Color.White, enabled = !t.done && !busy, onClick = onDone)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    if (t.done) "✓ Выполнено" else "Отметить выполненной",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (t.description.isNotBlank()) {
            Text(
                t.description,
                fontSize = 13.sp,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---------- Инфо: статистика (statsModal) ----------

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    val c = Md3.c
    Md3Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(
                label.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = c.onSurfaceVariant,
                letterSpacing = 0.4.sp,
            )
            Text(
                value,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = c.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SizeRow(icon: String, label: String, value: String, sub: String?) {
    val c = Md3.c
    Md3Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(icon, fontSize = 22.sp)
            Column(Modifier.weight(1f)) {
                Text(
                    label.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.onSurfaceVariant,
                )
                Text(
                    value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (sub != null) {
                    Text(sub, fontSize = 11.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
                }
            }
        }
    }
}

@Composable
private fun DayRow(d: DayStat) {
    val c = Md3.c
    Md3Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(d.date, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
                Text(
                    "${d.count} позиц. · ${d.menge} шт.",
                    fontSize = 12.sp,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(c.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text("${d.menge} шт.", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = c.onPrimaryContainer)
            }
        }
    }
}

/** Сводка, файлы и объёмы, статистика по дням. Показывается внутри ScrollBody окна «Инфо». */
@Composable
fun InfoStatsBlock() {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<InfoStatsData?>(null) }
    LaunchedEffect(Unit) { data = InfoStats.compute(ctx.applicationContext) }

    val d = data
    if (d == null) {
        EmptyHint("Загрузка…")
        return
    }

    SectionLabel("Общая сводка")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Товаров в каталоге", d.catalog.toString(), Modifier.weight(1f))
        StatCard("Всего приёмок", d.arrivals.toString(), Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Всего принято (шт.)", InfoStats.groupInt(d.totalMenge), Modifier.weight(1f))
        StatCard("Дней с приёмками", d.days.size.toString(), Modifier.weight(1f))
    }

    SectionLabel("Файлы и объёмы")
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SizeRow("📷", "Фото", "${d.photoCount} фото", InfoStats.fmtBytes(d.photoSize))
        SizeRow("📄", "Накладные", "${d.docCount} накл.", InfoStats.fmtBytes(d.docSize))
        SizeRow(
            "💾", "Всего данных", InfoStats.fmtBytes(d.totalSize),
            "каталог+история: " + InfoStats.fmtBytes(d.dbSize),
        )
    }

    SectionLabel("По дням")
    if (d.days.isEmpty()) {
        EmptyHint("Нет данных о приёмках.")
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            d.days.forEach { DayRow(it) }
        }
    }
    Spacer(Modifier.height(14.dp))
}
