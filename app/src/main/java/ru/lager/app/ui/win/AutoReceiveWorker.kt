package ru.lager.app.ui.win

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Проверка папки автоприёма в фоне, когда приложение закрыто (HTML: таймер автоприёма на странице).
 *
 * Берёт ту же папку и те же «Поделиться»-файлы, что окно «Автоприём», и гонит новые файлы через
 * [AutoReceiveRunner]. Статусы общие (AutoReceiveStore), поэтому файл не уйдёт в Gemini дважды,
 * даже если окно и воркер встретятся. Если очередь уже идёт (окно открыто), воркер пропускает запуск.
 */
class AutoReceiveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        // Без ключа Gemini делать нечего.
        if (SettingsStore.geminiKeys(app).isEmpty()) return Result.success()

        val files = withContext(Dispatchers.IO) { collect(app) }
        if (files.isEmpty()) return Result.success()

        AutoReceiveStore.load(app)
        if (files.none { AutoReceiveStore.status(it.key) == ArStatus.Waiting }) return Result.success()

        AutoReceiveRunner.runOnce(app, files, MAX_PER_RUN)
        // Что не успели, подхватит следующий запуск (статус «Ожидает» сохраняется).
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "lager_auto_receive"

        /** Не больше файлов за один запуск: у воркера около 10 минут, а между отправками есть пауза. */
        private const val MAX_PER_RUN = 5

        /** Интервалы из настройки «Время проверки папки автоприёма», минуты. */
        private val INTERVALS = listOf(1, 2, 5, 10, 15, 30, 60)

        /** Android не запускает периодическую работу чаще, чем раз в 15 минут. */
        private const val MIN_PERIOD = 15L

        /** Ставит (или обновляет) периодическую проверку. Вызывать при старте и при смене интервала. */
        fun schedule(ctx: Context) {
            val app = ctx.applicationContext
            val idx = SettingsStore.sp(app).getInt("autoInterval", 4)
            val minutes = INTERVALS.getOrElse(idx) { 15 }.toLong()
            val request = PeriodicWorkRequestBuilder<AutoReceiveWorker>(maxOf(MIN_PERIOD, minutes), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(app)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        // ---- список файлов: те же ключи, что в WindowsReceive.kt (arListFolder и блок «Share Target») ----

        private fun allowed(name: String, mime: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "pdf" || ext == "png" || ext == "jpg" || ext == "jpeg") return true
            val m = mime.lowercase()
            return m == "application/pdf" || m == "image/png" || m == "image/jpeg"
        }

        private fun folderName(tree: Uri): String =
            runCatching {
                val id = DocumentsContract.getTreeDocumentId(tree)
                id.substringAfterLast(':').substringAfterLast('/').ifEmpty { id }
            }.getOrDefault("")

        private fun listFolder(ctx: Context, tree: Uri): List<ArFile> {
            val out = ArrayList<ArFile>()
            val folder = folderName(tree)
            fun walk(docId: String, path: String, depth: Int) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val cols = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                runCatching {
                    ctx.contentResolver.query(children, cols, null, null, null)?.use { cur ->
                        while (cur.moveToNext()) {
                            val id = cur.getString(0) ?: continue
                            val name = cur.getString(1) ?: continue
                            val mime = cur.getString(2) ?: ""
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (depth < 4) walk(id, "$path$name/", depth + 1)
                            } else if (allowed(name, mime)) {
                                out.add(
                                    ArFile(
                                        "$folder::${path + name}", name, path + name,
                                        DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
            return out.sortedBy { it.path.lowercase() }
        }

        private fun collect(ctx: Context): List<ArFile> {
            val prefs = ctx.getSharedPreferences("lager_auto_receive", Context.MODE_PRIVATE)
            val tree = prefs.getString("tree", null)?.let { Uri.parse(it) }
            val shared = ShareState.saved(ctx).filter { allowed(it.name, "") }.map {
                ArFile("share::${it.parentFile?.name}/${it.name}", it.name, "Share Target/" + it.name, Uri.fromFile(it))
            }
            return (if (tree != null) listFolder(ctx, tree) else emptyList()) + shared
        }
    }
}
