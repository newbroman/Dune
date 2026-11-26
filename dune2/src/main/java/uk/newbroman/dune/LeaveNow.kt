package uk.newbroman.dune

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** "When do I need to leave?" */
object LeaveWhen : Cmd()

/**
 * Leave-now warnings. Calendar events in the next day that have a place in them are looked at in good time:
 * where the phone is, where the event is (the phone's own address lookup, then OpenStreetMap), how long the
 * journey takes (OpenStreetMap routing – it knows roads, not today's traffic, so a little is added), and
 * a notification goes up when it's time to go, with Directions. It looks again nearer the time from
 * wherever you are by then, so a warning set from home is still right after you've gone out.
 */
object LeaveNow {
    const val CHECK = "uk.newbroman.dune.LEAVE_CHECK"
    private const val CHANNEL = "dune_leave"
    private const val PREFS = "dune_leave"
    private const val LOOK_AHEAD = 18 * 3600_000L

    fun parse(s: String): Cmd? =
        if (Regex("^(?:when (?:do|should|must) i (?:need to )?(?:leave|set off|go)(?: for .+)?|what time (?:do|should) i (?:need to )?(?:leave|set off)(?: for .+)?|how long (?:will it take|does it take) to get to my next (?:appointment|meeting|event)|when'?s my next appointment and when do i leave)$").matches(s)) LeaveWhen else null

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, 0)
    fun on(ctx: Context) = p(ctx).getBoolean("on", true)
    fun setOn(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("on", v).apply(); plan(ctx) }
    /** Minutes to spare on arrival. */
    fun spare(ctx: Context) = p(ctx).getInt("spare", 10)
    fun setSpare(ctx: Context, m: Int) = p(ctx).edit().putInt("spare", m).apply()
    /** "car", "foot" or "bike". */
    fun mode(ctx: Context) = p(ctx).getString("mode", "car")!!
    fun setMode(ctx: Context, m: String) = p(ctx).edit().putString("mode", m).apply()

    fun backgroundAllowed(ctx: Context) = android.os.Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    data class Event(val id: Long, val title: String, val begin: Long, val where: String)

    fun events(ctx: Context, now: Long = System.currentTimeMillis()): List<Event> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now); android.content.ContentUris.appendId(it, now + LOOK_AHEAD) }.build()
        val where = "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.ALL_DAY} = 0 AND " +
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION), where, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                buildList { while (c.moveToNext()) {
                    val loc = c.getString(3)?.trim().orEmpty()
                    if (looksLikePlace(loc) && c.getLong(2) > now) add(Event(c.getLong(0), c.getString(1) ?: "(no title)", c.getLong(2), loc))
                } }
            }
        }.getOrNull().orEmpty()
    }

    /** A video-call link or "Phone" isn't somewhere to travel to. */
    fun looksLikePlace(s: String) = s.length >= 3 && !Regex("(?i)^https?://|zoom|teams|meet\\.google|google meet|skype|webex|phone call|^phone$|^online$|^call$|^tbc$|^tba$").containsMatchIn(s)

    // ---- where things are

    /** Dune keeps the last position it saw, so a check while it's in the background still has somewhere to start. */
    @SuppressLint("MissingPermission")
    fun remember(ctx: Context) {
        if (!Geo.hasPermission(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val l = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return
        val old = p(ctx).getLong("lastT", 0)
        if (l.time > old) p(ctx).edit().putFloat("lastLat", l.latitude.toFloat()).putFloat("lastLon", l.longitude.toFloat()).putLong("lastT", l.time).apply()
    }

    private suspend fun here(ctx: Context): Pair<Double, Double>? {
        Geo.fix(ctx, maxAgeMs = 10 * 60_000L, timeoutMs = 15_000L)?.let { remember(ctx); return it.latitude to it.longitude }
        val t = p(ctx).getLong("lastT", 0)
        if (System.currentTimeMillis() - t < 3 * 3600_000L) return p(ctx).getFloat("lastLat", 0f).toDouble() to p(ctx).getFloat("lastLon", 0f).toDouble()
        return null
    }

    /** An event's place as a position: the phone's own geocoder (good with full addresses), then OpenStreetMap near you. */
    private suspend fun place(ctx: Context, where: String, near: Pair<Double, Double>): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val key = "geo:" + where.lowercase()
        p(ctx).getString(key, null)?.split(',')?.let { return@withContext it[0].toDouble() to it[1].toDouble() }
        val hit = (if (android.location.Geocoder.isPresent()) runCatching {
            @Suppress("DEPRECATION") android.location.Geocoder(ctx, Locale.UK).getFromLocationName(where, 1)?.firstOrNull()?.let { it.latitude to it.longitude }
        }.getOrNull() else null)
            ?: Routes.search(where, near.first, near.second)?.firstOrNull()?.let { it.lat to it.lon }
            ?: Routes.search(where.substringBefore(','), near.first, near.second)?.firstOrNull()?.let { it.lat to it.lon }
        hit?.also { p(ctx).edit().putString(key, "${it.first},${it.second}").apply() }
    }

    data class Trip(val event: Event, val minutes: Int, val km: Double, val leaveAt: Long, val guessed: Boolean)

    /** When to leave for [e], from where the phone is. Unknown journey: 45 minutes beforehand, said so. */
    suspend fun trip(ctx: Context, e: Event): Trip? {
        val from = here(ctx)
        val to = from?.let { place(ctx, e.where, it) }
        val r = if (from != null && to != null) Routes.route(from.first, from.second, to.first, to.second, mode(ctx)) else null
        if (r != null && r.km < 0.3) return null                                       // already there
        val travel = r?.let { (it.minutes * if (mode(ctx) == "car") 1.15 else 1.0).roundToInt() + if (mode(ctx) == "car") 3 else 0 }
        val mins = travel ?: 45
        return Trip(e, mins, r?.km ?: 0.0, e.begin - (mins + spare(ctx)) * 60_000L, guessed = travel == null)
    }

    // ---- the checks

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 7073, Intent(ctx, AlarmReceiver::class.java).setAction(CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun hourly(ctx: Context) = PendingIntent.getBroadcast(ctx, 7074, Intent(ctx, AlarmReceiver::class.java).setAction(CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Start (or stop) watching: an hourly look at the calendar, and a check now. */
    fun plan(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(hourly(ctx))
        if (!on(ctx)) { am.cancel(pi(ctx)); return }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3600_000L, AlarmManager.INTERVAL_HOUR, hourly(ctx))
        wake(ctx, System.currentTimeMillis() + 5_000L)
    }

    private fun wake(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
    }

    /** Look at what's coming: warn for anything it's time to leave for; set the next look. */
    suspend fun check(ctx: Context) {
        if (!on(ctx)) return
        val now = System.currentTimeMillis()
        val done = p(ctx).getStringSet("done", emptySet())!!.filter { it.substringAfter(':').toLongOrNull()?.let { b -> b > now - 86_400_000L } == true }.toMutableSet()
        var next = Long.MAX_VALUE
        for (e in events(ctx, now)) {
            val tag = "${e.id}:${e.begin}"
            if (tag in done) continue
            if (e.begin - now > 4 * 3600_000L) { next = minOf(next, e.begin - 4 * 3600_000L); continue }   // too far off to work out yet
            val t = withTimeoutOrNull(40_000) { trip(ctx, e) } ?: continue
            when {
                now >= t.leaveAt - 60_000L -> { warn(ctx, t, now); done += tag }
                t.leaveAt - now > 30 * 60_000L -> next = minOf(next, t.leaveAt - 25 * 60_000L)   // look again nearer the time
                else -> next = minOf(next, t.leaveAt)
            }
        }
        p(ctx).edit().putStringSet("done", done).apply()
        if (next != Long.MAX_VALUE) wake(ctx, maxOf(next, now + 60_000L))
    }

    private val hm = SimpleDateFormat("HH:mm", Locale.UK)

    fun warn(ctx: Context, t: Trip, now: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Leave-now warnings", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val late = (now - t.leaveAt) / 60_000L
        val how = when (mode(ctx)) { "foot" -> "walk"; "bike" -> "cycle"; else -> "drive" }
        val title = (if (late > 5) "Leave now – you're running late" else "Time to leave") + " for ${t.event.title}"
        val body = "Starts ${hm.format(Date(t.event.begin))} at ${t.event.where}. " +
            if (t.guessed) "Couldn't work out the journey from here – this is 45 minutes before." else "About ${t.minutes} min $how (${"%.0f".format(t.km)} km)."
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(t.event.where) +
            "&mode=" + when (mode(ctx)) { "foot" -> "w"; "bike" -> "b"; else -> "d" })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val navOk = ctx.packageManager.resolveActivity(nav, 0) != null
        val go = PendingIntent.getActivity(ctx, (t.event.id % 10_000).toInt() + 30_000,
            if (navOk) nav else Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(t.event.where))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify((t.event.id % 10_000).toInt() + 30_000, android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title).setContentText(body).setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setCategory(android.app.Notification.CATEGORY_REMINDER).setContentIntent(go).setAutoCancel(true)
            .addAction(android.app.Notification.Action.Builder(null, "Directions", go).build())
            .setTimeoutAfter(maxOf(t.event.begin - now, 0) + 30 * 60_000L).build())
        if (Drive.active.value) Speaker.speak(ctx, title + ". " + body)
    }

    /** "When do I need to leave?" – for the next event with a place. */
    suspend fun answer(ctx: Context): String {
        val e = events(ctx).firstOrNull() ?: return if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            "Allow Dune to read your calendar first (Settings › Voice commands check)" else "Nothing with a place in your calendar in the next 18 hours"
        val t = trip(ctx, e) ?: return "${e.title} at ${hm.format(Date(e.begin))} – you're already there"
        val left = (t.leaveAt - System.currentTimeMillis()) / 60_000L
        val day = if (SimpleDateFormat("yyyyMMdd", Locale.UK).let { it.format(Date(e.begin)) != it.format(Date()) }) "tomorrow " else ""
        return "${e.title}, ${day}${hm.format(Date(e.begin))} at ${e.where}. " +
            (if (t.guessed) "I couldn't work out the journey, so allow 45 minutes" else "It's about ${t.minutes} min") +
            when { left <= 0 -> " – leave now."; left < 60 -> " – leave in $left min, at ${hm.format(Date(t.leaveAt))}."; else -> " – leave at $day${hm.format(Date(t.leaveAt))}." }
    }
}
