package ru.lager.app.auth

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.lager.app.FirebaseConfig
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

class EmailSendException(val httpCode: Int, message: String) : Exception(message)

data class ProfileData(
    val firstName: String,
    val lastName: String,
    val email: String,
    val uid: String,
)

class AuthRepository(context: Context) {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val prefs =
        context.applicationContext.getSharedPreferences("lager_auth", Context.MODE_PRIVATE)

    val isSignedIn: Boolean get() = auth.currentUser != null

    var cachedRole: Role
        get() = runCatching { Role.valueOf(prefs.getString(KEY_ROLE, null) ?: "USER") }
            .getOrDefault(Role.USER)
        set(value) {
            prefs.edit().putString(KEY_ROLE, value.name).apply()
        }

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BIO, value).apply()
        }

    var biometricAsked: Boolean
        get() = prefs.getBoolean(KEY_BIO_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BIO_ASKED, value).apply()
        }

    fun signOut() {
        auth.signOut()
        prefs.edit().clear().apply()
    }

    // ---- Вход (как handleLogin в index.html) ----
    suspend fun login(input: String, password: String): Role {
        var email = input.trim().lowercase()
        if (email == "admin") email = FirebaseConfig.ADMIN_EMAIL
        val user = auth.signInWithEmailAndPassword(email, password.trim()).await().user
            ?: error("no user")
        return resolveRole(user.uid, user.email ?: email)
    }

    suspend fun currentRole(): Role {
        val user = auth.currentUser ?: return Role.USER
        return resolveRole(user.uid, user.email)
    }

    private suspend fun resolveRole(uid: String, email: String?): Role {
        val isAdminEmail = email.equals(FirebaseConfig.ADMIN_EMAIL, ignoreCase = true)
        val role = try {
            when (db.collection("users").document(uid).get().await().getString("role")) {
                "admin" -> Role.ADMIN
                "observer" -> Role.OBSERVER
                else -> Role.USER
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            cachedRole
        }
        val result = if (isAdminEmail) Role.ADMIN else role
        cachedRole = result
        return result
    }

    suspend fun loadProfile(): ProfileData {
        val user = auth.currentUser ?: return ProfileData("—", "—", "—", "—")
        var first = ""
        var last = ""
        try {
            val doc = db.collection("users").document(user.uid).get().await()
            first = doc.getString("firstName").orEmpty()
            last = doc.getString("lastName").orEmpty()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        return ProfileData(
            firstName = first.ifBlank { "—" },
            lastName = last.ifBlank { "—" },
            email = user.email.orEmpty().ifBlank { "—" },
            uid = user.uid,
        )
    }

    // ---- Регистрация ----
    suspend fun emailExists(email: String): Boolean =
        !db.collection("users").whereEqualTo("email", email).limit(1).get().await().isEmpty

    /** Генерирует 6-значный код и отправляет его через EmailJS. */
    suspend fun sendCode(email: String): String {
        val code = (SecureRandom().nextInt(900000) + 100000).toString()
        sendEmailJs(email, code)
        return code
    }

    suspend fun register(
        email: String,
        firstName: String,
        lastName: String,
        password: String,
        observer: Boolean,
    ): Role {
        val user = auth.createUserWithEmailAndPassword(email, password.trim()).await().user
            ?: error("no user")
        val role = if (observer) Role.OBSERVER else Role.USER
        db.collection("users").document(user.uid).set(
            mapOf(
                "firstName" to firstName,
                "lastName" to lastName,
                "fullName" to "$firstName $lastName",
                "email" to email,
                "role" to if (observer) "observer" else "user",
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        cachedRole = role
        return role
    }

    // ---- Сброс пароля ----
    suspend fun sendResetLink(email: String) {
        auth.sendPasswordResetEmail(email).await()
    }

    // ---- EmailJS (REST API) ----
    private suspend fun sendEmailJs(email: String, code: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("service_id", FirebaseConfig.EMAILJS_SERVICE)
            put("template_id", FirebaseConfig.EMAILJS_TEMPLATE)
            put("user_id", FirebaseConfig.EMAILJS_PUBLIC_KEY)
            put(
                "template_params",
                JSONObject().apply {
                    put("to_email", email)
                    put("email", email)
                    put("passcode", code)
                    put("code", code)
                },
            )
        }.toString()

        val conn = URL("https://api.emailjs.com/api/v1.0/email/send")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val httpCode = conn.responseCode
            if (httpCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw EmailSendException(httpCode, err)
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val KEY_ROLE = "role"
        const val KEY_BIO = "biometric"
        const val KEY_BIO_ASKED = "biometric_asked"
    }
}
