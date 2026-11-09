@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Cloze progress, as the web app saves it: streak, today's points, and per language what's due. */
data class ClozeSummary(val streak: Int, val today: Int, val due: Map<String, Int>, val mastered: Map<String, Int>)

object ClozeStats {
    val NAMES = linkedMapOf("pol" to "Polish", "cym" to "Welsh", "deu" to "German", "nob" to "Norwegian")
    fun parse(json: String?): ClozeSummary? = runCatching {
        val o = JSONObject(json!!.trim('"').replace("\\\"", "\"").replace("\\\\", "\\"))
        val l = o.optJSONObject("langs") ?: JSONObject()
        val due = HashMap<String, Int>(); val m = HashMap<String, Int>()
        l.keys().forEach { k -> l.optJSONObject(k)?.let { due[k] = it.optInt("due"); m[k] = it.optInt("mastered") } }
        ClozeSummary(o.optInt("streak"), o.optInt("today"), due, m)
    }.getOrNull()
}

/** Languages: Cloze (sentences, spaced repetition) inside Dune with voice, plus your other language apps. */
object LearnHub : Hub {
    override val id = "learn"; override val title = "Learn"; override val glyph = "Ł"

    @Composable override fun summary(vm: LauncherVM): String? {
        val ctx = LocalContext.current
        val s = ClozeStats.parse(ctx.getSharedPreferences("dune", 0).getString("clozeSummary", null)) ?: return null
        val due = s.due.values.sum()
        return (if (due > 0) "$due sentences to review" else "Tongues: all caught up") + (if (s.streak > 0) " · 🔥 ${s.streak}" else "") +
            (if (s.today > 0) " · ${s.today} pts today" else "")
    }

    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        val prefs = remember { act.getSharedPreferences("dune", 0) }
        var stats by remember { mutableStateOf(ClozeStats.parse(prefs.getString("clozeSummary", null))) }
        var more by remember { mutableStateOf(false) }
        // Tongues only runs while this hub is on screen. Kept alive next door, its hands-free mode used to
        // restart (and re-say the same card) every time the page came back into view – e.g. on any hub switch.
        // Now: away → paused and silent; back → quiet, or a fresh card read aloud if Settings › Learn says so.
        LaunchedEffect(active, vm.resumeTick) {
            val v = act.cloze().view
            if (!active) { if (vm.screen != Screen.CLOZE) { v.evaluateJavascript(TonguesVoice.HUSH, null); v.onPause() }; return@LaunchedEffect }
            v.evaluateJavascript(TonguesVoice.HUSH, null)
            v.onResume()
            if (vm.store.learnSpeakOnOpen.value) { delay(400); v.evaluateJavascript(TonguesVoice.FRESH_CARD, null) }
        }
        // Read Cloze's progress from the page now and then, for this hub and the Home line.
        LaunchedEffect(active) {
            while (active) {
                act.cloze().view.evaluateJavascript("localStorage.getItem('cloze.summary')") { r ->
                    if (r != null && r != "null") { prefs.edit().putString("clozeSummary", r).apply(); stats = ClozeStats.parse(r) }
                }
                delay(5000)
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Learn", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Light)
                stats?.let { s -> if (s.streak > 0) Text("   🔥 ${s.streak}", color = Sand, fontSize = 14.sp) }
                Spacer(Modifier.weight(1f))
                Box {
                    MapAction("more ▾") { more = true }
                    DropdownMenu(more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("Rozmowa (conversation)") }, onClick = { more = false; vm.screen = Screen.ROZMOWA })
                        vm.webApps.filter { it.isLanguage && !it.url.contains("/cloze") && !it.url.contains("/rozmowa") }.forEach { w ->
                            DropdownMenuItem(text = { Text(w.label) }, onClick = { more = false; vm.openWeb(w) })
                        }
                        listOf("com.duolingo", "com.clozemaster.v2", "com.memrise.android.memrisecompanion", "com.ichi2.anki")
                            .filter { p -> vm.apps.any { it.pkg == p } }.forEach { p ->
                                DropdownMenuItem(text = { Text(vm.labelFor(p)) }, onClick = { more = false; launchPkg(vm, p) })
                            }
                    }
                }
                MapAction("full screen") { vm.screen = Screen.CLOZE }
            }
            // One tap into each language, with what's due.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 18.dp, bottom = 4.dp)) {
                ClozeStats.NAMES.forEach { (code, name) ->
                    val due = stats?.due?.get(code) ?: 0
                    Chip(name + if (due > 0) " · $due" else "") {
                        act.cloze().view.evaluateJavascript("location.hash='lang=$code';document.getElementById('lang').value='$code';" +
                            "document.getElementById('lang').dispatchEvent(new Event('change'))", null)
                    }
                }
            }
            AndroidView(factory = { act.cloze().detached() }, modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp))
        }
    }
}

/** What Dune says to the Tongues page when the Learn hub comes and goes. */
object TonguesVoice {
    /** Stop hands-free and any speech; the card stays on screen, silent. */
    const val HUSH = "(function(){try{if(typeof hf!=='undefined'&&hf&&typeof hfStop==='function')hfStop(true);" +
        "if(window.speechSynthesis)speechSynthesis.cancel();}catch(e){}})()"
    /** Move on to the next card and read it out (hands-free if it was on, otherwise just the sentence). */
    const val FRESH_CARD = "(function(){try{if(typeof deck==='undefined'||!deck||typeof next!=='function')return;" +
        "pos++;next(true);if(card!=null&&deck.items[card])speak(deck.items[card][0]);}catch(e){}})()"
}

/** Cloze on its own, full screen. */
@Composable
fun ClozeFull() {
    val act = LocalContext.current as MainActivity
    LaunchedEffect(Unit) { act.cloze().view.onResume() }
    AndroidView(factory = { act.cloze().detached() }, modifier = Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding())
}
