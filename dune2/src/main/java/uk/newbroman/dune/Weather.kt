package uk.newbroman.dune

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

data class Weather(
    val temp: Int,
    val code: Int,
    val wind: Int,
    val high: Int,
    val low: Int,
    val rainChance: Int?,
    val place: String,
    /** Where the wind comes FROM, degrees from north. */
    val windDir: Int = 0,
    val gust: Int = 0,
    val cloud: Int = 0,
    /** mm in the last hour, rain and snow together. */
    val precip: Double = 0.0,
    val isDay: Boolean = true,
) {
    val thunder get() = code >= 95
    val snowing get() = code in 71..77 || code == 85 || code == 86
    val raining get() = !snowing && (precip > 0 || code in 51..67 || code in 80..82 || thunder)
    val foggy get() = code == 45 || code == 48

    val summary: String get() = describe(code)

    companion object {
        /** Open-Meteo: free, no API key. */
        suspend fun fetch(lat: Double, lon: Double, place: String): Weather = withContext(Dispatchers.IO) {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,cloud_cover,precipitation,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=1&wind_speed_unit=mph",
                lat, lon
            )
            val j = JSONObject(get(url))
            val cur = j.getJSONObject("current")
            val d = j.getJSONObject("daily")
            val rain = d.optJSONArray("precipitation_probability_max")
            Weather(
                temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
                code = cur.getInt("weather_code"),
                wind = Math.round(cur.getDouble("wind_speed_10m")).toInt(),
                high = Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                low = Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                rainChance = if (rain != null && !rain.isNull(0)) rain.getInt(0) else null,
                place = place,
                windDir = cur.optDouble("wind_direction_10m", 0.0).toInt(),
                gust = Math.round(cur.optDouble("wind_gusts_10m", 0.0)).toInt(),
                cloud = cur.optInt("cloud_cover", 0),
                precip = cur.optDouble("precipitation", 0.0),
                isDay = cur.optInt("is_day", 1) == 1,
            )
        }

        fun describe(c: Int) = when (c) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61 -> "Light rain"
            63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71, 73, 75, 77 -> "Snow"
            80, 81 -> "Showers"
            82 -> "Heavy showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunder and hail"
            else -> "—"
        }
    }
}

data class Place(val lat: Double, val lon: Double, val name: String)

object Geo {
    /** Look a place name up with Open-Meteo's geocoder. */
    suspend fun lookup(name: String): Place? = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" +
            URLEncoder.encode(name, "UTF-8")
        val r = JSONObject(get(url)).optJSONArray("results") ?: return@withContext null
        if (r.length() == 0) return@withContext null
        val o = r.getJSONObject(0)
        Place(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("name", name))
    }

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * A name for a position: "Menai Bridge, Isle of Anglesey, LL59 5EU". Android's own lookup first; it
     * needs Google's services, so on phones without them it falls to postcodes.io (UK), then OpenStreetMap.
     */
    suspend fun placeName(ctx: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (Geocoder.isPresent()) runCatching {
            @Suppress("DEPRECATION") Geocoder(ctx, Locale.UK).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.thoroughfare?.let { t -> (a.subThoroughfare?.let { "$it " } ?: "") + t }, a.locality ?: a.subLocality,
                    a.subAdminArea, a.postalCode).distinct().joinToString(", ").takeIf { it.isNotBlank() }
            }
        }.getOrNull()?.let { return@withContext it }
        runCatching {
            val r = JSONObject(get(String.format(Locale.US, "https://api.postcodes.io/postcodes?lon=%.6f&lat=%.6f&limit=1&radius=2000", lon, lat)))
                .optJSONArray("result")?.optJSONObject(0)
            r?.let { listOfNotNull(it.optString("parish").takeIf { p -> p.isNotBlank() && p != "null" && !p.contains("unparished") },
                it.optString("admin_ward").takeIf { w -> w.isNotBlank() && w != "null" }, it.optString("admin_district").takeIf { d -> d.isNotBlank() && d != "null" },
                it.optString("postcode")).distinct().joinToString(", ") }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        runCatching {
            JSONObject(get(String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=16&lat=%.6f&lon=%.6f", lat, lon)))
                .optString("display_name").split(", ").take(4).joinToString(", ")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** The newest position the phone already holds, from any source (none is asked to look). */
    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return (lm.allProviders + "fused").distinct()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /**
     * Where the phone is now. Uses what it already holds if that's newer than [maxAgeMs]; otherwise asks
     * every source that's switched on – Google's combined one, GPS, the mobile network – for a fresh fix
     * and takes the first to answer, giving up after [timeoutMs] and falling back to the old position.
     * (Phones without Google's location service, and any phone just after a restart, often hold nothing
     * at all, so only reading the stored position finds nothing even with permission granted.)
     */
    @SuppressLint("MissingPermission")
    suspend fun fix(ctx: Context, maxAgeMs: Long = 2 * 60_000L, timeoutMs: Long = 25_000L): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val old = lastKnown(ctx)
        if (old != null && System.currentTimeMillis() - old.time < maxAgeMs) return old
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (sources.isEmpty()) return old
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listeners = ArrayList<android.location.LocationListener>()
                var left = sources.size
                fun done(l: Location?) {
                    if (!cont.isActive) return
                    if (l != null) { listeners.forEach { runCatching { lm.removeUpdates(it) } }; cont.resume(l) }
                    else if (--left == 0) cont.resume(null)
                }
                sources.forEach { p ->
                    runCatching {
                        if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(p, null, ctx.mainExecutor) { done(it) }
                        else {
                            val li = android.location.LocationListener { l -> done(l) }
                            listeners += li
                            @Suppress("DEPRECATION") lm.requestSingleUpdate(p, li, android.os.Looper.getMainLooper())
                        }
                    }.onFailure { done(null) }
                }
                cont.invokeOnCancellation { listeners.forEach { runCatching { lm.removeUpdates(it) } } }
            }
        }
        return fresh ?: old
    }

    /** Phone's approximate location, or null if no permission / no fix. */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Place? {
        // for weather, a position up to an hour old is close enough
        val loc = fix(ctx, maxAgeMs = 3600_000L, timeoutMs = 20_000L) ?: return null
        val name = placeName(ctx, loc.latitude, loc.longitude)?.split(", ")?.let { parts ->
            parts.firstOrNull { p -> p.isNotBlank() && !p.first().isDigit() && !Regex("^[A-Z]{1,2}\\d").containsMatchIn(p) } }
        return Place(loc.latitude, loc.longitude, name ?: "")
    }
}

internal fun get(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000; c.readTimeout = 8000
    c.setRequestProperty("User-Agent", "DuneLauncher/1.0")
    try {
        if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally { c.disconnect() }
}
