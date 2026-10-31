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

    val web = remember {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(act)).build()
        WebView(act).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(android.graphics.Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(r.url)
                override fun onPageFinished(v: WebView, url: String?) { here?.let { push(v, it) } }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun following(on: Boolean) { post { following = on } }
            }, "Dune")
            // Let the map have its drags; otherwise the pager would take every sideways swipe.
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            loadUrl("https://appassets.androidplatform.net/assets/map/index.html")
        }
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
            Text("Map", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.weight(1f))
            if (here != null) {
                if (!following) MapAction("centre") { web.evaluateJavascript("centre()", null); following = true }
                MapAction("share") {
                    val l = here!!
                    val text = (place?.let { "$it\n" } ?: "") + "https://maps.google.com/?q=${l.latitude},${l.longitude}"
                    act.tryStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share my location"))
                }
                MapAction("Maps ↗") {
                    val l = here!!
                    act.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${l.latitude},${l.longitude}?q=${l.latitude},${l.longitude}")))
                }
            }
        }
        Text(
            when {
                !any -> "Allow location to see where you are"
                here == null -> if (active) "Finding you…" else " "
                else -> listOfNotNull(place, latLon(here!!), "±${here!!.accuracy.toInt()} m").joinToString("  ·  ")
            },
            color = Dim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 6.dp)
        )
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
private fun MapAction(label: String, onClick: () -> Unit) = Text(label, color = Dim, fontSize = 13.sp,
    modifier = Modifier.combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp))

private fun push(web: WebView, l: Location) =
    web.evaluateJavascript("window.setPos && setPos(${l.latitude}, ${l.longitude}, ${maxOf(5f, l.accuracy)})", null)
