package uk.newbroman.dune

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata as CM
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What this phone's cameras will let an app do at night: longest exposure, ISO range, manual focus to
 * infinity, RAW. Read from what each camera advertises to Android (no photo is taken), so it's the ceiling
 * an Astro mode could ask for – a test shot is still needed to prove the phone honours it.
 */
object AstroCheck {
    data class Cam(
        val id: String, val facing: String, val focalMm: Float?, val equivMm: Int?, val megapixels: Float?,
        val level: String, val manualSensor: Boolean, val raw: Boolean, val manualFocus: Boolean, val focusCalibrated: Boolean,
        val minExpS: Double?, val maxExpS: Double?, val isoMin: Int?, val isoMax: Int?, val isoAnalogMax: Int?,
        val maxFrameS: Double?, val aperture: Float?, val physical: List<String>,
    ) {
        /** Longest exposure before stars visibly trail, by the "500 rule" for this lens. */
        val trailFreeS get() = equivMm?.takeIf { it > 0 }?.let { 500.0 / it }
    }

    fun read(ctx: Context): List<Cam> {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return emptyList()
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        // Lenses hidden behind a "logical" camera (wide + ultra-wide + tele as one) are listed too
        val physical = ids.flatMap { id -> runCatching { cm.getCameraCharacteristics(id).physicalCameraIds.toList() }.getOrDefault(emptyList()) }
        return (ids + physical).distinct().mapNotNull { id -> runCatching { cam(cm, id, ids) }.getOrNull() }
    }

    private fun cam(cm: CameraManager, id: String, top: List<String>): Cam {
        val c = cm.getCameraCharacteristics(id)
        val caps = c.get(CC.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val focal = c.get(CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        val size = c.get(CC.SENSOR_INFO_PHYSICAL_SIZE)
        val equiv = if (focal != null && size != null && size.width > 0) (focal * 43.27f / sqrt(size.width * size.width + size.height * size.height)).roundToInt() else null
        val px = c.get(CC.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val exp = c.get(CC.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = c.get(CC.SENSOR_INFO_SENSITIVITY_RANGE)
        val rawOut = c.get(CC.SCALER_STREAM_CONFIGURATION_MAP)?.outputFormats?.contains(ImageFormat.RAW_SENSOR) == true
        return Cam(
            id = id + if (id !in top) " (lens inside a combined camera)" else "",
            facing = when (c.get(CC.LENS_FACING)) { CM.LENS_FACING_BACK -> "back"; CM.LENS_FACING_FRONT -> "front"; else -> "external" },
            focalMm = focal, equivMm = equiv,
            megapixels = px?.let { (it.width * it.height / 100_000f).roundToInt() / 10f },
            level = when (c.get(CC.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CM.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"; CM.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
                CM.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"; CM.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level 3"
                CM.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"; else -> "?"
            },
            manualSensor = CM.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            raw = CM.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps || rawOut,
            manualFocus = (c.get(CC.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f,
            focusCalibrated = c.get(CC.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) != CM.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED,
            minExpS = exp?.lower?.let { it / 1e9 }, maxExpS = exp?.upper?.let { it / 1e9 },
            isoMin = iso?.lower, isoMax = iso?.upper, isoAnalogMax = c.get(CC.SENSOR_MAX_ANALOG_SENSITIVITY),
            maxFrameS = c.get(CC.SENSOR_INFO_MAX_FRAME_DURATION)?.let { it / 1e9 },
            aperture = c.get(CC.LENS_INFO_AVAILABLE_APERTURES)?.minOrNull(),
            physical = c.physicalCameraIds.toList(),
        )
    }

    private fun secs(s: Double?) = when {
        s == null -> "?"
        s >= 1 -> "%.1f s".format(s).replace(".0 s", " s")
        else -> "1/" + (1 / s).roundToInt() + " s"
    }

    /** One line on what this camera could do for the night sky. */
    fun verdict(c: Cam): String {
        val e = c.maxExpS ?: 0.0
        return when {
            !c.manualSensor && c.level == "legacy" -> "No manual control for apps – only the phone's own camera app can do night shots"
            !c.manualSensor -> "Apps can't set exposure directly – Astro mode would be limited to the phone's automatic night handling"
            e >= 10 -> "Good: single long exposures possible – Milky Way and aurora both workable, stacking cleans up the noise"
            e >= 2 -> "Workable: aurora in one shot; Milky Way by stacking many ${secs(e)} frames"
            e >= 0.5 -> "Short exposures only (${secs(e)}): aurora OK on bright nights; Milky Way needs heavy stacking (50+ frames)"
            else -> "Very short exposures only (${secs(e)}): stacking is the only route, and results will be noisy"
        }
    }

    fun report(ctx: Context): String {
        val cams = read(ctx)
        if (cams.isEmpty()) return "Couldn't read the cameras."
        val b = StringBuilder("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n")
        cams.sortedWith(compareBy({ it.facing != "back" }, { it.id })).forEach { c ->
            b.append("\nCamera ${c.id} · ${c.facing}")
            c.equivMm?.let { b.append(" · ${it} mm equiv") }; c.megapixels?.let { b.append(" · $it MP") }
            c.aperture?.let { b.append(" · f/").append("%.1f".format(it)) }
            b.append("\n  Hardware level: ${c.level}")
            if (c.physical.isNotEmpty()) b.append(" · combines cameras ${c.physical.joinToString(", ")}")
            b.append("\n  Manual exposure/ISO: ${if (c.manualSensor) "yes" else "no"} · RAW: ${if (c.raw) "yes" else "no"}")
            b.append(" · Manual focus: ${if (c.manualFocus) (if (c.focusCalibrated) "yes" else "yes (infinity uncalibrated)") else "no (fixed focus)"}")
            b.append("\n  Exposure: ${secs(c.minExpS)} to ${secs(c.maxExpS)}")
            c.maxFrameS?.let { b.append(" (frame up to ${secs(it)})") }
            b.append("\n  ISO: ${c.isoMin ?: "?"} to ${c.isoMax ?: "?"}")
            c.isoAnalogMax?.let { b.append(" (true sensor gain to $it, beyond that is digital)") }
            c.trailFreeS?.let { b.append("\n  Stars start to trail after about ${secs(it)} on this lens") }
            b.append("\n  → ").append(verdict(c)).append('\n')
        }
        b.append("\nThese are what the cameras advertise; a test shot is still needed to confirm the phone honours the longest exposure.")
        return b.toString()
    }
}
