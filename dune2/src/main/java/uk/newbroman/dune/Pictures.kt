@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Show me a picture of Pluto", "what does a puffin look like": the Wikipedia article's own picture and a
 * line about it, then more from Wikimedia Commons, shown under the answer. Free, no key, and every
 * picture links back to its page (where its licence and author are). "Show me a picture of it" works
 * after a question to Dune AI – the AI says what "it" was.
 */
object Pictures {
    data class Pic(val url: String, val page: String, val title: String)
    data class Found(val subject: String, val title: String, val about: String?, val article: String?, val pics: List<Pic>)

    private const val UA = "Dune/2 (https://github.com/newbroman/Dune; Android launcher)"
    private val pronoun = Regex("^(it|that|this|them|those|these|him|her|one|the same)$")

    /** The thing to show, from "show me a picture of X" and friends; null if it isn't that kind of request. */
    fun parse(s: String): String? {
        val pic = "(picture|pictures|photo|photos|photograph|photographs|image|images|pic|pics|snap|snaps)"
        val subject = listOf(
            Regex("^(show|find|get|give|bring up|pull up|display) (me |us )?(a |an |some |the |few |a few |any )?(nice |good )?$pic of (.+)$") to 6,
            Regex("^(can i|could i|let me|i want to|i'd like to) (see|have|look at) (a |an |some |the |a few )?$pic of (.+)$") to 5,
            Regex("^(a |an |some )?$pic of (.+)$") to 3,
            Regex("^what (does|do|did) (.+) look like$") to 2,
        ).firstNotNullOfOrNull { (re, g) -> re.matchEntire(s)?.groupValues?.get(g) } ?: return null
        // "what does the weather look like tomorrow", "what does my day look like" aren't pictures
        if (Regex("\\b(weather|forecast|day|week|weekend|diary|calendar|schedule|traffic|today|tomorrow|tonight|morning|afternoon|evening|inbox|my)\\b").containsMatchIn(subject)) return null
        return subject.replace(Regex("\\s+(please|for me)$"), "").trim().ifBlank { null }
    }

