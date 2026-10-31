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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

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
            prefs(ctx).edit().putBoolean("skyChoosing", true).apply()
            val ok = ctx.tryStart(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(ctx, SkyWallpaper::class.java)))
            if (!ok) ctx.tryStart(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("dune", 0)
        private fun ours(ctx: Context) = ComponentName(ctx, SkyWallpaper::class.java)

        /** Is the live sky what the lock screen shows? (Not if the lock screen keeps a picture of its own.) */
        fun liveOnLock(ctx: Context): Boolean = runCatching {
            val wm = WallpaperManager.getInstance(ctx)
            if (android.os.Build.VERSION.SDK_INT >= 34 && wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.component == ours(ctx)) return true
            isActive(ctx) && wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0   // -1: the lock screen shares the home wallpaper
        }.getOrDefault(false)

        /**
         * Android keeps a separate lock-screen picture (the old one) when a live wallpaper is set
         * for the home screen. Removing that picture makes the lock screen show the live sky too.
         */
        fun moveLiveToLock(ctx: Context): Boolean {
            if (!isActive(ctx)) return false
            if (liveOnLock(ctx)) return true
            runCatching { WallpaperManager.getInstance(ctx).clear(WallpaperManager.FLAG_LOCK) }
            return liveOnLock(ctx)
        }

        /** Back from Android's wallpaper screen: if Dune Sky was set, put it on the lock screen as well. */
        fun afterChoosing(ctx: Context) {
            val p = prefs(ctx)
            if (!p.getBoolean("skyChoosing", false)) return
            p.edit().putBoolean("skyChoosing", false).apply()
            if (!isActive(ctx) || picture(ctx)) return
            ctx.toast(if (moveLiveToLock(ctx)) "Dune Sky is on the home and lock screen"
                else "Dune Sky is set, but this phone's lock screen won't take it – turn on “Sky picture on the lock screen”")
        }

        // ---- A still picture of the sky on the lock screen, for phones whose lock screen only takes pictures.
        fun picture(ctx: Context) = prefs(ctx).getBoolean("skyLock", false)
        private val painting = AtomicBoolean(false)

        fun setPicture(ctx: Context, on: Boolean, scope: CoroutineScope) {
            prefs(ctx).edit().putBoolean("skyLock", on).putLong("skyLockAt", 0).apply()
            if (on) scope.launch { ctx.toast(if (paintLock(ctx, true)) "The sky is on your lock screen – it's redrawn as the sky and weather change" else "Couldn't set the lock screen picture") }
            else scope.launch {
                withContext(Dispatchers.IO) { runCatching { WallpaperManager.getInstance(ctx).let { if (it.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0) it.clear(WallpaperManager.FLAG_LOCK) } } }
                if (isActive(ctx)) moveLiveToLock(ctx)
            }
        }

        /** Redraws the lock-screen picture if it's on and more than a quarter of an hour old (or [force]). */
        suspend fun paintLock(ctx: Context, force: Boolean = false): Boolean {
            if (!picture(ctx)) return false
            val p = prefs(ctx)
            if (!force && System.currentTimeMillis() - p.getLong("skyLockAt", 0) < 15 * 60_000) return true
            if (!painting.compareAndSet(false, true)) return true
            try {
                return withContext(Dispatchers.Default) {
                    runCatching {
                        val app = ctx.applicationContext
                        val dm = android.util.DisplayMetrics()
                        @Suppress("DEPRECATION")
                        (app.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                            .getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(dm)
                        val w = minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(480)
                        val h = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(800)
                        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        val s = saved(app)
                        val now = System.currentTimeMillis()
                        SkyPainter(app).paint(android.graphics.Canvas(bmp), skyNow(now, s.lat, s.lon), s.weather, 0.0)
                        WallpaperManager.getInstance(app).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
                        bmp.recycle()
                        p.edit().putLong("skyLockAt", now).apply()
                        true
                    }.getOrDefault(false)
                }
            } finally { painting.set(false) }
        }

        internal class Saved(val lat: Double, val lon: Double, val weather: Weather?, val at: Long, val place: String)
        internal fun saved(ctx: Context): Saved {
            val s = prefs(ctx).getString("skyWallpaper", null) ?: return Saved(53.16, -4.37, null, 0, "")
            return runCatching {
                val o = JSONObject(s)
                Saved(o.getDouble("lat"), o.getDouble("lon"),
                    Weather(o.optInt("temp"), o.optInt("code"), o.optInt("wind"), 0, 0, null, o.optString("place"),
                        o.optInt("windDir"), o.optInt("gust"), o.optInt("cloud"), o.optDouble("precip"), o.optBoolean("day", true)),
                    o.optLong("t"), o.optString("place"))
            }.getOrDefault(Saved(53.16, -4.37, null, 0, ""))
        }
    }

    /** Draws the sky onto an Android canvas: used by the live wallpaper and the lock-screen picture. */
    internal class SkyPainter(ctx: Context) {
        private val drawScope = CanvasDrawScope()
        private val density = Density(ctx.resources.displayMetrics.density)
        private val measurer = TextMeasurer(createFontFamilyResolver(ctx), density, LayoutDirection.Ltr)
        private val label = TextStyle(color = Color(0x99D9B26A), fontSize = 10.sp)
        fun paint(c: android.graphics.Canvas, m: SkyModel, weather: Weather?, t: Double) {
            c.drawColor(android.graphics.Color.BLACK)
            drawScope.draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(c), Size(c.width.toFloat(), c.height.toFloat())) {
                runCatching { drawSky(m, measurer, label, weather, null, 0.9f, t) }
            }
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
        private val painter = SkyPainter(applicationContext)
        private val tick = object : Runnable { override fun run() { draw(); if (visible) main.postDelayed(this, 80) } }

        private fun load() {
            val o = saved(applicationContext)
            lat = o.lat; lon = o.lon; weather = o.weather
            // Weather more than an hour old: fetch it again ourselves (Dune may not have been opened).
            if (o.weather != null && System.currentTimeMillis() - o.at > 3_600_000) scope.launch {
                runCatching { Weather.fetch(lat, lon, o.place) }.getOrNull()?.let { w -> weather = w; SkyWallpaper.remember(applicationContext, lat, lon, w) }
            }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            main.removeCallbacks(tick)
            if (v) { load(); main.post(tick) }
            else if (!isPreview) scope.launch { paintLock(applicationContext) }   // screen going off: freshen the lock picture, if that's on
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
                painter.paint(c, model!!, weather, (System.currentTimeMillis() - start) / 1000.0)
            } finally { runCatching { holder.unlockCanvasAndPost(c) } }
        }
    }
}
