package uk.newbroman.dune

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Music and radio by voice: recent plays, BBC Sounds stations and shows, Music of the Spheres styles. */
sealed class ListenCmd : Cmd() {
    abstract val label: String
    /** [app] "spotify" to show only Spotify, null for anything played on the phone. */
    data class Recent(val app: String?) : ListenCmd() { override val label get() = "Recent plays" + if (app != null) " (Spotify)" else "" }
    data class Station(val name: String) : ListenCmd() { override val label get() = "Play BBC $name" }
    data class Show(val name: String) : ListenCmd() { override val label get() = "Play the latest $name" }
    object SoundsFavourites : ListenCmd() { override val label = "My BBC Sounds favourites" }
    /** [style] a Spheres dance style key (eno, satie…), or null for its home; [mix] the Sky mix. */
    data class Spheres(val style: String?, val mix: Boolean) : ListenCmd() {
        override val label get() = "Music of the Spheres" + (style?.let { " – " + Listen.styleName(it) + if (mix) " mix" else "" } ?: "")
    }
    data class PickNumber(val n: Int) : ListenCmd() { override val label get() = "Choose number $n" }
}

/** A list Dune shows (and, if the question was spoken, reads out) to choose from. */
data class Pick(val title: String, val items: List<Pick.Item>) {
    data class Item(val label: String, val sub: String? = null, val go: (MainActivity) -> String?)
}

object Listen {
    // ---------------------------------------------------------------- Music of the Spheres styles

    const val SPHERES = "https://newbroman.github.io/Music_of_the_Spheres/"
    /** What you'd say → the style's key in dance.html. */
    private val styles = linkedMapOf(
        "eno" to "eno", "ambient" to "ambient", "ambient drift" to "ambient", "drone" to "drone", "singing bowls" to "bowls", "bowls" to "bowls",
        "longest night" to "solstice", "solstice" to "solstice", "satie" to "satie", "part" to "part", "pärt" to "part", "reich" to "reich",
        "feldman" to "feldman", "cage" to "cage", "lo-fi" to "lofi", "lofi" to "lofi", "lo fi" to "lofi", "glass" to "glass", "bach" to "baroque",
        "baroque" to "baroque", "christmas" to "christmas", "dub" to "dub", "synthwave" to "synthwave", "tango" to "tango", "waltz" to "waltz",
        "techno" to "techno", "trance" to "trance", "house" to "house", "jungle" to "jungle", "disco" to "disco", "funk" to "funk", "swing" to "swing",
        "jive" to "jive", "samba" to "samba", "modal jazz" to "modal", "jazz" to "modal", "prog" to "prog", "prog rock" to "prog", "zappa" to "zappa",
        "moondog" to "moondog", "industrial" to "industrial", "hildegard" to "hildegard", "tallis" to "tallis", "debussy" to "debussy",
        "ravel" to "ravel", "mozart" to "mozart", "vivaldi" to "vivaldi", "chopin" to "chopin", "holst" to "holst", "messiaen" to "messiaen",
        "ligeti" to "ligeti", "xenakis" to "xenakis", "stockhausen" to "stockhausen", "scriabin" to "scriabin", "shostakovich" to "shostakovich",
        "morricone" to "morricone", "raga" to "raga", "gagaku" to "gagaku", "guqin" to "guqin", "gamelan" to "raga", "spectral" to "spectral",
        "birtwistle" to "birtwistle", "saariaho" to "saariaho", "crumb" to "crumb", "partch" to "partch", "nancarrow" to "nancarrow", "ives" to "ives",
        "soul" to "soul", "gospel" to "soul", "oberek" to "oberek", "plygain" to "plygain", "cerdd dant" to "cerdd", "sky" to "sky")
    fun styleName(key: String) = styles.entries.firstOrNull { it.value == key }?.key?.replaceFirstChar { it.uppercase() } ?: key

    fun spheresUrl(style: String?, mix: Boolean) =
        if (style == null) SPHERES else SPHERES + "dance.html#style=" + style + (if (mix) "&mix=1" else "") + "&play"

    // ---------------------------------------------------------------- BBC

