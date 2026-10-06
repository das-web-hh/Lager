package ru.lager.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.lager.app.auth.AuthViewModel
import ru.lager.app.auth.Screen
import ru.lager.app.auth.ToastMsg
import ru.lager.app.ui.win.CardState
import ru.lager.app.ui.win.Win
import ru.lager.app.ui.win.WinEnv
import ru.lager.app.ui.win.WinNav
import ru.lager.app.ui.win.WindowHost
import ru.lager.app.ui.win.openCard

@Composable
fun AppRoot(
    vm: AuthViewModel,
    onUnlock: () -> Unit,
    onEnableBiometric: () -> Unit,
) {
    val screen by vm.screen.collectAsState()
    val loading by vm.loading.collectAsState()
    val toast by vm.toast.collectAsState()
    val offerBio by vm.offerBiometric.collectAsState()
    val bioEnabled by vm.biometricEnabled.collectAsState()
    val lang by vm.language.collectAsState()
    val dark by vm.darkTheme.collectAsState()
    val profile by vm.profile.collectAsState()
    val nav = remember { WinNav() }

    LaunchedEffect(screen) {
        if (screen !is Screen.Home) nav.clear()
    }
    LaunchedEffect(nav.stack.lastOrNull()) {
        if (nav.stack.lastOrNull() == Win.Profile) vm.loadProfile()
    }

    BackHandler(
        enabled = screen is Screen.Register1 || screen is Screen.Register2 ||
            screen is Screen.Register3 || screen is Screen.Reset1 || screen is Screen.Reset2,
    ) { vm.showLogin() }

    Surface(color = Color.Transparent, contentColor = Color.White) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LagerColors.Background),
        ) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                label = "screen",
            ) { s ->
                when (s) {
                    Screen.Login -> LoginScreen(
                        loading = loading,
                        onLogin = vm::login,
                        onForgot = vm::showReset,
                        onRegister = vm::showRegister,
                    )
                    Screen.Register1 -> RegisterEmailScreen(loading, vm::registerSendCode, vm::showLogin)
                    Screen.Register2 -> RegisterCodeScreen(vm::registerVerify, vm::showLogin)
                    Screen.Register3 -> RegisterProfileScreen(loading, vm::registerComplete, vm::showLogin)
                    Screen.Reset1 -> ResetEmailScreen(loading, vm::resetSendCode, vm::showLogin)
                    Screen.Reset2 -> ResetCodeScreen(loading, vm::resetVerify, vm::showLogin)
                    Screen.Lock -> {
                        LaunchedEffect(Unit) { onUnlock() }
                        LockScreen(onUnlock = onUnlock, onUsePassword = vm::logout)
                    }
                    is Screen.Home -> MainHomeScreen(
                        lang = lang,
                        dark = dark,
                        onLanguage = vm::setLanguage,
                        onToggleDark = vm::toggleDarkTheme,
                        onTile = { id, _, bounds ->
                            when (id) {
                                "receiving" -> nav.push(Win.ReceiveHub, bounds)
                                "inventory" -> nav.push(Win.Inventory, bounds)
                                "bware" -> nav.push(Win.BWare, bounds)
                                "bytask" -> nav.push(Win.Plan, bounds)
                                "history" -> nav.push(Win.History, bounds)
                                "catalog" -> nav.push(Win.Catalog, bounds)
                                "importexport" -> nav.push(Win.ImportExportSheet, bounds)
                                "settings" -> nav.push(Win.Settings, bounds)
                                "itembarcode" -> nav.push(Win.LinkTool, bounds)
                                "tasks" -> nav.push(Win.Tasks, bounds)
                                "profile" -> nav.push(Win.Profile, bounds)
                                "info" -> nav.push(Win.Info, bounds)
                                "documents" -> nav.push(Win.Documents, bounds)
                                "gemini" -> nav.push(Win.GeminiChat, bounds)
                            }
                        },
                        onScan = { vm.showInfo(Str.scannerSoon(lang)) },
                        onOpenCard = { row ->
                            nav.openCard(CardState.build(row.ean, row.name, row.date, row.batchId))
                        },
                    )
                }
            }

            val homeScreen = screen
            if (homeScreen is Screen.Home) {
                WindowHost(
                    WinEnv(
                        dark = dark,
                        lang = lang,
                        setLanguage = vm::setLanguage,
                        setDark = vm::setDarkTheme,
                        role = homeScreen.role,
                        profile = profile,
                        biometricAvailable = vm.biometricAvailable,
                        biometricEnabled = bioEnabled,
                        toggleBiometric = { enable ->
                            if (enable) onEnableBiometric() else vm.setBiometric(false)
                        },
                        logout = vm::logout,
                        info = vm::showInfo,
                        nav = nav,
                    ),
                )
            }
            BackHandler(enabled = nav.isOpen) { nav.pop() }

            ToastHost(
                toast = toast,
                onDismiss = vm::dismissToast,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (offerBio) {
                AlertDialog(
                    onDismissRequest = vm::biometricOfferDeclined,
                    title = { Text("Вход по биометрии") },
                    text = { Text("Включить быстрый вход по отпечатку пальца или лицу?") },
                    confirmButton = {
                        TextButton(onClick = onEnableBiometric) { Text("Включить") }
                    },
                    dismissButton = {
                        TextButton(onClick = vm::biometricOfferDeclined) { Text("Не сейчас") }
                    },
                )
            }
        }
    }
}

@Composable
private fun ToastHost(toast: ToastMsg?, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(toast) }
    if (toast != null) last = toast

    LaunchedEffect(toast?.id) {
        val t = toast ?: return@LaunchedEffect
        delay(3500)
        onDismiss(t.id)
    }

    AnimatedVisibility(
        visible = toast != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
            .systemBarsPadding()
            .padding(top = 12.dp, start = 16.dp, end = 16.dp),
    ) {
        val shown = last
        if (shown != null) {
            val accent = if (shown.isError) LagerColors.Red else LagerColors.Green
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xF20F1F38),
                contentColor = accent,
                border = BorderStroke(1.dp, accent),
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    shown.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}
