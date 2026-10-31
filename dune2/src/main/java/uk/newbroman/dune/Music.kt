@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** A track heard recently, from any app. */
data class Played(val title: String, val artist: String?, val pkg: String, val time: Long)

/** The last 40 tracks played on the phone (kept only on the phone). */
object PlayHistory {
    private const val MAX = 40
    fun all(ctx: Context): List<Played> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences("dune", 0).getString("playHistory", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Played(o.getString("t"), o.optString("a").ifBlank { null }, o.getString("p"), o.getLong("w")) } }
    }.getOrDefault(emptyList())

    fun add(ctx: Context, p: Played) {
        val l = (listOf(p) + all(ctx).filterNot { it.title == p.title && it.artist == p.artist && it.pkg == p.pkg }).take(MAX)
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("t", it.title).put("a", it.artist ?: "").put("p", it.pkg).put("w", it.time)) }
        ctx.getSharedPreferences("dune", 0).edit().putString("playHistory", a.toString()).apply()
    }
}

object MusicHub : Hub {
    override val id = "music"; override val title = "Music"; override val glyph = "♪"
    const val SPOTIFY = "com.spotify.music"
    const val BBC_SOUNDS = "com.bbc.sounds"
    private val APPS = listOf(SPOTIFY, BBC_SOUNDS, "com.google.android.apps.youtube.music", "au.com.shiftyjelly.pocketcasts",
        "com.google.android.apps.podcasts", "tunein.player", "com.amazon.mp3", "deezer.android.app", "com.soundcloud.android",
        "com.apple.android.music", "radiotime.player", "com.audible.application")
    private val WORDS = listOf("music", "radio", "podcast", "sounds", "spotify", "player", "audio", "fm")

    /** BBC stations: their Sounds links open straight into the BBC Sounds app when it's installed. */
    val STATIONS = listOf(
        "Radio 1" to "bbc_radio_one", "Radio 2" to "bbc_radio_two", "Radio 3" to "bbc_radio_three",
        "Radio 4" to "bbc_radio_fourfm", "4 Extra" to "bbc_radio_four_extra", "5 Live" to "bbc_radio_five_live",
        "6 Music" to "bbc_6music", "Cymru" to "bbc_radio_cymru", "Wales" to "bbc_radio_wales_fm", "World Service" to "bbc_world_service",
    )

    /** Ask an app to find and play something ("play X" in Spotify, YouTube Music…). */
    fun playFromSearch(ctx: Context, pkg: String?, q: String): Boolean {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, q)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        return ctx.tryStart(i) || (pkg == SPOTIFY && ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
    }

    /** Apps that can take a "play X" request. */
    fun searchApps(ctx: Context, vm: LauncherVM): List<String> =
        ctx.packageManager.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
            .map { it.activityInfo.packageName }.distinct().filter { p -> vm.apps.any { it.pkg == p } }

    @Composable override fun summary(vm: LauncherVM): String? {
        val m = vm.media
        val t = m.title ?: return null
        return (if (m.playing) "▶ " else "❚❚ ") + t + (m.artist?.let { " — $it" } ?: "") + (m.appPkg?.let { " · " + vm.labelFor(it) } ?: "")
    }

    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        val m = vm.media
        // Watch media sessions while this hub is on screen (Drive mode may be watching too).
        DisposableEffect(active) {
            if (active) m.start()
            onDispose { if (active) m.stop() }
        }
        val searchers = remember(vm.apps) { searchApps(act, vm) }
        var target by rememberSaveable { mutableStateOf<String?>(null) }
        val chosen = target?.takeIf { it in searchers } ?: searchers.firstOrNull { it == SPOTIFY } ?: searchers.firstOrNull()
        var q by rememberSaveable { mutableStateOf("") }
        val history by produceState(emptyList<Played>(), m.title, active) { value = PlayHistory.all(act) }
        val noAccess = NotifService.instance == null

