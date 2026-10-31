@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package uk.newbroman.dune

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Hour(val time: LocalDateTime, val temp: Int, val code: Int, val rainChance: Int, val rainMm: Double, val wind: Int, val isDay: Boolean)
data class Day(val date: LocalDate, val code: Int, val high: Int, val low: Int, val rainChance: Int, val rainMm: Double,
               val wind: Int, val gust: Int, val uv: Double, val sunrise: String, val sunset: String)
data class Forecast(
    val place: String, val temp: Int, val feels: Int, val code: Int, val isDay: Boolean, val humidity: Int, val pressure: Int,
    val wind: Int, val gust: Int, val windDir: Int, val hours: List<Hour>, val days: List<Day>,
    /** Rain expected in each of the next eight quarter-hours (mm). */
    val next2h: List<Double>,
)

/** Dune's own forecast, from Open-Meteo (free, no key; the Met Office model over the UK). */
object Forecasts {
    suspend fun fetch(lat: Double, lon: Double, place: String): Forecast = withContext(Dispatchers.IO) {
        val url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,pressure_msl,weather_code,is_day,wind_speed_10m,wind_gusts_10m,wind_direction_10m" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,wind_speed_10m,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,uv_index_max,sunrise,sunset" +
            "&minutely_15=precipitation&forecast_minutely_15=8&forecast_days=8&timezone=auto&wind_speed_unit=mph", lat, lon)
        parse(get(url), place)
    }

    fun parse(json: String, place: String): Forecast {
        val j = JSONObject(json)
        val c = j.getJSONObject("current")
        val h = j.getJSONObject("hourly"); val d = j.getJSONObject("daily")
        val now = LocalDateTime.parse(c.getString("time")).withMinute(0)
        val ht = h.getJSONArray("time")
        val hours = (0 until ht.length()).map { i ->
            Hour(LocalDateTime.parse(ht.getString(i)), h.getJSONArray("temperature_2m").optDouble(i).r(), h.getJSONArray("weather_code").optInt(i),
                h.getJSONArray("precipitation_probability").optInt(i), h.getJSONArray("precipitation").optDouble(i, 0.0),
                h.getJSONArray("wind_speed_10m").optDouble(i).r(), h.getJSONArray("is_day").optInt(i, 1) == 1)
        }.filter { !it.time.isBefore(now) }.take(24)
        val dt = d.getJSONArray("time")
        val days = (0 until dt.length()).map { i ->
            Day(LocalDate.parse(dt.getString(i)), d.getJSONArray("weather_code").optInt(i), d.getJSONArray("temperature_2m_max").optDouble(i).r(),
                d.getJSONArray("temperature_2m_min").optDouble(i).r(), d.getJSONArray("precipitation_probability_max").optInt(i),
                d.getJSONArray("precipitation_sum").optDouble(i, 0.0), d.getJSONArray("wind_speed_10m_max").optDouble(i).r(),
                d.getJSONArray("wind_gusts_10m_max").optDouble(i).r(), d.getJSONArray("uv_index_max").optDouble(i, 0.0),
                d.getJSONArray("sunrise").optString(i).takeLast(5), d.getJSONArray("sunset").optString(i).takeLast(5))
        }
        val m = j.optJSONObject("minutely_15")?.optJSONArray("precipitation")
        return Forecast(place, c.optDouble("temperature_2m").r(), c.optDouble("apparent_temperature").r(), c.optInt("weather_code"),
            c.optInt("is_day", 1) == 1, c.optInt("relative_humidity_2m"), c.optDouble("pressure_msl").r(),
            c.optDouble("wind_speed_10m").r(), c.optDouble("wind_gusts_10m").r(), c.optDouble("wind_direction_10m").r(),
            hours, days, (0 until (m?.length() ?: 0)).map { m!!.optDouble(it, 0.0) })
    }
    private fun Double.r() = if (isNaN()) 0 else Math.round(this).toInt()

    /** "Rain starting in about 30 min", "Rain easing in about 45 min", or null. */
    fun nowcast(q: List<Double>): String? {
        if (q.isEmpty()) return null
        val wet = q.map { it >= 0.1 }
        return when {
            wet[0] && wet.all { it } -> "Rain for the next two hours"
            wet[0] -> "Rain easing in about ${(wet.indexOfFirst { !it }) * 15} min"
            wet.any { it } -> "Rain starting in about ${(wet.indexOfFirst { it }) * 15} min"
            else -> "No rain in the next two hours"
        }
    }

    fun icon(code: Int, day: Boolean = true) = when (code) {
        0 -> if (day) "☀" else "☾"; 1 -> if (day) "🌤" else "☾"; 2 -> "⛅"; 3 -> "☁"; 45, 48 -> "🌫"
        51, 53, 55, 56, 57 -> "🌦"; 61, 63, 80, 81 -> "🌧"; 65, 82, 66, 67 -> "🌧"; 71, 73, 75, 77, 85, 86 -> "🌨"
        95, 96, 99 -> "⛈"; else -> "·"
    }
    /** Compass point the wind comes from. */
    fun from(deg: Int) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((deg % 360 + 22) / 45) % 8]
    fun uvWord(uv: Double) = when { uv < 3 -> "low"; uv < 6 -> "moderate"; uv < 8 -> "high"; uv < 11 -> "very high"; else -> "extreme" }
}

