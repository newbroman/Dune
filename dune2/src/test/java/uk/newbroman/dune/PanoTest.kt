package uk.newbroman.dune

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Renders frames of a known synthetic world from known orientations, stitches them, and checks the result. */
class PanoTest {
    // a smooth world: colour depends on azimuth and elevation, so any misplacement shows as error
    private fun world(az: Double, el: Double): Int {
        val a = Math.toRadians(az); val e = Math.toRadians(el)
        val r = (127 + 120 * sin(a)).toInt(); val g = (127 + 120 * sin(e)).toInt(); val b = (127 + 120 * cos(a) * cos(e)).toInt()
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** device → world rotation for a camera pointing at (az, el), held upright in portrait. */
    private fun rot(az: Double, el: Double, roll: Double = 0.0): FloatArray {
        val f = Pano.dir(az, el)
        val z = doubleArrayOf(-f[0], -f[1], -f[2])
        var x = cross(f, doubleArrayOf(0.0, 0.0, 1.0))
        if (norm(x) < 1e-6) { val a = Math.toRadians(az); x = doubleArrayOf(cos(a), -sin(a), 0.0) }
        x = unit(x); var y = cross(z, x)
        // roll about the viewing axis
        val t = Math.toRadians(roll)
        val x2 = DoubleArray(3) { x[it] * cos(t) + y[it] * sin(t) }; val y2 = DoubleArray(3) { -x[it] * sin(t) + y[it] * cos(t) }
        x = x2; y = y2
        return floatArrayOf(x[0].toFloat(), y[0].toFloat(), z[0].toFloat(), x[1].toFloat(), y[1].toFloat(), z[1].toFloat(), x[2].toFloat(), y[2].toFloat(), z[2].toFloat())
    }
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(a: DoubleArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    private fun unit(a: DoubleArray) = norm(a).let { n -> DoubleArray(3) { a[it] / n } }

    private fun frame(r: FloatArray, fw: Int, fh: Int, f: Double) = IntArray(fw * fh) { k ->
        val u = k % fw; val v = k / fw
        val d = unit(doubleArrayOf((u - (fw - 1) / 2.0) / f, -(v - (fh - 1) / 2.0) / f, -1.0))
        val w = doubleArrayOf(r[0] * d[0] + r[1] * d[1] + r[2] * d[2], r[3] * d[0] + r[4] * d[1] + r[5] * d[2], r[6] * d[0] + r[7] * d[1] + r[8] * d[2])
        world(Math.toDegrees(atan2(w[0], w[1])), Math.toDegrees(asin(w[2].coerceIn(-1.0, 1.0))))
    }

    private fun check(sphere: Boolean, startAz: Double, roll: Double): Pano.Canvas {
        val fw = 120; val fh = 160; val fLong = 0.8    // vertical fov ≈ 64°
        val f = fLong * fh
        val ts = Pano.targets(sphere, Pano.fov(fw.toDouble(), f), Pano.fov(fh.toDouble(), f))
        val rots = ts.map { rot(startAz + it.yaw, it.pitch, roll) }
        val c = Pano.compose(rots, fw, fh, sphere, startAz, fLong) { frame(rots[it], fw, fh, f) }
        // compare every pixel with the world in that direction
        val ppd = c.fullW / 360.0
        var err = 0L; var n = 0
        for (j in 0 until c.h) for (i in 0 until c.w) {
            val lon = (c.left + i + .5) / ppd - 180; val lat = 90 - (c.top + j + .5) / ppd
            if (abs(lat) > 80) continue       // the poles are averaged rows, not exact
            val want = world(startAz + lon, lat); val got = c.px[j * c.w + i]
            err += abs((want shr 16 and 255) - (got shr 16 and 255)) + abs((want shr 8 and 255) - (got shr 8 and 255)) + abs((want and 255) - (got and 255))
            n++
        }
        val mean = err.toDouble() / n / 3
        assertTrue("mean error $mean too big (sphere=$sphere az=$startAz roll=$roll)", mean < 3.0)
        return c
    }

    @Test fun sphereLinesUp() { val c = check(true, 37.0, 0.0); assertEquals(c.fullW, c.w); assertEquals(c.w / 2, c.h) }
    @Test fun sphereWithTiltedHolding() { check(true, -120.0, 4.0) }
    @Test fun horizonSweep() { val c = check(false, 200.0, 0.0); assertTrue(c.h < c.w / 3) }

    // a busy world (like a real room) for measuring the lens: stripes and blobs at several scales
    private fun busy(az: Double, el: Double): Float {
        val a = Math.toRadians(az); val e = Math.toRadians(el)
        return (128 + 50 * sin(a * 7) * cos(e * 5) + 40 * sin(a * 23 + e * 11) + 30 * cos(e * 19 - a * 3)).toFloat()
    }
    private fun grayFrame(r: FloatArray, fw: Int, fh: Int, f: Double) = FloatArray(fw * fh) { k ->
        val u = k % fw; val v = k / fw
        val d = unit(doubleArrayOf((u - (fw - 1) / 2.0) / f, -(v - (fh - 1) / 2.0) / f, -1.0))
        val w = doubleArrayOf(r[0] * d[0] + r[1] * d[1] + r[2] * d[2], r[3] * d[0] + r[4] * d[1] + r[5] * d[2], r[6] * d[0] + r[7] * d[1] + r[8] * d[2])
        busy(Math.toDegrees(atan2(w[0], w[1])), Math.toDegrees(asin(w[2].coerceIn(-1.0, 1.0))))
    }

    @Test fun measuresTheLensWhenThePhoneReportsItWrong() {
        val fw = 120; val fh = 160; val trueF = 0.62
        val ts = Pano.targets(true, Pano.fov(fw.toDouble(), trueF * fh), Pano.fov(fh.toDouble(), trueF * fh))
        val rots = ts.map { rot(15 + it.yaw, it.pitch) }
        val g = rots.map { grayFrame(it, fw, fh, trueF * fh) }
        for (guess in listOf(0.9, 0.45)) {
            val got = Pano.calibrate(rots, g, fw, fh, guess)
            assertTrue("guess $guess → $got, want ≈$trueF", abs(got - trueF) / trueF < 0.03)
        }
    }

    @Test fun sphereTargetsCoverPoles() {
        val ts = Pano.targets(true, 50.0, 64.0)
        assertTrue(ts.any { it.pitch == 90.0 } && ts.any { it.pitch == -90.0 })
        assertTrue(ts.size in 20..40)
    }

    @Test fun xmpGoesAfterExifHeader() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 4, 1, 2, 0xFF.toByte(), 0xDB.toByte(), 9)
        val out = Pano.withXmp(jpeg, "<x/>")
        assertArrayEquals(jpeg.copyOfRange(0, 8), out.copyOfRange(0, 8))
        assertEquals(0xE1.toByte(), out[9])
        assertTrue(String(out).contains("http://ns.adobe.com/xap/1.0/\u0000<x/>"))
        assertArrayEquals(jpeg.copyOfRange(8, jpeg.size), out.copyOfRange(out.size - 3, out.size))
    }
}
