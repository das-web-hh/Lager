package ru.lager.app.ui.win

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.lager.app.ui.BarcodeScanIcon

// ---------- Приём по заданию ----------

@Composable
fun PlanWindow(env: WinEnv) {
    var plan by rememberSaveable { mutableStateOf("") }
    WindowScaffold(
        title = "Приём по заданию",
        actions = {
            TopBarTextButton("Вставить") { env.info("Вставка из буфера — в разработке") }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .md3Clickable(color = Color.White) { env.nav.push(Win.PlanSession) },
                contentAlignment = Alignment.Center,
            ) { BarcodeScanIcon(Color.White, Modifier.size(24.dp)) }
        },
        footer = { LongButton("📷 Сканировать товары", LongKind.Blue, { env.nav.push(Win.PlanSession) }) },
    ) {
        ScrollBody {
            LabeledInput("", plan, { plan = it }, placeholder = "Добавить задачу", singleLine = false, minHeight = 110)
            EmptyHint("Задание пока пустое")
        }
    }
}

@Composable
fun PlanSessionWindow(env: WinEnv) {
    val c = Md3.c
    var showOrder by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf("") }
    WindowScaffold(
        title = "Приём по заданию",
        actions = { TopBarTextButton("Далее") { showOrder = true } },
        footer = { LongButton("Далее", LongKind.Green, { showOrder = true }) },
    ) {
        ScrollBody {
            EmptyHint("Список отсканированных товаров пуст")
            Text(
                "Нажмите кнопку сканирования, чтобы считать штрихкод из локальной базы.",
                color = c.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (showOrder) {
        DialogCard(
            title = "Номер заказа",
            onDismiss = { showOrder = false },
            actions = {
                DialogActionConfirm("Сохранить", {
                    showOrder = false
                    env.nav.push(Win.PlanResult)
                })
            },
        ) {
            LabeledInput("", order, { order = it.filter(Char::isDigit) }, keyboardType = KeyboardType.Number)
        }
    }
}

@Composable
fun PlanResultWindow(env: WinEnv) {
    WindowScaffold(
        title = "Результат сверки",
        actions = { TopBarTextButton("💾") { env.info("Сохранение — в разработке") } },
        footer = { LongButton("Сохранить", LongKind.Green, { env.info("Сохранение — в разработке") }) },
    ) {
        ScrollBody { EmptyHint("Нет данных для сверки") }
    }
}