    val stations = linkedMapOf(
        "radio 1" to "bbc_radio_one", "radio one" to "bbc_radio_one", "radio 2" to "bbc_radio_two", "radio two" to "bbc_radio_two",
        "radio 3" to "bbc_radio_three", "radio three" to "bbc_radio_three", "radio 3 unwind" to "bbc_radio_three_unwind",
        "radio 4" to "bbc_radio_fourfm", "radio four" to "bbc_radio_fourfm", "radio 4 extra" to "bbc_radio_four_extra", "4 extra" to "bbc_radio_four_extra",
        "radio 5 live" to "bbc_radio_five_live", "5 live" to "bbc_radio_five_live", "6 music" to "bbc_6music", "six music" to "bbc_6music",
        "radio cymru" to "bbc_radio_cymru", "radio wales" to "bbc_radio_wales_fm", "world service" to "bbc_world_service",
        "1xtra" to "bbc_1xtra", "asian network" to "bbc_asian_network")

    // ---------------------------------------------------------------- understanding

    fun parse(s: String): Cmd? {
        // choosing from the list Dune just showed: "number 2", "the third one", "2"
        Regex("^(?:play |choose |pick |select )?(?:number |no\\.? |the )?(\\S+?)(?: one)?$").find(s)?.let { m ->
            val n = Assistant.num(m.groupValues[1]) ?: ordinals[m.groupValues[1]]
            if (n != null && n in 1..20 && (s.startsWith("number") || s.matches(Regex("^\\d+$")) || ordinals.containsKey(m.groupValues[1]) || Regex("^(?:play|choose|pick|select) ").containsMatchIn(s))) return ListenCmd.PickNumber(n)
        }
        // recent plays
        if (Regex("^(?:open spotify and )?(?:show|list|what are|read) (?:me )?(?:my )?(?:most )?recent(?:ly)? (?:plays|played|tracks|songs|music|listening)(?: on spotify| in spotify)?$|" +
                "^(?:my )?(?:most )?recent(?:ly)? (?:plays|played)(?: on spotify)?$|^what (?:have i|did i) (?:been )?listen(?:ed|ing) to(?: on spotify)?$|" +
                "^(?:open )?spotify (?:and )?(?:show )?(?:my )?(?:most )?recent(?:ly)? ?(?:plays|played|tracks)?$|^play something (?:i played|from) recently$").matches(s))
            return ListenCmd.Recent(if (s.contains("spotify")) "spotify" else null)
        // BBC Sounds favourites
        if (Regex("^(?:open )?(?:bbc )?sounds(?: and)?(?: show)?(?: my)? favou?rites$|^(?:show |open |list )?(?:my )?(?:bbc |radio |sounds )?favou?rite (?:shows|programmes|programs|radio)$|^my (?:bbc )?sounds$").matches(s))
            return ListenCmd.SoundsFavourites
        // a station: "play Radio 3", "put on Radio 4", "Radio 3", "BBC Radio 3 on Sounds"
        Regex("^(?:play |put on |turn on |listen to |open |start )?(?:the )?(?:bbc )?(.+?)(?: on (?:bbc )?sounds| live)?$").find(s)?.let { m ->
            val k = m.groupValues[1].removePrefix("bbc ").trim()
            if (k in stations) return ListenCmd.Station(k.replace("radio one", "radio 1").replace("radio two", "radio 2").replace("radio three", "radio 3").replace("radio four", "radio 4").replace("six music", "6 music"))
        }
        // Music of the Spheres: "play Music of the Spheres", "play the Eno mix", "Spheres Satie", "play Eno on Music of the Spheres"
        val sp = Regex("^(?:play |open |start |put on )?(?:the )?(?:music of the spheres|spheres|mots)(?: app)?(?: (?:and )?(?:play |go to |with )?(?:the )?(.+?))?$").find(s)
            ?: Regex("^(?:play |put on |start )(?:the )?(.+?) (?:on|in|from) (?:music of the spheres|spheres)$").find(s)
            ?: Regex("^(?:play |put on |start )(?:the )?(.+? mix)$").find(s)
        if (sp != null) {
            val want = sp.groupValues[1].trim().removePrefix("the ").trim()
            if (want.isEmpty()) return ListenCmd.Spheres(null, false)
            val mix = want.endsWith(" mix") || want.endsWith(" sky mix")
            val name = want.removeSuffix(" mix").removeSuffix(" sky").removeSuffix(" style").trim()
            styles[name]?.let { return ListenCmd.Spheres(it, mix) }
            if (sp.value.contains("spheres")) return ListenCmd.Spheres(null, false)
        }
        Regex("^(?:play|put on|listen to) (?:the )?(.+)$").find(s)?.let { m -> if (m.groupValues[1] in knownShows) return ListenCmd.Show(m.groupValues[1]) }
        // a BBC show: "play Unclassified", "play the latest New Music Show", "play Unclassified on BBC Sounds"
        Regex("^(?:play|put on|listen to) (?:the )?(?:latest |last |newest |new episode of |latest episode of )?(?:the )?(.+?)(?: on (?:bbc )?sounds| from (?:bbc )?sounds| on radio 3| on bbc)$").find(s)?.let {
            return ListenCmd.Show(it.groupValues[1])
        }
        Regex("^(?:play|put on|listen to) (?:the )?(?:latest |last |newest )(?:episode of )?(?:the )?(.+)$").find(s)?.let { return ListenCmd.Show(it.groupValues[1]) }
        return null
    }

