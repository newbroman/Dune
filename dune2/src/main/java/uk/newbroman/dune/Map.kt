@file:OptIn(ExperimentalFoundationApi::class)

package uk.newbroman.dune

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

private fun hasFine(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
private fun hasAny(ctx: Context) = hasFine(ctx) ||
    ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** A choice in the map page's menu: a Mapy.com map (its own site) or a tile map drawn by Dune. */
data class MapSource(val id: String, val label: String, val site: ((Location?) -> String)? = null,
                     val layer: String? = null, val radar: Boolean = false)

val MAP_SOURCES = listOf(
    MapSource("mapy-basic", "Mapy.com", site = { mapyUrl("basic", it) }),
    MapSource("mapy-outdoor", "Mapy.com · Outdoor", site = { mapyUrl("outdoor", it) }),
    MapSource("mapy-aerial", "Mapy.com · Aerial", site = { mapyUrl("aerial", it) }),
    MapSource("mapy-winter", "Mapy.com · Winter", site = { mapyUrl("winter", it) }),
    MapSource("mapy-traffic", "Mapy.com · Traffic", site = { mapyUrl("traffic", it) }),
    MapSource("radar", "Rain radar (RainViewer)", layer = "dark", radar = true),
    MapSource("windy", "Windy · radar and forecast", site = { windyUrl(it) }),
    MapSource("dark", "Dune dark", layer = "dark"),
    MapSource("osm", "OpenStreetMap", layer = "osm"),
    MapSource("topo", "OpenTopoMap (contours)", layer = "topo"),
    MapSource("cycle", "CyclOSM (cycling)", layer = "cycle"),
    MapSource("sat", "Satellite (Esri)", layer = "sat"),
)

/** Windy's free embeddable map, showing the radar (animated) with a forecast at your spot. */
fun windyUrl(l: Location?): String {
    val (lat, lon) = l?.let { it.latitude to it.longitude } ?: (54.4 to -3.0)
    return String.format(Locale.US, "https://embed.windy.com/embed2.html?lat=%.4f&lon=%.4f&detailLat=%.4f&detailLon=%.4f" +
        "&zoom=%d&level=surface&overlay=radar&product=radar&menu=&message=true&marker=true&calendar=now&type=map" +
        "&location=coordinates&detail=&metricWind=mph&metricTemp=%%C2%%B0C&radarRange=-1", lat, lon, lat, lon, if (l != null) 8 else 5)
}

/** Mapy.com's documented link: centre is longitude first. */
fun mapyUrl(mapset: String, l: Location?) = "https://mapy.com/fnc/v1/showmap?mapset=$mapset" +
    (l?.let { String.format(Locale.US, "&center=%.6f,%.6f&zoom=16&marker=true", it.longitude, it.latitude) } ?: "")

private const val LEAFLET = "https://appassets.androidplatform.net/assets/map/index.html"
private fun leafletUrl(s: MapSource) = "$LEAFLET?layer=${s.layer}" + if (s.radar) "&radar=1" else ""

/** "53.13702, -4.36712" – five decimals is about a metre. */
fun latLon(l: Location) = String.format(Locale.UK, "%.5f, %.5f", l.latitude, l.longitude)

/**
 * The map page (three swipes left): where you are, on a dark map. The GPS only runs while the
 * page is on screen. Swipe from the left edge to leave; drag anywhere else moves the map.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "MissingPermission")
@Composable
fun MapPage(vm: LauncherVM, active: Boolean) {
    val act = LocalContext.current as MainActivity
    var granted by remember { mutableIntStateOf(0) }
    var mapCrashed by remember { mutableIntStateOf(0) }   // the map page's renderer died: a fresh WebView is made
    val fine = remember(granted, vm.resumeTick) { hasFine(act) }
    val any = remember(granted, vm.resumeTick) { hasAny(act) }
    var here by remember { mutableStateOf<Location?>(null) }
    var place by remember { mutableStateOf<String?>(null) }
    var following by remember { mutableStateOf(true) }
    val prefs = remember { act.getSharedPreferences("dune", 0) }
    var source by remember { mutableStateOf(MAP_SOURCES.firstOrNull { it.id == prefs.getString("mapSource", "mapy-basic") } ?: MAP_SOURCES[0]) }
    var picking by remember { mutableStateOf(false) }
    var align by remember { mutableStateOf(prefs.getBoolean("mapAlign", false)) }
    var lastPkg by remember { mutableStateOf(prefs.getString("lastMapApp", null)) }
    val lastMapApp = remember(lastPkg) { lastPkg?.let { p -> mapApps(act, vm).firstOrNull { it.pkg == p } } }
    // Mapy.com is a whole site, so it is (re)loaded centred on you once a fix arrives.
    var siteCentred by remember { mutableStateOf(false) }

    var showing by remember { mutableStateOf<SavedTrack?>(null) }
    var showingPts by remember { mutableStateOf<List<TrackPoint>?>(null) }
    // What's drawn on Dune's own map is kept here as state, and redrawn whenever that map (re)loads –
    // so nothing is lost while it's loading, or when you switch to Mapy.com and back.
    var placeShown by remember { mutableStateOf<Found?>(null) }
    var routeShown by remember { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    val ready = remember { booleanArrayOf(false) }   // Dune's own map loaded and ready for drawing
    // GIS: layers, their load status, the survey, and the OS grid reference of where you are.
    var layers by remember { mutableStateOf(Gis.load(act)) }
    val layerStatus = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var surveyCount by remember { mutableIntStateOf(0) }
    var grid by remember { mutableStateOf<String?>(null) }
    var gridOn by remember { mutableStateOf(prefs.getBoolean("mapGrid", true)) }
    var measuring by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var surveyOpen by remember { mutableStateOf(false) }
    val exportToQField = remember { booleanArrayOf(false) }
    val gisScope = androidx.compose.runtime.rememberCoroutineScope()
    // The bridge below is only answered while Dune's own map is showing, not Mapy.com or Windy.
    val ownPage = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val pendingJs = remember { ArrayList<String>() }   // map calls waiting for Dune's own map to load
    val web = remember(mapCrashed) {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(act))
            .addPathHandler("/gis/", WebViewAssetLoader.InternalStoragePathHandler(act, Gis.dir(act))).build()
        WebView(act).apply {
            // Fill the page. Without this the WebView sizes itself to its content, and a full-height
            // map has no content height – it collapses to nothing (the "black box").
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setGeolocationEnabled(true)
            settings.userAgentString = settings.userAgentString + " Dune (github.com/newbroman/Dune)"   // tile servers ask apps to say who they are
            setBackgroundColor(android.graphics.Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(r.url)
                override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) { ready[0] = false; ownPage.set(url?.startsWith(LEAFLET) == true) }
                override fun onPageFinished(v: WebView, url: String?) {
                    ready[0] = url?.startsWith(LEAFLET) == true
                    if (url?.startsWith(LEAFLET) == true) {
                        here?.let { push(v, it) }; v.evaluateJavascript("setAlign($align)", null)
                        v.evaluateJavascript("setTrack(${trackJs(Tracks.points.value)})", null)
                        showingPts?.let { v.evaluateJavascript("showTrack(${trackJs(it)})", null) }   // a saved track asked for before the map loaded
                        placeShown?.let { f -> v.evaluateJavascript("showPlace(${f.lat},${f.lon},${org.json.JSONObject.quote(f.name)})", null) }
                        routeShown?.let { r -> v.evaluateJavascript("showRoute(${routeJs(r)})", null) }
                        v.evaluateJavascript("gisSetLayers(${Gis.toJs(layers)})", null)
                        Gis.readSurvey(act)?.let { v.evaluateJavascript("gisSetSurvey(${org.json.JSONObject.quote(it)})", null) }
                        pendingJs.forEach { v.evaluateJavascript(it, null) }; pendingJs.clear()
                    }
                }
                override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
                    // The map's page ran out of memory (a huge layer): switch file layers off so it can't loop, and say so.
                    ready[0] = false
                    android.os.Handler(Looper.getMainLooper()).post { mapCrashed++ }
                    return true
                }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val h = r.url.host ?: return false
                    if (h.endsWith("mapy.com") || h.endsWith("mapy.cz") || h.endsWith("seznam.cz") || h.endsWith("windy.com") || h == "appassets.androidplatform.net") return false
                    act.tryStart(Intent(Intent.ACTION_VIEW, r.url)); return true
                }
            }
            // Mapy.com's own "locate me" button: allowed when Dune itself has location.
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String, cb: android.webkit.GeolocationPermissions.Callback) =
                    cb.invoke(origin, hasAny(act), false)
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun following(on: Boolean) { post { following = on } }
                @JavascriptInterface fun grid(g: String) { if (!ownPage.get()) return; post { grid = g.ifEmpty { null } } }
                @JavascriptInterface fun layerStatus(id: String, s: String) { if (!ownPage.get()) return; post { layerStatus[id] = s } }
                @JavascriptInterface fun surveyCount(n: Int) { if (!ownPage.get()) return; post { surveyCount = n } }
                @JavascriptInterface fun measuring(on: Boolean) { if (!ownPage.get()) return; post { measuring = on } }
                @JavascriptInterface fun toast(s: String) { if (!ownPage.get()) return; post { act.toast(s) } }
                @JavascriptInterface fun gisReady() {}
                @JavascriptInterface fun layerLoaded(id: String) {}
                @JavascriptInterface fun copy(s: String) { if (!ownPage.get()) return; post {
                    act.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Grid reference", s))
                    act.toast("Copied") } }
                @JavascriptInterface fun surveySaved(json: String) { if (!ownPage.get()) return; Gis.writeSurvey(act, json) }
                @JavascriptInterface fun openLogger(well: String) { if (!ownPage.get()) return; post { openWellLogger(vm, act, well) } }
                @JavascriptInterface fun saveFile(name: String, mime: String, b64: String) { if (!ownPage.get()) return; post {
                    gisScope.launch {
                        val bytes = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
                        val u = bytes?.let { Gis.saveExport(act, name, mime, it) }
                        when {
                            u == null -> { exportToQField[0] = false; act.toast("Couldn't save $name") }
                            exportToQField[0] -> { exportToQField[0] = false; Gis.sendToQField(act, u, mime) }
                            else -> { act.toast("Saved to Downloads/Dune: $name"); Gis.share(act, u, mime, "Share the survey") }
                        }
                    }
                } }
            }, "Dune")
            // Let the map have its drags; otherwise the pager would take every sideways swipe.
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            loadUrl(source.site?.invoke(null) ?: leafletUrl(source))
        }
    }
    fun show(s: MapSource) {
        source = s; prefs.edit().putString("mapSource", s.id).apply(); following = true
        if (s.site != null) { web.loadUrl(s.site.invoke(here)); siteCentred = here != null }
        else if (web.url?.startsWith(LEAFLET) == true) web.evaluateJavascript("setLayer('${s.layer}'); setRadar(${s.radar})", null)
        else web.loadUrl(leafletUrl(s))
    }
    /** Runs on Dune's own map, switching to it (and waiting for it to load) if a website map is showing. */
    fun dune(js: String) {
        if (ready[0]) { web.evaluateJavascript(js, null); return }
        pendingJs += js
        if (source.site != null) show(MAP_SOURCES.first { it.id == "dark" })
    }
    fun setLayers(l: List<Gis.Layer>) {
        layers = l; Gis.save(act, l)
        if (ready[0]) web.evaluateJavascript("gisSetLayers(${Gis.toJs(l)})", null)
        else if (source.site != null && l.any { it.on }) show(MAP_SOURCES.first { it.id == "dark" })   // drawn once Dune's map loads
    }
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        gisScope.launch {
            val (added, bad) = Gis.import(act, uris, layers)
            if (bad.isNotEmpty()) act.toast("Not a map file Dune can read: " + bad.joinToString(", "))
            if (added.isEmpty()) return@launch
            setLayers(layers + added.mapIndexed { i, a -> if (i == 0) a else a })
            act.toast("Added " + added.joinToString(", ") { it.name })
            web.postDelayed({ if (ready[0]) web.evaluateJavascript("gisZoom('${added.first().id}')", null) }, 1200)
        }
    }
    // The track being recorded: draw new points as they arrive.
    val recording by Tracks.recording.collectAsState()
    val trackPts by Tracks.points.collectAsState()
    var drawn by remember { mutableIntStateOf(0) }
    LaunchedEffect(trackPts) {
        if (trackPts.size < drawn) { web.evaluateJavascript("setTrack(${trackJs(trackPts)})", null) }
        else trackPts.drop(drawn).forEach { p -> web.evaluateJavascript("addTrackPoint(${p.lat},${p.lon})", null) }
        drawn = trackPts.size
    }
    val savedTick by Tracks.saved.collectAsState()
    var trackMenu by remember { mutableStateOf<List<SavedTrack>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val trackScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(vm.mapTrackRequest) {
        val path = vm.mapTrackRequest ?: return@LaunchedEffect
        vm.mapTrackRequest = null   // (restarts this effect, so the work below runs in its own scope)
        trackScope.launch {
        val f = java.io.File(path)
        val (pts, info) = withContext(Dispatchers.IO) { runCatching { TrackMath.parseGpx(f.readText()) }.getOrDefault(emptyList()) to Tracks.list(act).firstOrNull { it.file == f } }
        showing = info; showingPts = pts
        if (source.site != null) show(MAP_SOURCES.first { it.id == "dark" })   // drawn when the map finishes loading
        else if (ready[0]) web.evaluateJavascript("showTrack(${trackJs(pts)})", null)
        }
    }
    LaunchedEffect(mapCrashed) {
        if (mapCrashed == 0) return@LaunchedEffect
        setLayers(layers.map { if (it.kind == "file") it.copy(on = false) else it })
        act.toast("The map ran out of memory – file layers are switched off. Turn them on one at a time.")
    }
    LaunchedEffect(vm.gisImport) {
        val u = vm.gisImport ?: return@LaunchedEffect
        vm.gisImport = null
        importFiles(u)
    }
    LaunchedEffect(vm.mapRequest) {
        vm.mapRequest?.let { id -> MAP_SOURCES.firstOrNull { it.id == id }?.let { show(it) }; vm.mapRequest = null }
    }
    LaunchedEffect(here != null) {
        if (here != null && source.site != null && !siteCentred) { web.loadUrl(source.site!!.invoke(here)); siteCentred = true }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }

    // Location updates only while this page is showing.
    DisposableEffect(active, any) {
        val lm = act.getSystemService(LocationManager::class.java)
        val listener = LocationListener { l ->
            if (here == null || l.accuracy <= (here?.accuracy ?: 9e9f) * 2 || l.time - (here?.time ?: 0) > 20_000) { here = l; push(web, l) }
        }
        if (active && any && lm != null) {
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
                else { if (fine) add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER) }
            }
            providers.forEach { p ->
                runCatching {
                    lm.getLastKnownLocation(p)?.let { l -> if (here == null) { here = l; push(web, l) } }
                    lm.requestLocationUpdates(p, 3000L, 3f, listener, Looper.getMainLooper())
                }
            }
        }
        onDispose { runCatching { lm?.removeUpdates(listener) } }
    }
    // The street or place name, looked up now and then.
    LaunchedEffect(here?.let { "%.3f,%.3f".format(it.latitude, it.longitude) }) {
        val l = here ?: return@LaunchedEffect
        place = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(act, Locale.UK).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(a.thoroughfare ?: a.featureName, a.locality ?: a.subAdminArea).distinct().joinToString(", ")
                }
            }.getOrNull()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Text(source.label + "  ▾", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.Light, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.combinedClickable { picking = true }.padding(vertical = 8.dp))
                androidx.compose.material3.DropdownMenu(picking, onDismissRequest = { picking = false }) {
                    MAP_SOURCES.forEach { s ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text((if (s.id == source.id) "● " else "    ") + s.label) },
                            onClick = { picking = false; show(s) })
                    }
                }
            }
            // Track: record where you go (keeps going with the screen off), or look at a saved one.
            MapAction(if (recording) "■ stop" else "● track") {
                if (recording) { Tracks.stop(act); act.toast("Track saved") }
                else if (!fine) act.requestPerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted++ }
                else {
                    act.requestPerm(Manifest.permission.POST_NOTIFICATIONS) { }
                    if (source.site != null) show(MAP_SOURCES.first { it.id == "dark" })   // Mapy.com's own site can't show Dune's line
                    Tracks.start(act); drawn = 0
                }
            }
            Box {
                MapAction("tracks ▾") { scope.launch { trackMenu = withContext(Dispatchers.IO) { Tracks.list(act) } } }
                androidx.compose.material3.DropdownMenu(trackMenu != null, onDismissRequest = { trackMenu = null }) {
                    if (trackMenu?.isEmpty() == true) androidx.compose.material3.DropdownMenuItem(text = { Text("No saved tracks yet") }, onClick = { trackMenu = null })
                    if (showing != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Hide “${showing!!.name}”") }, onClick = {
                        trackMenu = null; showing = null; showingPts = null; web.evaluateJavascript("hideTrack()", null) })
                    trackMenu?.forEach { tr ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(tr.name.removePrefix("Track ") + "\n" + String.format(Locale.UK, "%.2f km · %d min", tr.km, tr.minutes)) },
                            trailingIcon = { Row {
                                Text("share", color = Dim, fontSize = 13.sp, modifier = Modifier.combinedClickable { trackMenu = null; Tracks.share(act, tr.file) }.padding(8.dp))
                                Text("✕", color = Dim, fontSize = 14.sp, modifier = Modifier.combinedClickable {
                                    Tracks.delete(tr.file); if (showing?.file == tr.file) { showing = null; showingPts = null; web.evaluateJavascript("hideTrack()", null) }
                                    scope.launch { trackMenu = withContext(Dispatchers.IO) { Tracks.list(act) } }
                                }.padding(8.dp))
                            } },
                            onClick = {
                                trackMenu = null; showing = tr
                                scope.launch {
                                    val pts = withContext(Dispatchers.IO) { runCatching { TrackMath.parseGpx(tr.file.readText()) }.getOrDefault(emptyList()) }
                                    showingPts = pts
                                    if (source.site != null) show(MAP_SOURCES.first { it.id == "dark" })   // drawn on load
                                    else if (ready[0]) web.evaluateJavascript("showTrack(${trackJs(pts)})", null)
                                }
                            })
                    }
                }
            }
            // Solstice and lunar alignments, as on the Spheres weather page (Dune's own maps only).
            MapAction(if (align) "☀ on" else "☀ lines") {
                align = !align; prefs.edit().putBoolean("mapAlign", align).apply()
                if (source.site != null) show(MAP_SOURCES.first { it.id == "dark" }.takeIf { align } ?: source)
                else web.evaluateJavascript("setAlign($align)", null)
            }
        }
        PlaceSearch(vm, here, active,
            onPlace = { f ->
                placeShown = f; routeShown = null
                if (source.site != null) show(MAP_SOURCES.first { it.id == "dark" })   // Mapy.com's site can't show Dune's marker
                else if (ready[0]) web.evaluateJavascript("clearPlace();showPlace(${f.lat},${f.lon},${org.json.JSONObject.quote(f.name)})", null)
            },
            onRoute = { r -> routeShown = r; if (ready[0]) web.evaluateJavascript("showRoute(${routeJs(r)})", null) },
            onClear = { placeShown = null; routeShown = null; if (ready[0]) web.evaluateJavascript("clearPlace()", null) })
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                recording -> "● Recording  " + Tracks.stats(trackPts)
                showing != null -> "${showing!!.name.removePrefix("Track ")} · " + String.format(Locale.UK, "%.2f km · %d min", showing!!.km, showing!!.minutes)
                !any -> "Allow location to see where you are"
                here == null -> if (active) "Finding you…" else " "
                else -> listOfNotNull(place, if (gridOn && grid != null) grid else latLon(here!!), "±${here!!.accuracy.toInt()} m").joinToString("  ·  ")
            },
            color = Dim, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MapAction("layers" + layers.count { it.on }.let { if (it > 0) " ($it)" else "" } + " ▾") { showLayers = true }
            Box {
                MapAction(if (measuring) "■ measuring" else "survey" + (if (surveyCount > 0) " ($surveyCount)" else "") + " ▾") {
                    if (measuring) web.evaluateJavascript("gisEndMeasure()", null) else surveyOpen = true
                }
                SurveyMenu(surveyOpen, surveyCount, gridOn, onDismiss = { surveyOpen = false }, js = { dune(it) },
                    onGrid = { gridOn = !gridOn; prefs.edit().putBoolean("mapGrid", gridOn).apply() },
                    onExport = { fmt, qf -> exportToQField[0] = qf; dune("gisExport('$fmt')") })
            }
            Text("wells", color = Dim, fontSize = 13.sp, modifier = Modifier.combinedClickable(
                onClick = { openWellLogger(vm, act) }, onLongClick = { openWellLogger(vm, act, installed = true) }).padding(horizontal = 8.dp, vertical = 10.dp))
            if (here != null) {
                if (source.site != null) MapAction("centre") { web.loadUrl(source.site!!.invoke(here)) }
                else if (!following) MapAction("centre") { web.evaluateJavascript("centre()", null); following = true }
                MapAction("share") {
                    val l = here!!
                    val text = (place?.let { "$it\n" } ?: "") + "https://maps.google.com/?q=${l.latitude},${l.longitude}"
                    act.tryStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share my location"))
                }
            }
            // Map apps on the phone: always here, with the last one used as a one-tap shortcut.
            lastMapApp?.let { m -> MapAction("↗ " + m.label.take(12)) { openIn(act, m, here, place); prefs.edit().putString("lastMapApp", m.pkg).apply() } }
            Box {
                var apps by remember { mutableStateOf<List<MapApp>?>(null) }
                MapAction("apps ▾") { apps = mapApps(act, vm) }
                androidx.compose.material3.DropdownMenu(apps != null, onDismissRequest = { apps = null }) {
                    if (apps?.isEmpty() == true) androidx.compose.material3.DropdownMenuItem(text = { Text("No map apps found") }, onClick = { apps = null })
                    apps?.forEach { m ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(m.label + if (m.geo) "" else "  (opens the app)") },
                            leadingIcon = { m.icon?.let { androidx.compose.foundation.Image(it, null, Modifier.padding(0.dp).width(28.dp)) } },
                            onClick = { apps = null; openIn(act, m, here, place); prefs.edit().putString("lastMapApp", m.pkg).apply(); lastPkg = m.pkg })
                    }
                }
            }
        }
        if (showLayers) LayersSheet(layers, layerStatus, onChange = { setLayers(it) },
            onZoom = { l -> showLayers = false; web.postDelayed({ if (ready[0]) web.evaluateJavascript("gisZoom('${l.id}')", null) }, if (ready[0]) 300 else 2500) },
            onAddFiles = { act.pickFiles { importFiles(it) } }, onClose = { showLayers = false })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            androidx.compose.runtime.key(web) { AndroidView(factory = { web }, modifier = Modifier.fillMaxSize()) }
            // A strip down the left edge still swipes back to the inbox.
            Box(Modifier.width(26.dp).fillMaxHeight().pointerInput(Unit) { })
            // …and one down the right edge, since in Dune 2 the map may have hubs on both sides.
            Box(Modifier.align(Alignment.CenterEnd).width(26.dp).fillMaxHeight().pointerInput(Unit) { })
            if (!fine) Column(Modifier.align(Alignment.Center).background(Color(0xE6000000)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (any) "Only your rough area is allowed" else "Dune needs your location for the map", color = Fg, fontSize = 16.sp)
                Text("Allow precise location", color = Sand, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp)
                    .combinedClickable { act.requestPerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted++ } }.padding(8.dp))
            }
        }
    }
}

