package uk.newbroman.dune

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Panoramas made from the phone's motion sensors: Dune shows where to point, takes a frame each time the
 * phone is held still on a target, and lays every frame onto a sphere using the orientation it was taken at.
 * "360" covers the whole sphere (sky and ground) and is saved as an equirectangular photo sphere; "Pano" is
 * a sweep around the horizon. Both carry Google's panorama metadata, so Google Photos opens them in its
 * 360 / panorama viewer.
 *
 * Frames are placed by orientation, not matched by their content, so turn the phone around the lens rather
 * than around yourself: things close by can show small steps at the joins; distant scenes join cleanly.
 *
 * Coordinates: world is east/north/up (Android's rotation vector); device is x right, y up, z out of the
 * screen, natural (portrait) orientation; the camera looks along -z.
 */
object Pano {
    /** A place to point the camera: [yaw] degrees right of where you started, [pitch] degrees up. */
    data class Target(val yaw: Double, val pitch: Double)

    /** One frame on disk with the device → world rotation it was taken at. */
    class Shot(val file: File, val r: FloatArray)

    /**
     * Lens: focal length as a fraction of the image's long side (portrait height), so the same number works
     * for any frame size. From the camera's own focal length and sensor size; [fallbackVfov] if unknown.
     */
    fun focalOfLong(focalMm: Float?, sensorLongMm: Float?, fallbackVfov: Double = 66.0): Double =
        if (focalMm != null && sensorLongMm != null && focalMm > 0 && sensorLongMm > 0) (focalMm / sensorLongMm).toDouble()
        else 0.5 / Math.tan(Math.toRadians(fallbackVfov / 2))

    /** Field of view (degrees) across a side of [px] pixels, for focal length [f] in pixels. */
    fun fov(px: Double, f: Double) = Math.toDegrees(2 * atan(px / 2 / f))

    /** Where to take the frames. Overlap of about a quarter between neighbours. */
    fun targets(sphere: Boolean, hfov: Double, vfov: Double): List<Target> {
        val step = hfov * 0.72
        fun ring(pitch: Double, n: Int) = (0 until n).map { Target(it * 360.0 / n, pitch) }
        if (!sphere) return ring(0.0, ceil(360 / step).toInt())
        val n0 = ceil(360 / step).toInt()
        val up = min(vfov * 0.75, 55.0)
        // the frame's corners shrink towards the pole, so count the top ring at its lower edge's latitude
        val nUp = max(4, ceil(360 / (step / cos(Math.toRadians(up - vfov / 2 + 4)))).toInt())
        val ringUp = ring(up, nUp).map { it.copy(yaw = it.yaw + 180.0 / nUp) }
        val ringDown = ring(-up, nUp).map { it.copy(yaw = it.yaw + 180.0 / nUp) }
        val poles = if (up + vfov / 2 >= 88) emptyList() else listOf(Target(0.0, 90.0), Target(0.0, -90.0))
        return ring(0.0, n0) + ringUp + poles.take(1) + ringDown + poles.drop(1)
    }

    /** Unit vector in the world for an azimuth (degrees clockwise from north) and elevation (degrees). */
    fun dir(azDeg: Double, elDeg: Double): DoubleArray {
        val a = Math.toRadians(azDeg); val e = Math.toRadians(elDeg)
        return doubleArrayOf(cos(e) * sin(a), cos(e) * cos(a), sin(e))
    }
    /** Where the camera points, in the world: R · (0, 0, -1). */
    fun forward(r: FloatArray) = doubleArrayOf(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())
    fun azimuth(v: DoubleArray) = Math.toDegrees(atan2(v[0], v[1]))
    fun elevation(v: DoubleArray) = Math.toDegrees(asin(v[2].coerceIn(-1.0, 1.0)))
    fun angle(a: DoubleArray, b: DoubleArray) = Math.toDegrees(acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))
    /** A world direction in device coordinates: Rᵀ · w. */
    fun toDevice(r: FloatArray, w: DoubleArray) = doubleArrayOf(
        r[0] * w[0] + r[3] * w[1] + r[6] * w[2],
        r[1] * w[0] + r[4] * w[1] + r[7] * w[2],
        r[2] * w[0] + r[5] * w[1] + r[8] * w[2])

    /**
     * Lays the frames onto an equirectangular canvas and saves it to Pictures/Dune with panorama metadata.
     * [startAz] is the azimuth of the first frame (it becomes the middle of the picture), [fLong] the focal
     * length as a fraction of a frame's long side.
     */
    suspend fun stitch(ctx: Context, shots: List<Shot>, sphere: Boolean, startAz: Double, fLong: Double,
                       progress: (Float) -> Unit = {}): Uri? = withContext(Dispatchers.Default) {
        if (shots.isEmpty()) return@withContext null
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(shots[0].file.path, probe)
        val fw = probe.outWidth; val fh = probe.outHeight
        if (fw <= 0 || fh <= 0) return@withContext null
        // Phones don't always report their lens width correctly: measure it from how the frames overlap.
        val small = shots.map { sh ->
            BitmapFactory.decodeFile(sh.file.path, BitmapFactory.Options().apply { inSampleSize = max(1, max(fw, fh) / 256) })?.let { bm ->
                val g = gray(bm); bm.recycle(); g
            }
        }
        val f0 = small.firstOrNull { it != null }?.let { g0 ->
            calibrate(shots.map { it.r }, small.map { it?.second }, g0.first.first, g0.first.second, fLong)
        } ?: fLong
        lastFocal = f0
        val c = compose(shots.map { it.r }, fw, fh, sphere, startAz, f0, progress) { n ->
            BitmapFactory.decodeFile(shots[n].file.path)?.let { bm ->
                if (bm.width != fw || bm.height != fh) { bm.recycle(); null }
                else IntArray(fw * fh).also { bm.getPixels(it, 0, fw, 0, 0, fw, fh); bm.recycle() }
            }
        }
        val bmp = Bitmap.createBitmap(c.px, c.w, c.h, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray(); bmp.recycle()
        save(ctx, bytes, xmp(c.w, c.h, c.fullW, c.fullW / 2, c.left, c.top), sphere)
    }

    /** The lens width the last stitch measured (focal length ÷ long side), to aim the next panorama's targets with. */
    @Volatile var lastFocal: Double? = null

    private fun gray(bm: Bitmap): Pair<Pair<Int, Int>, FloatArray> {
        val w = bm.width; val h = bm.height; val px = IntArray(w * h); bm.getPixels(px, 0, w, 0, 0, w, h)
        return (w to h) to FloatArray(w * h) { val c = px[it]; ((c shr 16 and 255) * .3f + (c shr 8 and 255) * .59f + (c and 255) * .11f) }
    }

    /**
     * Finds the focal length (as a fraction of a frame's long side) that makes overlapping frames agree best: points of
     * one frame are carried through both frames' orientations into the other, and the brightness differences summed.
     * [g] are small greyscale frames (w × h); returns [guess] if there's too little overlap to judge.
     */
    fun calibrate(rots: List<FloatArray>, g: List<FloatArray?>, w: Int, h: Int, guess: Double): Double {
        val n = rots.size
        val fwd = rots.map { forward(it) }
        // neighbouring frames (closest pairs) are the ones that overlap
        val pairs = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) for (j in i + 1 until n) if (g[i] != null && g[j] != null) pairs += i to j
        val near = pairs.sortedBy { (i, j) -> angle(fwd[i], fwd[j]) }.filter { (i, j) -> angle(fwd[i], fwd[j]) in 5.0..60.0 }.take(12)
        if (near.isEmpty()) return guess
        val L = max(w, h).toDouble(); val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        fun err(fl: Double): Double {
            val f = fl * L
            var sum = 0.0; var cnt = 0
            for ((i, j) in near) {
                val a = g[i]!!; val b = g[j]!!; val ra = rots[i]; val rb = rots[j]
                var y = 4
                while (y < h - 4) {
                    var x = 4
                    while (x < w - 4) {
                        // pixel of frame i → device direction → world → frame j
                        val dx = (x - cx) / f; val dy = -(y - cy) / f; val dz = -1.0
                        val wx = ra[0] * dx + ra[1] * dy + ra[2] * dz; val wy = ra[3] * dx + ra[4] * dy + ra[5] * dz; val wz = ra[6] * dx + ra[7] * dy + ra[8] * dz
                        val ex = rb[0] * wx + rb[3] * wy + rb[6] * wz; val ey = rb[1] * wx + rb[4] * wy + rb[7] * wz; val ez = rb[2] * wx + rb[5] * wy + rb[8] * wz
                        if (ez < -0.1) {
                            val u = cx + f * ex / -ez; val v = cy - f * ey / -ez
                            if (u >= 1 && v >= 1 && u < w - 2 && v < h - 2) {
                                val ui = u.toInt(); val vi = v.toInt(); val fu = (u - ui).toFloat(); val fv = (v - vi).toFloat()
                                val k = vi * w + ui
                                val bv = (b[k] * (1 - fu) + b[k + 1] * fu) * (1 - fv) + (b[k + w] * (1 - fu) + b[k + w + 1] * fu) * fv
                                sum += abs(a[y * w + x] - bv); cnt++
                            }
                        }
                        x += 3
                    }
                    y += 3
                }
            }
            // too little overlap at this width can't be trusted
            return if (cnt < 300 * near.size / 4) Double.MAX_VALUE else sum / cnt
        }
        var best = guess; var bestE = err(guess)
        var s = 0.45
        while (s <= 2.2) { val fl = guess * s; val e = err(fl); if (e < bestE) { bestE = e; best = fl }; s *= 1.03 }
        // and finer around the best
        var t = 0.97
        while (t <= 1.03) { val fl = best * t; val e = err(fl); if (e < bestE) { bestE = e; best = fl }; t += 0.004 }
        return best
    }

    /** The finished picture and where it sits within the full 360 × 180 sphere. */
    class Canvas(val px: IntArray, val w: Int, val h: Int, val fullW: Int, val left: Int, val top: Int)

    /** The stitching itself, without Android: frame [n] comes from [load] as ARGB pixels, fw × fh. */
    fun compose(rots: List<FloatArray>, fw: Int, fh: Int, sphere: Boolean, startAz: Double, fLong: Double,
                progress: (Float) -> Unit = {}, load: (Int) -> IntArray?): Canvas {
        val f = fLong * max(fw, fh)
        val hfov = fov(fw.toDouble(), f); val vfov = fov(fh.toDouble(), f)
        val rad = Math.toDegrees(atan(sqrt((fw * fw + fh * fh).toDouble()) / 2 / f))   // centre-to-corner angle

        // The canvas: the whole sphere, or the band and span the sweep covered.
        val centres = rots.map { forward(it) }
        val yaws = centres.map { norm180(azimuth(it) - startAz) }
        val els = centres.map { elevation(it) }
        val ppd: Double; val lonL: Double; val lonSpan: Double; val latT: Double; val latSpan: Double
        if (sphere) {
            lonL = -180.0; lonSpan = 360.0; latT = 90.0; latSpan = 180.0
            ppd = min(f * PI / 180, 5120.0 / 360)
        } else {
            val sorted = yaws.sorted()
            // the largest gap between neighbouring frames is where the sweep isn't: the picture starts after it
            var gapAt = sorted.size - 1; var gap = sorted[0] + 360 - sorted.last()
            for (i in 0 until sorted.size - 1) if (sorted[i + 1] - sorted[i] > gap) { gap = sorted[i + 1] - sorted[i]; gapAt = i }
            val full = gap < hfov * 0.9
            if (full) { lonL = -180.0; lonSpan = 360.0 }
            else {
                val first = sorted[(gapAt + 1) % sorted.size]
                lonL = first - hfov / 2; lonSpan = min(360.0, 360 - gap + hfov)
            }
            latT = min(90.0, els.max() + vfov / 2); latSpan = latT - max(-90.0, els.min() - vfov / 2)
            ppd = min(f * PI / 180, 9000.0 / lonSpan)
        }
        val fullW = (360 * ppd).roundToInt()
        val W = if (lonSpan >= 360) fullW else (lonSpan * ppd).roundToInt()
        val H = (latSpan * ppd).roundToInt()
        val out = IntArray(W * H)
        val best = FloatArray(W * H)     // how central the chosen frame's sample was (0 = nothing yet)
        // per-column azimuth and per-row elevation, worked out once
        val sinAz = DoubleArray(W); val cosAz = DoubleArray(W)
        for (i in 0 until W) { val a = Math.toRadians(startAz + lonL + (i + .5) / ppd); sinAz[i] = sin(a); cosAz[i] = cos(a) }
        val sinEl = DoubleArray(H); val cosEl = DoubleArray(H)
        for (j in 0 until H) { val e = Math.toRadians(latT - (j + .5) / ppd); sinEl[j] = sin(e); cosEl[j] = cos(e) }
        val band = 0.012f    // weights this close blend, so joins are soft rather than hard edges
        val cx = (fw - 1) / 2.0; val cy = (fh - 1) / 2.0

        rots.forEachIndexed { n, r ->
            progress(n.toFloat() / rots.size)
            val px = load(n) ?: return@forEachIndexed
            val el = els[n]; val yaw = yaws[n]
            // rows and columns this frame can reach
            val j0 = max(0, floor((latT - (el + rad)) * ppd).toInt()); val j1 = min(H - 1, ceil((latT - (el - rad)) * ppd).toInt())
            val nearPole = abs(el) + rad >= 84
            val half = if (nearPole) 180.0 else min(180.0, Math.toDegrees(asin(min(1.0, sin(Math.toRadians(rad)) / cos(Math.toRadians(abs(el) + rad * .5))))) * 1.2 + 2)
            val iA = floor((yaw - half - lonL) * ppd).toInt(); val iB = ceil((yaw + half - lonL) * ppd).toInt()
            val cols = if (half >= 180 || iB - iA >= fullW) (0 until W).toList().toIntArray()
                       else (iA..iB).map { if (lonSpan >= 360) Math.floorMod(it, W) else it }.filter { it in 0 until W }.distinct().toIntArray()
            for (j in j0..j1) {
                val se = sinEl[j]; val ce = cosEl[j]; val row = j * W
                for (i in cols) {
                    val wx = ce * sinAz[i]; val wy = ce * cosAz[i]; val wz = se
                    val dz = r[2] * wx + r[5] * wy + r[8] * wz
                    if (dz >= -0.05) continue
                    val dx = r[0] * wx + r[3] * wy + r[6] * wz
                    val dy = r[1] * wx + r[4] * wy + r[7] * wz
                    val u = cx + f * dx / -dz; val v = cy - f * dy / -dz
                    if (u < 0 || v < 0 || u > fw - 1 || v > fh - 1) continue
                    // a little less weight towards the frame's edges, where lenses are softest
                    val edge = min(min(u, fw - 1 - u) / fw, min(v, fh - 1 - v) / fh).toFloat()
                    val w = (-dz).toFloat() * 0.5f + min(edge, 0.25f) * 2f
                    val k = row + i; val b = best[k]
                    if (w <= b - band) continue
                    val c = bilinear(px, fw, fh, u, v)
                    if (b == 0f || w >= b + band) { out[k] = c; best[k] = w }
                    else { out[k] = mix(out[k], c, ((w - b + band) / (2 * band)).coerceIn(0f, 1f)); if (w > b) best[k] = w }
                }
            }
        }
        if (sphere) fillGaps(out, best, W, H)
        progress(1f)
        val top = ((90 - latT) * ppd).roundToInt()
        val left = if (lonSpan >= 360) 0 else Math.floorMod(((lonL + 180) * ppd).roundToInt(), fullW)
        return Canvas(out, W, H, fullW, left, top)
    }

    private fun norm180(a: Double): Double { var x = a % 360; if (x < -180) x += 360; if (x >= 180) x -= 360; return x }

    private fun bilinear(px: IntArray, w: Int, h: Int, u: Double, v: Double): Int {
        val x0 = u.toInt(); val y0 = v.toInt(); val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = (u - x0).toFloat(); val fy = (v - y0).toFloat()
        return mix(mix(px[y0 * w + x0], px[y0 * w + x1], fx), mix(px[y1 * w + x0], px[y1 * w + x1], fx), fy)
    }
    private fun mix(a: Int, b: Int, t: Float): Int {
        val s = 1 - t
        val r = ((a shr 16 and 255) * s + (b shr 16 and 255) * t).toInt()
        val g = ((a shr 8 and 255) * s + (b shr 8 and 255) * t).toInt()
        val bl = ((a and 255) * s + (b and 255) * t).toInt()
        return (255 shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Anything the frames didn't reach (usually right at the poles) takes its row's average colour. */
    private fun fillGaps(out: IntArray, best: FloatArray, w: Int, h: Int) {
        val mean = IntArray(h) { 0 }; val has = BooleanArray(h)
        for (j in 0 until h) {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (i in 0 until w) { val k = j * w + i; if (best[k] > 0) { val c = out[k]; r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255; n++ } }
            if (n > 0) { mean[j] = (255 shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt(); has[j] = true }
        }
        for (j in 0 until h) {
            val c = if (has[j]) mean[j] else {
                var d = 1; var found = 0xFF202020.toInt()
                while (d < h) { if (j - d >= 0 && has[j - d]) { found = mean[j - d]; break }; if (j + d < h && has[j + d]) { found = mean[j + d]; break }; d++ }
                found
            }
            for (i in 0 until w) { val k = j * w + i; if (best[k] == 0f) out[k] = c }
        }
    }

    /** Google's photo-sphere / panorama metadata (GPano), so viewers know how to show it. */
    fun xmp(w: Int, h: Int, fullW: Int, fullH: Int, left: Int, top: Int): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"""" +
        """ GPano:ProjectionType="equirectangular" GPano:UsePanoramaViewer="True" GPano:CaptureSoftware="Dune"""" +
        """ GPano:CroppedAreaImageWidthPixels="$w" GPano:CroppedAreaImageHeightPixels="$h"""" +
        """ GPano:FullPanoWidthPixels="$fullW" GPano:FullPanoHeightPixels="$fullH"""" +
        """ GPano:CroppedAreaLeftPixels="$left" GPano:CroppedAreaTopPixels="$top"/></rdf:RDF></x:xmpmeta>"""

    /** Puts an XMP block into a JPEG, after its JFIF / Exif headers. */
    fun withXmp(jpeg: ByteArray, xml: String): ByteArray {
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xml.toByteArray()
        require(payload.size + 2 <= 65535)
        var p = 2
        while (p + 4 <= jpeg.size && jpeg[p] == 0xFF.toByte() && (jpeg[p + 1] == 0xE0.toByte() || jpeg[p + 1] == 0xE1.toByte())) {
            p += 2 + ((jpeg[p + 2].toInt() and 255) shl 8 or (jpeg[p + 3].toInt() and 255))
        }
        val len = payload.size + 2
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 255).toByte()) + payload
        return jpeg.copyOfRange(0, p) + seg + jpeg.copyOfRange(p, jpeg.size)
    }

    private fun save(ctx: Context, bytes: ByteArray, xml: String, sphere: Boolean): Uri? {
        // location first (Exif), then the panorama metadata after it
        val tmp = File(ctx.cacheDir, "pano-out.jpg")
        tmp.writeBytes(bytes)
        Captures.where(ctx)?.let { loc -> runCatching { ExifInterface(tmp).apply { setGpsInfo(loc) }.saveAttributes() } }
        tmp.writeBytes(withXmp(tmp.readBytes(), xml))
        val name = (if (sphere) "Dune_360_" else "Dune_PANO_") + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Captures.sub)
        }
        val u = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(u)?.use { it.write(tmp.readBytes()) }
        tmp.delete()
        return u
    }
}