        HubPage("Music", gestures) {
            item {
                AppsRow(vm, id, APPS, WORDS)
                if (noAccess) LinkRow("Allow notification access so Dune can see and control what's playing") { Actions.notificationAccess(act) }
                NowPlaying(vm)
                // Other apps with something loaded: tap to control that one instead.
                if (m.sessions.size > 1) FlowRow(Modifier.padding(top = 6.dp)) {
                    m.sessions.forEach { p -> Chip((if (p == m.appPkg) "● " else "") + vm.labelFor(p)) { m.choose(p) } }
                }
            }
            // Play something: typed or spoken, sent to the chosen app.
            item {
                Text("PLAY SOMETHING", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
                val go = { if (q.isNotBlank()) { if (!playFromSearch(act, chosen, q.trim())) act.toast("That app didn't take the request"); q = "" } }
                Row(Modifier.fillMaxWidth().border(1.dp, Faint, RoundedCornerShape(22.dp)).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(q, { q = it }, textStyle = TextStyle(color = Fg, fontSize = 17.sp), cursorBrush = SolidColor(Sand), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }),
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        decorationBox = { inner -> Box { if (q.isEmpty()) Text("An artist, album, song or playlist", color = Faint, fontSize = 16.sp); inner() } })
                    Text("🎤", fontSize = 20.sp, modifier = Modifier.combinedClickable {
                        act.listen("What shall I play?") { it?.let { t -> if (!playFromSearch(act, chosen, t)) act.toast("That app didn't take the request") } }
                    }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                if (searchers.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp)) {
                    Text("in  ", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    searchers.forEach { p -> Chip((if (p == chosen) "● " else "") + vm.labelFor(p)) { target = p } }
                } else Note("Install Spotify or YouTube Music to play things by name.")
            }
            // Radio
            item {
                Text("RADIO", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                FlowRow {
                    STATIONS.forEach { (name, sid) ->
                        Chip(name) { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bbc.co.uk/sounds/play/live:$sid")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    Chip("✦ Spheres") { act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(Actions.SPHERES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            // Recently played, from any app: tap to play it again there.
            if (history.isNotEmpty()) item {
                Text("RECENTLY PLAYED", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            }
            history.forEachIndexed { i, p ->
                item(key = "h$i") {
                    Row(Modifier.fillMaxWidth().combinedClickable {
                        val again = p.title + (p.artist?.let { " $it" } ?: "")
                        if (!playFromSearch(act, p.pkg.takeIf { it in searchers } ?: chosen, again)) launchPkg(vm, p.pkg)
                    }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = Fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(p.artist, vm.labelFor(p.pkg), ago(p.time)).joinToString(" · "), color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("▶", color = Sand, fontSize = 16.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(vm: LauncherVM) {
    val m = vm.media
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(104.dp).background(Color(0xFF15140F), RoundedCornerShape(12.dp))
            .combinedClickable { m.appPkg?.let { launchPkg(vm, it) } }, contentAlignment = Alignment.Center) {
            val art = m.art
            if (art != null) Image(art.asImageBitmap(), null, Modifier.size(104.dp), contentScale = ContentScale.Crop)
            else Text("♪", color = Faint, fontSize = 40.sp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title ?: "Nothing playing", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Light, maxLines = 2, overflow = TextOverflow.Ellipsis)
            m.artist?.let { Text(it, color = Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            m.appPkg?.let { Text(vm.labelFor(it), color = Sand, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp).combinedClickable { launchPkg(vm, it) }) }
        }
    }
    // Progress, worked out from the session's last report so it moves smoothly between updates.
    if (m.durationMs > 0) {
        val now by produceState(android.os.SystemClock.elapsedRealtime(), m.playing, m.positionMs) {
            while (m.playing) { delay(500); value = android.os.SystemClock.elapsedRealtime() }
            value = android.os.SystemClock.elapsedRealtime()
        }
        val pos = (if (m.playing) m.positionMs + ((now - m.positionAt) * m.speed).toLong() else m.positionMs).coerceIn(0, m.durationMs)
        Column(Modifier.padding(top = 12.dp)) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Faint, RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(pos.toFloat() / m.durationMs).height(4.dp).background(Sand, RoundedCornerShape(2.dp)))
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(clock(pos), color = Dim, fontSize = 11.sp); Spacer(Modifier.weight(1f)); Text(clock(m.durationMs), color = Dim, fontSize = 11.sp)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Ctl("⏮", 30) { m.control("previous") }
        if (m.durationMs > 0) Ctl("−15", 16) { m.seekBy(-15_000) }
        Ctl(if (m.playing) "❚❚" else "▶", 40, big = true) { m.control("toggle") }
        if (m.durationMs > 0) Ctl("+30", 16) { m.seekBy(30_000) }
        Ctl("⏭", 30) { m.control("next") }
    }
}

@Composable
private fun Ctl(label: String, size: Int, big: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(if (big) 76.dp else 56.dp).border(if (big) 1.dp else 0.dp, if (big) Sand else Color.Transparent, RoundedCornerShape(38.dp))
        .combinedClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (big) Sand else Fg, fontSize = size.sp)
    }
}

private fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
