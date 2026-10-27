package uk.newbroman.dune

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Tonight's sky for the home-screen background: bright stars, the five naked-eye planets,
 * the Sun and the Moon (with its real phase), for the viewer's place and time.
 * Low-precision formulae (JPL Keplerian elements, Schlyter's Sun/Moon) – good to a fraction
 * of a degree for planets and about a degree for the Moon, plenty for a picture.
 */

private const val D2R = PI / 180.0
private fun rev(x: Double) = x - floor(x / 360.0) * 360.0
private fun sind(x: Double) = sin(x * D2R)
private fun cosd(x: Double) = cos(x * D2R)

data class Star(val name: String, val raH: Double, val dec: Double, val mag: Double)

val STARS = listOf(
    Star("Sirius", 6.752, -16.716, -1.46), Star("Canopus", 6.399, -52.696, -0.74),
    Star("Rigil Kent", 14.660, -60.835, -0.27), Star("Arcturus", 14.261, 19.182, -0.05),
    Star("Vega", 18.616, 38.784, 0.03), Star("Capella", 5.278, 45.998, 0.08),
    Star("Rigel", 5.242, -8.202, 0.13), Star("Procyon", 7.655, 5.225, 0.34),
    Star("Achernar", 1.629, -57.237, 0.46), Star("Betelgeuse", 5.919, 7.407, 0.50),
    Star("Hadar", 14.064, -60.373, 0.61), Star("Altair", 19.846, 8.868, 0.77),
    Star("Acrux", 12.443, -63.099, 0.76), Star("Aldebaran", 4.599, 16.509, 0.85),
    Star("Spica", 13.420, -11.161, 0.97), Star("Antares", 16.490, -26.432, 1.06),
    Star("Pollux", 7.755, 28.026, 1.14), Star("Fomalhaut", 22.961, -29.622, 1.16),
    Star("Deneb", 20.690, 45.280, 1.25), Star("Regulus", 10.140, 11.967, 1.35),
    Star("Adhara", 6.977, -28.972, 1.50), Star("Castor", 7.577, 31.888, 1.58),
    Star("Shaula", 17.560, -37.104, 1.62), Star("Bellatrix", 5.419, 6.350, 1.64),
    Star("Elnath", 5.438, 28.608, 1.65), Star("Alnilam", 5.604, -1.202, 1.69),
    Star("Alnitak", 5.679, -1.943, 1.74), Star("Alioth", 12.900, 55.960, 1.77),
    Star("Mirfak", 3.405, 49.861, 1.79), Star("Dubhe", 11.062, 61.751, 1.79),
    Star("Wezen", 7.140, -26.393, 1.83), Star("Kaus Australis", 18.403, -34.385, 1.85),
    Star("Alkaid", 13.792, 49.313, 1.86), Star("Menkalinan", 5.992, 44.948, 1.90),
    Star("Alhena", 6.628, 16.399, 1.93), Star("Polaris", 2.530, 89.264, 1.98),
    Star("Mirzam", 6.378, -17.956, 1.98), Star("Alphard", 9.460, -8.659, 1.98),
    Star("Hamal", 2.120, 23.462, 2.00), Star("Diphda", 0.727, -17.987, 2.04),
    Star("Nunki", 18.921, -26.297, 2.05), Star("Mirach", 1.162, 35.621, 2.05),
    Star("Alpheratz", 0.140, 29.091, 2.06), Star("Saiph", 5.796, -9.670, 2.06),
    Star("Kochab", 14.845, 74.156, 2.08), Star("Rasalhague", 17.582, 12.560, 2.08),
    Star("Algieba", 10.333, 19.842, 2.08), Star("Almach", 2.065, 42.330, 2.10),
    Star("Algol", 3.136, 40.956, 2.12), Star("Denebola", 11.818, 14.572, 2.13),
    Star("Mizar", 13.399, 54.925, 2.23), Star("Sadr", 20.370, 40.257, 2.23),
    Star("Schedar", 0.675, 56.537, 2.24), Star("Eltanin", 17.943, 51.489, 2.23),
    Star("Alphecca", 15.578, 26.715, 2.23), Star("Mintaka", 5.533, -0.299, 2.23),
    Star("Caph", 0.153, 59.150, 2.28), Star("Merak", 11.031, 56.382, 2.37),
    Star("Izar", 14.750, 27.074, 2.37), Star("Enif", 21.736, 9.875, 2.38),
    Star("Scheat", 23.063, 28.083, 2.42), Star("Phecda", 11.897, 53.695, 2.44),
    Star("Gamma Cas", 0.945, 60.717, 2.47), Star("Markab", 23.079, 15.205, 2.48),
    Star("Gienah", 20.770, 33.970, 2.48), Star("Menkar", 3.038, 4.090, 2.54),
    Star("Zosma", 11.235, 20.524, 2.56), Star("Unukalhai", 15.738, 6.426, 2.63),
    Star("Ruchbah", 1.430, 60.235, 2.68), Star("Algenib", 0.220, 15.184, 2.83),
    Star("Vindemiatrix", 13.036, 10.959, 2.83), Star("Delta Cyg", 19.750, 45.131, 2.87),
    Star("Alcyone", 3.791, 24.105, 2.87), Star("Albireo", 19.512, 27.960, 3.05),
    Star("Megrez", 12.257, 57.033, 3.31), Star("Segin", 1.907, 63.670, 3.37),
)

/** Stick figures for a few well-known patterns, by star name. */
private val LINES = listOf(
    // Orion
    "Betelgeuse" to "Bellatrix", "Bellatrix" to "Mintaka", "Mintaka" to "Alnilam", "Alnilam" to "Alnitak",
    "Alnitak" to "Saiph", "Mintaka" to "Rigel", "Betelgeuse" to "Alnitak",
    // Plough
    "Dubhe" to "Merak", "Merak" to "Phecda", "Phecda" to "Megrez", "Megrez" to "Dubhe",
    "Megrez" to "Alioth", "Alioth" to "Mizar", "Mizar" to "Alkaid",
    // Cassiopeia
    "Caph" to "Schedar", "Schedar" to "Gamma Cas", "Gamma Cas" to "Ruchbah", "Ruchbah" to "Segin",
    // Cygnus
    "Deneb" to "Sadr", "Sadr" to "Albireo", "Delta Cyg" to "Sadr", "Sadr" to "Gienah",
    // Pegasus and Andromeda
    "Alpheratz" to "Scheat", "Scheat" to "Markab", "Markab" to "Algenib", "Algenib" to "Alpheratz",
    "Alpheratz" to "Mirach", "Mirach" to "Almach",
    // Leo
    "Regulus" to "Algieba", "Algieba" to "Zosma", "Zosma" to "Denebola",
    // Summer triangle
    "Vega" to "Deneb", "Deneb" to "Altair", "Altair" to "Vega",
)

data class Body(val name: String, val ra: Double, val dec: Double, val color: Color, val size: Float)

/** Julian date from Unix millis. */
fun julian(ms: Long) = ms / 86_400_000.0 + 2440587.5

/** Planet element table (JPL, valid 1800–2050): a, e, I, L, long. peri, node, each with a rate per century. */
private val ELEMENTS = mapOf(
    "Mercury" to doubleArrayOf(0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749, 252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081),
    "Venus" to doubleArrayOf(0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890, 181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418),
    "Earth" to doubleArrayOf(1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668, 100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0),
    "Mars" to doubleArrayOf(1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131, -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343),
    "Jupiter" to doubleArrayOf(5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714, 34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106),
    "Saturn" to doubleArrayOf(9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609, 49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794),
)

/** Heliocentric ecliptic x, y, z (AU). */
private fun helio(name: String, jd: Double): DoubleArray {
    val e = ELEMENTS.getValue(name); val t = (jd - 2451545.0) / 36525.0
    val a = e[0] + e[1] * t; val ec = e[2] + e[3] * t; val i = e[4] + e[5] * t
    val l = e[6] + e[7] * t; val wb = e[8] + e[9] * t; val om = e[10] + e[11] * t
    val w = wb - om
    val m = rev(l - wb) * D2R
    var ea = m + ec * sin(m)
    repeat(6) { ea -= (ea - ec * sin(ea) - m) / (1 - ec * cos(ea)) }
    val xp = a * (cos(ea) - ec); val yp = a * sqrt(1 - ec * ec) * sin(ea)
    val cw = cosd(w); val sw = sind(w); val co = cosd(om); val so = sind(om); val ci = cosd(i); val si = sind(i)
    return doubleArrayOf(
        (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp,
        (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp,
        (sw * si) * xp + (cw * si) * yp,
    )
}

/** Ecliptic x, y, z -> (RA degrees, Dec degrees). */
private fun eclToEq(x: Double, y: Double, z: Double, jd: Double): Pair<Double, Double> {
    val eps = 23.4393 - 3.563e-7 * (jd - 2451543.5)
    val ye = y * cosd(eps) - z * sind(eps); val ze = y * sind(eps) + z * cosd(eps)
    return rev(atan2(ye, x) / D2R) to atan2(ze, sqrt(x * x + ye * ye)) / D2R
}

/** Sun and Moon ecliptic longitudes (deg) and the bodies on the sky. */
fun solarSystem(jd: Double): Pair<List<Body>, Double> {
    val out = ArrayList<Body>()
    val earth = helio("Earth", jd)
    val planetColours = mapOf(
        "Mercury" to Color(0xFFBDB7AE), "Venus" to Color(0xFFFFF4D6), "Mars" to Color(0xFFE07A4F),
        "Jupiter" to Color(0xFFF2DEB8), "Saturn" to Color(0xFFE6CF8F),
    )
    for ((n, c) in planetColours) {
        val p = helio(n, jd)
        val (ra, dec) = eclToEq(p[0] - earth[0], p[1] - earth[1], p[2] - earth[2], jd)
        out += Body(n, ra, dec, c, if (n == "Venus" || n == "Jupiter") 5.5f else 4.2f)
    }
    // Sun (Schlyter)
    val d = jd - 2451543.5
    val ws = 282.9404 + 4.70935e-5 * d; val es = 0.016709 - 1.151e-9 * d; val ms = rev(356.0470 + 0.9856002585 * d)
    val eS = ms + es / D2R * sind(ms) * (1 + es * cosd(ms))
    val xv = cosd(eS) - es; val yv = sqrt(1 - es * es) * sind(eS)
    val sunLon = rev(atan2(yv, xv) / D2R + ws)
    val (sra, sdec) = eclToEq(cosd(sunLon), sind(sunLon), 0.0, jd)
    out += Body("Sun", sra, sdec, Color(0xFFFFE7A8), 9f)
    // Moon (Schlyter, with the largest perturbations)
    val nM = 125.1228 - 0.0529538083 * d; val iM = 5.1454; val wM = 318.0634 + 0.1643573223 * d
    val eM = 0.054900; val mM = rev(115.3654 + 13.0649929509 * d)
    var ea = mM + eM / D2R * sind(mM) * (1 + eM * cosd(mM))
    repeat(4) { ea -= (ea - eM / D2R * sind(ea) - mM) / (1 - eM * cosd(ea)) }
    val xm = 60.2666 * (cosd(ea) - eM); val ym = 60.2666 * sqrt(1 - eM * eM) * sind(ea)
    val v = atan2(ym, xm) / D2R
    var lon = atan2(
        sind(nM) * cosd(v + wM) + cosd(nM) * sind(v + wM) * cosd(iM),
        cosd(nM) * cosd(v + wM) - sind(nM) * sind(v + wM) * cosd(iM)
    ) / D2R
    var lat = asin(sind(v + wM) * sind(iM)) / D2R
    val lM = rev(nM + wM + mM); val dM = lM - (ms + ws); val f = lM - nM
    lon += -1.274 * sind(mM - 2 * dM) + 0.658 * sind(2 * dM) - 0.186 * sind(ms) -
        0.059 * sind(2 * mM - 2 * dM) - 0.057 * sind(mM - 2 * dM + ms) + 0.053 * sind(mM + 2 * dM)
    lat += -0.173 * sind(f - 2 * dM) - 0.055 * sind(mM - f - 2 * dM) - 0.046 * sind(mM + f - 2 * dM)
    lon = rev(lon)
    val (mra, mdec) = eclToEq(cosd(lat) * cosd(lon), cosd(lat) * sind(lon), sind(lat), jd)
    out += Body("Moon", mra, mdec, Color(0xFFF1EDE4), 10f)
    return out to rev(lon - sunLon)
}

/** Local sidereal time in degrees. */
fun lst(jd: Double, lonDeg: Double) = rev(280.46061837 + 360.98564736629 * (jd - 2451545.0) + lonDeg)

/** RA/Dec (deg) -> altitude, azimuth (deg, azimuth from north through east). */
fun altAz(ra: Double, dec: Double, latDeg: Double, lstDeg: Double): Pair<Double, Double> {
    val h = lstDeg - ra
    val alt = asin(sind(dec) * sind(latDeg) + cosd(dec) * cosd(latDeg) * cosd(h)) / D2R
    val az = atan2(-sind(h) * cosd(dec), sind(dec) * cosd(latDeg) - sind(latDeg) * cosd(dec) * cosd(h)) / D2R
    return alt to rev(az)
}

/**
 * Animated sky. Zenith at the centre of the screen, north up, east to the left (as when lying
 * on your back with your head to the north). Stars twinkle; the sky turns with real time.
 */
@Composable
fun SkyBackground(lat: Double, lon: Double, strength: Float, modifier: Modifier = Modifier.fillMaxSize()) {
    var frame by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        // ~12 fps; withFrameNanos stops while the screen is off or Dune is in the background.
        while (true) {
            withFrameNanos { frame = it / 1_000_000 }
            now = System.currentTimeMillis()
            delay(80)
        }
    }
    // Positions only change slowly: recompute once a minute.
    val minute = now / 60_000
    val model = remember(minute, lat, lon) { skyModel(julian(minute * 60_000 + 30_000), lat, lon) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color(0x99D9B26A), fontSize = 10.sp)

    Canvas(modifier) {
        val m = model
        val cx = size.width / 2f; val cy = size.height * 0.46f
        val r = maxOf(size.width, size.height) * 0.52f
        fun pos(alt: Double, az: Double): Offset {
            val rr = ((90 - alt) / 90).toFloat() * r
            return Offset(cx - rr * sind(az).toFloat(), cy - rr * cosd(az).toFloat())
        }

        // Sky colour from the Sun's altitude: black at night, deep blue at twilight and by day.
        val sunAlt = m.sunAlt
        val night = Color(0xFF000000); val twilight = Color(0xFF0B1730); val day = Color(0xFF13233F)
        val top = when {
            sunAlt < -18 -> night
            sunAlt < -6 -> lerp(night, twilight, ((sunAlt + 18) / 12).toFloat())
            sunAlt < 0 -> lerp(twilight, day, ((sunAlt + 6) / 6).toFloat())
            else -> day
        }
        drawRect(Brush.radialGradient(listOf(top, Color.Black), Offset(cx, cy), r * 1.1f))
        val dayDim = when { sunAlt > 0 -> 0.35f; sunAlt > -6 -> 0.6f; else -> 1f }
        val a = strength * dayDim
        val t = frame / 1000.0

        // Horizon
        drawCircle(Color(0x22D9B26A), r, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))

        // Constellation lines
        val byName = m.stars.associateBy { it.first.name }
        for ((p, q) in LINES) {
            val s1 = byName[p] ?: continue; val s2 = byName[q] ?: continue
            if (s1.second < -2 || s2.second < -2) continue
            drawLine(Color(0x26ECE8E1).copy(alpha = 0.15f * a), pos(s1.second, s1.third), pos(s2.second, s2.third), 1.2f)
        }
        // Stars
        m.stars.forEachIndexed { i, (st, alt, az) ->
            if (alt < 0) return@forEachIndexed
            val base = ((3.6 - st.mag) / 5.0).coerceIn(0.15, 1.0)
            val tw = 0.78 + 0.22 * sin(t * (1.3 + (i % 7) * 0.35) + i * 2.1)
            // extinction near the horizon
            val ext = (alt / 12.0).coerceIn(0.25, 1.0)
            val alpha = (base * tw * ext * a).toFloat().coerceIn(0f, 1f)
            val rad = (3.6 - st.mag * 0.7).toFloat().coerceIn(1.2f, 4.6f)
            val p = pos(alt, az)
            if (st.mag < 1.0) drawCircle(Color(0xFFECE8E1).copy(alpha = alpha * 0.18f), rad * 3.2f, p)
            drawCircle(Color(0xFFECE8E1).copy(alpha = alpha), rad, p)
        }
        // Planets, Sun, Moon
        for ((b, alt, az) in m.bodies) {
            if (alt < -1) continue
            val p = pos(alt, az)
            when (b.name) {
                "Sun" -> {
                    drawCircle(Brush.radialGradient(listOf(Color(0x66FFE7A8), Color.Transparent), p, 120f), 120f, p)
                    drawCircle(b.color.copy(alpha = 0.9f), b.size * 1.6f, p)
                }
                "Moon" -> drawMoon(p, 17f, m.moonElong, m.sunPos?.let { s -> pos(s.first, s.second) }, a)
                else -> {
                    drawCircle(b.color.copy(alpha = 0.25f * a), b.size * 2.4f, p)
                    drawCircle(b.color.copy(alpha = 0.95f * a), b.size, p)
                }
            }
            if (b.name != "Sun") drawText(measurer, b.name, p + Offset(12f, -8f), labelStyle.copy(color = labelStyle.color.copy(alpha = 0.6f * a)))
        }
    }
}

private class SkyModel(
    val stars: List<Triple<Star, Double, Double>>,
    val bodies: List<Triple<Body, Double, Double>>,
    val sunAlt: Double,
    val sunPos: Pair<Double, Double>?,
    val moonElong: Double,
)

private fun skyModel(jd: Double, lat: Double, lon: Double): SkyModel {
    val ls = lst(jd, lon)
    val stars = STARS.map { s -> val (al, az) = altAz(s.raH * 15, s.dec, lat, ls); Triple(s, al, az) }
    val (bodies, elong) = solarSystem(jd)
    val placed = bodies.map { b -> val (al, az) = altAz(b.ra, b.dec, lat, ls); Triple(b, al, az) }
    val sun = placed.first { it.first.name == "Sun" }
    return SkyModel(stars, placed, sun.second, sun.second to sun.third, elong)
}

/** Moon disc lit on the side facing the Sun. [elong] is Moon−Sun longitude (0 new, 180 full). */
private fun DrawScope.drawMoon(c: Offset, r: Float, elong: Double, sun: Offset?, a: Float) {
    val k = ((1 - cosd(elong)) / 2).toFloat()        // illuminated fraction
    drawCircle(Color(0xFF1A1916).copy(alpha = 0.9f), r, c)
    drawCircle(Color(0xFFF1EDE4).copy(alpha = 0.12f * a), r * 2.6f, c)
    // Direction of the Sun on screen; fall back to waxing = lit on the right.
    val angle = if (sun != null) Math.toDegrees(atan2((sun.y - c.y).toDouble(), (sun.x - c.x).toDouble())).toFloat()
    else if (elong < 180) 0f else 180f
    val path = Path()
    val steps = 24
    for (j in 0..steps) {                             // bright limb, top to bottom through +x
        val th = (-90.0 + 180.0 * j / steps) * D2R
        val x = c.x + r * cos(th).toFloat(); val y = c.y + r * sin(th).toFloat()
        if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (j in steps downTo 0) {                       // terminator back up
        val th = (-90.0 + 180.0 * j / steps) * D2R
        path.lineTo(c.x + (1 - 2 * k) * r * cos(th).toFloat(), c.y + r * sin(th).toFloat())
    }
    path.close()
    rotate(angle, c) { drawPath(path, Color(0xFFF1EDE4).copy(alpha = (0.95f * a).coerceIn(0f, 1f))) }
    if (abs(k) < 0.02f) drawCircle(Color(0x33F1EDE4), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
}
