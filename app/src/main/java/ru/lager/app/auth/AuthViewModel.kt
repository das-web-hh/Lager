package ru.lager.app.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.lager.app.ui.Lang

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AuthRepository(app)

    private val _screen = MutableStateFlow(startScreen())
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _toast = MutableStateFlow<ToastMsg?>(null)
    val toast: StateFlow<ToastMsg?> = _toast.asStateFlow()

    private val _offerBiometric = MutableStateFlow(false)
    val offerBiometric: StateFlow<Boolean> = _offerBiometric.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(repo.biometricEnabled)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val uiPrefs = app.getSharedPreferences("lager_ui", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(Lang.fromCode(uiPrefs.getString("lang", null)))
    val language: StateFlow<Lang> = _language.asStateFlow()

    private val _darkTheme = MutableStateFlow(uiPrefs.getBoolean("dark", false))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    fun setLanguage(lang: Lang) {
        _language.value = lang
        uiPrefs.edit().putString("lang", lang.code).apply()
    }

    fun setDarkTheme(value: Boolean) {
        _darkTheme.value = value
        uiPrefs.edit().putBoolean("dark", value).apply()
    }

    private val _profile = MutableStateFlow<ProfileData?>(null)
    val profile: StateFlow<ProfileData?> = _profile.asStateFlow()

    fun loadProfile() {
        viewModelScope.launch {
            try {
                _profile.value = repo.loadProfile()
            } catch (e: Exception) {
                e.rethrowIfCancelled()
            }
        }
    }

    fun toggleDarkTheme() {
        val v = !_darkTheme.value
        _darkTheme.value = v
        uiPrefs.edit().putBoolean("dark", v).apply()
    }

    val biometricAvailable: Boolean get() = Biometrics.available(getApplication())

    private var regEmail = ""
    private var regCode: String? = null
    private var resetEmail = ""
    private var resetCode: String? = null

    init {
        if (_screen.value is Screen.Home) refreshRole()
    }

    private fun startScreen(): Screen = when {
        !repo.isSignedIn -> Screen.Login
        repo.biometricEnabled && Biometrics.available(getApplication()) -> Screen.Lock
        else -> Screen.Home(repo.cachedRole)
    }

    // ---- Служебное ----
    private fun Throwable.rethrowIfCancelled() {
        if (this is CancellationException) throw this
    }

    private fun busy(block: suspend () -> Unit) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                block()
            } finally {
                _loading.value = false
            }
        }
    }

    private fun toast(text: String, error: Boolean = false) {
        _toast.value = ToastMsg(System.nanoTime(), text, error)
    }

    fun showError(text: String) = toast(text, true)

    fun showInfo(text: String) = toast(text)

    fun dismissToast(id: Long) {
        if (_toast.value?.id == id) _toast.value = null
    }

    private fun emailErrorText(e: Throwable): String = when {
        e is EmailSendException && e.httpCode == 403 ->
            "EmailJS блокирует запросы из приложения: включите API для не-браузерных " +
                "приложений в настройках EmailJS (Account → Security)."
        e is FirebaseNetworkException -> "Нет соединения с интернетом."
        else -> "Ошибка при отправке кода на Email."
    }

    // ---- Навигация ----
    fun showLogin() { _screen.value = Screen.Login }
    fun showRegister() { _screen.value = Screen.Register1 }
    fun showReset() { _screen.value = Screen.Reset1 }

    private fun refreshRole() {
        viewModelScope.launch {
            try {
                val role = repo.currentRole()
                if (_screen.value is Screen.Home) _screen.value = Screen.Home(role)
            } catch (e: Exception) {
                e.rethrowIfCancelled()
            }
        }
    }

    private fun enterHome(role: Role, message: String) {
        toast(message)
        _screen.value = Screen.Home(role)
        if (Biometrics.available(getApplication()) &&
            !repo.biometricAsked && !repo.biometricEnabled
        ) {
            _offerBiometric.value = true
        }
    }

    // ---- Вход ----
    fun login(input: String, password: String) {
        if (input.isBlank() || password.isBlank()) {
            toast("Введите Email/Логин и пароль", true)
            return
        }
        busy {
            try {
                enterHome(repo.login(input, password), "Успешный вход!")
            } catch (e: FirebaseNetworkException) {
                toast("Нет соединения с интернетом.", true)
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                toast("Ошибка входа: неверный Email/Логин или пароль.", true)
            }
        }
    }

    fun logout() {
        repo.signOut()
        _biometricEnabled.value = false
        _offerBiometric.value = false
        _screen.value = Screen.Login
    }

    // ---- Биометрия ----
    fun unlocked() {
        _screen.value = Screen.Home(repo.cachedRole)
        refreshRole()
    }

    fun lockIfNeeded() {
        if (_screen.value is Screen.Home && repo.isSignedIn &&
            repo.biometricEnabled && Biometrics.available(getApplication())
        ) {
            _screen.value = Screen.Lock
        }
    }

    fun setBiometric(enabled: Boolean) {
        repo.biometricAsked = true
        repo.biometricEnabled = enabled
        _biometricEnabled.value = enabled
        _offerBiometric.value = false
        toast(if (enabled) "Вход по биометрии включён" else "Вход по биометрии выключен")
    }

    fun biometricOfferDeclined() {
        repo.biometricAsked = true
        _offerBiometric.value = false
    }

    fun dismissOffer() {
        _offerBiometric.value = false
    }

    // ---- Регистрация ----
    fun registerSendCode(emailRaw: String) {
        val email = emailRaw.trim().lowercase()
        if (email.isEmpty() || !email.contains("@")) {
            toast("Введите корректный Email", true)
            return
        }
        busy {
            try {
                if (repo.emailExists(email)) {
                    toast("Пользователь с таким Email уже существует!", true)
                    return@busy
                }
                regCode = repo.sendCode(email)
                regEmail = email
                toast("Код отправлен на $email")
                _screen.value = Screen.Register2
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                toast(emailErrorText(e), true)
            }
        }
    }

    fun registerVerify(code: String) {
        if (code.trim() != regCode) {
            toast("Неверный код из Email!", true)
            return
        }
        toast("Email подтверждён!")
        _screen.value = Screen.Register3
    }

    fun registerComplete(first: String, last: String, password: String, observer: Boolean) {
        if (first.isBlank() || last.isBlank()) {
            toast("Укажите имя и фамилию", true)
            return
        }
        if (password.trim().length < 6) {
            toast("Пароль должен быть не короче 6 символов", true)
            return
        }
        busy {
            try {
                val role = repo.register(regEmail, first.trim(), last.trim(), password, observer)
                enterHome(role, "Регистрация успешно завершена!")
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                toast("Ошибка сохранения: ${e.message}", true)
            }
        }
    }

    // ---- Сброс пароля ----
    fun resetSendCode(emailRaw: String) {
        val email = emailRaw.trim().lowercase()
        if (email.isEmpty() || !email.contains("@")) {
            toast("Введите корректный Email", true)
            return
        }
        busy {
            try {
                if (!repo.emailExists(email)) {
                    toast("Пользователь с таким Email не найден!", true)
                    return@busy
                }
                resetCode = repo.sendCode(email)
                resetEmail = email
                toast("Код отправлен на $email")
                _screen.value = Screen.Reset2
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                toast(emailErrorText(e), true)
            }
        }
    }

    fun resetVerify(code: String) {
        if (code.trim() != resetCode) {
            toast("Неверный код из Email!", true)
            return
        }
        busy {
            try {
                repo.sendResetLink(resetEmail)
                toast("Ссылка для сброса отправлена на почту!")
                _screen.value = Screen.Login
            } catch (e: Exception) {
                e.rethrowIfCancelled()
                toast("Ошибка сброса: ${e.message}", true)
            }
        }
    }
}
