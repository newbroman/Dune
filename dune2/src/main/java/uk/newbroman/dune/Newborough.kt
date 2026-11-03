@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A dipwell: id, where it is, and its ground / pipe-top levels (m AOD). */
data class Well(val id: String, val lat: Double, val lon: Double, val near: String, val ground: Double?, val pipe: Double?)

object Wells {
    private var cache: List<Well>? = null
    /** The well list that ships with Dune (from the well logger, reconciled with the project's well metadata). */
    fun all(ctx: Context): List<Well> = cache ?: runCatching {
        val fs = JSONObject(ctx.assets.open("map/wells.geojson").bufferedReader().readText()).getJSONArray("features")
        (0 until fs.length()).map { fs.getJSONObject(it) }.map { f ->
            val c = f.getJSONObject("geometry").getJSONArray("coordinates"); val p = f.getJSONObject("properties")
            fun d(k: String) = if (p.isNull(k)) null else p.optDouble(k).takeIf { !it.isNaN() }
            Well(p.getString("well"), c.getDouble(1), c.getDouble(0), p.optString("near"), d("ground_mAOD"), d("pipe_top_mAOD"))
        }
    }.getOrDefault(emptyList()).also { cache = it }

    fun nearest(ctx: Context, at: Location): Pair<Well, Float>? = all(ctx).map { w ->
        val r = FloatArray(1); Location.distanceBetween(at.latitude, at.longitude, w.lat, w.lon, r); w to r[0]
    }.minByOrNull { it.second }

    /** Walking directions in Google Maps. */
    fun walkTo(w: Well) = "https://www.google.com/maps/dir/?api=1&destination=${w.lat},${w.lon}&travelmode=walking"
}

/** Newborough Warren: the well logger, the wells on a map, the research tools, and the conditions on site. */
object NewboroughHub : Hub {
    override val id = "warren"; override val title = "Newborough"; override val glyph = "≋"
    private const val SITE = "https://newbroman.github.io/Newborough_Hydrology/"
    /** The wells map in Google My Maps (linked from the project's site). */
    const val WELLS_MAP = "https://www.google.com/maps/d/viewer?mid=1hXLAauiMeaVsXhBR_IoUTziAtjk"

    private val TOOLS = listOf(
        "Water Watch (monthly)" to SITE + "water_watch/index.html",
        "Ask the water levels (chat)" to SITE + "chat/index.html",
        "Groundwater flooding forecaster" to SITE + "outputs/11b_spatial_thresholds/forecaster.html",
        "Scenario viewer" to SITE + "scenario_viewer.html",
        "Seasonal extremes" to SITE + "seasonal_extremes_scatter.html",
        "Hindcast film" to SITE + "hindcast_film.html",
        "Report (PDF)" to SITE + "docs/report/report.pdf",
        "Public summary (PDF)" to SITE + "docs/public_summaries/Newborough_Warren_Public_Summary.pdf",
        "Crynodeb cyhoeddus (PDF)" to SITE + "docs/public_summaries/Niwbwrch_Crynodeb_Cyhoeddus.pdf",
        "Research site" to SITE,
        "GitHub repo" to "https://github.com/newbroman/Newborough_Hydrology",
    )
    private val CONDITIONS = listOf(
        "Tides · Llanddwyn" to "https://www.tidetimes.org.uk/llanddwyn-island-tide-times",
        "Tides · Admiralty Holyhead" to "https://easytide.admiralty.co.uk/?PortID=0478",
        "Rain · Bodorgan (IBODOR6)" to "https://www.wunderground.com/dashboard/pws/IBODOR6",
        "Rain · ILLANF24" to "https://www.wunderground.com/dashboard/pws/ILLANF24",
        "Rain · RAF Valley" to "https://weather.metoffice.gov.uk/observations/gckyby0r2",
    )

    private fun open(ctx: Context, url: String) = ctx.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    @Composable override fun summary(vm: LauncherVM): String? {
        val ctx = LocalContext.current
        val s = remember(vm.resumeTick) { ctx.getSharedPreferences("dune", 0).getString("wellsSummary", null) }
        return s ?: "Well logger · wells map · research tools"
    }

