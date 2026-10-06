package ru.lager.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.lager.app.auth.Role

/** Экран биометрической блокировки (сессия сохранена, нужно подтвердить личность). */
@Composable
fun LockScreen(onUnlock: () -> Unit, onUsePassword: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LogoBadge()
        Spacer(Modifier.height(24.dp))
        Text("С возвращением", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Подтвердите вход отпечатком пальца или лицом",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(LagerColors.Blue.copy(alpha = 0.18f), CircleShape)
                .border(BorderStroke(1.5.dp, LagerColors.Blue), CircleShape)
                .clickable(onClick = onUnlock),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Fingerprint,
                contentDescription = "Разблокировать",
                tint = LagerColors.Blue,
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.height(36.dp))
        Text(
            "Войти по паролю",
            color = LagerColors.Orange,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(onClick = onUsePassword)
                .padding(12.dp),
        )
    }
}

/** Временное окно «Профиль»: роль, биометрия, выход из аккаунта. */
@Composable
fun ProfileDialog(
    lang: Lang,
    role: Role,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val roleLabel = when (role) {
        Role.ADMIN -> Str.roleAdmin(lang)
        Role.OBSERVER -> Str.roleObserver(lang)
        Role.USER -> Str.roleUser(lang)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Str.profile(lang)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(roleLabel, color = Color.White.copy(alpha = 0.7f))
                if (biometricAvailable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Str.biometric(lang), fontWeight = FontWeight.SemiBold)
                            Text(
                                Str.biometricSub(lang),
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = onToggleBiometric,
                            colors = SwitchDefaults.colors(checkedTrackColor = LagerColors.Blue),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onLogout()
            }) { Text(Str.logout(lang), color = LagerColors.Red) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Str.close(lang)) }
        },
    )
}
