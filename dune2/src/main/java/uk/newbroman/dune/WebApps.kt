package uk.newbroman.dune

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** One of Martin's web apps, as listed on his portfolio page (newbroman.github.io). */
data class WebApp(val title: String, val url: String, val shelf: String) {
    /** "Newborough Warren: groundwater in…" → "Newborough Warren". */
    val label get() = title.substringBefore(":").trim()
    val isLanguage get() = shelf.contains("polish", true) || shelf.contains("language", true)
}

object WebApps {
    private const val PAGE = "https://raw.githubusercontent.com/newbroman/newbroman.github.io/main/index.html"
    @Volatile private var cache: List<WebApp>? = null

    /** Pull every app card (and the research study) off the portfolio page, keeping its shelf heading. */
    fun parse(html: String): List<WebApp> {
        val out = ArrayList<WebApp>()
        var shelf = ""
        val re = Regex("<h2[^>]*>(.*?)</h2>|<article class=\"(app[^\"]*|study)\"[^>]*>(.*?)</article>", RegexOption.DOT_MATCHES_ALL)
        fun clean(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&#39;", "'")
            .replace("&rsquo;", "’").replace("&quot;", "\"").trim()
        for (m in re.findAll(html)) {
            if (m.groups[1] != null) { shelf = clean(m.groupValues[1]); continue }
            val body = m.groupValues[3]
            val title = Regex("<h3>(.*?)</h3>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1) ?: continue
            val url = Regex("<a class=\"go\" href=\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: continue
            if (url.contains("/Dune/")) continue                      // the launcher itself
            out += WebApp(clean(title), url, shelf)
        }
        return out
    }

    private fun fromJson(s: String) = JSONArray(s).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { WebApp(it.getString("title"), it.getString("url"), it.optString("shelf")) }
    }

    suspend fun all(ctx: Context): List<WebApp> = cache ?: withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, "webapps.json")
        val list = runCatching { if (f.exists()) fromJson(f.readText()) else null }.getOrNull()
            ?: fromJson(ctx.assets.open("webapps.json").bufferedReader().use { it.readText() })
        cache = list; list
    }

    /** Re-read the portfolio page once a day, so new apps appear without a Dune update. Icons too. */
    suspend fun refresh(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, "webapps.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 86_400_000L) return@withContext false
        val list = runCatching { parse(fetch(PAGE)) }.getOrNull()?.takeIf { it.size >= 3 } ?: return@withContext false
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("title", it.title).put("url", it.url).put("shelf", it.shelf)) }
        f.writeText(a.toString()); cache = list
        list.forEach { runCatching { fetchIcon(ctx, it) } }
        true
    }

    // ---------- icons: the app's own PWA icon, cached on the phone

    private fun iconFile(ctx: Context, w: WebApp) = File(File(ctx.filesDir, "webicons").apply { mkdirs() },
        Integer.toHexString(w.url.hashCode()) + ".png")

    fun icon(ctx: Context, w: WebApp): ImageBitmap? = runCatching {
        val f = iconFile(ctx, w); if (!f.exists()) null else BitmapFactory.decodeFile(f.path)?.asImageBitmap()
    }.getOrNull()

    suspend fun ensureIcons(ctx: Context, list: List<WebApp>) = withContext(Dispatchers.IO) {
        list.filterNot { iconFile(ctx, it).exists() }.forEach { runCatching { fetchIcon(ctx, it) } }
    }

    private fun fetchIcon(ctx: Context, w: WebApp) {
        val page = fetch(w.url)
        val base = URI(w.url)
        // Prefer the web-app manifest's largest icon, then apple-touch-icon, then any icon link.
        val manifestHref = Regex("<link[^>]+rel=[\"']manifest[\"'][^>]*>", RegexOption.IGNORE_CASE).find(page)?.value
            ?.let { Regex("href=[\"']([^\"']+)").find(it)?.groupValues?.get(1) }
        var iconUrl: String? = null
        if (manifestHref != null) runCatching {
            val mUrl = base.resolve(manifestHref)
            val icons = JSONObject(fetch(mUrl.toString())).optJSONArray("icons")
            if (icons != null) {
                var best = 0
                for (i in 0 until icons.length()) {
                    val ic = icons.getJSONObject(i)
                    val size = ic.optString("sizes").substringBefore("x").toIntOrNull() ?: 1
                    val src = ic.optString("src")
                    if (src.isNotBlank() && !src.endsWith(".svg") && size > best) { best = size; iconUrl = mUrl.resolve(src).toString() }
                }
            }
        }
        if (iconUrl == null) {
            val link = Regex("<link[^>]+rel=[\"'](apple-touch-icon|icon|shortcut icon)[\"'][^>]*>", RegexOption.IGNORE_CASE).findAll(page)
                .mapNotNull { Regex("href=[\"']([^\"']+)").find(it.value)?.groupValues?.get(1) }
                .firstOrNull { !it.endsWith(".svg") && !it.startsWith("data:") }
            if (link != null) iconUrl = base.resolve(link).toString()
        }
        val u = iconUrl ?: return
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        try {
            if (c.responseCode !in 200..299) return
            val bytes = c.inputStream.use { it.readBytes() }
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) iconFile(ctx, w).writeBytes(bytes)
        } finally { c.disconnect() }
    }

    private fun fetch(url: String): String = get(url)
}