    override fun search(ctx: Context, vm: LauncherVM, q: String): List<Pair<String, () -> Unit>> {
        val w = Wells.all(ctx).filter { it.id.equals(q.trim(), true) || (q.length >= 3 && it.id.startsWith(q.trim(), true)) }.take(3)
        return w.map { ("≋ Well ${it.id} – log a reading") to { act(ctx)?.let { a -> openWellLogger(vm, a, it.id) } ?: Unit } }
    }
    private fun act(ctx: Context) = ctx as? MainActivity

    @SuppressLint("MissingPermission")
    @Composable override fun Page(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
        val act = LocalContext.current as MainActivity
        var here by remember { mutableStateOf<Location?>(null) }
        var logged by remember { mutableStateOf(act.getSharedPreferences("dune", 0).getString("wellsSummary", null)) }
        // Where you are (last known fix – no GPS kept running), for the nearest well.
        LaunchedEffect(active, vm.resumeTick) {
            if (!active) return@LaunchedEffect
            here = withContext(Dispatchers.IO) {
                val ok = act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!ok) null else act.getSystemService(LocationManager::class.java)?.let { lm ->
                    lm.getProviders(true).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
                }
            }
            // What's in the logger inside Dune (its own readings store).
            // (only if the logger has been opened – no loading it in the background just to count)
            act.wellsIfOpen()?.eval("(function(){try{var r=JSON.parse(localStorage.getItem('nw_readings')||'[]');" +
                "var last=r.length?r[r.length-1]:null;return JSON.stringify({n:r.length,last:last?(last.dateDisplay||last.date||''):''," +
                "today:r.filter(function(x){return x.dateDisplay===new Date().toLocaleDateString('en-GB',{day:'2-digit',month:'short',year:'numeric'})}).length})}catch(e){return ''}})()") { res ->
                runCatching {
                    val o = JSONObject(JSONObject("{\"v\":$res}").getString("v"))
                    val n = o.optInt("n"); val last = o.optString("last"); val today = o.optInt("today")
                    logged = if (n == 0) "No readings in Dune's logger yet" else "$n readings in Dune's logger" + (if (today > 0) " · $today today" else "") +
                        (if (last.isNotBlank()) " · last $last" else "")
                    act.getSharedPreferences("dune", 0).edit().putString("wellsSummary", logged).apply()
                }
            }
        }
        val wells = remember { Wells.all(act) }
        val near = here?.let { h -> Wells.nearest(act, h) }?.takeIf { it.second < 5000 }
        HubPage("Newborough", gestures) {
            item {
                FlowRow(Modifier.padding(top = 4.dp)) {
                    Tile("Log readings", "well logger", onLong = { openWellLogger(vm, act, installed = true) }) { openWellLogger(vm, act) }
                    Tile("Wells map", "Google My Maps") { open(act, WELLS_MAP) }
                    Tile("On Dune's map", "${wells.size} wells") { vm.mapShowWells = true; vm.jumpHub = "map" }
                }
                logged?.let { Note(it) }
            }
            item {
                Section("Nearest well")
                if (near == null) Note(if (here == null) "Allow location (Map hub) to see the nearest well." else "You're not at the Warren – the nearest well is over 5 km away.")
                else {
                    val (w, m) = near
                    Text("${w.id}  ·  ${if (m < 1000) "${m.toInt()} m" else String.format(Locale.UK, "%.1f km", m / 1000)}", color = Fg, fontSize = 20.sp)
                    Text(listOfNotNull(w.near.ifBlank { null }, w.ground?.let { String.format(Locale.UK, "ground %.2f m AOD", it) },
                        w.pipe?.let { String.format(Locale.UK, "pipe top %.2f", it) }).joinToString(" · "), color = Dim, fontSize = 13.sp)
                    Row {
                        Chip("log ${w.id}") { openWellLogger(vm, act, w.id) }
                        Chip("walk there") { open(act, Wells.walkTo(w)) }
                    }
                }
            }
            item {
                Section("Research")
                TOOLS.forEach { (label, url) -> LinkRow(label) { open(act, url) } }
            }
            item {
                Section("Conditions")
                FlowRow { CONDITIONS.forEach { (label, url) -> Chip(label) { open(act, url) } } }
                Note("Long-press “Log readings” to use the copy installed from Chrome instead (it keeps its own readings).")
            }
        }
    }

    @Composable private fun Section(t: String) = Text(t.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))

    @Composable private fun Tile(title: String, sub: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        Column(Modifier.padding(end = 8.dp, bottom = 8.dp).width(104.dp).border(1.dp, Faint, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 10.dp, vertical = 12.dp)) {
            Text(title, color = Sand, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
