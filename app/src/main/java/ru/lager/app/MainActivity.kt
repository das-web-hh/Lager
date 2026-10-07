package ru.lager.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import ru.lager.app.auth.AuthViewModel
import ru.lager.app.auth.Biometrics
import ru.lager.app.ui.AppRoot
import ru.lager.app.ui.LagerTheme
import ru.lager.app.ui.win.ShareState

class MainActivity : FragmentActivity() {

    private val vm: AuthViewModel by viewModels()
    private var stoppedAt = 0L
    private var promptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) ShareState.handle(this, intent)
        setContent {
            LagerTheme {
                AppRoot(
                    vm = vm,
                    onUnlock = ::promptUnlock,
                    onEnableBiometric = ::promptEnable,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShareState.handle(this, intent)
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // Если приложение было в фоне дольше минуты, снова просим биометрию
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > LOCK_AFTER_MS) {
            vm.lockIfNeeded()
        }
    }

    private fun promptUnlock() {
        if (promptShowing) return
        promptShowing = true
        Biometrics.prompt(
            activity = this,
            title = "Lager",
            subtitle = "Подтвердите личность для входа",
            onSuccess = {
                promptShowing = false
                vm.unlocked()
            },
            onFailure = { cancelled, message ->
                promptShowing = false
                if (!cancelled) vm.showError(message)
            },
        )
    }

    private fun promptEnable() {
        if (promptShowing) return
        promptShowing = true
        Biometrics.prompt(
            activity = this,
            title = "Вход по биометрии",
            subtitle = "Подтвердите, чтобы включить",
            onSuccess = {
                promptShowing = false
                vm.setBiometric(true)
            },
            onFailure = { cancelled, message ->
                promptShowing = false
                vm.dismissOffer()
                if (!cancelled) vm.showError(message)
            },
        )
    }

    private companion object {
        const val LOCK_AFTER_MS = 60_000L
    }
}
