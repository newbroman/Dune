package uk.newbroman.dune

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A string kept encrypted with a key that never leaves the phone's Android Keystore. */
object Secure {
    private fun secret(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun put(ctx: Context, name: String, value: String?) {
        val p = ctx.getSharedPreferences("dune-secure", Context.MODE_PRIVATE)
        if (value.isNullOrBlank()) { p.edit().remove(name).apply(); return }
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret("dune-$name")) }
            p.edit().putString(name, Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP)).commit()   // (written at once: a restore restarts Dune straight after)
        }
    }

    fun get(ctx: Context, name: String): String? = runCatching {
        val s = ctx.getSharedPreferences("dune-secure", Context.MODE_PRIVATE).getString(name, null) ?: return null
        val blob = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret("dune-$name"), GCMParameterSpec(128, blob, 0, 12)) }
        String(c.doFinal(blob, 12, blob.size - 12))
    }.getOrNull()
}

/**
 * A rolling log of the messages and emails that arrive, kept only on the phone, so the inbox
 * page and the AI can look back after a notification has been cleared. Last 7 days, at most 600.
 */
object InboxLog {
    data class Entry(val time: Long, val pkg: String, val app: String, val title: String, val text: String, val kind: String)

    private const val KEEP_MS = 7L * 24 * 3600 * 1000
    private const val MAX = 600
    @Volatile private var cache: MutableList<Entry>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "inbox-log.json")

    @Synchronized fun all(ctx: Context): List<Entry> {
        cache?.let { return it.toList() }
        val l = runCatching {
            val a = JSONArray(String(android.util.AtomicFile(file(ctx)).readFully()))
            (0 until a.length()).map { i -> a.getJSONObject(i).let {
                Entry(it.getLong("t"), it.getString("p"), it.optString("a"), it.optString("ti"), it.optString("x"), it.optString("k"))
            } }
        }.getOrDefault(emptyList()).toMutableList()
        cache = l
        return l.toList()
    }

    @Synchronized fun add(ctx: Context, e: Entry) {
        if (e.text.isBlank() && e.title.isBlank()) return
        val l = all(ctx).toMutableList()
        // the same message re-posted (an app updating its notification) is stored once
        if (l.takeLast(40).any { it.pkg == e.pkg && it.title == e.title && it.text == e.text && kotlin.math.abs(it.time - e.time) < 10 * 60_000 }) return
        l += e
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val kept = l.filter { it.time >= cutoff }.takeLast(MAX).toMutableList()
        cache = kept
        val snapshot = kept.toList()
        writer.execute {
            val a = JSONArray()
            snapshot.forEach { a.put(JSONObject().put("t", it.time).put("p", it.pkg).put("a", it.app).put("ti", it.title).put("x", it.text).put("k", it.kind)) }
            val f = android.util.AtomicFile(file(ctx))   // a crash mid-write can't wipe the log
            runCatching {
                val out = f.startWrite()
                try { out.write(a.toString().toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out) }
            }
        }
    }
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Synchronized fun clear(ctx: Context) { cache = mutableListOf(); writer.execute { android.util.AtomicFile(file(ctx)).delete() } }
}

/** What sort of thing a notification is, for the inbox filters. */
object Kind {
    private val EMAIL = setOf("com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
        "ch.protonmail.android", "me.proton.android.mail", "com.fsck.k9", "net.thunderbird.android", "com.samsung.android.email.provider")
    fun of(pkg: String, category: String?, isMessage: Boolean): String = when {
        pkg in EMAIL || category == android.app.Notification.CATEGORY_EMAIL -> "email"
        isMessage -> "message"
        else -> "other"
    }
}

/**
 * Dune's AI: answers questions about your messages, tasks and calendar using your own
 * OpenAI or OpenRouter key. Nothing is sent until you ask, and only the parts you allow.
 */
