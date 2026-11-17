package uk.newbroman.dune

import android.content.Context
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * English ⇄ Polish on the phone (Google ML Kit). The first translation each way downloads its model
 * (~30 MB, once); after that it works offline. The Polish is always said aloud – hearing it is the
 * point – and the English is said too when the question was spoken.
 */
object Translation {
    fun code(lang: String) = if (lang.startsWith("pol")) "pl" else "en"

    private val polishWords = setOf("jest", "nie", "tak", "dziękuję", "dziekuje", "proszę", "prosze", "dzień", "dzien", "dobry", "dobrze",
        "cześć", "czesc", "jak", "się", "sie", "masz", "co", "to", "gdzie", "ile", "kocham", "ciebie", "mam", "jestem", "przepraszam",
        "dobranoc", "smacznego", "na", "zdrowie", "kiedy", "dlaczego", "bardzo", "piwo", "woda", "chleb", "rachunek", "poproszę")

    /** Which way to go when the person didn't say: Polish letters or common Polish words mean it's Polish. */
    fun guessTo(text: String): String {
        val t = text.lowercase()
        if (Regex("[ąćęłńóśźż]").containsMatchIn(t)) return "en"
        val words = t.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        return if (words.isNotEmpty() && words.count { it in polishWords } * 2 >= words.size) "en" else "pl"
    }

    private val clients = HashMap<String, Translator>()
    private fun client(to: String): Translator = clients.getOrPut(to) {
        val o = TranslatorOptions.Builder()
            .setSourceLanguage(if (to == "pl") TranslateLanguage.ENGLISH else TranslateLanguage.POLISH)
            .setTargetLanguage(if (to == "pl") TranslateLanguage.POLISH else TranslateLanguage.ENGLISH).build()
        com.google.mlkit.nl.translate.Translation.getClient(o)
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
    }

    @Volatile private var ready = HashSet<String>()

    suspend fun translate(text: String, to: String): String {
        val tr = client(to)
        if (to !in ready) { tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await(); ready += to }
        return tr.translate(text).await()
    }

    fun run(act: MainActivity, vm: LauncherVM, cmd: Cmd.Translate): String? {
        if (cmd.text.isBlank()) {
            // "translate from Polish": listen in Polish (or English), then translate
            val to = cmd.to ?: "pl"
            val heard = if (to == "en") "pl-PL" else "en-GB"
            act.listen(if (to == "en") "Powiedz po polsku…" else "Say it in English…", heard) { said ->
                if (!said.isNullOrBlank()) act.runCmd(Cmd.Translate(said, to))
            }
            return null
        }
        val to = cmd.to ?: guessTo(cmd.text)
        val first = to !in ready
        val spoken = act.lastAskSpoken
        act.lifecycleScope.launch {
            val r = runCatching { translate(cmd.text, to) }
            val reply = r.fold(
                onSuccess = { out ->
                    if (to == "pl") Speaker.speak(act, out, PolishTime.PL)
                    else { if (spoken) Speaker.speak(act, out) }
                    "“${cmd.text}” in ${if (to == "pl") "Polish" else "English"}: $out"
                },
                onFailure = {
                    android.util.Log.w("Dune", "translation failed", it)
                    Actions.translate(act, cmd.text)
                    if (first) "The translation model couldn't download (${it.javaClass.simpleName}) – opening Google Translate instead"
                    else "Couldn't translate that – opening Google Translate"
                })
            vm.assistantReply = reply
        }
        return if (first) "Translating – the first time downloads the ${if (to == "pl") "English→Polish" else "Polish→English"} model (about 30 MB)…"
        else "Translating…"
    }
}
