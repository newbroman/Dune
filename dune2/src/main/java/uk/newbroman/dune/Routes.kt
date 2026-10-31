package uk.newbroman.dune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** A place found by search. */
data class Found(val name: String, val detail: String, val lat: Double, val lon: Double)

/** A route: the line to draw, and how far and how long. */
data class Route(val points: List<Pair<Double, Double>>, val km: Double, val minutes: Int)

/** Dune's own place search (Photon, from OpenStreetMap) and directions (FOSSGIS routing). Free, no keys. */
object Routes {
    suspend fun search(q: String, nearLat: Double?, nearLon: Double?): List<Found> = withContext(Dispatchers.IO) {
        val near = if (nearLat != null && nearLon != null) String.format(Locale.US, "&lat=%.4f&lon=%.4f", nearLat, nearLon) else ""
        runCatching { parseSearch(get("https://photon.komoot.io/api/?limit=8&lang=en$near&q=" + URLEncoder.encode(q, "UTF-8"))) }.getOrDefault(emptyList())
    }

    fun parseSearch(json: String): List<Found> {
        val fs = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until fs.length()).mapNotNull { i ->
            val f = fs.getJSONObject(i); val p = f.optJSONObject("properties") ?: return@mapNotNull null
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val name = p.optString("name").ifBlank { listOf(p.optString("housenumber"), p.optString("street")).filter { it.isNotBlank() }.joinToString(" ") }
            if (name.isBlank()) return@mapNotNull null
            val detail = listOf(p.optString("street").takeIf { p.has("name") }, p.optString("city"), p.optString("county"), p.optString("country"))
                .filter { !it.isNullOrBlank() && it != name }.distinct().joinToString(", ")
            Found(name, detail, c.getDouble(1), c.getDouble(0))
        }
    }

    /** mode: "foot", "bike" or "car". */
    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: String): Route? = withContext(Dispatchers.IO) {
        val url = String.format(Locale.US, "https://routing.openstreetmap.de/routed-%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
            mode, fromLon, fromLat, toLon, toLat)
        runCatching { parseRoute(get(url)) }.getOrNull()
    }

    fun parseRoute(json: String): Route? {
        val r = JSONObject(json).optJSONArray("routes")?.optJSONObject(0) ?: return null
        val c = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until c.length()).map { c.getJSONArray(it).let { a -> a.getDouble(1) to a.getDouble(0) } }
        return Route(pts, r.optDouble("distance") / 1000, Math.round(r.optDouble("duration") / 60).toInt())
    }
}
