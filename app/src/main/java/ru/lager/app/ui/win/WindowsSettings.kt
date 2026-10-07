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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lager.app.ui.Lang

@Composable
private fun CardColumn(content: @Composable ColumnScope.() -> Unit) {
    Md3Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun PrefRow(title: String, hint: String? = null, control: @Composable () -> Unit) {
    val c = Md3.c
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, fontSize = 12.sp, color = c.onSurfaceVariant)
        }
        control()
    }
}

@Composable
private fun SliderField(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Column {
        FieldLabel("$label: $valueText")
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun SettingsWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Оформление
    var iconBg by rememberPrefInt("iconBg", 0)
    var rcvStyle by rememberPrefInt("rcvStyle", 0)
    // Google Диск
    var driveUrl by rememberSaveable { mutableStateOf(SettingsStore.str(ctx, "driveUrl")) }
    var driveFolder by rememberSaveable { mutableStateOf(SettingsStore.str(ctx, "driveFolder")) }
    var codeField by rememberPrefInt("codeField", 0)
    var drivePublic by rememberPrefBool("drivePublic", false)
    // Интерфейс и сканер
    var virtualKb by rememberPrefBool("virtualKb", true)
    var extScanner by rememberPrefBool("extScanner", false)
    var camera by rememberPrefInt("camera", 0)
    // Звук
    var tapSound by rememberPrefInt("tapSound", 0)
    var scanSound by rememberPrefInt("scanSound", 0)
    var wheelSound by rememberPrefInt("wheelSound", 0)
    var vibration by rememberPrefInt("vibration", 2)
    // Голос
    var voiceOn by rememberPrefInt("voiceOn", 0)
    var rate by rememberPrefFloat("voiceRate", 1f)
    var pitch by rememberPrefFloat("voicePitch", 1f)
    var volume by rememberPrefFloat("voiceVolume", 1f)
    var phrase by rememberSaveable { mutableStateOf(SettingsStore.str(ctx, "voicePhrase")) }
    var speakTaps by rememberPrefInt("speakTaps", 0)
    var showSec by rememberPrefInt("showSec", 2)
    var autoSec by rememberPrefInt("autoSec", 2)
    // Wi-Fi сканер, Python
    var pcIp by rememberSaveable { mutableStateOf(SettingsStore.str(ctx, "pcIp")) }
    var pcPort by rememberSaveable { mutableStateOf(SettingsStore.str(ctx, "pcPort", "8080")) }
    var pyUrl by rememberSaveable { mutableStateOf(SettingsStore.str(ctx, "pyUrl", "http://localhost:8050")) }
    var pyStartup by rememberPrefBool("pyStartup", true)
    // Gemini
    var geminiModel by rememberPrefInt("geminiModel", 0)
    var instruction by rememberSaveable { mutableStateOf(SettingsStore.str(ctx, "instruction")) }
    var attachInstruction by rememberPrefBool("attachInstruction", false)
    var snapshotPrompt by rememberSaveable { mutableStateOf(SettingsStore.str(ctx, "snapshotPrompt")) }
    var attachPng by rememberPrefBool("attachPng", false)
    var bwarePrompt by rememberSaveable { mutableStateOf(SettingsStore.bwarePrompt(ctx)) }
    var showKeyEditor by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf(SettingsStore.geminiKeys(ctx)) }
    // Хранилище
    var autoInterval by rememberPrefInt("autoInterval", 4)
    var geminiTimeout by rememberPrefInt("geminiTimeout", 1)
    var geminiPause by rememberPrefInt("geminiPause", 1)
    var geminiAttempts by rememberPrefInt("geminiAttempts", 2)
    var cleanup by rememberPrefBool("cleanup", false)
    // Очистка мест: черновик адреса, уровень, открытое окно выбора и подтверждение
    var cdW by remember { mutableStateOf("") }
    var cdRow by remember { mutableStateOf("--") }
    var cdFloor by remember { mutableStateOf("--") }
    var cdShelf by remember { mutableStateOf("--") }
    var cdScope by remember { mutableStateOf("") }
    var cdPicker by remember { mutableStateOf("") }
    var cdConfirm by remember { mutableStateOf(false) }
    var cdStatus by remember { mutableStateOf("") }
    var cdStatusErr by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { InventoryStore.load(ctx) }
    var photoFolder by remember { mutableStateOf(SettingsStore.str(ctx, "photoFolder")) }
    var docFolder by remember { mutableStateOf(SettingsStore.str(ctx, "docFolder")) }
    var eraseAsk by remember { mutableStateOf(false) }

    fun folderTitle(uri: String): String? = runCatching {
        android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(uri)).substringAfter(':').ifEmpty { "Память телефона" }
    }.getOrNull()

    fun pickFolder(key: String, set: (String) -> Unit): (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SettingsStore.putStr(ctx, key, uri.toString())
            set(uri.toString())
        }
    }
    val photoFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), pickFolder("photoFolder") { photoFolder = it })
    val docFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), pickFolder("docFolder") { docFolder = it })

    // Проверка голоса: TextToSpeech создаётся по требованию и освобождается при закрытии окна.
    var tts by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    DisposableEffect(Unit) { onDispose { tts?.shutdown() } }
    fun speakTest() {
        val say = (phrase.trim() + " пятнадцать").trim()
        fun go(t: android.speech.tts.TextToSpeech) {
            t.language = java.util.Locale("ru", "RU")
            t.setPitch(pitch)
            t.setSpeechRate(rate)
            val b = android.os.Bundle().apply { putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
            t.speak(say, android.speech.tts.TextToSpeech.QUEUE_FLUSH, b, "lager_test")
        }
        val existing = tts
        if (existing != null) { go(existing); return }
        var created: android.speech.tts.TextToSpeech? = null
        created = android.speech.tts.TextToSpeech(ctx.applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) created?.let { go(it) } else env.info("Голосовой движок недоступен")
        }
        tts = created
    }

    fun probe(label: String, block: () -> Result<Int>) {
        env.info("$label: проверяю…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            r.onSuccess { code ->
                env.info(if (code in 200..399) "$label: отвечает (код $code)" else "$label: ответ с кодом $code")
            }.onFailure { env.info("$label: нет связи (${it.message ?: it.javaClass.simpleName})") }
        }
    }

    WindowScaffold("Настройки") {
        ScrollBody {
            // ----- Оформление -----
            SectionLabel("Оформление")
            Md3Card {
                PrefRow("Язык") {
                    SegmentedPill(Lang.entries.map { it.code.uppercase() }, Lang.entries.indexOf(env.lang), { env.setLanguage(Lang.entries[it]) })
                }
                RowDivider()
                PrefRow("Тема") {
                    SegmentedPill(listOf("Светлая", "Тёмная"), if (env.dark) 1 else 0, { env.setDark(it == 1) })
                }
                RowDivider()
                PrefRow("Фон иконок", "Главный экран") {
                    SegmentedPill(listOf("Как сейчас", "С фоном"), iconBg, { iconBg = it }, compact = true)
                }
                RowDivider()
                PrefRow("Окно приёма", "Как открывается приём товаров") {
                    SegmentedPill(listOf("Три окна", "Единое окно"), rcvStyle, { rcvStyle = it }, compact = true)
                }
            }

            // ----- Google Диск -----
            SectionLabel("Google Диск")
            CardColumn {
                HintText("Укажите URL опубликованного Google Apps Script Web App и ID папки Google Диска. Эти параметры хранятся только на этом устройстве.")
                LabeledInput("URL Web App Google Apps Script", driveUrl, { driveUrl = it }, placeholder = "https://script.google.com/macros/s/.../exec", keyboardType = KeyboardType.Uri)
                LabeledInput("ID папки Google Диска", driveFolder, { driveFolder = it }, placeholder = "1AbCDefGhI...")
                SelectField(
                    "Поле кода файла",
                    listOf("EAN / штрихкод", "Артикул", "Номер партии / накладной", "Автоматически: EAN → артикул → партия"),
                    codeField, { codeField = it },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Checkbox(checked = drivePublic, onCheckedChange = { drivePublic = it })
                    Text("Разрешить просмотр загруженных файлов по ссылке (нужно для показа без входа в Google)", fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("Сохранить", {
                        SettingsStore.putStr(ctx, "driveUrl", driveUrl.trim())
                        SettingsStore.putStr(ctx, "driveFolder", driveFolder.trim())
                        env.info("Настройки Google Диска сохранены")
                    }, Modifier.weight(1f), filled = true)
                    SoftButton("Проверить", {
                        val u = driveUrl.trim()
                        if (!u.startsWith("http")) env.info("Укажите URL Web App") else probe("Web App") { SettingsStore.httpCheck(u) }
                    }, Modifier.weight(1f))
                }
                LaunchedEffect(Unit) { DriveOutbox.refreshPending(ctx) }
                val gdPending = DriveOutbox.pending.value
                val gdStatus = DriveOutbox.status.value
                if (gdStatus.isNotEmpty()) {
                    HintText(gdStatus)
                } else if (gdPending > 0) {
                    HintText("Ожидают отправки на Google Диск: $gdPending файл(ов). Уйдут автоматически после настройки.")
                }
                if (gdPending > 0) {
                    SoftButton("Отправить очередь сейчас", {
                        val c = DriveOutbox.config(ctx)
                        if (!c.ready) env.info("Укажите URL Web App и ID папки и сохраните") else scope.launch { DriveOutbox.flush(ctx) }
                    }, Modifier.fillMaxWidth())
                }
                HintText("Файлы получают имена вида код_дата_имя-файла. При открытии карточки приложение ищет этот код в папке и показывает найденные фото и PDF.")
            }

            // ----- Интерфейс -----
            SectionLabel("Интерфейс")
            Md3Card {
                SettingsRow(
                    icon = "⌨️", title = "Виртуальная клавиатура",
                    sub = if (virtualKb) "Включена · окно уменьшено" else "Выключена",
                    trailing = { Md3Switch(virtualKb) { virtualKb = it } },
                )
            }

            // ----- Камера -----
            SectionLabel("Камера")
            Md3Card {
                SettingsRow(
                    icon = "📷", title = "Камера", sub = "Какая камера используется для сканирования",
                ) {
                    SelectField("", listOf("Автоматический выбор", "Задняя", "Фронтальная"), camera, { camera = it })
                }
            }

            // ----- Звук и вибрация -----
            SectionLabel("Звук и вибрация")
            CardColumn {
                HintText("Выберите отдельный сигнал для нажатия на товар, сканирования камерой и прокрутки количества. Уровень вибрации применяется ко всем вибрациям приложения.")
                val sounds = listOf("Стандартный", "Короткий", "Мягкий", "Без звука")
                SelectField("Сигнал при тапе", sounds, tapSound, { tapSound = it })
                SelectField("Сигнал при сканировании камерой", sounds, scanSound, { scanSound = it })
                SelectField("Сигнал при выборе количества прокруткой", sounds, wheelSound, { wheelSound = it })
                SelectField("Уровень вибрации", listOf("Выключена", "Низкая", "Средняя", "Высокая"), vibration, { vibration = it })
                HintText("Изменения сохраняются сразу и действуют после закрытия настроек.")
            }

            // ----- Голос -----
            SectionLabel("Голос и счётчик количества")
            CardColumn {
                HintText("В «Приёме по наименованию» голос называет только последнее число со счётчика, например «пятнадцать».")
                SelectField("Голос", listOf("Включён", "Выключен"), voiceOn, { voiceOn = it })
                SliderField("Скорость", rate, 0.5f..2f, "%.1f".format(rate)) { rate = it }
                SliderField("Тембр (высота голоса)", pitch, 0.5f..2f, "%.1f".format(pitch)) { pitch = it }
                SliderField("Громкость", volume, 0f..1f, "${(volume * 100).toInt()}%") { volume = it }
                LabeledInput("Слово перед числом (необязательно)", phrase, { phrase = it; SettingsStore.putStr(ctx, "voicePhrase", it) }, placeholder = "Пусто — голос говорит только число")
                SelectField("Озвучивать серию тапов (в конце серии)", listOf("Да", "Нет"), speakTaps, { speakTaps = it })
                SelectField(
                    "Сколько секунд держать добавленное число на экране",
                    listOf("1 сек", "2 сек", "3 сек (стандарт)", "4 сек", "5 сек", "7 сек", "10 сек"),
                    showSec, { showSec = it },
                )
                SelectField(
                    "Авто-подтверждение количества в окне прокрутки",
                    listOf("Выключено", "Через 3 сек", "Через 5 сек (стандарт)", "Через 8 сек", "Через 10 сек", "Через 15 сек"),
                    autoSec, { autoSec = it },
                )
                SoftButton("🔊 Проверить голос", { speakTest() }, Modifier.fillMaxWidth())
            }

            // ----- Внешний сканер -----
            SectionLabel("Внешний сканер")
            Md3Card {
                SettingsRow(
                    icon = "📡", title = "Беспроводной сканер",
                    sub = if (extScanner) "Включён" else "Выключен · подключите Bluetooth-сканер как клавиатуру",
                    trailing = { Md3Switch(extScanner) { extScanner = it } },
                )
                HintText(
                    "Сопрягите сканер в настройках телефона. В приложении отдельное подключение не требуется.",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }

            // ----- Сканер через Wi-Fi -----
            SectionLabel("Сканер через Wi‑Fi")
            CardColumn {
                HintText("Введите IP-адрес компьютера, к которому подключён сканер. На компьютере должен работать WebSocket-мост.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledInput("IP-адрес компьютера", pcIp, { pcIp = it }, Modifier.weight(2f), placeholder = "192.168.1.50")
                    LabeledInput("Порт", pcPort, { pcPort = it.filter(Char::isDigit) }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("Сохранить", {
                        SettingsStore.putStr(ctx, "pcIp", pcIp.trim())
                        SettingsStore.putStr(ctx, "pcPort", pcPort.ifEmpty { "8080" })
                        env.info("Адрес сканера сохранён")
                    }, Modifier.weight(1f), filled = true)
                    SoftButton("Подключить", {
                        val ip = pcIp.trim()
                        val port = pcPort.toIntOrNull() ?: 8080
                        if (ip.isEmpty()) env.info("Укажите IP-адрес компьютера") else {
                            env.info("Подключаюсь к $ip:$port…")
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { SettingsStore.tcpCheck(ip, port) }
                                env.info(if (r.isSuccess) "Компьютер $ip:$port доступен" else "Нет связи с $ip:$port")
                            }
                        }
                    }, Modifier.weight(1f))
                }
                HintText("Схема: сканер → компьютер → WebSocket на порту 8080 → этот телефон. Bluetooth-сканер продолжает работать отдельно.")
            }

            // ----- Python -----
            SectionLabel("Python")
            CardColumn {
                HintText("Укажите адрес Python-сервера. При запуске приложение отправит на него GET-запрос и ожидает JSON-ответ.")
                LabeledInput("Адрес Python-сервера", pyUrl, { pyUrl = it }, placeholder = "http://localhost:8050", keyboardType = KeyboardType.Uri)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("Сохранить", {
                        SettingsStore.putStr(ctx, "pyUrl", pyUrl.trim())
                        env.info("Адрес Python-сервера сохранён")
                    }, Modifier.weight(1f), filled = true)
                    SoftButton("Проверить", {
                        val u = pyUrl.trim()
                        if (!u.startsWith("http")) env.info("Адрес должен начинаться с http://") else probe("Python-сервер") { SettingsStore.httpCheck(u) }
                    }, Modifier.weight(1f))
                }
                LabeledInput("Папка входящих файлов", "", { }, placeholder = "Не получена от Python-сервера")
                LabeledInput("Папка обработанных файлов", "", { }, placeholder = "Не получена от Python-сервера")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Проверять при запуске", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        HintText(if (pyStartup) "Включено · запрос отправляется при открытии" else "Выключено")
                    }
                    Md3Switch(pyStartup) { pyStartup = it }
                }
            }

            // ----- Инвентаризация -----
            SectionLabel("Инвентаризация")
            CardColumn {
                Text("Очистка мест", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                val openPicker: (String) -> Unit = { part ->
                    val need = when {
                        part == "row" && cdW.isEmpty() -> "Сначала выберите склад"
                        part == "floor" && (cdW.isEmpty() || cdRow == "--") -> "Сначала выберите склад и ряд"
                        part == "shelf" && (cdW.isEmpty() || cdRow == "--" || cdFloor == "--") -> "Сначала выберите склад, ряд и этаж"
                        else -> ""
                    }
                    if (need.isNotEmpty()) { cdStatus = need; cdStatusErr = true } else cdPicker = part
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddressPart("Склад", cdW.ifEmpty { "—" }, { openPicker("warehouse") }, Modifier.weight(1f))
                    AddressPart("Ряд", cdRow, { openPicker("row") }, Modifier.weight(1f))
                    AddressPart("Этаж", cdFloor, { openPicker("floor") }, Modifier.weight(1f))
                    AddressPart("Полка", cdShelf, { openPicker("shelf") }, Modifier.weight(1f))
                }
                HintText("Выберите склад, ряд, этаж или полку с товарами. Пустые места не показываются.")
                if (cdStatus.isNotEmpty()) {
                    StatusLine(cdStatus, if (cdStatusErr) StatusKind.Err else StatusKind.Ok, Modifier.fillMaxWidth())
                }
                LongButton("Удалить", LongKind.Red, {
                    val ok = cdScope.isNotEmpty() && cdW.isNotEmpty() &&
                        (cdScope == "warehouse" || cdRow != "--") &&
                        (cdScope != "floor" && cdScope != "shelf" || cdFloor != "--") &&
                        (cdScope != "shelf" || cdShelf != "--")
                    if (!ok) {
                        cdStatus = "Выберите место для удаления"; cdStatusErr = true
                    } else {
                        val t = InvAddr(cdW, cdRow, cdFloor, cdShelf)
                        val (places, _, _) = InventoryStore.scopeStats(t, cdScope)
                        if (places == 0) { cdStatus = "В этом месте пока нет товаров."; cdStatusErr = true }
                        else cdConfirm = true
                    }
                })
            }

            // ----- Искусственный интеллект -----
            SectionLabel("Искусственный интеллект")
            CardColumn {
                HintText("Эти параметры используются для обработки документов через Google Gemini. Ключ хранится только на этом устройстве.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("API-ключи Gemini", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${keys.size}/${SettingsStore.MAX_KEYS}", fontSize = 12.sp, color = c.onSurfaceVariant)
                }
                keys.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surfaceLow).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(k.masked, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Лимит: ${"%,d".format(k.limit).replace(',', ' ')} токенов в день", fontSize = 11.sp, color = c.onSurfaceVariant)
                        }
                        Box(
                            Modifier.size(44.dp).md3Clickable {
                                keys = keys.filter { it.key != k.key }
                                SettingsStore.saveGeminiKeys(ctx, keys)
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text("🗑", fontSize = 16.sp) }
                    }
                }
                SoftButton("＋ Добавить API-ключ", {
                    if (keys.size >= SettingsStore.MAX_KEYS) env.info("Максимум ключей: ${SettingsStore.MAX_KEYS}") else showKeyEditor = true
                }, Modifier.fillMaxWidth())
                SelectField(
                    "Вариант Gemini",
                    listOf("Gemini 3.6 Flash — универсальный вариант", "Gemini 2.5 Pro — глубокий анализ"),
                    geminiModel, { geminiModel = it },
                )
                LabeledInput(
                    "Инструкция для обработки", instruction, { instruction = it },
                    placeholder = "Например: извлеки название товара, количество, артикул и штрихкод…",
                    singleLine = false, minHeight = 96,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Прикреплять инструкцию к каждому файлу", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        HintText(if (attachInstruction) "Включено" else "Выключено · инструкция отправляется один раз в день")
                    }
                    Md3Switch(attachInstruction) { attachInstruction = it }
                }
                LabeledInput(
                    "Промт для «Снимок»", snapshotPrompt, { snapshotPrompt = it },
                    placeholder = "Например: извлекай только товары и количество с этой страницы документа…",
                    singleLine = false, minHeight = 96,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Прикреплять промт к PNG", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        HintText(if (attachPng) "Включено" else "Выключено · используется системная инструкция")
                    }
                    Md3Switch(attachPng) { attachPng = it }
                }
                LabeledInput(
                    "Промт для «Приём B-Ware» (накладная → данные)", bwarePrompt, { bwarePrompt = it },
                    singleLine = false, minHeight = 160,
                )
                SoftButton("Промт B-Ware: по умолчанию", {
                    bwarePrompt = SettingsStore.DEFAULT_BWARE_PROMPT
                    SettingsStore.saveBwarePrompt(ctx, "")
                    env.info("Промт B-Ware сброшен")
                }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("Сохранить", {
                        SettingsStore.putStr(ctx, "instruction", instruction.trim())
                        SettingsStore.putStr(ctx, "snapshotPrompt", snapshotPrompt.trim())
                        SettingsStore.saveBwarePrompt(ctx, bwarePrompt)
                        env.info("Параметры ИИ сохранены")
                    }, Modifier.weight(1f), filled = true)
                    SoftButton("Проверить", {
                        val k = keys.firstOrNull()
                        if (k == null) env.info("Сначала добавьте API-ключ") else probe("Gemini") {
                            SettingsStore.httpCheck("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1&key=${k.key}")
                        }
                    }, Modifier.weight(1f))
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surfaceLow)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Расход Gemini за сегодня", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    val tot = GeminiClient.totals(ctx)
                    fun fmt(n: Long) = "%,d".format(n).replace(',', ' ')
                    listOf(
                        "Вход" to fmt(tot.input), "Выход" to fmt(tot.output), "Всего" to fmt(tot.total),
                        "Лимит" to fmt(if (tot.limit > 0) tot.limit else 200000L),
                    ).forEach { (k, v) ->
                        Row {
                            Text(k, color = c.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(v, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HintText("Для безопасности не передавайте ключи другим людям. Документы отправляются по очереди доступным ключам.")
            }

            // ----- Хранилище -----
            SectionLabel("Хранилище данных")
            Md3Card {
                SettingsRow(
                    icon = "⏱️", title = "Время проверки папки автоприёма",
                    sub = "Как часто приложение ищет новые файлы в выбранной папке. В фоне, при закрытом приложении, Android разрешает проверку не чаще раза в 15 минут",
                ) {
                    SelectField(
                        "",
                        listOf("Каждую минуту", "Каждые 2 минуты", "Каждые 5 минут", "Каждые 10 минут", "Каждые 15 минут", "Каждые 30 минут", "Каждый час"),
                        autoInterval, { autoInterval = it; AutoReceiveWorker.schedule(ctx) },
                    )
                }
                RowDivider()
                SettingsRow(
                    icon = "⏳", title = "Время ожидания ответа Gemini",
                    sub = "Сколько ждать ответ на распознавание документа",
                ) {
                    SelectField(
                        "",
                        listOf("30 секунд", "1 минута", "90 секунд", "2 минуты", "3 минуты", "5 минут", "10 минут"),
                        geminiTimeout, { geminiTimeout = it },
                    )
                }
                RowDivider()
                SettingsRow(
                    icon = "⏸️", title = "Пауза между отправками в Gemini",
                    sub = "Слишком короткая пауза может вызвать лимит Gemini",
                ) {
                    SelectField(
                        "",
                        listOf("10 секунд", "20 секунд", "30 секунд", "1 минута", "2 минуты", "3 минуты", "5 минут"),
                        geminiPause, { geminiPause = it },
                    )
                }
                RowDivider()
                SettingsRow(
                    icon = "🔁", title = "Количество попыток отправки в Gemini",
                    sub = "Сколько раз повторять запрос при сбое сети или перегрузке сервера",
                ) {
                    SelectField(
                        "",
                        listOf("1 (без повторов)", "2 попытки", "3 попытки", "4 попытки", "5 попыток"),
                        geminiAttempts, { geminiAttempts = it },
                    )
                }
                RowDivider()
                SettingsRow(
                    icon = "🔄", title = "Синхронизация с Firebase", sub = "Ещё не выполнялась",
                    trailing = { SoftButton("Синхр.", { env.info("Синхронизация — в разработке") }) },
                )
                RowDivider()
                SettingsRow(
                    icon = "📁", title = "📷 Папка для фото",
                    sub = folderTitle(photoFolder)?.let { "Папка: $it" } ?: "Раздельная папка — только фото товаров",
                    onClick = { photoFolderPicker.launch(null) },
                    trailing = { Text("›", fontSize = 22.sp, color = c.onSurfaceVariant) },
                )
                RowDivider()
                SettingsRow(
                    icon = "🧾", title = "📄 Папка для накладных",
                    sub = folderTitle(docFolder)?.let { "Папка: $it" } ?: "Раздельная папка — только накладные",
                    onClick = { docFolderPicker.launch(null) },
                    trailing = { Text("›", fontSize = 22.sp, color = c.onSurfaceVariant) },
                )
                RowDivider()
                SettingsRow(
                    icon = "🧹", title = "Удалять фото старше месяца",
                    sub = if (cleanup) "Включено" else "Выключено · фото сохраняются",
                    trailing = { Md3Switch(cleanup) { cleanup = it } },
                )
            }

            // ----- Каталог -----
            SectionLabel("Каталог")
            Md3Card {
                SettingsRow(
                    icon = "🗑", title = "Стереть весь каталог", sub = "Удалить все товары из базы",
                    onClick = { eraseAsk = true },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ----- Очистка мест: выбор значения -----
    if (cdPicker.isNotEmpty()) {
        val part = cdPicker
        val options = InventoryStore.cleanupOptions(part, cdW, cdRow, cdFloor)
        val title = when (part) { "warehouse" -> "Склад"; "row" -> "Ряд"; "floor" -> "Этаж"; else -> "Полка" }
        val current = when (part) { "warehouse" -> cdW; "row" -> cdRow; "floor" -> cdFloor; else -> cdShelf }
        DialogCard(title = title, onDismiss = { cdPicker = "" }) {
            if (options.isEmpty()) {
                HintText("Нет мест с товарами")
            } else {
                options.forEach { value ->
                    Text(
                        value,
                        fontSize = 15.sp,
                        fontWeight = if (value == current) FontWeight.Bold else FontWeight.Normal,
                        color = if (value == current) c.primary else c.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .md3Clickable {
                                when (part) {
                                    "warehouse" -> { cdW = value; cdRow = "--"; cdFloor = "--"; cdShelf = "--" }
                                    "row" -> { cdRow = value; cdFloor = "--"; cdShelf = "--" }
                                    "floor" -> { cdFloor = value; cdShelf = "--" }
                                    else -> cdShelf = value
                                }
                                cdScope = part
                                val label = InvAddr(cdW, cdRow, cdFloor, cdShelf).label
                                cdStatus = "Место для очистки: $label"
                                cdStatusErr = false
                                cdPicker = ""
                            }
                            .padding(horizontal = 6.dp, vertical = 12.dp),
                    )
                    RowDivider()
                }
            }
        }
    }

    // ----- Очистка мест: подтверждение удаления -----
    if (cdConfirm) {
        val target = InvAddr(cdW, cdRow, cdFloor, cdShelf)
        val (places, items, ev) = InventoryStore.scopeStats(target, cdScope)
        CatConfirmDialog(
            title = when (cdScope) {
                "shelf" -> "Вы уверены, что хотите удалить эту полку и все товары в ней?"
                "floor" -> "Вы уверены, что хотите удалить этот этаж и все товары на нём?"
                "row" -> "Вы уверены, что хотите удалить этот ряд и все товары в нём?"
                else -> "Вы уверены, что хотите удалить этот склад и все товары в нём?"
            },
            text = "${target.label}\n$places мест · $items позиций · $ev сканирований",
            yes = "Да, удалить",
            onYes = {
                val n = InventoryStore.clearScope(ctx, target, cdScope)
                cdConfirm = false
                cdScope = ""; cdW = ""; cdRow = "--"; cdFloor = "--"; cdShelf = "--"
                cdStatus = "Удалено: $n записей"
                cdStatusErr = false
            },
            onNo = { cdConfirm = false },
        )
    }

    if (eraseAsk) {
        CatConfirmDialog(
            title = "Стереть весь каталог?",
            text = "Все товары будут удалены из каталога (${CatalogStore.items.size} шт.). История приёмок останется. Действие нельзя отменить.",
            yes = "Да, стереть",
            onYes = { CatalogStore.clear(ctx); eraseAsk = false; env.info("Каталог очищен") },
            onNo = { eraseAsk = false },
        )
    }

    if (showKeyEditor) {
        var key by remember { mutableStateOf("") }
        var limit by remember { mutableStateOf("200000") }
        DialogCard(
            title = "Добавить API-ключ",
            onDismiss = { showKeyEditor = false },
            actions = {
                DialogActionCancel("Отмена") { showKeyEditor = false }
                DialogActionConfirm("Сохранить", onClick = {
                    val k = key.trim()
                    when {
                        k.length < 20 -> env.info("Ключ слишком короткий")
                        keys.any { it.key == k } -> env.info("Такой ключ уже добавлен")
                        else -> {
                            keys = keys + GeminiKey(k, limit.toIntOrNull()?.coerceAtLeast(1000) ?: 200000)
                            SettingsStore.saveGeminiKeys(ctx, keys)
                            showKeyEditor = false
                            env.info("Ключ добавлен")
                        }
                    }
                })
            },
        ) {
            HintText("Для каждого ключа укажите отдельный дневной лимит токенов. Максимум — 10 ключей.")
            LabeledInput("API-ключ Gemini", key, { key = it }, placeholder = "AIza… или AQ.Ab…")
            LabeledInput("Дневной лимит токенов", limit, { limit = it.filter(Char::isDigit) }, keyboardType = KeyboardType.Number)
        }
    }
}
