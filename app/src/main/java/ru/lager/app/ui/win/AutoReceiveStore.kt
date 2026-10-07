package ru.lager.app.ui.win

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

// =====================================================================
//  Автоприём: статусы файлов и фоновая очередь (HTML: _runAutoReceiveQueueOnce)
// =====================================================================

internal enum class ArStatus { Waiting, Processing, Success, Error }

/** Файл из папки автоприёма или из «Поделиться». [key] — постоянный идентификатор (папка + путь). */
internal class ArFile(val key: String, val name: String, val path: String, val uri: Uri)

internal data class ArRecord(
    val status: ArStatus,
    val attempts: Int = 0,
    val sentAt: Long = 0L,
    val processedAt: Long = 0L,
    val error: String = "",
    val batchId: String = "",
    val sender: String = "",
    val order: String = "",
    val items: Int = 0,
    val duplicate: Boolean = false,
)

/**
 * Состояние обработки по каждому файлу. Хранится в auto_receive_state.json.
 * Как в HTML: файл уходит в Gemini не более одного раза. Ошибка не возвращается в очередь сама,
 * повтор только по нажатию на строку с ошибкой.
 */
internal object AutoReceiveStore {
    private const val FILE = "auto_receive_state.json"
    private val map = mutableStateMapOf<String, ArRecord>()
    private val io = Executors.newSingleThreadExecutor()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file(ctx)
            if (!f.exists()) return@runCatching
            val root = JSONObject(f.readText())
            var fixed = false
            root.keys().forEach { key ->
                val o = root.optJSONObject(key) ?: return@forEach
                var rec = ArRecord(
                    status = runCatching { ArStatus.valueOf(o.optString("status")) }.getOrDefault(ArStatus.Waiting),
                    attempts = o.optInt("attempts"),
                    sentAt = o.optLong("sentAt"),
                    processedAt = o.optLong("processedAt"),
                    error = o.optString("error"),
                    batchId = o.optString("batchId"),
                    sender = o.optString("sender"),
                    order = o.optString("order"),
                    items = o.optInt("items"),
                    duplicate = o.optBoolean("duplicate"),
                )
                // Приложение закрыли посреди запроса: запрос мог дойти до Gemini, поэтому
                // сами не повторяем, а показываем ошибку (как «потерянный ответ» в HTML).
                if (rec.status == ArStatus.Processing) {
                    rec = rec.copy(
                        status = ArStatus.Error,
                        error = "Приложение закрылось во время обработки. Нажмите, чтобы повторить.",
                    )
                    fixed = true
                }
                map[key] = rec
            }
            if (fixed) save(ctx)
        }
    }

    fun get(key: String): ArRecord? = map[key]

    fun status(key: String): ArStatus = map[key]?.status ?: ArStatus.Waiting

    fun put(ctx: Context, key: String, rec: ArRecord) {
        map[key] = rec
        save(ctx)
    }

    /** Повтор: статус «Ожидает», счётчик попыток сохраняется. */
    fun retry(ctx: Context, key: String) {
        val old = map[key] ?: return
        put(ctx, key, old.copy(status = ArStatus.Waiting, error = ""))
    }

    private fun save(ctx: Context) {
        val snapshot = map.toMap()
        val f = file(ctx)
        io.execute {
            runCatching {
                val root = JSONObject()
                snapshot.forEach { (k, r) ->
                    root.put(
                        k,
                        JSONObject()
                            .put("status", r.status.name)
                            .put("attempts", r.attempts)
                            .put("sentAt", r.sentAt)
                            .put("processedAt", r.processedAt)
                            .put("error", r.error)
                            .put("batchId", r.batchId)
                            .put("sender", r.sender)
                            .put("order", r.order)
                            .put("items", r.items)
                            .put("duplicate", r.duplicate),
                    )
                }
                f.writeText(root.toString())
            }
        }
    }
}

/**
 * Очередь автоприёма: файлы по одному → Gemini → запись в историю приёмок.
 * Работает в собственном scope, поэтому не прерывается, если закрыть окно (пока жив процесс).
 * Паузу между отправками выдерживает сам GeminiClient (настройка «Пауза между отправками»).
 */
internal object AutoReceiveRunner {
    /** Сообщение над списком: ход обработки или причина остановки. */
    val notice = mutableStateOf("")
    val noticeError = mutableStateOf(false)
    val running = mutableStateOf(false)

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun say(text: String, error: Boolean = false) {
        notice.value = text
        noticeError.value = error
    }

    /** Запускает очередь, если она ещё не идёт. [files] читается заново перед каждым файлом. */
    fun start(ctx: Context, files: () -> List<ArFile>) {
        val app = ctx.applicationContext
        scope.launch {
            if (!mutex.tryLock()) return@launch
            running.value = true
            try {
                AutoReceiveStore.load(app)
                loop(app, files)
            } finally {
                running.value = false
                mutex.unlock()
            }
        }
    }

