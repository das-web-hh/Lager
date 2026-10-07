package ru.lager.app.ui.win

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import ru.lager.app.auth.ProfileData
import java.util.Locale

/**
 * Новый товар из приёмки → онлайн-каталог на проверку (__warehouseSaveToCollection('new_products') в HTML).
 * Документ new_products/new_<штрихкод|артикул|название> со status = "pending".
 * Идентификатор строится так же, как в веб-версии, поэтому повторная отправка обновляет ту же запись.
 */
object NewProductSync {
    private const val TAG = "NewProductSync"

    /** Как encodeURIComponent в JavaScript. */
    private fun encodeUriComponent(s: String): String {
        val keep = "-_.!~*'()"
        val sb = StringBuilder()
        s.toByteArray(Charsets.UTF_8).forEach { b ->
            val ch = (b.toInt() and 0xFF).toChar()
            if ((ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in keep) && b >= 0) {
                sb.append(ch)
            } else {
                sb.append('%').append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    fun documentId(barcode: String, article: String, name: String): String {
        val stable = barcode.ifEmpty { article }.ifEmpty { name.lowercase(Locale("ru", "RU")) }
        return ("new_" + encodeUriComponent(stable)).replace('%', '_').take(420)
    }

    /** Отправляет товар без ожидания: Firestore сам доставит запись, когда появится сеть. */
    fun submit(profile: ProfileData?, name: String, barcode: String, article: String = "") {
        val n = name.trim()
        val bc = barcode.trim()
        val art = article.trim()
        if (n.isEmpty() || (bc.isEmpty() && art.isEmpty() && n.isEmpty())) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val first = profile?.firstName?.takeIf { it != "—" }.orEmpty().trim()
        val last = profile?.lastName?.takeIf { it != "—" }.orEmpty().trim()
        val email = (profile?.email?.takeIf { it != "—" } ?: user.email).orEmpty().trim()
        val userName = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { email }
        val now = System.currentTimeMillis()
        val data = hashMapOf<String, Any>(
            "name" to n,
            "article" to art,
            "barcode" to bc,
            "ean" to bc,
            "category" to "",
            "unit" to "",
            "status" to "pending",
            "source" to "warehouse",
            "createdAt" to now,
            "uid" to user.uid,
            "savedAt" to now,
            "userId" to user.uid,
            "userName" to userName,
            "firstName" to first,
            "lastName" to last,
            "userEmail" to email,
            "windowId" to "android",
            "windowName" to "Lager Android",
        )
        runCatching {
            FirebaseFirestore.getInstance()
                .collection("new_products")
                .document(documentId(bc, art, n))
                .set(data, SetOptions.merge())
                .addOnFailureListener { Log.w(TAG, "Не удалось отправить новый товар: ${it.message}") }
        }.onFailure { Log.w(TAG, "Не удалось отправить новый товар: ${it.message}") }
    }
}
