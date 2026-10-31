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

    val web = remember {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(act)).build()
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
                override fun onPageFinished(v: WebView, url: String?) {
                    if (url?.startsWith(LEAFLET) == true) { here?.let { push(v, it) }; v.evaluateJavascript("setAlign($align)", null) }
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
    LaunchedEffect(vm.mapRequest) {
        vm.mapRequest?.let { id -> MAP_SOURCES.firstOrNull { it.id == id }?.let { show(it) }; vm.mapRequest = null }
    }
    LaunchedEffect(here != null) {
        if (here != null && source.site != null && !siteCentred) { web.loadUrl(source.site!!.invoke(here)); siteCentred = true }
    }
    DisposableEffect(Unit) { onDispose { web.destroy() } }

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
            Box {
                Text(source.label + "  ▾", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.Light, maxLines = 1,
                    modifier = Modifier.combinedClickable { picking = true }.padding(vertical = 8.dp))
                androidx.compose.material3.DropdownMenu(picking, onDismissRequest = { picking = false }) {
                    MAP_SOURCES.forEach { s ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text((if (s.id == source.id) "● " else "    ") + s.label) },
                            onClick = { picking = false; show(s) })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Solstice and lunar alignments, as on the Spheres weather page (Dune's own maps only).
            MapAction(if (align) "☀ on" else "☀ lines") {
                align = !align; prefs.edit().putBoolean("mapAlign", align).apply()
                if (source.site != null) show(MAP_SOURCES.first { it.id == "dark" }.takeIf { align } ?: source)
                else web.evaluateJavascript("setAlign($align)", null)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                !any -> "Allow location to see where you are"
                here == null -> if (active) "Finding you…" else " "
                else -> listOfNotNull(place, latLon(here!!), "±${here!!.accuracy.toInt()} m").joinToString("  ·  ")
            },
            color = Dim, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
            // A strip down the left edge still swipes back to the inbox.
            Box(Modifier.width(26.dp).fillMaxHeight().pointerInput(Unit) { })
            if (!fine) Column(Modifier.align(Alignment.Center).background(Color(0xE6000000)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (any) "Only your rough area is allowed" else "Dune needs your location for the map", color = Fg, fontSize = 16.sp)
                Text("Allow precise location", color = Sand, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp)
                    .combinedClickable { act.requestPerm(Manifest.permission.ACCESS_FINE_LOCATION) { granted++ } }.padding(8.dp))
            }
        }
    }
}

@Composable
internal fun MapAction(label: String, onClick: () -> Unit) = Text(label, color = Dim, fontSize = 13.sp,
    modifier = Modifier.combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp))

private fun push(web: WebView, l: Location) =
    web.evaluateJavascript("window.setPos && setPos(${l.latitude}, ${l.longitude}, ${maxOf(5f, l.accuracy)})", null)

/** The weather page (four swipes left): your Weawow widgets, as big as you like. */
@Composable
fun WeatherPage(vm: LauncherVM, gestures: androidx.compose.ui.input.nestedscroll.NestedScrollConnection) {
    val act = LocalContext.current as MainActivity
    val placed = vm.store.weatherPage.value.split(',').mapNotNull { e ->
        e.split(':').let { p -> p[0].toIntOrNull()?.let { it to (p.getOrNull(1)?.toIntOrNull() ?: 200) } }
    }
    val am = android.appwidget.AppWidgetManager.getInstance(act)
    SidePage("Weather", gestures) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                vm.weatherApp?.let { w -> Chip("Open Weawow") { vm.launch(w) } }
                Chip("+ add a Weawow widget") { vm.addWeatherPageWidget() }
            }
            Row {
                Chip("🌧 Rain radar") { vm.mapRequest = "radar"; vm.jumpHub = "map" }
                Chip("Windy") { vm.mapRequest = "windy"; vm.jumpHub = "map" }
            }
            if (placed.isEmpty()) Note("Add one or more Weawow widgets – the large forecast and radar ones work well here. " +
                "If Weawow isn't installed, any app's widgets are offered instead.")
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
}

/** A map app on the phone. [geo] apps can be handed your position; others are just opened. */
data class MapApp(val pkg: String, val label: String, val geo: Boolean, val icon: androidx.compose.ui.graphics.ImageBitmap?)

private val MAP_WORDS = listOf("map", "mapy", "waze", "earth", "komoot", "outdoor", "hiking", "hike", "trail", "osm",
    "turyst", "navig", "gps", "ordnance", "os maps", "citymapper", "here we")

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
