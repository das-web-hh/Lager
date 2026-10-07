package ru.lager.app.ui.win

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.Executors

/**
 * Android «Поделиться» (Share Target). Файлы копируются в filesDir/share_target/<номер>/<имя>,
 * потому что доступ к чужому Uri действует только пока жива активность.
 */
object ShareState {
    private const val DIR = "share_target"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Файлы, ждущие выбора режима (диалог «Файлы получены»). */
    var pending by mutableStateOf<List<Uri>>(emptyList())

    /** Файлы, отправленные в «Приём по имени»; окно забирает их и очищает. */
    var nameQueue by mutableStateOf<List<Uri>>(emptyList())

    /** Растёт при каждом новом наборе файлов — список автоприёма обновляется. */
    var version by mutableStateOf(0)

    fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, DIR)

    /** Файлы, уже лежащие в очереди автоприёма. */
    fun saved(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?.flatMap { d -> d.listFiles()?.filter { it.isFile }.orEmpty() }.orEmpty()

    @Suppress("DEPRECATION")
    private fun sources(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) out.add(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (list != null) out.addAll(list)
            }
            else -> return emptyList()
        }
        val clip: ClipData? = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val u = clip.getItemAt(i).uri
                if (u != null) out.add(u)
            }
        }
        return out.toList()
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        val n = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
        return n.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_").ifBlank { "file" }
    }

    /** Разбирает intent «Поделиться». Вызывать из onCreate / onNewIntent. */
    fun handle(ctx: Context, intent: Intent?) {
        if (intent == null) return
        val src = sources(intent)
        if (src.isEmpty()) return
        val app = ctx.applicationContext
        io.execute {
            val stamp = System.currentTimeMillis()
            val copied = ArrayList<Uri>()
            src.forEachIndexed { i, u ->
                runCatching {
                    val name = displayName(app, u)
                    val d = File(dir(app), "$stamp-$i").apply { mkdirs() }
                    val f = File(d, name)
                    val bytes = app.contentResolver.openInputStream(u)?.use { input -> f.outputStream().use { out -> input.copyTo(out) } } ?: 0L
                    if (bytes > 0L) copied.add(Uri.fromFile(f)) else d.deleteRecursively()
                }
            }
            main.post {
                if (copied.isNotEmpty()) {
                    pending = copied
                    version += 1
                }
            }
        }
    }
}

/** Диалог «Файлы получены» (#shareRouteOverlay). */
@Composable
fun ShareRouteHost(env: WinEnv) {
    val files = ShareState.pending
    if (files.isEmpty()) return
    val close = { ShareState.pending = emptyList() }
    DialogCard(
        title = "Файлы получены",
        onDismiss = close,
        actions = { DialogActionCancel("Оставить в очереди", close) },
    ) {
        Text(
            "Получено файлов: ${files.size}. Выберите режим обработки документов.",
            fontSize = 14.sp, color = Md3.c.onSurfaceVariant,
        )
        LongButton("⚡ Автоприём — в список файлов", LongKind.Blue, {
            close()
            env.nav.push(Win.ReceiveAuto)
        }, Modifier.fillMaxWidth())
        SoftButton("📋 Приём по имени — распознать накладную", {
            ShareState.nameQueue = files
            close()
            env.nav.push(Win.ReceiveName)
        }, Modifier.fillMaxWidth())
    }
}
