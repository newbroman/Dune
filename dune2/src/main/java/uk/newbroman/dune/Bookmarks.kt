package uk.newbroman.dune

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved web page: shared to Dune from a browser, or imported from a browser's bookmarks file. */
data class Bookmark(val url: String, val title: String, val folder: String = "", val added: Long = 0)

/**
 * Bookmarks. Android gives other apps no way into Firefox's (or Chrome's) bookmarks, so they come in by
 * Share › Dune, by a browser's "Add to Home screen" (kept as pinned shortcuts, see [Store.pinned]),
 * or from a bookmarks file exported on a computer (bookmarks.html from any browser, or Firefox's .json backup).
 * Kept in files/bookmarks.json, which goes into Dune's backup.
 */
object Bookmarks {
    const val FILE = "bookmarks.json"

    var list by mutableStateOf<List<Bookmark>>(emptyList())
        private set
    @Volatile private var loaded = false

    fun ensure(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            list = runCatching { fromJson(File(ctx.filesDir, FILE).readText()) }.getOrDefault(emptyList())
            loaded = true
        }
    }

    private fun save(ctx: Context, l: List<Bookmark>) {
        list = l
        runCatching {
            val f = File(ctx.filesDir, FILE)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(toJson(l)); tmp.renameTo(f)
        }
    }

    /** Adds what's new; returns how many were new. */
    fun add(ctx: Context, new: List<Bookmark>): Int {
        ensure(ctx)
        val merged = merge(list, new)
        val n = merged.size - list.size
        if (merged != list) save(ctx, merged)
        return n
    }

    fun remove(ctx: Context, b: Bookmark) { ensure(ctx); save(ctx, list.filterNot { norm(it.url) == norm(b.url) }) }

    fun open(ctx: Context, url: String) = ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun byUrl(url: String): Bookmark? = list.firstOrNull { norm(it.url) == norm(url) }

    /** Reads a picked bookmarks file and adds it. Returns a line to show, e.g. "312 bookmarks added (40 already here)". */
    fun importFile(ctx: Context, uri: Uri): String {
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            ?: return "Couldn't read that file"
        val found = parse(text)
        if (found.isEmpty()) return "No bookmarks found in that file – it needs to be a bookmarks .html export or a Firefox .json backup"
        val n = add(ctx, found)
        val dup = found.distinctBy { norm(it.url) }.size - n
        return "$n bookmark${if (n == 1) "" else "s"} added" + (if (dup > 0) " ($dup already here)" else "")
    }

    // ---------- pure logic (unit-tested)

    /** bookmarks.html (Netscape format, from Firefox, Chrome, Edge…) or a Firefox .json backup. */
    fun parse(text: String): List<Bookmark> =
        if (text.trimStart().startsWith("{")) runCatching { parseFirefoxJson(text) }.getOrDefault(emptyList()) else parseHtml(text)

    private val TOKEN = Regex(
        """<DL\b[^>]*>|</DL>|<H3\b[^>]*>(.*?)</H3>|<A\s([^>]*)>(.*?)</A>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val HREF = Regex("""\bHREF\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val ADD_DATE = Regex("""\bADD_DATE\s*=\s*"(\d+)"""", RegexOption.IGNORE_CASE)

    fun parseHtml(html: String): List<Bookmark> {
        val out = mutableListOf<Bookmark>()
        val path = ArrayDeque<String>()
        var pending: String? = null
        for (m in TOKEN.findAll(html)) {
            val t = m.value
            when {
                t.startsWith("<H3", true) -> pending = unescape(m.groupValues[1]).trim()
                t.startsWith("</DL", true) -> path.removeLastOrNull()
                t.startsWith("<DL", true) -> { path.addLast(pending ?: ""); pending = null }
                else -> {
                    val url = HREF.find(m.groupValues[2])?.groupValues?.get(1)?.let(::unescape)?.trim() ?: continue
                    if (!isWeb(url)) continue
                    val secs = ADD_DATE.find(m.groupValues[2])?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val title = unescape(m.groupValues[3].replace(Regex("<[^>]+>"), "")).trim()
                    out += Bookmark(url, title.ifEmpty { host(url) }, folderName(path), secs * 1000)
                }
            }
        }
        return out
    }

    /** Firefox's Bookmarks › Manage › Import and Backup › Backup… (.json). */
    fun parseFirefoxJson(json: String): List<Bookmark> {
        val out = mutableListOf<Bookmark>()
        fun walk(o: JSONObject, path: List<String>) {
            val kids = o.optJSONArray("children")
            if (o.optString("type") == "text/x-moz-place") {
                val url = o.optString("uri")
                if (isWeb(url)) out += Bookmark(url, o.optString("title").trim().ifEmpty { host(url) },
                    folderName(path), o.optLong("dateAdded") / 1000)
            }
            if (kids != null) {
                val name = ROOTS[o.optString("root")] ?: ROOTS[o.optString("title")] ?: o.optString("title")
                val next = if (o.optString("root") == "placesRoot") path else path + name
                for (i in 0 until kids.length()) kids.optJSONObject(i)?.let { walk(it, next) }
            }
        }
        walk(JSONObject(json), emptyList())
        return out
    }

    private val ROOTS = mapOf(
        "bookmarksMenuFolder" to "Bookmarks Menu", "toolbarFolder" to "Bookmarks Toolbar",
        "unfiledBookmarksFolder" to "Other Bookmarks", "mobileFolder" to "Mobile Bookmarks",
        "menu" to "Bookmarks Menu", "toolbar" to "Bookmarks Toolbar", "unfiled" to "Other Bookmarks", "mobile" to "Mobile Bookmarks",
    )

    private fun folderName(path: Collection<String>) = path.filter { it.isNotBlank() }.joinToString(" › ")

    /** From Share › Dune: browsers send the address (sometimes with the page title around it) and often the title as the subject. */
    fun fromShare(text: String?, subject: String?, now: Long = 0): Bookmark? {
        val t = text ?: return null
        val raw = Regex("""https?://\S+""").find(t)?.value ?: return null
        val url = raw.trimEnd('.', ',', ')', '"', '\'', '>')
        val rest = t.replace(raw, "").trim { it.isWhitespace() || it in "-–—|:.,·" }
        val title = subject?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("http") }
            ?: rest.takeIf { it.isNotEmpty() && it.length < 200 } ?: host(url)
        return Bookmark(url, title, "Shared", now)
    }

    /** Old ones keep their place; new ones go first, newest first. Same page = same address ignoring http/https, www. and a final /. */
    fun merge(old: List<Bookmark>, new: List<Bookmark>): List<Bookmark> {
        val have = old.map { norm(it.url) }.toMutableSet()
        val add = new.filter { have.add(norm(it.url)) }.sortedByDescending { it.added }
        return add + old
    }

    fun norm(url: String) = url.trim().lowercase()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")

    fun isWeb(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    /** Every word of [q] somewhere in the title, address or folder. */
    fun matches(b: Bookmark, q: String): Boolean {
        val hay = (b.title + " " + b.url + " " + b.folder).lowercase()
        return q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { it in hay }
    }

    fun search(l: List<Bookmark>, q: String, limit: Int = 50): List<Bookmark> =
        if (q.isBlank()) l.take(limit) else l.filter { matches(it, q) }.take(limit)

    fun unescape(s: String): String = Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos|#39|nbsp);""").replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> runCatching {
                val cp = if (e.startsWith("#x", true)) e.drop(2).toInt(16) else e.drop(1).toInt()
                String(Character.toChars(cp))
            }.getOrDefault(m.value)
        }
    }

    fun toJson(l: List<Bookmark>): String = JSONArray().apply {
        l.forEach { put(JSONObject().put("url", it.url).put("title", it.title).put("folder", it.folder).put("added", it.added)) }
    }.toString()

    fun fromJson(s: String): List<Bookmark> {
        val a = JSONArray(s)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Bookmark(it.getString("url"), it.optString("title"), it.optString("folder"), it.optLong("added"))
        }
    }
}
