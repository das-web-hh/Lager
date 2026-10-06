package ru.lager.app.ui.win

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================
//  Чат с Gemini (#geminiChatModal)
// =====================================================================

class ChatMsg(val role: String, val text: String, val files: List<String>, val time: Long)

/** История чата хранится на устройстве, не больше 60 последних сообщений. */
object ChatStore {
    private const val PREFS = "lager_gemini_chat"
    private const val KEY = "log"
    private const val MAX = 60
    val log = mutableStateListOf<ChatMsg>()
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = o.optJSONArray("files") ?: JSONArray()
                log.add(
                    ChatMsg(
                        o.optString("role"), o.optString("text"),
                        (0 until f.length()).map { f.optString(it) }, o.optLong("time"),
                    ),
                )
            }
        }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        log.takeLast(MAX).forEach { m ->
            arr.put(
                JSONObject().put("role", m.role).put("text", m.text).put("time", m.time)
                    .put("files", JSONArray(m.files)),
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, m: ChatMsg) {
        log.add(m)
        while (log.size > MAX) log.removeAt(0)
        save(ctx)
    }

    fun clear(ctx: Context) {
        log.clear()
        save(ctx)
    }

    /** Две последние реплики (вопрос и ответ) — контекст для запроса «что мы обсуждали». */
    fun recentHistory(): List<Pair<String, String>> {
        val l = log.filter { it.text.isNotEmpty() }.takeLast(2)
        return if (l.size == 2 && l[0].role == "user" && l[1].role == "assistant")
            listOf("user" to l[0].text, "model" to l[1].text)
        else emptyList()
    }
}

private val HISTORY_WORDS = listOf(
    "истори", "предыдущ", "контекст", "что мы обсуждали", "как я писал",
    "previous", "history", "context", "earlier", "before",
)

private fun asksForHistory(text: String): Boolean {
    val t = text.lowercase()
    return HISTORY_WORDS.any { t.contains(it) }
}

/** Минимальный разбор разметки Gemini: **жирный**, заголовки #, маркеры списка. */
private fun mdToAnnotated(src: String): AnnotatedString = buildAnnotatedString {
    val boldRe = Regex("\\*\\*(.+?)\\*\\*")
    src.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        var line = raw
        var heading = false
        Regex("^#{1,6}\\s+").find(line)?.let { line = line.removeRange(it.range); heading = true }
        line = line.replace(Regex("^(\\s*)[*-]\\s+"), "\$1• ")
        val base = if (heading) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle()
        var idx = 0
        boldRe.findAll(line).forEach { m ->
            withStyle(base) { append(line.substring(idx, m.range.first)) }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            idx = m.range.last + 1
        }
        withStyle(base) { append(line.substring(idx)) }
    }
}

private fun chatTime(t: Long): String =
    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(t))