object Brain {
    data class Provider(val id: String, val name: String, val url: String, val defaultModel: String, val keyHint: String)
    /** Where to get (or copy) a key for each provider. */
    fun keyPage(id: String) = when (id) {
        "openrouter" -> "https://openrouter.ai/settings/keys"
        "gemini" -> "https://aistudio.google.com/apikey"
        "claude" -> "https://platform.claude.com/settings/keys"
        else -> "https://platform.openai.com/api-keys"
    }
    val PROVIDERS = listOf(
        Provider("openai", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-5.4-mini", "sk-…  from platform.openai.com/api-keys"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-5.4-mini", "sk-or-…  from openrouter.ai/keys"),
        // Google's Gemini, through its OpenAI-compatible endpoint (same request shape, Bearer key).
        // Anthropic's Claude, through its OpenAI-compatible endpoint (API key from the Claude Console, not the Pro/Max subscription).
        Provider("claude", "Claude", "https://api.anthropic.com/v1/chat/completions", "claude-haiku-5-5", "sk-ant-…  from the Claude Console"),
        Provider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-3.8-flash", "AIza…  from aistudio.google.com"),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dune", 0)
    fun provider(ctx: Context) = PROVIDERS.firstOrNull { it.id == prefs(ctx).getString("aiProvider", "openai") } ?: PROVIDERS[0]
    fun setProvider(ctx: Context, id: String) = prefs(ctx).edit().putString("aiProvider", id).apply()
    fun model(ctx: Context) = prefs(ctx).getString("aiModel_" + provider(ctx).id, "")!!.ifBlank { provider(ctx).defaultModel }
    fun setModel(ctx: Context, m: String) = prefs(ctx).edit().putString("aiModel_" + provider(ctx).id, m.trim()).apply()
    fun key(ctx: Context) = Secure.get(ctx, "ai_" + provider(ctx).id)
    fun setKey(ctx: Context, k: String?) {
        Secure.put(ctx, "ai_" + provider(ctx).id, k?.trim())
        prefs(ctx).edit().putBoolean("aiHasKey_" + provider(ctx).id, Secure.get(ctx, "ai_" + provider(ctx).id) != null).apply()
    }
    /** Cheap check (no Keystore call), safe to use while drawing the screen. */
    fun ready(ctx: Context) = prefs(ctx).getBoolean("aiHasKey_" + provider(ctx).id, false)

    fun shareMessages(ctx: Context) = prefs(ctx).getBoolean("aiMessages", true)
    fun shareCalendar(ctx: Context) = prefs(ctx).getBoolean("aiCalendar", true)
    fun shareTasks(ctx: Context) = prefs(ctx).getBoolean("aiTasks", true)
    fun setShare(ctx: Context, what: String, on: Boolean) = prefs(ctx).edit().putBoolean(what, on).apply()

    private val fmt get() = SimpleDateFormat("EEE d MMM HH:mm", Locale.UK)

    /** The background the AI is given: today's date, your tasks, recent messages, your calendar. */
    fun context(ctx: Context, store: Store): String = buildString {
        appendLine("Now: " + fmt.format(Date()) + " (phone's local time, " + java.util.TimeZone.getDefault().id + ").")
        if (shareTasks(ctx)) {
            val open = store.tasks.filter { !it.done }
            appendLine("\nTASKS (open): " + if (open.isEmpty()) "none" else "")
            open.forEach { appendLine("- " + it.text) }
            if (store.notes.isNotEmpty()) { appendLine("\nNOTES (newest first):"); store.notes.take(30).forEach { appendLine("- " + it.text.take(500).replace('\n', ' ')) } }
        }
        if (shareCalendar(ctx)) {
            val ev = calendar(ctx)
            if (ev != null) { appendLine("\nCALENDAR (yesterday to 2 weeks ahead):"); if (ev.isEmpty()) appendLine("nothing"); ev.forEach { appendLine("- $it") } }
        }
        if (shareMessages(ctx)) {
            val log = InboxLog.all(ctx).takeLast(150)
            appendLine("\nMESSAGES AND EMAILS (last 7 days, oldest first; app · from · text):")
            if (log.isEmpty()) appendLine("none")
            log.forEach { appendLine("- ${fmt.format(Date(it.time))} · ${it.app} · ${it.title} · ${redact(it.text).take(400)}") }
        }
    }

    /** Login codes and the like never leave the phone: runs of 4–8 digits become "[code]". */
    fun redact(s: String) = Regex("(?<![\\d£$€.,:])\\d{4,8}(?![\\d.,:%])").replace(s) { m ->
        if (m.value.length == 4 && (m.value.startsWith("19") || m.value.startsWith("20"))) m.value else "[code]"   // keep years
    }