    fun needsResolving(subject: String) = pronoun.matches(subject.lowercase())

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Wikipedia's best article for [q]: (title, lead picture, two-sentence summary, link). Pure, for tests. */
    fun parseWiki(j: JSONObject): Triple<String, Pic?, Pair<String?, String?>>? {
        val p = j.optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0) ?: return null
        val title = p.optString("title").ifBlank { return null }
        val page = p.optString("fullurl").ifBlank { null }
        val thumb = p.optJSONObject("thumbnail")?.optString("source")?.ifBlank { null }
        return Triple(title, thumb?.let { Pic(it, page ?: it, title) }, p.optString("extract").trim().ifBlank { null } to page)
    }

    /** The same from Wikipedia's page-summary service. Pure, for tests. */
    fun parseSummary(j: JSONObject): Triple<String, Pic?, Pair<String?, String?>>? {
        if (j.optString("type") == "disambiguation") return null
        val title = j.optString("title").ifBlank { return null }
        val page = j.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")?.ifBlank { null }
        // the original if it's a sensible size, otherwise the thumbnail at 1280 px (a size Wikimedia keeps ready)
        val orig = j.optJSONObject("originalimage"); val th = j.optJSONObject("thumbnail")
        val img = (if (orig != null && orig.optInt("width") in 1..1600) orig.optString("source")
            else th?.optString("source")?.let { t -> if (orig != null) t.replace(Regex("/\\d+px-"), "/1280px-") else t })?.ifBlank { null }
        val about = j.optString("extract").trim().split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ").ifBlank { null }
        return Triple(title, img?.let { Pic(it, page ?: it, title) }, about to page)
    }

    /** Pictures from a Wikimedia Commons file search, in search order, photos and drawings only. Pure, for tests. */
    fun parseCommons(j: JSONObject): List<Pic> {
        val a = j.optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedBy { it.optInt("index", 999) }.mapNotNull { p ->
            val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            if (ii.optString("mime") !in setOf("image/jpeg", "image/png", "image/webp")) return@mapNotNull null
            val url = ii.optString("thumburl").ifBlank { return@mapNotNull null }
            Pic(url, ii.optString("descriptionurl").ifBlank { url },
                p.optString("title").removePrefix("File:").substringBeforeLast('.').replace('_', ' '))
        }
    }

    suspend fun find(subject: String): Found = withContext(Dispatchers.IO) {
        val wiki = runCatching {
            parseWiki(JSONObject(fetch("https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1" +
                "&generator=search&gsrlimit=1&gsrsearch=${enc(subject)}&prop=pageimages%7Cextracts%7Cinfo&inprop=url" +
                "&piprop=thumbnail&pithumbsize=1024&exintro=1&explaintext=1&exsentences=2")))
        }.getOrNull() ?: runCatching {
            // the page summary, if the search is busy or refused: needs the article's name, so try the words as said
            val name = subject.replace(Regex("^(a|an|the|some) ", RegexOption.IGNORE_CASE), "").trim().replace(' ', '_')
            parseSummary(JSONObject(fetch("https://en.wikipedia.org/api/rest_v1/page/summary/" + Uri.encode(name))))
        }.getOrNull()
        val title = wiki?.first ?: subject
        val more = runCatching {
            parseCommons(JSONObject(fetch("https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2" +
                "&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch=${enc("$title filetype:bitmap")}" +
                "&prop=imageinfo&iiprop=url%7Cmime&iiurlwidth=1024")))
        }.getOrDefault(emptyList())
        val lead = wiki?.second
        val pics = (listOfNotNull(lead) + more.filter { m -> lead == null || m.url.substringAfterLast('/') != lead.url.substringAfterLast('/') }).take(8)
        Found(subject, title, wiki?.third?.first, wiki?.third?.second, pics)
    }

    fun run(act: MainActivity, vm: LauncherVM, subject: String, asked: String): String? {
        val spoken = act.lastAskSpoken
        act.lifecycleScope.launch {
            var what = subject
            if (needsResolving(subject)) {
                // "show me a picture of it": the AI knows what "it" was from the conversation
                what = if (Brain.ready(act) && Brain.Chat.size > 0) runCatching {
                    Brain.askFull(act, vm.store, "In the conversation so far, what does \"$subject\" refer to? Reply with only its name, a few words, nothing else.",
                        remember = false).text.trim().trim('"', '.', '“', '”').take(80)
                }.getOrDefault("") else ""
                if (what.isBlank()) { vm.assistantReply = "A picture of what? Try “show me a picture of Pluto”."; return@launch }
            }
            val r = runCatching { find(what) }.getOrNull()
            val reply = when {
                r == null -> { vm.pictures = null; "Couldn't reach Wikipedia for pictures – opening Google Images."
                    .also { googleImages(act, what) } }
                r.pics.isEmpty() -> { vm.pictures = r; "No pictures of “$what” on Wikipedia or Wikimedia Commons." + (r.about?.let { "\n$it" } ?: "") }
                else -> { vm.pictures = r; r.title + (r.about?.let { " – $it" } ?: "") }
            }
            vm.assistantReply = reply
            if (spoken && r?.about != null) Speaker.speak(act, r.about)
            if (Brain.ready(act)) Brain.Chat.add(asked, "(Showed pictures of ${r?.title ?: what}.) " + (r?.about ?: ""))
        }
        return "Finding pictures of ${if (needsResolving(subject)) "that" else subject}…"
    }

    fun googleImages(act: MainActivity, q: String) =
        act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?tbm=isch&q=" + Uri.encode(q))))

    // ---- showing them

    private val cache = LruCache<String, Bitmap>(24)
    private suspend fun load(url: String, maxPx: Int): Bitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000; c.setRequestProperty("User-Agent", UA)
            val bytes = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1; while (o.outWidth / (s * 2) >= maxPx) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()?.also { cache.put(url, it) }
    }

    @Composable
    private fun Picture(url: String, maxPx: Int, modifier: Modifier, crop: Boolean) {
        val bmp by produceState<Bitmap?>(cache.get(url), url) { value = load(url, maxPx) }
        Box(modifier.background(Faint.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit) }
                ?: Text("…", color = Dim)
        }
    }

    @Composable
    fun Panel(vm: LauncherVM) {
        val f = vm.pictures ?: return
        val act = LocalContext.current as MainActivity
        var open by remember(f) { mutableStateOf<Int?>(null) }
        if (f.pics.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                f.pics.forEachIndexed { i, p ->
                    Picture(p.url, 700, Modifier.padding(end = 8.dp).size(if (i == 0) 200.dp else 150.dp, 200.dp)
                        .clip(RoundedCornerShape(12.dp)).clickable { open = i }, crop = true)
                }
            }
        }
        FlowRow(Modifier.padding(top = 4.dp)) {
            f.article?.let { a -> Chip("↗ Wikipedia") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(a))) } }
            Chip("More on Google Images") { googleImages(act, f.title) }
            Chip("✕") { vm.pictures = null }
        }
        open?.let { i ->
            val p = f.pics[i]
            Dialog(onDismissRequest = { open = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Column(Modifier.fillMaxSize().background(Color.Black).clickable { open = null }.padding(12.dp),
                    verticalArrangement = Arrangement.Center) {
                    Picture(p.url, 1600, Modifier.fillMaxWidth().weight(1f), crop = false)
                    Text(p.title, color = Fg, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 6.dp)) {
                        if (i > 0) Chip("‹ Previous") { open = i - 1 }
                        if (i < f.pics.size - 1) Chip("Next ›") { open = i + 1 }
                        Chip("↗ Source & licence") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(p.page))) }
                    }
                }
            }
        }
    }
}
