package ru.lager.app.ui.win

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Озвучка добавленного количества (warehouseVoiceSpeak / warehouseVoiceAnnounce в warehouse.html).
 * Настройки берутся из тех же значений, что и в окне «Настройки».
 */
object Speech {
    class Settings(
        val enabled: Boolean,
        val voiceName: String,
        val rate: Float,
        val pitch: Float,
        val volume: Float,
        val phrase: String,
        val speakTaps: Boolean,
        val showSec: Int,
        val autoSec: Int,
    )

    /** Варианты списков в настройках (по индексу). */
    val SHOW_SEC = listOf(1, 2, 3, 4, 5, 7, 10)
    val AUTO_SEC = listOf(0, 3, 5, 8, 10, 15)

    fun settings(ctx: Context): Settings {
        val sp = SettingsStore.sp(ctx)
        return Settings(
            enabled = sp.getInt("voiceOn", 0) == 0,
            voiceName = sp.getString("voiceName", "") ?: "",
            rate = sp.getFloat("voiceRate", 1f),
            pitch = sp.getFloat("voicePitch", 1f),
            volume = sp.getFloat("voiceVolume", 1f),
            phrase = SettingsStore.str(ctx, "voicePhrase").trim().take(40),
            speakTaps = sp.getInt("speakTaps", 0) == 0,
            showSec = SHOW_SEC.getOrElse(sp.getInt("showSec", 2)) { 3 },
            autoSec = AUTO_SEC.getOrElse(sp.getInt("autoSec", 2)) { 5 },
        )
    }

    fun langTag(code: String) = when (code) {
        "de" -> "de-DE"
        "en" -> "en-US"
        else -> "ru-RU"
    }

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val queue = ArrayList<(TextToSpeech) -> Unit>()

    /** Запускает движок (если ещё не запущен) и выполняет [run], когда он готов. */
    fun ensure(ctx: Context, run: ((TextToSpeech) -> Unit)? = null) {
        val cur = tts
        if (cur != null && ready) {
            run?.invoke(cur)
            return
        }
        if (run != null) synchronized(queue) { queue.add(run) }
        if (cur != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                ready = true
                val jobs = synchronized(queue) { val l = ArrayList(queue); queue.clear(); l }
                jobs.forEach { runCatching { it(t) } }
            } else {
                ready = false
                tts = null
                synchronized(queue) { queue.clear() }
            }
        }
    }

    /** Голоса движка: имя и языковой тег. Пусто, пока движок не готов. */
    fun voices(): List<Pair<String, String>> {
        val t = tts
        if (t == null || !ready) return emptyList()
        return runCatching {
            t.voices.orEmpty().map { it.name to it.locale.toLanguageTag() }.sortedBy { it.first }
        }.getOrDefault(emptyList())
    }

    fun speak(ctx: Context, text: String, langTag: String, force: Boolean = false) {
        val s = settings(ctx)
        if ((!s.enabled && !force) || text.isBlank()) return
        ensure(ctx) { t ->
            runCatching {
                val voice = if (s.voiceName.isNotEmpty()) t.voices?.firstOrNull { it.name == s.voiceName } else null
                if (voice != null) t.voice = voice else t.language = Locale.forLanguageTag(langTag)
                t.setPitch(s.pitch)
                t.setSpeechRate(s.rate)
                val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, s.volume.coerceIn(0f, 1f)) }
                t.speak(text, TextToSpeech.QUEUE_FLUSH, b, "lager_voice")
            }
        }
    }

    /** source: "tap" — серия нажатий, "wheel" — добавленное количество. */
    fun announce(ctx: Context, count: Int, source: String, langTag: String) {
        val s = settings(ctx)
        if (!s.enabled || count <= 0) return
        if (source == "tap" && !s.speakTaps) return
        speak(ctx, (if (s.phrase.isNotEmpty()) s.phrase + " " else "") + count, langTag)
    }
}