    /** Next two weeks of events, or null if Dune may not read the calendar. */
    /** The next thing in your calendar: (start, title, all-day); an event already on only if nothing else starts soon. */
    fun nextEvent(ctx: Context): Triple<Long, String, Boolean>? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - 12 * 3600_000L); android.content.ContentUris.appendId(it, now + 36 * 3600_000L)
        }.build()
        // Only calendars you show, and not invitations you've declined.
        val where = "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY), where, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                var allDay: Triple<Long, String, Boolean>? = null; var ongoing: Triple<Long, String, Boolean>? = null
                while (c.moveToNext()) {
                    val begin = c.getLong(1); val end = c.getLong(2); val day = c.getInt(3) == 1
                    val title = c.getString(0) ?: "(no title)"
                    if (day) { if (allDay == null && end > now) allDay = Triple(begin, title, true); continue }
                    if (begin >= now) return@use if (ongoing != null && begin - now > 3 * 3600_000L) ongoing else Triple(begin, title, false)
                    if (end > now && ongoing == null) ongoing = Triple(begin, title, false)
                }
                ongoing ?: allDay
            }
        }.getOrNull()
    }

    fun calendar(ctx: Context): List<String>? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val from = System.currentTimeMillis() - 24 * 3600_000L
        val to = System.currentTimeMillis() + 14 * 24 * 3600_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to)
        }.build()
        val out = ArrayList<String>()
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION), null, null,
                CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext() && out.size < 60) {
                    val day = c.getInt(2) == 1
                    val whenS = if (day) SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(c.getLong(1))) + " (all day)" else fmt.format(Date(c.getLong(1)))
                    val loc = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " @ $it" } ?: ""
                    out += "$whenS · ${c.getString(0) ?: "(no title)"}$loc"
                }
            }
        }
        return out
    }

    private const val SYSTEM = """You are Dune, the assistant built into Martin's Android phone launcher. Answer his question
using the background below (his tasks, calendar and recent messages) when it is relevant, and your general knowledge
otherwise. Be brief and direct: a sentence or two, or a short list. Plain text, no markdown headings. If the
background doesn't contain what he asks about, say so plainly rather than guessing. Never invent messages or events."""

    /** Ask the AI. [spoken] keeps the answer short enough to be read aloud in the car. */
    /** An answer, and the web pages it used (title, link) when it searched. */
    data class Answer(val text: String, val sources: List<Pair<String, String>> = emptyList())

    fun webOn(ctx: Context) = prefs(ctx).getBoolean("aiWeb", true)
    fun setWeb(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("aiWeb", on).apply()

    suspend fun ask(ctx: Context, store: Store, question: String, spoken: Boolean = false): String = askFull(ctx, store, question, spoken).text

    /**
     * Asks the AI. With web search on, each service's own search is offered and the AI decides when
     * to use it: OpenAI (Responses web_search), OpenRouter (its web_search tool), Claude (web search
     * tool), Gemini (Google Search). If a service refuses search, the question is asked again without it.
     */
    suspend fun askFull(ctx: Context, store: Store, question: String, spoken: Boolean = false): Answer = withContext(Dispatchers.IO) {
        val key = key(ctx) ?: throw IllegalStateException("Add an AI key in Dune's settings first")
        val p = provider(ctx); val model = model(ctx)
        val web = webOn(ctx)
        val sys = SYSTEM + (if (spoken) "\nHe is driving: answer in one or two short spoken sentences." else "") +
            (if (web) "\nYou can search the web. Do so when the question needs current or outside facts (news, prices, opening times, " +
                "tides, sports, travel, anything after your training); otherwise answer directly. Mention where facts came from in a few words." else "") +
            "\n\nBACKGROUND\n" + context(ctx, store)
        if (web) runCatching { return@withContext askWeb(p, key, model, sys, question, spoken) }
            .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e
                val plain = askPlain(p, key, model, sys, question, spoken)
                return@withContext plain.copy(text = plain.text + "\n(web search unavailable: ${e.message?.take(80)})") }
        askPlain(p, key, model, sys, question, spoken)
    }

    private class Reply(val ok: Boolean, val code: Int, val json: JSONObject?)
    private fun post(url: String, body: JSONObject, headers: Map<String, String>): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 90_000
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val txt = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return Reply(ok, c.responseCode, runCatching { JSONObject(txt) }.getOrNull())
    }
    private fun fail(r: Reply): Nothing = throw IllegalStateException(r.json?.optJSONObject("error")?.optString("message")?.ifBlank { null }
        ?: r.json?.optString("message")?.ifBlank { null } ?: "HTTP ${r.code}")
    private fun auth(p: Provider, key: String) = buildMap {
        put("Authorization", "Bearer $key")
        if (p.id == "openrouter") { put("HTTP-Referer", "https://github.com/newbroman/Dune"); put("X-Title", "Dune") }
    }

    private fun askPlain(p: Provider, key: String, model: String, sys: String, question: String, spoken: Boolean): Answer {
        val body = JSONObject().put("model", model).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", question)))
        if (p.id == "claude") body.put("max_tokens", if (spoken) 300 else 1200)
        if (Regex("^(openai/)?(gpt-5|o\\d)").containsMatchIn(model) || (p.id == "gemini" && model.startsWith("gemini-3"))) body.put("reasoning_effort", "low")
        val r = post(p.url, body, auth(p, key))
        if (!r.ok) fail(r)
        return parseChat(r.json!!)
    }

    private fun askWeb(p: Provider, key: String, model: String, sys: String, question: String, spoken: Boolean): Answer = when (p.id) {
        "openai" -> {
            val body = JSONObject().put("model", model).put("instructions", sys).put("input", question)
                .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            if (Regex("^(gpt-5|o\\d)").containsMatchIn(model)) body.put("reasoning", JSONObject().put("effort", "low"))
            val r = post("https://api.openai.com/v1/responses", body, auth(p, key)); if (!r.ok) fail(r); parseResponses(r.json!!)
        }
        "openrouter" -> {
            val body = JSONObject().put("model", model).put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", question)))
                .put("tools", JSONArray().put(JSONObject().put("type", "openrouter:web_search").put("parameters", JSONObject().put("max_results", 5))))
            val r = post(p.url, body, auth(p, key)); if (!r.ok) fail(r); parseChat(r.json!!)
        }
        "claude" -> {
            val body = JSONObject().put("model", model).put("max_tokens", if (spoken) 400 else 1500).put("system", sys)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
                .put("tools", JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3)))
            val r = post("https://api.anthropic.com/v1/messages", body, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
            if (!r.ok) fail(r); parseClaude(r.json!!)
        }
        "gemini" -> {
            val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question)))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            val r = post("https://generativelanguage.googleapis.com/v1beta/models/${model.removePrefix("models/")}:generateContent", body, mapOf("x-goog-api-key" to key))
            if (!r.ok) fail(r); parseGemini(r.json!!)
        }
        else -> throw IllegalStateException("no web search for ${p.name}")
    }

    // ---- reading each service's answer and its sources (pure, so they can be tested)
    private fun nonEmpty(t: String?) = t?.trim()?.ifBlank { null } ?: throw IllegalStateException("The AI sent back an empty answer")
    private fun dedupe(l: List<Pair<String, String>>) = l.filter { it.second.startsWith("http") }.distinctBy { it.second }.take(5)

    fun parseChat(j: JSONObject): Answer {
        val m = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw IllegalStateException("The AI sent back an empty answer")
        val a = m.optJSONArray("annotations") ?: JSONArray()
        val src = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { n ->
            val u = n.optJSONObject("url_citation") ?: n
            u.optString("url").takeIf { it.isNotBlank() }?.let { u.optString("title").ifBlank { it } to it }
        }
        return Answer(nonEmpty(m.optString("content")), dedupe(src))
    }
    fun parseResponses(j: JSONObject): Answer {
        val out = j.optJSONArray("output") ?: JSONArray()
        val text = StringBuilder(); val src = ArrayList<Pair<String, String>>()
        (0 until out.length()).mapNotNull { out.optJSONObject(it) }.filter { it.optString("type") == "message" }.forEach { msg ->
            val cs = msg.optJSONArray("content") ?: JSONArray()
            (0 until cs.length()).mapNotNull { cs.optJSONObject(it) }.filter { it.optString("type") == "output_text" || it.has("text") }.forEach { c ->
                text.append(c.optString("text"))
                val an = c.optJSONArray("annotations") ?: JSONArray()
                (0 until an.length()).mapNotNull { an.optJSONObject(it) }.filter { it.optString("type") == "url_citation" }
                    .forEach { src += it.optString("title").ifBlank { it.optString("url") } to it.optString("url") }
            }
        }
        return Answer(nonEmpty(text.toString().ifBlank { j.optString("output_text") }), dedupe(src))
    }
    fun parseClaude(j: JSONObject): Answer {
        val cs = j.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder(); val src = ArrayList<Pair<String, String>>()
        (0 until cs.length()).mapNotNull { cs.optJSONObject(it) }.filter { it.optString("type") == "text" }.forEach { b ->
            text.append(b.optString("text"))
            val ci = b.optJSONArray("citations") ?: JSONArray()
            (0 until ci.length()).mapNotNull { ci.optJSONObject(it) }.forEach { src += it.optString("title").ifBlank { it.optString("url") } to it.optString("url") }
        }
        return Answer(nonEmpty(text.toString()), dedupe(src))
    }
    fun parseGemini(j: JSONObject): Answer {
        val cand = j.optJSONArray("candidates")?.optJSONObject(0) ?: throw IllegalStateException("The AI sent back an empty answer")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }.joinToString("")
        val ch = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks") ?: JSONArray()
        val src = (0 until ch.length()).mapNotNull { ch.optJSONObject(it)?.optJSONObject("web") }.map { it.optString("title").ifBlank { it.optString("uri") } to it.optString("uri") }
        return Answer(nonEmpty(text), dedupe(src))
    }

}
