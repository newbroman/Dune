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

    /** A translation and where it came from ("on the phone", "Google", "MyMemory"). */
    data class Result(val text: String, val via: String)

    /** Why the on-phone model last failed, shown once so a broken download isn't silent. */
    @Volatile var phoneError: String? = null

    /**
     * Translate into [to] ("pl"/"en"), from the other one: the on-phone model first, then Google's free
     * web endpoint, then MyMemory. Throws only if all three fail.
     */
    suspend fun translateAny(text: String, to: String): Result {
        val from = if (to == "pl") "en" else "pl"
        // a model already on the phone answers in well under a second. One that is still downloading (the first
        // Polish→English, ~30 MB) mustn't hold the answer up: the download carries on by itself, and this time
        // the web answers instead.
        val phone = runCatching { kotlinx.coroutines.withTimeout(if (to in ready) 15_000 else 4_000) { translate(text, to) } }
        phone.getOrNull()?.let { phoneError = null; return Result(it, "on the phone") }
        phoneError = phone.exceptionOrNull()?.let { it.message?.take(80) ?: it.javaClass.simpleName }
        android.util.Log.w("Dune", "on-phone translation failed: $phoneError")
        val q = java.net.URLEncoder.encode(text, "UTF-8")
        val google = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val a = org.json.JSONArray(get("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=$q")).getJSONArray(0)
            (0 until a.length()).joinToString("") { a.getJSONArray(it).optString(0) }
        } }
        google.getOrNull()?.takeIf { it.isNotBlank() }?.let { return Result(it, "Google") }
        val mm = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val j = org.json.JSONObject(get("https://api.mymemory.translated.net/get?langpair=$from%7C$to&q=$q"))
            if (j.optInt("responseStatus") != 200) throw IllegalStateException(j.optString("responseDetails", "MyMemory refused"))
            android.text.Html.fromHtml(j.getJSONObject("responseData").getString("translatedText"), 0).toString()
        }
        if (mm.isNotBlank()) return Result(mm, "MyMemory")
        // no web either: wait for the on-phone model after all
        return Result(kotlinx.coroutines.withTimeout(90_000) { translate(text, to) }, "on the phone")
    }

    /** Google Translate (app if it takes the link, otherwise the website) with the direction set. */
    fun openGoogle(ctx: Context, text: String, to: String) {
        val from = if (to == "pl") "en" else "pl"
        // the Translate app takes the text itself (it works out the language); the website link only sets the direction
        if (text.isNotBlank() && ctx.packageManager.getLaunchIntentForPackage(TRANSLATE) != null &&
            ctx.tryStart(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, text).setPackage(TRANSLATE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))) return
        ctx.tryStart(android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://translate.google.com/?sl=$from&tl=$to&op=translate&text=" + android.net.Uri.encode(text)))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
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
            val r = runCatching { translateAny(cmd.text, to) }
            val reply = r.fold(
                onSuccess = { res -> val out = res.text
                    if (to == "pl") Speaker.speak(act, out, PolishTime.PL)
                    else { if (spoken) Speaker.speak(act, out) }
                    "“${cmd.text}” in ${if (to == "pl") "Polish" else "English"}: $out" + if (res.via != "on the phone") "  (via ${res.via})" else ""
                },
                onFailure = {
                    android.util.Log.w("Dune", "translation failed", it)
                    openGoogle(act, cmd.text, to)
                    "Couldn't translate that here (${phoneError ?: it.javaClass.simpleName}) – opening Google Translate, ${if (to == "pl") "English → Polish" else "Polish → English"}"
                })
            vm.assistantReply = reply
        }
        return if (first) "Translating – the first time downloads the ${if (to == "pl") "English→Polish" else "Polish→English"} model (about 30 MB)…"
        else "Translating…"
    }
}
