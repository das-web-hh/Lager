package ru.lager.app.ui.win

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class GeminiKey(val key: String, val limit: Int) {
    val masked: String get() = if (key.length <= 8) "••••" else key.take(4) + "…" + key.takeLast(4)
}

/** Настройки приложения (аналог localStorage в HTML). Хранятся на устройстве. */
object SettingsStore {
    const val MAX_KEYS = 10

    const val DEFAULT_BWARE_PROMPT =
        "Ты — умный помощник складской программы. Проанализируй прикреплённое фото накладной (Lieferschein / Auftragsbestätigung).\n" +
            "Извлеки только данные шапки документа. Список товаров и их данные НЕ извлекай.\n\n" +
            "Верни СТРОГО один JSON-объект без markdown и без пояснений, с такими ключами:\n" +
            "1. \"lieferant\": название компании-поставщика (Absender, отправитель на документе).\n" +
            "2. \"bestell_nr\": номер заказа (ищи \"Bestell-Nr.\", \"Ihre Bestell-Nr.\" или номер с префиксом EB). Если его нет, верни пустую строку \"\".\n" +
            "3. \"lieferschein_nr\": номер накладной (ищи \"LS-Nr.\", \"Lieferschein-Nr.\", \"Beleg-Nr.\"). Если нет, верни \"\".\n" +
            "4. \"datum\": дата доставки в формате ДД.ММ.ГГГГ. Если дата не найдена, подставь текущую дату системы: {{DATE}}.\n" +
            "5. \"pruefung\": пустая строка \"\". Если текст нечёткий или какое-то поле нельзя прочитать надёжно, верни \"Требуется ручная проверка (нечеткий текст)\".\n\n" +
            "ВАЖНО:\n" +
            "- Ничего не придумывай. Если на накладной нет номера заказа (Bestell-Nr.), верни \"\" — не угадывай его.\n" +
            "- Если поле прочитано не полностью, верни то, что удалось прочитать, и заполни \"pruefung\".\n" +
            "- STRÖH / Ströh — это получатель (наша компания), а не поставщик. Поставщик — тот, кто отправил товар.\n" +
            "- Верни только JSON, без текста до и после."

    fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("lager_settings", Context.MODE_PRIVATE)

    fun str(ctx: Context, key: String, def: String = "") = sp(ctx).getString(key, def) ?: def
    fun putStr(ctx: Context, key: String, v: String) { sp(ctx).edit().putString(key, v).apply() }

    fun bwarePrompt(ctx: Context): String = str(ctx, "bwarePrompt").ifBlank { DEFAULT_BWARE_PROMPT }
    fun saveBwarePrompt(ctx: Context, v: String) {
        val t = v.trim()
        if (t.isEmpty() || t == DEFAULT_BWARE_PROMPT) sp(ctx).edit().remove("bwarePrompt").apply() else putStr(ctx, "bwarePrompt", t)
    }

    /** Уровень вибрации: 0 выключена … 3 высокая. */
    fun vibration(ctx: Context) = sp(ctx).getInt("vibration", 2)
    /** Камера: 0 авто (задняя), 1 задняя, 2 фронтальная. */
    fun camera(ctx: Context) = sp(ctx).getInt("camera", 0)

    fun geminiKeys(ctx: Context): List<GeminiKey> = runCatching {
        val arr = JSONArray(str(ctx, "geminiKeys", "[]"))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { GeminiKey(it.optString("key"), it.optInt("limit", 200000)) }
            .filter { it.key.isNotBlank() }
    }.getOrDefault(emptyList())

    fun saveGeminiKeys(ctx: Context, list: List<GeminiKey>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("key", it.key).put("limit", it.limit)) }
        putStr(ctx, "geminiKeys", arr.toString())
    }

    // ---------- проверки связи (вызывать из фонового потока) ----------

    /** GET-запрос. Возвращает код ответа или текст ошибки. */
    fun httpCheck(url: String): Result<Int> = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        try { c.responseCode } finally { c.disconnect() }
    }

    fun tcpCheck(host: String, port: Int): Result<Unit> = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
    }
}

private class PrefState<T>(initial: T, private val write: (T) -> Unit) : MutableState<T> {
    private val st = mutableStateOf(initial)
    override var value: T
        get() = st.value
        set(v) { st.value = v; write(v) }
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

@Composable
fun rememberPrefInt(key: String, def: Int): MutableState<Int> {
    val ctx = LocalContext.current
    return remember(key) { PrefState(SettingsStore.sp(ctx).getInt(key, def)) { SettingsStore.sp(ctx).edit().putInt(key, it).apply() } }
}

@Composable
fun rememberPrefBool(key: String, def: Boolean): MutableState<Boolean> {
    val ctx = LocalContext.current
    return remember(key) { PrefState(SettingsStore.sp(ctx).getBoolean(key, def)) { SettingsStore.sp(ctx).edit().putBoolean(key, it).apply() } }
}

@Composable
fun rememberPrefFloat(key: String, def: Float): MutableState<Float> {
    val ctx = LocalContext.current
    return remember(key) { PrefState(SettingsStore.sp(ctx).getFloat(key, def)) { SettingsStore.sp(ctx).edit().putFloat(key, it).apply() } }
}