/** The weather hub: Dune's own forecast, with radar, and your Weawow widgets below if you add any. */
@Composable
fun ForecastPage(vm: LauncherVM, active: Boolean, gestures: NestedScrollConnection) {
    val act = LocalContext.current as MainActivity
    var tick by remember { mutableIntStateOf(0) }
    val f by produceState<Forecast?>(null, vm.skyLat, vm.skyLon, active, tick) {
        if (active || value == null) value = runCatching { Forecasts.fetch(vm.skyLat, vm.skyLon, vm.weather?.place ?: "") }.getOrNull() ?: value
    }
    HubPage(f?.place?.ifBlank { null } ?: "Weather", gestures, trailing = {
        Text("↻", color = Dim, fontSize = 18.sp, modifier = Modifier.combinedClickable { vm.refreshWeather(true); tick++ }.padding(8.dp))
    }) {
        val w = f
        if (w == null) { item { Note("Fetching the forecast… (Dune needs your location or a place in Settings › Weather)") }; return@HubPage }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Forecasts.icon(w.code, w.isDay), fontSize = 46.sp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("${w.temp}°", color = Sand, fontSize = 52.sp, fontWeight = FontWeight.Thin)
                    Text(Weather.describe(w.code) + " · feels ${w.feels}°", color = Fg, fontSize = 16.sp)
                }
            }
            // Wind: the arrow points where it's blowing to.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("↓", color = Fg, fontSize = 20.sp, modifier = Modifier.rotate(w.windDir.toFloat()))
                Text("  ${w.wind} mph from the ${Forecasts.from(w.windDir)}" + (if (w.gust > w.wind + 5) ", gusts ${w.gust}" else ""), color = Dim, fontSize = 14.sp)
            }
            Text("Humidity ${w.humidity}% · ${w.pressure} hPa", color = Dim, fontSize = 13.sp)
            Forecasts.nowcast(w.next2h)?.let { Text("☂ $it", color = if (it.startsWith("No")) Dim else Sand, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
            FlowRow(Modifier.padding(top = 8.dp)) {
                Chip("🌧 rain radar") { vm.mapRequest = "radar"; vm.jumpHub = "map" }
                Chip("Windy") { vm.mapRequest = "windy"; vm.jumpHub = "map" }
            }
        }
        // Next 24 hours
        item {
            Text("NEXT 24 HOURS", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                w.hours.forEach { h ->
                    Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(h.time.format(DateTimeFormatter.ofPattern("HH")), color = Dim, fontSize = 12.sp)
                        Text(Forecasts.icon(h.code, h.isDay), fontSize = 20.sp, modifier = Modifier.padding(vertical = 2.dp))
                        Text("${h.temp}°", color = Fg, fontSize = 15.sp)
                        // rain chance as a small bar
                        Box(Modifier.padding(top = 4.dp).width(6.dp).height(28.dp).background(Faint, RoundedCornerShape(3.dp)), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.width(6.dp).height((28 * h.rainChance / 100).dp).background(Color(0xFF6FA8DC), RoundedCornerShape(3.dp)))
                        }
                        Text(if (h.rainChance > 0) "${h.rainChance}%" else " ", color = Color(0xFF6FA8DC), fontSize = 10.sp)
                    }
                }
            }
        }
        // Next 8 days
        item {
            Text("THE WEEK", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            val lo = w.days.minOfOrNull { it.low } ?: 0; val hi = w.days.maxOfOrNull { it.high } ?: 1
            w.days.forEachIndexed { i, d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (i == 0) "Today" else d.date.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK)), color = Fg, fontSize = 14.sp, modifier = Modifier.width(62.dp))
                    Text(Forecasts.icon(d.code), fontSize = 18.sp, modifier = Modifier.width(30.dp))
                    Text(if (d.rainChance >= 20) "${d.rainChance}%" else "", color = Color(0xFF6FA8DC), fontSize = 12.sp, modifier = Modifier.width(36.dp))
                    Text("${d.low}°", color = Dim, fontSize = 13.sp, modifier = Modifier.width(30.dp))
                    // temperature range bar across the week's spread
                    Box(Modifier.weight(1f).height(5.dp).background(Faint, RoundedCornerShape(3.dp))) {
                        val span = (hi - lo).coerceAtLeast(1).toFloat()
                        Row { Spacer(Modifier.fillMaxWidth(((d.low - lo) / span).coerceIn(0f, 1f)).height(5.dp))
                            Box(Modifier.fillMaxWidth(((d.high - d.low) / (hi - d.low).coerceAtLeast(1).toFloat()).coerceIn(0.05f, 1f)).height(5.dp).background(Sand, RoundedCornerShape(3.dp))) }
                    }
                    Text("  ${d.high}°", color = Fg, fontSize = 13.sp, modifier = Modifier.width(38.dp))
                }
            }
            w.days.firstOrNull()?.let { d ->
                Text("☀ up ${d.sunrise} · down ${d.sunset} · UV ${"%.0f".format(d.uv)} (${Forecasts.uvWord(d.uv)}) · wind up to ${d.wind} mph, gusts ${d.gust}",
                    color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        // Weawow (or any) widgets, optional, below
        item { Text("YOUR WEATHER WIDGETS", color = Dim, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)) }
        weatherWidgets(vm, act)
    }
}