@Composable
private fun ChatBubble(m: ChatMsg, onCopy: () -> Unit) {
    val c = Md3.c
    val mine = m.role == "user"
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 330.dp)
                .clip(shape)
                .background(if (mine) c.primaryContainer else c.card)
                .border(1.dp, c.outlineVariant, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val fg = if (mine) c.onPrimaryContainer else c.onSurface
            m.files.forEach { Text("📎 $it", fontSize = 12.sp, color = fg, fontWeight = FontWeight.SemiBold) }
            if (m.text.isNotEmpty()) {
                Text(if (mine) AnnotatedString(m.text) else mdToAnnotated(m.text), fontSize = 14.sp, lineHeight = 20.sp, color = fg)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(chatTime(m.time), fontSize = 10.sp, color = c.onSurfaceVariant)
                if (!mine) {
                    Text(
                        "Копировать", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.primary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).md3Clickable(onClick = onCopy).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun GeminiChatWindow(env: WinEnv) {
    val c = Md3.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var msg by rememberSaveable { mutableStateOf("") }
    val pending = remember { mutableStateListOf<Pair<Uri, String>>() }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusKind by remember { mutableStateOf(StatusKind.Warn) }
    var usageTick by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val log = ChatStore.log

    LaunchedEffect(Unit) { ChatStore.load(ctx) }
    LaunchedEffect(log.size, sending) {
        delay(60)
        scroll.animateScrollTo(scroll.maxValue)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val room = 5 - pending.size
        uris.filter { u -> pending.none { it.first == u } }.take(maxOf(room, 0)).forEach {
            pending.add(it to AttachmentStore.displayName(ctx, it))
        }
        if (uris.size > room) env.info("Можно прикрепить не больше 5 файлов")
    }

    fun send() {
        if (sending) return
        val text = msg.trim()
        if (text.isEmpty() && pending.isEmpty()) return
        val files = pending.toList()
        val history = if (asksForHistory(text)) ChatStore.recentHistory() else emptyList()
        msg = ""
        pending.clear()
        status = ""
        sending = true
        ChatStore.add(ctx, ChatMsg("user", text, files.map { it.second }, System.currentTimeMillis()))
        scope.launch {
            try {
                val instruction = SettingsStore.str(ctx, "instruction").trim()
                val perFile = SettingsStore.sp(ctx).getBoolean("attachInstruction", false)
                val parts = ArrayList<GeminiPart>()
                if (text.isNotEmpty()) parts.add(GeminiPart.Text(text))
                else if (instruction.isNotEmpty() && !perFile) parts.add(GeminiPart.Text(instruction))
                for ((uri, name) in files) {
                    if (perFile && instruction.isNotEmpty()) parts.add(GeminiPart.Text(instruction))
                    parts.addAll(GeminiFiles.chatParts(ctx, uri, name))
                }
                val reply = GeminiClient.generate(
                    ctx, parts, history,
                    onStatus = { status = it; statusKind = StatusKind.Warn },
                )
                ChatStore.add(ctx, ChatMsg("assistant", reply.text, emptyList(), System.currentTimeMillis()))
                status = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = e.message ?: "Не удалось получить ответ Gemini."
                statusKind = StatusKind.Err
            } finally {
                sending = false
                usageTick++
            }
        }
    }

    WindowScaffold(
        title = "Чат с Gemini",
        actions = { TopBarTextButton("Очистить") { if (log.isEmpty()) env.info("Чат уже пуст") else confirmClear = true } },
        footer = {
            pending.toList().forEach { (uri, name) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.surfaceLow)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📎 $name", fontSize = 13.sp, color = c.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(40.dp).md3Clickable { pending.removeAll { it.first == uri } },
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", fontSize = 14.sp, color = c.onSurfaceVariant) }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(c.surfaceHigh).md3Clickable {
                        if (pending.size >= 5) env.info("Можно прикрепить не больше 5 файлов") else picker.launch(arrayOf("*/*"))
                    },
                    contentAlignment = Alignment.Center,
                ) { Text("+", fontSize = 24.sp, color = c.onSurface) }
                LabeledInput("", msg, { msg = it }, Modifier.weight(1f), placeholder = "Напишите сообщение…", singleLine = false)
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (sending) c.surfaceHigh else c.primary)
                        .md3Clickable { send() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (sending) "…" else "↑", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = if (sending) c.onSurfaceVariant else c.onPrimary,
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            val tot = remember(usageTick) { GeminiClient.totals(ctx) }
            val limit = if (tot.limit > 0) tot.limit else 200_000L
            val fraction = (tot.total.toFloat() / limit).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).background(c.outlineVariant)) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(if (fraction >= 1f) c.error else if (fraction >= 0.75f) c.warning else c.primary),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                fun fmt(n: Long) = "%,d".format(n).replace(',', ' ')
                Text("Вход: ${fmt(tot.input)}", fontSize = 12.sp, color = c.onSurfaceVariant)
                Text("Выход: ${fmt(tot.output)}", fontSize = 12.sp, color = c.onSurfaceVariant)
                Text("Всего: ${fmt(tot.total)} / ${fmt(limit)}", fontSize = 12.sp, color = c.onSurfaceVariant)
            }
            if (log.isEmpty() && !sending) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text("✦", fontSize = 44.sp, color = c.primary)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Напишите вопрос Gemini или прикрепите документ для анализа.",
                            color = c.onSurfaceVariant,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    log.toList().forEach { m ->
                        ChatBubble(m, onCopy = {
                            clipboard.setText(AnnotatedString(m.text))
                            env.info("Скопировано")
                        })
                    }
                    if (sending) {
                        Text("Gemini думает…", fontSize = 13.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (status.isNotEmpty()) {
                StatusLine(status, statusKind, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }

    if (confirmClear) {
        CatConfirmDialog(
            title = "Очистить чат?",
            text = "Вся переписка с Gemini на этом устройстве будет удалена.",
            yes = "Очистить",
            onYes = { ChatStore.clear(ctx); confirmClear = false },
            onNo = { confirmClear = false },
        )
    }
}