    private suspend fun loop(ctx: Context, files: () -> List<ArFile>) {
        say("")
        while (true) {
            val next = files().firstOrNull { AutoReceiveStore.status(it.key) == ArStatus.Waiting } ?: break

            val keys = SettingsStore.geminiKeys(ctx)
            if (keys.isEmpty()) {
                say("Автоприём остановлен: в настройках Gemini не указан API-ключ.", true)
                return
            }
            if (keys.all { GeminiClient.keyUsage(ctx, it.key) >= it.limit }) {
                say("Все API-ключи Gemini исчерпали заданные лимиты токенов.", true)
                return
            }
            process(ctx, next)
        }
        say("")
    }

    private suspend fun process(ctx: Context, f: ArFile) {
        val attempts = (AutoReceiveStore.get(f.key)?.attempts ?: 0) + 1
        val sentAt = System.currentTimeMillis()
        AutoReceiveStore.put(ctx, f.key, ArRecord(ArStatus.Processing, attempts = attempts, sentAt = sentAt))
        say("Обрабатывается «${f.name}»…")
        try {
            val inv = NrInvoice.recognize(ctx, f.uri, f.name) { say(it) }
            CatalogStore.load(ctx)
            val rec = commit(ctx, f, inv).copy(attempts = attempts, sentAt = sentAt)
            AutoReceiveStore.put(ctx, f.key, rec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AutoReceiveStore.put(
                ctx, f.key,
                ArRecord(
                    ArStatus.Error,
                    attempts = attempts,
                    sentAt = sentAt,
                    error = e.message ?: "Gemini не ответил. Автоматический повтор отключён.",
                ),
            )
        }
    }

    /** _saveAutoReceiveBatch: позиции → история приёмок и каталог, документ → вложение партии. */
    private suspend fun commit(ctx: Context, f: ArFile, inv: NrInvoiceResult): ArRecord {
        val now = System.currentTimeMillis()

        // Заказ уже есть в истории: документ прикрепляем к нему, позиции не дублируем.
        if (inv.order.isNotEmpty()) {
            val existing = ArrivalStore.items.firstOrNull {
                it.order.equals(inv.order, ignoreCase = true) && it.batchId.isNotEmpty()
            }
            if (existing != null) {
                withContext(Dispatchers.IO) {
                    AttachmentStore.save(ctx, existing.batchId, emptyList(), listOf(f.uri))
                }
                return ArRecord(
                    ArStatus.Success, processedAt = now, batchId = existing.batchId,
                    sender = inv.sender, order = inv.order, duplicate = true,
                )
            }
        }

        if (inv.items.isEmpty()) {
            return ArRecord(ArStatus.Error, error = "Gemini не нашёл товаров в документе.")
        }
        val rows = inv.items.filter { it.second > 0 }
        if (rows.isEmpty()) {
            return ArRecord(ArStatus.Error, error = "В документе у всех позиций количество 0.")
        }

        val batchId = "auto_" + now.toString(36)
        // Дата берётся из имени файла (Сканирование_20260721-1149.pdf), иначе сегодняшняя.
        val date = dateFromName(f.name)
            ?: java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.US).format(java.util.Date())
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        ArrivalStore.addAll(
            ctx,
            rows.map { (name, qty, _) ->
                Arrival(
                    id = java.util.UUID.randomUUID().toString().take(12),
                    batchId = batchId, date = date, name = name, menge = qty,
                    sender = inv.sender.trim(), order = inv.order.trim(),
                    source = f.name, receivedAt = stamp,
                )
            },
        )
        rows.forEach { CatalogStore.ensure(ctx, it.first, it.third) }
        withContext(Dispatchers.IO) {
            AttachmentStore.save(ctx, batchId, emptyList(), listOf(f.uri))
        }
        return ArRecord(
            ArStatus.Success, processedAt = now, batchId = batchId,
            sender = inv.sender, order = inv.order, items = rows.size,
        )
    }

    /** _autoReceiveDateFromFileName: YYYYMMDD или YYMMDD в имени → «дд.мм.гг». */
    private fun dateFromName(name: String): String? {
        val full = Regex("(?:^|[^0-9])((?:19|20)\\d{6})(?!\\d)").find(name)?.groupValues?.get(1)
        val short = if (full == null) Regex("(?:^|[^0-9])(\\d{6})(?!\\d)").find(name)?.groupValues?.get(1) else null
        val d = full ?: short ?: return null
        val y: Int
        val m: Int
        val day: Int
        if (full != null) {
            y = d.substring(0, 4).toInt(); m = d.substring(4, 6).toInt(); day = d.substring(6, 8).toInt()
        } else {
            y = 2000 + d.substring(0, 2).toInt(); m = d.substring(2, 4).toInt(); day = d.substring(4, 6).toInt()
        }
        return runCatching {
            val cal = java.util.Calendar.getInstance()
            cal.isLenient = false
            cal.clear()
            cal.set(y, m - 1, day)
            cal.timeInMillis // бросит исключение на несуществующей дате
            "%02d.%02d.%02d".format(day, m, y % 100)
        }.getOrNull()
    }
}
