package uk.newbroman.dune

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** One shared text-to-speech engine. Speech ducks music rather than stopping it. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var warnedPolish = false
    private val pending = ArrayList<Pair<String, Locale>>()
    /** True while Dune is reading something out (the Stop button shows). */
    var talking by mutableStateOf(false)
        private set

    fun speak(ctx: Context, text: String, locale: Locale = Locale.UK, flush: Boolean = true) {
        val app = ctx.applicationContext
        if (tts == null) {
            pending += text to locale
            tts = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (!ready) {
                    tts?.shutdown(); tts = null; pending.clear()
                    app.toast("Text-to-speech isn't available")
                    return@TextToSpeech
                }
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { talking = true }
                    override fun onDone(id: String?) { if (tts?.isSpeaking != true) talking = false }
                    @Deprecated("old API") override fun onError(id: String?) { talking = false }
                    override fun onStop(id: String?, interrupted: Boolean) { talking = false }
                })
                val q = pending.toList(); pending.clear()
                q.forEachIndexed { i, (s, l) -> say(app, s, l, i == 0) }
            }
            return
        }
        if (!ready) { if (pending.size < 20) pending += text to locale; return }
        say(app, text, locale, flush)
    }

    private fun say(ctx: Context, text: String, locale: Locale, flush: Boolean) {
        val t = tts ?: return
        val r = t.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (locale.language == "pl") {
                // Reading Polish with an English voice is worse than useless: offer to install the voice instead.
                if (!warnedPolish) {
                    warnedPolish = true
                    ctx.toast("Install the Polish voice: Settings › Text-to-speech › Speech engine › Install voice data")
                    ctx.tryStart(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                }
                return
            }
            t.language = Locale.UK
        }
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "dune" + System.nanoTime())
    }

    fun stop() { tts?.stop(); pending.clear(); talking = false }
}
