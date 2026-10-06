package ru.lager.app.ui.win

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Задача от администратора (документ в коллекции tasks_<uid>). */
data class WhTask(val id: String, val title: String, val description: String, val done: Boolean)

/**
 * Задачи из Firestore, как в HTML: коллекция `tasks_<uid>`, сортировка по createdAt
 * от новых к старым, не больше 50 штук. Статус «done» / «completed» = выполнена.
 */
object TaskStore {
    val tasks = mutableStateListOf<WhTask>()

    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    private fun uid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    suspend fun refresh() {
        val uid = uid()
        if (uid == null) {
            failed = true
            return
        }
        loading = true
        try {
            val snap = FirebaseFirestore.getInstance()
                .collection("tasks_$uid")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()
            val list = snap.documents.map { d ->
                val status = d.getString("status").orEmpty()
                WhTask(
                    id = d.id,
                    title = d.getString("title")?.takeIf { it.isNotBlank() }
                        ?: d.getString("name")?.takeIf { it.isNotBlank() }
                        ?: "Без названия",
                    description = d.getString("description").orEmpty(),
                    done = status == "done" || status == "completed",
                )
            }
            tasks.clear()
            tasks.addAll(list)
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    /** Отметить задачу выполненной (status = "done"). */
    suspend fun markDone(id: String): Boolean {
        val uid = uid() ?: return false
        return try {
            FirebaseFirestore.getInstance()
                .collection("tasks_$uid")
                .document(id)
                .update("status", "done")
                .await()
            val i = tasks.indexOfFirst { it.id == id }
            if (i >= 0) tasks[i] = tasks[i].copy(done = true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