    private val ordinals = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7,
        "eighth" to 8, "ninth" to 9, "tenth" to 10, "last" to -1)

    /** Shows Martin listens to, checked against BBC Sounds before falling to a search ("play Unclassified"). */
    private val knownShows = mapOf("unclassified" to "b0b02sl2", "new music show" to "m00040cx", "the new music show" to "m00040cx")

    // ---------------------------------------------------------------- doing

    fun run(act: MainActivity, vm: LauncherVM, c: ListenCmd): String? = when (c) {
        is ListenCmd.Recent -> recent(act, vm, c.app)
        is ListenCmd.Station -> {
            val sid = stations[c.name.lowercase()] ?: stations["radio 3"]!!
            act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bbc.co.uk/sounds/play/live/$sid")))
            "Playing BBC ${c.name.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }}"
        }
        is ListenCmd.Show -> { later(act, vm) { playShow(act, c.name) }; "Finding the latest ${c.name}…" }
        ListenCmd.SoundsFavourites -> soundsFavourites(act, vm)
        is ListenCmd.Spheres -> {
            if (c.style == null) Actions.openSpheres(act) else act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(spheresUrl(c.style, c.mix))))
            if (c.style == null) "Opening Music of the Spheres"
            else "Playing the ${styleName(c.style)}${if (c.mix) " mix" else ""} in Music of the Spheres – tap the screen if it waits for you to start"
        }
        is ListenCmd.PickNumber -> {
            val p = vm.pick ?: return "There's no list to choose from"
            val i = if (c.n == -1) p.items.size else c.n
            p.items.getOrNull(i - 1)?.let { choose(act, vm, it) } ?: "There are only ${p.items.size} on the list"
        }
    }

    private fun later(act: MainActivity, vm: LauncherVM, block: suspend () -> String) {
        act.lifecycleScope.launch {
            val r = runCatching { block() }.getOrElse { "Sorry, that didn't work (${it.javaClass.simpleName})" }
            vm.assistantReply = r
            if (act.lastAskSpoken) Speaker.speak(act, r)
        }
    }

    fun choose(act: MainActivity, vm: LauncherVM, it: Pick.Item): String? { vm.pick = null; return it.go(act) }

    /** Show [p]; if the question was spoken, read the first few out and listen for the choice. */
    private fun offer(act: MainActivity, vm: LauncherVM, p: Pick): String {
        vm.pick = p
        if (act.lastAskSpoken) {
            val said = p.items.take(5).mapIndexed { i, it -> "${i + 1}: ${it.label}" + (it.sub?.let { s -> ", $s" } ?: "") }.joinToString(". ")
            Speaker.speak(act, "${p.title}. $said. Which one?")
            act.window.decorView.postDelayed({
                act.listen("Which one? Say a number") { t ->
                    if (t.isNullOrBlank()) return@listen
                    val n = Assistant.num(t.lowercase().removePrefix("number ").trim().split(' ').first()) ?: ordinals[t.lowercase().removePrefix("the ").split(' ').first()]
                    val item = if (n != null) p.items.getOrNull((if (n == -1) p.items.size else n) - 1)
                        else p.items.firstOrNull { it.label.contains(t, true) || t.contains(it.label, true) }
                    if (item != null) choose(act, vm, item)?.let { r -> vm.assistantReply = r; Speaker.speak(act, r) }
                }
            }, (2500 + 1800 * minOf(5, p.items.size)).toLong())
        }
        return p.title + " – tap one" + if (act.lastAskSpoken) ", or say its number" else ""
    }

    private fun recent(act: MainActivity, vm: LauncherVM, app: String?): String {
        val all = PlayHistory.all(act)
        val list = (if (app == "spotify") all.filter { it.pkg == SPOTIFY } else all).take(12)
        if (list.isEmpty()) {
            if (app == "spotify") { act.launchPackage(SPOTIFY) }
            return if (app == "spotify") "Dune hasn't seen anything play on Spotify yet – opening Spotify. Its own Recents are under Home."
            else "Dune hasn't seen anything play yet (it needs notification access – Settings › Voice commands check)"
        }
        return offer(act, vm, Pick(if (app == "spotify") "Recently played on Spotify" else "Recently played", list.map { p ->
            Pick.Item(p.title, listOfNotNull(p.artist, if (app == null) vm.labelFor(p.pkg) else null).joinToString(" · ").ifBlank { null }) { a ->
                val q = listOfNotNull(p.title, p.artist).joinToString(" ")
                if (MusicHub.playFromSearch(a, p.pkg, q)) "Playing ${p.title}" else { a.launchPackage(p.pkg); "Opening ${vm.labelFor(p.pkg)} – search for ${p.title}" }
            }
        }))
    }

    private fun soundsFavourites(act: MainActivity, vm: LauncherVM): String {
        val shows = vm.store.soundsFavourites.value.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val items = shows.map { name ->
            Pick.Item(name, "latest episode") { a -> later(a, vm) { playShow(a, name) }; "Finding the latest $name…" }
        } + listOf("Radio 3", "Radio 3 Unwind").map { st ->
            Pick.Item(st, "live") { a -> run(a, vm, ListenCmd.Station(st.lowercase())) }
        }
        return offer(act, vm, Pick("Your BBC Sounds favourites", items))
    }

    // BBC's own Sounds service, the one its app uses: no sign-in needed for what's publicly playable
    private const val RMS = "https://rms.api.bbc.co.uk/v2/"

    private suspend fun json(url: String) = withContext(Dispatchers.IO) { JSONObject(get(url)) }

    /** The brand (programme) id for a show, by name. */
    private suspend fun brandFor(name: String): Pair<String, String>? {
        val key = name.lowercase().trim()
        knownShows[key]?.let { return it to name.split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) } }
        val d = json(RMS + "experience/inline/search?q=" + URLEncoder.encode(name, "UTF-8"))
        var hit: Pair<String, String>? = null
        fun walk(o: Any?) {
            if (hit != null) return
            when (o) {
                is JSONObject -> {
                    val urn = o.optString("urn")
                    if ((urn.startsWith("urn:bbc:radio:brand:") || urn.startsWith("urn:bbc:radio:series:")) && o.optJSONObject("titles") != null)
                        hit = o.getString("id") to o.getJSONObject("titles").optString("primary", name)
                    o.keys().forEach { walk(o.opt(it)) }
                }
                is org.json.JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        walk(d)
        return hit
    }

    suspend fun playShow(act: MainActivity, name: String): String {
        val (id, title) = brandFor(name) ?: return "I couldn't find “$name” on BBC Sounds"
        val eps = json(RMS + "programmes/playable?container=$id&sort=sequential&type=episode").optJSONArray("data")
        val ep = eps?.optJSONObject(0) ?: return "$title has nothing to play on BBC Sounds right now"
        // the episode's own id is in its urn ("urn:bbc:radio:episode:m00342c4"); "id" is the version, which Sounds won't open
        val pid = ep.optString("urn").takeIf { it.startsWith("urn:bbc:radio:episode:") }?.substringAfterLast(':') ?: ep.getString("id")
        val epTitle = ep.optJSONObject("titles")?.optString("secondary")?.takeIf { it.isNotBlank() && it != "null" }
        val date = ep.optJSONObject("release")?.optString("date")?.take(10)
        act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bbc.co.uk/sounds/play/$pid")))
        return "Playing $title" + (epTitle?.let { ": $it" } ?: "") + (date?.let { " ($it)" } ?: "")
    }

    // ---------------------------------------------------------------- the list on screen

    @Composable
    fun PickPanel(vm: LauncherVM) {
        val p = vm.pick ?: return
        val act = LocalContext.current as MainActivity
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            p.items.forEachIndexed { i, it ->
                Row(Modifier.fillMaxWidth().clickable { choose(act, vm, it)?.let { r -> vm.assistantReply = r } }.padding(vertical = 6.dp, horizontal = 4.dp)) {
                    Text("${i + 1}", color = Sand, fontSize = 15.sp, modifier = Modifier.width(26.dp))
                    Column {
                        Text(it.label, color = Fg, fontSize = 15.sp)
                        it.sub?.let { s -> Text(s, color = Dim, fontSize = 12.sp) }
                    }
                }
            }
            Text("close", color = Faint, fontSize = 13.sp, modifier = Modifier.clickable { vm.pick = null }.padding(4.dp))
        }
    }
}