@Composable
internal fun MapAction(label: String, onClick: () -> Unit) = Text(label, color = Dim, fontSize = 13.sp,
    modifier = Modifier.combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp))

private fun push(web: WebView, l: Location) =
    web.evaluateJavascript("window.setPos && setPos(${l.latitude}, ${l.longitude}, ${maxOf(5f, l.accuracy)}, ${if (l.hasAltitude()) l.altitude else "null"})", null)

/** Weather widgets (Weawow or any) on the weather hub, under Dune's own forecast. */
fun androidx.compose.foundation.lazy.LazyListScope.weatherWidgets(vm: LauncherVM, act: MainActivity) {
    val placed = vm.store.weatherPage.value.split(',').mapNotNull { e ->
        e.split(':').let { p -> p[0].toIntOrNull()?.let { it to (p.getOrNull(1)?.toIntOrNull() ?: 200) } }
    }
    val am = android.appwidget.AppWidgetManager.getInstance(act)
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            vm.weatherApp?.let { w -> Chip("Open Weawow") { vm.launch(w) } }
            Chip("+ add a widget") { vm.addWeatherPageWidget() }
        }
    }
    placed.forEach { (id, h) ->
        item(key = "ww$id") {
            val info = remember(id) { am.getAppWidgetInfo(id) }
            if (info != null) {
                Box(Modifier.padding(top = 8.dp)) { HostedWidget(act, id, info, h) }
                Row {
                    MapAction("taller") { act.editWeatherPage(id, 80) }
                    MapAction("shorter") { act.editWeatherPage(id, -80) }
                    MapAction("remove") { act.editWeatherPage(id, null) }
                }
            }
        }
    }
}

