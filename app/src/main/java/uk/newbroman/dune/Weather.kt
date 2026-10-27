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
) {
    val summary: String get() = describe(code)

    companion object {
        /** Open-Meteo: free, no API key. */
        suspend fun fetch(lat: Double, lon: Double, place: String): Weather = withContext(Dispatchers.IO) {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,weather_code,wind_speed_10m" +
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
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Phone's approximate location, or null if no permission / no fix. */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Place? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        var loc: Location? = lm.allProviders
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if ((loc == null || System.currentTimeMillis() - loc.time > 6 * 3600_000L) &&
            Build.VERSION.SDK_INT >= 30 && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            val fresh = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { cont.resume(it) }
                }
            }
            if (fresh != null) loc = fresh
        }
        loc ?: return null
        val name = withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale.UK).getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea }
            } catch (e: Exception) { null }
        }
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
