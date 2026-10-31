package uk.newbroman.dune

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "Dune Sky": the sky from Dune's home screen as the phone's own wallpaper, so it's there on the
 * lock screen when you wake the phone. It only draws while it's showing (about 12 frames a second
 * for the twinkling), and does nothing while the screen is off.
 */
class SkyWallpaper : WallpaperService() {
    companion object {
        /** Dune saves where you are and the latest weather here, for the wallpaper to use. */
        fun remember(ctx: Context, lat: Double, lon: Double, w: Weather) {
            ctx.getSharedPreferences("dune", 0).edit().putString("skyWallpaper", JSONObject()
                .put("lat", lat).put("lon", lon).put("t", System.currentTimeMillis())
                .put("temp", w.temp).put("code", w.code).put("wind", w.wind).put("windDir", w.windDir).put("gust", w.gust)
                .put("cloud", w.cloud).put("precip", w.precip).put("day", w.isDay).put("place", w.place).toString()).apply()
        }
        fun isActive(ctx: Context) = runCatching {
            WallpaperManager.getInstance(ctx).wallpaperInfo?.component == ComponentName(ctx, SkyWallpaper::class.java)
        }.getOrDefault(false)
        /** Opens Android's own "set wallpaper" screen with Dune Sky chosen (you pick home, lock or both). */
        fun choose(ctx: Context) {
            val ok = ctx.tryStart(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(ctx, SkyWallpaper::class.java)))
            if (!ok) ctx.tryStart(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    override fun onCreateEngine(): Engine = SkyEngine()

    private inner class SkyEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var visible = false
        private var lat = 53.16; private var lon = -4.37
        private var weather: Weather? = null
        private var model: SkyModel? = null
        private var modelMinute = -1L
        private val start = System.currentTimeMillis()
        private val drawScope = CanvasDrawScope()
        private val density = Density(resources.displayMetrics.density)
        private val measurer = TextMeasurer(createFontFamilyResolver(applicationContext), density, LayoutDirection.Ltr)
        private val label = TextStyle(color = Color(0x99D9B26A), fontSize = 10.sp)
        private val tick = object : Runnable { override fun run() { draw(); if (visible) main.postDelayed(this, 80) } }

        private fun load() {
            val s = getSharedPreferences("dune", 0).getString("skyWallpaper", null) ?: return
            runCatching {
                val o = JSONObject(s)
                lat = o.getDouble("lat"); lon = o.getDouble("lon")
                weather = Weather(o.optInt("temp"), o.optInt("code"), o.optInt("wind"), 0, 0, null, o.optString("place"),
                    o.optInt("windDir"), o.optInt("gust"), o.optInt("cloud"), o.optDouble("precip"), o.optBoolean("day", true))
                // Weather more than an hour old: fetch it again ourselves (Dune may not have been opened).
                if (System.currentTimeMillis() - o.optLong("t") > 3_600_000) scope.launch {
                    runCatching { Weather.fetch(lat, lon, o.optString("place")) }.getOrNull()?.let { w -> weather = w; SkyWallpaper.remember(applicationContext, lat, lon, w) }
                }
            }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            main.removeCallbacks(tick)
            if (v) { load(); main.post(tick) }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { super.onSurfaceChanged(holder, format, width, height); draw() }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { visible = false; main.removeCallbacks(tick); super.onSurfaceDestroyed(holder) }
        override fun onDestroy() { main.removeCallbacks(tick); scope.cancel(); super.onDestroy() }

        private fun draw() {
            val holder = surfaceHolder
            val c = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                val minute = System.currentTimeMillis() / 60_000
                if (minute != modelMinute || model == null) { model = skyNow(minute * 60_000 + 30_000, lat, lon); modelMinute = minute }
                c.drawColor(android.graphics.Color.BLACK)
                val m = model!!
                drawScope.draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(c), Size(c.width.toFloat(), c.height.toFloat())) {
                    runCatching { drawSky(m, measurer, label, weather, null, 0.9f, (System.currentTimeMillis() - start) / 1000.0) }
                }
            } finally { runCatching { holder.unlockCanvasAndPost(c) } }
        }
    }
}
