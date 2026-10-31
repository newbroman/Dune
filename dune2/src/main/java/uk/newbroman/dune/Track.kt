package uk.newbroman.dune

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on a track: degrees, metres above sea level (or null), milliseconds since 1970. */
data class TrackPoint(val lat: Double, val lon: Double, val ele: Double?, val time: Long)

/** A saved track (a GPX file in Dune's own storage). */
data class SavedTrack(val file: File, val name: String, val start: Long, val km: Double, val minutes: Long)

/** Track maths and GPX, kept free of Android so they can be unit-tested. */
object TrackMath {
    /** Great-circle distance in metres. */
    fun metres(a: TrackPoint, b: TrackPoint): Double {
        val r = 6371000.0; val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
    fun km(points: List<TrackPoint>) = points.zipWithNext { a, b -> metres(a, b) }.sum() / 1000
    fun minutes(points: List<TrackPoint>) = if (points.size < 2) 0L else (points.last().time - points.first().time) / 60_000
    /** Metres climbed (ignoring wobbles under 3 m, as GPS height is noisy). */
    fun climb(points: List<TrackPoint>): Double {
        var up = 0.0; var ref = points.firstOrNull { it.ele != null }?.ele ?: return 0.0
        points.mapNotNull { it.ele }.forEach { e -> if (e - ref > 3) { up += e - ref; ref = e } else if (ref - e > 3) ref = e }
        return up
    }

    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun gpx(name: String, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Dune" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <trk><name>").append(esc(name)).append("</name><trkseg>\n")
        points.forEach { p ->
            append(String.format(Locale.US, "    <trkpt lat=\"%.7f\" lon=\"%.7f\">", p.lat, p.lon))
            p.ele?.let { append(String.format(Locale.US, "<ele>%.1f</ele>", it)) }
            append("<time>").append(iso(p.time)).append("</time></trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    fun parseGpx(xml: String): List<TrackPoint> {
        val pt = Regex("""<trkpt[^>]*lat="([-\d.]+)"[^>]*lon="([-\d.]+)"[^>]*>(.*?)</trkpt>""", RegexOption.DOT_MATCHES_ALL)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return pt.findAll(xml).map { m ->
            val body = m.groupValues[3]
            TrackPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(),
                Regex("<ele>([-\\d.]+)</ele>").find(body)?.groupValues?.get(1)?.toDoubleOrNull(),
                Regex("<time>([^<]+)</time>").find(body)?.groupValues?.get(1)?.let { runCatching { iso.parse(it)?.time }.getOrNull() } ?: 0L)
        }.toList()
    }
}

/** The track being recorded (if any) and the saved ones. */
object Tracks {
    val recording = MutableStateFlow(false)
    val points = MutableStateFlow<List<TrackPoint>>(emptyList())
    /** Bumped when the list of saved tracks changes. */
    val saved = MutableStateFlow(0)
    /** Saving and reading GPX files happens here, never on the main thread. */
    val io: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun dir(ctx: Context) = File(ctx.filesDir, "tracks").apply { mkdirs() }

    fun start(ctx: Context) {
        points.value = emptyList()
        runCatching { ctx.startForegroundService(Intent(ctx, TrackService::class.java)) }
            .onFailure { ctx.toast("Couldn't start tracking: ${it.message}") }
    }
    fun stop(ctx: Context) = ctx.stopService(Intent(ctx, TrackService::class.java))

    /** Write the recorded points as GPX; returns the file, or null if there was nothing worth keeping. */
    fun save(ctx: Context, pts: List<TrackPoint>): File? {
        if (pts.size < 2) return null
        val start = pts.first().time
        val name = "Track " + SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.UK).format(Date(start))
        val f = File(dir(ctx), SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(start)) + ".gpx")
        f.writeText(TrackMath.gpx(name, pts))
        saved.value++
        return f
    }

    fun list(ctx: Context): List<SavedTrack> = (dir(ctx).listFiles { f -> f.extension == "gpx" } ?: emptyArray())
        .sortedByDescending { it.name }
        .mapNotNull { f ->
            runCatching {
                val p = TrackMath.parseGpx(f.readText())
                val name = Regex("<name>([^<]*)</name>").find(f.readText())?.groupValues?.get(1) ?: f.nameWithoutExtension
                SavedTrack(f, name, p.firstOrNull()?.time ?: f.lastModified(), TrackMath.km(p), TrackMath.minutes(p))
            }.getOrNull()
        }

    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.tryStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share track")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun delete(f: File) { f.delete(); saved.value++ }

    fun shareAll(ctx: Context, files: List<File>) {
        if (files.size == 1) return share(ctx, files[0])
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
        ctx.tryStart(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/gpx+xml")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share tracks")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "2.41 km · 38 min · 4.1 km/h · ↑ 63 m" */
    fun stats(p: List<TrackPoint>): String {
        val km = TrackMath.km(p); val min = TrackMath.minutes(p)
        val speed = if (min > 0) " · " + String.format(Locale.UK, "%.1f km/h", km / (min / 60.0)) else ""
        val up = TrackMath.climb(p).let { if (it >= 5) " · ↑ ${it.toInt()} m" else "" }
        return String.format(Locale.UK, "%.2f km · %d min", km, min) + speed + up
    }
}

/** Keeps recording your track with the screen off or another app open (needs a notification to do so). */
class TrackService : Service() {
    private val listener = LocationListener { l -> add(l) }
    private var lastSaved = 0

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("track", "Track recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java).putExtra("hub", "map")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "track").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Recording a track").setContentText("Open Dune's map to see it or stop")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(79, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(79, n)
        Tracks.recording.value = true
        val lm = getSystemService(LocationManager::class.java)
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 4f, listener, Looper.getMainLooper()) }
            .onFailure { toast("Tracking needs precise location"); stopSelf() }
    }

    private fun add(l: Location) {
        if (l.hasAccuracy() && l.accuracy > 30) return          // too vague to draw
        val p = TrackPoint(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null, l.time)
        val cur = Tracks.points.value
        if (cur.isNotEmpty() && TrackMath.metres(cur.last(), p) < 3) return   // standing still
        Tracks.points.value = cur + p
        // Save as we go, so a crash or a flat battery doesn't lose the walk.
        if (Tracks.points.value.size - lastSaved >= 30) {
            lastSaved = Tracks.points.value.size
            val snap = Tracks.points.value; val ctx = applicationContext
            Tracks.io.execute { runCatching { Tracks.save(ctx, snap) } }
        }
    }

    override fun onDestroy() {
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
        Tracks.recording.value = false
        val snap = Tracks.points.value; val ctx = applicationContext
        Tracks.io.execute { runCatching { Tracks.save(ctx, snap) } }
        super.onDestroy()
    }
}