/** A map app on the phone. [geo] apps can be handed your position; others are just opened. */
data class MapApp(val pkg: String, val label: String, val geo: Boolean, val icon: androidx.compose.ui.graphics.ImageBitmap?)

private val MAP_WORDS = listOf("map", "mapy", "waze", "earth", "komoot", "outdoor", "hiking", "hike", "trail", "osm",
    "turyst", "navig", "gps", "ordnance", "os maps", "citymapper", "here we", "qfield", "qgis", "field")

/** Every app that can show a place (geo: links), plus installed apps that look like maps by name. */
fun mapApps(ctx: Context, vm: LauncherVM): List<MapApp> {
    val pm = ctx.packageManager
    fun icon(pkg: String) = runCatching {
        val d = pm.getApplicationIcon(pkg)
        android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
            val c = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c)
        }.asImageBitmap()
    }.getOrNull()
    val geo = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=0,0")), PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName }.distinct().filter { it != ctx.packageName }
    val byName = vm.apps.filter { a -> val l = a.label.lowercase(); MAP_WORDS.any { l.contains(it) } }.map { it.pkg }
    return (geo + byName).distinct().map { p ->
        MapApp(p, vm.labelFor(p), p in geo, icon(p))
    }.sortedWith(compareBy({ !it.geo }, { it.label.lowercase() }))
}

