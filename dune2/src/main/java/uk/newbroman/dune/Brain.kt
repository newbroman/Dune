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
            p.edit().putString(name, Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
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
    val PROVIDERS = listOf(
        Provider("openai", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-5.4-mini", "sk-…  from platform.openai.com/api-keys"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-5.4-mini", "sk-or-…  from openrouter.ai/keys"),
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
    suspend fun ask(ctx: Context, store: Store, question: String, spoken: Boolean = false): String = withContext(Dispatchers.IO) {
        val key = key(ctx) ?: throw IllegalStateException("Add an AI key in Dune's settings first")
        val p = provider(ctx); val model = model(ctx)
        val sys = SYSTEM + (if (spoken) "\nHe is driving: answer in one or two short spoken sentences." else "") +
            "\n\nBACKGROUND\n" + context(ctx, store)
        val body = JSONObject().put("model", model).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", question)))
        if (Regex("^(openai/)?(gpt-5|o\\d)").containsMatchIn(model)) body.put("reasoning_effort", "low")
        val c = URL(p.url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        if (p.id == "openrouter") { c.setRequestProperty("HTTP-Referer", "https://github.com/newbroman/Dune"); c.setRequestProperty("X-Title", "Dune") }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val txt = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        val j = runCatching { JSONObject(txt) }.getOrNull()
        if (!ok) throw IllegalStateException(j?.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "HTTP ${c.responseCode}")
        j?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
            ?.ifBlank { null } ?: throw IllegalStateException("The AI sent back an empty answer")
    }
}
