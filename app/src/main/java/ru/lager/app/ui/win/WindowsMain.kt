package ru.lager.app.ui.win

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.lager.app.auth.Role
import ru.lager.app.ui.BarcodeScanIcon

// ---------- Приём товаров (список) ----------

@Composable
private fun HubRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Md3.c
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .md3Clickable(onClick = onClick)
                .heightIn(min = 76.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(30.dp))
            }
            Text(
                label,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = c.onSurface,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
        }
        RowDivider()
    }
}

@Composable
fun ReceiveHubWindow(env: WinEnv) {
    WindowScaffold("Приём товаров") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HubRow(Icons.Filled.MoveToInbox, "Приём товаров - вручную") {
                env.nav.push(Win.ReceiveManual)
            }
            HubRow(Icons.Filled.Description, "Приём товаров по наименованиям") {
                env.nav.push(Win.ReceiveName)
            }
            HubRow(Icons.Filled.FlashOn, "Автоприём") {
                env.nav.push(Win.ReceiveAuto)
            }
        }
    }
}

// Задачи: см. WindowsTasksInfo.kt и TaskStore.kt

// ---------- Профиль ----------

@Composable
private fun ProfileLine(label: String, value: String) {
    val c = Md3.c
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVariant)
            Text(
                value,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        RowDivider()
    }
}

@Composable
fun ProfileWindow(env: WinEnv) {
    val p = env.profile
    val roleLabel = when (env.role) {
        Role.ADMIN -> "Администратор"
        Role.OBSERVER -> "Наблюдатель"
        Role.USER -> "Сотрудник склада"
    }
    WindowScaffold("Профиль") {
        ScrollBody {
            Md3Card {
                ProfileLine("Имя", p?.firstName ?: "—")
                ProfileLine("Фамилия", p?.lastName ?: "—")
                ProfileLine("Email", p?.email ?: "—")
                ProfileLine("Логин", p?.email ?: "—")
                ProfileLine("Пароль", "••••••••")
                ProfileLine("Тип учётной записи", roleLabel)
                ProfileLine("ID аккаунта", p?.uid ?: "—")
            }
            if (env.biometricAvailable) {
                Spacer(Modifier.height(14.dp))
                Md3Card {
                    SettingsRow(
                        icon = "👆",
                        title = "Вход по биометрии",
                        sub = "Отпечаток или лицо вместо пароля",
                        trailing = { Md3Switch(env.biometricEnabled, env.toggleBiometric) },
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            LongButton("Выход", LongKind.Red, { env.nav.clear(); env.logout() })
        }
    }
}

// ---------- Инфо ----------

@Composable
fun InfoWindow() {
    var showAbout by remember { mutableStateOf(false) }
    WindowScaffold("Инфо") {
        ScrollBody {
            InfoStatsBlock()
            Md3Card {
                SettingsRow(icon = "ℹ️", title = "О программе", sub = "Авторы и версия", onClick = { showAbout = true })
            }
        }
    }
    if (showAbout) AppInfoDialog { showAbout = false }
}

@Composable
private fun CreditRow(icon: String, title: String, sub: String) {
    val c = Md3.c
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceLow)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(icon, fontSize = 22.sp)
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 12.sp, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppInfoDialog(onDismiss: () -> Unit) {
    val c = Md3.c
    DialogCard(title = "О программе", onDismiss = onDismiss) {
        CreditRow("🤖", "Google Gemini", "AI-ассистент разработки")
        CreditRow("💡", "Sticklight", "Дизайн-концепция")
        CreditRow("⚙️", "WebCode 7.0", "Движок приложения")
        CreditRow("👨‍💻", "Marufov", "Автор идеи и постановщик")
        CreditRow("📦", "Firebase + Google Drive", "Данные и файлы в облаке")
        Text(
            "© 2026 · Lager v0.2",
            fontSize = 12.sp,
            color = c.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        SoftButton("Закрыть", onDismiss, Modifier.fillMaxWidth())
    }
}