fun openIn(ctx: Context, m: MapApp, l: Location?, place: String?) {
    val q = l?.let { "${it.latitude},${it.longitude}" + (place?.let { p -> "(" + Uri.encode(p) + ")" } ?: "") }
    val i = if (m.geo && l != null) Intent(Intent.ACTION_VIEW, Uri.parse("geo:${l.latitude},${l.longitude}?q=$q")).setPackage(m.pkg)
            else ctx.packageManager.getLaunchIntentForPackage(m.pkg)
    if (i != null) ctx.tryStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun trackJs(p: List<TrackPoint>) = p.joinToString(",", "[", "]") { "[${it.lat},${it.lon}]" }

private fun routeJs(r: List<Pair<Double, Double>>) = r.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }

/** Find a place, show it, get walking / cycling / driving directions – Dune's own, no app needed. */
@Composable
private fun PlaceSearch(vm: LauncherVM, here: Location?, active: Boolean, onPlace: (Found) -> Unit, onRoute: (List<Pair<Double, Double>>) -> Unit, onClear: () -> Unit) {
    val act = LocalContext.current as MainActivity
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Found>?>(null) }
    var picked by remember { mutableStateOf<Found?>(null) }
    var routeInfo by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("foot") }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(active) { if (!active) focus.clearFocus() }
    val search = {
        focus.clearFocus()
        if (q.isNotBlank()) scope.launch {
            val r = Routes.search(q.trim(), here?.latitude, here?.longitude)
            results = r ?: emptyList()
            if (r == null) act.toast("Search failed – check the connection") else if (r.isEmpty()) act.toast("Nothing found")
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 2.dp).border(1.dp, Faint, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
        .padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicTextField(q, { q = it }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Fg, fontSize = 15.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Sand),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
            modifier = Modifier.weight(1f).padding(vertical = 9.dp),
            decorationBox = { inner -> Box { if (q.isEmpty()) Text("Find a place", color = Faint, fontSize = 15.sp); inner() } })
        if (q.isNotEmpty() || picked != null) Text("✕", color = Dim, fontSize = 14.sp, modifier = Modifier.combinedClickable {
            job?.cancel(); q = ""; results = null; picked = null; routeInfo = null; focus.clearFocus(); onClear()
        }.padding(10.dp))
        else Text("🎤", fontSize = 15.sp, modifier = Modifier.combinedClickable { act.listen("Where?") { it?.let { t -> q = t; search() } } }.padding(10.dp))
    }
    results?.let { list ->
        // A short scrolling list, so the map keeps its room even with the keyboard up.
        Column(Modifier.padding(start = 24.dp, end = 12.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            list.forEach { f ->
                Column(Modifier.fillMaxWidth().combinedClickable {
                    job?.cancel(); picked = f; results = null; routeInfo = null; focus.clearFocus(); onPlace(f)
                }.padding(vertical = 6.dp)) {
                    Text(f.name, color = Fg, fontSize = 15.sp, maxLines = 1)
                    if (f.detail.isNotBlank()) Text(f.detail, color = Dim, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
    picked?.let { f ->
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("foot" to "🚶", "bike" to "🚲", "car" to "🚗").forEach { (m, icon) ->
                MapAction((if (mode == m && routeInfo != null) "● " else "") + icon) {
                    val from = here ?: run { act.toast("Waiting for your location…"); return@MapAction }
                    mode = m; routeInfo = "routing…"
                    job?.cancel()
                    job = scope.launch {
                        val r = Routes.route(from.latitude, from.longitude, f.lat, f.lon, m)
                        if (picked != f) return@launch   // you've moved on to somewhere else
                        if (r == null) { routeInfo = "no route found"; return@launch }
                        routeInfo = String.format(Locale.UK, "%.1f km · %s", r.km, if (r.minutes >= 60) "${r.minutes / 60} h ${r.minutes % 60} min" else "${r.minutes} min")
                        onRoute(r.points)
                    }
                }
            }
            Text(routeInfo ?: f.name, color = if (routeInfo != null) Sand else Dim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp))
            // Turn-by-turn is best left to a navigation app: hand the destination over.
            MapAction("go ↗") {
                val gm = act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${f.lat},${f.lon}&mode=" + when (mode) { "foot" -> "w"; "bike" -> "b"; else -> "d" }))
                    .setPackage("com.google.android.apps.maps"))
                if (!gm) act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${f.lat},${f.lon}?q=${f.lat},${f.lon}(" + Uri.encode(f.name) + ")")))
            }
        }
    }
}
